package org.nostr.nostrord.network.managers

import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.utils.epochMillis

/**
 * Runs liveness probes under [RelayProbePolicy], keeping the evidence per RELAY rather than per
 * socket.
 *
 * Per-socket state is what made the probe loop: every counter died with the socket it was about to
 * convict, so each replacement started innocent and was killed on its first silence, forever. The
 * tallies here outlive the socket, so a relay that never answers a probe is recognized as such.
 */
object RelayProbeGuard {
    private val consecutiveMisses = mutableMapOf<String, Int>()
    private val killStreak = mutableMapOf<String, Int>()
    private val mutedUntilMs = mutableMapOf<String, Long>()

    /** Probe [client] and tear it down only once the misses add up. Safe to call concurrently. */
    suspend fun probe(client: NostrGroupClient) {
        val url = client.getRelayUrl()
        val now = epochMillis()
        if ((mutedUntilMs[url] ?: 0L) > now) return
        if (client.probeLiveness()) {
            consecutiveMisses.remove(url)
            killStreak.remove(url)
            return
        }
        val misses = (consecutiveMisses[url] ?: 0) + 1
        if (!RelayProbePolicy.killsSocket(misses)) {
            consecutiveMisses[url] = misses
            return
        }
        consecutiveMisses.remove(url)
        val kills = (killStreak[url] ?: 0) + 1
        killStreak[url] = kills
        // Mute BEFORE the kill: the reconnect it triggers goes silent the same way, and probing
        // the replacement is the loop this exists to stop.
        if (RelayProbePolicy.mutesProbe(kills)) {
            mutedUntilMs[url] = now + RelayProbePolicy.MUTE_MS
            killStreak.remove(url)
            return
        }
        client.markDead()
    }

    /**
     * A publish got no OK and no frame of any kind while it waited. Returns true once that has
     * happened enough times in a row on [relayUrl] to call the socket dead.
     *
     * Same reasoning as the probe: a relay that rate-limits a publish answers late, not never, and
     * one late OK is not a zombie. The count is per relay so it survives the socket it judges.
     */
    fun onPublishTimeout(relayUrl: String): Boolean {
        val misses = (publishMisses[relayUrl] ?: 0) + 1
        if (!RelayProbePolicy.killsSocket(misses)) {
            publishMisses[relayUrl] = misses
            return false
        }
        publishMisses.remove(relayUrl)
        return true
    }

    /** A publish came back: whatever the relay was doing, the socket carries traffic. */
    fun onPublishAnswered(relayUrl: String) {
        publishMisses.remove(relayUrl)
    }

    /** Drop all evidence (logout, account switch): a new session gets a clean read of the relays. */
    fun reset() {
        consecutiveMisses.clear()
        killStreak.clear()
        mutedUntilMs.clear()
        publishMisses.clear()
    }

    private val publishMisses = mutableMapOf<String, Int>()
}
