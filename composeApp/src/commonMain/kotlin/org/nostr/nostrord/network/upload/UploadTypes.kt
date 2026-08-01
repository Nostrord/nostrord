package org.nostr.nostrord.network.upload

import org.nostr.nostrord.network.createHttpClient

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

/** One HTTP client for every media host, shared by the NIP-96 and Blossom paths. */
object UploadClient {
    internal val client by lazy { createHttpClient() }
}

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
