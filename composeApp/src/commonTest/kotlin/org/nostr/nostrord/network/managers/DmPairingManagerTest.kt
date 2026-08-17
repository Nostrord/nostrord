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

    // Carries an id like the relay-delivered event does: dedup is keyed on it.
    private fun request(throwawayPubkey: String) = Nip4e.buildClientKeyRequest(identity, throwawayPubkey, createdAt = 1L).let { it.copy(id = it.calculateId()) }

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
        assertIs<DmPairingManager.State.IncomingRequests>(state)
        assertEquals(other.publicKeyHex, state.requests.single().throwawayPubkey)
        // Both devices derive the code from the same value, so comparing them is meaningful.
        assertEquals(Nip4e.pairingCode(other.publicKeyHex), state.requests.single().code)
    }

    @Test
    fun `every pending request is offered, and deciding one keeps the rest`() {
        val manager = DmPairingManager()
        val first = KeyPair.generate()
        val second = KeyPair.generate()

        // A device that retried published one request per attempt, and the relay still serves both.
        manager.onRequestSeen(request(first.publicKeyHex))
        manager.onRequestSeen(request(second.publicKeyHex))
        assertEquals(
            listOf(first.publicKeyHex, second.publicKeyHex),
            assertIs<DmPairingManager.State.IncomingRequests>(manager.state.value).requests.map { it.throwawayPubkey },
        )

        manager.resolveIncoming(first.publicKeyHex)
        assertEquals(
            listOf(second.publicKeyHex),
            assertIs<DmPairingManager.State.IncomingRequests>(manager.state.value).requests.map { it.throwawayPubkey },
        )
    }

    @Test
    fun `declining all decides every pending request at once`() {
        val manager = DmPairingManager()
        val first = KeyPair.generate()
        val second = KeyPair.generate()
        var persisted: Map<String, Long> = emptyMap()
        manager.onProcessedChanged = { persisted = it }

        manager.onRequestSeen(request(first.publicKeyHex))
        manager.onRequestSeen(request(second.publicKeyHex))
        manager.resolveAllIncoming()

        assertIs<DmPairingManager.State.Idle>(manager.state.value)
        // Each one is recorded, or the relay serving it again re-prompts on the next launch.
        assertEquals(2, persisted.size)
        manager.onRequestSeen(request(first.publicKeyHex))
        manager.onRequestSeen(request(second.publicKeyHex))
        assertIs<DmPairingManager.State.Idle>(manager.state.value)
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
    fun `a share published by another holder drops our prompt for the same request`() {
        val manager = DmPairingManager()
        val requester = KeyPair.generate()
        val holder = KeyPair.generate()
        manager.onRequestSeen(request(requester.publicKeyHex))

        // A third device answered first. Its kind:4455 reaches every device of the account, and the
        // recipient tag says which request it settles.
        val share = Nip4e.buildKeyShare(identity, holder.publicKeyHex, requester.publicKeyHex, "ciphertext", createdAt = 2L)
        manager.resolveIncoming(Nip4e.keyShareRecipientFrom(share)!!)

        assertIs<DmPairingManager.State.Idle>(manager.state.value)
        // And it stays gone: the relay keeps serving the request until it is deleted or expires.
        manager.onRequestSeen(request(requester.publicKeyHex))
        assertIs<DmPairingManager.State.Idle>(manager.state.value)
    }

    @Test
    fun `a declined request stays declined across a restart`() {
        val other = KeyPair.generate()
        val event = request(other.publicKeyHex)
        var persisted: Map<String, Long> = emptyMap()

        val manager = DmPairingManager()
        manager.onProcessedChanged = { persisted = it }
        manager.onRequestSeen(event)
        manager.resolveIncoming(other.publicKeyHex)
        assertEquals(setOf(event.id), persisted.keys)

        // The subscription looks back in time, so the relay serves the same request again on the
        // next launch. A fresh manager hydrated from storage must stay quiet.
        val restarted = DmPairingManager()
        restarted.hydrateProcessed(persisted)
        restarted.onRequestSeen(event)
        assertIs<DmPairingManager.State.Idle>(restarted.state.value)
    }

    @Test
    fun `a decision older than the retention window is forgotten`() {
        val other = KeyPair.generate()
        val event = request(other.publicKeyHex)
        val expired = mapOf(event.id!! to 1L)

        val manager = DmPairingManager()
        manager.hydrateProcessed(expired)
        manager.onRequestSeen(event)

        // Past the window the relay no longer serves it either; a request this old arriving now is
        // a new one worth showing.
        assertIs<DmPairingManager.State.IncomingRequests>(manager.state.value)
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
