package org.nostr.nostrord.network.managers

import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.auth.NostrSigner
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.nostr.Nip17
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reactions inside NIP-17: a kind:7 rumor in the same gift wrap, `e`-tagging the message it
 * targets. They are not messages, so they must reach the reaction map and nothing else.
 */
class DmReactionTest {
    private fun signer() = NostrSigner.Local(KeyPair.generate())

    private fun reaction(
        sender: String,
        recipient: String,
        targetId: String,
        emoji: String = "👍",
        emojiTag: List<String>? = null,
    ): Event = Nip17.buildRumor(
        senderPubkey = sender,
        recipientPubkey = recipient,
        content = emoji,
        createdAt = 1000L,
        extraTags = listOfNotNull(listOf("e", targetId, ""), emojiTag),
        kind = Nip17.KIND_REACTION,
    )

    @Test
    fun `an incoming reaction lands on the message it targets`() = runTest {
        val dm = DmManager(backgroundScope)
        val alice = signer()
        val bob = signer()
        val wrap = Nip17.wrap(reaction(alice.pubkey, bob.pubkey, "msg-1"), bob.pubkey, alice)

        assertTrue(dm.ingestGiftWrap(wrap, bob.pubkey, bob))

        val info = dm.reactions.value["msg-1"]?.get("👍")
        assertNotNull(info, "the reaction must be filed under its target")
        assertEquals(listOf(alice.pubkey), info.reactors)
    }

    @Test
    fun `a reaction is not a message`() = runTest {
        val dm = DmManager(backgroundScope)
        val alice = signer()
        val bob = signer()
        dm.ingestGiftWrap(Nip17.wrap(reaction(alice.pubkey, bob.pubkey, "msg-1"), bob.pubkey, alice), bob.pubkey, bob)

        assertNull(dm.messagesByPeer.value[alice.pubkey], "a reaction must not open a thread")
        assertEquals(emptyList(), dm.conversations.value, "a reaction must not create a conversation")
        assertEquals(emptyMap(), dm.unreadByPeer.value, "a reaction must not count as unread")
    }

    @Test
    fun `two reactors on the same emoji are counted once each`() = runTest {
        val dm = DmManager(backgroundScope)
        val alice = signer()
        val carol = signer()
        val bob = signer()
        dm.ingestGiftWrap(Nip17.wrap(reaction(alice.pubkey, bob.pubkey, "msg-1"), bob.pubkey, alice), bob.pubkey, bob)
        dm.ingestGiftWrap(Nip17.wrap(reaction(carol.pubkey, bob.pubkey, "msg-1"), bob.pubkey, carol), bob.pubkey, bob)
        // The same wrap redelivered from a second relay must not double-count.
        val repeat = reaction(alice.pubkey, bob.pubkey, "msg-1")
        dm.ingestGiftWrap(Nip17.wrap(repeat, bob.pubkey, alice), bob.pubkey, bob)

        assertEquals(setOf(alice.pubkey, carol.pubkey), dm.reactions.value["msg-1"]?.get("👍")?.reactors?.toSet())
    }

    @Test
    fun `distinct emojis are kept apart`() = runTest {
        val dm = DmManager(backgroundScope)
        val alice = signer()
        val bob = signer()
        dm.ingestGiftWrap(Nip17.wrap(reaction(alice.pubkey, bob.pubkey, "msg-1", "👍"), bob.pubkey, alice), bob.pubkey, bob)
        dm.ingestGiftWrap(Nip17.wrap(reaction(alice.pubkey, bob.pubkey, "msg-1", "🔥"), bob.pubkey, alice), bob.pubkey, bob)

        assertEquals(setOf("👍", "🔥"), dm.reactions.value["msg-1"]?.keys)
    }

    @Test
    fun `a custom emoji keeps its image url`() = runTest {
        val dm = DmManager(backgroundScope)
        val alice = signer()
        val bob = signer()
        val rumor =
            reaction(alice.pubkey, bob.pubkey, "msg-1", ":party:", listOf("emoji", "party", "https://cdn.example/party.png"))
        dm.ingestGiftWrap(Nip17.wrap(rumor, bob.pubkey, alice), bob.pubkey, bob)

        assertEquals("https://cdn.example/party.png", dm.reactions.value["msg-1"]?.get(":party:")?.emojiUrl)
    }

    @Test
    fun `our own reaction shows before its wrap round-trips`() = runTest {
        val dm = DmManager(backgroundScope)
        val me = signer()
        val peer = signer()
        val rumor = reaction(me.pubkey, peer.pubkey, "msg-1")

        dm.addOptimisticReaction(rumor, peer.pubkey, me.pubkey)
        assertEquals(listOf(me.pubkey), dm.reactions.value["msg-1"]?.get("👍")?.reactors)

        // The self-copy echoing back off a relay must not add a second reactor.
        dm.ingestGiftWrap(Nip17.wrap(rumor, me.pubkey, me), me.pubkey, me)
        assertEquals(listOf(me.pubkey), dm.reactions.value["msg-1"]?.get("👍")?.reactors)
    }

    @Test
    fun `reactions restored from storage are filed again`() = runTest {
        val dm = DmManager(backgroundScope)
        val alice = signer()
        val bob = signer()
        val rumor = reaction(alice.pubkey, bob.pubkey, "msg-1")
        dm.ingestGiftWrap(Nip17.wrap(rumor, bob.pubkey, alice), bob.pubkey, bob)
        val stored = dm.reactionRumorsByPeer.value.values.flatten()
        assertEquals(1, stored.size)

        val restored = DmManager(backgroundScope)
        restored.hydrateReactions(stored)
        assertEquals(listOf(alice.pubkey), restored.reactions.value["msg-1"]?.get("👍")?.reactors)
    }

    @Test
    fun `reactions are dropped on account switch`() = runTest {
        val dm = DmManager(backgroundScope)
        val alice = signer()
        val bob = signer()
        dm.ingestGiftWrap(Nip17.wrap(reaction(alice.pubkey, bob.pubkey, "msg-1"), bob.pubkey, alice), bob.pubkey, bob)

        dm.clear()
        assertEquals(emptyMap(), dm.reactions.value)
    }
}
