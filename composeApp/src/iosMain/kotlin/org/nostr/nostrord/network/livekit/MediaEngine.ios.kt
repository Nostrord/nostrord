package org.nostr.nostrord.network.livekit

import kotlinx.coroutines.flow.StateFlow
import org.nostr.nostrord.utils.Result

/** No LiveKit SDK on this target: the roster renders, joining reports [AV_UNSUPPORTED_MESSAGE]. */
actual class MediaEngine actual constructor() {
    private val stub = NoMediaEngine()

    actual val isSupported: Boolean = stub.isSupported
    actual val connectionState: StateFlow<AvConnectionState> = stub.connectionState
    actual val participants: StateFlow<List<AvParticipant>> = stub.participants
    actual val micEnabled: StateFlow<Boolean> = stub.micEnabled
    actual val cameraEnabled: StateFlow<Boolean> = stub.cameraEnabled
    actual val audioPlaybackBlocked: StateFlow<Boolean> = stub.audioPlaybackBlocked

    actual fun startAudio() {}

    actual suspend fun connect(credentials: LiveKitCredentials): Result<Unit> = stub.unsupported()

    actual fun disconnect() {}

    actual suspend fun setMicEnabled(enabled: Boolean): Result<Unit> = stub.unsupported()

    actual suspend fun setCameraEnabled(enabled: Boolean): Result<Unit> = stub.unsupported()

    actual fun attachVideo(identity: String, surface: Any): Boolean = false

    actual fun detachVideo(identity: String, surface: Any) {}
}
