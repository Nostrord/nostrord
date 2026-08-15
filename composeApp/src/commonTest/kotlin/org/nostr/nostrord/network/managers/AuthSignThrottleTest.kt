package org.nostr.nostrord.network.managers

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthSignThrottleTest {
    private val relay = "wss://groups.example"
    private val pubkey = "a".repeat(64)
    private val other = "b".repeat(64)

    @Test
    fun firstAuthForRelayIsImmediate() = runTest {
        val throttle = AuthSignThrottle()
        assertEquals(0L, throttle.delayBeforeSignMs(relay, pubkey, nowMs = 1_000))
    }

    @Test
    fun repeatWithinIntervalWaitsOutTheRemainder() = runTest {
        val throttle = AuthSignThrottle()
        throttle.recordSign(relay, pubkey, nowMs = 1_000)

        assertEquals(15_000L, throttle.delayBeforeSignMs(relay, pubkey, nowMs = 1_000))
        assertEquals(5_000L, throttle.delayBeforeSignMs(relay, pubkey, nowMs = 11_000))
        assertEquals(0L, throttle.delayBeforeSignMs(relay, pubkey, nowMs = 16_000))
    }

    @Test
    fun churningRelayBacksOffFurtherEachTime() = runTest {
        val throttle = AuthSignThrottle()
        var now = 0L
        val waits = mutableListOf<Long>()
        repeat(5) {
            now += throttle.delayBeforeSignMs(relay, pubkey, now).also { waits += it }
            throttle.recordSign(relay, pubkey, now)
        }

        assertEquals(listOf(0L, 15_000L, 30_000L, 60_000L, 120_000L), waits)
    }

    @Test
    fun signaturesOlderThanTheWindowStopCounting() = runTest {
        val throttle = AuthSignThrottle()
        repeat(4) { throttle.recordSign(relay, pubkey, nowMs = it * 1_000L) }

        val afterWindow = AuthSignThrottle.WINDOW_MS + 1_000
        assertEquals(0L, throttle.delayBeforeSignMs(relay, pubkey, afterWindow))
    }

    @Test
    fun pacingIsPerRelayAndPerIdentity() = runTest {
        val throttle = AuthSignThrottle()
        throttle.recordSign(relay, pubkey, nowMs = 1_000)

        assertEquals(0L, throttle.delayBeforeSignMs("wss://other.example", pubkey, nowMs = 1_000))
        // An account switch re-AUTHs every socket under the new pubkey; that burst is not
        // the outgoing account's churn.
        assertEquals(0L, throttle.delayBeforeSignMs(relay, other, nowMs = 1_000))
    }

    @Test
    fun logoutClearsHistory() = runTest {
        val throttle = AuthSignThrottle()
        throttle.recordSign(relay, pubkey, nowMs = 1_000)
        throttle.clear()

        assertEquals(0L, throttle.delayBeforeSignMs(relay, pubkey, nowMs = 1_000))
    }

    @Test
    fun intervalIsCappedForAPersistentlyChurningRelay() {
        val cap = AuthSignThrottle.requiredIntervalMs(4)
        assertEquals(cap, AuthSignThrottle.requiredIntervalMs(50))
        assertTrue(cap > AuthSignThrottle.requiredIntervalMs(3))
    }
}
