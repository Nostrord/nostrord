package org.nostr.nostrord.network.managers

import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.nostr.Nip44
import org.nostr.nostrord.nostr.Nip4e
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class DmPairingManagerTest {
    private val identity = "a".repeat(64)

    private fun request(throwawayPubkey: String) = Nip4e.buildClientKeyRequest(identity, throwawayPubkey, createdAt = 1L)

    @Test
    fun `requesting shows a code derived from the throwaway key`() {
        val manager = DmPairingManager()
        val throwaway = manager.beginRequest()

        val state = manager.state.value
        assertIs<DmPairingManager.State.Requesting>(state)
        assertEquals(Nip4e.pairingCode(throwaway), state.code)
        assertNotNull(manager.requestThrowawayKey())
    }

    @Test
    fun `our own request never prompts us`() {
        val manager = DmPairingManager()
        val throwaway = manager.beginRequest()

        manager.onRequestSeen(request(throwaway))

        // The requesting device receives its own event back from the relay; answering itself
        // would be a no-op at best and a confusing prompt at worst.
        assertIs<DmPairingManager.State.Requesting>(manager.state.value)
    }

    @Test
    fun `another device's request becomes a prompt with the same code`() {
        val manager = DmPairingManager()
        val other = KeyPair.generate()

        manager.onRequestSeen(request(other.publicKeyHex))

        val state = manager.state.value
        assertIs<DmPairingManager.State.IncomingRequest>(state)
        assertEquals(other.publicKeyHex, state.throwawayPubkey)
        // Both devices derive the code from the same value, so comparing them is meaningful.
        assertEquals(Nip4e.pairingCode(other.publicKeyHex), state.code)
    }

    @Test
    fun `a resolved request does not prompt again when the event is re-delivered`() {
        val manager = DmPairingManager()
        val other = KeyPair.generate()

        manager.onRequestSeen(request(other.publicKeyHex))
        manager.resolveIncoming(other.publicKeyHex)
        assertIs<DmPairingManager.State.Idle>(manager.state.value)

        // Same event arriving from a second relay must stay silent.
        manager.onRequestSeen(request(other.publicKeyHex))
        assertIs<DmPairingManager.State.Idle>(manager.state.value)
    }

    @Test
    fun `a request with no throwaway key is ignored`() {
        val manager = DmPairingManager()
        manager.onRequestSeen(Nip4e.buildClientKeyRequest(identity, "not-a-key", createdAt = 1L))
        assertIs<DmPairingManager.State.Idle>(manager.state.value)
    }

    @Test
    fun `account switch drops any pending pairing`() {
        val manager = DmPairingManager()
        manager.beginRequest()
        manager.clear()
        assertIs<DmPairingManager.State.Idle>(manager.state.value)
        assertEquals(null, manager.requestThrowawayKey())
    }

    @Test
    fun `the shared key round-trips between the two throwaway keys`() {
        // The share is encrypted throwaway-to-throwaway, so neither identity key can open it and
        // only the device that published the request can read the answer.
        val encryptionKey = KeyPair.generate()
        val requester = KeyPair.generate()
        val holder = KeyPair.generate()

        val ciphertext = Nip44.encrypt(encryptionKey.privateKeyHex, holder.privateKeyHex, requester.publicKeyHex)
        val share = Nip4e.buildKeyShare(identity, holder.publicKeyHex, requester.publicKeyHex, ciphertext, createdAt = 2L)

        assertEquals(requester.publicKeyHex, Nip4e.keyShareRecipientFrom(share))
        assertEquals(holder.publicKeyHex, Nip4e.throwawayPubkeyFrom(share))
        assertEquals(
            encryptionKey.privateKeyHex,
            Nip44.decrypt(share.content, requester.privateKeyHex, holder.publicKeyHex),
        )
    }
}
