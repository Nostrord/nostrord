package org.nostr.nostrord.network.managers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DmSendQueueTest {
    private fun wrap(rumorId: String, wrapId: String, toSelf: Boolean = false) = PendingDmWrap(
        rumorId = rumorId,
        wrapId = wrapId,
        wrapJson = """{"id":"$wrapId"}""",
        relays = listOf("wss://dm.example"),
        createdAt = 0,
        toSelf = toSelf,
    )

    @Test
    fun keepsRetryingLongAfterTheOldSixAttemptCeiling() = runTest {
        var attempts = 0
        val delivered = mutableListOf<String>()
        val queue =
            DmSendQueue(
                scope = backgroundScope,
                publish = { _, _, _, _ ->
                    attempts++
                    if (attempts >= 20) DmPublishOutcome.Accepted(listOf("wss://dm.example")) else DmPublishOutcome.Retry
                },
                onDelivered = { rumorId, _ -> delivered += rumorId },
                onRejected = { _, _ -> },
                onQueued = {},
                persist = { _, _ -> },
                now = { testScheduler.currentTime },
            )

        queue.enqueue("me", listOf(wrap("rumor1", "wrap1")))
        advanceTimeBy(6 * 60 * 60 * 1000L)

        assertEquals(20, attempts)
        assertEquals(listOf("rumor1"), delivered)
        assertEquals(0, queue.size)
    }

    @Test
    fun anOfflineWrapStaysQueuedAndPersistedInsteadOfBeingDropped() = runTest {
        var persisted: List<PendingDmWrap> = emptyList()
        val queue =
            DmSendQueue(
                scope = backgroundScope,
                publish = { _, _, _, _ -> DmPublishOutcome.Retry },
                onDelivered = { _, _ -> },
                onRejected = { _, _ -> },
                onQueued = {},
                persist = { _, entries -> persisted = entries },
                now = { testScheduler.currentTime },
            )

        queue.enqueue("me", listOf(wrap("rumor1", "wrap1")))
        advanceTimeBy(24 * 60 * 60 * 1000L)

        assertEquals(1, queue.size)
        assertEquals(1, persisted.size)
        // Backoff is capped, so a full day of failures leaves the entry due again shortly, not
        // parked forever behind an ever-growing delay.
        assertTrue(persisted.first().attempts > 6, "expected retries past the old ceiling, got ${persisted.first().attempts}")
    }

    @Test
    fun resumeRestoresTheQueueAndReportsEachMessageAsSending() = runTest {
        val sending = mutableListOf<String>()
        var attempts = 0
        val queue =
            DmSendQueue(
                scope = backgroundScope,
                publish = { _, _, _, _ ->
                    attempts++
                    DmPublishOutcome.Accepted(listOf("wss://dm.example"))
                },
                onDelivered = { _, _ -> },
                onRejected = { _, _ -> },
                onQueued = { sending += it },
                persist = { _, _ -> },
                now = { testScheduler.currentTime },
            )

        queue.resume("me", listOf(wrap("rumor1", "wrap1"), wrap("rumor2", "wrap2"), wrap("rumor2", "wrap2-self", toSelf = true)))
        advanceTimeBy(10_000L)

        // A restored self-copy is sent but never reported: it must not put a message whose
        // recipient wrap already landed back on Sending.
        assertEquals(listOf("rumor1", "rumor2"), sending)
        assertEquals(3, attempts)
        assertEquals(0, queue.size)
    }

    @Test
    fun reconnectRetriesImmediatelyInsteadOfWaitingOutTheBackoff() = runTest {
        var online = false
        var attempts = 0
        val queue =
            DmSendQueue(
                scope = backgroundScope,
                publish = { _, _, _, _ ->
                    attempts++
                    if (online) DmPublishOutcome.Accepted(listOf("wss://dm.example")) else DmPublishOutcome.Retry
                },
                onDelivered = { _, _ -> },
                onRejected = { _, _ -> },
                onQueued = {},
                persist = { _, _ -> },
                now = { testScheduler.currentTime },
            )

        queue.enqueue("me", listOf(wrap("rumor1", "wrap1")))
        advanceTimeBy(20 * 60 * 1000L)
        val beforeReconnect = attempts
        assertEquals(1, queue.size)

        online = true
        queue.onConnectionRestored()
        advanceTimeBy(1_000L)

        assertEquals(beforeReconnect + 1, attempts)
        assertEquals(0, queue.size)
    }

    @Test
    fun theSelfCopyStillGoesOutAfterTheRecipientWrapIsAccepted() = runTest {
        val published = mutableListOf<String>()
        val delivered = mutableListOf<String>()
        val queue =
            DmSendQueue(
                scope = backgroundScope,
                publish = { _, _, wrapId, _ ->
                    published += wrapId
                    DmPublishOutcome.Accepted(listOf("wss://relay"))
                },
                onDelivered = { rumorId, _ -> delivered += rumorId },
                onRejected = { _, _ -> },
                onQueued = {},
                persist = { _, _ -> },
                now = { testScheduler.currentTime },
            )

        queue.enqueue("me", listOf(wrap("rumor1", "wrap1"), wrap("rumor1", "wrap-self", toSelf = true)))
        advanceTimeBy(10_000L)

        assertEquals(listOf("wrap1", "wrap-self"), published)
        // Delivered once, on the recipient's wrap; the self-copy landing is not delivery.
        assertEquals(listOf("rumor1"), delivered)
        assertEquals(0, queue.size)
    }

    @Test
    fun aRelayThatTakesTheWrapLateStillReportsThroughOnDelivered() = runTest {
        val delivered = mutableListOf<Pair<String, List<String>>>()
        var lateAccept: ((List<String>) -> Unit)? = null
        val queue =
            DmSendQueue(
                scope = backgroundScope,
                publish = { _, _, wrapId, onLateAccept ->
                    if (wrapId == "wrap1") lateAccept = onLateAccept
                    DmPublishOutcome.Accepted(listOf("wss://fast"))
                },
                onDelivered = { rumorId, relays -> delivered += rumorId to relays },
                onRejected = { _, _ -> },
                onQueued = {},
                persist = { _, _ -> },
                now = { testScheduler.currentTime },
            )

        queue.enqueue("me", listOf(wrap("rumor1", "wrap1"), wrap("rumor1", "wrap-self", toSelf = true)))
        advanceTimeBy(10_000L)
        assertEquals(0, queue.size)
        assertEquals(listOf("rumor1" to listOf("wss://fast")), delivered)

        // The slow relay answers after the entry is gone: still counts for "seen on".
        lateAccept!!(listOf("wss://slow"))
        assertEquals(listOf("rumor1" to listOf("wss://fast"), "rumor1" to listOf("wss://slow")), delivered)
    }

    @Test
    fun anAcceptedSelfCopyDoesNotMarkTheMessageDelivered() = runTest {
        val delivered = mutableListOf<String>()
        val queue =
            DmSendQueue(
                scope = backgroundScope,
                publish = { _, _, wrapId, _ ->
                    if (wrapId == "wrap-self") DmPublishOutcome.Accepted(listOf("wss://relay")) else DmPublishOutcome.Retry
                },
                onDelivered = { rumorId, _ -> delivered += rumorId },
                onRejected = { _, _ -> },
                onQueued = {},
                persist = { _, _ -> },
                now = { testScheduler.currentTime },
            )

        queue.enqueue("me", listOf(wrap("rumor1", "wrap1"), wrap("rumor1", "wrap-self", toSelf = true)))
        advanceTimeBy(10_000L)

        assertEquals(emptyList(), delivered)
        // The recipient wrap is still in flight; only the self-copy left the queue.
        assertEquals(1, queue.size)
    }

    @Test
    fun aRefusedRecipientWrapParksAndStopsBurningAttempts() = runTest {
        var attempts = 0
        val rejected = mutableListOf<Pair<String, String>>()
        val queue =
            DmSendQueue(
                scope = backgroundScope,
                publish = { _, _, _, _ ->
                    attempts++
                    DmPublishOutcome.Rejected("blocked: pubkey not allowed")
                },
                onDelivered = { _, _ -> },
                onRejected = { rumorId, reason -> rejected += rumorId to reason },
                onQueued = {},
                persist = { _, _ -> },
                now = { testScheduler.currentTime },
            )

        queue.enqueue("me", listOf(wrap("rumor1", "wrap1")))
        advanceTimeBy(60 * 60 * 1000L)

        assertEquals(1, attempts, "a parked wrap must not be swept again")
        assertEquals(listOf("rumor1" to "blocked: pubkey not allowed"), rejected)
        // Kept, not dropped: Retry has to have something to send.
        assertEquals(1, queue.size)
    }

    @Test
    fun aRefusedSelfCopyIsDroppedWithoutFailingTheMessage() = runTest {
        val rejected = mutableListOf<String>()
        val queue =
            DmSendQueue(
                scope = backgroundScope,
                publish = { _, _, _, _ -> DmPublishOutcome.Rejected("invalid: too large") },
                onDelivered = { _, _ -> },
                onRejected = { rumorId, _ -> rejected += rumorId },
                onQueued = {},
                persist = { _, _ -> },
                now = { testScheduler.currentTime },
            )

        queue.enqueue("me", listOf(wrap("rumor1", "wrap-self", toSelf = true)))
        advanceTimeBy(10_000L)

        assertTrue(rejected.isEmpty(), "the peer's copy is what decides delivery, not ours")
        assertEquals(0, queue.size)
    }

    @Test
    fun retrySendsAParkedWrapAgain() = runTest {
        var refuse = true
        val queued = mutableListOf<String>()
        val delivered = mutableListOf<String>()
        val queue =
            DmSendQueue(
                scope = backgroundScope,
                publish = { _, _, _, _ ->
                    if (refuse) DmPublishOutcome.Rejected("blocked: pubkey not allowed") else DmPublishOutcome.Accepted(listOf("wss://dm.example"))
                },
                onDelivered = { rumorId, _ -> delivered += rumorId },
                onRejected = { _, _ -> },
                onQueued = { queued += it },
                persist = { _, _ -> },
                now = { testScheduler.currentTime },
            )

        queue.enqueue("me", listOf(wrap("rumor1", "wrap1")))
        advanceTimeBy(10_000L)
        assertEquals(1, queue.size)

        refuse = false
        queue.retry("rumor1")
        advanceTimeBy(10_000L)

        assertEquals(listOf("rumor1"), queued)
        assertEquals(listOf("rumor1"), delivered)
        assertEquals(0, queue.size)
    }

    @Test
    fun dismissDropsBothWrapsOfTheMessage() = runTest {
        val queue =
            DmSendQueue(
                scope = backgroundScope,
                publish = { _, _, _, _ -> DmPublishOutcome.Retry },
                onDelivered = { _, _ -> },
                onRejected = { _, _ -> },
                onQueued = {},
                persist = { _, _ -> },
                now = { testScheduler.currentTime },
            )

        queue.enqueue("me", listOf(wrap("rumor1", "wrap1"), wrap("rumor1", "wrap-self", toSelf = true)))
        advanceTimeBy(10_000L)
        assertEquals(2, queue.size)

        queue.dismiss("rumor1")
        advanceTimeBy(1_000L)

        assertEquals(0, queue.size)
    }

    @Test
    fun resumeReportsAParkedWrapAsRefusedRatherThanSending() = runTest {
        val queued = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        val queue =
            DmSendQueue(
                scope = backgroundScope,
                publish = { _, _, _, _ -> DmPublishOutcome.Retry },
                onDelivered = { _, _ -> },
                onRejected = { rumorId, _ -> rejected += rumorId },
                onQueued = { queued += it },
                persist = { _, _ -> },
                now = { testScheduler.currentTime },
            )

        queue.resume("me", listOf(wrap("rumor1", "wrap1").copy(parkedReason = "blocked: pubkey not allowed")))
        advanceTimeBy(10_000L)

        assertEquals(listOf("rumor1"), rejected)
        assertTrue(queued.isEmpty(), "a parked wrap must not come back as Sending")
    }
}
