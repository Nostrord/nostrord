package org.nostr.nostrord.network

/**
 * What a relay's OK-false reason means for a send, read from the NIP-01 machine-readable prefix.
 * Group sends and DM wraps classify it the same way: the two used to disagree, so the same relay
 * saying "rate-limited" retried a DM silently and put a Retry button under a chat message.
 */
enum class RelayRejection {
    /** "duplicate": the relay already has the event. The send is done, not failed. */
    AlreadyStored,

    /** Clears on its own: AUTH still pending, a rate limit, or the relay's own transient fault. */
    Transient,

    /** Asking again cannot change the answer: "blocked", "invalid", "pow", "restricted". */
    Permanent,
}

private val TRANSIENT_PREFIXES = listOf("auth-required", "rate-limited", "error")

fun classifyRejection(reason: String): RelayRejection {
    val normalized = reason.trim().lowercase()
    return when {
        // A duplicate is the relay confirming it holds the event, which is what a retry was after.
        // Treating it as a refusal marked delivered messages Not delivered whenever an OK was lost.
        normalized.startsWith("duplicate") -> RelayRejection.AlreadyStored
        TRANSIENT_PREFIXES.any { normalized.startsWith(it) } -> RelayRejection.Transient
        else -> RelayRejection.Permanent
    }
}
