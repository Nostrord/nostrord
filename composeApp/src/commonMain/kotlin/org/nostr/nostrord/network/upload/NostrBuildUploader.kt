package org.nostr.nostrord.network.upload

import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import org.nostr.nostrord.network.createHttpClient
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result

const val MAX_UPLOAD_BYTES: Long = 20L * 1024 * 1024

const val SUPPORTED_FORMATS_MESSAGE =
    "Supported formats:\n" +
        "Images: jpg, png, gif, webp, avif\n" +
        "Video: mp4, mov, webm\n" +
        "Audio: mp3, ogg, wav, flac, m4a, aac, opus"

internal fun isBlobRef(s: String) = s.startsWith("nostrord-blob|")

/** Still-image extensions (avatars / banners). Single source for native pickers. */
internal val SUPPORTED_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "avif")

/** All upload extensions (image + video + audio). Keep in sync with mimeTypeForFilename. */
internal val SUPPORTED_MEDIA_EXTENSIONS =
    SUPPORTED_IMAGE_EXTENSIONS + setOf("mp4", "mov", "webm", "mp3", "ogg", "wav", "flac", "m4a", "aac", "opus")

internal val SUPPORTED_UPLOAD_MIMES =
    setOf(
        "image/jpeg",
        "image/png",
        "image/gif",
        "image/webp",
        "image/avif",
        "video/mp4",
        "video/quicktime",
        "video/webm",
        "audio/mpeg",
        "audio/ogg",
        "audio/wav",
        "audio/flac",
        "audio/mp4",
        "audio/aac",
        "audio/opus",
    )

internal fun isSupportedUploadMime(mime: String) = mime in SUPPORTED_UPLOAD_MIMES

private val responseJson = Json { ignoreUnknownKeys = true }

/**
 * Metadata returned by the upload server, used to build NIP-68 `imeta` tags.
 */
data class UploadResult(
    val url: String,
    val mimeType: String? = null,
    val sha256: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val size: Long? = null,
    // Server-generated poster image (nostr.build returns one for videos). Emitted
    // as the imeta `thumb` field so clients can preview a video without fetching
    // it (the web player uses it as the <video> poster).
    val thumbnailUrl: String? = null,
) {
    /**
     * Build a NIP-68 `imeta` tag from this upload result.
     * Only includes fields that are present.
     */
    fun toImetaTag(): List<String> = buildList {
        add("imeta")
        add("url $url")
        mimeType?.let { add("m $it") }
        sha256?.let { add("x $it") }
        if (width != null && height != null) add("dim ${width}x$height")
        size?.let { add("size $it") }
        thumbnailUrl?.let { add("thumb $it") }
    }
}

object NostrBuildUploader {
    internal val client by lazy { createHttpClient() }

    fun uploadUrl(serverUrl: String) = serverUrl.trimEnd('/') + "/api/v2/upload/files"

    /**
     * Upload a file to a nostr.build-compatible host with NIP-98 auth.
     * Returns [UploadResult] with URL and NIP-68 metadata (dimensions, hash, etc.).
     * [buildAuthHeader] receives (url, method) and must return "Nostr <base64>" or null.
     */
    suspend fun upload(
        serverUrl: String,
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        buildAuthHeader: Nip98AuthBuilder,
    ): Result<UploadResult> {
        val isCached = isBlobRef(filename)
        val uploadUrl = uploadUrl(serverUrl)

        val authHeader =
            try {
                buildAuthHeader(uploadUrl, "POST")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Signer failure (bunker unreachable/timeout, extension denial) — surface
                // the actual cause; "not authenticated" would be false and misleading.
                return Result.Error(AppError.Unknown("Upload authorization failed: ${e.message}", e))
            } ?: return Result.Error(AppError.Auth.NotAuthenticated)

        // Blob-ref uploads can't be retried — the JS cache entry is consumed on first use
        val maxAttempts = if (isCached) 1 else 3
        var lastException: Throwable? = null
        repeat(maxAttempts) { attempt ->
            try {
                return doUpload(uploadUrl, bytes, filename, mimeType, authHeader)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastException = e
                if (attempt < maxAttempts - 1) delay(500)
            }
        }
        return Result.Error(AppError.Unknown("Upload failed: ${lastException?.message}", lastException))
    }

    // Throws on IO/connection errors so the caller can retry.
    // Returns Result.Error for server-side errors (no retry needed).
    private suspend fun doUpload(
        uploadUrl: String,
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        authHeader: String,
    ): Result<UploadResult> {
        val (statusCode, text) = executeUpload(uploadUrl, bytes, filename, mimeType, authHeader)

        if (statusCode !in 200..299) {
            val msg =
                runCatching {
                    responseJson
                        .parseToJsonElement(text)
                        .jsonObject["message"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                }.getOrNull() ?: "HTTP $statusCode"
            return Result.Error(AppError.Unknown("Upload failed: $msg"))
        }

        val json = responseJson.parseToJsonElement(text).jsonObject

        val status = json["status"]?.jsonPrimitive?.contentOrNull
        if (status != "success") {
            val msg = json["message"]?.jsonPrimitive?.contentOrNull ?: text
            return Result.Error(AppError.Unknown("Upload failed: $msg"))
        }

        // nostr.build v2 returns data as array; handle object shape defensively
        val data = json["data"]
        val entry =
            when {
                data is JsonArray -> data.firstOrNull()?.jsonObject
                data is JsonObject -> data
                else -> null
            }
        val url =
            entry?.get("url")?.jsonPrimitive?.contentOrNull
                ?: return Result.Error(AppError.Unknown("Upload failed: no URL in response"))

        // Extract NIP-68 metadata from the response
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
        if ((width == null || height == null) && resolvedMime?.startsWith("image/") == true) {
            decodeImageDimensions(bytes, resolvedMime)?.let { (w, h) ->
                width = w
                height = h
            }
        }
        return Result.Success(
            UploadResult(
                url = url,
                mimeType =
                entry["mime"]?.jsonPrimitive?.contentOrNull
                    ?: entry["type"]?.jsonPrimitive?.contentOrNull,
                sha256 =
                entry["original_sha256"]?.jsonPrimitive?.contentOrNull
                    ?: entry["sha256"]?.jsonPrimitive?.contentOrNull,
                width = width,
                height = height,
                size = entry["size"]?.jsonPrimitive?.longOrNull,
                // nostr.build v2 returns a generated poster for video uploads.
                // Field name observed as "thumbnail"; fall back to "thumb".
                thumbnailUrl =
                entry["thumbnail"]?.jsonPrimitive?.contentOrNull
                    ?: entry["thumb"]?.jsonPrimitive?.contentOrNull,
            ),
        )
    }
}
