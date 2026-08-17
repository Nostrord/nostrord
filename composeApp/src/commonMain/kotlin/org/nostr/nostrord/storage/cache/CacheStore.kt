package org.nostr.nostrord.storage.cache

/**
 * NIP-17 direct-message kind. DMs are cached in the message table keyed by peer pubkey (group_id)
 * and excluded from the group-message byte-budget eviction so conversations are never dropped.
 */
const val DM_CACHE_KIND = 14

/**
 * NIP-17 file message kind. Cached in the same table as [DM_CACHE_KIND] and treated as a DM
 * everywhere (never evicted by the group-message budget); the kind is kept so a hydrated row
 * still knows it carries an attachment rather than text.
 */
const val DM_FILE_CACHE_KIND = 15

/** Every kind the message table holds as a direct message. */
val DM_CACHE_KINDS = listOf(DM_CACHE_KIND, DM_FILE_CACHE_KIND)

/**
 * Bulk, queryable, bounded cache for chat messages and generic events — the persistence
 * seam for "never wait on the relay for something you've seen". Separate from [SecureStorage]
 * (which stays for small KV slots and credentials): message/event history is large and needs
 * range queries and byte-bounded eviction that a flat KV blob can't provide.
 *
 * Everything is `suspend` because the web backend (IndexedDB) is async and the native backend
 * (SQLite) runs off the main thread. Every row is scoped by [account] (pubkey hex) so logout
 * clears one account without touching another. The shape here is engine-agnostic on purpose:
 * native binds it to SQLDelight, web to IndexedDB, sharing this interface and the logical schema,
 * never the engine.
 */
interface CacheStore {
    /** Open the backing store and run any schema migration. Safe to call more than once. */
    suspend fun init()

    // ── Messages (chat history per group) ───────────────────────────────────
    suspend fun upsertMessages(
        account: String,
        groupId: String,
        messages: List<CachedMsg>,
    )

    /** The most recent [limit] messages for the group, oldest-first (ready to append in the UI). */
    suspend fun loadLatest(
        account: String,
        groupId: String,
        limit: Int,
    ): List<CachedMsg>

    /** The [limit] messages just older than [beforeCreatedAt], oldest-first (scroll-back page). */
    suspend fun loadBefore(
        account: String,
        groupId: String,
        beforeCreatedAt: Long,
        limit: Int,
    ): List<CachedMsg>

    /** Oldest cached `created_at` for the group, or null when nothing is cached. */
    suspend fun oldestCreatedAt(
        account: String,
        groupId: String,
    ): Long?

    /**
     * Every cached message of [kind] for [account], oldest-first. DMs are stored here as kind:14
     * with the peer pubkey in `groupId`, so this hydrates the whole DM history in one call without
     * knowing the peers up front. Excluded from byte-budget eviction so DMs are never dropped.
     */
    suspend fun loadByKind(
        account: String,
        kind: Int,
    ): List<CachedMsg>

    // ── Generic events (kind:1/7/9/30023), keyed by id, immutable ────────────
    suspend fun upsertEvents(
        account: String,
        events: List<CachedEventRow>,
    )

    suspend fun getEvent(
        account: String,
        id: String,
    ): CachedEventRow?

    suspend fun getEvents(
        account: String,
        ids: List<String>,
    ): List<CachedEventRow>

    /**
     * Remove rows (message and generic-event tables) for [account] whose id is in [ids]. Applied
     * when a deletion event (kind 5 / 9005) lands, so the deleted target (e.g. a revoked kind:9009
     * invite code) does not rehydrate from cache on the next cold open. No-op for ids not present.
     */
    suspend fun deleteByIds(
        account: String,
        ids: Collection<String>,
    )

    // ── Lifecycle ────────────────────────────────────────────────────────────
    /** Drop the oldest rows (by `created_at`) for [account] until under [maxBytes]. */
    suspend fun evictToByteBudget(
        account: String,
        maxBytes: Long,
    )

    /** Remove every row for [account] (logout / account removal). */
    suspend fun clearAccount(account: String)
}

/** A chat message row. [tagsJson] is the event tags serialized as a JSON array-of-arrays. */
data class CachedMsg(
    val id: String,
    val groupId: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val content: String,
    val tagsJson: String,
)

/** A generic event row (quote/reaction/reply targets), keyed by [id]. */
data class CachedEventRow(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val content: String,
    val tagsJson: String,
)
