package io.github.mccitiesclone.paperclip.editor

import io.github.mccitiesclone.paperclip.PaperclipConfig
import io.github.mccitiesclone.paperclip.discord.DesiredDiscordRole
import io.github.mccitiesclone.paperclip.discord.DiscordRole
import io.github.mccitiesclone.paperclip.luckperms.DesiredGroup
import io.github.mccitiesclone.paperclip.luckperms.GroupInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.logging.Logger

class EditorClient(
    private val config: PaperclipConfig,
    dataFolder: Path,
    private val logger: Logger,
    private val availableGroups: () -> List<GroupInfo>,
    private val availableDiscordRoles: () -> List<DiscordRole>,
) {
    private val client = editorHttpClient(config.editor, dataFolder, logger)
    private val bytebin = BytebinClient(client, config.editor.bytebinUrl)
    private val json = Json { ignoreUnknownKeys = true }
    private val activeSessions = ConcurrentHashMap<String, ActiveEditorSession>()
    private val pendingTrust = ConcurrentHashMap<String, PendingEditorTrust>()

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

        return createBytesocksChannel()
            .thenCompose { channelId ->
                val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                val initialPayload = initialPayloadJson(channelId, keyPair.public).toString()

                uploadPayload(initialPayload)
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
    }

    fun trust(nonce: String): String? {
        val pending = pendingTrust.remove(nonce) ?: return null
        val session = pending.session
        val editorKey = pending.editorKey
        if (session.editorNonce != nonce || !sameKey(session.editorPublicKey, editorKey)) {
            logger.warning("Ignored stale editor trust nonce $nonce for session ${session.bytebinId}")
            return null
        }
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
            .header("User-Agent", "DiscordPaperclip/editor")
            .header("Origin", config.editor.baseUrl)
            .buildAsync(uri, SocketListener(session))
            .thenApply { socket ->
                session.socket = socket
                socket
            }
            .exceptionally { throwable ->
                throw describeSocketFailure(uri, throwable)
            }
    }

    private fun createBytesocksChannel(): CompletableFuture<String> {
        val request = HttpRequest.newBuilder(bytesocksHttpUri("create"))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", "DiscordPaperclip/editor")
            .GET()
            .build()

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                if (response.statusCode() !in 200..299) {
                    throw IllegalStateException("bytesocks returned HTTP ${response.statusCode()} when creating a channel")
                }
                parseCreateChannelResponse(response)
            }
    }

    private fun bytesocksHttpUri(path: String): URI {
        val baseUri = URI.create(config.editor.bytesocksUrl.trimEnd('/'))
        val scheme = when (baseUri.scheme?.lowercase()) {
            "wss" -> "https"
            "ws" -> "http"
            "https", "http" -> baseUri.scheme
            else -> throw IllegalStateException("editor.bytesocks-url must use ws, wss, http, or https")
        }
        val basePath = baseUri.path?.trimEnd('/').orEmpty()
        val requestPath = "$basePath/${path.trimStart('/')}"
        return URI(
            scheme,
            baseUri.userInfo,
            baseUri.host,
            baseUri.port,
            requestPath,
            baseUri.query,
            null,
        )
    }

    private fun parseCreateChannelResponse(response: HttpResponse<String>): String {
        val locationKey = response.headers().firstValue("Location")
            .map(::extractPathKey)
            .filter { it.isNotBlank() }
            .orElse(null)
        if (locationKey != null) {
            return locationKey
        }

        val body = response.body().orEmpty().trim()
        if (body.isBlank()) {
            throw IllegalStateException("bytesocks create response did not include a Location header or response body")
        }

        return json.parseToJsonElement(body).jsonObject["key"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("bytesocks create response did not include Location or key")
    }

    private fun extractPathKey(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        if (!trimmed.contains('/')) {
            return trimmed
        }
        return runCatching {
            URI.create(trimmed).path.trim('/').substringAfterLast('/')
        }.getOrElse {
            trimmed.substringAfterLast('/')
        }
    }

    private fun describeSocketFailure(uri: URI, throwable: Throwable): IllegalStateException {
        val cause = unwrapCompletion(throwable)
        if (cause is WebSocketHandshakeException) {
            val response = cause.response
            return IllegalStateException(
                "bytesocks WebSocket handshake failed for $uri: HTTP ${response.statusCode()}${headerSummary(response.headers())}. " +
                    "Check editor.bytesocks-url points at a WebSocket relay, not the hosted editor web app or bytebin URL.",
                cause,
            )
        }
        return IllegalStateException("bytesocks WebSocket connection failed for $uri: ${cause.message ?: cause.javaClass.simpleName}", cause)
    }

    private fun headerSummary(headers: HttpHeaders): String {
        val contentType = headers.firstValue("content-type").orElse(null)
        val server = headers.firstValue("server").orElse(null)
        val parts = listOfNotNull(
            contentType?.let { "content-type=$it" },
            server?.let { "server=$it" },
        )
        return if (parts.isEmpty()) "" else " (${parts.joinToString(", ")})"
    }

    private fun unwrapCompletion(throwable: Throwable): Throwable =
        when (throwable) {
            is CompletionException -> throwable.cause?.let(::unwrapCompletion) ?: throwable
            is ExecutionException -> throwable.cause?.let(::unwrapCompletion) ?: throwable
            else -> throwable
        }

    private fun uploadPayload(payload: String): CompletableFuture<String> =
        bytebin.uploadJson(payload)

    private fun downloadPayload(payloadId: String): CompletableFuture<String> =
        bytebin.downloadJson(payloadId)

    private fun initialPayloadJson(channelId: String, publicKey: PublicKey): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("paperclip-editor-session"))
            put("createdAt", JsonPrimitive(Instant.now().epochSecond))
            put("expiresAt", JsonPrimitive(Instant.now().plusSeconds(config.editor.sessionTtlSeconds).epochSecond))
            put("channelId", JsonPrimitive(channelId))
            put("serverPublicKey", JsonPrimitive(encodeKey(publicKey)))
            put("availableGroups", availableGroupsJson())
            put("groups", groupsJson())
            put("availableDiscordRoles", availableDiscordRolesJson())
            put("config", editableConfigJson())
        }

    private fun availableGroupsJson(): JsonArray =
        JsonArray(
            (availableGroups().map { it.name } + config.groupRoleMap.keys + config.roleGroupMap.values)
                .filter { it.isNotBlank() }
                .sorted()
                .map { JsonPrimitive(it) }
        )

    private fun groupsJson(): JsonArray =
        JsonArray(
            availableGroups()
                .filter { it.name.isNotBlank() }
                .map { group ->
                    buildJsonObject {
                        put("name", JsonPrimitive(group.name))
                        put("displayName", JsonPrimitive(group.displayName.orEmpty()))
                        put("weight", JsonPrimitive(group.weight))
                    }
                }
        )

    private fun availableDiscordRolesJson(): JsonArray =
        JsonArray(
            availableDiscordRoles()
                .filter { it.id.isNotBlank() && it.name.isNotBlank() }
                .map { role ->
                    buildJsonObject {
                        put("id", JsonPrimitive(role.id))
                        put("name", JsonPrimitive(role.name))
                        put("color", JsonPrimitive(role.color))
                        put("position", JsonPrimitive(role.position))
                    }
                }
        )

    private fun editableConfigJson(): JsonObject =
        buildJsonObject {
            put("groupRoleMap", JsonObject(config.groupRoleMap.mapValues { JsonPrimitive(it.value) }))
            put("roleGroupMap", JsonObject(config.roleGroupMap.mapValues { JsonPrimitive(it.value) }))
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
        val roleGroupMap = configObject["roleGroupMap"]?.jsonObject
            ?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }
            ?.filterKeys { it.isNotBlank() }
            ?.filterValues { it.isNotBlank() }
            ?: emptyMap()

        val managedGroups = root["groups"]?.jsonArray
            ?.mapNotNull { element ->
                val group = element.jsonObject
                val name = group["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (name.isBlank()) {
                    null
                } else {
                    DesiredGroup(
                        name = name,
                        displayName = group["displayName"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null },
                    )
                }
            }
            ?: emptyList()

        val managedDiscordRoles = root["discordRoles"]?.jsonArray
            ?.mapNotNull { element ->
                val role = element.jsonObject
                val name = role["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (name.isBlank()) {
                    null
                } else {
                    DesiredDiscordRole(
                        id = role["id"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null },
                        name = name,
                        color = role["color"]?.jsonPrimitive?.intOrNull ?: 0,
                    )
                }
            }
            ?: emptyList()

        return EditorResult(groupRoleMap, roleGroupMap, linkedAccounts, managedGroups, managedDiscordRoles)
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
        pendingTrust.entries.removeIf { it.value.session === session }
        session.editorPublicKey = editorKey
        session.editorNonce = nonce

        val trusted = fingerprint(editorKey) in config.editor.trustedEditorKeys
        session.trusted = trusted
        if (trusted) {
            session.sendHelloReply(accepted = true, trustRequired = false)
        } else {
            pendingTrust[nonce] = PendingEditorTrust(session, editorKey)
            session.sendHelloReply(accepted = false, trustRequired = true)
            logger.info("Editor session ${session.bytebinId} is waiting for trust. Run /paperclip editor trust $nonce")
        }
    }

    private fun handleRequestChanges(session: ActiveEditorSession, packet: JsonObject) {
        val payloadId = packet["payloadId"]?.jsonPrimitive?.contentOrNull ?: return
        downloadPayload(payloadId)
            .thenApply(::parseResult)
            .thenApply(session.applyChanges)
            .thenCompose { refreshed -> uploadPayload(refreshedPayloadJson(refreshed).toString()) }
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

    private data class PendingEditorTrust(
        val session: ActiveEditorSession,
        val editorKey: PublicKey,
    )

    /**
     * The payload returned to the editor after changes are applied. Mirrors the session payload's
     * data so the editor can refresh its tables and role-management page with freshly created
     * groups/roles, new Discord role ids, and updated weights/positions.
     */
    private fun refreshedPayloadJson(result: EditorResult): JsonObject =
        buildJsonObject {
            put(
                "config",
                buildJsonObject {
                    put("groupRoleMap", JsonObject(result.groupRoleMap.mapValues { JsonPrimitive(it.value) }))
                    put("roleGroupMap", JsonObject(result.roleGroupMap.mapValues { JsonPrimitive(it.value) }))
                    put("linkedAccounts", JsonObject(result.linkedAccounts.mapValues { JsonPrimitive(it.value) }))
                },
            )
            put("availableGroups", availableGroupsJson())
            put("groups", groupsJson())
            put("availableDiscordRoles", availableDiscordRolesJson())
        }
}

data class EditorSession(
    val sessionId: String,
    val editorUrl: String,
    val channelId: String,
)

data class EditorResult(
    val groupRoleMap: Map<String, String>,
    val roleGroupMap: Map<String, String>,
    val linkedAccounts: Map<String, String>,
    val managedGroups: List<DesiredGroup> = emptyList(),
    val managedDiscordRoles: List<DesiredDiscordRole> = emptyList(),
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

private fun sameKey(first: PublicKey?, second: PublicKey): Boolean =
    first?.encoded?.contentEquals(second.encoded) == true
