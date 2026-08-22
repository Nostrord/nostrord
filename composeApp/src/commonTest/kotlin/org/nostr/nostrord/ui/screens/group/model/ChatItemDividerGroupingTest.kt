package org.nostr.nostrord.ui.screens.group.model

import org.nostr.nostrord.network.NostrGroupClient.NostrMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The "New Messages" divider must break message grouping: a continuation row rendered under it
 * carries no avatar and no author name, so it reads as belonging to whoever posted above.
 */
class ChatItemDividerGroupingTest {
    private fun msg(id: String, pubkey: String, createdAt: Long) = NostrMessage(
        id = id,
        pubkey = pubkey,
        content = id,
        createdAt = createdAt,
        kind = 9,
    )

    @Test
    fun firstMessageAfterDividerStartsANewGroup() {
        val items = buildChatItems(
            messages = listOf(
                msg("a", "bob", 1_000),
                // Same author, inside the 5-minute grouping window, but unread.
                msg("b", "bob", 1_060),
            ),
            lastReadTimestamp = 1_030,
            currentUserPubkey = "me",
        )
        val dividerAt = items.indexOfFirst { it is ChatItem.NewMessagesDivider }
        assertTrue(dividerAt >= 0, "divider missing")
        val afterDivider = items[dividerAt + 1] as ChatItem.Message
        assertEquals("b", afterDivider.message.id)
        assertTrue(afterDivider.isFirstInGroup, "message under the divider lost its author header")
    }

    @Test
    fun groupingSurvivesWithoutADivider() {
        val items = buildChatItems(
            messages = listOf(msg("a", "bob", 1_000), msg("b", "bob", 1_060)),
            lastReadTimestamp = null,
            currentUserPubkey = "me",
        )
        val messages = items.filterIsInstance<ChatItem.Message>()
        assertEquals(2, messages.size)
        assertTrue(messages[0].isFirstInGroup)
        assertTrue(!messages[1].isFirstInGroup, "consecutive same-author messages must stay grouped")
    }
}
