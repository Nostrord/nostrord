package org.nostr.nostrord.ui.navigation

import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.utils.toRelayUrl

/**
 * A new-design page addressable by the web hash (and mirrored as native navigation
 * state). The first hash segment is the page-type namespace, prototype-style:
 * `#/g/…` for groups, `#/u/…` for user profiles.
 */
sealed interface HashRoute

/**
 * Canonical route for a group page, shared by the web hash router and the native
 * navigation state. Web form (GitHub Pages friendly; the prototype's /g/:groupId
 * extended with the relay, since Nostrord is multi-relay):
 *
 *   #/g/<relay>/<groupId>[?invite=<code>]
 *
 * <relay> is the relay URL without the wss:// scheme, percent-encoded as a single
 * path segment (a rare path-bearing relay encodes its '/' as %2F), so the group id
 * always sits in the last segment: #/g/groups.0xchat.com/chachi. An explicit invite
 * code travels as a ?invite= query inside the hash and auto-joins on arrival (same
 * semantics as the existing ?relay=&group=&invite= deep link). NIP-29 relays are
 * TLS-only in practice, so the scheme is fixed to wss://.
 */
data class GroupRoute(
    val relayUrl: String,
    val groupId: String,
    val inviteCode: String? = null,
    // Deep-link target (?e=<event id>): scroll to and flash this message once the chat
    // loads. Set when opening from a notification or a shared message link; cleared from
    // the hash after the scroll so a refresh/back-forward replay doesn't re-jump.
    val messageId: String? = null,
    // Which pane of the group page is showing. Chat is the default live chat; Threads swaps
    // the centre pane for the forum-style threads list (or a single thread when
    // [threadRootId] is set). Carried in the hash as a `/threads` path suffix so a refresh or
    // a shared link reopens the same pane. The rail and group sidebar stay mounted either way.
    val view: GroupView = GroupView.Chat,
    // The open thread root (kind:11 event id) when [view] is Threads; null shows the list.
    // Appended as a fourth path segment: #/g/<relay>/<id>/threads/<rootId>.
    val threadRootId: String? = null,
) : HashRoute

/** Which pane of a [GroupRoute] is showing: the live chat (default) or the threads pane. */
enum class GroupView { Chat, Threads }

/**
 * Shareable web link for a thread: https://web.nostrord.com/#/g/<relay>/<id>/threads/<rootId>,
 * plus `?e=<messageId>` targeting the specific message to scroll to and flash. Backs "Copy
 * event link" in the thread context menu (the chat's nostrord.com/open ?e= form scrolls the
 * chat, which never contains a kind:11/1111 event).
 */
fun threadShareLink(
    relayUrl: String,
    groupId: String,
    rootId: String,
    messageId: String? = null,
): String = "https://web.nostrord.com/" +
    GroupRoute(relayUrl, groupId, view = GroupView.Threads, threadRootId = rootId, messageId = messageId).toHash()

/**
 * The value to persist in the last-route slot ([SecureStorage.saveLastRoute]) for [route].
 * Only groups and the home tabs are tracked for restore: a group persists its base hash
 * (no deep-link target / invite / thread pane), and the home tabs persist their hash (empty
 * for the default Groups home). Any other page returns null so the slot is left unchanged.
 */
fun persistedRouteHash(route: HashRoute?): String? = when (route) {
    null -> "" // default Groups home is the empty hash
    is HomeRoute -> route.toHash()
    is GroupRoute -> GroupRoute(route.relayUrl, route.groupId).toHash()
    else -> null
}

/**
 * Decode a persisted last-route slot ([SecureStorage.getLastRoute], a URL hash) back into the
 * [HashRoute] to reopen on launch. Only groups and the home tabs are restored; an empty/blank
 * slot (default Groups home) or any other route yields null, which
 * [NavigationHistory.seedDeepLink] treats as plain Home.
 */
fun restoredRoute(saved: String?): HashRoute? {
    val hash = saved?.takeIf { it.isNotBlank() } ?: return null
    return when (val route = parseHashRoute(hash)) {
        is GroupRoute, is HomeRoute -> route
        else -> null
    }
}

/**
 * Route for a relay page (#/r/<relay>): the relay's NIP-11 header plus the groups on it
 * that you or people you follow are in. [relayUrl] is the full ws(s):// URL; the hash
 * carries it scheme-stripped as a single segment, same encoding as [GroupRoute].
 */
data class RelayRoute(
    val relayUrl: String,
) : HashRoute

/**
 * Route for a user profile page: #/u/<npub>. [pubkey] is hex internally; the hash
 * carries the npub (shareable, nostr-native) but hex is accepted on parse too.
 */
data class UserRoute(
    val pubkey: String,
) : HashRoute

/**
 * Route for the direct-messages section: #/dm (conversation list) or
 * #/dm/<npub> (one conversation). Same npub-out / npub-or-hex-in rule as [UserRoute].
 */
data class DmRoute(
    val pubkey: String? = null,
) : HashRoute

/**
 * Route for the notifications page: #/notifications (the singular #/notification is
 * accepted on parse too). A parameterless page route mirrored like the others, so a
 * refresh or a hand-typed/shared #/notifications reopens the page instead of falling
 * back to Home.
 */
data object NotificationsRoute : HashRoute

/**
 * Route for the settings page: #/settings. A parameterless page route mirrored like
 * the others, so a refresh or a hand-typed/shared #/settings reopens settings instead
 * of falling back to Home.
 */
data object SettingsRoute : HashRoute

/** The Home page tabs, in pill order. [Groups] is the default home (no hash). */
enum class HomeTab { Groups, Friends, Recommended, People }

/**
 * The Home page on a specific discovery tab: #/friends, #/recommended, #/people. The
 * default [HomeTab.Groups] is plain Home (root, no hash) and is represented by a null
 * route, so only the non-default tabs ever become a [HomeRoute]. Mirrored into the URL
 * hash so a refresh or a shared link restores the tab.
 */
data class HomeRoute(val tab: HomeTab) : HashRoute

private const val GROUP_HASH_PREFIX = "#/g/"
private const val RELAY_HASH_PREFIX = "#/r/"
private const val USER_HASH_PREFIX = "#/u/"
private const val DM_HASH_PREFIX = "#/dm"
private const val NOTIFICATIONS_HASH = "#/notifications"
private const val SETTINGS_HASH = "#/settings"
private const val FRIENDS_HASH = "#/friends"
private const val RECOMMENDED_HASH = "#/recommended"
private const val PEOPLE_HASH = "#/people"
private const val HEX = "0123456789ABCDEF"

fun HashRoute.toHash(): String = when (this) {
    is GroupRoute -> toHash()
    is RelayRoute -> toHash()
    is UserRoute -> toHash()
    is DmRoute -> toHash()
    is NotificationsRoute -> NOTIFICATIONS_HASH
    is SettingsRoute -> SETTINGS_HASH
    is HomeRoute -> when (tab) {
        HomeTab.Groups -> "" // default home is the root, no hash
        HomeTab.Friends -> FRIENDS_HASH
        HomeTab.Recommended -> RECOMMENDED_HASH
        HomeTab.People -> PEOPLE_HASH
    }
}

fun parseHashRoute(hash: String): HashRoute? = parseGroupHash(hash) ?: parseRelayHash(hash) ?: parseUserHash(hash) ?: parseDmHash(hash) ?: parseNotificationsHash(hash) ?: parseSettingsHash(hash) ?: parseHomeTabHash(hash)

fun RelayRoute.toHash(): String = "$RELAY_HASH_PREFIX${encodeSegment(relayUrl.removePrefix("wss://").removePrefix("ws://"))}"

/** Parses a `#/r/<relay>` hash; null for any other hash. */
fun parseRelayHash(hash: String): RelayRoute? {
    if (!hash.startsWith(RELAY_HASH_PREFIX)) return null
    val seg = hash.removePrefix(RELAY_HASH_PREFIX).substringBefore('?')
    if (seg.isEmpty() || seg.contains('/')) return null
    return RelayRoute(decodeSegment(seg).toRelayUrl())
}

/** Parses the discovery-tab hashes (#/friends, #/recommended, #/people); null otherwise. */
fun parseHomeTabHash(hash: String): HomeRoute? = when (hash.substringBefore('?')) {
    FRIENDS_HASH -> HomeRoute(HomeTab.Friends)
    RECOMMENDED_HASH -> HomeRoute(HomeTab.Recommended)
    PEOPLE_HASH -> HomeRoute(HomeTab.People)
    else -> null
}

/** Parses `#/notifications` (or the singular `#/notification`); null for any other hash. */
fun parseNotificationsHash(hash: String): NotificationsRoute? {
    val path = hash.substringBefore('?')
    return if (path == NOTIFICATIONS_HASH || path == "#/notification") NotificationsRoute else null
}

/** Parses `#/settings`; null for any other hash. */
fun parseSettingsHash(hash: String): SettingsRoute? {
    val path = hash.substringBefore('?')
    return if (path == SETTINGS_HASH) SettingsRoute else null
}

fun UserRoute.toHash(): String = "$USER_HASH_PREFIX${encodePubkeySegment(pubkey)}"

/** Parses a `#/u/<npub or hex pubkey>` hash; null for any other hash. */
fun parseUserHash(hash: String): UserRoute? {
    if (!hash.startsWith(USER_HASH_PREFIX)) return null
    val hex = decodePubkeySegment(hash.removePrefix(USER_HASH_PREFIX).substringBefore('?')) ?: return null
    return UserRoute(hex)
}

fun DmRoute.toHash(): String {
    val pk = pubkey ?: return DM_HASH_PREFIX
    return "$DM_HASH_PREFIX/${encodePubkeySegment(pk)}"
}

/** Parses `#/dm` or `#/dm/<npub or hex pubkey>`; null for any other hash. */
fun parseDmHash(hash: String): DmRoute? {
    if (hash == DM_HASH_PREFIX || hash.substringBefore('?') == DM_HASH_PREFIX) return DmRoute(null)
    if (!hash.startsWith("$DM_HASH_PREFIX/")) return null
    val hex = decodePubkeySegment(hash.removePrefix("$DM_HASH_PREFIX/").substringBefore('?')) ?: return null
    return DmRoute(hex)
}

private fun encodePubkeySegment(pubkeyHex: String): String = encodeSegment(runCatching { Nip19.encodeNpub(pubkeyHex) }.getOrDefault(pubkeyHex))

/** Decodes an npub or 64-hex path segment to a hex pubkey; null when neither. */
private fun decodePubkeySegment(segment: String): String? {
    val raw = decodeSegment(segment)
    if (raw.isEmpty() || raw.contains('/')) return null
    return if (raw.startsWith("npub1")) {
        (runCatching { Nip19.decode(raw) }.getOrNull() as? Nip19.Entity.Npub)?.pubkey
    } else {
        raw.lowercase().takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }
}

fun GroupRoute.toHash(): String {
    // Strip the scheme for a clean segment; the scheme is restored on parse via
    // toRelayUrl (wss:// by default, ws:// for loopback). Strip BOTH so a local
    // ws:// relay doesn't leak its scheme into the segment.
    val relay = encodeSegment(relayUrl.removePrefix("wss://").removePrefix("ws://"))
    val id = encodeSegment(groupId)
    val threadsPath =
        if (view == GroupView.Threads) {
            "/threads" + (threadRootId?.let { "/${encodeSegment(it)}" } ?: "")
        } else {
            ""
        }
    val params =
        buildList {
            inviteCode?.let { add("invite=${encodeSegment(it)}") }
            messageId?.let { add("e=${encodeSegment(it)}") }
        }
    val query = if (params.isEmpty()) "" else "?" + params.joinToString("&")
    return "$GROUP_HASH_PREFIX$relay/$id$threadsPath$query"
}

/**
 * Parses a `#/g/<relay>/<groupId>` hash, optionally with a `/threads` (list) or
 * `/threads/<rootId>` (single thread) suffix and an `?invite=`/`?e=` query; null otherwise.
 */
fun parseGroupHash(hash: String): GroupRoute? {
    if (!hash.startsWith(GROUP_HASH_PREFIX)) return null
    val rest = hash.removePrefix(GROUP_HASH_PREFIX)
    val path = rest.substringBefore('?')
    val query = rest.substringAfter('?', "")
    val parts = path.split('/')
    // <relay>/<id> for chat, plus an optional /threads[/<rootId>] pane suffix.
    if (parts.size < 2 || parts.size > 4) return null
    if (parts[0].isEmpty() || parts[1].isEmpty()) return null
    val view: GroupView
    val threadRootId: String?
    when (parts.size) {
        2 -> {
            view = GroupView.Chat
            threadRootId = null
        }
        3 -> {
            if (parts[2] != "threads") return null
            view = GroupView.Threads
            threadRootId = null
        }
        else -> {
            if (parts[2] != "threads" || parts[3].isEmpty()) return null
            view = GroupView.Threads
            threadRootId = decodeSegment(parts[3])
        }
    }
    val params = query.split('&')
    fun param(key: String) = params
        .firstOrNull { it.startsWith("$key=") }
        ?.removePrefix("$key=")
        ?.takeIf { it.isNotEmpty() }
        ?.let(::decodeSegment)
    return GroupRoute(
        // toRelayUrl restores the scheme: wss:// for normal hosts, ws:// for loopback,
        // and it also tolerates a segment that already carries a scheme (older links
        // like #/g/ws%3A%2F%2F127.0.0.1%3A9888/… that encoded the full ws:// URL).
        relayUrl = decodeSegment(parts[0]).toRelayUrl(),
        groupId = decodeSegment(parts[1]),
        inviteCode = param("invite"),
        messageId = param("e"),
        view = view,
        threadRootId = threadRootId,
    )
}

/** RFC 3986 percent-encoding of everything outside the unreserved set (UTF-8). */
private fun encodeSegment(s: String): String = buildString {
    for (byte in s.encodeToByteArray()) {
        val c = byte.toInt() and 0xFF
        val ch = c.toChar()
        if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-._~") {
            append(ch)
        } else {
            append('%')
            append(HEX[c shr 4])
            append(HEX[c and 0xF])
        }
    }
}

private fun decodeSegment(s: String): String {
    val bytes = ArrayList<Byte>(s.length)
    var i = 0
    while (i < s.length) {
        val ch = s[i]
        val hi = if (ch == '%' && i + 2 < s.length) s[i + 1].digitToIntOrNull(16) else null
        val lo = if (hi != null) s[i + 2].digitToIntOrNull(16) else null
        if (hi != null && lo != null) {
            bytes.add(((hi shl 4) or lo).toByte())
            i += 3
        } else {
            // Unencoded ASCII passes through; anything else round-trips via UTF-8.
            for (b in ch.toString().encodeToByteArray()) bytes.add(b)
            i += 1
        }
    }
    return bytes.toByteArray().decodeToString()
}
