package org.nostr.nostrord.network.managers

/**
 * When an unanswered liveness probe means the socket is dead.
 *
 * The probe sends a REQ no relay can match and waits for any frame back. Silence is evidence, not
 * proof: a relay under its own rate limit drops the REQ, and a busy client can leave the answer
 * unread past the deadline. Tearing the socket down on the first silence turns either into a loop
 * (kill, reconnect, probe, kill) that costs a handshake a minute per relay and proves nothing,
 * because the replacement socket behaves exactly like the one before it.
 */
object RelayProbePolicy {
    /**
     * Unanswered probes in a row before the socket is torn down.
     *
     * Two probes are ~10s apart at worst, so a real zombie is still caught in the same window the
     * reconnect scheduler works in, while a single dropped REQ costs nothing.
     */
    const val MISSES_BEFORE_KILL = 2

    /**
     * Probe kills in a row on one relay, with no probe ever answered in between, before probing it
     * is muted.
     *
     * A kill is a hypothesis: this socket is dead, a new one will work. When the new socket goes
     * just as silent, the hypothesis is wrong - the relay does not answer our probe at all - and
     * killing it again only churns connections. Stop asking and let the relay's own traffic (or a
     * real close) speak instead.
     */
    const val KILLS_BEFORE_MUTE = 2

    /** How long a relay that proved probe-blind is left alone. */
    const val MUTE_MS = 30 * 60 * 1000L

    fun killsSocket(consecutiveMisses: Int): Boolean = consecutiveMisses >= MISSES_BEFORE_KILL

    fun mutesProbe(killStreak: Int): Boolean = killStreak >= KILLS_BEFORE_MUTE
}
