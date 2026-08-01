package org.nostr.nostrord.network.upload

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result

/**
 * Routes an upload to the configured service, hiding the protocol difference (NIP-96's
 * multipart POST vs Blossom's `PUT /upload`) from every call site. Both paths return the
 * same [UploadResult] so imeta tags are built once.
 */
object MediaUploader {
    suspend fun upload(
        service: MediaUploadService,
        blossomServers: List<String>,
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        buildNip98AuthHeader: Nip98AuthBuilder,
        buildBlossomAuthHeader: BlossomAuthBuilder,
    ): Result<UploadResult> = when (service) {
        is MediaUploadService.Nip96 ->
            Nip96Uploader.upload(service.url, bytes, filename, mimeType, buildNip98AuthHeader)

        MediaUploadService.Blossom ->
            uploadToBlossomList(blossomServers, bytes, filename, mimeType, buildBlossomAuthHeader)
    }

    /**
     * Upload to the first server in [servers] that accepts the blob, then hand the rest a
     * copy via BUD-04 mirror. Trying the whole list matters more than it looks: one host
     * being down or full would otherwise fail an upload the user could have completed.
     * Mirroring is best effort and never turns a successful upload into an error.
     *
     * The kind:24242 token is signed once here and reused for every server and every
     * mirror. It is bound to the blob hash, not to a host, so re-signing per server would
     * only mean extra signer prompts (or bunker round-trips) for the same permission.
     */
    private suspend fun uploadToBlossomList(
        servers: List<String>,
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        buildAuthHeader: BlossomAuthBuilder,
    ): Result<UploadResult> {
        if (servers.isEmpty()) {
            return Result.Error(AppError.Unknown("No Blossom server configured. Add one in Settings → Media."))
        }

        val auth =
            when (val signed = BlossomUploader.createUploadAuth(bytes, buildAuthHeader)) {
                is Result.Success -> signed.data
                is Result.Error -> return Result.Error(signed.error)
            }

        var lastError: AppError? = null
        servers.forEachIndexed { index, server ->
            when (val result = BlossomUploader.upload(server, bytes, filename, mimeType, auth)) {
                is Result.Success -> {
                    val others = servers.filterIndexed { i, _ -> i != index }
                    if (others.isNotEmpty()) BlossomUploader.mirror(others, result.data.url, auth)
                    return result
                }

                is Result.Error -> lastError = result.error
            }
        }
        return Result.Error(lastError ?: AppError.Unknown("Upload failed: every Blossom server refused the file."))
    }
}

/**
 * Upload using the service selected in Settings → Media. The single entry point for UI code
 * on every platform; it wires the signer and the configuration so call sites stay one line.
 */
suspend fun uploadMedia(
    bytes: ByteArray,
    filename: String,
    mimeType: String,
): Result<UploadResult> {
    val settings = AppModule.mediaServerSettings
    return MediaUploader.upload(
        service = settings.service.value,
        blossomServers = settings.blossomServers.value,
        bytes = bytes,
        filename = filename,
        mimeType = mimeType,
        buildNip98AuthHeader = AppModule.nostrRepository::buildNip98AuthHeader,
        buildBlossomAuthHeader = AppModule.nostrRepository::buildBlossomAuthHeader,
    )
}

/**
 * Mime type for an upload, derived from the filename extension. Blob refs
 * ("nostrord-blob|<mime>|…") carry their own mime because the bytes are already cached.
 */
fun mimeTypeForFilename(filename: String): String {
    if (isBlobRef(filename)) return filename.split("|").getOrNull(1) ?: "application/octet-stream"
    return when (filename.substringAfterLast('.').lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "opus" -> "audio/opus"
        "avif" -> "image/avif"
        else -> "application/octet-stream"
    }
}
