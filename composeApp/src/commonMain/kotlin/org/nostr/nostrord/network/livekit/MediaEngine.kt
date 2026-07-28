package org.nostr.nostrord.network.livekit

import kotlinx.coroutines.flow.StateFlow
import org.nostr.nostrord.utils.Result

/** Where a connection attempt currently stands. */
enum class AvConnectionState { Disconnected, Connecting, Connected, Reconnecting }

/**
 * One participant as the media engine sees them, keyed by the LiveKit identity.
 *
 * [pubkey] is the Nostr identity behind that LiveKit identity, or null when the room contains a
 * participant whose `sub` does not carry one (a non-NIP-29 client on the same LiveKit server).
 */
data class AvParticipant(
    val identity: String,
    val pubkey: String?,
    val isLocal: Boolean = false,
    val isSpeaking: Boolean = false,
    val micEnabled: Boolean = false,
    val cameraEnabled: Boolean = false,
)

/**
 * Live audio/video transport for a NIP-29 AV space.
 *
 * The protocol layer (metadata tags, kind 39004, NIP-98 token minting) is shared by every
 * target; only capture and playback need a LiveKit SDK, which exists per platform. Targets
 * without one report [isSupported] false and fail [connect], so the UI can show the room roster
 * from kind 39004 and explain that joining is unavailable here.
 */
expect class MediaEngine() {
    /** Whether this platform can actually join a room. */
    val isSupported: Boolean

    val connectionState: StateFlow<AvConnectionState>

    /** Everyone the engine currently sees in the room, local participant included. */
    val participants: StateFlow<List<AvParticipant>>

    val micEnabled: StateFlow<Boolean>
    val cameraEnabled: StateFlow<Boolean>

    /** Join the room described by [credentials]. Publishes nothing until a track is enabled. */
    suspend fun connect(credentials: LiveKitCredentials): Result<Unit>

    /**
     * Leave the room and release capture devices. Idempotent, and non-suspending so teardown
     * still runs from `ViewModel.onCleared`, where the viewModelScope is already cancelled.
     */
    fun disconnect()

    /** Enable or disable microphone capture. Prompts for permission on first enable. */
    suspend fun setMicEnabled(enabled: Boolean): Result<Unit>

    /** Enable or disable camera capture. Prompts for permission on first enable. */
    suspend fun setCameraEnabled(enabled: Boolean): Result<Unit>
}
