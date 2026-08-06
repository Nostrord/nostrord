package org.nostr.nostrord.network.managers

import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.nostr.Nip4e
import org.nostr.nostrord.storage.Nip4eStoredKey
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.loadNip4eKeysFor
import org.nostr.nostrord.storage.saveNip4eAnnouncedFor
import org.nostr.nostrord.storage.saveNip4eKeysFor
import org.nostr.nostrord.utils.epochSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DmEncryptionManagerTest {
    // Distinct per test: the manager persists to SecureStorage, which is shared across the run.
    private fun account(tag: String) = tag.padEnd(64, '0')

    /** A manager on a known-empty account: the slots outlive the process, so reset them first. */
    private fun freshManager(pubkey: String, remoteSigner: Boolean = true): DmEncryptionManager {
        SecureStorage.saveNip4eKeysFor(pubkey, emptyList())
        SecureStorage.saveNip4eAnnouncedFor(pubkey, false)
        return DmEncryptionManager().also { it.loadFor(pubkey, remoteSigner) }
    }

    @Test
    fun `a locally signing account is offered nothing`() {
        val manager = freshManager(account("a1"), remoteSigner = false)
        assertIs<DmEncryptionManager.State.Unavailable>(manager.state.value)
    }

    @Test
    fun `generating holds the key and announcing activates it`() {
        val manager = freshManager(account("a2"))
        assertIs<DmEncryptionManager.State.Disabled>(manager.state.value)

        val encPubkey = manager.generateKey()
        assertEquals(encPubkey, (manager.state.value as DmEncryptionManager.State.HeldNotAnnounced).encPubkey)

        manager.setAnnounced(true)
        assertEquals(encPubkey, (manager.state.value as DmEncryptionManager.State.Active).encPubkey)
    }

    @Test
    fun `disabling stops advertising but never drops the key`() {
        val manager = freshManager(account("a3"))
        val encPubkey = manager.generateKey()
        manager.setAnnounced(true)

        manager.setAnnounced(false)
        // Messages already addressed to this key only open with it, so it has to survive.
        assertEquals(encPubkey, (manager.state.value as DmEncryptionManager.State.HeldNotAnnounced).encPubkey)
        assertEquals(encPubkey, manager.currentEncPubkeyOrNull())
    }

    @Test
    fun `a key announced by another device must be imported`() {
        val pubkey = account("a4")
        val manager = freshManager(pubkey)
        val elsewhere = KeyPair.generate()

        manager.ingestOwnAnnouncement(Nip4e.buildAnnouncement(pubkey, elsewhere.publicKeyHex, createdAt = 10L))
        assertEquals(elsewhere.publicKeyHex, (manager.state.value as DmEncryptionManager.State.AnnouncedElsewhere).encPubkey)

        // A different key would leave us unable to read, so it is refused outright.
        assertFalse(manager.importKey(KeyPair.generate().privateKeyHex))
        assertIs<DmEncryptionManager.State.AnnouncedElsewhere>(manager.state.value)

        assertTrue(manager.importKey(elsewhere.privateKeyHex))
        assertEquals(elsewhere.publicKeyHex, (manager.state.value as DmEncryptionManager.State.Active).encPubkey)
    }

    @Test
    fun `retired keys stay held so their history keeps opening`() {
        val manager = freshManager(account("a5"))
        val first = manager.generateKey()
        val second = manager.generateKey()

        val held = manager.heldKeys().map { it.publicKeyHex }
        assertEquals(listOf(second, first), held, "current key first, retired keys still tried on receive")
    }

    @Test
    fun `a withdrawal from another device stops us advertising`() {
        val pubkey = account("a6")
        val manager = freshManager(pubkey)
        val encPubkey = manager.generateKey()
        manager.setAnnounced(true)

        manager.ingestOwnAnnouncement(Nip4e.buildAnnouncement(pubkey, null, createdAt = 20L))
        assertEquals(encPubkey, (manager.state.value as DmEncryptionManager.State.HeldNotAnnounced).encPubkey)
    }

    @Test
    fun `our own announcement of a key we hold reactivates it`() {
        val pubkey = account("a7")
        val manager = freshManager(pubkey)
        val encPubkey = manager.generateKey()

        manager.ingestOwnAnnouncement(Nip4e.buildAnnouncement(pubkey, encPubkey, createdAt = 30L))
        assertEquals(encPubkey, (manager.state.value as DmEncryptionManager.State.Active).encPubkey)
    }

    @Test
    fun `rotating advertises a new key and keeps the old one readable`() {
        val manager = freshManager(account("a9"))
        val first = manager.generateKey()
        manager.setAnnounced(true)

        val second = manager.generateKey()
        manager.setAnnounced(true)

        assertEquals(second, (manager.state.value as DmEncryptionManager.State.Active).encPubkey)
        // Contacts who have not re-read the announcement still address the old key, and its
        // history only ever opens with it.
        assertEquals(listOf(second, first), manager.heldKeys().map { it.publicKeyHex })
    }

    @Test
    fun `rotating does not move the archive cutoff`() {
        val manager = freshManager(account("aa"))
        manager.generateKey()
        manager.setAnnounced(true)
        val firstAnnouncedAt = manager.announcedAt()

        manager.generateKey()
        manager.setAnnounced(true)

        // Messages between the first announcement and the rotation are already addressed to a key
        // we still hold, so moving the cutoff would re-archive them for nothing.
        assertEquals(firstAnnouncedAt, manager.announcedAt())
    }

    @Test
    fun `a retired key past the retention window is dropped`() {
        val pubkey = account("ab")
        val stale = KeyPair.generate()
        val current = KeyPair.generate()
        SecureStorage.saveNip4eAnnouncedFor(pubkey, true)
        SecureStorage.saveNip4eKeysFor(
            pubkey,
            listOf(
                Nip4eStoredKey(current.privateKeyHex),
                Nip4eStoredKey(stale.privateKeyHex, retiredAt = epochSeconds() - DmEncryptionManager.RETENTION_SECONDS - 1),
            ),
        )

        val manager = DmEncryptionManager()
        manager.loadFor(pubkey, remoteSigner = true)

        // A rotated-away key is a decryption capability we deliberately stop keeping around.
        assertEquals(listOf(current.publicKeyHex), manager.heldKeys().map { it.publicKeyHex })
    }

    @Test
    fun `a retired key inside the window is kept`() {
        val pubkey = account("ac")
        val recent = KeyPair.generate()
        val current = KeyPair.generate()
        SecureStorage.saveNip4eAnnouncedFor(pubkey, true)
        SecureStorage.saveNip4eKeysFor(
            pubkey,
            listOf(
                Nip4eStoredKey(current.privateKeyHex),
                Nip4eStoredKey(recent.privateKeyHex, retiredAt = epochSeconds() - 60),
            ),
        )

        val manager = DmEncryptionManager()
        manager.loadFor(pubkey, remoteSigner = true)

        assertEquals(listOf(current.publicKeyHex, recent.publicKeyHex), manager.heldKeys().map { it.publicKeyHex })
    }

    @Test
    fun `no more than the cap of retired keys is kept`() {
        val pubkey = account("ad")
        val current = KeyPair.generate()
        val now = epochSeconds()
        val retired = (1..DmEncryptionManager.MAX_RETIRED_KEYS + 5).map {
            Nip4eStoredKey(KeyPair.generate().privateKeyHex, retiredAt = now - it)
        }
        SecureStorage.saveNip4eAnnouncedFor(pubkey, true)
        SecureStorage.saveNip4eKeysFor(pubkey, listOf(Nip4eStoredKey(current.privateKeyHex)) + retired)

        val manager = DmEncryptionManager()
        manager.loadFor(pubkey, remoteSigner = true)

        assertEquals(DmEncryptionManager.MAX_RETIRED_KEYS + 1, manager.heldKeys().size)
        assertEquals(current.publicKeyHex, manager.heldKeys().first().publicKeyHex, "the current key is never pruned")
    }

    @Test
    fun `rotating stamps the replaced key so its window starts now`() {
        val manager = freshManager(account("ae"))
        val first = manager.generateKey()
        manager.setAnnounced(true)
        val second = manager.generateKey()

        assertEquals(listOf(second, first), manager.heldKeys().map { it.publicKeyHex })
        val stored = SecureStorage.loadNip4eKeysFor(account("ae"))
        assertEquals(0L, stored.first().retiredAt, "the current key carries no retirement stamp")
        assertTrue(stored[1].retiredAt > 0L, "the replaced key starts its retention window")
    }

    @Test
    fun `account switch drops in-memory state`() {
        val manager = freshManager(account("a8"))
        manager.generateKey()
        manager.clear()
        assertIs<DmEncryptionManager.State.Unavailable>(manager.state.value)
        assertTrue(manager.heldKeys().isEmpty())
    }
}
