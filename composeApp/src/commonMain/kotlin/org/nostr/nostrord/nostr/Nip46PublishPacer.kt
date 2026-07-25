package org.nostr.nostrord.nostr

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // Written under [gate] in awaitTurn and raced by the note* callbacks; both
    // races are benign (worst case one extra paced slot or cooldown step).
    @kotlin.concurrent.Volatile
    private var nextSlotAtMs = 0L

    @kotlin.concurrent.Volatile
    private var cooldownMs = 0L

    /** Wait for this publish's slot: the pacing interval plus any active rate-limit cooldown. */
    suspend fun awaitTurn() {
        gate.withLock {
            val wait = nextSlotAtMs - now()
            if (wait > 0) delay(wait)
            nextSlotAtMs = maxOf(now(), nextSlotAtMs) + minIntervalMs
        }
    }

    /** A relay accepted a publish: the rate limit (if any) has lifted. */
    fun noteAccepted() {
        cooldownMs = 0L
    }

    /** Every relay rejected the publish as rate-limited: push all upcoming slots out, escalating. */
    fun noteRateLimited() {
        cooldownMs = if (cooldownMs == 0L) INITIAL_COOLDOWN_MS else minOf(cooldownMs * 4, MAX_COOLDOWN_MS)
        nextSlotAtMs = maxOf(nextSlotAtMs, now() + cooldownMs)
    }

    companion object {
        const val MIN_INTERVAL_MS = 400L
        const val INITIAL_COOLDOWN_MS = 2_000L
        const val MAX_COOLDOWN_MS = 30_000L

        /** Publish attempts per request before the failure propagates to the caller. */
        const val MAX_PUBLISH_ATTEMPTS = 3

        /** OK-false reasons that mean "slow down" (NIP-01 rate-limited: prefix + common free text). */
        fun isRateLimitReason(reason: String): Boolean {
            val r = reason.lowercase()
            return r.contains("rate-limit") || r.contains("rate limit") ||
                r.contains("slow down") || r.contains("noting too much")
        }
    }
}
