package org.nostr.nostrord.network.livekit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result

/** Message shown when a target has no LiveKit SDK behind it. */
const val AV_UNSUPPORTED_MESSAGE = "Live audio and video are not available on this device yet"

/**
 * Shared body for the [MediaEngine] actuals on targets without a LiveKit SDK. Every call fails
 * with [AV_UNSUPPORTED_MESSAGE] and the flows stay at their idle values, so a screen bound to
 * this engine still renders the kind 39004 roster with joining disabled.
 */
internal class NoMediaEngine {
    val isSupported: Boolean = false
    val connectionState: StateFlow<AvConnectionState> = MutableStateFlow(AvConnectionState.Disconnected)
    val participants: StateFlow<List<AvParticipant>> = MutableStateFlow(emptyList())
    val micEnabled: StateFlow<Boolean> = MutableStateFlow(false)
    val cameraEnabled: StateFlow<Boolean> = MutableStateFlow(false)
    val audioPlaybackBlocked: StateFlow<Boolean> = MutableStateFlow(false)

    fun unsupported(): Result<Unit> = Result.Error(AppError.Unknown(AV_UNSUPPORTED_MESSAGE))
}
