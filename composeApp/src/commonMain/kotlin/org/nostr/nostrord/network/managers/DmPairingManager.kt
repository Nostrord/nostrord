package org.nostr.nostrord.network.managers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.nostr.Nip4e

/**
 * NIP-4e device pairing (kinds 4454 / 4455): moves the encryption key to another device of the
 * same account without the user copying it by hand.
 *
 * The device that wants the key publishes a throwaway pubkey; a device that holds the key answers
 * with the key encrypted throwaway-to-throwaway. Identity keys sign both events but encrypt
 * neither, so only the requesting device can open the answer. Both events are deleted afterwards.
 *
 * Both sides display a code derived from the requester's throwaway pubkey, so the user approves
 * the request they actually made rather than whatever arrived first.
 */
class DmPairingManager {
    sealed interface State {
        data object Idle : State

        /** This device asked for the key and is waiting for another device to answer. */
        data class Requesting(val code: String) : State

        /** Another device of this account is asking us for the key. */
        data class IncomingRequest(val code: String, val throwawayPubkey: String) : State

        data object Completed : State

        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Our throwaway keypair while requesting; the only thing that can open the answer. */
    private var requestKey: KeyPair? = null

    /** Requests already answered this session, so a re-delivered 4454 does not re-prompt. */
    private var answered: Set<String> = emptySet()

    /** Begin a request. Returns the throwaway pubkey to publish in the kind:4454. */
    fun beginRequest(): String {
        val throwaway = KeyPair.generate()
        requestKey = throwaway
        _state.value = State.Requesting(Nip4e.pairingCode(throwaway.publicKeyHex))
        return throwaway.publicKeyHex
    }

    fun requestThrowawayKey(): KeyPair? = requestKey

    /**
     * A kind:4454 from our own account arrived. Ignored when it is our own outstanding request,
     * or one we already answered; otherwise it becomes a prompt for the user.
     */
    fun onRequestSeen(event: Event) {
        val throwaway = Nip4e.throwawayPubkeyFrom(event) ?: return
        if (throwaway == requestKey?.publicKeyHex) return
        if (throwaway in answered) return
        if (_state.value is State.Requesting) return
        _state.value = State.IncomingRequest(Nip4e.pairingCode(throwaway), throwaway)
    }

    /** Mark an incoming request handled (approved and answered, or declined). */
    fun resolveIncoming(throwawayPubkey: String) {
        answered = answered + throwawayPubkey
        if ((_state.value as? State.IncomingRequest)?.throwawayPubkey == throwawayPubkey) {
            _state.value = State.Idle
        }
    }

    fun succeeded() {
        requestKey = null
        _state.value = State.Completed
    }

    fun failed(reason: String) {
        requestKey = null
        _state.value = State.Failed(reason)
    }

    fun reset() {
        requestKey = null
        _state.value = State.Idle
    }

    fun clear() {
        requestKey = null
        answered = emptySet()
        _state.value = State.Idle
    }
}
