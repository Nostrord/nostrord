package org.nostr.nostrord.network.managers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.nostr.Nip4e
import org.nostr.nostrord.utils.epochSeconds

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

        /**
         * Devices of this account asking us for the key, oldest first. A list, not a slot: the
         * relay serves every request it still holds, and a slot kept only the last one, so the
         * others came back one per launch with no way to decide them.
         */
        data class IncomingRequests(val requests: List<Request>) : State

        data object Completed : State

        data class Failed(val reason: String) : State
    }

    /** One device asking for the key. [code] is what the user compares against that device. */
    data class Request(val code: String, val throwawayPubkey: String, val eventId: String)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Our throwaway keypair while requesting; the only thing that can open the answer. */
    private var requestKey: KeyPair? = null

    /**
     * Requests already decided, by kind:4454 event id, with when they were decided. Persisted by
     * the owner through [onProcessedChanged]: the subscription looks back in time, so a decision
     * kept only in memory means every restart re-prompts for the same declined request.
     */
    private var processed: Map<String, Long> = emptyMap()

    /** Called whenever [processed] changes, so the owner can persist it. */
    var onProcessedChanged: ((Map<String, Long>) -> Unit)? = null

    fun hydrateProcessed(stored: Map<String, Long>) {
        processed = prune(stored)
    }

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
     * or one already decided; otherwise it becomes a prompt for the user.
     */
    fun onRequestSeen(event: Event) {
        val eventId = event.id ?: return
        val throwaway = Nip4e.throwawayPubkeyFrom(event) ?: return
        if (throwaway == requestKey?.publicKeyHex) return
        if (eventId in processed) return
        if (_state.value is State.Requesting) return
        val pending = (_state.value as? State.IncomingRequests)?.requests.orEmpty()
        // The same request arrives once per relay; both keys identify it.
        if (pending.any { it.eventId == eventId || it.throwawayPubkey == throwaway }) return
        _state.value = State.IncomingRequests(pending + Request(Nip4e.pairingCode(throwaway), throwaway, eventId))
    }

    /**
     * Record a request as decided (approved, declined, or made by us), so it never prompts again
     * while the relay still serves it.
     */
    fun markProcessed(eventId: String) {
        if (eventId.isBlank() || eventId in processed) return
        processed = prune(processed + (eventId to epochSeconds()))
        onProcessedChanged?.invoke(processed)
    }

    /** The pending request for [throwawayPubkey], if it is still waiting on a decision. */
    fun pendingRequest(throwawayPubkey: String): Request? = (_state.value as? State.IncomingRequests)?.requests?.firstOrNull { it.throwawayPubkey == throwawayPubkey }

    /** Mark one incoming request handled (approved and answered, or declined). */
    fun resolveIncoming(throwawayPubkey: String) {
        val pending = (_state.value as? State.IncomingRequests)?.requests ?: return
        val match = pending.firstOrNull { it.throwawayPubkey == throwawayPubkey } ?: return
        markProcessed(match.eventId)
        val rest = pending - match
        _state.value = if (rest.isEmpty()) State.Idle else State.IncomingRequests(rest)
    }

    /** Decline everything pending at once, for the pile a device leaves behind after retrying. */
    fun resolveAllIncoming() {
        val pending = (_state.value as? State.IncomingRequests)?.requests ?: return
        pending.forEach { markProcessed(it.eventId) }
        _state.value = State.Idle
    }

    /** Drop decisions older than the window the pairing subscription can still deliver. */
    private fun prune(all: Map<String, Long>): Map<String, Long> {
        val now = epochSeconds()
        return all.filterValues { now - it < PROCESSED_RETENTION_SECONDS }
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
        processed = emptyMap()
        _state.value = State.Idle
    }

    companion object {
        /**
         * Must cover the pairing subscription's lookback, or a decision expires while the relay is
         * still serving the request it decided and the prompt comes back.
         */
        const val PROCESSED_RETENTION_SECONDS = 24L * 60 * 60
    }
}
