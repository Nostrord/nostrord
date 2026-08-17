package org.nostr.nostrord.storage.cache

import kotlinx.browser.window
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.json

/**
 * IndexedDB-backed [CacheStore] for the web. SQLDelight has no stable web driver and the app
 * is Kotlin/JS (not WASM), so web persists message/event history in IndexedDB object stores
 * with the same logical schema as the native SQLite: one store per entity, compound primary
 * key [account, id], and an index for range queries over created_at.
 *
 * IndexedDB semantics (cursors, key ranges) can only be behavior-verified in a browser. The
 * `?cachetest` query param on the web entry runs runCacheStoreSelfTest against this real store;
 * re-run it (e.g. via a headless driver) after changing the cursor/range logic.
 *
 * Kotlin/JS `Long` is a boxed object, not a JS number, so created_at is stored and queried as a
 * JS number (Double) and converted back at the boundary; nostr timestamps are seconds, well
 * within Double's exact-integer range.
 */
class IndexedDbCacheStore : CacheStore {
    private var cachedDb: dynamic = null

    override suspend fun init() {
        db()
    }

    private suspend fun db(): dynamic {
        val existing = cachedDb
        if (existing != null) return existing
        val idb = window.asDynamic().indexedDB
        val req = idb.open(DB_NAME, DB_VERSION)
        req.onupgradeneeded = { _: dynamic ->
            val database = req.result
            val store =
                if (!database.objectStoreNames.contains(MESSAGE).unsafeCast<Boolean>()) {
                    val created = database.createObjectStore(MESSAGE, json("keyPath" to arrayOf("account", "id")))
                    created.createIndex(MSG_GROUP_TIME, arrayOf("account", "group_id", "created_at"), json("unique" to false))
                    created
                } else {
                    // Existing database: the store is only reachable through the upgrade transaction.
                    req.transaction.objectStore(MESSAGE)
                }
            // Without this, reading one kind means stepping a cursor over every message the account
            // has cached, group history included: a DM inbox that should hydrate instantly instead
            // waits on tens of thousands of cursor hops.
            if (!store.indexNames.contains(MSG_KIND_TIME).unsafeCast<Boolean>()) {
                store.createIndex(MSG_KIND_TIME, arrayOf("account", "kind", "created_at"), json("unique" to false))
            }
            if (!database.objectStoreNames.contains(EVENT).unsafeCast<Boolean>()) {
                database.createObjectStore(EVENT, json("keyPath" to arrayOf("account", "id")))
            }
        }
        val opened = awaitRequest<dynamic>(req)
        cachedDb = opened
        return opened
    }

    override suspend fun upsertMessages(
        account: String,
        groupId: String,
        messages: List<CachedMsg>,
    ) {
        if (messages.isEmpty()) return
        val database = db()
        val tx = database.transaction(MESSAGE, "readwrite")
        val store = tx.objectStore(MESSAGE)
        messages.forEach { m ->
            store.put(
                json(
                    "account" to account,
                    "id" to m.id,
                    "group_id" to m.groupId,
                    "pubkey" to m.pubkey,
                    "created_at" to m.createdAt.toDouble(),
                    "kind" to m.kind,
                    "content" to m.content,
                    "tags_json" to m.tagsJson,
                ),
            )
        }
        awaitTx(tx)
    }

    override suspend fun loadLatest(
        account: String,
        groupId: String,
        limit: Int,
    ): List<CachedMsg> {
        val range = boundGroup(account, groupId, upperCreatedAt = null, upperExclusive = false)
        // Newest-first via a reverse cursor, capped at limit, then reversed to oldest-first.
        return cursorMessages(range, limit).asReversed()
    }

    override suspend fun loadBefore(
        account: String,
        groupId: String,
        beforeCreatedAt: Long,
        limit: Int,
    ): List<CachedMsg> {
        val range = boundGroup(account, groupId, upperCreatedAt = beforeCreatedAt, upperExclusive = true)
        return cursorMessages(range, limit).asReversed()
    }

    override suspend fun oldestCreatedAt(
        account: String,
        groupId: String,
    ): Long? {
        val range = boundGroup(account, groupId, upperCreatedAt = null, upperExclusive = false)
        val database = db()
        val store = database.transaction(MESSAGE, "readonly").objectStore(MESSAGE)
        val cursorReq = store.index(MSG_GROUP_TIME).openCursor(range, "next")
        val first = awaitRequest<dynamic>(cursorReq) ?: return null
        return (first.value.created_at.unsafeCast<Double>()).toLong()
    }

    override suspend fun loadByKind(
        account: String,
        kind: Int,
    ): List<CachedMsg> {
        val database = db()
        val store = database.transaction(MESSAGE, "readonly").objectStore(MESSAGE)
        val out = ArrayList<CachedMsg>()
        // The index is already ordered by created_at within the kind, so the cursor visits only
        // this kind's rows and they come out sorted.
        drainCursor(store.index(MSG_KIND_TIME).openCursor(boundKind(account, kind), "next")) { cursor ->
            out.add(toCachedMsg(cursor.value))
            true
        }
        return out
    }

    override suspend fun upsertEvents(
        account: String,
        events: List<CachedEventRow>,
    ) {
        if (events.isEmpty()) return
        val database = db()
        val tx = database.transaction(EVENT, "readwrite")
        val store = tx.objectStore(EVENT)
        events.forEach { e ->
            store.put(
                json(
                    "account" to account,
                    "id" to e.id,
                    "pubkey" to e.pubkey,
                    "created_at" to e.createdAt.toDouble(),
                    "kind" to e.kind,
                    "content" to e.content,
                    "tags_json" to e.tagsJson,
                ),
            )
        }
        awaitTx(tx)
    }

    override suspend fun getEvent(
        account: String,
        id: String,
    ): CachedEventRow? {
        val database = db()
        val store = database.transaction(EVENT, "readonly").objectStore(EVENT)
        val row = awaitRequest<dynamic>(store.get(arrayOf(account, id))) ?: return null
        return toEventRow(row)
    }

    override suspend fun getEvents(
        account: String,
        ids: List<String>,
    ): List<CachedEventRow> {
        if (ids.isEmpty()) return emptyList()
        val database = db()
        val store = database.transaction(EVENT, "readonly").objectStore(EVENT)
        return ids.mapNotNull { id ->
            val row = awaitRequest<dynamic>(store.get(arrayOf(account, id)))
            if (row == null) null else toEventRow(row)
        }
    }

    override suspend fun deleteByIds(
        account: String,
        ids: Collection<String>,
    ) {
        if (ids.isEmpty()) return
        val database = db()
        val tx = database.transaction(arrayOf(MESSAGE, EVENT), "readwrite")
        val msgStore = tx.objectStore(MESSAGE)
        val evtStore = tx.objectStore(EVENT)
        ids.forEach { id ->
            msgStore.delete(arrayOf(account, id))
            evtStore.delete(arrayOf(account, id))
        }
        awaitTx(tx)
    }

    override suspend fun evictToByteBudget(
        account: String,
        maxBytes: Long,
    ) {
        // Gather (store, key, created_at, bytes) for the account, oldest-first, delete until under.
        val rows = (sizeRows(MESSAGE, account) + sizeRows(EVENT, account)).sortedBy { it.createdAt }
        var total = rows.sumOf { it.bytes }
        if (total <= maxBytes) return
        val database = db()
        val tx = database.transaction(arrayOf(MESSAGE, EVENT), "readwrite")
        for (row in rows) {
            if (total <= maxBytes) break
            tx.objectStore(row.store).delete(arrayOf(account, row.id))
            total -= row.bytes
        }
        awaitTx(tx)
    }

    override suspend fun clearAccount(account: String) {
        val database = db()
        val tx = database.transaction(arrayOf(MESSAGE, EVENT), "readwrite")
        // Delete every row whose compound key starts with [account].
        val range = boundAccount(account)
        deleteByRange(tx.objectStore(MESSAGE), range)
        deleteByRange(tx.objectStore(EVENT), range)
        awaitTx(tx)
    }

    // ── Cursor + range helpers ──────────────────────────────────────────────

    private suspend fun cursorMessages(
        range: dynamic,
        limit: Int,
    ): List<CachedMsg> {
        val database = db()
        val store = database.transaction(MESSAGE, "readonly").objectStore(MESSAGE)
        val out = ArrayList<CachedMsg>(limit)
        // One continuation drives the whole cursor and resumes exactly once. Re-registering the
        // request's handlers per step (and re-awaiting it) can resume a continuation twice under
        // real IDB timing, which throws background "already resumed" errors even when the result
        // is correct — so iterate inside a single handler instead.
        drainCursor(store.index(MSG_GROUP_TIME).openCursor(range, "prev")) { cursor ->
            out.add(toCachedMsg(cursor.value))
            out.size < limit // keep going until the page is full
        }
        return out
    }

    private class SizeRow(val store: String, val id: String, val createdAt: Long, val bytes: Long)

    private suspend fun sizeRows(
        storeName: String,
        account: String,
    ): List<SizeRow> {
        val database = db()
        val store = database.transaction(storeName, "readonly").objectStore(storeName)
        val out = ArrayList<SizeRow>()
        drainCursor(store.openCursor(boundAccount(account), "next")) { cursor ->
            val v = cursor.value
            // DMs are excluded from the byte budget so a conversation is never evicted.
            if (!(storeName == MESSAGE && v.kind.unsafeCast<Int>() in DM_CACHE_KINDS)) {
                val bytes = (v.content.unsafeCast<String>().length + v.tags_json.unsafeCast<String>().length + 120).toLong()
                out.add(SizeRow(storeName, v.id.unsafeCast<String>(), (v.created_at.unsafeCast<Double>()).toLong(), bytes))
            }
            true
        }
        return out
    }

    private suspend fun deleteByRange(
        store: dynamic,
        range: dynamic,
    ) {
        drainCursor(store.openCursor(range, "next")) { cursor ->
            cursor.delete()
            true
        }
    }

    /**
     * Drive an IndexedDB cursor to completion with a single continuation. [onRow] handles each
     * row and returns true to advance, false to stop early. Resumes exactly once (cursor null,
     * an early stop, or an error), so no step can double-resume the continuation.
     */
    private suspend fun drainCursor(
        cursorReq: dynamic,
        onRow: (cursor: dynamic) -> Boolean,
    ): Unit = suspendCancellableCoroutine { cont ->
        cursorReq.onsuccess = { _: dynamic ->
            // try/catch is essential: an exception in onRow/toCachedMsg (a malformed cached
            // row) thrown inside this IDB callback would otherwise leave the continuation
            // unresumed forever — hanging loadBefore and dead-locking scroll-back pagination.
            try {
                val cursor = cursorReq.result
                if (cursor == null) {
                    cont.resume(Unit)
                } else if (onRow(cursor)) {
                    cursor.`continue`()
                } else {
                    cont.resume(Unit)
                }
            } catch (e: Throwable) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
        cursorReq.onerror = { _: dynamic -> cont.resumeWithException(RuntimeException("IndexedDB cursor failed")) }
    }

    private fun boundGroup(
        account: String,
        groupId: String,
        upperCreatedAt: Long?,
        upperExclusive: Boolean,
    ): dynamic {
        val keyRange = window.asDynamic().IDBKeyRange
        val lower = arrayOf<dynamic>(account, groupId, MIN_TIME)
        val upper = arrayOf<dynamic>(account, groupId, upperCreatedAt?.toDouble() ?: MAX_TIME)
        return keyRange.bound(lower, upper, false, upperExclusive)
    }

    private fun boundKind(account: String, kind: Int): dynamic {
        val keyRange = window.asDynamic().IDBKeyRange
        return keyRange.bound(
            arrayOf<dynamic>(account, kind, MIN_TIME),
            arrayOf<dynamic>(account, kind, MAX_TIME),
            false,
            false,
        )
    }

    private fun boundAccount(account: String): dynamic {
        val keyRange = window.asDynamic().IDBKeyRange
        // Compound-key prefix range: [account] .. [account, "￿"].
        return keyRange.bound(arrayOf<dynamic>(account), arrayOf<dynamic>(account, "￿"), false, false)
    }

    private fun toCachedMsg(v: dynamic): CachedMsg = CachedMsg(
        id = v.id.unsafeCast<String>(),
        groupId = v.group_id.unsafeCast<String>(),
        pubkey = v.pubkey.unsafeCast<String>(),
        createdAt = (v.created_at.unsafeCast<Double>()).toLong(),
        kind = v.kind.unsafeCast<Int>(),
        content = v.content.unsafeCast<String>(),
        tagsJson = v.tags_json.unsafeCast<String>(),
    )

    private fun toEventRow(v: dynamic): CachedEventRow = CachedEventRow(
        id = v.id.unsafeCast<String>(),
        pubkey = v.pubkey.unsafeCast<String>(),
        createdAt = (v.created_at.unsafeCast<Double>()).toLong(),
        kind = v.kind.unsafeCast<Int>(),
        content = v.content.unsafeCast<String>(),
        tagsJson = v.tags_json.unsafeCast<String>(),
    )

    private suspend fun <T> awaitRequest(req: dynamic): T = suspendCancellableCoroutine { cont ->
        req.onsuccess = { _: dynamic -> cont.resume(req.result.unsafeCast<T>()) }
        req.onerror = { _: dynamic -> cont.resumeWithException(RuntimeException("IndexedDB request failed")) }
    }

    private suspend fun awaitTx(tx: dynamic): Unit = suspendCancellableCoroutine { cont ->
        tx.oncomplete = { _: dynamic -> cont.resume(Unit) }
        tx.onerror = { _: dynamic -> cont.resumeWithException(RuntimeException("IndexedDB transaction failed")) }
        tx.onabort = { _: dynamic -> cont.resumeWithException(RuntimeException("IndexedDB transaction aborted")) }
    }

    private companion object {
        const val DB_NAME = "nostrord_cache_db"

        /** 2 added the (account, kind, created_at) index the DM hydration reads. */
        const val DB_VERSION = 2
        const val MESSAGE = "message"
        const val EVENT = "event"
        const val MSG_GROUP_TIME = "group_time"
        const val MSG_KIND_TIME = "kind_time"

        // created_at bounds for compound-key ranges (nostr seconds sit well inside).
        const val MIN_TIME = 0.0
        const val MAX_TIME = 1.0e15
    }
}
