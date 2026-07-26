package org.nostr.nostrord.nostr

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.nostr.nostrord.utils.epochMillis

/**
 * Paces kind:24133 publishes to the bunker relay and backs off when it
 * rate-limits ("rate-limited: you are noting too much"). Every NIP-46 request
 * type (sign_event, nip44 encrypt/decrypt, get_public_key) takes a slot via
 * [awaitTurn] before publishing. The slot lock is held only for the wait,
 * never across the response await: the signer answers in bursts, so many
 * requests must stay in-flight for one burst to satisfy at once.
 *
 * Pacing also throttles the signer's own kind:24133 responses (one per
 * request, on the same relay), which is what the relay was rate-limiting on
 * the signer's side ("Error sending bunker response" in Amber).
 */
class Nip46PublishPacer(
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val now: () -> Long = { epochMillis() },
) {
    private val gate = Mutex()
    private val requestWindow = Semaphore(MAX_IN_FLIGHT_REQUESTS)

    // Written under [gate] in awaitTurn and raced by the note* callbacks; both
    // races are benign (worst case one extra paced slot or cooldown step).
    @kotlin.concurrent.Volatile
    private var nextSlotAtMs = 0L

    @kotlin.concurrent.Volatile
    private var cooldownMs = 0L

    @kotlin.concurrent.Volatile
    private var cooldownUntilMs = 0L

    /**
     * Wait for this publish's slot. The background lane (the DM gift-wrap decrypt backlog,
     * the one source that floods the relay) pays the pacing interval plus any active
     * rate-limit cooldown. The interactive lane (login handshake, NIP-42 AUTH, user-action
     * signs and encrypts - a handful per session) publishes immediately and honors only an
     * explicit relay cooldown: queuing it behind the backlog made bunker login visibly slow.
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
     * Caps unanswered background requests in flight (the whole publish + response await runs
     * inside [block]). The relay rate-limits the SIGNER's response publishes too, and that
     * side is invisible to the OK-based backoff here: it shows up only as responses not
     * coming back. A full window pauses new publishes until answers return, draining the
     * signer's queue instead of growing it (a grown queue also makes the signer retry stale
     * responses that the relay then rejects as "ephemeral event expired"). Window > 1 because
     * the signer answers in bursts with quiet gaps; serializing the awaits collapses
     * throughput. Interactive requests bypass the window for the same reason they skip
     * pacing: they must not wait behind a backlog slot held across a signer round trip.
     */
    suspend fun <T> withRequestSlot(background: Boolean = true, block: suspend () -> T): T = if (background) requestWindow.withPermit { block() } else block()

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
    }

    companion object {
        const val MIN_INTERVAL_MS = 400L
        const val INITIAL_COOLDOWN_MS = 2_000L
        const val MAX_COOLDOWN_MS = 30_000L

        /** Publish attempts per request before the failure propagates to the caller. */
        const val MAX_PUBLISH_ATTEMPTS = 3

        /** Unanswered requests allowed in flight per client (see [withRequestSlot]). */
        const val MAX_IN_FLIGHT_REQUESTS = 8

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
