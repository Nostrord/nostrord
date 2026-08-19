package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.nostr.nostrord.utils.epochMillis

/** What a relay round said about a wrap, as far as the queue is concerned. */
sealed interface DmPublishOutcome {
    /** [relays] are the ones that OK'd it, recorded as the message's "sent to" list. */
    data class Accepted(
        val relays: List<String>,
    ) : DmPublishOutcome

    /** Nobody accepted, but the refusals are the kind that pass: offline, timeout, auth, rate limit. */
    data object Retry : DmPublishOutcome

    /** Every target relay refused for a reason that will not change however often we ask. */
    data class Rejected(
        val reason: String,
    ) : DmPublishOutcome
}

/**
 * Persisted send queue for NIP-17 gift wraps.
 *
 * A wrap stays queued until a relay accepts it: attempts are never exhausted and entries never
 * expire, so a message written while the DM relays are unreachable lands whenever connectivity
 * returns, including after the app is closed and reopened (the caller reloads the queue from disk
 * and calls [resume]). Retries use exponential backoff capped at [MAX_BACKOFF_MS]; a reconnect
 * short-circuits the wait via [onConnectionRestored].
 *
 * The one exception to "forever" is a [DmPublishOutcome.Rejected] recipient wrap: every relay
 * refused it for a reason that will not change, so it is parked (kept, but no longer swept) and
 * reported through [onRejected] for the UI to offer Retry / Dismiss. A rejected self-copy is
 * dropped quietly, since it says nothing about whether the peer got the message.
 */
class DmSendQueue(
    private val scope: CoroutineScope,
    /** Publishes a wrap to its relay set. */
    private val publish: suspend (relays: List<String>, wrapJson: String, wrapId: String) -> DmPublishOutcome,
    /** True when the rumor already counts as delivered, e.g. its self-copy echoed back. */
    private val isDelivered: (rumorId: String) -> Boolean,
    /** Fires when a wrap is accepted, with the relays that took it, so the on-screen message
     *  resolves to Delivered and can name where it landed. */
    private val onDelivered: (rumorId: String, relays: List<String>) -> Unit,
    /** Fires when the recipient's wrap is refused for good, so the bubble can offer Retry / Dismiss. */
    private val onRejected: (rumorId: String, reason: String) -> Unit,
    /** Fires for every entry restored from disk, so its bubble shows Sending again. */
    private val onQueued: (rumorId: String) -> Unit,
    private val persist: suspend (pubkey: String, entries: List<PendingDmWrap>) -> Unit,
    private val now: () -> Long = { epochMillis() },
) {
    companion object {
        /** Retries never stop, so the queue is bounded instead: the oldest entries drop once it
         *  is full, keeping the persisted blob from growing without limit. One send costs two. */
        const val MAX_QUEUE_SIZE = 200
        const val BASE_BACKOFF_MS = 4_000L
        const val MAX_BACKOFF_MS = 300_000L
        const val MIN_SWEEP_WAIT_MS = 1_000L
    }

    private val mutex = Mutex()
    private val entries = MutableStateFlow<List<PendingDmWrap>>(emptyList())
    private var owner: String? = null
    private var sweepJob: Job? = null

    /** Number of wraps still awaiting a relay OK. */
    val size: Int get() = entries.value.size

    /**
     * Queue freshly signed wraps and attempt delivery immediately.
     *
     * Suspends until the queue is on disk. The caller shows the message only after this returns, so
     * a process death can leave a queued wrap with no bubble (the self-copy paints it back) but
     * never a bubble with nothing retrying it, which is the failure this queue exists to prevent.
     */
    suspend fun enqueue(
        pubkey: String,
        wraps: List<PendingDmWrap>,
    ) {
        if (wraps.isEmpty()) return
        mutex.withLock {
            owner = pubkey
            entries.value = (entries.value + wraps).takeLast(MAX_QUEUE_SIZE)
        }
        persistNow()
        restartSweep()
    }

    /** Restore the persisted queue on login and resume delivery. */
    suspend fun resume(
        pubkey: String,
        queued: List<PendingDmWrap>,
    ) {
        mutex.withLock {
            owner = pubkey
            entries.value = queued.takeLast(MAX_QUEUE_SIZE)
        }
        if (queued.isEmpty()) return
        queued.forEach { entry ->
            val parked = entry.parkedReason
            if (parked != null && !entry.toSelf) onRejected(entry.rumorId, parked) else onQueued(entry.rumorId)
        }
        restartSweep()
    }

    /** User asked to send a parked message again: un-park it and attempt it now. */
    fun retry(rumorId: String) {
        scope.launch {
            val unparked =
                mutex.withLock {
                    val matches = entries.value.filter { it.rumorId == rumorId && it.parkedReason != null }
                    if (matches.isEmpty()) return@withLock false
                    entries.value =
                        entries.value.map {
                            if (it.rumorId == rumorId) it.copy(parkedReason = null, attempts = 0, lastAttemptAt = 0) else it
                        }
                    true
                }
            if (!unparked) return@launch
            onQueued(rumorId)
            persistNow()
            restartSweep()
        }
    }

    /** User gave up on a message: drop both of its wraps for good. */
    fun dismiss(rumorId: String) {
        scope.launch {
            mutex.withLock { entries.value = entries.value.filterNot { it.rumorId == rumorId } }
            persistNow()
        }
    }

    /** Drop the in-memory queue on account switch; the persisted copy stays for the next [resume].
     *  Resets synchronously (after cancelling the sweep) so a [resume] for the incoming account can
     *  never be undone by a clear still queued behind it. */
    fun clear() {
        sweepJob?.cancel()
        sweepJob = null
        entries.value = emptyList()
        owner = null
    }

    /** A relay came back: make every entry due now instead of waiting out its backoff. */
    fun onConnectionRestored() {
        if (entries.value.isEmpty()) return
        scope.launch {
            mutex.withLock { entries.value = entries.value.map { it.copy(lastAttemptAt = 0) } }
            restartSweep()
        }
    }

    private fun restartSweep() {
        sweepJob?.cancel()
        sweepJob = scope.launch { sweep() }
    }

    private suspend fun sweep() {
        while (true) {
            val active = entries.value.filter { it.parkedReason == null }
            if (active.isEmpty()) return
            for (entry in active) {
                if (now() < nextAttemptAt(entry)) continue
                attempt(entry)
            }
            val remaining = entries.value.filter { it.parkedReason == null }
            if (remaining.isEmpty()) return
            val wait = remaining.minOf { nextAttemptAt(it) } - now()
            delay(wait.coerceIn(MIN_SWEEP_WAIT_MS, MAX_BACKOFF_MS))
        }
    }

    private suspend fun attempt(entry: PendingDmWrap) {
        if (isDelivered(entry.rumorId)) {
            remove(entry.wrapId)
            return
        }
        when (val outcome = publish(entry.relays, entry.wrapJson, entry.wrapId)) {
            is DmPublishOutcome.Accepted -> {
                onDelivered(entry.rumorId, outcome.relays)
                remove(entry.wrapId)
            }
            is DmPublishOutcome.Rejected -> {
                // A refused self-copy says nothing about the peer's copy, so it just goes away.
                if (entry.toSelf) {
                    remove(entry.wrapId)
                    return
                }
                park(entry.wrapId, outcome.reason)
                onRejected(entry.rumorId, outcome.reason)
            }
            is DmPublishOutcome.Retry -> {
                mutex.withLock {
                    entries.value =
                        entries.value.map {
                            if (it.wrapId == entry.wrapId) it.copy(attempts = it.attempts + 1, lastAttemptAt = now()) else it
                        }
                }
                persistNow()
            }
        }
    }

    private suspend fun park(wrapId: String, reason: String) {
        mutex.withLock {
            entries.value = entries.value.map { if (it.wrapId == wrapId) it.copy(parkedReason = reason) else it }
        }
        persistNow()
    }

    private suspend fun remove(wrapId: String) {
        mutex.withLock { entries.value = entries.value.filterNot { it.wrapId == wrapId } }
        persistNow()
    }

    private fun nextAttemptAt(entry: PendingDmWrap): Long = if (entry.attempts == 0) 0 else entry.lastAttemptAt + backoff(entry.attempts)

    // 4s, 8s, 16s ... then every 5 minutes for as long as the relays stay unreachable.
    private fun backoff(attempts: Int): Long {
        val shifted = BASE_BACKOFF_MS shl (attempts - 1).coerceIn(0, 20)
        return shifted.coerceAtMost(MAX_BACKOFF_MS)
    }

    private suspend fun persistNow() {
        val pubkey = owner ?: return
        persist(pubkey, entries.value)
    }
}
