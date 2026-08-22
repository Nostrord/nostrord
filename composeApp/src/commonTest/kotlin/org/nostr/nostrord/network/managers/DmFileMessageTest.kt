package org.nostr.nostrord.network.managers

import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.auth.NostrSigner
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.nostr.Nip17
import org.nostr.nostrord.nostr.Nip17File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * NIP-17 kind:15 file messages, the shape Jumble and 0xchat send an image in: the blob on the
 * media server is AES-GCM ciphertext and the rumor carries its key. Dropping the kind would lose
 * the message entirely, which is what a reader that only accepts kind:14 does.
 */
class DmFileMessageTest {
    private fun signer() = NostrSigner.Local(KeyPair.generate())

    private fun fileRumor(
        sender: String,
        recipient: String,
        url: String = "https://blossom.example/abc.bin",
        tags: List<List<String>> = FILE_TAGS,
    ): Event {
        val rumor =
            Event(
                pubkey = sender,
                createdAt = 1000L,
                kind = Nip17.KIND_FILE,
                tags = listOf(listOf("p", recipient)) + tags,
                content = url,
            )
        return rumor.copy(id = rumor.calculateId())
    }

    @Test
    fun `a kind 15 rumor reaches the conversation`() = runTest {
        val dm = DmManager(backgroundScope)
        val alice = signer()
        val bob = signer()
        val wrap = Nip17.wrap(fileRumor(alice.pubkey, bob.pubkey), bob.pubkey, alice)

        assertTrue(dm.ingestGiftWrap(wrap, bob.pubkey, bob))

        val message = dm.messagesByPeer.value[alice.pubkey]?.singleOrNull()
        assertNotNull(message, "the file message must show up in the conversation")
        assertEquals(Nip17.KIND_FILE, message.kind)
        assertEquals("https://blossom.example/abc.bin", message.content)
    }

    @Test
    fun `the attachment payload is readable off the stored message`() = runTest {
        val dm = DmManager(backgroundScope)
        val alice = signer()
        val bob = signer()
        val wrap = Nip17.wrap(fileRumor(alice.pubkey, bob.pubkey), bob.pubkey, alice)
        dm.ingestGiftWrap(wrap, bob.pubkey, bob)

        val file = dm.messagesByPeer.value[alice.pubkey]?.single()?.file
        assertNotNull(file)
        assertEquals("image/jpeg", file.mimeType)
        assertEquals("aes-gcm", file.algorithm)
        assertEquals("a".repeat(64), file.decryptionKeyHex)
        assertEquals("b".repeat(24), file.decryptionNonceHex)
        assertEquals("c".repeat(64), file.originalHashHex)
        assertEquals(800 to 600, file.dimensions)
        assertEquals(12345L, file.size)
        assertTrue(file.isImage)
        assertTrue(file.isDecryptable)
    }

    @Test
    fun `a chat message carries no attachment`() = runTest {
        val dm = DmManager(backgroundScope)
        val alice = signer()
        val bob = signer()
        val rumor = Nip17.buildRumor(alice.pubkey, bob.pubkey, "just text")
        dm.ingestGiftWrap(Nip17.wrap(rumor, bob.pubkey, alice), bob.pubkey, bob)

        val message = dm.messagesByPeer.value[alice.pubkey]?.single()
        assertNotNull(message)
        assertEquals(Nip17.KIND_CHAT, message.kind)
        assertNull(message.file)
    }

    @Test
    fun `the conversation preview names the attachment instead of its url`() = runTest {
        val dm = DmManager(backgroundScope)
        val alice = signer()
        val bob = signer()
        dm.ingestGiftWrap(Nip17.wrap(fileRumor(alice.pubkey, bob.pubkey), bob.pubkey, alice), bob.pubkey, bob)

        assertEquals("Photo", dm.messagesByPeer.value[alice.pubkey]?.single()?.previewText())
    }

    @Test
    fun `an unreadable encryption scheme is flagged rather than rendered`() {
        val sender = signer().pubkey
        val rumor =
            fileRumor(
                sender,
                signer().pubkey,
                tags = listOf(listOf("file-type", "image/png"), listOf("encryption-algorithm", "chacha20")),
            )
        val file = Nip17File.fromRumor(rumor)
        assertNotNull(file)
        assertTrue(!file.isDecryptable)
        assertTrue(!file.isReadable)
    }

    @Test
    fun `a plain upload with no key is readable as-is`() {
        val rumor =
            fileRumor(
                signer().pubkey,
                signer().pubkey,
                tags = listOf(listOf("file-type", "image/png"), listOf("x", "d".repeat(64)), listOf("size", "10")),
            )
        val file = Nip17File.fromRumor(rumor)
        assertNotNull(file)
        assertTrue(file.isPlain)
        assertTrue(!file.isDecryptable)
        assertTrue(file.isReadable)
        assertEquals("d".repeat(64), file.encryptedHashHex)
    }

    @Test
    fun `a rumor whose content is not a url is not a file message`() {
        val rumor = fileRumor(signer().pubkey, signer().pubkey, url = "not a url")
        assertNull(Nip17File.fromRumor(rumor))
    }

    @Test
    fun `a kind 15 rumor restored from a backup file is filed too`() = runTest {
        val dm = DmManager(backgroundScope)
        val alice = signer()
        val bob = signer()

        assertTrue(dm.importRumor(fileRumor(alice.pubkey, bob.pubkey), bob.pubkey))
        assertEquals(Nip17.KIND_FILE, dm.messagesByPeer.value[alice.pubkey]?.single()?.kind)
    }

    @Test
    fun `a reply carries the id of the message it answers`() = runTest {
        val dm = DmManager(backgroundScope)
        val alice = signer()
        val bob = signer()
        val reply =
            Nip17.buildRumor(
                senderPubkey = alice.pubkey,
                recipientPubkey = bob.pubkey,
                content = "answering that",
                createdAt = 2000L,
                extraTags = listOf(listOf("e", "parent-1")),
            )
        dm.ingestGiftWrap(Nip17.wrap(reply, bob.pubkey, alice), bob.pubkey, bob)

        val message = dm.messagesByPeer.value[alice.pubkey]?.single()
        assertNotNull(message)
        assertEquals("parent-1", message.replyToId)
    }

    @Test
    fun `a plain message answers nothing`() = runTest {
        val dm = DmManager(backgroundScope)
        val alice = signer()
        val bob = signer()
        dm.ingestGiftWrap(Nip17.wrap(Nip17.buildRumor(alice.pubkey, bob.pubkey, "hi"), bob.pubkey, alice), bob.pubkey, bob)

        assertNull(dm.messagesByPeer.value[alice.pubkey]?.single()?.replyToId)
    }

    @Test
    fun `a message keeps its custom emoji tags for the renderer`() = runTest {
        val dm = DmManager(backgroundScope)
        val alice = signer()
        val bob = signer()
        val rumor =
            Nip17.buildRumor(
                senderPubkey = alice.pubkey,
                recipientPubkey = bob.pubkey,
                content = "nice :party:",
                createdAt = 3000L,
                extraTags = listOf(listOf("emoji", "party", "https://cdn.example/party.png")),
            )
        dm.ingestGiftWrap(Nip17.wrap(rumor, bob.pubkey, alice), bob.pubkey, bob)

        val tags = dm.messagesByPeer.value[alice.pubkey]?.single()?.tags.orEmpty()
        assertEquals(
            listOf("emoji", "party", "https://cdn.example/party.png"),
            tags.firstOrNull { it.firstOrNull() == "emoji" },
        )
    }

    private companion object {
        val FILE_TAGS =
            listOf(
                listOf("file-type", "image/jpeg"),
                listOf("encryption-algorithm", "aes-gcm"),
                listOf("decryption-key", "a".repeat(64)),
                listOf("decryption-nonce", "b".repeat(24)),
                listOf("ox", "c".repeat(64)),
                listOf("dim", "800x600"),
                listOf("size", "12345"),
            )
    }
}
