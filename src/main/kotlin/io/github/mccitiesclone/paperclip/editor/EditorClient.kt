package io.github.mccitiesclone.paperclip.editor

import io.github.mccitiesclone.paperclip.PaperclipConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

class EditorClient(
    private val config: PaperclipConfig,
    private val logger: Logger,
) {
    private val client = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val activeSessions = ConcurrentHashMap<String, ActiveEditorSession>()
    private val pendingTrust = ConcurrentHashMap<String, ActiveEditorSession>()

    fun createSession(applyChanges: (EditorResult) -> EditorResult): CompletableFuture<EditorSession> {
        if (config.editor.baseUrl.isBlank()) {
            return CompletableFuture.failedFuture(IllegalStateException("editor.base-url is not configured"))
        }
        if (config.editor.bytebinUrl.isBlank()) {
            return CompletableFuture.failedFuture(IllegalStateException("editor.bytebin-url is not configured"))
        }
        if (config.editor.bytesocksUrl.isBlank()) {
            return CompletableFuture.failedFuture(IllegalStateException("editor.bytesocks-url is not configured"))
        }

        val channelId = UUID.randomUUID().toString()
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val initialPayload = initialPayloadJson(channelId, keyPair.public).toString()

        return uploadPayload(initialPayload)
            .thenCompose { payloadId ->
                val session = ActiveEditorSession(
                    bytebinId = payloadId,
                    channelId = channelId,
                    privateKey = keyPair.private,
                    publicKey = keyPair.public,
                    applyChanges = applyChanges,
                )
                activeSessions[payloadId] = session
                connectSocket(session).thenApply {
                    EditorSession(
                        sessionId = payloadId,
                        editorUrl = "${config.editor.baseUrl}/?session=$payloadId",
                        channelId = channelId,
                    )
                }
            }
    }

    fun trust(nonce: String): String? {
        val session = pendingTrust.remove(nonce) ?: return null
        val editorKey = session.editorPublicKey ?: return null
        session.trusted = true
        session.sendHelloReply(accepted = true, trustRequired = false)
        val fingerprint = fingerprint(editorKey)
        logger.info("Trusted editor key $fingerprint for session ${session.bytebinId}")
        return fingerprint
    }

    fun shutdown() {
        activeSessions.values.forEach { session ->
            session.sendPong(disconnecting = true)
            session.close()
        }
        activeSessions.clear()
        pendingTrust.clear()
    }

    private fun connectSocket(session: ActiveEditorSession): CompletableFuture<WebSocket> {
        val uri = URI.create("${config.editor.bytesocksUrl.trimEnd('/')}/${session.channelId}")
        return client.newWebSocketBuilder()
            .buildAsync(uri, SocketListener(session))
            .thenApply { socket ->
                session.socket = socket
                socket
            }
    }

    private fun uploadPayload(payload: String): CompletableFuture<String> {
        val request = HttpRequest.newBuilder(URI.create(config.editor.bytebinUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                if (response.statusCode() !in 200..299) {
                    throw IllegalStateException("bytebin returned HTTP ${response.statusCode()}")
                }
                parsePayloadId(response.body())
            }
    }

    private fun downloadPayload(payloadId: String): CompletableFuture<String> {
        val request = HttpRequest.newBuilder(URI.create("${config.editor.bytebinUrl}/$payloadId"))
            .GET()
            .build()

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                if (response.statusCode() !in 200..299) {
                    throw IllegalStateException("bytebin returned HTTP ${response.statusCode()} for $payloadId")
                }
                response.body()
            }
    }

    private fun parsePayloadId(body: String): String {
        val root = json.parseToJsonElement(body).jsonObject
        return root["key"]?.jsonPrimitive?.contentOrNull
            ?: root["id"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("bytebin response did not include key or id")
    }

    private fun initialPayloadJson(channelId: String, publicKey: PublicKey): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("paperclip-editor-session"))
            put("createdAt", JsonPrimitive(Instant.now().epochSecond))
            put("expiresAt", JsonPrimitive(Instant.now().plusSeconds(config.editor.sessionTtlSeconds).epochSecond))
            put("channelId", JsonPrimitive(channelId))
            put("serverPublicKey", JsonPrimitive(encodeKey(publicKey)))
            put("config", editableConfigJson())
        }

    private fun editableConfigJson(): JsonObject =
        buildJsonObject {
            put("groupRoleMap", JsonObject(config.groupRoleMap.mapValues { JsonPrimitive(it.value) }))
            put("linkedAccounts", JsonObject(config.linkedAccounts.mapKeys { it.key.toString() }.mapValues { JsonPrimitive(it.value) }))
        }

    private fun parseResult(body: String): EditorResult {
        val root = json.parseToJsonElement(body).jsonObject
        val configObject = root["config"]?.jsonObject ?: root
        val groupRoleMap = configObject["groupRoleMap"]?.jsonObject
            ?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }
            ?.filterValues { it.isNotBlank() }
            ?: emptyMap()
        val linkedAccounts = configObject["linkedAccounts"]?.jsonObject
            ?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }
            ?.filterValues { it.isNotBlank() }
            ?: emptyMap()

        return EditorResult(groupRoleMap, linkedAccounts)
    }

    private inner class SocketListener(
        private val session: ActiveEditorSession,
    ) : WebSocket.Listener {
        private val buffer = StringBuilder()

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
            buffer.append(data)
            if (!last) {
                webSocket.request(1)
                return CompletableFuture.completedFuture(null)
            }

            val frame = buffer.toString()
            buffer.setLength(0)
            runCatching { handleFrame(session, frame) }
                .onFailure { logger.warning("Editor socket message failed: ${it.message}") }
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }
    }

    private fun handleFrame(session: ActiveEditorSession, frameText: String) {
        val frame = json.parseToJsonElement(frameText).jsonObject
        val msg = frame["msg"]?.jsonPrimitive?.contentOrNull ?: return
        val signature = frame["signature"]?.jsonPrimitive?.contentOrNull ?: return
        val packet = json.parseToJsonElement(msg).jsonObject
        val type = packet["type"]?.jsonPrimitive?.contentOrNull

        if (type == "hello") {
            handleHello(session, msg, signature, packet)
            return
        }

        val editorKey = session.editorPublicKey ?: return
        if (!session.trusted || !verify(msg, signature, editorKey)) {
            logger.warning("Rejected unsigned or untrusted editor packet for ${session.bytebinId}")
            return
        }

        when (type) {
            "ping" -> session.sendPong(disconnecting = false)
            "request-changes" -> handleRequestChanges(session, packet)
        }
    }

    private fun handleHello(session: ActiveEditorSession, msg: String, signature: String, packet: JsonObject) {
        val publicKeyText = packet["publicKey"]?.jsonPrimitive?.contentOrNull ?: return
        val editorKey = decodePublicKey(publicKeyText)
        if (!verify(msg, signature, editorKey)) {
            logger.warning("Rejected editor hello with invalid signature")
            return
        }

        val nonce = packet["nonce"]?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString().take(8)
        session.editorPublicKey = editorKey
        session.editorNonce = nonce

        val trusted = fingerprint(editorKey) in config.editor.trustedEditorKeys
        session.trusted = trusted
        if (trusted) {
            session.sendHelloReply(accepted = true, trustRequired = false)
        } else {
            pendingTrust[nonce] = session
            session.sendHelloReply(accepted = false, trustRequired = true)
            logger.info("Editor session ${session.bytebinId} is waiting for trust. Run /paperclip editor trust $nonce")
        }
    }

    private fun handleRequestChanges(session: ActiveEditorSession, packet: JsonObject) {
        val payloadId = packet["payloadId"]?.jsonPrimitive?.contentOrNull ?: return
        downloadPayload(payloadId)
            .thenApply(::parseResult)
            .thenApply(session.applyChanges)
            .thenCompose { refreshed -> uploadPayload(refreshed.toJson().toString()) }
            .whenComplete { refreshedPayloadId, throwable ->
                if (throwable != null) {
                    session.send(
                        buildJsonObject {
                            put("type", JsonPrimitive("changes-rejected"))
                            put("error", JsonPrimitive(throwable.message ?: "unknown error"))
                        }
                    )
                    return@whenComplete
                }
                session.send(
                    buildJsonObject {
                        put("type", JsonPrimitive("changes-applied"))
                        put("payloadId", JsonPrimitive(refreshedPayloadId))
                    }
                )
            }
    }

    private inner class ActiveEditorSession(
        val bytebinId: String,
        val channelId: String,
        val privateKey: PrivateKey,
        val publicKey: PublicKey,
        val applyChanges: (EditorResult) -> EditorResult,
    ) {
        @Volatile
        var socket: WebSocket? = null

        @Volatile
        var editorPublicKey: PublicKey? = null

        @Volatile
        var editorNonce: String? = null

        @Volatile
        var trusted: Boolean = false

        fun sendHelloReply(accepted: Boolean, trustRequired: Boolean) {
            send(
                buildJsonObject {
                    put("type", JsonPrimitive("hello-reply"))
                    put("accepted", JsonPrimitive(accepted))
                    put("trustRequired", JsonPrimitive(trustRequired))
                }
            )
        }

        fun sendPong(disconnecting: Boolean) {
            send(
                buildJsonObject {
                    put("type", JsonPrimitive("pong"))
                    put("disconnecting", JsonPrimitive(disconnecting))
                }
            )
        }

        fun send(packet: JsonObject) {
            val msg = packet.toString()
            val frame = buildJsonObject {
                put("msg", JsonPrimitive(msg))
                put("signature", JsonPrimitive(sign(msg, privateKey)))
            }.toString()
            socket?.sendText(frame, true)
        }

        fun close() {
            socket?.sendClose(WebSocket.NORMAL_CLOSURE, "session closed")
        }
    }

    private fun EditorResult.toJson(): JsonObject =
        buildJsonObject {
            put("groupRoleMap", JsonObject(groupRoleMap.mapValues { JsonPrimitive(it.value) }))
            put("linkedAccounts", JsonObject(linkedAccounts.mapValues { JsonPrimitive(it.value) }))
        }
}

data class EditorSession(
    val sessionId: String,
    val editorUrl: String,
    val channelId: String,
)

data class EditorResult(
    val groupRoleMap: Map<String, String>,
    val linkedAccounts: Map<String, String>,
)

private fun sign(message: String, privateKey: PrivateKey): String {
    val signature = Signature.getInstance("SHA256withRSA")
    signature.initSign(privateKey)
    signature.update(message.toByteArray(StandardCharsets.UTF_8))
    return Base64.getEncoder().encodeToString(signature.sign())
}

private fun verify(message: String, encodedSignature: String, publicKey: PublicKey): Boolean =
    runCatching {
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initVerify(publicKey)
        signature.update(message.toByteArray(StandardCharsets.UTF_8))
        signature.verify(Base64.getDecoder().decode(encodedSignature))
    }.getOrDefault(false)

private fun encodeKey(publicKey: PublicKey): String =
    Base64.getEncoder().encodeToString(publicKey.encoded)

private fun decodePublicKey(encoded: String): PublicKey {
    val bytes = Base64.getDecoder().decode(encoded)
    return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes))
}

private fun fingerprint(publicKey: PublicKey): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
    return digest.joinToString(":") { "%02x".format(it) }
}
