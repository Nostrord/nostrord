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
    fun `seal n tags are read from tags directly`() {
        assertEquals(encKey, Nip4e.encryptionKeyFromTags(listOf(listOf("n", encKey))))
        assertNull(Nip4e.encryptionKeyFromTags(listOf(listOf("p", identity))))
    }
}
