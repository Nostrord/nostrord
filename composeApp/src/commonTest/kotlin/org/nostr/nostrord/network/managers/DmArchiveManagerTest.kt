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

class DmArchiveManagerTest {
    private fun rumor(author: String, recipient: String, content: String, createdAt: Long) = Nip17.buildRumor(author, recipient, content, createdAt)

    private fun wrapOf(rumor: Event) = rumor.copy(kind = Nip17.KIND_GIFT_WRAP)

    @Test
    fun `only messages predating the announcement are archived`() {
        val manager = DmArchiveManager()
        val me = "a".repeat(64)
        val peer = "b".repeat(64)
        val old = rumor(peer, me, "before", createdAt = 100L)
        val new = rumor(peer, me, "after", createdAt = 300L)

        val pending = manager.pending(listOf(old, new), announcedAt = 200L)
        // Anything sent after the announcement is already addressed to the encryption key.
        assertEquals(listOf(old.id), pending.map { it.id })
    }

    @Test
    fun `already archived ids are skipped on a re-run`() {
        val manager = DmArchiveManager()
        val me = "a".repeat(64)
        val peer = "b".repeat(64)
        val first = rumor(peer, me, "one", createdAt = 10L)
        val second = rumor(peer, me, "two", createdAt = 20L)
        manager.hydrate(setOf(first.id!!))

        assertEquals(listOf(second.id), manager.pending(listOf(first, second), announcedAt = 0L).map { it.id })
    }

    @Test
    fun `progress only advances on relay acceptance`() = runTest {
        val manager = DmArchiveManager()
        val me = "a".repeat(64)
        val peer = "b".repeat(64)
        val accepted = rumor(peer, me, "accepted", createdAt = 10L)
        val rejected = rumor(peer, me, "rejected", createdAt = 20L)
        var persisted: Set<String> = emptySet()

        manager.run(
            rumors = listOf(accepted, rejected),
            buildWrap = { wrapOf(it) },
            publish = { wrap -> wrap.content == "accepted" },
            persistProgress = { persisted = it },
        )

        assertEquals(1, manager.progress.value.done)
        assertEquals(1, manager.progress.value.failed)
        assertTrue(!manager.progress.value.running)
        // A rejected copy must stay pending, or it would be silently lost on the next device.
        assertEquals(setOf(accepted.id), persisted)
    }

    @Test
    fun `a relay refusing everything aborts instead of hammering`() = runTest {
        val manager = DmArchiveManager()
        val me = "a".repeat(64)
        val peer = "b".repeat(64)
        val rumors = (1..20).map { rumor(peer, me, "m$it", createdAt = it.toLong()) }
        var attempts = 0

        manager.run(
            rumors = rumors,
            buildWrap = { wrapOf(it) },
            publish = {
                attempts++
                false
            },
            persistProgress = {},
        )

        assertEquals(DmArchiveManager.MAX_CONSECUTIVE_FAILURES, attempts)
        assertNotNull(manager.progress.value.error)
        assertTrue(!manager.progress.value.running)
    }

    @Test
    fun `cancelling stops the run and keeps what was published`() = runTest {
        val manager = DmArchiveManager()
        val me = "a".repeat(64)
        val peer = "b".repeat(64)
        val rumors = (1..5).map { rumor(peer, me, "m$it", createdAt = it.toLong()) }

        manager.run(
            rumors = rumors,
            buildWrap = { wrapOf(it) },
            publish = {
                manager.cancel()
                true
            },
            persistProgress = {},
        )

        assertEquals(1, manager.progress.value.done)
        assertTrue(!manager.progress.value.running)
        assertEquals(1, manager.archivedIds().size)
    }

    @Test
    fun `an archived foreign rumor comes back with its original author`() = runTest {
        // End to end over the real envelope: this is the shape the repository publishes.
        val me = NostrSigner.Local(KeyPair.generate())
        val alice = NostrSigner.Local(KeyPair.generate())
        val myEnc = KeyPair.generate()
        val original = Nip17.buildRumor(alice.pubkey, me.pubkey, "said long ago", createdAt = 50L)

        val archived =
            Nip17.wrap(
                original,
                me.pubkey,
                me,
                encryptTo = myEnc.publicKeyHex,
                senderEncTag = myEnc.publicKeyHex,
                encryptWith = myEnc.privateKeyHex,
            )

        val decryptor = Nip17.Nip44Decryptor { peer, ciphertext -> org.nostr.nostrord.nostr.Nip44.decrypt(ciphertext, myEnc.privateKeyHex, peer) }
        val out = Nip17.unwrap(archived, me.pubkey, listOf(decryptor))

        assertNotNull(out)
        assertEquals("said long ago", out.rumor.content)
        assertEquals(alice.pubkey, out.senderPubkey, "the archive must not reattribute the message to us")
        assertEquals(original.id, out.rumor.id, "same rumor id, so every client dedupes it against the original")
    }

    @Test
    fun `nobody else accepts our archive of a foreign rumor`() = runTest {
        val me = NostrSigner.Local(KeyPair.generate())
        val alice = NostrSigner.Local(KeyPair.generate())
        val myEnc = KeyPair.generate()
        val archived =
            Nip17.wrap(
                Nip17.buildRumor(alice.pubkey, me.pubkey, "private", createdAt = 50L),
                me.pubkey,
                me,
                encryptTo = myEnc.publicKeyHex,
                senderEncTag = myEnc.publicKeyHex,
                encryptWith = myEnc.privateKeyHex,
            )

        val decryptor = Nip17.Nip44Decryptor { peer, ciphertext -> org.nostr.nostrord.nostr.Nip44.decrypt(ciphertext, myEnc.privateKeyHex, peer) }
        // Same key material, but the reader is not the seal's author: the carve-out does not apply
        // and the NIP-59 author guard rejects it. This is why other clients drop it silently.
        assertNull(Nip17.unwrap(archived, alice.pubkey, listOf(decryptor)))
    }

    @Test
    fun `our own archived message stays a plain interoperable self-wrap`() = runTest {
        val me = NostrSigner.Local(KeyPair.generate())
        val peer = KeyPair.generate()
        val myEnc = KeyPair.generate()
        val mine = Nip17.buildRumor(me.pubkey, peer.publicKeyHex, "I said this", createdAt = 50L)

        val archived =
            Nip17.wrap(
                mine,
                me.pubkey,
                me,
                encryptTo = myEnc.publicKeyHex,
                senderEncTag = myEnc.publicKeyHex,
                encryptWith = myEnc.privateKeyHex,
            )

        val decryptor = Nip17.Nip44Decryptor { p, ciphertext -> org.nostr.nostrord.nostr.Nip44.decrypt(ciphertext, myEnc.privateKeyHex, p) }
        // seal.pubkey == rumor.pubkey here, so it passes the ordinary guard: no carve-out needed
        // and any conforming client on this account reads it.
        val out = Nip17.unwrap(archived, "00".repeat(32), listOf(decryptor))
        assertNotNull(out)
        assertEquals(me.pubkey, out.senderPubkey)
        assertEquals(listOf(listOf("p", myEnc.publicKeyHex), listOf("p", me.pubkey)), archived.tags)
    }
}
