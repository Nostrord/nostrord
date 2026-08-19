package org.nostr.nostrord.network.managers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.cache.InMemoryCacheStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PQ_PUBKEY = "00000000000000000000000000000000000000000000000000000000feedface"
private const val PQ_GROUP = "pending-queue-group"

@OptIn(ExperimentalCoroutinesApi::class)
class PendingEventQueueTest {
    @AfterTest
    fun cleanup() {
        SecureStorage.clearPendingEvents(PQ_PUBKEY)
        SecureStorage.clearAllMessagesForAccount(PQ_PUBKEY)
    }

    @Test
    fun `queued events persist and reload across restarts`() = runTest {
        val scope = TestScope(testScheduler)
        val first = PendingEventManager(ConnectionManager(scope), scope)
        first.setCurrentPubkey(PQ_PUBKEY)
        first.queueEvent("""["EVENT",{}]""", "event-1", PQ_GROUP)

        // Fresh manager (app restart): the queue reloads from storage.
        val second = PendingEventManager(ConnectionManager(scope), scope)
        second.setCurrentPubkey(PQ_PUBKEY)
        assertEquals(listOf("event-1"), second.pendingEvents.value.map { it.eventId })

        scope.cancel()
    }

    @Test
    fun `a restored queue surfaces its events as Sending`() = runTest {
        val scope = TestScope(testScheduler)
        // Persist an undelivered event, as a previous app run would have.
        PendingEventManager(ConnectionManager(scope), scope).apply {
            setCurrentPubkey(PQ_PUBKEY)
            queueEvent("""["EVENT",{}]""", "event-2", PQ_GROUP)
        }

        val pending = PendingEventManager(ConnectionManager(scope), scope)
        val manager = GroupManager(
            connectionManager = ConnectionManager(scope),
            scope = scope,
            pendingEventManager = pending,
            cacheStore = InMemoryCacheStore(),
        ).apply { setCurrentPubkey(PQ_PUBKEY) }
        pending.setCurrentPubkey(PQ_PUBKEY)
        runCurrent()

        assertEquals(GroupManager.MessageStatus.Sending, manager.messageStatus.value["event-2"])

        scope.cancel()
    }

    @Test
    fun `a restored queue repaints its message in the chat`() = runTest {
        val scope = TestScope(testScheduler)
        // A previous run left an undelivered kind:9. It never reached the cache (only a relay
        // confirmation caches an own message), so the queue is the only copy of it.
        val event = """{"id":"event-4","pubkey":"$PQ_PUBKEY","content":"still unsent",""" +
            """"created_at":42,"kind":9,"tags":[["h","$PQ_GROUP"]]}"""
        PendingEventManager(ConnectionManager(scope), scope).apply {
            setCurrentPubkey(PQ_PUBKEY)
            queueEvent("""["EVENT",$event]""", "event-4", PQ_GROUP)
        }

        val pending = PendingEventManager(ConnectionManager(scope), scope)
        val manager = GroupManager(
            connectionManager = ConnectionManager(scope),
            scope = scope,
            pendingEventManager = pending,
            cacheStore = InMemoryCacheStore(),
        ).apply { setCurrentPubkey(PQ_PUBKEY) }
        pending.setCurrentPubkey(PQ_PUBKEY)
        runCurrent()

        val message = manager.getMessagesForGroup(PQ_GROUP).singleOrNull()
        assertEquals("still unsent", message?.content, "the queued message must be back on screen")
        assertEquals(GroupManager.MessageStatus.Sending, manager.messageStatus.value["event-4"])

        scope.cancel()
    }

    @Test
    fun `a long-unreachable relay never turns a queued event into a failure`() = runTest {
        val scope = TestScope(testScheduler)
        // A previous run left an event that failed to land many times over (relay offline).
        SecureStorage.savePendingEvents(
            PQ_PUBKEY,
            """[{"id":"pending_1_event-3","eventJson":"[\"EVENT\",{}]","eventId":"event-3",""" +
                """"groupId":"$PQ_GROUP","createdAt":1,"retryCount":99,"lastAttemptAt":1}]""",
        )

        val pending = PendingEventManager(ConnectionManager(scope), scope)
        var failed: String? = null
        pending.onEventPermanentlyFailed = { eventId, _, _, _ -> failed = eventId }
        pending.setCurrentPubkey(PQ_PUBKEY)
        pending.retryPendingEvents()

        assertEquals(listOf("event-3"), pending.pendingEvents.value.map { it.eventId }, "the event must stay queued")
        assertEquals(null, failed, "a transient failure must never resolve to Not delivered")

        scope.cancel()
    }

    @Test
    fun `the relay echo of an own message resolves Sending and clears the queue`() = runTest {
        val scope = TestScope(testScheduler)
        val pending = PendingEventManager(ConnectionManager(scope), scope).apply { setCurrentPubkey(PQ_PUBKEY) }
        val manager = GroupManager(
            connectionManager = ConnectionManager(scope),
            scope = scope,
            pendingEventManager = pending,
            cacheStore = InMemoryCacheStore(),
        ).apply { setCurrentPubkey(PQ_PUBKEY) }

        manager.sendMessage(PQ_GROUP, "hello", PQ_PUBKEY) { it.copy(id = it.calculateId(), sig = "ff".repeat(64)) }
        runCurrent()

        val message = manager.getMessagesForGroup(PQ_GROUP).single()
        assertEquals(GroupManager.MessageStatus.Sending, manager.messageStatus.value[message.id])
        assertEquals(1, pending.pendingEvents.value.size)

        // The relay's live feed echoes the same event id back (its OK was lost).
        val rawMsg = """["EVENT","sub",{"id":"${message.id}","kind":9,"tags":[["h","$PQ_GROUP"]]}]"""
        manager.handleMessage(message, rawMsg, subscriptionId = null, relayUrl = "wss://relay.test")
        advanceTimeBy(1_000) // ordering-buffer debounce
        runCurrent()

        assertEquals(
            GroupManager.MessageStatus.Delivered,
            manager.messageStatus.value[message.id],
            "echo must resolve the Sending status to Delivered",
        )
        assertTrue(pending.pendingEvents.value.isEmpty(), "echo must drop the queued retry")

        scope.cancel()
    }
}
