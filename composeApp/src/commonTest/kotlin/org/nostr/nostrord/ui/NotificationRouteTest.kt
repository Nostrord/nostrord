package org.nostr.nostrord.ui

import org.nostr.nostrord.notifications.NotificationEntry
import org.nostr.nostrord.notifications.NotificationHistoryStore
import org.nostr.nostrord.notifications.NotificationType
import org.nostr.nostrord.ui.navigation.GroupView
import org.nostr.nostrord.ui.navigation.notificationRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationRouteTest {
    @Test
    fun `a thread notification opens the threads pane at its root`() {
        val route = notificationRoute(
            relayUrl = "wss://relay.example",
            groupId = "g1",
            messageId = "reply1",
            threadRootId = "root1",
        )
        assertEquals(GroupView.Threads, route.view)
        assertEquals("root1", route.threadRootId)
        // The reply stays the scroll/flash target inside the thread.
        assertEquals("reply1", route.messageId)
    }

    @Test
    fun `a chat notification opens the chat at its message`() {
        val route = notificationRoute(
            relayUrl = "wss://relay.example",
            groupId = "g1",
            messageId = "msg1",
            threadRootId = null,
        )
        assertEquals(GroupView.Chat, route.view)
        assertNull(route.threadRootId)
        assertEquals("msg1", route.messageId)
    }
}

/**
 * Reading a group's chat clears its chat notifications, never its thread ones: a kind:1111 is
 * not in the chat, so opening the chat is no evidence the user saw it.
 */
class NotificationReadScopeTest {
    private fun entry(id: String, groupId: String, threadRootId: String?) = NotificationEntry(
        id = id,
        type = NotificationType.REPLY,
        groupId = groupId,
        relayUrl = "wss://relay.example",
        actorPubkey = "other",
        createdAt = 1,
        preview = "hi",
        threadRootId = threadRootId,
    )

    @Test
    fun `reading the chat leaves thread entries unread`() {
        val store = NotificationHistoryStore()
        store.add(entry("chat1", "g1", threadRootId = null))
        store.add(entry("thread1", "g1", threadRootId = "root1"))

        store.markReadForGroup("g1")

        assertEquals(true, store.entries.value.first { it.id == "chat1" }.read)
        assertEquals(false, store.entries.value.first { it.id == "thread1" }.read)
    }

    @Test
    fun `opening the thread clears only that thread's entries`() {
        val store = NotificationHistoryStore()
        store.add(entry("thread1", "g1", threadRootId = "root1"))
        store.add(entry("thread2", "g1", threadRootId = "root2"))

        store.markReadForThread("root1")

        assertEquals(true, store.entries.value.first { it.id == "thread1" }.read)
        assertEquals(false, store.entries.value.first { it.id == "thread2" }.read)
    }
}
