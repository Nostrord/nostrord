package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.nostr.NostrFilter
import org.nostr.nostrord.nostr.Spell
import org.nostr.nostrord.nostr.SpellContext
import org.nostr.nostrord.nostr.resolveChunked
import org.nostr.nostrord.nostr.targetRelays
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result

/** Subscription id namespace. NostrRepository routes inbound frames on this prefix. */
const val SPELL_SUB_PREFIX = "spell"

/**
 * Runs saved queries (kind:777) as live feeds.
 *
 * One open spell means one subscription id fanned out to every target relay, so the loading
 * state machine, the ordering buffer and the cursor are all keyed by [Spell.key] exactly the
 * way group chat keys them by group id.
 *
 * Events are held in memory only, capped at [MAX_EVENTS_PER_SPELL]: a kind:1 feed is a
 * firehose and nothing here is worth surviving a restart.
 */
class SpellManager(
    private val scope: CoroutineScope,
    private val connectRelay: suspend (relayUrl: String) -> NostrGroupClient?,
    /**
     * Pool lookup with no connect. CLOSE goes through this so tearing a feed down cannot
     * resurrect a socket that already died.
     */
    private val pooledRelay: suspend (relayUrl: String) -> NostrGroupClient? = connectRelay,
    private val pageSize: Int = PAGE_SIZE,
) {
    companion object {
        const val PAGE_SIZE = 50
        const val MAX_EVENTS_PER_SPELL = 500
        const val AUTH_TIMEOUT_MS = 12_000L
    }

    private val registry = GroupLoadingRegistry(scope, pageSize, subIdPrefix = SPELL_SUB_PREFIX)
    private val mutex = Mutex()

    /** Spells with a live subscription, by [Spell.key]. */
    private val open = mutableMapOf<String, OpenSpell>()

    /** Reverse routing for inbound frames, which carry only the subscription id. */
    private val subToKey = mutableMapOf<String, String>()

    /**
     * Per-key job mirroring a controller's state into [states].
     *
     * Held so [close] can cancel it: the controller is discarded on close and a re-open builds a
     * fresh one, so a surviving collector would keep writing the dead controller's state and
     * strand the feed in a stale loading spinner.
     */
    private val stateJobs = mutableMapOf<String, Job>()

    private val _events = MutableStateFlow<Map<String, List<NostrGroupClient.NostrMessage>>>(emptyMap())
    val events: StateFlow<Map<String, List<NostrGroupClient.NostrMessage>>> = _events.asStateFlow()

    private val _states = MutableStateFlow<Map<String, GroupLoadingState>>(emptyMap())
    val states: StateFlow<Map<String, GroupLoadingState>> = _states.asStateFlow()

    private val buffer =
        EventOrderingBuffer(scope) { key, messages ->
            appendEvents(key, messages)
        }

    private data class OpenSpell(
        val spell: Spell,
        val filters: List<NostrFilter>,
        val relays: List<String>,
    )

    /**
     * Resolve [spell] against the caller's identity and subscribe on every target relay.
     *
     * Idempotent: re-opening an already-loading spell is a no-op, so a screen remount does not
     * restart the feed.
     */
    suspend fun open(
        spell: Spell,
        ctx: SpellContext,
        nip65ReadRelays: List<String>,
    ): Result<Unit> {
        val filters =
            when (val r = spell.resolveChunked(ctx)) {
                is Result.Success -> r.data
                is Result.Error -> return Result.Error(r.error)
            }
        val relays = spell.targetRelays(nip65ReadRelays)
        if (relays.isEmpty()) {
            return Result.Error(AppError.Spell.Malformed("no relay to query for '${spell.name}'"))
        }

        val key = spell.key
        val controller = registry.getController(key)
        val subId = controller.startInitialLoad(armTimeout = false) ?: return Result.Success(Unit)
        registry.registerSubscription(subId, controller)
        observeState(key, controller)

        mutex.withLock {
            open[key] = OpenSpell(spell, filters, relays)
            subToKey[subId] = key
        }

        val sent = fanOut(subId, relays, filters, until = null)
        if (sent == 0) {
            mutex.withLock { subToKey.remove(subId) }
            registry.unregisterSubscription(subId)
            controller.handleSendFailure(subId)
            return Result.Error(AppError.Network.Disconnected(relays.first()))
        }
        controller.armInitialTimeout(subId)
        return Result.Success(Unit)
    }

    /** Fetch the page before the current cursor. Returns false when the spell cannot paginate. */
    suspend fun loadMore(key: String): Boolean {
        val entry = mutex.withLock { open[key] } ?: return false
        val controller = registry.getController(key)
        val (subId, cursor) = controller.startPagination() ?: return false
        registry.registerSubscription(subId, controller)
        mutex.withLock { subToKey[subId] = key }

        val sent = fanOut(subId, entry.relays, entry.filters, until = cursor.untilTimestamp)
        if (sent == 0) {
            mutex.withLock { subToKey.remove(subId) }
            registry.unregisterSubscription(subId)
            controller.handleSendFailure(subId)
            return false
        }
        return true
    }

    /** CLOSE the spell's subscriptions on every relay it reached and drop its cached events. */
    suspend fun close(key: String) {
        val entry = mutex.withLock { open.remove(key) } ?: return
        val subIds = mutex.withLock {
            val owned = subToKey.filterValues { it == key }.keys.toList()
            owned.forEach { subToKey.remove(it) }
            owned
        }
        subIds.forEach { registry.unregisterSubscription(it) }
        registry.remove(key)
        mutex.withLock { stateJobs.remove(key) }?.cancel()
        _events.update { it - key }
        _states.update { it - key }

        for (relayUrl in entry.relays) {
            val client = runCatchingCancellable { pooledRelay(relayUrl) } ?: continue
            subIds.forEach { subId ->
                runCatchingCancellable { client.closeSubscription(subId) }
            }
        }
    }

    /** True when [subId] belongs to a spell, so NostrRepository can route the frame here. */
    fun owns(subId: String): Boolean = subId.startsWith("${SPELL_SUB_PREFIX}_")

    /** Route one inbound EVENT. Unknown subscription ids are dropped. */
    fun handleEvent(
        subId: String,
        message: NostrGroupClient.NostrMessage,
    ) {
        scope.launch {
            val key = mutex.withLock { subToKey[subId] } ?: return@launch
            registry.trackMessage(subId, message.createdAt, message.id)
            buffer.enqueue(key, message)
        }
    }

    /**
     * Route one inbound EOSE, settling the page and advancing the cursor.
     *
     * The subscription stays mapped afterwards. EOSE ends the stored page, not the feed: the REQ
     * is still open on the relay (spells are deliberately absent from the one-shot auto-CLOSE
     * list), so dropping the mapping here would silently discard every live event that follows.
     * Only [close] unmaps.
     */
    suspend fun handleEose(
        subId: String,
        relayUrl: String,
    ) {
        mutex.withLock { subToKey[subId] } ?: return
        registry.handleEose(subId, relayUrl)
        // The page is settled; anything still buffered belongs to it.
        buffer.flushAll()
    }

    /** Drop every spell's state. Called on logout and account switch. */
    suspend fun clear() {
        val keys = mutex.withLock { open.keys.toList() }
        keys.forEach { close(it) }
        buffer.flushAll()
        registry.clear()
        mutex.withLock {
            open.clear()
            subToKey.clear()
            stateJobs.values.forEach { it.cancel() }
            stateJobs.clear()
        }
        _events.value = emptyMap()
        _states.value = emptyMap()
    }

    private suspend fun fanOut(
        subId: String,
        relays: List<String>,
        filters: List<NostrFilter>,
        until: Long?,
    ): Int {
        var sent = 0
        for (relayUrl in relays) {
            val client = runCatchingCancellable { connectRelay(relayUrl) } ?: continue
            // A restricted relay answers a pre-AUTH REQ with zero events, which the state
            // machine would settle as an empty feed. Wait for the challenge to resolve first.
            if (client.requiresAuth() && !client.hasAuthSucceeded()) {
                runCatchingCancellable { client.awaitAuthOrTimeout(AUTH_TIMEOUT_MS) }
            }
            val ok = runCatchingCancellable { client.requestFilters(subId, filters, until) } != null
            if (ok) sent++
        }
        return sent
    }

    private fun appendEvents(
        key: String,
        messages: List<NostrGroupClient.NostrMessage>,
    ) {
        if (messages.isEmpty()) return
        _events.update { current ->
            val existing = current[key].orEmpty()
            val merged = mergeSpellEvents(existing, messages, MAX_EVENTS_PER_SPELL)
            if (merged === existing) current else current + (key to merged)
        }
    }

    private suspend fun observeState(
        key: String,
        controller: GroupLoadingController,
    ) {
        mutex.withLock {
            if (stateJobs.containsKey(key)) return
            stateJobs[key] = scope.launch {
                controller.state.collect { state ->
                    _states.update { it + (key to state) }
                }
            }
        }
    }
}

/**
 * Stable identity for a spell within an account: the group it is bound to plus its own id.
 * The same spell id under two groups is two independent feeds.
 */
val Spell.key: String
    get() = group?.let { "${it.relayUrl}|${it.groupId}|$id" } ?: id

/**
 * Merge a delivered batch into a feed: newest first, deduplicated by event id, capped at [cap].
 *
 * Returns [existing] unchanged when the batch adds nothing, so an idle relay redelivering the
 * same page does not churn the StateFlow. Fanning one subscription across several relays makes
 * duplicates the normal case, not the exception.
 */
internal fun mergeSpellEvents(
    existing: List<NostrGroupClient.NostrMessage>,
    incoming: List<NostrGroupClient.NostrMessage>,
    cap: Int,
): List<NostrGroupClient.NostrMessage> {
    val seen = existing.mapTo(mutableSetOf()) { it.id }
    val fresh = incoming.filter { seen.add(it.id) }
    if (fresh.isEmpty()) return existing
    return (existing + fresh).sortedByDescending { it.createdAt }.take(cap)
}

private inline fun <T> runCatchingCancellable(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    null
}
