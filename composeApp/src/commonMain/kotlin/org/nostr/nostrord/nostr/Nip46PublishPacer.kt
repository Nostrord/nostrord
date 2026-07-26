package org.nostr.nostrord.nostr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.nostr.nostrord.utils.epochMillis

/**
 * Flow control for kind:24133 publishes to the bunker relay.
 *
 * The one traffic source that floods the relay is the DM gift-wrap decrypt
 * backlog (two nip44_decrypt per wrap, per device). That background lane is
 * paced ([awaitTurn]), bounded by an adaptive in-flight window
 * ([withRequestSlot]) and backed off on explicit rate-limit rejections.
 * Interactive requests (login handshake, NIP-42 AUTH, user-action
 * signs/encrypts - a handful per session) publish immediately and honor only
 * an explicit relay cooldown: queuing them behind the backlog made bunker
 * login visibly slow.
 *
 * The relay also rate-limits the SIGNER's response publishes, which is
 * invisible to OK-based backoff here - it shows up only as responses not
 * coming back. The adaptive window (TCP-style AIMD: grow by one per answered
 * request, halve on a lost one) converges each device onto the response rate
 * the relay actually delivers, so N devices sharing one signer shrink
 * automatically instead of stacking requests into its queue - which is also
 * what keeps the signer's own queue short enough that another device's login
 * gets answered promptly, and stops the signer retrying stale responses that
 * the relay then rejects as "ephemeral event expired".
 */
class Nip46PublishPacer(
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val now: () -> Long = { epochMillis() },
) {
    private val gate = Mutex()

    // Raced by the note* callbacks; the races are benign (worst case one extra
    // paced slot or cooldown step).
    @kotlin.concurrent.Volatile
    private var nextSlotAtMs = 0L

    @kotlin.concurrent.Volatile
    private var cooldownMs = 0L

    @kotlin.concurrent.Volatile
    private var cooldownUntilMs = 0L

    // Adaptive window state. [inFlight] and the waiter queue are strictly under
    // [windowLock]; [windowSize] is volatile so shrink paths can write it from
    // non-suspend contexts (growth takes the lock because it must wake waiters).
    private val windowLock = Mutex()
    private var inFlight = 0
    private val slotWaiters = ArrayDeque<CompletableDeferred<Unit>>()

    @kotlin.concurrent.Volatile
    private var windowSize = INITIAL_WINDOW

    internal val windowSizeNow: Int get() = windowSize

    /**
     * Wait for this publish's slot. Background requests pay the pacing interval
     * plus any active rate-limit cooldown; interactive ones only the cooldown.
     */
    suspend fun awaitTurn(background: Boolean = true) {
        if (!background) {
            val wait = cooldownUntilMs - now()
            if (wait > 0) delay(wait)
            return
        }
        gate.withLock {
            val wait = nextSlotAtMs - now()
            if (wait > 0) delay(wait)
            nextSlotAtMs = maxOf(now(), nextSlotAtMs) + minIntervalMs
        }
    }

    /**
     * Runs [block] (the whole publish + response await) inside the adaptive
     * in-flight window when [background]; interactive requests bypass it for
     * the same reason they skip pacing - they must not wait behind a backlog
     * slot held across a full signer round trip.
     */
    suspend fun <T> withRequestSlot(background: Boolean = true, block: suspend () -> T): T {
        if (!background) return block()
        acquireSlot()
        try {
            return block()
        } finally {
            withContext(NonCancellable) { releaseSlot() }
        }
    }

    /** A background request's response arrived: the signer keeps up, widen the window. */
    suspend fun noteResponseArrived() {
        windowLock.withLock {
            if (windowSize < MAX_WINDOW) {
                windowSize++
                wakeSlotWaitersLocked()
            }
        }
    }

    /** A background request died unanswered (timeout/cancel): halve the window. */
    suspend fun noteResponseLost() {
        withContext(NonCancellable) {
            windowLock.withLock { windowSize = maxOf(MIN_WINDOW, windowSize / 2) }
        }
    }

    /** A relay accepted a publish: the rate limit (if any) has lifted. */
    fun noteAccepted() {
        cooldownMs = 0L
        cooldownUntilMs = 0L
    }

    /** Every relay rejected the publish as rate-limited: push all upcoming slots out, escalating. */
    fun noteRateLimited() {
        cooldownMs = if (cooldownMs == 0L) INITIAL_COOLDOWN_MS else minOf(cooldownMs * 4, MAX_COOLDOWN_MS)
        cooldownUntilMs = maxOf(cooldownUntilMs, now() + cooldownMs)
        nextSlotAtMs = maxOf(nextSlotAtMs, cooldownUntilMs)
        windowSize = maxOf(MIN_WINDOW, windowSize / 2)
    }

    private suspend fun acquireSlot() {
        val waiter = windowLock.withLock {
            if (inFlight < windowSize) {
                inFlight++
                null
            } else {
                CompletableDeferred<Unit>().also { slotWaiters.addLast(it) }
            }
        } ?: return
        try {
            waiter.await()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                windowLock.withLock {
                    // Completed concurrently with the cancel: the slot handed to this
                    // waiter would leak, so pass it on.
                    if (!slotWaiters.remove(waiter) && waiter.isCompleted) releaseSlotLocked()
                }
            }
            throw e
        }
    }

    private suspend fun releaseSlot() {
        windowLock.withLock { releaseSlotLocked() }
    }

    private fun releaseSlotLocked() {
        inFlight--
        wakeSlotWaitersLocked()
    }

    // Waking transfers the slot: inFlight is incremented on the waiter's behalf.
    private fun wakeSlotWaitersLocked() {
        while (slotWaiters.isNotEmpty() && inFlight < windowSize) {
            slotWaiters.removeFirst().complete(Unit)
            inFlight++
        }
    }

    companion object {
        const val MIN_INTERVAL_MS = 400L
        const val INITIAL_COOLDOWN_MS = 2_000L
        const val MAX_COOLDOWN_MS = 30_000L

        /** Publish attempts per request before the failure propagates to the caller. */
        const val MAX_PUBLISH_ATTEMPTS = 3

        /**
         * Adaptive window bounds. Start modest so a fresh boot leaves the signer's
         * queue short for login-critical signs; grow toward [MAX_WINDOW] while the
         * signer answers (it replies in bursts - a near-serial window collapses
         * backlog throughput into the 90s decrypt timeout); never below
         * [MIN_WINDOW] so probing continues and recovery is detected.
         */
        const val MIN_WINDOW = 2
        const val INITIAL_WINDOW = 6
        const val MAX_WINDOW = 24

        /** OK-false reasons that mean "slow down" (NIP-01 rate-limited: prefix + common free text). */
        fun isRateLimitReason(reason: String): Boolean {
            val r = reason.lowercase()
            return r.contains("rate-limit") ||
                r.contains("rate limit") ||
                r.contains("slow down") ||
                r.contains("noting too much")
        }
    }
}
