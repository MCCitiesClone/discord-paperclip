package io.github.mccitiesclone.paperclip.editor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.zip.GZIPOutputStream

class BytebinClient(
    private val client: HttpClient,
    bytebinUrl: String,
) {
    private val baseUrl = bytebinUrl.trim().trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    fun uploadJson(payload: String): java.util.concurrent.CompletableFuture<String> {
        return postJson(payload, gzip = true)
            .thenCompose { response ->
                if (response.statusCode() == 400 || response.statusCode() == 415 || response.statusCode() == 501) {
                    return@thenCompose postJson(payload, gzip = false)
                        .thenCompose { uncompressedResponse -> parseOrFallback(payload, uncompressedResponse) }
                }
                parseOrFallback(payload, response)
            }
    }

    private fun parseOrFallback(
        payload: String,
        response: HttpResponse<String>,
    ): java.util.concurrent.CompletableFuture<String> {
        if (response.statusCode() == 404 || response.statusCode() == 405) {
            return uploadJsonLegacy(payload)
        }
        return java.util.concurrent.CompletableFuture.completedFuture(parseUploadResponse(response))
    }

    private fun postJson(payload: String, gzip: Boolean): java.util.concurrent.CompletableFuture<HttpResponse<String>> {
        val body = if (gzip) {
            HttpRequest.BodyPublishers.ofByteArray(gzip(payload))
        } else {
            HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)
        }
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/post"))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json")
            .apply {
                if (gzip) {
                    header("Content-Encoding", "gzip")
                }
            }
            .POST(body)
            .build()

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
    }

    fun downloadJson(contentKey: String): java.util.concurrent.CompletableFuture<String> {
        val request = HttpRequest.newBuilder(contentUri(contentKey))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                if (response.statusCode() !in 200..299) {
                    throw IllegalStateException("bytebin returned HTTP ${response.statusCode()} for $contentKey")
                }
                response.body()
            }
    }

    private fun uploadJsonLegacy(payload: String): java.util.concurrent.CompletableFuture<String> {
        val request = HttpRequest.newBuilder(URI.create(baseUrl))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
            .build()

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                if (response.statusCode() !in 200..299) {
                    throw IllegalStateException("bytebin returned HTTP ${response.statusCode()}")
                }
                parseUploadResponse(response)
            }
    }

    private fun parseUploadResponse(response: HttpResponse<String>): String {
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("bytebin returned HTTP ${response.statusCode()}")
        }

        val locationKey = response.headers().firstValue("Location")
            .map(::extractKey)
            .filter { it.isNotBlank() }
            .orElse(null)
        if (locationKey != null) {
            return locationKey
        }

        val body = response.body().orEmpty().trim()
        if (body.isBlank()) {
            throw IllegalStateException("bytebin response did not include a Location header or response body")
        }

        val root = json.parseToJsonElement(body).jsonObject
        return root["key"]?.jsonPrimitive?.contentOrNull
            ?: root["id"]?.jsonPrimitive?.contentOrNull
            ?: root["location"]?.jsonPrimitive?.contentOrNull?.let(::extractKey)
            ?: throw IllegalStateException("bytebin response did not include Location, key, or id")
    }

    private fun contentUri(contentKey: String): URI {
        val encodedKey = URLEncoder.encode(extractKey(contentKey), StandardCharsets.UTF_8)
            .replace("+", "%20")
        return URI.create("$baseUrl/$encodedKey")
    }

    private fun extractKey(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        if (!trimmed.contains('/')) {
            return trimmed
        }
        return runCatching {
            val uri = URI.create(trimmed)
            uri.path.trim('/').substringAfterLast('/')
        }.getOrElse {
            trimmed.substringAfterLast('/')
        }
    }

    private fun gzip(payload: String): ByteArray {
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { gzip ->
            gzip.write(bytes)
        }
        return output.toByteArray()
    }

    companion object {
        private const val USER_AGENT = "DiscordPaperclip/editor"
    }
}
