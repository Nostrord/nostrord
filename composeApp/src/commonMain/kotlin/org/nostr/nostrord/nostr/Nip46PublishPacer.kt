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
 * Every request costs the relay twice: our publish and the signer's response
 * publish, and the relay rate-limits both sides. The signer's side is
 * invisible to OK-based backoff here - it shows up only as responses arriving
 * slow (current Amber retries each response up to 5x re-signed) or not at all.
 *
 * Background lane (the DM gift-wrap decrypt backlog, the one bulk source):
 * paced by an adaptive interval, bounded by an adaptive in-flight window
 * (TCP-style AIMD), cooled down on explicit rate-limit rejections. Interactive
 * lane (login handshake, NIP-42 AUTH, user-action signs/encrypts): never
 * queues behind the backlog, but pays a token bucket - a fresh session AUTHs
 * every relay in the pool at once, and that burst alone (x2 with a second
 * device logging in) can blow the relay's budget with no DM involved. The
 * bucket lets a login's handful through instantly and spaces the rest.
 */
class Nip46PublishPacer(
    private val baseIntervalMs: Long = MIN_INTERVAL_MS,
    private val now: () -> Long = { epochMillis() },
) {
    private val gate = Mutex()
    private val interactiveGate = Mutex()

    // Raced by the note* callbacks; the races are benign (worst case one extra
    // paced slot or cooldown step).
    @kotlin.concurrent.Volatile
    private var currentIntervalMs = baseIntervalMs

    @kotlin.concurrent.Volatile
    private var nextSlotAtMs = 0L

    @kotlin.concurrent.Volatile
    private var cooldownMs = 0L

    @kotlin.concurrent.Volatile
    private var cooldownUntilMs = 0L

    // Interactive token bucket, strictly under [interactiveGate].
    private var interactiveTokens = INTERACTIVE_BURST.toDouble()
    private var interactiveRefillAtMs = 0L

    // Adaptive window state. [inFlight] and the waiter queue are strictly under
    // [windowLock]; [windowSize] is volatile so shrink paths can write it from
    // non-suspend contexts (growth takes the lock because it must wake waiters).
    private val windowLock = Mutex()
    private var inFlight = 0
    private val slotWaiters = ArrayDeque<CompletableDeferred<Unit>>()

    @kotlin.concurrent.Volatile
    private var windowSize = INITIAL_WINDOW

    internal val windowSizeNow: Int get() = windowSize
    internal val intervalNowMs: Long get() = currentIntervalMs

    /**
     * Wait for this publish's slot. Background requests pay the adaptive pacing
     * interval plus any active rate-limit cooldown; interactive ones pay the
     * cooldown and the token bucket (burst of [INTERACTIVE_BURST], then one
     * token per interval).
     */
    suspend fun awaitTurn(background: Boolean = true) {
        val cooldownWait = cooldownUntilMs - now()
        if (cooldownWait > 0) delay(cooldownWait)
        if (!background) {
            interactiveGate.withLock {
                refillInteractiveLocked()
                if (interactiveTokens < 1.0) {
                    delay(((1.0 - interactiveTokens) * currentIntervalMs).toLong())
                    refillInteractiveLocked()
                }
                interactiveTokens = maxOf(0.0, interactiveTokens - 1.0)
            }
            return
        }
        gate.withLock {
            val wait = nextSlotAtMs - now()
            if (wait > 0) delay(wait)
            nextSlotAtMs = maxOf(now(), nextSlotAtMs) + currentIntervalMs
        }
    }

    private fun refillInteractiveLocked() {
        val t = now()
        if (interactiveRefillAtMs != 0L) {
            val elapsed = t - interactiveRefillAtMs
            if (elapsed > 0) {
                interactiveTokens = minOf(
                    INTERACTIVE_BURST.toDouble(),
                    interactiveTokens + elapsed.toDouble() / currentIntervalMs,
                )
            }
        }
        interactiveRefillAtMs = t
    }

    /**
     * Runs [block] (the whole publish + response await) inside the adaptive
     * in-flight window when [background]; interactive requests bypass it for
     * the same reason they skip the backlog queue - they must not wait behind
     * a slot held across a full signer round trip.
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

    /**
     * A background request's response arrived. Fast answer: the signer keeps up,
     * widen the window and relax the interval. Slow answer (over
     * [SLOW_RESPONSE_MS]): it arrived but the signer is drowning - its relay
     * keeps rejecting response publishes, so each response burns seconds of the
     * signer's own retry backoff. Shrink the window and stretch the interval so
     * the combined multi-device rate drops under what the relay delivers and an
     * interactive sign (another device's login ack) is answered promptly.
     */
    suspend fun noteResponseArrived(latencyMs: Long = 0L) {
        windowLock.withLock {
            if (latencyMs > SLOW_RESPONSE_MS) {
                windowSize = maxOf(MIN_WINDOW, windowSize - maxOf(1, windowSize / 4))
                stretchInterval()
            } else {
                relaxInterval()
                if (windowSize < MAX_WINDOW) {
                    windowSize++
                    wakeSlotWaitersLocked()
                }
            }
        }
    }

    /** A background request died unanswered (timeout/cancel): halve the window, stretch the interval. */
    suspend fun noteResponseLost() {
        withContext(NonCancellable) {
            windowLock.withLock {
                windowSize = maxOf(MIN_WINDOW, windowSize / 2)
                stretchInterval()
            }
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
        stretchInterval()
    }

    private fun stretchInterval() {
        currentIntervalMs = minOf(currentIntervalMs * 2, MAX_INTERVAL_MS)
    }

    private fun relaxInterval() {
        currentIntervalMs = maxOf(baseIntervalMs, currentIntervalMs * 3 / 4)
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

        /** Congestion cap for the adaptive pacing interval (stretch doubles up to here). */
        const val MAX_INTERVAL_MS = 3_200L

        const val INITIAL_COOLDOWN_MS = 2_000L
        const val MAX_COOLDOWN_MS = 30_000L

        /**
         * Interactive burst allowance. A login handshake needs 2-3 requests
         * instantly; a fresh session's NIP-42 AUTH storm (one sign per pool
         * relay) gets the rest spaced at the current interval.
         */
        const val INTERACTIVE_BURST = 6

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

        /** Response latency above this means the signer's queue is backed up (see noteResponseArrived). */
        const val SLOW_RESPONSE_MS = 10_000L

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
