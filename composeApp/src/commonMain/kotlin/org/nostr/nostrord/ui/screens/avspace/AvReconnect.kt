package org.nostr.nostrord.ui.screens.avspace

/**
 * Backoff for rejoining an AV space that dropped.
 *
 * A drop is usually the network moving under the connection (a VPN coming up, Wi-Fi changing),
 * which clears in seconds, so the first retries are quick. The delay then grows to keep a room
 * that is genuinely gone from hammering the relay for a token per second.
 */
internal object AvReconnect {
    const val MAX_ATTEMPTS = 5

    private const val FIRST_DELAY_MS = 1_000L
    private const val MAX_DELAY_MS = 15_000L

    /** Wait before attempt [attempt], counted from zero: 1s, 2s, 4s, 8s, then 15s. */
    fun delayMs(attempt: Int): Long {
        val doubled = FIRST_DELAY_MS shl attempt.coerceIn(0, 4)
        return minOf(doubled, MAX_DELAY_MS)
    }
}
