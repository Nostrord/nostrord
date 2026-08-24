package org.nostr.nostrord.utils

/** Length of the sha256 hex a Blossom url is keyed by. */
private const val SHA256_HEX_LENGTH = 64

/** The sha256 a Blossom url addresses its blob by, or null when the path isn't one. */
fun blossomHashFromUrl(url: String): String? {
    val segment = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
    val candidate = segment.substringBefore('.').lowercase()
    val isHex = candidate.length == SHA256_HEX_LENGTH && candidate.all { it in '0'..'9' || it in 'a'..'f' }
    return candidate.takeIf { isHex }
}

/**
 * The same blob on [servers], for when the host in [url] won't serve it to the caller: Blossom
 * addresses a blob by its sha256, so an uploader's mirrors hold the identical file under the
 * identical name. The host in [url] is left out (it already answered), the path extension is kept
 * so a server can label the response, and [limit] caps how far one lookup fans the hash out.
 * Empty when [url] is not hash-keyed. Verify the bytes against the hash before trusting them.
 */
fun blossomMirrorUrls(
    url: String,
    servers: List<String>,
    limit: Int = 4,
): List<String> {
    val hash = blossomHashFromUrl(url) ?: return emptyList()
    val path = url.substringBefore('?').substringBefore('#')
    val extension = path.substringAfterLast('/').substringAfterLast('.', "")
    val suffix = if (extension.isNotEmpty() && extension.length <= 5) ".$extension" else ""
    val origin = path.split('/').take(3).joinToString("/")
    return servers
        .map { it.trimEnd('/') }
        .filter { it.isNotEmpty() }
        .distinct()
        .filter { !it.equals(origin, ignoreCase = true) }
        .take(limit)
        .map { "$it/$hash$suffix" }
}
