package org.nostr.nostrord.network.upload

import kotlinx.serialization.Serializable

/** Upload API a media server speaks. */
enum class MediaServerProtocol {
    /** nostr.build's v2 multipart API, NIP-98 auth. */
    NostrBuild,

    /** Blossom (BUD-02): `PUT /upload` with a raw body, kind:24242 auth. */
    Blossom,
}

/**
 * A media host the user can upload to. [url] is the origin (no trailing slash) and is
 * also the identity of the entry — selection and custom-server dedup key off it.
 */
@Serializable
data class MediaServer(
    val url: String,
    val name: String,
    val protocol: MediaServerProtocol,
    /** Shipped with the app: cannot be removed, and its name follows app updates. */
    val builtIn: Boolean = false,
)

/**
 * Servers offered out of the box. nostr.build stays first so the default upload path is
 * unchanged for existing users; the rest are free Blossom hosts.
 */
val BUILT_IN_MEDIA_SERVERS: List<MediaServer> =
    listOf(
        MediaServer("https://nostr.build", "nostr.build", MediaServerProtocol.NostrBuild, builtIn = true),
        MediaServer("https://blossom.band", "blossom.band", MediaServerProtocol.Blossom, builtIn = true),
        MediaServer("https://blossom.primal.net", "Primal", MediaServerProtocol.Blossom, builtIn = true),
        MediaServer("https://blossom.nostr.build", "nostr.build (Blossom)", MediaServerProtocol.Blossom, builtIn = true),
        MediaServer("https://nostr.download", "nostr.download", MediaServerProtocol.Blossom, builtIn = true),
    )

val DEFAULT_MEDIA_SERVER: MediaServer = BUILT_IN_MEDIA_SERVERS.first()

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
            trimmed.startsWith("https://", ignoreCase = true) -> "https://" + trimmed.removePrefix("https://").removePrefix("HTTPS://")
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

/** Display label for a custom server: its host, which is what the user recognizes. */
fun mediaServerDisplayName(url: String): String = url.removePrefix("https://").substringBefore('/')
