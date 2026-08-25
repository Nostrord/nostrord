package org.nostr.nostrord.notifications

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RealtimeCutoffTest {
    @Test
    fun disarmedTreatsNothingAsRealtime() {
        val cutoff = RealtimeCutoff()
        assertFalse(cutoff.isRealtime(0L))
        assertFalse(cutoff.isRealtime(Long.MAX_VALUE))
    }

    @Test
    fun armedSplitsOnTheActivationInstant() {
        val cutoff = RealtimeCutoff()
        cutoff.arm(1_000L)
        assertFalse(cutoff.isRealtime(999L))
        assertTrue(cutoff.isRealtime(1_000L))
        assertTrue(cutoff.isRealtime(1_001L))
    }

    @Test
    fun disarmAfterArmSilencesAgain() {
        val cutoff = RealtimeCutoff()
        cutoff.arm(1_000L)
        cutoff.disarm()
        assertFalse(cutoff.isRealtime(2_000L))
    }

    @Test
    fun rearmMovesTheCutoffForward() {
        val cutoff = RealtimeCutoff()
        cutoff.arm(1_000L)
        cutoff.arm(2_000L)
        assertFalse(cutoff.isRealtime(1_500L))
        assertTrue(cutoff.isRealtime(2_000L))
    }
}
