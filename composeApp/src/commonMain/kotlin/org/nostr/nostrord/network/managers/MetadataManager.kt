package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import org.nostr.nostrord.network.CachedEvent
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.cache.CacheStore
import org.nostr.nostrord.storage.cache.CachedEventRow
import org.nostr.nostrord.storage.cache.InMemoryCacheStore
import org.nostr.nostrord.utils.LruCache
import org.nostr.nostrord.utils.epochMillis
import org.nostr.nostrord.utils.normalizeRelayUrl

/** How long a by-id fetch waits for a gated relay's NIP-42 to land before giving up on it. */
private const val EVENT_FETCH_AUTH_TIMEOUT_MS = 8_000L

/** How long a by-id fetch blocks an identical one. Must stay under the UI's give-up window. */
private const val EVENT_FETCH_RETRY_AFTER_MS = 4_000L

class MetadataManager(
    private val connectionManager: ConnectionManager,
    private val outboxManager: OutboxManager,
    private val scope: CoroutineScope,
    private val cacheStore: CacheStore = InMemoryCacheStore(),
    // Active account (pubkey hex) for scoping the event cache; null/blank skips persistence.
    private val accountProvider: () -> String? = { null },
) {
    /** Set by NostrRepository so batchFetch can reconnect bootstrap relays when all are offline. */
    var messageHandler: ((String, NostrGroupClient) -> Unit)? = null

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        // Holds enough profiles for a large active room so members don't get evicted (and their
        // avatars blanked) mid-session. A UserMetadata is small (a few hundred bytes), so a few
        // thousand entries is ~1-2 MB. The disk-backed store (see docs/metadata-cache-plan.md)
        // later makes eviction fall back to disk instead of the network.
        const val MAX_METADATA_CACHE_SIZE = 5000
        const val MAX_EVENTS_CACHE_SIZE = 500

        /** Metadata older than this is considered stale and will be re-fetched. */
        const val STALE_THRESHOLD_MS = 30 * 60 * 1000L

        /**
         * How long to suppress retries for a pubkey whose previous batch finished
         * without returning any kind:0 event. Without this, every UI render that
         * triggers requestUserMetadata refires the same single-author REQ to every
         * bootstrap relay every few seconds (observed: ~60 duplicate REQs per relay
         * on cold start, dominating the profile-fetch fan-out).
         */
        const val NEGATIVE_CACHE_TTL_MS = 5 * 60 * 1000L

        /** Number of retry attempts before giving up on a metadata fetch. */
        const val MAX_FETCH_ATTEMPTS = 3

        /** Max authors per REQ filter — keeps relay filter sizes reasonable. */
        const val BATCH_SIZE = 50

        /** Coalesce window for metadata StateFlow emissions (ms). */
        const val METADATA_FLUSH_DELAY_MS = 100L

        /**
         * How long to accumulate pubkeys from concurrent callers before firing the
         * outgoing REQ. Event handlers fire one [requestUserMetadata] per incoming
         * kind:9 / kind:7 / kind:0 event; without this, 50 mux events become 50
         * separate batches × 4 bootstrap relays = ~200 REQs in a single burst.
         * 100 ms is large enough to catch the post-EOSE burst and small enough
         * that interactive flows (open profile modal) don't feel laggy.
         */
        const val COALESCE_WINDOW_MS = 100L

        /** Wall-clock fallback if a relay never emits EOSE for a metadata batch. */
        const val EOSE_FALLBACK_MS = 5_000L

        /** Debounce before snapshotting the metadata cache to disk after a change. */
        const val DISK_PERSIST_DELAY_MS = 5_000L

        /**
         * Caps how many [batchFetch] runs (send + EOSE wait) may be in flight at once.
         * requestUserMetadata fires one batchFetch per COALESCE_WINDOW_MS coalescing
         * window; a busy cold boot spreads many small pubkey sets across many windows
         * a few hundred ms apart, and each batch dispatches to the same handful of
         * bootstrap relays. Without a cap, dozens of these can be genuinely concurrent
         * (a Playwright capture observed 68 separate metadata_batch_ REQs open on one
         * relay at once), tripping its "too many concurrent REQs" limit. Extra callers
         * queue on the semaphore instead of firing immediately.
         */
        const val MAX_CONCURRENT_BATCH_FETCHES = 3
    }

    private val batchFetchSemaphore = Semaphore(MAX_CONCURRENT_BATCH_FETCHES)

    /** On-disk form of a [CachedMetadata] entry (see the global user-metadata store). */
    @Serializable
    private data class PersistedMetadata(
        val pubkey: String,
        val metadata: UserMetadata,
        val createdAt: Long,
        val fetchedAt: Long,
    )

    private val persistedMetadataListSerializer = ListSerializer(PersistedMetadata.serializer())

    /**
     * Cached kind:0 entry. [createdAt] is the event's own timestamp (seconds, NIP-01) and
     * gates stale-echo rejection; [fetchedAt] is the wall-clock time we last received it
     * and gates the 30-min refresh on incoming messages.
     */
    data class CachedMetadata(
        val metadata: UserMetadata,
        val createdAt: Long,
        val fetchedAt: Long,
    )

    private val metadataCache = LruCache<String, CachedMetadata>(MAX_METADATA_CACHE_SIZE)
    private val eventsCache = LruCache<String, CachedEvent>(MAX_EVENTS_CACHE_SIZE)

    /**
     * Pubkey → wall-clock time we last finished a batch fetch that returned no
     * kind:0 for that pubkey. Suppresses repeat fetches for [NEGATIVE_CACHE_TTL_MS]
     * so a non-existent profile doesn't keep getting re-asked from every UI render.
     */
    private val negativeMetadataCache = LruCache<String, Long>(MAX_METADATA_CACHE_SIZE)

    private val inFlightPubkeys = mutableSetOf<String>()
    private val inFlightMutex = Mutex()
    private val inFlightEvents = mutableSetOf<String>()
    private val inFlightEventsMutex = Mutex()

    /**
     * Pubkeys waiting to be flushed into a single batched REQ. Populated by
     * [requestUserMetadata] under [pendingMutex]; drained by the flush coroutine
     * scheduled when the set transitions from empty to non-empty.
     */
    private val pendingPubkeys = mutableSetOf<String>()
    private val pendingMutex = Mutex()
    private var pendingForceStale = false

    /**
     * Per-batch state for EOSE-driven completion of [batchFetch]. Each batch
     * waits on its [deferred] until every relay in [pendingRelays] has emitted
     * EOSE for the batch subscription id, or the wall-clock fallback fires.
     */
    private data class MetadataBatch(
        val pendingRelays: MutableSet<String>,
        val deferred: CompletableDeferred<Unit>,
    )

    private val metadataBatches = mutableMapOf<String, MetadataBatch>()
    private val metadataBatchesMutex = Mutex()
    private var metadataBatchCounter = 0L

    private val _userMetadata = MutableStateFlow<Map<String, UserMetadata>>(emptyMap())
    val userMetadata: StateFlow<Map<String, UserMetadata>> = _userMetadata.asStateFlow()

    private val _cachedEvents = MutableStateFlow<Map<String, CachedEvent>>(emptyMap())
    val cachedEvents: StateFlow<Map<String, CachedEvent>> = _cachedEvents.asStateFlow()

    fun requestUserMetadata(
        pubkeys: Set<String>,
        messageHandler: (String, NostrGroupClient) -> Unit,
        forceStale: Boolean = false,
    ) {
        if (pubkeys.isEmpty()) return

        scope.launch {
            val now = epochMillis()
            val toEnqueue =
                inFlightMutex.withLock {
                    pubkeys
                        .filter { pk ->
                            if (pk in inFlightPubkeys) return@filter false
                            if (metadataCache.get(pk) != null && !(forceStale && isStale(pk))) {
                                return@filter false
                            }
                            // Skip pubkeys whose previous batch returned no kind:0
                            // within the TTL — relays don't suddenly start hosting
                            // metadata for an unknown user inside 5 minutes.
                            val negAt = negativeMetadataCache.get(pk)
                            if (!forceStale && negAt != null && now - negAt < NEGATIVE_CACHE_TTL_MS) {
                                return@filter false
                            }
                            true
                        }.also { inFlightPubkeys.addAll(it) }
                }
            if (toEnqueue.isEmpty()) return@launch

            // Enqueue and (only on empty → non-empty transition) schedule the flush.
            // Concurrent callers within COALESCE_WINDOW_MS land in the same pending
            // set, so a burst of N event handlers becomes one batched REQ per relay
            // instead of N separate ones.
            val shouldSchedule = pendingMutex.withLock {
                val wasEmpty = pendingPubkeys.isEmpty()
                pendingPubkeys.addAll(toEnqueue)
                pendingForceStale = pendingForceStale || forceStale
                wasEmpty
            }
            if (shouldSchedule) {
                scope.launch { flushPending() }
            }
        }
    }

    private suspend fun flushPending() {
        delay(COALESCE_WINDOW_MS)
        val toFetch = pendingMutex.withLock {
            val snapshot = pendingPubkeys.toList()
            pendingPubkeys.clear()
            pendingForceStale = false
            snapshot
        }
        if (toFetch.isEmpty()) return

        val fetchStartedAt = epochMillis()
        try {
            batchFetchSemaphore.withPermit { batchFetch(toFetch) }
        } finally {
            val finishedAt = epochMillis()
            inFlightMutex.withLock {
                inFlightPubkeys.removeAll(toFetch.toSet())
                // Mark pubkeys whose batch returned nothing fresh so the next
                // caller doesn't refetch immediately. "Nothing fresh" = no
                // cache entry OR an entry that predates this batch.
                toFetch.forEach { pk ->
                    val fetchedAt = metadataCache.get(pk)?.fetchedAt
                    if (fetchedAt == null || fetchedAt < fetchStartedAt) {
                        negativeMetadataCache.put(pk, finishedAt)
                    } else {
                        negativeMetadataCache.remove(pk)
                    }
                }
            }
        }
    }

    private suspend fun batchFetch(pubkeys: List<String>) {
        // Record the fetch start so we can distinguish "fetched before this call"
        // from "fetched during this call" for stale refresh scenarios.
        val fetchStartedAt = epochMillis()

        // Source the active account's relay list from OutboxManager (in-memory,
        // pubkey-scoped) instead of SecureStorage. The on-disk slot used to be
        // global and would leak another account's relays into this filter.
        val nip29Relays =
            outboxManager.kind10009Relays.value +
                connectionManager.currentRelayUrl.value

        val candidates =
            outboxManager.bootstrapRelays
                .filter { it !in nip29Relays }

        // Relays we have already dispatched this batch's author set to. Re-sending
        // the identical filter to the same relay a few seconds later cannot yield a
        // different result, so retries skip them and target only relays we could
        // NOT reach yet (offline / not connected), which is the loop's real
        // purpose. This is send-based, not EOSE-based, on purpose: a relay whose
        // EOSE we never observe (e.g. metadata that rode the NIP-46 bunker socket,
        // whose handler does not route metadata EOSE) would otherwise be re-queried
        // every attempt forever — it was ~2/3 of all metadata REQs.
        val completedRelays = mutableSetOf<String>()

        repeat(MAX_FETCH_ATTEMPTS) { attempt ->
            val missing =
                pubkeys.filter { pk ->
                    val fetchedAt = metadataCache.get(pk)?.fetchedAt
                    fetchedAt == null || fetchedAt < fetchStartedAt
                }
            if (missing.isEmpty()) return

            // completedRelays is keyed by the client's own (normalized) URL, the
            // same key notifyMetadataEose removes by, so compare candidates the
            // same way.
            val activeCandidates = candidates.filter { it.normalizeRelayUrl() !in completedRelays }
            // Every reachable relay has already answered — nothing left to retry.
            if (activeCandidates.isEmpty()) return

            // Register the batch BEFORE sending any REQ so a fast relay's EOSE
            // is not dropped between dispatch and registration.
            val deferred = CompletableDeferred<Unit>()
            val (subId, pendingRelays) = metadataBatchesMutex.withLock {
                val id = "metadata_batch_${++metadataBatchCounter}_$attempt"
                val relays = mutableSetOf<String>()
                metadataBatches[id] = MetadataBatch(relays, deferred)
                id to relays
            }

            // Relays we actually dispatched to this attempt (kept even after their
            // EOSE removes them from pendingRelays), so we can mark the EOSEd ones
            // complete once the wait settles.
            val sentRelays = mutableSetOf<String>()

            suspend fun trySendOn(client: NostrGroupClient?, relayUrl: String): Boolean {
                if (client == null || !client.isConnected()) return false
                // Key the relay by the client's own URL (normalized), because the
                // EOSE handler reports the source as client.getRelayUrl(); the
                // bootstrap-list string can differ (e.g. a bunker relay carries a
                // trailing slash), and a mismatch left the relay forever "pending"
                // so the batch always timed out and re-queried just that relay.
                val key = client.getRelayUrl().normalizeRelayUrl()
                metadataBatchesMutex.withLock {
                    pendingRelays.add(key)
                    sentRelays.add(key)
                }
                return try {
                    missing.chunked(BATCH_SIZE).forEach { chunk ->
                        client.requestMetadata(chunk, subId)
                    }
                    true
                } catch (_: Exception) {
                    val complete = metadataBatchesMutex.withLock {
                        sentRelays.remove(key)
                        pendingRelays.remove(key) && pendingRelays.isEmpty()
                    }
                    if (complete) deferred.complete(Unit)
                    false
                }
            }

            // Dispatch in parallel so we don't pay per-relay round-trip latency
            // serially — all REQs go in flight before we start waiting on EOSE.
            coroutineScope {
                activeCandidates.map { relayUrl ->
                    async { trySendOn(connectionManager.getClientForRelay(relayUrl), relayUrl) }
                }.awaitAll()
            }

            // All bootstrap relays offline — try to reconnect one.
            val anySent = metadataBatchesMutex.withLock { pendingRelays.isNotEmpty() }
            if (!anySent) {
                val handler = messageHandler
                if (handler != null) {
                    activeCandidates.firstOrNull { relayUrl ->
                        runCatching {
                            trySendOn(connectionManager.getOrConnectRelay(relayUrl, handler), relayUrl)
                        }.getOrDefault(false)
                    }
                }
            }

            val finalSent = metadataBatchesMutex.withLock { pendingRelays.isNotEmpty() }
            if (finalSent) {
                // Wait for EOSE from every relay we sent to; bounded fallback in
                // case a relay never EOSEs so we don't hang the next attempt.
                withTimeoutOrNull(EOSE_FALLBACK_MS) { deferred.await() }
                metadataBatchesMutex.withLock {
                    // Every relay we successfully sent to is done for this batch,
                    // whether or not we observed its EOSE. Re-querying it can't help.
                    completedRelays.addAll(sentRelays)
                    metadataBatches.remove(subId)
                }

                val allFresh =
                    pubkeys.all { pk ->
                        val at = metadataCache.get(pk)?.fetchedAt
                        at != null && at >= fetchStartedAt
                    }
                if (allFresh) return
            } else {
                metadataBatchesMutex.withLock { metadataBatches.remove(subId) }
                if (attempt < MAX_FETCH_ATTEMPTS - 1) {
                    // No bootstrap relay was reachable — back off briefly before retry.
                    delay(2_000L)
                }
            }
        }
    }

    /**
     * Marks [sourceRelayUrl] as done for the given batch [subId]. When every
     * relay we dispatched the batch to has EOSEd, the batch's deferred
     * completes and [batchFetch] proceeds to the next attempt (or returns).
     */
    suspend fun notifyMetadataEose(subId: String, sourceRelayUrl: String) {
        if (!subId.startsWith("metadata_batch_")) return
        val key = sourceRelayUrl.normalizeRelayUrl()
        val toComplete = metadataBatchesMutex.withLock {
            val batch = metadataBatches[subId] ?: return@withLock null
            batch.pendingRelays.remove(key)
            if (batch.pendingRelays.isEmpty()) batch.deferred else null
        }
        toComplete?.complete(Unit)
    }

    suspend fun requestEventById(
        eventId: String,
        relayHints: List<String> = emptyList(),
        author: String? = null,
        /** Group the reference was seen in. Some NIP-29 relays answer an ids-only REQ with
         *  nothing and require the `#h` filter even for a lookup by id. */
        groupId: String? = null,
        messageHandler: (String, NostrGroupClient) -> Unit,
    ) {
        suspend fun NostrGroupClient.reqEvent() {
            // Always ask by id alone. An external event (a kind:1 nevent quoted inside a group)
            // carries no `h` tag, so an #h-scoped filter matches nothing on any relay, including
            // the hint relay dialed below specifically to serve it.
            requestEventById(eventId)
            // The #h lookup is additive, and a separate subscription rather than a second filter
            // on the same REQ: a strict NIP-29 relay CLOSEs a REQ whose filter omits `#h`, which
            // would take the group lookup down with it.
            if (groupId != null) requestGroupMessageById(groupId, eventId)
        }
        // Skip if already cached or already in-flight
        if (_cachedEvents.value.containsKey(eventId)) return
        // Disk fallback: resolve a previously-seen event from the persistent cache before any
        // network REQ (cold-start quotes/reactions/replies, and a cheap re-fetch avoidance when
        // the in-memory LRU has evicted it).
        val account = accountProvider()?.takeIf { it.isNotBlank() }
        if (account != null) {
            val persisted =
                try {
                    cacheStore.getEvent(account, eventId)
                } catch (_: Exception) {
                    null
                }
            if (persisted != null) {
                eventsCache.put(eventId, persisted.toCachedEvent())
                _cachedEvents.value = eventsCache.toMap()
                return
            }
        }
        val shouldFetch =
            inFlightEventsMutex.withLock {
                if (inFlightEvents.contains(eventId)) {
                    false
                } else {
                    inFlightEvents.add(eventId)
                    true
                }
            }
        if (!shouldFetch) return

        try {
            // Broadcast to EVERY connected relay, not just the nevent's relay hint. A NIP-29 event
            // lives only on its group's relay, and the hint is often wrong or absent (it points at
            // the relay it was copied from, not the event's home), so querying only the hint misses
            // it while another connected relay actually has it. This is how native effectively
            // resolves these — it has those relays connected. (Includes the focused + author
            // outbox relays already in the pool.)
            val connected = connectionManager.getAllConnectedClients()
            connected.forEach { client ->
                try {
                    client.reqEvent()
                } catch (_: Exception) {
                }
            }

            // An auth-gating relay withholds its group events until NIP-42 completes, and answers
            // the REQ above with CLOSED "auth-required" — which is why a quoted kind:11 in a private
            // group resolved only when the socket happened to be authed already (the card read as removed
            // otherwise). Re-ask each gated relay once AUTH lands. Launched per client so a slow
            // (bunker) signer on one relay does not hold up the others.
            connected
                .filter { it.requiresAuth() && !it.hasAuthSucceeded() }
                .forEach { client ->
                    scope.launch {
                        if (client.awaitAuthOrTimeout(EVENT_FETCH_AUTH_TIMEOUT_MS) && !_cachedEvents.value.containsKey(eventId)) {
                            try {
                                client.reqEvent()
                            } catch (_: Exception) {
                            }
                        }
                    }
                }

            // Plus any relay hint we are NOT connected to: connect on demand and REQ there, so a
            // correct hint to a relay outside the pool (an event whose group relay we never joined)
            // still resolves. getOrConnectRelay is singleflight + pool-checked; messageHandler wires
            // the freshly-connected client so its reply is cached.
            relayHints
                .filter { it.isNotBlank() }
                .distinct()
                .forEach { hint ->
                    try {
                        val existing = connectionManager.getClientForRelay(hint)
                        if (existing == null || !existing.isConnected()) {
                            connectionManager.getOrConnectRelay(hint, messageHandler)?.reqEvent()
                        }
                    } catch (_: Exception) {
                    }
                }
        } finally {
            // Release the in-flight guard sooner than the reference card gives up (5s), or the
            // card's Retry button lands inside the guard and no-ops silently.
            scope.launch {
                delay(EVENT_FETCH_RETRY_AFTER_MS)
                inFlightEventsMutex.withLock { inFlightEvents.remove(eventId) }
            }
        }
    }

    suspend fun requestAddressableEvent(
        kind: Int,
        pubkey: String,
        identifier: String,
        relayHints: List<String> = emptyList(),
        messageHandler: (String, NostrGroupClient) -> Unit,
    ) {
        // Create composite key for caching
        val addressKey = "$kind:$pubkey:$identifier"

        // Skip if already cached or already in-flight
        if (_cachedEvents.value.containsKey(addressKey)) return
        val shouldFetch =
            inFlightEventsMutex.withLock {
                if (inFlightEvents.contains(addressKey)) {
                    false
                } else {
                    inFlightEvents.add(addressKey)
                    true
                }
            }
        if (!shouldFetch) return

        try {
            // Use outbox model for relay selection
            val relaysToTry =
                outboxManager.selectConnectedOutboxRelays(
                    authors = listOf(pubkey),
                    explicitRelays = relayHints,
                )

            // All relays in relaysToTry are already connected (pre-filtered).
            var sent =
                relaysToTry.count { relayUrl ->
                    try {
                        connectionManager.getClientForRelay(relayUrl)!!.requestAddressableEvent(kind, pubkey, identifier)
                        true
                    } catch (_: Exception) {
                        false
                    }
                }

            // Always also try focused NIP-29 relay — it hosts the addressable events.
            val focused = connectionManager.getFocusedClient()
            if (focused != null && focused.isConnected()) {
                try {
                    focused.requestAddressableEvent(kind, pubkey, identifier)
                    sent++
                } catch (_: Exception) {
                }
            }
        } finally {
            scope.launch {
                delay(10_000)
                inFlightEventsMutex.withLock { inFlightEvents.remove(addressKey) }
            }
        }
    }

    private var metadataFlushJob: Job? = null

    /**
     * Accepts an incoming kind:0 unless it is a stale or empty echo that would clobber a
     * better cached entry. Without this guard, idle clients gradually lose all profiles:
     * relays occasionally re-emit older or empty-content kind:0 events for a pubkey, and
     * the periodic stale-refresh would accept them blindly.
     */
    fun handleMetadataEvent(
        pubkey: String,
        metadata: UserMetadata,
        createdAt: Long,
    ) {
        val cached = metadataCache.get(pubkey)
        if (cached != null) {
            // Older event: reject — we already have a newer one.
            if (createdAt < cached.createdAt) return
            if (createdAt == cached.createdAt) {
                // Identical echo (same ts, same content): refresh fetchedAt only, no flush.
                if (metadata == cached.metadata) {
                    metadataCache.put(pubkey, cached.copy(fetchedAt = epochMillis()))
                    return
                }
                // Same ts, content disagrees: prefer the non-empty one.
                if (metadata.isEmpty() && !cached.metadata.isEmpty()) return
            }
            // createdAt > cached.createdAt falls through and overwrites — a strictly newer
            // empty event is treated as a deliberate profile wipe and is allowed.
        }
        metadataCache.put(pubkey, CachedMetadata(metadata, createdAt, epochMillis()))
        scheduleMetadataFlush()
        scheduleDiskPersist()
    }

    /**
     * Pre-populates the cache + StateFlow from the on-disk store so names/avatars show
     * instantly on cold start, without waiting for the network. Must run AFTER
     * [SecureStorage.preloadMetadata] on web (IndexedDB reads are async there). Restored
     * entries keep their original fetchedAt so a stale one (>30 min) still revalidates on
     * the next incoming event; it does NOT mark anything as fetched, so refresh still runs.
     */
    fun restoreFromCache() {
        try {
            val cached = SecureStorage.getUserMetadataCache()
            if (cached.isNullOrBlank()) return
            val entries = json.decodeFromString(persistedMetadataListSerializer, cached)
            entries.forEach { e ->
                val existing = metadataCache.get(e.pubkey)
                if (existing == null || e.createdAt > existing.createdAt) {
                    metadataCache.put(e.pubkey, CachedMetadata(e.metadata, e.createdAt, e.fetchedAt))
                }
            }
            if (entries.isNotEmpty()) publishMetadataSnapshot()
        } catch (_: Exception) {
            // Corrupted cache — start fresh.
        }
    }

    private var diskPersistJob: Job? = null

    /** Debounced snapshot of the (recency-bounded) cache to the global on-disk store. */
    private fun scheduleDiskPersist() {
        diskPersistJob?.cancel()
        diskPersistJob =
            scope.launch {
                delay(DISK_PERSIST_DELAY_MS)
                val entries =
                    metadataCache.toMap().map { (pk, c) ->
                        PersistedMetadata(pk, c.metadata, c.createdAt, c.fetchedAt)
                    }
                // Never overwrite the store with an empty snapshot (e.g. if a logout cleared
                // the in-memory cache between the schedule and the write).
                if (entries.isEmpty()) return@launch
                try {
                    SecureStorage.saveUserMetadataCache(json.encodeToString(persistedMetadataListSerializer, entries))
                } catch (_: Exception) {
                }
            }
    }

    private fun publishMetadataSnapshot() {
        val snapshot = HashMap<String, UserMetadata>(metadataCache.size())
        metadataCache.toMap().forEach { (pubkey, cached) -> snapshot[pubkey] = cached.metadata }
        _userMetadata.value = snapshot
    }

    private fun scheduleMetadataFlush() {
        metadataFlushJob?.cancel()
        metadataFlushJob =
            scope.launch {
                delay(METADATA_FLUSH_DELAY_MS)
                publishMetadataSnapshot()
            }
    }

    private fun flushMetadataNow() {
        metadataFlushJob?.cancel()
        publishMetadataSnapshot()
    }

    fun updateLocalMetadata(
        pubkey: String,
        metadata: UserMetadata,
        createdAt: Long,
    ) {
        metadataCache.put(pubkey, CachedMetadata(metadata, createdAt, epochMillis()))
        flushMetadataNow()
        scheduleDiskPersist()
    }

    private fun UserMetadata.isEmpty(): Boolean = name.isNullOrBlank() &&
        displayName.isNullOrBlank() &&
        picture.isNullOrBlank() &&
        about.isNullOrBlank() &&
        nip05.isNullOrBlank() &&
        banner.isNullOrBlank() &&
        lud16.isNullOrBlank() &&
        lud06.isNullOrBlank() &&
        website.isNullOrBlank()

    fun handleCachedEvent(event: CachedEvent) {
        eventsCache.put(event.id, event)
        _cachedEvents.value = eventsCache.toMap()
        persistEvent(event)
    }

    private val eventTagsSerializer = ListSerializer(ListSerializer(String.serializer()))

    /** Write a generic event through to the persistent cache so it resolves on cold start. */
    private fun persistEvent(event: CachedEvent) {
        val account = accountProvider()?.takeIf { it.isNotBlank() } ?: return
        scope.launch {
            try {
                cacheStore.upsertEvents(
                    account,
                    listOf(
                        CachedEventRow(
                            id = event.id,
                            pubkey = event.pubkey,
                            createdAt = event.createdAt,
                            kind = event.kind,
                            content = event.content,
                            tagsJson = json.encodeToString(eventTagsSerializer, event.tags),
                        ),
                    ),
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun CachedEventRow.toCachedEvent(): CachedEvent = CachedEvent(
        id = id,
        pubkey = pubkey,
        kind = kind,
        content = content,
        createdAt = createdAt,
        tags = try {
            json.decodeFromString(eventTagsSerializer, tagsJson)
        } catch (_: Exception) {
            emptyList()
        },
    )

    fun parseAndCacheEvent(eventJson: JsonObject, relayUrl: String? = null): CachedEvent? {
        return try {
            val eventId = eventJson["id"]?.jsonPrimitive?.content ?: return null

            // A copy already in the cache wins, but an unstamped one (disk restore) takes the
            // relay this delivery came from: readers scope quote/reply previews on it.
            eventsCache.get(eventId)?.let { existing ->
                if (existing.relayUrl == null && relayUrl != null) {
                    val stamped = existing.copy(relayUrl = relayUrl.normalizeRelayUrl())
                    eventsCache.put(eventId, stamped)
                    _cachedEvents.value = eventsCache.toMap()
                    return stamped
                }
                return existing
            }

            val pubkey = eventJson["pubkey"]?.jsonPrimitive?.content ?: return null
            val content = eventJson["content"]?.jsonPrimitive?.content ?: ""
            val createdAt = eventJson["created_at"]?.jsonPrimitive?.long ?: 0L
            val kind = eventJson["kind"]?.jsonPrimitive?.int ?: 1
            val tags =
                eventJson["tags"]?.jsonArray?.map { tagArray ->
                    tagArray.jsonArray.map { it.jsonPrimitive.content }
                } ?: emptyList()

            val cachedEvent =
                CachedEvent(
                    id = eventId,
                    pubkey = pubkey,
                    kind = kind,
                    content = content,
                    createdAt = createdAt,
                    tags = tags,
                    relayUrl = relayUrl?.normalizeRelayUrl(),
                )

            eventsCache.put(eventId, cachedEvent)
            _cachedEvents.value = eventsCache.toMap()
            persistEvent(cachedEvent)
            cachedEvent
        } catch (e: Exception) {
            null
        }
    }

    fun parseAndCacheAddressableEvent(eventJson: JsonObject, relayUrl: String? = null): CachedEvent? {
        return try {
            val eventId = eventJson["id"]?.jsonPrimitive?.content ?: return null
            val pubkey = eventJson["pubkey"]?.jsonPrimitive?.content ?: return null
            val content = eventJson["content"]?.jsonPrimitive?.content ?: ""
            val createdAt = eventJson["created_at"]?.jsonPrimitive?.long ?: 0L
            val kind = eventJson["kind"]?.jsonPrimitive?.int ?: 1
            val tags =
                eventJson["tags"]?.jsonArray?.map { tagArray ->
                    tagArray.jsonArray.map { it.jsonPrimitive.content }
                } ?: emptyList()

            val identifier = tags.find { it.firstOrNull() == "d" }?.getOrNull(1) ?: ""
            val addressKey = "$kind:$pubkey:$identifier"
            eventsCache.get(addressKey)?.let { return it }

            val cachedEvent =
                CachedEvent(
                    id = eventId,
                    pubkey = pubkey,
                    kind = kind,
                    content = content,
                    createdAt = createdAt,
                    tags = tags,
                    relayUrl = relayUrl?.normalizeRelayUrl(),
                )

            eventsCache.put(eventId, cachedEvent)
            eventsCache.put(addressKey, cachedEvent)
            _cachedEvents.value = eventsCache.toMap()
            persistEvent(cachedEvent)
            cachedEvent
        } catch (_: Exception) {
            null
        }
    }

    fun hasMetadata(pubkey: String): Boolean = metadataCache.containsKey(pubkey)

    fun isStale(pubkey: String): Boolean {
        val cached = metadataCache.get(pubkey) ?: return true
        return (epochMillis() - cached.fetchedAt) > STALE_THRESHOLD_MS
    }

    fun hasCachedEvent(eventId: String): Boolean = eventsCache.containsKey(eventId)

    fun getMetadata(pubkey: String): UserMetadata? = metadataCache.get(pubkey)?.metadata

    fun getCachedEvent(eventId: String): CachedEvent? = eventsCache.get(eventId)

    fun clear() {
        // Cancel any pending persist so it can't write the about-to-be-emptied cache over
        // the on-disk store (which is global and survives logout for fast re-login).
        diskPersistJob?.cancel()
        metadataCache.clear()
        eventsCache.clear()
        _userMetadata.value = emptyMap()
        _cachedEvents.value = emptyMap()
    }
}
