package org.nostr.nostrord.utils

/**
 * Name to save the file at [url] under. Media hosts that key blobs by hash serve them under a
 * generic object name (blossom.primal.net answers a `.ogg` request with a redirect to a `.bin`
 * object), so whoever names the file from the URL the bytes finally came from writes something
 * no app opens. The path name wins; [mimeType] (the response Content-Type) supplies the
 * extension only when the path carries none or carries the `.bin` placeholder. [fallbackBase]
 * names a URL whose path ends in a slash.
 */
fun downloadFileName(
    url: String,
    mimeType: String? = null,
    fallbackBase: String = "file",
): String {
    val segment = url.substringBefore('?').substringBefore('#').substringAfterLast('/').trim()
    val pathExt = segment.substringAfterLast('.', "").lowercase().takeIf { it.isPlausibleExtension() }
    // Only a real extension is stripped off the base, so a host name keeps its dots.
    val base = (if (pathExt != null) segment.substringBeforeLast('.') else segment).ifBlank { fallbackBase }
    val ext =
        if (pathExt == null || pathExt == PLACEHOLDER_EXT) {
            extensionForMimeType(mimeType) ?: pathExt
        } else {
            pathExt
        }
    return if (ext == null) base else "$base.$ext"
}

/**
 * Extension for a Content-Type, or null when the type says nothing useful (`application/octet-stream`,
 * a wildcard, or a subtype that isn't a usable suffix).
 */
fun extensionForMimeType(mimeType: String?): String? {
    val mime =
        mimeType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: return null
    knownMimeExtensions[mime]?.let { return it }
    if (mime.endsWith("/octet-stream")) return null
    return mime.substringAfter('/', "").substringBefore('+').takeIf { it.isPlausibleExtension() }
}

/** The extension hosts hand out when they store every blob under one generic object name. */
private const val PLACEHOLDER_EXT = "bin"

/** Subtypes whose name is not the conventional extension. */
private val knownMimeExtensions =
    mapOf(
        "audio/mpeg" to "mp3",
        "audio/mp3" to "mp3",
        "audio/mp4" to "m4a",
        "audio/x-m4a" to "m4a",
        "audio/x-wav" to "wav",
        "audio/wave" to "wav",
        "audio/vnd.wave" to "wav",
        "audio/x-flac" to "flac",
        "audio/webm" to "weba",
        "image/jpeg" to "jpg",
        "image/svg+xml" to "svg",
        "video/quicktime" to "mov",
        "video/x-matroska" to "mkv",
        "text/plain" to "txt",
    )

/** A file extension is short and alphanumeric; anything else (a domain, a wildcard) is not one. */
private fun String.isPlausibleExtension(): Boolean = length in 1..5 && all { it.isLetterOrDigit() }
