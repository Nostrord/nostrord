package org.nostr.nostrord.network.managers

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.auth.NostrSigner
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.nostr.Nip17
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DmManagerTest {
    private fun signer() = NostrSigner.Local(KeyPair.generate())

    private fun announcement(author: String, key: String?, createdAt: Long) = org.nostr.nostrord.nostr.Nip4e.buildAnnouncement(author, key, createdAt)

    @Test
    fun `an announced encryption key is used for that peer`() = runTest {
        val dm = DmManager(backgroundScope)
        val peer = signer().pubkey
        val key = "b".repeat(64)
        assertEquals(null, dm.encryptionKeyFor(peer))

        dm.ingestEncryptionKey(announcement(peer, key, createdAt = 10L))
        assertEquals(key, dm.encryptionKeyFor(peer))
    }

    @Test
    fun `an older announcement cannot regress a newer key`() = runTest {
        val dm = DmManager(backgroundScope)
        val peer = signer().pubkey
        val newer = "b".repeat(64)
        val older = "c".repeat(64)

        dm.ingestEncryptionKey(announcement(peer, newer, createdAt = 20L))
        // Same replaceable arriving from a slower relay, stale copy.
        dm.ingestEncryptionKey(announcement(peer, older, createdAt = 10L))
        assertEquals(newer, dm.encryptionKeyFor(peer))
    }

    @Test
    fun `a withdrawal puts the peer back on identity encryption`() = runTest {
        val dm = DmManager(backgroundScope)
        val peer = signer().pubkey
        dm.ingestEncryptionKey(announcement(peer, "b".repeat(64), createdAt = 10L))
        dm.ingestEncryptionKey(announcement(peer, null, createdAt = 20L))
        assertEquals(null, dm.encryptionKeyFor(peer))
    }

    @Test
    fun `encryption keys are dropped on account switch`() = runTest {
        val dm = DmManager(backgroundScope)
        val peer = signer().pubkey
        dm.ingestEncryptionKey(announcement(peer, "b".repeat(64), createdAt = 10L))
        dm.clear()
        assertEquals(null, dm.encryptionKeyFor(peer))
    }

    @Test
    fun `hydrated encryption keys survive a cold start`() = runTest {
        val dm = DmManager(backgroundScope)
        val peer = signer().pubkey
        val key = "b".repeat(64)
        dm.hydrate(emptyList(), emptyMap(), encKeys = mapOf(peer to DmEncKey(key, 10L)))
        assertEquals(key, dm.encryptionKeyFor(peer))
    }

    @Test
    fun `received message lands under the sender as the conversation peer`() = runTest {
        val dm = DmManager(backgroundScope)
        val alice = signer()
        val bob = signer()
        val rumor = Nip17.buildRumor(alice.pubkey, bob.pubkey, "hey")
        val wrap = Nip17.wrap(rumor, bob.pubkey, alice)

        dm.ingestGiftWrap(wrap, myPubkey = bob.pubkey, signer = bob)

        val msgs = dm.messagesByPeer.value[alice.pubkey]
        assertEquals(1, msgs?.size)
        assertEquals("hey", msgs?.first()?.content)
        assertTrue(msgs?.first()?.mine == false)
    }

    @Test
    fun `self-copy lands under the recipient and is marked mine`() = runTest {
        val dm = DmManager(backgroundScope)
        val alice = signer()
        val bob = signer()
        val rumor = Nip17.buildRumor(alice.pubkey, bob.pubkey, "yo")
        // Self-copy: sealed and wrapped to myself, but the rumor's p-tag is the recipient.
        val selfWrap = Nip17.wrap(rumor, alice.pubkey, alice)

        dm.ingestGiftWrap(selfWrap, myPubkey = alice.pubkey, signer = alice)

        val msgs = dm.messagesByPeer.value[bob.pubkey]
        assertEquals(1, msgs?.size)
        assertEquals("yo", msgs?.first()?.content)
        assertTrue(msgs?.first()?.mine == true)
    }

    @Test
    fun `duplicate gift wraps are deduped by rumor id`() = runTest {
        val dm = DmManager(backgroundScope)
        val alice = signer()
        val bob = signer()
        val rumor = Nip17.buildRumor(alice.pubkey, bob.pubkey, "once")
        // Same rumor wrapped twice (e.g. arriving from two relays) -> distinct gift wraps.
        val w1 = Nip17.wrap(rumor, bob.pubkey, alice)
        val w2 = Nip17.wrap(rumor, bob.pubkey, alice)

        dm.ingestGiftWrap(w1, bob.pubkey, bob)
        dm.ingestGiftWrap(w2, bob.pubkey, bob)

        assertEquals(1, dm.messagesByPeer.value[alice.pubkey]?.size)
    }

    @Test
    fun `kind 10050 is parsed into the peer's DM relay list`() = runTest {
        val dm = DmManager(backgroundScope)
        val event =
            Event(
                pubkey = "ab".repeat(32),
                createdAt = 1L,
                kind = 10050,
                tags = listOf(listOf("relay", "wss://dm.example.com"), listOf("relay", "wss://nos.lol")),
                content = "",
            )
        dm.ingestDmRelays(event)
        assertEquals(listOf("wss://dm.example.com", "wss://nos.lol"), dm.dmRelaysFor("ab".repeat(32)))
    }

    @Test
    fun `incoming message is unread until marked read`() = runTest {
        val dm = DmManager(backgroundScope)
        val alice = signer()
        val bob = signer()
        val rumor = Nip17.buildRumor(alice.pubkey, bob.pubkey, "ping")
        val wrap = Nip17.wrap(rumor, bob.pubkey, alice)

        dm.ingestGiftWrap(wrap, myPubkey = bob.pubkey, signer = bob)
        assertEquals(1, dm.unreadByPeer.first { it.containsKey(alice.pubkey) }[alice.pubkey])

        dm.markRead(alice.pubkey)
        assertTrue(dm.unreadByPeer.first { !it.containsKey(alice.pubkey) }.isEmpty())
    }

    @Test
    fun `my own messages never count as unread`() = runTest {
        val dm = DmManager(backgroundScope)
        val alice = signer()
        val bob = signer()
        val rumor = Nip17.buildRumor(alice.pubkey, bob.pubkey, "mine")
        val selfWrap = Nip17.wrap(rumor, alice.pubkey, alice)

        dm.ingestGiftWrap(selfWrap, myPubkey = alice.pubkey, signer = alice)
        // The conversation with bob appears, but a self-authored message is not unread.
        val convo = dm.conversations.first { list -> list.any { it.peerPubkey == bob.pubkey } }
        assertEquals(0, convo.first { it.peerPubkey == bob.pubkey }.unread)
    }

    @Test
    fun `hydrate restores messages and read state so old messages are not unread`() = runTest {
        val dm = DmManager(backgroundScope)
        val peer = "cd".repeat(32)
        val msg = DmMessage(id = "rumor1", peerPubkey = peer, senderPubkey = peer, content = "hi", createdAt = 10L, mine = false)

        dm.hydrate(listOf(msg), mapOf(peer to 10L))

        assertEquals(1, dm.messagesByPeer.value[peer]?.size)
        val convo = dm.conversations.first { it.isNotEmpty() }
        assertEquals(0, convo.first().unread)
    }

    @Test
    fun `seen-on relays accumulate across every wrap arrival and dedupe`() = runTest {
        val dm = DmManager(backgroundScope)
        val alice = signer()
        val bob = signer()
        val rumor = Nip17.buildRumor(alice.pubkey, bob.pubkey, "hi")
        val wrap = Nip17.wrap(rumor, bob.pubkey, alice)
        val wrapId = wrap.id!!

        // Relay recorded before decrypt, then the wrap decrypts, then the same wrap arrives
        // again from a second relay (deduped as a message, but its relay still counts).
        dm.recordWrapRelay(wrapId, "wss://a.example")
        dm.ingestGiftWrap(wrap, bob.pubkey, bob)
        dm.recordWrapRelay(wrapId, "wss://b.example")
        dm.recordWrapRelay(wrapId, "wss://a.example")

        val msg = dm.messagesByPeer.value[alice.pubkey]!!.first()
        assertEquals(listOf("wss://a.example", "wss://b.example"), msg.relays)
        assertEquals(listOf("wss://a.example", "wss://b.example"), dm.seenRelaysSnapshot()[rumor.id])
    }

    @Test
    fun `hydrate restores persisted seen-on relays onto messages`() = runTest {
        val dm = DmManager(backgroundScope)
        val peer = "cd".repeat(32)
        val msg = DmMessage(id = "rumor1", peerPubkey = peer, senderPubkey = peer, content = "hi", createdAt = 10L, mine = false)

        dm.hydrate(listOf(msg), emptyMap(), mapOf("rumor1" to listOf("wss://seen.example")))

        assertEquals(listOf("wss://seen.example"), dm.messagesByPeer.value[peer]?.first()?.relays)
    }

    @Test
    fun `restored wrap-to-rumor link attaches a relay on the decrypt-skip path`() = runTest {
        // A wrap decrypted in a previous session: its message is hydrated from cache and the
        // wrap->rumor link is restored, but the wrap is never decrypted again this session.
        val dm = DmManager(backgroundScope)
        val peer = "cd".repeat(32)
        val msg = DmMessage(id = "rumor1", peerPubkey = peer, senderPubkey = peer, content = "hi", createdAt = 10L, mine = false)
        dm.hydrate(listOf(msg), emptyMap(), emptyMap(), mapOf("wrap1" to "rumor1"))

        // Re-streamed wrap: only recordWrapRelay runs (no ingestGiftWrap), yet the relay attaches.
        dm.recordWrapRelay("wrap1", "wss://seen.example")

        assertEquals(listOf("wss://seen.example"), dm.messagesByPeer.value[peer]?.first()?.relays)
        assertEquals(mapOf("wrap1" to "rumor1"), dm.wrapToRumorSnapshot())
    }

    @Test
    fun `own message is Sending then Delivered on the self-echo`() = runTest {
        val dm = DmManager(backgroundScope)
        val alice = signer()
        val rumor = Nip17.buildRumor(alice.pubkey, "bb".repeat(32), "hi")
        dm.addOptimistic(rumor, "bb".repeat(32), alice.pubkey)
        assertEquals(GroupManager.MessageStatus.Sending, dm.messageStatus.value[rumor.id])

        // The self-copy echoes back through the inbox (deduped) -> Delivered.
        val selfWrap = Nip17.wrap(rumor, alice.pubkey, alice)
        dm.ingestGiftWrap(selfWrap, alice.pubkey, alice)
        assertEquals(GroupManager.MessageStatus.Delivered, dm.messageStatus.value[rumor.id])
    }

    @Test
    fun `markDelivered is a no-op for an untracked id`() = runTest {
        val dm = DmManager(backgroundScope)
        dm.markDelivered("never-sent")
        assertTrue(dm.messageStatus.value["never-sent"] == null)
    }

    @Test
    fun `onSeenRelaysChanged fires when a new relay is recorded`() = runTest {
        val dm = DmManager(backgroundScope)
        val alice = signer()
        val bob = signer()
        val rumor = Nip17.buildRumor(alice.pubkey, bob.pubkey, "hi")
        val wrap = Nip17.wrap(rumor, bob.pubkey, alice)
        dm.ingestGiftWrap(wrap, bob.pubkey, bob)

        var fired = 0
        dm.onSeenRelaysChanged = { fired++ }
        val wrapId = wrap.id!!
        dm.recordWrapRelay(wrapId, "wss://new.example")
        dm.recordWrapRelay(wrapId, "wss://new.example") // duplicate: no extra fire
        assertEquals(1, fired)
    }
}
