package org.nostr.nostrord.ui.screens.avspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvReconnectTest {
    @Test
    fun `the first attempts are quick, then the wait doubles`() {
        assertEquals(1_000L, AvReconnect.delayMs(0))
        assertEquals(2_000L, AvReconnect.delayMs(1))
        assertEquals(4_000L, AvReconnect.delayMs(2))
        assertEquals(8_000L, AvReconnect.delayMs(3))
    }

    @Test
    fun `the wait is capped so a dead room never backs off for minutes`() {
        assertEquals(15_000L, AvReconnect.delayMs(4))
        assertEquals(15_000L, AvReconnect.delayMs(50))
    }

    @Test
    fun `giving up takes under a minute, so the banner is not a surprise later`() {
        val total = (0 until AvReconnect.MAX_ATTEMPTS).sumOf { AvReconnect.delayMs(it) }
        assertTrue(total in 1_000L..60_000L, "total backoff was $total ms")
    }
}
