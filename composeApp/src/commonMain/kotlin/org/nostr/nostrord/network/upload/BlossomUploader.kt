package org.nostr.nostrord.network.upload

import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import org.nostr.nostrord.nostr.Crypto
import org.nostr.nostrord.nostr.toHexString
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result

/** Signs a kind:24242 authorization for (sha256, verb). Returns null when signed out. */
typealias BlossomAuthBuilder = suspend (sha256Hex: String, verb: String) -> String?

/** Lifetime of a kind:24242 token: long enough for a slow 20 MB upload, short enough to be useless if leaked. */
const val BLOSSOM_AUTH_TTL_SECONDS: Long = 600

/**
 * Uploads to a Blossom media server (BUD-02): `PUT <server>/upload` with the raw blob as
 * the body and a kind:24242 authorization event. The response is a blob descriptor
 * (`url`, `sha256`, `size`, `type`), which maps straight onto [UploadResult]. Blossom has
 * no notion of image dimensions or video posters, so `dim` is decoded client-side and
 * `thumb` is left off.
 */
object BlossomUploader {
    private val responseJson = Json { ignoreUnknownKeys = true }

    fun uploadUrl(serverUrl: String) = serverUrl.trimEnd('/') + "/upload"

    /**
     * Statuses from the BUD-06 pre-flight that mean the server doesn't implement the check,
     * not that it refuses this blob. Anything else is a real rejection worth failing on.
     */
    private val PREFLIGHT_UNSUPPORTED = setOf(404, 405, 501)

    /**
     * One kind:24242 token, bound to a blob hash. It carries no `server` tag, so the same
     * signature authorizes the upload AND every mirror: the user is asked to sign once per
     * file, not once per server.
     */
    data class Auth(
        val header: String,
        val sha256Hex: String,
    )

    /** Sign the upload token for [bytes]. One signer round-trip per file. */
    suspend fun createUploadAuth(
        bytes: ByteArray,
        buildAuthHeader: BlossomAuthBuilder,
    ): Result<Auth> {
        val sha256Hex = Crypto.sha256(bytes).toHexString()
        val header =
            try {
                buildAuthHeader(sha256Hex, "upload")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Signer failure (bunker unreachable/timeout, extension denial) — surface
                // the actual cause; "not authenticated" would be false and misleading.
                return Result.Error(AppError.Unknown("Upload authorization failed: ${e.message}", e))
            } ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return Result.Success(Auth(header, sha256Hex))
    }

    /**
     * Ask [servers] to take a copy of an already-uploaded blob (BUD-04). Best effort: a
     * server that refuses or is down is skipped, since the blob already lives at the
     * primary. Returns the servers that accepted it.
     */
    suspend fun mirror(
        servers: List<String>,
        blobUrl: String,
        auth: Auth,
    ): List<String> = servers.filter { ktorMirrorBlob(it, blobUrl, auth.header) }

    suspend fun upload(
        serverUrl: String,
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        auth: Auth,
    ): Result<UploadResult> {
        // Blob-ref uploads can't be retried — the JS cache entry is consumed on first use
        val maxAttempts = if (isBlobRef(filename)) 1 else 3
        var lastException: Throwable? = null
        repeat(maxAttempts) { attempt ->
            try {
                return doUpload(serverUrl, bytes, mimeType, auth.header, auth.sha256Hex)
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
        serverUrl: String,
        bytes: ByteArray,
        mimeType: String,
        authHeader: String,
        sha256Hex: String,
    ): Result<UploadResult> {
        val uploadUrl = uploadUrl(serverUrl)

        // Pre-flight first: a server that won't take this blob (too large, wrong type, out
        // of quota) says so before we push the whole body over a phone connection.
        val preflight = ktorPreflightUpload(uploadUrl, bytes.size.toLong(), mimeType, authHeader, sha256Hex)
        if (preflight != null && preflight !in 200..299 && preflight !in PREFLIGHT_UNSUPPORTED) {
            return Result.Error(AppError.Unknown("Upload failed: ${errorMessage(preflight, "")}"))
        }

        val (statusCode, text) = executePutUpload(uploadUrl, bytes, mimeType, authHeader, sha256Hex)

        if (statusCode !in 200..299) {
            return Result.Error(AppError.Unknown("Upload failed: ${errorMessage(statusCode, text)}"))
        }

        val entry =
            runCatching { responseJson.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: return Result.Error(AppError.Unknown("Upload failed: unexpected response from ${mediaServerDisplayName(serverUrl)}"))

        val url =
            entry["url"]?.jsonPrimitive?.contentOrNull
                ?: return Result.Error(AppError.Unknown("Upload failed: no URL in response"))

        val resolvedMime = entry["type"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: mimeType
        val dimensions =
            if (resolvedMime.startsWith("image/")) decodeImageDimensions(bytes, resolvedMime) else null

        return Result.Success(
            UploadResult(
                url = url,
                mimeType = resolvedMime,
                sha256 = entry["sha256"]?.jsonPrimitive?.contentOrNull ?: sha256Hex,
                width = dimensions?.first,
                height = dimensions?.second,
                size = entry["size"]?.jsonPrimitive?.longOrNull ?: bytes.size.toLong(),
            ),
        )
    }

    // Servers answer with either a JSON body or the X-Reason header, which the engine
    // folds into the same slot; a bare status is the last resort.
    private fun errorMessage(
        statusCode: Int,
        text: String,
    ): String {
        val fromJson =
            runCatching {
                val obj = responseJson.parseToJsonElement(text).jsonObject
                obj["message"]?.jsonPrimitive?.contentOrNull ?: obj["reason"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        return fromJson
            ?: text.takeIf { it.isNotBlank() && !it.trimStart().startsWith('{') && !it.trimStart().startsWith('<') }
            ?: when (statusCode) {
                401 -> "the server rejected the authorization"
                402 -> "this server requires payment"
                403 -> "the server refused the upload"
                413 -> "the file is too large for this server"
                415 -> "this server does not accept that file type"
                429 -> "rate limited, try again shortly"
                else -> "HTTP $statusCode"
            }
    }
}
