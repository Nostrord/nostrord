package org.nostr.nostrord.storage.cache

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [CacheStore] implementation. It is the temporary production backend until the
 * SQLDelight (native) and IndexedDB (web) backends land, and doubles as the test double for
 * consumers of the seam. Correct and account-scoped, but not persistent — it intentionally
 * carries no disk tier yet, so cold-start hydration is a no-op until the real backends arrive.
 *
 * All access is serialized through a [Mutex] so concurrent suspend callers can't corrupt the
 * maps. Byte accounting is an approximation (content + tags + a fixed per-row overhead), good
 * enough for eviction ordering; the SQL backend will enforce the real on-disk budget.
 */
class InMemoryCacheStore : CacheStore {
    private val mutex = Mutex()

    // account -> groupId -> id -> message
    private val messages = mutableMapOf<String, MutableMap<String, MutableMap<String, CachedMsg>>>()

    // account -> id -> event
    private val events = mutableMapOf<String, MutableMap<String, CachedEventRow>>()

    override suspend fun init() {
        // No disk tier yet: nothing to open or migrate.
    }

    override suspend fun upsertMessages(
        account: String,
        groupId: String,
        messages: List<CachedMsg>,
    ) = mutex.withLock {
        if (messages.isEmpty()) return@withLock
        val byGroup = this.messages.getOrPut(account) { mutableMapOf() }
        val byId = byGroup.getOrPut(groupId) { mutableMapOf() }
        messages.forEach { byId[it.id] = it }
    }

    override suspend fun loadLatest(
        account: String,
        groupId: String,
        limit: Int,
    ): List<CachedMsg> = mutex.withLock {
        groupMessages(account, groupId)
            .sortedBy { it.createdAt }
            .takeLast(limit)
    }

    override suspend fun loadBefore(
        account: String,
        groupId: String,
        beforeCreatedAt: Long,
        limit: Int,
    ): List<CachedMsg> = mutex.withLock {
        groupMessages(account, groupId)
            .filter { it.createdAt < beforeCreatedAt }
            .sortedBy { it.createdAt }
            .takeLast(limit)
    }

    override suspend fun oldestCreatedAt(
        account: String,
        groupId: String,
    ): Long? = mutex.withLock {
        groupMessages(account, groupId).minOfOrNull { it.createdAt }
    }

    override suspend fun loadByKind(
        account: String,
        kind: Int,
    ): List<CachedMsg> = mutex.withLock {
        messages[account]?.values.orEmpty()
            .flatMap { it.values }
            .filter { it.kind == kind }
            .sortedBy { it.createdAt }
    }

    override suspend fun upsertEvents(
        account: String,
        events: List<CachedEventRow>,
    ) = mutex.withLock {
        if (events.isEmpty()) return@withLock
        val byId = this.events.getOrPut(account) { mutableMapOf() }
        events.forEach { byId[it.id] = it }
    }

    override suspend fun getEvent(
        account: String,
        id: String,
    ): CachedEventRow? = mutex.withLock { events[account]?.get(id) }

    override suspend fun getEvents(
        account: String,
        ids: List<String>,
    ): List<CachedEventRow> = mutex.withLock {
        val byId = events[account] ?: return@withLock emptyList()
        ids.mapNotNull { byId[it] }
    }

    override suspend fun deleteByIds(
        account: String,
        ids: Collection<String>,
    ) = mutex.withLock {
        if (ids.isEmpty()) return@withLock
        messages[account]?.values?.forEach { byId -> ids.forEach { byId.remove(it) } }
        events[account]?.let { byId -> ids.forEach { byId.remove(it) } }
        Unit
    }

    override suspend fun evictToByteBudget(
        account: String,
        maxBytes: Long,
    ) = mutex.withLock {
        val rows = mutableListOf<Evictable>()
        messages[account]?.forEach { (_, byId) ->
            // DMs are excluded from the group-message byte budget, so they're never evicted.
            byId.values.filter { it.kind !in DM_CACHE_KINDS }.forEach { m ->
                rows.add(Evictable(m.createdAt, estimateBytes(m.content, m.tagsJson)) { byId.remove(m.id) })
            }
        }
        events[account]?.values?.toList()?.forEach { e ->
            rows.add(Evictable(e.createdAt, estimateBytes(e.content, e.tagsJson)) { events[account]?.remove(e.id) })
        }
        var total = rows.sumOf { it.bytes }
        if (total <= maxBytes) return@withLock
        // Oldest first.
        rows.sortBy { it.createdAt }
        for (row in rows) {
            if (total <= maxBytes) break
            row.remove()
            total -= row.bytes
        }
    }

    override suspend fun clearAccount(account: String) = mutex.withLock {
        messages.remove(account)
        events.remove(account)
        Unit
    }

    // Caller holds [mutex].
    private fun groupMessages(
        account: String,
        groupId: String,
    ): Collection<CachedMsg> = messages[account]?.get(groupId)?.values ?: emptyList()

    private fun estimateBytes(
        content: String,
        tagsJson: String,
    ): Long = content.length + tagsJson.length + ROW_OVERHEAD_BYTES

    private class Evictable(
        val createdAt: Long,
        val bytes: Long,
        val remove: () -> Unit,
    )

    private companion object {
        // Fixed allowance for id/pubkey/kind/created_at columns beyond the variable text.
        const val ROW_OVERHEAD_BYTES = 120L
    }
}
