package org.nostr.nostrord.network.managers

import org.nostr.nostrord.network.NostrGroupClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The EOSE reconciliation that makes a thread deleted on one device disappear on another: the
 * second device never receives the kind:5/9005, so absence from the relay's answer is the signal.
 */
class StaleThreadEventsTest {
    private fun root(id: String, createdAt: Long) = NostrGroupClient.NostrMessage(
        id = id,
        pubkey = "author",
        content = "c",
        createdAt = createdAt,
        kind = 11,
        tags = emptyList(),
        relayUrl = "wss://relay.example",
    )

    @Test
    fun `a stored root the answer omits is gone`() {
        val stored = listOf(root("a", 100), root("b", 200), root("c", 300))
        assertEquals(setOf("b"), staleThreadEventIds(stored, seen = setOf("a", "c")))
    }

    @Test
    fun `nothing is dropped when the answer matches what we hold`() {
        val stored = listOf(root("a", 100), root("b", 200))
        assertEquals(emptySet(), staleThreadEventIds(stored, seen = setOf("a", "b")))
    }

    @Test
    fun `history older than the capped answer survives`() {
        // The REQ is limit-capped: the relay served only the two newest, so the older ones were
        // never asked for. Dropping them would wipe history on every EOSE.
        val stored = listOf(root("old1", 10), root("old2", 20), root("new1", 300), root("new2", 400))
        assertEquals(emptySet(), staleThreadEventIds(stored, seen = setOf("new1", "new2")))
    }

    @Test
    fun `a deletion inside the answered window is caught, older history is not`() {
        val stored = listOf(root("old", 10), root("deleted", 350), root("new1", 300), root("new2", 400))
        assertEquals(setOf("deleted"), staleThreadEventIds(stored, seen = setOf("new1", "new2")))
    }

    @Test
    fun `an empty answer clears everything we hold for that relay`() {
        // Every thread was deleted elsewhere. Callers only reconcile a settled, authenticated
        // sub, so an empty EOSE is a real "there is nothing here".
        val stored = listOf(root("a", 100), root("b", 200))
        assertEquals(setOf("a", "b"), staleThreadEventIds(stored, seen = emptySet()))
    }

    @Test
    fun `an empty store is a no-op`() {
        assertEquals(emptySet(), staleThreadEventIds(emptyList(), seen = setOf("a")))
    }
}

/** Who a kind:1111 reply notifies, decided from the reply's own NIP-22 tags. */
class ThreadReplyTargetTest {
    private val me = "me"
    private val other = "other"
    private val hint = "wss://relay.example"

    @Test
    fun `a top-level reply to my root notifies me through the uppercase P tag`() {
        // ThreadTags.reply omits the lowercase p when the parent IS the root (relays cap
        // indexable tags), so P is the only author marker on the most common reply there is.
        val tags = listOf(
            listOf("h", "g1"),
            listOf("E", "root1", hint, me),
            listOf("K", "11"),
            listOf("P", me),
        )
        assertTrue(threadReplyTargetsMe(tags, me) { null })
    }

    @Test
    fun `the E tag author element alone is enough when the root is not in memory`() {
        val tags = listOf(listOf("E", "root1", hint, me))
        // findMessageAuthor returns null: the device never loaded the thread.
        assertTrue(threadReplyTargetsMe(tags, me) { null })
    }

    @Test
    fun `a nested reply to my reply notifies me through the lowercase p`() {
        val tags = listOf(
            listOf("E", "root1", hint, other),
            listOf("P", other),
            listOf("e", "reply1", hint, me),
            listOf("p", me),
        )
        assertTrue(threadReplyTargetsMe(tags, me) { null })
    }

    @Test
    fun `a reply between two other people does not notify me`() {
        val tags = listOf(
            listOf("E", "root1", hint, other),
            listOf("P", other),
        )
        assertFalse(threadReplyTargetsMe(tags, me) { null })
    }

    @Test
    fun `a lean tag set falls back to the local author lookup`() {
        // No author element, no P: only the local cache can attribute it.
        val tags = listOf(listOf("E", "root1"))
        assertTrue(threadReplyTargetsMe(tags, me) { if (it == "root1") me else null })
        assertFalse(threadReplyTargetsMe(tags, me) { null })
    }
}

/** A thread reaction survives a cold start through its cache row. */
class ThreadReactionCacheTest {
    private val reaction = NostrGroupClient.NostrReaction(
        id = "react1",
        pubkey = "reactor",
        emoji = ":pepe:",
        emojiUrl = "https://x/pepe.png",
        targetEventId = "reply1",
        createdAt = 42,
        targetAuthorPubkey = "me",
        groupId = "g1",
        threadRootId = "root1",
    )

    @Test
    fun `the cached row round-trips back into the same reaction`() {
        val tags = reactionCacheTags(reaction, groupId = "g1", rootId = "root1")
        val restored = reactionFromCacheRow(reaction.id, reaction.pubkey, reaction.createdAt, reaction.emoji, tags)
        assertEquals(reaction, restored)
    }

    @Test
    fun `a plain reaction keeps its root so the chip and the notification agree`() {
        val plain = reaction.copy(emojiUrl = null, targetAuthorPubkey = null)
        val tags = reactionCacheTags(plain, groupId = "g1", rootId = "root1")
        val restored = reactionFromCacheRow(plain.id, plain.pubkey, plain.createdAt, plain.emoji, tags)
        assertEquals("root1", restored?.threadRootId)
        assertEquals("reply1", restored?.targetEventId)
    }

    @Test
    fun `a chat reaction round-trips without a thread root`() {
        val chat = reaction.copy(threadRootId = null)
        val tags = reactionCacheTags(chat, groupId = "g1", rootId = null)
        val restored = reactionFromCacheRow(chat.id, chat.pubkey, chat.createdAt, chat.emoji, tags)
        assertEquals(chat, restored)
    }

    @Test
    fun `a row with no target is unusable`() {
        assertEquals(null, reactionFromCacheRow("r", "p", 1, "+", listOf(listOf("h", "g1"))))
    }
}
