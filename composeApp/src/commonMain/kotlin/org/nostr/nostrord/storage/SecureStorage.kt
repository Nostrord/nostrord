package org.nostr.nostrord.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.nostr.nostrord.network.managers.PendingGroupInvite
import org.nostr.nostrord.nostr.Crypto
import org.nostr.nostrord.nostr.toHexString

// 128-bit (32 hex) SHA-256 prefix of the pubkey. Used as the storage subkey
// for all per-account slots: hashCode is 32-bit (collision-feasible) and the
// full pubkey hex exceeds java.util.prefs.Preferences' 80-char key limit on
// JVM. Cached because callers hit it on every per-account read/write — race
// on insert is harmless since the function is deterministic.
private val pubkeyDigestCache = mutableMapOf<String, String>()

private fun pubkeyDigest(pubkey: String): String = pubkeyDigestCache.getOrPut(pubkey) {
    Crypto.sha256(pubkey).toHexString().take(32)
}

/**
 * One-shot read-time migration: returns the value from [newKey] if present,
 * otherwise promotes the value from [legacyKey] (writes it under [newKey]
 * and clears the legacy slot). Returns null when both are empty.
 */
private fun SecureStorage.migrateStringSlot(
    newKey: String,
    legacyKey: String,
): String? {
    val current = getStringPref(newKey, "")
    if (current.isNotBlank()) return current
    val legacy = getStringPref(legacyKey, "")
    if (legacy.isBlank()) return null
    saveStringPref(newKey, legacy)
    saveStringPref(legacyKey, "")
    return legacy
}

expect object SecureStorage {
    fun savePrivateKey(privateKeyHex: String)

    fun getPrivateKey(): String?

    fun hasPrivateKey(): Boolean

    fun clearPrivateKey()

    fun saveCurrentRelayUrl(relayUrl: String)

    fun getCurrentRelayUrl(): String?

    fun clearCurrentRelayUrl()

    // Persisted relay list — all NIP-29 relays the user has added
    fun saveRelayList(relays: List<String>)

    fun loadRelayList(): List<String>

    // Account-scoped joined groups (namespaced by pubkey)
    fun saveJoinedGroupsForRelay(
        pubkey: String,
        relayUrl: String,
        groupIds: Set<String>,
    )

    fun getJoinedGroupsForRelay(
        pubkey: String,
        relayUrl: String,
    ): Set<String>

    fun clearJoinedGroupsForRelay(
        pubkey: String,
        relayUrl: String,
    )

    fun clearAllJoinedGroupsForAccount(pubkey: String)

    // NIP-46 Bunker support
    fun saveBunkerUrl(bunkerUrl: String)

    fun getBunkerUrl(): String?

    fun hasBunkerUrl(): Boolean

    fun clearBunkerUrl()

    // NIP-46 Bunker User Pubkey support
    fun saveBunkerUserPubkey(pubkey: String)

    fun getBunkerUserPubkey(): String?

    fun clearBunkerUserPubkey()

    // NIP-46 Bunker Client Private Key (for session persistence)
    fun saveBunkerClientPrivateKey(privateKey: String)

    fun getBunkerClientPrivateKey(): String?

    fun clearBunkerClientPrivateKey()

    // NIP-07 Browser Extension (for session persistence)
    fun saveNip07UserPubkey(pubkey: String)

    fun getNip07UserPubkey(): String?

    fun clearNip07UserPubkey()

    fun clearAll()

    // Last read timestamp tracking (account-scoped, per group)
    fun saveLastReadTimestamp(
        pubkey: String,
        groupId: String,
        timestamp: Long,
    )

    fun getLastReadTimestamp(
        pubkey: String,
        groupId: String,
    ): Long?

    fun clearLastReadTimestamp(
        pubkey: String,
        groupId: String,
    )

    fun getAllLastReadTimestamps(pubkey: String): Map<String, Long>

    // Last viewed group persistence (for restoring state on app restart)
    // Stores the group ID and name so the app can restore the last screen
    fun saveLastViewedGroup(
        pubkey: String,
        groupId: String,
        groupName: String?,
    )

    fun getLastViewedGroup(pubkey: String): Pair<String, String?>?

    fun clearLastViewedGroup(pubkey: String)

    // Last open route, for reopening straight into it on the next launch (native).
    // Stores the route's URL hash; it already encodes relay + groupId (or the home tab),
    // so a single string round-trips via parseHashRoute. Only groups and home are tracked.
    fun saveLastRoute(
        pubkey: String,
        routeHash: String,
    )

    fun getLastRoute(pubkey: String): String?

    fun clearLastRoute(pubkey: String)

    // Message persistence (for offline-first behavior)
    // Stores recent messages per group to survive app restarts
    fun saveMessagesForGroup(
        pubkey: String,
        groupId: String,
        messagesJson: String,
    )

    fun getMessagesForGroup(
        pubkey: String,
        groupId: String,
    ): String?

    fun clearMessagesForGroup(
        pubkey: String,
        groupId: String,
    )

    fun clearAllMessagesForAccount(pubkey: String)

    // Pending events persistence (for offline queue)
    fun savePendingEvents(
        pubkey: String,
        eventsJson: String,
    )

    fun getPendingEvents(pubkey: String): String?

    fun clearPendingEvents(pubkey: String)

    // Group metadata cache (for offline-first relay switching)
    fun saveGroupsForRelay(
        relayUrl: String,
        groupsJson: String,
    )

    fun getGroupsForRelay(relayUrl: String): String?

    fun clearGroupsForRelay(relayUrl: String)

    // Joined-group metadata cache — only the groups from kind:10009, scoped by pubkey.
    // Written on every kind:39000 event for a joined group and on leave/create.
    // Read on startup for instant sidebar display without waiting for the network.
    fun saveJoinedGroupMetadata(
        pubkey: String,
        relayUrl: String,
        groupsJson: String,
    )

    fun getJoinedGroupMetadata(
        pubkey: String,
        relayUrl: String,
    ): String?

    fun clearAllJoinedGroupMetadataForAccount(pubkey: String)

    // NIP-11 relay metadata cache (persisted across restarts)
    fun saveRelayMetadata(json: String)

    fun getRelayMetadata(): String?

    // kind:0 user metadata cache (persisted across restarts). Public, non-sensitive: a
    // pubkey's profile is the same for everyone, so this is a single global store (not
    // per-account) restored before network so names/avatars show instantly on cold start.
    fun saveUserMetadataCache(json: String)

    fun getUserMetadataCache(): String?

    // Other users' kind:10009 group lists (friends + curator), so the From friends /
    // Recommended tabs render from cache before the network answers. Public, non-sensitive,
    // global (a user's list is the same for everyone).
    fun saveUserGroupListsCache(json: String)

    fun getUserGroupListsCache(): String?

    // Live subscription cursors — last-seen event timestamp per group per relay
    fun saveLiveCursors(
        relayUrl: String,
        json: String,
    )

    fun getLiveCursors(relayUrl: String): String?

    fun clearLiveCursors(relayUrl: String)

    // Generic boolean preference — backs user-facing feature flags / settings.
    fun saveBooleanPref(
        key: String,
        value: Boolean,
    )

    fun getBooleanPref(
        key: String,
        default: Boolean,
    ): Boolean

    // Generic string preference — backs per-relay settings that aren't booleans (e.g. timestamps).
    fun saveStringPref(
        key: String,
        value: String,
    )

    fun getStringPref(
        key: String,
        default: String,
    ): String

    // Sensitive credential blob. Uses the platform's encrypted/secure path
    // (EncryptedSharedPreferences on Android, AES-GCM on JVM, localStorage on web).
    // Use these directly only for credentials. Account-scoped wrappers live as
    // extension functions below (`savePrivateKeyFor`, `saveBunkerUrlFor`, ...).
    fun saveSensitive(
        key: String,
        value: String,
    )

    fun getSensitive(key: String): String?

    fun clearSensitive(key: String)

    // Preload large metadata blobs from async storage (IndexedDB on web) into the in-memory cache.
    // Must be called once before restoreJoinedGroupMetadataFromStorage to ensure synchronous reads work.
    // No-op on Android and JVM (EncryptedSharedPreferences / Preferences are synchronous).
    suspend fun preloadMetadata()
}

// Per-relay group-list EOSE timestamp — lets the app skip requestGroups() when the
// cached group list is fresh enough (< GROUP_CACHE_TTL_S seconds old).
private const val GROUP_CACHE_TTL_S = 3600L // 1 hour

// Per-account: the freshness of one account's joined-group snapshot must not mark the
// relay "fresh" for a different account after a warm switch (that skipped the re-fetch and
// left the other account's groups on "No description"). Keyed by relay AND pubkey.
private fun groupEoseKey(
    relayUrl: String,
    pubkey: String?,
) = "group_eose_ts_${relayUrl.hashCode()}_${pubkey?.hashCode() ?: 0}"

fun SecureStorage.saveGroupListEoseTimestamp(
    relayUrl: String,
    pubkey: String?,
    timestampSeconds: Long,
) {
    saveStringPref(groupEoseKey(relayUrl, pubkey), timestampSeconds.toString())
}

fun SecureStorage.getGroupListEoseTimestamp(
    relayUrl: String,
    pubkey: String?,
): Long = getStringPref(groupEoseKey(relayUrl, pubkey), "0").toLongOrNull() ?: 0L

fun SecureStorage.isGroupListCacheFresh(
    relayUrl: String,
    pubkey: String?,
    nowSeconds: Long,
): Boolean {
    val ts = getGroupListEoseTimestamp(relayUrl, pubkey)
    return ts > 0L && (nowSeconds - ts) < GROUP_CACHE_TTL_S
}

// Per-relay full group-list EOSE timestamp — set only when requestGroups() (unfiltered) EOSE
// arrives, distinct from the joined-only timestamp saved by requestGroupsForIds().
// Lets hasFullGroupListBeenFetched() return true after an app restart without re-fetching.
fun SecureStorage.saveFullGroupListEoseTimestamp(
    relayUrl: String,
    timestampSeconds: Long,
) {
    saveStringPref("group_full_eose_ts_${relayUrl.hashCode()}", timestampSeconds.toString())
}

fun SecureStorage.isFullGroupListCacheFresh(
    relayUrl: String,
    nowSeconds: Long,
): Boolean {
    val ts = getStringPref("group_full_eose_ts_${relayUrl.hashCode()}", "0").toLongOrNull() ?: 0L
    return ts > 0L && (nowSeconds - ts) < GROUP_CACHE_TTL_S
}

// Per-relay Unix-seconds cursor of the last time the kind:9008 delete watch was armed.
// The next arm uses it as `since`, so a deletion that happened while this client was
// offline (or before the watch existed) replays on reconnect instead of leaving the
// group's cached metadata behind forever.
fun SecureStorage.saveDeleteWatchCursor(
    relayUrl: String,
    timestampSeconds: Long,
) {
    saveStringPref("group_del_watch_ts_${relayUrl.hashCode()}", timestampSeconds.toString())
}

fun SecureStorage.getDeleteWatchCursor(relayUrl: String): Long = getStringPref("group_del_watch_ts_${relayUrl.hashCode()}", "0").toLongOrNull() ?: 0L

// Persisted timestamp of the most recently published (or locally applied) kind:10009 event.
// Survives app restarts so that handleKind10009Event can reject stale network events that
// would otherwise restore relays/groups the user explicitly removed while offline.
//
// Pubkey-scoped: a timestamp from account A must NOT bleed into account B's freshness
// check after a logout+login, otherwise B's kind:10009 (with an older createdAt) gets
// rejected as "stale" even though it's fresh for B. Without this, B sees no relays
// because `_kind10009Relays` never populates.
private fun kind10009TimestampKey(pubkey: String) = "kind10009_latest_ts_${pubkeyDigest(pubkey)}"

private fun legacyKind10009TimestampKey(pubkey: String) = "kind10009_latest_ts_${pubkey.hashCode()}"

fun SecureStorage.saveKind10009Timestamp(
    pubkey: String,
    timestamp: Long,
) {
    saveStringPref(kind10009TimestampKey(pubkey), timestamp.toString())
    // Drop legacy slot so a colliding pubkey can't read a stale write.
    saveStringPref(legacyKind10009TimestampKey(pubkey), "")
}

fun SecureStorage.loadKind10009Timestamp(pubkey: String): Long {
    val raw = migrateStringSlot(kind10009TimestampKey(pubkey), legacyKind10009TimestampKey(pubkey))
    return raw?.toLongOrNull() ?: 0L
}

// ── Per-account NIP-29 relay list ───────────────────────────────────────────
// Pubkey-scoped wrappers around the legacy global `saveRelayList`/`loadRelayList`
// slot, which would otherwise leak relays across accounts. The first read for
// any pubkey adopts the legacy slot once and clears it.
private fun relayListForAccountKey(pubkey: String) = "relay_list_${pubkeyDigest(pubkey)}"

private fun legacyHashRelayListForAccountKey(pubkey: String) = "relay_list_${pubkey.hashCode()}"

private const val RELAY_LIST_MIGRATION_DONE_KEY = "relay_list_legacy_migrated"

private const val NOSTRCONNECT_RELAYS_KEY = "nostrconnect_relays"

private fun groupOrderForAccountKey(pubkey: String) = "group_order_${pubkeyDigest(pubkey)}"

/**
 * Rail order: `relay|id` entries in the tag order of the account's kind:10009. A sort key
 * only — an entry whose group is not joined is ignored, never a source of membership.
 * Persisted so a cold start orders the rail before the event lands.
 */
fun SecureStorage.saveGroupOrderFor(
    pubkey: String,
    order: List<Pair<String, String>>,
) {
    if (pubkey.isBlank()) return
    try {
        saveStringPref(
            groupOrderForAccountKey(pubkey),
            Json.encodeToString<List<String>>(order.map { (relay, id) -> "$relay|$id" }),
        )
    } catch (_: Exception) {
    }
}

fun SecureStorage.loadGroupOrderFor(pubkey: String): List<Pair<String, String>> {
    if (pubkey.isBlank()) return emptyList()
    val raw = getStringPref(groupOrderForAccountKey(pubkey), "")
    if (raw.isBlank()) return emptyList()
    return try {
        Json.decodeFromString<List<String>>(raw).mapNotNull { entry ->
            // Relay URLs carry no "|", so the first separator splits it; a group id may.
            val sep = entry.indexOf('|')
            if (sep <= 0 || sep == entry.lastIndex) null else entry.substring(0, sep) to entry.substring(sep + 1)
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * NIP-46 nostrconnect:// QR-login relays the user customized. Global (pre-login)
 * — not per-account, since they are chosen before any account exists.
 */
fun SecureStorage.saveNostrConnectRelays(relays: List<String>) {
    try {
        saveStringPref(NOSTRCONNECT_RELAYS_KEY, Json.encodeToString<List<String>>(relays))
    } catch (_: Exception) {
    }
}

/** Returns the saved relays, or null when none were ever customized. */
fun SecureStorage.getNostrConnectRelays(): List<String>? = try {
    val raw = getStringPref(NOSTRCONNECT_RELAYS_KEY, "")
    if (raw.isBlank()) {
        null
    } else {
        Json.decodeFromString<List<String>>(raw)
            .filter { it.isNotBlank() }
            // Drop relay.nsec.app from any previously-saved list: it was unreachable
            // for some users and made the QR connect fail with "Failed to connect to
            // any relay". Filtering on read auto-heals lists persisted before it was
            // removed from the defaults; an emptied list falls back to the defaults.
            .filterNot { it.trimEnd('/').endsWith("relay.nsec.app") }
            .takeIf { it.isNotEmpty() }
    }
} catch (_: Exception) {
    null
}

fun SecureStorage.saveRelayListFor(
    pubkey: String,
    relays: List<String>,
) {
    if (pubkey.isBlank()) return
    try {
        saveStringPref(relayListForAccountKey(pubkey), Json.encodeToString<List<String>>(relays))
        saveStringPref(legacyHashRelayListForAccountKey(pubkey), "")
    } catch (_: Exception) {
    }
}

fun SecureStorage.loadRelayListFor(pubkey: String): List<String> {
    if (pubkey.isBlank()) return emptyList()
    val raw = getStringPref(relayListForAccountKey(pubkey), "")
    if (raw.isNotBlank()) {
        return try {
            Json.decodeFromString(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }
    // First try the legacy hashCode-keyed slot from earlier builds of this branch.
    val legacyHash = getStringPref(legacyHashRelayListForAccountKey(pubkey), "")
    if (legacyHash.isNotBlank()) {
        val parsed: List<String> =
            try {
                Json.decodeFromString(legacyHash)
            } catch (_: Exception) {
                emptyList()
            }
        if (parsed.isNotEmpty()) {
            saveRelayListFor(pubkey, parsed)
            return parsed
        }
    }
    // Per-account slot is empty. Migrate from the legacy global slot once —
    // the first pubkey to read after the rollout adopts the legacy list.
    if (!getBooleanPref(RELAY_LIST_MIGRATION_DONE_KEY, false)) {
        val legacy = loadRelayList()
        if (legacy.isNotEmpty()) {
            saveRelayListFor(pubkey, legacy)
            saveRelayList(emptyList())
            saveBooleanPref(RELAY_LIST_MIGRATION_DONE_KEY, true)
            return legacy
        }
        saveBooleanPref(RELAY_LIST_MIGRATION_DONE_KEY, true)
    }
    return emptyList()
}

// ── Per-account friends cache ───────────────────────────────────────────────
// Last-known followed users (kind:3) with their resolved kind:0 metadata, so the
// home sidebar shows names + avatars instantly on launch instead of re-fetching
// every time. Refreshed from live data and on follow/unfollow.
private fun followingCacheKey(pubkey: String) = "following_cache_${pubkeyDigest(pubkey)}"

/**
 * The followed pubkey list (kind:3), per account. Only the identities are cached here — the
 * ordering pointer for the friends sidebar; their kind:0 metadata comes from the global
 * user-metadata store (MetadataManager), so it is not duplicated and stays a single source
 * of truth. Lets the sidebar render its rows before kind:3 re-arrives on cold start.
 */
fun SecureStorage.saveFollowingCacheFor(
    pubkey: String,
    following: List<String>,
) {
    if (pubkey.isBlank()) return
    try {
        saveStringPref(
            followingCacheKey(pubkey),
            Json.encodeToString(ListSerializer(String.serializer()), following),
        )
    } catch (_: Exception) {
    }
}

fun SecureStorage.loadFollowingCacheFor(pubkey: String): List<String> {
    if (pubkey.isBlank()) return emptyList()
    val raw = getStringPref(followingCacheKey(pubkey), "")
    if (raw.isBlank()) return emptyList()
    return try {
        Json.decodeFromString(ListSerializer(String.serializer()), raw)
    } catch (_: Exception) {
        emptyList()
    }
}

// ── Per-account mute list (NIP-51 kind:10000) ───────────────────────────────
// One JSON blob per account with the full last-known list (public tags, raw encrypted
// content, decrypted private section), hydrated at login so filtering works before the
// network answers AND so a publish never rebuilds from a partial base (which would clobber
// words/private mutes written by other clients). The blob shape is owned by NostrRepository;
// this slot only stores the encoded string. The timestamp floors the kind:10000 staleness
// check so a lagging relay can't resurrect mutes removed in a previous session.
private fun muteListKey(pubkey: String) = "mute_list_${pubkeyDigest(pubkey)}"

private fun kind10000TimestampKey(pubkey: String) = "kind10000_latest_ts_${pubkeyDigest(pubkey)}"

fun SecureStorage.saveMuteListSnapshotFor(
    pubkey: String,
    snapshotJson: String,
) {
    if (pubkey.isBlank()) return
    saveStringPref(muteListKey(pubkey), snapshotJson)
}

fun SecureStorage.loadMuteListSnapshotFor(pubkey: String): String? {
    if (pubkey.isBlank()) return null
    return getStringPref(muteListKey(pubkey), "").ifBlank { null }
}

fun SecureStorage.saveKind10000TimestampFor(
    pubkey: String,
    timestamp: Long,
) {
    if (pubkey.isBlank()) return
    saveStringPref(kind10000TimestampKey(pubkey), timestamp.toString())
}

fun SecureStorage.loadKind10000TimestampFor(pubkey: String): Long {
    if (pubkey.isBlank()) return 0L
    return getStringPref(kind10000TimestampKey(pubkey), "0").toLongOrNull() ?: 0L
}

// ── Per-account group membership cache ──────────────────────────────────────
// One JSON blob per account holding every known group's members/admins/roles
// (NIP-29 kind:39002/39001/39003) plus each list's source-event timestamp, so a
// previously-seen group renders its member list instantly on cold start
// (stale-while-revalidate) instead of waiting on a relay REQ. The blob shape is
// owned by GroupManager; this slot only stores the encoded string.
private fun groupMembershipCacheKey(pubkey: String) = "group_membership_${pubkeyDigest(pubkey)}"

fun SecureStorage.saveGroupMembershipFor(
    pubkey: String,
    membershipJson: String,
) {
    if (pubkey.isBlank()) return
    saveStringPref(groupMembershipCacheKey(pubkey), membershipJson)
}

fun SecureStorage.loadGroupMembershipFor(pubkey: String): String? {
    if (pubkey.isBlank()) return null
    return getStringPref(groupMembershipCacheKey(pubkey), "").ifBlank { null }
}

fun SecureStorage.clearGroupMembershipFor(pubkey: String) {
    if (pubkey.isBlank()) return
    saveStringPref(groupMembershipCacheKey(pubkey), "")
}

// One-shot guard: true once the legacy per-group message blobs have been seeded into the
// bulk history cache (CacheStore), so the migration runs at most once per account.
private fun messageBlobMigratedKey(pubkey: String) = "message_blob_migrated_${pubkeyDigest(pubkey)}"

fun SecureStorage.isMessageBlobMigratedFor(pubkey: String): Boolean = getBooleanPref(messageBlobMigratedKey(pubkey), false)

fun SecureStorage.setMessageBlobMigratedFor(pubkey: String) {
    saveBooleanPref(messageBlobMigratedKey(pubkey), true)
}

fun SecureStorage.clearMessageBlobMigratedFor(pubkey: String) {
    saveBooleanPref(messageBlobMigratedKey(pubkey), false)
}

// ── NIP-11 relay-info refresh timestamps ────────────────────────────────────
// Per-relay last-successful-fetch epoch seconds, one JSON blob (account-independent,
// like the relay metadata itself). Lets the NIP-11 cache skip the network fetch while
// the cached document is fresh (soft TTL) and refresh only occasionally, instead of
// re-fetching every relay on every cold start.
private const val RELAY_NIP11_FETCHED_AT_KEY = "relay_nip11_fetched_at"

fun SecureStorage.saveRelayMetadataFetchedAt(fetchedAtJson: String) {
    saveStringPref(RELAY_NIP11_FETCHED_AT_KEY, fetchedAtJson)
}

fun SecureStorage.getRelayMetadataFetchedAt(): String? = getStringPref(RELAY_NIP11_FETCHED_AT_KEY, "").ifBlank { null }

// ── Per-account "current relay" pointer ─────────────────────────────────────
// Pubkey-scoped wrappers around the legacy global `saveCurrentRelayUrl`, so a
// freshly added account doesn't inherit the previous account's last-used relay.
// The first read for any pubkey adopts the legacy slot once and clears it.
private fun currentRelayUrlForAccountKey(pubkey: String) = "current_relay_url_${pubkeyDigest(pubkey)}"

private fun legacyHashCurrentRelayUrlForAccountKey(pubkey: String) = "current_relay_url_${pubkey.hashCode()}"

private const val CURRENT_RELAY_URL_MIGRATION_DONE_KEY = "current_relay_url_legacy_migrated"

fun SecureStorage.saveCurrentRelayUrlFor(
    pubkey: String,
    relayUrl: String,
) {
    if (pubkey.isBlank()) return
    saveStringPref(currentRelayUrlForAccountKey(pubkey), relayUrl)
    saveStringPref(legacyHashCurrentRelayUrlForAccountKey(pubkey), "")
}

fun SecureStorage.getCurrentRelayUrlFor(pubkey: String): String? {
    if (pubkey.isBlank()) return null
    val raw = getStringPref(currentRelayUrlForAccountKey(pubkey), "")
    if (raw.isNotBlank()) return raw
    // First try the legacy hashCode-keyed slot from earlier builds of this branch.
    val legacyHash = getStringPref(legacyHashCurrentRelayUrlForAccountKey(pubkey), "")
    if (legacyHash.isNotBlank()) {
        saveCurrentRelayUrlFor(pubkey, legacyHash)
        return legacyHash
    }
    if (!getBooleanPref(CURRENT_RELAY_URL_MIGRATION_DONE_KEY, false)) {
        val legacy = getCurrentRelayUrl()
        if (!legacy.isNullOrBlank()) {
            saveCurrentRelayUrlFor(pubkey, legacy)
            clearCurrentRelayUrl()
            saveBooleanPref(CURRENT_RELAY_URL_MIGRATION_DONE_KEY, true)
            return legacy
        }
        saveBooleanPref(CURRENT_RELAY_URL_MIGRATION_DONE_KEY, true)
    }
    return null
}

fun SecureStorage.clearCurrentRelayUrlFor(pubkey: String) {
    if (pubkey.isBlank()) return
    saveStringPref(currentRelayUrlForAccountKey(pubkey), "")
    saveStringPref(legacyHashCurrentRelayUrlForAccountKey(pubkey), "")
}

// Per-relay lazy fetch mode — when true (the default), only joined-group metadata is fetched on
// connect; the full group list is deferred until the user expands "OTHER GROUPS".
// Set to false on relays where you always want the full list loaded at startup (EAGER mode).
fun SecureStorage.saveGroupFetchLazy(
    relayUrl: String,
    lazy: Boolean,
) {
    saveBooleanPref("group_fetch_lazy_${relayUrl.hashCode()}", lazy)
}

fun SecureStorage.isGroupFetchLazy(relayUrl: String): Boolean = getBooleanPref("group_fetch_lazy_${relayUrl.hashCode()}", true)

// Per-relay restricted groups — groupIds the relay CLOSED with "restricted" for this
// pubkey. Persisted so subsequent sessions exclude them from batched #d/#h REQs from
// the start (otherwise pyramid-style relays CLOSE the whole batch on the first
// connect, starving non-restricted groups of metadata until per-group CLOSEDs arrive).
// Scoped by pubkey+relay so different accounts don't leak each other's state.
// Entries auto-expire after RESTRICTED_GROUPS_TTL_S (7 days) — approval may have been
// granted since the last session and we want to retry.
private const val RESTRICTED_GROUPS_TTL_S = 7 * 24 * 3600L

@Serializable
private data class RestrictedGroupEntry(
    val reason: String,
    val ts: Long,
)

private fun restrictedGroupsKey(
    pubkey: String,
    relayUrl: String,
): String = "restricted_groups_${pubkeyDigest(pubkey)}_${relayUrl.hashCode()}"

private fun legacyRestrictedGroupsKey(
    pubkey: String,
    relayUrl: String,
): String = "restricted_groups_${pubkey.hashCode()}_${relayUrl.hashCode()}"

fun SecureStorage.getRestrictedGroupsForRelay(
    pubkey: String,
    relayUrl: String,
    nowSeconds: Long,
): Map<String, String> {
    val key = restrictedGroupsKey(pubkey, relayUrl)
    val raw =
        migrateStringSlot(key, legacyRestrictedGroupsKey(pubkey, relayUrl))
            ?: return emptyMap()
    val parsed: Map<String, RestrictedGroupEntry> =
        try {
            Json.decodeFromString(raw)
        } catch (_: Exception) {
            return emptyMap()
        }
    val fresh = parsed.filterValues { nowSeconds - it.ts < RESTRICTED_GROUPS_TTL_S }
    if (fresh.size != parsed.size) {
        // Prune stale entries from storage so the blob doesn't grow unbounded.
        try {
            saveStringPref(key, Json.encodeToString(fresh))
        } catch (_: Exception) {
        }
    }
    return fresh.mapValues { it.value.reason }
}

fun SecureStorage.addRestrictedGroupForRelay(
    pubkey: String,
    relayUrl: String,
    groupId: String,
    reason: String,
    nowSeconds: Long,
) {
    val key = restrictedGroupsKey(pubkey, relayUrl)
    val raw = getStringPref(key, "")
    val current: MutableMap<String, RestrictedGroupEntry> =
        if (raw.isBlank()) {
            mutableMapOf()
        } else {
            try {
                Json.decodeFromString<Map<String, RestrictedGroupEntry>>(raw).toMutableMap()
            } catch (_: Exception) {
                mutableMapOf()
            }
        }
    current[groupId] = RestrictedGroupEntry(reason, nowSeconds)
    try {
        saveStringPref(key, Json.encodeToString<Map<String, RestrictedGroupEntry>>(current))
    } catch (_: Exception) {
    }
}

fun SecureStorage.removeRestrictedGroupForRelay(
    pubkey: String,
    relayUrl: String,
    groupId: String,
) {
    val key = restrictedGroupsKey(pubkey, relayUrl)
    val raw = getStringPref(key, "")
    if (raw.isBlank()) return
    val current: MutableMap<String, RestrictedGroupEntry> =
        try {
            Json.decodeFromString<Map<String, RestrictedGroupEntry>>(raw).toMutableMap()
        } catch (_: Exception) {
            return
        }
    if (current.remove(groupId) != null) {
        try {
            saveStringPref(key, Json.encodeToString<Map<String, RestrictedGroupEntry>>(current))
        } catch (_: Exception) {
        }
    }
}

// ── Left-groups persistence ─────────────────────────────────────────────────
// Durable per-account/per-relay record of groups the user EXPLICITLY left (sent a
// kind:9022). Survives restart so the membership derivation can report NONE even when
// the relay still lists us in its kind:39002 (some relays keep a member listed after a
// leave). Entries auto-expire after LEFT_GROUPS_TTL_S so a long-abandoned marker can't
// keep a group out forever; a rejoin clears it immediately.
private const val LEFT_GROUPS_TTL_S = 30 * 24 * 3600L

private fun leftGroupsKey(
    pubkey: String,
    relayUrl: String,
): String = "left_groups_${pubkeyDigest(pubkey)}_${relayUrl.hashCode()}"

fun SecureStorage.getLeftGroupsForRelay(
    pubkey: String,
    relayUrl: String,
    nowSeconds: Long,
): Map<String, Long> {
    val key = leftGroupsKey(pubkey, relayUrl)
    val raw = getStringPref(key, "")
    if (raw.isBlank()) return emptyMap()
    val parsed: Map<String, Long> =
        try {
            Json.decodeFromString(raw)
        } catch (_: Exception) {
            return emptyMap()
        }
    val fresh = parsed.filterValues { nowSeconds - it < LEFT_GROUPS_TTL_S }
    if (fresh.size != parsed.size) {
        try {
            saveStringPref(key, Json.encodeToString(fresh))
        } catch (_: Exception) {
        }
    }
    return fresh
}

fun SecureStorage.addLeftGroupForRelay(
    pubkey: String,
    relayUrl: String,
    groupId: String,
    nowSeconds: Long,
) {
    val key = leftGroupsKey(pubkey, relayUrl)
    val raw = getStringPref(key, "")
    val current: MutableMap<String, Long> =
        if (raw.isBlank()) {
            mutableMapOf()
        } else {
            try {
                Json.decodeFromString<Map<String, Long>>(raw).toMutableMap()
            } catch (_: Exception) {
                mutableMapOf()
            }
        }
    current[groupId] = nowSeconds
    try {
        saveStringPref(key, Json.encodeToString<Map<String, Long>>(current))
    } catch (_: Exception) {
    }
}

// ── kind:10009 republish-pending flag ────────────────────────────────────────
// True while no relay has ACCEPTED the latest kind:10009 publish (offline leave, zombie
// socket): the reconnect hook republishes the CURRENT list and clears it on the first OK.
private fun kind10009RepublishPendingKey(pubkey: String) = "kind10009_republish_pending_${pubkeyDigest(pubkey)}"

fun SecureStorage.saveKind10009RepublishPendingFor(
    pubkey: String,
    pending: Boolean,
) {
    saveStringPref(kind10009RepublishPendingKey(pubkey), if (pending) "true" else "")
}

fun SecureStorage.isKind10009RepublishPendingFor(pubkey: String): Boolean = getStringPref(kind10009RepublishPendingKey(pubkey), "") == "true"

// ── Pending group invites ────────────────────────────────────────────────────
// External adds (an admin's kind:9000) the user hasn't accepted or declined yet. One JSON
// slot per account; entries are dropped on accept/decline/join/leave.
private fun pendingGroupInvitesKey(pubkey: String) = "pending_group_invites_${pubkeyDigest(pubkey)}"

fun SecureStorage.savePendingGroupInvitesFor(
    pubkey: String,
    invites: List<PendingGroupInvite>,
) {
    saveStringPref(pendingGroupInvitesKey(pubkey), Json.encodeToString(invites))
}

fun SecureStorage.getPendingGroupInvitesFor(pubkey: String): List<PendingGroupInvite> {
    val raw = getStringPref(pendingGroupInvitesKey(pubkey), "")
    if (raw.isBlank()) return emptyList()
    return try {
        Json.decodeFromString(raw)
    } catch (_: Exception) {
        emptyList()
    }
}

// ── Put-user watch cursor ────────────────────────────────────────────────────
// Unix-seconds `since` for the per-relay kind:9000 (put-user, #p self) watch. Advances
// as adds are processed so historical put-user replays stop arriving on reconnect;
// without it a very old add could re-adopt a group after the left marker's TTL expires.
private fun putUserCursorKey(
    pubkey: String,
    relayUrl: String,
): String = "put_user_cursor_${pubkeyDigest(pubkey)}_${relayUrl.hashCode()}"

fun SecureStorage.savePutUserCursorForRelay(
    pubkey: String,
    relayUrl: String,
    seconds: Long,
) {
    saveStringPref(putUserCursorKey(pubkey, relayUrl), seconds.toString())
}

fun SecureStorage.getPutUserCursorForRelay(
    pubkey: String,
    relayUrl: String,
): Long = getStringPref(putUserCursorKey(pubkey, relayUrl), "").toLongOrNull() ?: 0L

fun SecureStorage.removeLeftGroupForRelay(
    pubkey: String,
    relayUrl: String,
    groupId: String,
) {
    val key = leftGroupsKey(pubkey, relayUrl)
    val raw = getStringPref(key, "")
    if (raw.isBlank()) return
    val current: MutableMap<String, Long> =
        try {
            Json.decodeFromString<Map<String, Long>>(raw).toMutableMap()
        } catch (_: Exception) {
            return
        }
    if (current.remove(groupId) != null) {
        try {
            saveStringPref(key, Json.encodeToString<Map<String, Long>>(current))
        } catch (_: Exception) {
        }
    }
}

// ── Unread state persistence ────────────────────────────────────────────────
// Persists per-account unread counters + high-water timestamps so badges,
// rail bubbles, and the title counter survive app restarts. The high-water
// guards against double-counting when relays re-deliver history on reconnect:
// any incoming message with createdAt <= highWater is treated as already-seen.

@Serializable
internal data class UnreadEntry(
    val count: Int,
    val highWater: Long,
)

private fun unreadEntriesKey(pubkey: String): String = "unread_entries_${pubkeyDigest(pubkey)}"

private fun legacyUnreadEntriesKey(pubkey: String): String = "unread_entries_${pubkey.hashCode()}"

internal fun SecureStorage.getUnreadEntries(pubkey: String): Map<String, UnreadEntry> {
    val raw =
        migrateStringSlot(unreadEntriesKey(pubkey), legacyUnreadEntriesKey(pubkey))
            ?: return emptyMap()
    return try {
        Json.decodeFromString(raw)
    } catch (_: Exception) {
        emptyMap()
    }
}

internal fun SecureStorage.saveUnreadEntries(
    pubkey: String,
    entries: Map<String, UnreadEntry>,
) {
    try {
        saveStringPref(unreadEntriesKey(pubkey), Json.encodeToString<Map<String, UnreadEntry>>(entries))
    } catch (_: Exception) {
    }
}

// ── NIP-17 direct messages ──────────────────────────────────────────────────
// Decrypted DM rumors are cached per account so they survive restarts and we
// never re-decrypt a gift wrap. The sync cursor is the last time we opened the
// inbox; the next start subscribes from cursor - 2 days to cover NIP-59's
// randomized (backdated) gift-wrap timestamps. Read high-water per peer drives
// unread badges.

private fun dmMessagesKey(pubkey: String): String = "dm_messages_${pubkeyDigest(pubkey)}"

private fun dmLastReadKey(pubkey: String): String = "dm_last_read_${pubkeyDigest(pubkey)}"

private fun dmSyncCursorKey(pubkey: String): String = "dm_sync_cursor_${pubkeyDigest(pubkey)}"

fun SecureStorage.loadDmMessages(pubkey: String): List<org.nostr.nostrord.network.managers.DmMessage> {
    if (pubkey.isBlank()) return emptyList()
    val raw = getStringPref(dmMessagesKey(pubkey), "")
    if (raw.isBlank()) return emptyList()
    return try {
        Json.decodeFromString(raw)
    } catch (_: Exception) {
        emptyList()
    }
}

private fun dmCacheMigratedKey(pubkey: String) = "dm_cache_migrated_${pubkeyDigest(pubkey)}"

/**
 * Whether the legacy DM blob ([loadDmMessages]) has been moved into the CacheStore. DM history now
 * lives in the bulk message cache (IndexedDB / SQLDelight); this one-shot flag stops the migration
 * from re-running and lets [clearDmMessages] free the old KV slot.
 */
fun SecureStorage.isDmCacheMigratedFor(pubkey: String): Boolean = getBooleanPref(dmCacheMigratedKey(pubkey), false)

fun SecureStorage.setDmCacheMigratedFor(pubkey: String) {
    saveBooleanPref(dmCacheMigratedKey(pubkey), true)
}

/** Drop the legacy DM message blob once it has been migrated into the CacheStore. */
fun SecureStorage.clearDmMessages(pubkey: String) {
    if (pubkey.isBlank()) return
    saveStringPref(dmMessagesKey(pubkey), "")
}

fun SecureStorage.loadDmLastRead(pubkey: String): Map<String, Long> {
    if (pubkey.isBlank()) return emptyMap()
    val raw = getStringPref(dmLastReadKey(pubkey), "")
    if (raw.isBlank()) return emptyMap()
    return try {
        Json.decodeFromString(raw)
    } catch (_: Exception) {
        emptyMap()
    }
}

fun SecureStorage.saveDmLastRead(
    pubkey: String,
    lastRead: Map<String, Long>,
) {
    if (pubkey.isBlank()) return
    try {
        saveStringPref(dmLastReadKey(pubkey), Json.encodeToString(lastRead))
    } catch (_: Exception) {
    }
}

fun SecureStorage.loadDmSyncCursor(pubkey: String): Long = if (pubkey.isBlank()) 0L else getStringPref(dmSyncCursorKey(pubkey), "0").toLongOrNull() ?: 0L

fun SecureStorage.saveDmSyncCursor(
    pubkey: String,
    cursor: Long,
) {
    if (pubkey.isBlank()) return
    try {
        saveStringPref(dmSyncCursorKey(pubkey), cursor.toString())
    } catch (_: Exception) {
    }
}

// Gift-wrap (kind:1059) ids we have already unwrapped, so a re-streamed backlog skips the
// expensive per-wrap decrypt (a remote round-trip on a bunker signer) instead of redoing it.
// Durable progress: a slow/interrupted backfill resumes across app restarts.
private fun dmProcessedWrapsKey(pubkey: String): String = "dm_processed_wraps_${pubkeyDigest(pubkey)}"

fun SecureStorage.loadDmProcessedWrapIds(pubkey: String): Set<String> {
    if (pubkey.isBlank()) return emptySet()
    val raw = getStringPref(dmProcessedWrapsKey(pubkey), "") ?: ""
    if (raw.isBlank()) return emptySet()
    return runCatching { Json.decodeFromString<Set<String>>(raw) }.getOrDefault(emptySet())
}

fun SecureStorage.saveDmProcessedWrapIds(
    pubkey: String,
    ids: Set<String>,
) {
    if (pubkey.isBlank()) return
    try {
        saveStringPref(dmProcessedWrapsKey(pubkey), Json.encodeToString(ids))
    } catch (_: Exception) {
    }
}

// Relays each DM rumor's gift wrap was seen on, keyed by rumor id, so the "View source"
// modal's "Seen on" list survives restarts instead of only covering the current session.
private fun dmSeenRelaysKey(pubkey: String): String = "dm_seen_relays_${pubkeyDigest(pubkey)}"

fun SecureStorage.loadDmSeenRelays(pubkey: String): Map<String, List<String>> {
    if (pubkey.isBlank()) return emptyMap()
    val raw = getStringPref(dmSeenRelaysKey(pubkey), "") ?: ""
    if (raw.isBlank()) return emptyMap()
    return runCatching { Json.decodeFromString<Map<String, List<String>>>(raw) }.getOrDefault(emptyMap())
}

fun SecureStorage.saveDmSeenRelays(
    pubkey: String,
    seen: Map<String, List<String>>,
) {
    if (pubkey.isBlank()) return
    try {
        saveStringPref(dmSeenRelaysKey(pubkey), Json.encodeToString(seen))
    } catch (_: Exception) {
    }
}

// Undelivered outgoing DM gift wraps, persisted per account so a send survives an app restart and
// is retried on next launch until a relay OKs it (the "cached but never published" gap).
private fun dmSendQueueKey(pubkey: String): String = "dm_send_queue_${pubkeyDigest(pubkey)}"

fun SecureStorage.loadDmSendQueue(pubkey: String): List<org.nostr.nostrord.network.managers.PendingDmWrap> {
    if (pubkey.isBlank()) return emptyList()
    val raw = getStringPref(dmSendQueueKey(pubkey), "") ?: ""
    if (raw.isBlank()) return emptyList()
    return runCatching {
        Json.decodeFromString(ListSerializer(org.nostr.nostrord.network.managers.PendingDmWrap.serializer()), raw)
    }.getOrDefault(emptyList())
}

fun SecureStorage.saveDmSendQueue(
    pubkey: String,
    queue: List<org.nostr.nostrord.network.managers.PendingDmWrap>,
) {
    if (pubkey.isBlank()) return
    try {
        saveStringPref(
            dmSendQueueKey(pubkey),
            Json.encodeToString(ListSerializer(org.nostr.nostrord.network.managers.PendingDmWrap.serializer()), queue),
        )
    } catch (_: Exception) {
    }
}

// Gift-wrap id -> rumor id links, so a re-streamed but already-decrypted wrap can attach its
// delivering relay to the right message without paying the decrypt again (View source "Seen on").
private fun dmWrapRumorKey(pubkey: String): String = "dm_wrap_rumor_${pubkeyDigest(pubkey)}"

fun SecureStorage.loadDmWrapRumor(pubkey: String): Map<String, String> {
    if (pubkey.isBlank()) return emptyMap()
    val raw = getStringPref(dmWrapRumorKey(pubkey), "") ?: ""
    if (raw.isBlank()) return emptyMap()
    return runCatching { Json.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap())
}

fun SecureStorage.saveDmWrapRumor(
    pubkey: String,
    links: Map<String, String>,
) {
    if (pubkey.isBlank()) return
    try {
        saveStringPref(dmWrapRumorKey(pubkey), Json.encodeToString(links))
    } catch (_: Exception) {
    }
}

// Notification history — persisted feed of cross-relay notifications shown in
// the notification center. Scoped by pubkey so multi-account devices stay isolated.
private fun notificationHistoryKey(pubkey: String): String = "notification_history_${pubkeyDigest(pubkey)}"

private fun legacyNotificationHistoryKey(pubkey: String): String = "notification_history_${pubkey.hashCode()}"

fun SecureStorage.getPersistedNotifications(pubkey: String): List<org.nostr.nostrord.notifications.NotificationEntry> {
    val raw =
        migrateStringSlot(notificationHistoryKey(pubkey), legacyNotificationHistoryKey(pubkey))
            ?: return emptyList()
    return try {
        Json.decodeFromString(raw)
    } catch (_: Exception) {
        emptyList()
    }
}

fun SecureStorage.savePersistedNotifications(
    pubkey: String,
    entries: List<org.nostr.nostrord.notifications.NotificationEntry>,
) {
    try {
        saveStringPref(
            notificationHistoryKey(pubkey),
            Json.encodeToString<List<org.nostr.nostrord.notifications.NotificationEntry>>(entries),
        )
    } catch (_: Exception) {
    }
}

// Per-account, per-group notification level overrides (issue #70). Stored as a
// groupId -> NotificationLevel.name map so each account keeps its own muting /
// "mentions & replies only" choices. The settings layer maps the names back to
// the enum; storing strings keeps this layer free of the settings dependency.
private fun groupNotificationLevelsKey(pubkey: String): String = "group_notif_levels_${pubkeyDigest(pubkey)}"

fun SecureStorage.loadGroupNotificationLevelsFor(pubkey: String): Map<String, String> {
    if (pubkey.isBlank()) return emptyMap()
    val raw = getStringPref(groupNotificationLevelsKey(pubkey), "")
    if (raw.isBlank()) return emptyMap()
    return try {
        Json.decodeFromString(raw)
    } catch (_: Exception) {
        emptyMap()
    }
}

fun SecureStorage.saveGroupNotificationLevelsFor(
    pubkey: String,
    levels: Map<String, String>,
) {
    if (pubkey.isBlank()) return
    try {
        saveStringPref(groupNotificationLevelsKey(pubkey), Json.encodeToString<Map<String, String>>(levels))
    } catch (_: Exception) {
    }
}

// Per-account "last active" timestamp in Unix seconds. Written when the user
// switches away from this account and as a periodic heartbeat while active so
// it survives crashes. Used to compute the catch-up `since` for notification
// subscriptions on the next switch-in.
private fun lastActiveAtKey(pubkey: String): String = "account_last_active_at_${pubkeyDigest(pubkey)}"

private fun legacyHashLastActiveAtKey(pubkey: String): String = "account_last_active_at_${pubkey.hashCode()}"

fun SecureStorage.saveLastActiveAt(
    pubkey: String,
    unixSeconds: Long,
) {
    if (pubkey.isBlank()) return
    saveStringPref(lastActiveAtKey(pubkey), unixSeconds.toString())
    saveStringPref(legacyHashLastActiveAtKey(pubkey), "")
}

fun SecureStorage.getLastActiveAt(pubkey: String): Long {
    if (pubkey.isBlank()) return 0L
    val raw = migrateStringSlot(lastActiveAtKey(pubkey), legacyHashLastActiveAtKey(pubkey))
    return raw?.toLongOrNull() ?: 0L
}

fun SecureStorage.clearLastActiveAt(pubkey: String) {
    if (pubkey.isBlank()) return
    saveStringPref(lastActiveAtKey(pubkey), "")
    saveStringPref(legacyHashLastActiveAtKey(pubkey), "")
}

// ── Account-scoped credential storage ──────────────────────────────────────
// Each login method has its own pubkey-keyed slot so multiple accounts coexist.
// Legacy single-slot variants remain as a transient "active session" pointer;
// the one-shot legacy → per-account migration lives in StartupResolver.

private fun privKeyForAccountKey(pubkey: String) = "priv_key_${pubkeyDigest(pubkey)}"

private fun encryptedPrivKeyForAccountKey(pubkey: String) = "ncryptsec_${pubkeyDigest(pubkey)}"

private fun bunkerUrlForAccountKey(pubkey: String) = "bunker_url_${pubkeyDigest(pubkey)}"

private fun bunkerClientPrivForAccountKey(pubkey: String) = "bunker_client_priv_${pubkeyDigest(pubkey)}"

// Legacy hashCode-based keys — used only by the read-time migration below.
private fun legacyHashPrivKeyForAccountKey(pubkey: String) = "priv_key_${pubkey.hashCode()}"

private fun legacyHashBunkerUrlForAccountKey(pubkey: String) = "bunker_url_${pubkey.hashCode()}"

private fun legacyHashBunkerClientPrivForAccountKey(pubkey: String) = "bunker_client_priv_${pubkey.hashCode()}"

private fun SecureStorage.migrateSensitiveSlot(
    newKey: String,
    legacyKey: String,
): String? {
    val legacy = getSensitive(legacyKey) ?: return null
    saveSensitive(newKey, legacy)
    clearSensitive(legacyKey)
    return legacy
}

fun SecureStorage.savePrivateKeyFor(
    pubkey: String,
    privateKeyHex: String,
) {
    if (pubkey.isBlank()) return
    saveSensitive(privKeyForAccountKey(pubkey), privateKeyHex)
    clearSensitive(legacyHashPrivKeyForAccountKey(pubkey))
}

fun SecureStorage.getPrivateKeyFor(pubkey: String): String? {
    if (pubkey.isBlank()) return null
    getSensitive(privKeyForAccountKey(pubkey))?.let { return it }
    return migrateSensitiveSlot(privKeyForAccountKey(pubkey), legacyHashPrivKeyForAccountKey(pubkey))
}

fun SecureStorage.clearPrivateKeyFor(pubkey: String) {
    if (pubkey.isBlank()) return
    clearSensitive(privKeyForAccountKey(pubkey))
    clearSensitive(legacyHashPrivKeyForAccountKey(pubkey))
}

// NIP-49 password-protected accounts: only the ncryptsec touches disk (the raw key
// lives in the in-memory signer) and the unlock password is asked at startup.
fun SecureStorage.saveEncryptedPrivateKeyFor(
    pubkey: String,
    ncryptsec: String,
) {
    if (pubkey.isBlank()) return
    saveSensitive(encryptedPrivKeyForAccountKey(pubkey), ncryptsec)
}

fun SecureStorage.getEncryptedPrivateKeyFor(pubkey: String): String? {
    if (pubkey.isBlank()) return null
    return getSensitive(encryptedPrivKeyForAccountKey(pubkey))
}

fun SecureStorage.clearEncryptedPrivateKeyFor(pubkey: String) {
    if (pubkey.isBlank()) return
    clearSensitive(encryptedPrivKeyForAccountKey(pubkey))
}

fun SecureStorage.saveBunkerUrlFor(
    pubkey: String,
    bunkerUrl: String,
) {
    if (pubkey.isBlank()) return
    saveSensitive(bunkerUrlForAccountKey(pubkey), bunkerUrl)
    clearSensitive(legacyHashBunkerUrlForAccountKey(pubkey))
}

fun SecureStorage.getBunkerUrlFor(pubkey: String): String? {
    if (pubkey.isBlank()) return null
    getSensitive(bunkerUrlForAccountKey(pubkey))?.let { return it }
    return migrateSensitiveSlot(bunkerUrlForAccountKey(pubkey), legacyHashBunkerUrlForAccountKey(pubkey))
}

fun SecureStorage.clearBunkerUrlFor(pubkey: String) {
    if (pubkey.isBlank()) return
    clearSensitive(bunkerUrlForAccountKey(pubkey))
    clearSensitive(legacyHashBunkerUrlForAccountKey(pubkey))
}

fun SecureStorage.saveBunkerClientPrivateKeyFor(
    pubkey: String,
    clientPrivateKey: String,
) {
    if (pubkey.isBlank()) return
    saveSensitive(bunkerClientPrivForAccountKey(pubkey), clientPrivateKey)
    clearSensitive(legacyHashBunkerClientPrivForAccountKey(pubkey))
}

fun SecureStorage.getBunkerClientPrivateKeyFor(pubkey: String): String? {
    if (pubkey.isBlank()) return null
    getSensitive(bunkerClientPrivForAccountKey(pubkey))?.let { return it }
    return migrateSensitiveSlot(
        bunkerClientPrivForAccountKey(pubkey),
        legacyHashBunkerClientPrivForAccountKey(pubkey),
    )
}

fun SecureStorage.clearBunkerClientPrivateKeyFor(pubkey: String) {
    if (pubkey.isBlank()) return
    clearSensitive(bunkerClientPrivForAccountKey(pubkey))
    clearSensitive(legacyHashBunkerClientPrivForAccountKey(pubkey))
}

/** Convenience: wipe every credential slot belonging to [pubkey]. */
private fun pomegranateCentralForAccountKey(pubkey: String) = "pomegranate_central_${pubkeyDigest(pubkey)}"

/**
 * Central-server origin of a pomegranate ("Login with Google") bunker account. Its
 * presence is what marks the account as pomegranate-managed; settings use it to offer
 * nsec export / disconnect against that server.
 */
fun SecureStorage.savePomegranateCentralFor(
    pubkey: String,
    central: String,
) {
    if (pubkey.isBlank()) return
    saveStringPref(pomegranateCentralForAccountKey(pubkey), central)
}

fun SecureStorage.loadPomegranateCentralFor(pubkey: String): String? {
    if (pubkey.isBlank()) return null
    return getStringPref(pomegranateCentralForAccountKey(pubkey), "").takeIf { it.isNotBlank() }
}

fun SecureStorage.clearPomegranateCentralFor(pubkey: String) {
    if (pubkey.isBlank()) return
    saveStringPref(pomegranateCentralForAccountKey(pubkey), "")
}

private fun pomegranateDisconnectedForAccountKey(pubkey: String) = "pomegranate_disconnected_${pubkeyDigest(pubkey)}"

/**
 * Set when the user deliberately deletes the account on the pomegranate central
 * server: Google login and NIP-46 signing are gone for good, and only an exported
 * nsec can revive the account. Lets the UI say that instead of generic bunker copy.
 */
fun SecureStorage.markPomegranateDisconnectedFor(pubkey: String) {
    if (pubkey.isBlank()) return
    saveBooleanPref(pomegranateDisconnectedForAccountKey(pubkey), true)
}

fun SecureStorage.loadPomegranateDisconnectedFor(pubkey: String): Boolean {
    if (pubkey.isBlank()) return false
    return getBooleanPref(pomegranateDisconnectedForAccountKey(pubkey), false)
}

fun SecureStorage.clearPomegranateDisconnectedFor(pubkey: String) {
    if (pubkey.isBlank()) return
    saveBooleanPref(pomegranateDisconnectedForAccountKey(pubkey), false)
}

fun SecureStorage.clearAllCredentialsForAccount(pubkey: String) {
    clearPrivateKeyFor(pubkey)
    clearEncryptedPrivateKeyFor(pubkey)
    clearBunkerUrlFor(pubkey)
    clearBunkerClientPrivateKeyFor(pubkey)
    clearPomegranateCentralFor(pubkey)
    clearPomegranateDisconnectedFor(pubkey)
    clearNip55SignerPackageFor(pubkey)
}

private fun droppedGroupsForAccountKey(pubkey: String) = "dropped_groups_${pubkeyDigest(pubkey)}"

/**
 * Group ids the user deleted or left on this device. Persisted so the additive kind:10009 merge
 * (OutboxManager) keeps skipping them after a restart: a relay that never received the updated list
 * still serves the old one, and without this guard the stale copy resurrects the group in the rail.
 * Cleared (per id) on re-join.
 */
fun SecureStorage.saveDroppedGroupIds(
    pubkey: String,
    ids: Set<String>,
) {
    if (pubkey.isBlank()) return
    try {
        saveStringPref(droppedGroupsForAccountKey(pubkey), Json.encodeToString<List<String>>(ids.toList()))
    } catch (_: Exception) {
    }
}

fun SecureStorage.loadDroppedGroupIds(pubkey: String): Set<String> {
    if (pubkey.isBlank()) return emptySet()
    val raw = getStringPref(droppedGroupsForAccountKey(pubkey), "")
    if (raw.isBlank()) return emptySet()
    return try {
        Json.decodeFromString<List<String>>(raw).toSet()
    } catch (_: Exception) {
        emptySet()
    }
}

// ── NIP-55 (Amber) signer package per account ───────────────────────────────
// Package name of the Android signer app chosen at login; addresses every later
// Intent / ContentResolver request for this account. Android-only at runtime.
private fun nip55SignerPackageForAccountKey(pubkey: String) = "nip55_signer_package_${pubkeyDigest(pubkey)}"

fun SecureStorage.saveNip55SignerPackageFor(
    pubkey: String,
    packageName: String,
) {
    if (pubkey.isBlank() || packageName.isBlank()) return
    saveStringPref(nip55SignerPackageForAccountKey(pubkey), packageName)
}

fun SecureStorage.getNip55SignerPackageFor(pubkey: String): String? {
    if (pubkey.isBlank()) return null
    return getStringPref(nip55SignerPackageForAccountKey(pubkey), "").ifBlank { null }
}

fun SecureStorage.clearNip55SignerPackageFor(pubkey: String) {
    if (pubkey.isBlank()) return
    saveStringPref(nip55SignerPackageForAccountKey(pubkey), "")
}
