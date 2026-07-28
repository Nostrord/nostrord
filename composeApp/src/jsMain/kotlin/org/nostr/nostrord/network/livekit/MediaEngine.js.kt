package org.nostr.nostrord.network.livekit

import kotlinx.coroutines.flow.StateFlow
import org.nostr.nostrord.utils.Result

// TODO(phase-2): back this with the livekit-client npm SDK.
actual class MediaEngine actual constructor() {
    private val stub = NoMediaEngine()

    actual val isSupported: Boolean = stub.isSupported
    actual val connectionState: StateFlow<AvConnectionState> = stub.connectionState
    actual val participants: StateFlow<List<AvParticipant>> = stub.participants
    actual val micEnabled: StateFlow<Boolean> = stub.micEnabled
    actual val cameraEnabled: StateFlow<Boolean> = stub.cameraEnabled

    actual suspend fun connect(credentials: LiveKitCredentials): Result<Unit> = stub.unsupported()

    actual fun disconnect() {}

    actual suspend fun setMicEnabled(enabled: Boolean): Result<Unit> = stub.unsupported()

    actual suspend fun setCameraEnabled(enabled: Boolean): Result<Unit> = stub.unsupported()
}
