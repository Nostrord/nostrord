package org.nostr.nostrord.network.upload

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result

/** Signs a NIP-98 authorization for (url, method). Returns null when signed out. */
typealias Nip98AuthBuilder = suspend (url: String, method: String) -> String?

/**
 * Uploads to any NIP-96 host. The upload endpoint is discovered from
 * `<server>/.well-known/nostr/nip96.json` (`api_url`) rather than hardcoded, which is what
 * lets one implementation serve nostr.build, nostrcheck.me, sovbit and the rest.
 *
 * The response carries a NIP-94 event whose tags hold the URL and metadata; those map onto
 * [UploadResult]. Hosts that answer in nostr.build's older `{status, data:[…]}` shape are
 * still parsed, since that is what its v2 endpoint returns.
 */
object Nip96Uploader {
    private val responseJson = Json { ignoreUnknownKeys = true }

    // api_url per host. Discovery is a round-trip we only want to pay once per session.
    private val apiUrlCache = mutableMapOf<String, String>()

    suspend fun upload(
        serverUrl: String,
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        buildAuthHeader: Nip98AuthBuilder,
    ): Result<UploadResult> {
        val apiUrl =
            try {
                discoverApiUrl(serverUrl)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                return Result.Error(
                    AppError.Unknown("${mediaServerDisplayName(serverUrl)} is not reachable: ${e.message}", e),
                )
            } ?: return Result.Error(
                AppError.Unknown(
                    "${mediaServerDisplayName(serverUrl)} does not advertise a NIP-96 upload endpoint. " +
                        "Pick another service in Settings → Media.",
                ),
            )

        val authHeader =
            try {
                buildAuthHeader(apiUrl, "POST")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Signer failure (bunker unreachable/timeout, extension denial) — surface
                // the actual cause; "not authenticated" would be false and misleading.
                return Result.Error(AppError.Unknown("Upload authorization failed: ${e.message}", e))
            } ?: return Result.Error(AppError.Auth.NotAuthenticated)

        // Blob-ref uploads can't be retried — the JS cache entry is consumed on first use
        val maxAttempts = if (isBlobRef(filename)) 1 else 3
        var lastException: Throwable? = null
        repeat(maxAttempts) { attempt ->
            try {
                return doUpload(apiUrl, bytes, filename, mimeType, authHeader)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastException = e
                if (attempt < maxAttempts - 1) delay(500)
            }
        }
        return Result.Error(AppError.Unknown("Upload failed: ${lastException?.message}", lastException))
    }

    /** `api_url` from the host's NIP-96 document, or null when it serves none. */
    suspend fun discoverApiUrl(serverUrl: String): String? {
        val origin = serverUrl.trimEnd('/')
        apiUrlCache[origin]?.let { return it }
        val body = UploadClient.client.get("$origin/.well-known/nostr/nip96.json").bodyAsText()
        val apiUrl =
            runCatching {
                responseJson.parseToJsonElement(body).jsonObject["api_url"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        // Spec allows a relative api_url ("/api/upload").
        val absolute = if (apiUrl.startsWith("http")) apiUrl else origin + "/" + apiUrl.removePrefix("/")
        apiUrlCache[origin] = absolute
        return absolute
    }

    // Throws on IO/connection errors so the caller can retry.
    // Returns Result.Error for server-side errors (no retry needed).
    private suspend fun doUpload(
        apiUrl: String,
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        authHeader: String,
    ): Result<UploadResult> {
        val (statusCode, text) = executeUpload(apiUrl, bytes, filename, mimeType, authHeader)
        val json = runCatching { responseJson.parseToJsonElement(text).jsonObject }.getOrNull()

        if (statusCode !in 200..299) {
            val msg = json?.get("message")?.jsonPrimitive?.contentOrNull ?: "HTTP $statusCode"
            return Result.Error(AppError.Unknown("Upload failed: $msg"))
        }
        if (json == null) return Result.Error(AppError.Unknown("Upload failed: unexpected response"))

        // A "processing" status means the file is accepted but not ready; we have no URL to
        // put in the message, so treat it as a failure rather than sending a dead link.
        val status = json["status"]?.jsonPrimitive?.contentOrNull
        if (status != null && status != "success") {
            val msg = json["message"]?.jsonPrimitive?.contentOrNull ?: status
            return Result.Error(AppError.Unknown("Upload failed: $msg"))
        }

        return parseNip94(json, bytes, mimeType)
            ?: parseLegacyNostrBuild(json, bytes, mimeType)
            ?: Result.Error(AppError.Unknown("Upload failed: no URL in response"))
    }

    /** NIP-96 proper: metadata lives in the tags of the returned NIP-94 (kind 1063) event. */
    private suspend fun parseNip94(
        json: JsonObject,
        bytes: ByteArray,
        mimeType: String,
    ): Result<UploadResult>? {
        val tags =
            json["nip94_event"]?.jsonObject?.get("tags")?.jsonArray
                ?: return null
        val values = mutableMapOf<String, String>()
        for (tag in tags) {
            val pair = tag.jsonArray
            val name = pair.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: continue
            val value = pair.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: continue
            if (!values.containsKey(name)) values[name] = value
        }
        val url = values["url"] ?: return null
        val resolvedMime = values["m"]?.takeIf { it.isNotBlank() } ?: mimeType
        val dim = values["dim"]?.split('x')
        var width = dim?.getOrNull(0)?.toIntOrNull()
        var height = dim?.getOrNull(1)?.toIntOrNull()
        if ((width == null || height == null) && resolvedMime.startsWith("image/")) {
            decodeImageDimensions(bytes, resolvedMime)?.let { (w, h) ->
                width = w
                height = h
            }
        }
        return Result.Success(
            UploadResult(
                url = url,
                mimeType = resolvedMime,
                sha256 = values["ox"] ?: values["x"],
                width = width,
                height = height,
                size = values["size"]?.toLongOrNull() ?: bytes.size.toLong(),
                thumbnailUrl = values["thumb"] ?: values["image"],
            ),
        )
    }

    /** nostr.build's v2 shape: `{status, data:[{url, mime, dimensions:{…}, …}]}`. */
    private suspend fun parseLegacyNostrBuild(
        json: JsonObject,
        bytes: ByteArray,
        mimeType: String,
    ): Result<UploadResult>? {
        val data = json["data"]
        val entry =
            when {
                data is JsonArray -> data.firstOrNull()?.jsonObject
                data is JsonObject -> data
                else -> null
            } ?: return null
        val url = entry["url"]?.jsonPrimitive?.contentOrNull ?: return null

        val dimensions = entry["dimensions"]?.jsonObject
        val resolvedMime =
            entry["mime"]?.jsonPrimitive?.contentOrNull
                ?: entry["type"]?.jsonPrimitive?.contentOrNull
                ?: mimeType
        var width = dimensions?.get("width")?.jsonPrimitive?.intOrNull
        var height = dimensions?.get("height")?.jsonPrimitive?.intOrNull
        // Fallback: some hosts omit dimensions. Decode them client-side from the bytes we
        // already have so our media always carries a NIP-68 `dim` and never shifts the feed
        // on the receiving end. Images only; videos keep their server poster.
        if ((width == null || height == null) && resolvedMime.startsWith("image/")) {
            decodeImageDimensions(bytes, resolvedMime)?.let { (w, h) ->
                width = w
                height = h
            }
        }
        return Result.Success(
            UploadResult(
                url = url,
                mimeType = resolvedMime,
                sha256 =
                entry["original_sha256"]?.jsonPrimitive?.contentOrNull
                    ?: entry["sha256"]?.jsonPrimitive?.contentOrNull,
                width = width,
                height = height,
                size = entry["size"]?.jsonPrimitive?.longOrNull,
                thumbnailUrl =
                entry["thumbnail"]?.jsonPrimitive?.contentOrNull
                    ?: entry["thumb"]?.jsonPrimitive?.contentOrNull,
            ),
        )
    }
}
