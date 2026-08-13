package org.nostr.nostrord.network.managers

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.utils.epochSeconds
import org.nostr.nostrord.utils.groupKey
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The same group id served by two relays is two groups. Their unread state must not bleed:
 * this is what put a badge on chat.wisp.talk's "nostrord" for traffic in groups.0xchat.com's.
 */
class UnreadRelayScopeTest {

    private val wisp = "wss://chat.wisp.talk"
    private val oxchat = "wss://groups.0xchat.com"
    private val gid = "nostrord"

    private fun manager(scope: TestScope) = UnreadManager(
        isJoined = { _, _ -> true },
        isAppFocused = { true },
        scope = scope,
    )

    private fun message(
        id: String,
        relayUrl: String,
        createdAt: Long = epochSeconds() + 60,
    ) = NostrGroupClient.NostrMessage(
        id = id,
        pubkey = "someone-else",
        content = "hi",
        createdAt = createdAt,
        kind = 9,
        relayUrl = relayUrl,
    )

    @Test
    fun `a message on one relay does not raise the badge of the same-id group on another`() = runTest {
        val unread = manager(TestScope(StandardTestDispatcher(testScheduler)))
        unread.initialize("me")

        unread.onMessagesFlushed(gid, listOf(message("m1", oxchat)))

        assertEquals(1, unread.getUnreadCount(oxchat, gid))
        assertEquals(0, unread.getUnreadCount(wisp, gid))
        assertEquals(mapOf(groupKey(oxchat, gid) to 1), unread.unreadByGroupKey.value)
    }

    @Test
    fun `opening one relay's copy leaves the other relay's badge standing`() = runTest {
        val unread = manager(TestScope(StandardTestDispatcher(testScheduler)))
        unread.initialize("me")
        unread.onMessagesFlushed(gid, listOf(message("m1", oxchat), message("m2", wisp)))

        unread.setActiveGroup(wisp, gid)
        unread.markAsRead(wisp, gid)

        assertEquals(0, unread.getUnreadCount(wisp, gid))
        assertEquals(1, unread.getUnreadCount(oxchat, gid))
    }

    @Test
    fun `the open group stays silent while its twin on another relay still counts`() = runTest {
        val unread = manager(TestScope(StandardTestDispatcher(testScheduler)))
        unread.initialize("me")
        unread.setActiveGroup(wisp, gid)

        unread.onMessagesFlushed(gid, listOf(message("m1", wisp), message("m2", oxchat)))

        assertEquals(0, unread.getUnreadCount(wisp, gid))
        assertEquals(1, unread.getUnreadCount(oxchat, gid))
    }

    @Test
    fun `a relay url that differs only in case or trailing slash is the same group`() = runTest {
        val unread = manager(TestScope(StandardTestDispatcher(testScheduler)))
        unread.initialize("me")

        unread.onMessagesFlushed(gid, listOf(message("m1", "wss://Groups.0xchat.com/")))

        assertEquals(1, unread.getUnreadCount(oxchat, gid))
    }
}
