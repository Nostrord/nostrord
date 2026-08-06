package org.nostr.nostrord.nostr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Nip4eTest {
    private val identity = "a".repeat(64)
    private val encKey = "b".repeat(64)

    private fun announcement(tags: List<List<String>>, kind: Int = Nip4e.KIND_ENCRYPTION_KEY) = Event(pubkey = identity, createdAt = 1L, kind = kind, tags = tags, content = "")

    @Test
    fun `reads the announced encryption key`() {
        assertEquals(encKey, Nip4e.encryptionKeyFrom(announcement(listOf(listOf("n", encKey)))))
    }

    @Test
    fun `an announcement without an n tag announces no key`() {
        // The withdrawal shape: the author is back to identity-addressed encryption.
        assertNull(Nip4e.encryptionKeyFrom(announcement(emptyList())))
        assertNull(Nip4e.encryptionKeyFrom(announcement(listOf(listOf("relay", "wss://x")))))
    }

    @Test
    fun `malformed keys are ignored rather than sent to`() {
        assertNull(Nip4e.encryptionKeyFrom(announcement(listOf(listOf("n")))))
        assertNull(Nip4e.encryptionKeyFrom(announcement(listOf(listOf("n", "")))))
        assertNull(Nip4e.encryptionKeyFrom(announcement(listOf(listOf("n", "tooshort")))))
        assertNull(Nip4e.encryptionKeyFrom(announcement(listOf(listOf("n", "z".repeat(64))))))
    }

    @Test
    fun `the first valid n tag wins`() {
        val event = announcement(listOf(listOf("n", "nothex"), listOf("n", encKey)))
        assertEquals(encKey, Nip4e.encryptionKeyFrom(event))
    }

    @Test
    fun `another kind carrying an n tag is not an announcement`() {
        assertNull(Nip4e.encryptionKeyFrom(announcement(listOf(listOf("n", encKey)), kind = 10050)))
    }

    @Test
    fun `announcement round-trips`() {
        val built = Nip4e.buildAnnouncement(identity, encKey, createdAt = 42L)
        assertEquals(Nip4e.KIND_ENCRYPTION_KEY, built.kind)
        assertEquals(identity, built.pubkey)
        assertEquals(42L, built.createdAt)
        assertEquals(encKey, Nip4e.encryptionKeyFrom(built))
    }

    @Test
    fun `a null key builds the withdrawal shape`() {
        val built = Nip4e.buildAnnouncement(identity, null, createdAt = 42L)
        assertTrue(built.tags.none { it.firstOrNull() == Nip4e.TAG_ENCRYPTION_PUBKEY })
        assertNull(Nip4e.encryptionKeyFrom(built))
    }

    @Test
    fun `a pairing request carries the throwaway key under both tag names`() {
        val throwaway = "c".repeat(64)
        val request = Nip4e.buildClientKeyRequest(identity, throwaway, createdAt = 5L)

        assertEquals(Nip4e.KIND_CLIENT_KEY, request.kind)
        assertEquals(throwaway, Nip4e.throwawayPubkeyFrom(request))
        // `P` is what the NIP defines; `pubkey` is the only one Coop reads.
        assertTrue(request.tags.any { it.firstOrNull() == Nip4e.TAG_THROWAWAY_PUBKEY && it[1] == throwaway })
        assertTrue(request.tags.any { it.firstOrNull() == Nip4e.TAG_THROWAWAY_PUBKEY_LEGACY && it[1] == throwaway })
    }

    @Test
    fun `a request carrying only Coop's tag is still understood`() {
        val throwaway = "c".repeat(64)
        val coopStyle =
            Event(
                pubkey = identity,
                createdAt = 5L,
                kind = Nip4e.KIND_CLIENT_KEY,
                tags = listOf(listOf(Nip4e.TAG_THROWAWAY_PUBKEY_LEGACY, throwaway)),
                content = "",
            )
        assertEquals(throwaway, Nip4e.throwawayPubkeyFrom(coopStyle))
    }

    @Test
    fun `no device label goes on the wire`() {
        // It would tell relays the OS and browser of the device asking to be paired.
        val request = Nip4e.buildClientKeyRequest(identity, "c".repeat(64), createdAt = 5L)
        assertTrue(request.tags.none { it.firstOrNull() == "client" })
    }

    @Test
    fun `a key share names both throwaway keys`() {
        val sender = "c".repeat(64)
        val recipient = "d".repeat(64)
        val share = Nip4e.buildKeyShare(identity, sender, recipient, "ciphertext", createdAt = 6L)

        assertEquals(Nip4e.KIND_KEY_SHARE, share.kind)
        assertEquals(sender, Nip4e.throwawayPubkeyFrom(share))
        assertEquals(recipient, Nip4e.keyShareRecipientFrom(share))
        assertEquals("ciphertext", share.content)
    }

    @Test
    fun `the pairing code matches the format the other clients show`() {
        // The user compares this across two devices, so the format has to be identical to
        // Jumble's: first 8 characters, uppercase, split into two groups.
        val throwaway = "abcdef12" + "0".repeat(56)
        assertEquals("ABCD EF12", Nip4e.pairingCode(throwaway))
        assertEquals(Nip4e.pairingCode(throwaway), Nip4e.pairingCode(throwaway))
    }

    @Test
    fun `seal n tags are read from tags directly`() {
        assertEquals(encKey, Nip4e.encryptionKeyFromTags(listOf(listOf("n", encKey))))
        assertNull(Nip4e.encryptionKeyFromTags(listOf(listOf("p", identity))))
    }
}
