package org.nostr.nostrord.network.managers

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * Paces kind:22242 signing per relay and identity, for every login method.
 *
 * A NIP-42 challenge is bound to one socket, so a signature can never be reused: every
 * reconnect costs one signature. That is fine once, but a relay that drops sockets in a loop
 * (or re-challenges an already authenticated one) turns into a steady stream of sign requests
 * at whatever signs for the account - a remote NIP-46 bunker sees them all, and one server
 * signing for many accounts feels the total.
 *
 * The first AUTH for a relay is never delayed, so a cold start and a genuine reconnect are as
 * fast as before. Repeats inside [WINDOW_MS] wait out an interval that grows with how often
 * this relay has already been signed for, which turns an unbounded churn loop into a slow
 * trickle while still authenticating eventually.
 *
 * Keyed per identity: switching accounts deliberately re-AUTHs every socket under the new
 * pubkey, and that burst must not be paced by the outgoing account's history.
 */
class AuthSignThrottle {
    private val mutex = Mutex()

    @Volatile private var signedAt: Map<String, List<Long>> = emptyMap()

    /** How long to wait before signing another AUTH for [relayUrl] as [pubkey]. */
    suspend fun delayBeforeSignMs(relayUrl: String, pubkey: String, nowMs: Long): Long = mutex.withLock {
        val recent = recentLocked(relayUrl, pubkey, nowMs)
        val last = recent.lastOrNull() ?: return@withLock 0L
        val required = requiredIntervalMs(recent.size)
        (last + required - nowMs).coerceAtLeast(0L)
    }

    /** Record that an AUTH for [relayUrl] as [pubkey] is being sent to the signer. */
    suspend fun recordSign(relayUrl: String, pubkey: String, nowMs: Long) = mutex.withLock {
        val key = key(relayUrl, pubkey)
        val recent = recentLocked(relayUrl, pubkey, nowMs)
        signedAt = (signedAt + (key to (recent + nowMs).takeLast(MAX_HISTORY))).let { entries ->
            // Prune identities/relays that fell out of the window entirely, so a long session
            // across many relays doesn't grow this map without bound.
            entries.filterValues { times -> times.any { nowMs - it < WINDOW_MS } }
        }
    }

    /** Logout: the next login starts from a clean history. Callable off a coroutine. */
    fun clear() {
        signedAt = emptyMap()
    }

    private fun recentLocked(relayUrl: String, pubkey: String, nowMs: Long): List<Long> = signedAt[key(relayUrl, pubkey)].orEmpty().filter { nowMs - it < WINDOW_MS }

    private fun key(relayUrl: String, pubkey: String) = "$relayUrl|$pubkey"

    companion object {
        // Signatures older than this stop counting: a relay that needed re-AUTH half an hour
        // ago is not churning.
        const val WINDOW_MS = 10 * 60_000L

        private val INTERVALS_MS = longArrayOf(15_000L, 30_000L, 60_000L, 120_000L)

        private const val MAX_HISTORY = 8

        /** Interval owed after [signsInWindow] signatures for the same relay+identity. */
        fun requiredIntervalMs(signsInWindow: Int): Long = when {
            signsInWindow <= 0 -> 0L
            else -> INTERVALS_MS[(signsInWindow - 1).coerceAtMost(INTERVALS_MS.lastIndex)]
        }
    }
}
