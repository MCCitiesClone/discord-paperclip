package io.github.mccitiesclone.paperclip.link

import io.github.mccitiesclone.paperclip.PaperclipConfig
import io.github.mccitiesclone.paperclip.editor.BytebinClient
import io.github.mccitiesclone.paperclip.editor.editorHttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

class LinkService(
    config: PaperclipConfig,
    dataFolder: Path,
    logger: Logger,
    private val completeLink: (UUID, String) -> Unit,
) {
    private val bytebin = BytebinClient(editorHttpClient(config.editor, dataFolder, logger), config.editor.bytebinUrl)
    private val json = Json { ignoreUnknownKeys = true }
    private val consumedCodes = ConcurrentHashMap.newKeySet<String>()
    private val ttlSeconds = config.editor.sessionTtlSeconds

    fun createCode(minecraftUuid: UUID, minecraftName: String): CompletableFuture<LinkCode> {
        val expiresAt = Instant.now().plusSeconds(ttlSeconds).epochSecond
        val payload = buildJsonObject {
            put("type", JsonPrimitive("paperclip-link-request"))
            put("minecraftUuid", JsonPrimitive(minecraftUuid.toString()))
            put("minecraftName", JsonPrimitive(minecraftName))
            put("createdAt", JsonPrimitive(Instant.now().epochSecond))
            put("expiresAt", JsonPrimitive(expiresAt))
        }

        return bytebin.uploadJson(payload.toString())
            .thenApply { code -> LinkCode(code, expiresAt) }
    }

    fun completeCode(code: String, discordUserId: String): CompletableFuture<LinkResult> {
        val normalizedCode = code.trim()
        if (normalizedCode.isBlank()) {
            return CompletableFuture.completedFuture(LinkResult.Failed("Enter the code from Minecraft."))
        }
        if (!consumedCodes.add(normalizedCode)) {
            return CompletableFuture.completedFuture(LinkResult.Failed("That link code has already been used."))
        }

        return bytebin.downloadJson(normalizedCode)
            .thenApply { body -> parsePayload(body, discordUserId) }
            .exceptionally { throwable ->
                consumedCodes.remove(normalizedCode)
                LinkResult.Failed(throwable.message ?: "Could not read that link code.")
            }
    }

    private fun parsePayload(body: String, discordUserId: String): LinkResult {
        val root = json.parseToJsonElement(body).jsonObject
        if (root["type"]?.jsonPrimitive?.contentOrNull != "paperclip-link-request") {
            return LinkResult.Failed("That code is not a Paperclip link code.")
        }

        val expiresAt = root["expiresAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: return LinkResult.Failed("That link code is malformed.")
        if (Instant.now().epochSecond > expiresAt) {
            return LinkResult.Failed("That link code has expired. Run /link in Minecraft again.")
        }

        val minecraftUuid = root["minecraftUuid"]?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return LinkResult.Failed("That link code is malformed.")
        val minecraftName = root["minecraftName"]?.jsonPrimitive?.contentOrNull.orEmpty()

        completeLink(minecraftUuid, discordUserId)
        return LinkResult.Linked(minecraftUuid, minecraftName)
    }
}

data class LinkCode(
    val code: String,
    val expiresAt: Long,
)

sealed interface LinkResult {
    data class Linked(
        val minecraftUuid: UUID,
        val minecraftName: String,
    ) : LinkResult

    data class Failed(
        val reason: String,
    ) : LinkResult
}
