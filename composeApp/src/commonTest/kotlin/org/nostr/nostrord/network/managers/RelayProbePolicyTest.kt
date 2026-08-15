package org.nostr.nostrord.network.managers

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelayProbePolicyTest {
    @Test
    fun `one unanswered probe is not enough to kill the socket`() {
        assertFalse(RelayProbePolicy.killsSocket(1))
    }

    @Test
    fun `repeated silence convicts the socket`() {
        assertTrue(RelayProbePolicy.killsSocket(RelayProbePolicy.MISSES_BEFORE_KILL))
        assertTrue(RelayProbePolicy.killsSocket(RelayProbePolicy.MISSES_BEFORE_KILL + 5))
    }

    @Test
    fun `a relay is left alone once killing it stopped helping`() {
        // One kill is a hypothesis worth testing, so probing continues.
        assertFalse(RelayProbePolicy.mutesProbe(1))
        // The replacement socket went silent the same way: the probe, not the socket, is wrong.
        assertTrue(RelayProbePolicy.mutesProbe(RelayProbePolicy.KILLS_BEFORE_MUTE))
    }

    @Test
    fun `the mute outlasts the reconnect cycle it exists to break`() {
        // A killed socket reconnects in seconds and goes silent again within the probe window;
        // a mute measured in minutes is what turns that loop back into a quiet connection.
        assertTrue(RelayProbePolicy.MUTE_MS >= 10 * 60 * 1000L)
    }
}
