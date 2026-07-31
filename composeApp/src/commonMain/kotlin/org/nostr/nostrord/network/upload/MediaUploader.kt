package org.nostr.nostrord.network.upload

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.utils.Result

/** Signs a NIP-98 authorization for (url, method). Returns null when signed out. */
typealias Nip98AuthBuilder = suspend (url: String, method: String) -> String?

/**
 * Routes an upload to whichever media server the user picked, hiding the protocol
 * difference (nostr.build's multipart v2 API vs Blossom's `PUT /upload`) from every
 * call site. Both paths return the same [UploadResult] so imeta tags are built once.
 */
object MediaUploader {
    suspend fun upload(
        server: MediaServer,
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        buildNip98AuthHeader: Nip98AuthBuilder,
        buildBlossomAuthHeader: BlossomAuthBuilder,
    ): Result<UploadResult> = when (server.protocol) {
        MediaServerProtocol.NostrBuild ->
            NostrBuildUploader.upload(server.url, bytes, filename, mimeType, buildNip98AuthHeader)

        MediaServerProtocol.Blossom ->
            BlossomUploader.upload(server.url, bytes, filename, mimeType, buildBlossomAuthHeader)
    }
}

/**
 * Upload to the media server selected in Settings → Media. The single entry point for UI
 * code on every platform; it wires the signer and the selection so call sites stay one line.
 */
suspend fun uploadMedia(
    bytes: ByteArray,
    filename: String,
    mimeType: String,
): Result<UploadResult> = MediaUploader.upload(
    server = AppModule.mediaServerSettings.selected.value,
    bytes = bytes,
    filename = filename,
    mimeType = mimeType,
    buildNip98AuthHeader = AppModule.nostrRepository::buildNip98AuthHeader,
    buildBlossomAuthHeader = AppModule.nostrRepository::buildBlossomAuthHeader,
)

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
