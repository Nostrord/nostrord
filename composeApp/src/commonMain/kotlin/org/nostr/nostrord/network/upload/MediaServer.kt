package org.nostr.nostrord.network.upload

/**
 * Where uploads go, picked in Settings → Media.
 *
 * [Blossom] is a list rather than a single host: the blob is uploaded to the first server
 * that accepts it and mirrored to the rest, so one host going away doesn't take the media
 * with it. [Nip96] is a single host that owns the file.
 */
sealed interface MediaUploadService {
    data object Blossom : MediaUploadService

    data class Nip96(val url: String) : MediaUploadService
}

/** NIP-96 hosts offered in the picker. The first is the default for a fresh install. */
val NIP96_SERVICES: List<String> =
    listOf(
        "https://nostr.build",
        "https://mockingyou.com",
        "https://nostpic.com",
        "https://nostrcheck.me",
        "https://nostrmedia.com",
        "https://files.sovbit.host",
    )

val DEFAULT_NIP96_SERVICE: String = NIP96_SERVICES.first()

/** Free Blossom servers offered as one-tap additions, and the list a new user starts with. */
val RECOMMENDED_BLOSSOM_SERVERS: List<String> =
    listOf(
        "https://blossom.band",
        "https://blossom.primal.net",
        "https://nostr.media",
        "https://blossom.nostr.build",
        "https://nostr.download",
    )

val DEFAULT_BLOSSOM_SERVERS: List<String> = RECOMMENDED_BLOSSOM_SERVERS.take(3)

/**
 * Where an encrypted DM attachment goes when the user's own media service refuses it: media
 * hosts such as nostr.build only take images, video and audio, and the ciphertext is none of
 * those. A host other NIP-17 clients already use for the same purpose.
 */
const val ENCRYPTED_DM_FALLBACK_BLOSSOM: String = "https://blossom.jumble.social"

/**
 * Normalize a user-typed server address to a comparable origin: adds `https://` when the
 * scheme is missing, drops the path/trailing slash, lowercases the host. Returns null when
 * the input can't be a server URL, so the caller can reject it with one check.
 */
fun normalizeMediaServerUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    val withScheme =
        when {
            trimmed.startsWith("https://", ignoreCase = true) -> "https://" + trimmed.substring("https://".length)
            trimmed.startsWith("http://", ignoreCase = true) -> return null // uploads must be over TLS
            trimmed.contains("://") -> return null // ws://, ftp://, … are not media servers
            else -> "https://$trimmed"
        }
    val rest = withScheme.removePrefix("https://")
    // Everything after the first slash is a path — servers are identified by origin only.
    val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
    if (authority.isEmpty()) return null
    val host = authority.substringBefore(':')
    // A bare word is a typo, not a host; require a dot and no whitespace.
    if (!host.contains('.') || host.any { it.isWhitespace() }) return null
    if (host.startsWith('.') || host.endsWith('.')) return null
    return "https://" + authority.lowercase()
}

/** Display label for a server: its host, which is what the user recognizes. */
fun mediaServerDisplayName(url: String): String = url.removePrefix("https://").trimEnd('/').substringBefore('/')
