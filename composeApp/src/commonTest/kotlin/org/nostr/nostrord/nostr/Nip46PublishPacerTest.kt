package org.nostr.nostrord.nostr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class Nip46PublishPacerTest {
    @Test
    fun spacesConsecutivePublishesByTheMinInterval() = runTest {
        val pacer = Nip46PublishPacer(now = { testScheduler.currentTime })
        val slots = mutableListOf<Long>()
        repeat(3) {
            pacer.awaitTurn()
            slots += testScheduler.currentTime
        }
        assertEquals(0L, slots[0])
        assertEquals(Nip46PublishPacer.MIN_INTERVAL_MS, slots[1])
        assertEquals(2 * Nip46PublishPacer.MIN_INTERVAL_MS, slots[2])
    }

    @Test
    fun rateLimitCooldownDelaysTheNextSlotAndEscalates() = runTest {
        val pacer = Nip46PublishPacer(now = { testScheduler.currentTime })
        pacer.awaitTurn()

        pacer.noteRateLimited()
        val beforeFirstRetry = testScheduler.currentTime
        pacer.awaitTurn()
        assertTrue(testScheduler.currentTime - beforeFirstRetry >= Nip46PublishPacer.INITIAL_COOLDOWN_MS)

        pacer.noteRateLimited()
        val beforeSecondRetry = testScheduler.currentTime
        pacer.awaitTurn()
        assertTrue(testScheduler.currentTime - beforeSecondRetry >= 4 * Nip46PublishPacer.INITIAL_COOLDOWN_MS)
    }

    @Test
    fun cooldownEscalationIsCapped() = runTest {
        val pacer = Nip46PublishPacer(now = { testScheduler.currentTime })
        repeat(10) { pacer.noteRateLimited() }
        val before = testScheduler.currentTime
        pacer.awaitTurn()
        val waited = testScheduler.currentTime - before
        assertTrue(waited <= Nip46PublishPacer.MAX_COOLDOWN_MS)
    }

    @Test
    fun acceptedPublishResetsTheCooldown() = runTest {
        val pacer = Nip46PublishPacer(now = { testScheduler.currentTime })
        pacer.noteRateLimited()
        pacer.awaitTurn()
        pacer.noteAccepted()

        // Back to plain pacing: the next slot is one interval away, not a cooldown.
        val before = testScheduler.currentTime
        pacer.awaitTurn()
        assertEquals(Nip46PublishPacer.MIN_INTERVAL_MS, testScheduler.currentTime - before)
    }

    @Test
    fun requestWindowCapsUnansweredRequestsInFlight() = runTest {
        val pacer = Nip46PublishPacer(now = { testScheduler.currentTime })
        val release = CompletableDeferred<Unit>()
        var active = 0
        var maxActive = 0
        var completed = 0
        repeat(20) {
            launch {
                pacer.withRequestSlot {
                    active++
                    maxActive = maxOf(maxActive, active)
                    release.await()
                    active--
                    completed++
                }
            }
        }
        runCurrent()
        // All 20 requests are unanswered: only a window's worth may be in flight.
        assertEquals(Nip46PublishPacer.MAX_IN_FLIGHT_REQUESTS, maxActive)
        release.complete(Unit)
        runCurrent()
        // Answers release the slots and the remaining requests flow through.
        assertEquals(20, completed)
    }

    @Test
    fun recognizesRateLimitReasons() {
        assertTrue(Nip46PublishPacer.isRateLimitReason("rate-limited: you are noting too much"))
        assertTrue(Nip46PublishPacer.isRateLimitReason("Rate limit exceeded"))
        assertTrue(Nip46PublishPacer.isRateLimitReason("slow down there"))
        assertTrue(!Nip46PublishPacer.isRateLimitReason("blocked: unknown member"))
        assertTrue(!Nip46PublishPacer.isRateLimitReason("auth-required: please auth"))
    }
}
