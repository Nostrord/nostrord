package org.nostr.nostrord.network.livekit

import android.Manifest
import io.livekit.android.AudioOptions
import io.livekit.android.AudioType
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import livekit.org.webrtc.SurfaceViewRenderer
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result
import java.util.Collections
import java.util.WeakHashMap

/**
 * Android AV transport, backed by the official LiveKit SDK.
 *
 * WebRTC owns capture, playback and audio routing here, so this class only translates the room
 * into the engine's flows and holds the foreground service that keeps capture alive off screen.
 * Mic and camera are asked for on first use, not on join: a listener needs neither.
 */
actual class MediaEngine actual constructor() {
    actual val isSupported: Boolean = true

    // LiveKit's room API is main-thread affine (renderer init, track add/remove).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var room: Room? = null
    private var mirrorJob: Job? = null

    /** Renderers already bound to a room's EGL context; re-initializing one throws. */
    private val initialized: MutableSet<SurfaceViewRenderer> =
        Collections.newSetFromMap(WeakHashMap())

    /** Track behind each attached renderer, so detach unsubscribes the right one. */
    private val attachedTracks = WeakHashMap<SurfaceViewRenderer, VideoTrack>()

    private val _connectionState = MutableStateFlow(AvConnectionState.Disconnected)
    actual val connectionState: StateFlow<AvConnectionState> = _connectionState.asStateFlow()

    private val _participants = MutableStateFlow<List<AvParticipant>>(emptyList())
    actual val participants: StateFlow<List<AvParticipant>> = _participants.asStateFlow()

    private val _micEnabled = MutableStateFlow(false)
    actual val micEnabled: StateFlow<Boolean> = _micEnabled.asStateFlow()

    private val _cameraEnabled = MutableStateFlow(false)
    actual val cameraEnabled: StateFlow<Boolean> = _cameraEnabled.asStateFlow()

    /** Native playback needs no permission; the browser-only autoplay block never happens here. */
    actual val audioPlaybackBlocked: StateFlow<Boolean> = MutableStateFlow(false)

    actual fun startAudio() {}

    actual suspend fun connect(credentials: LiveKitCredentials): Result<Unit> {
        if (room != null) {
            // A dropped room leaves its handle behind. Rejoining needs a fresh Room, and
            // without this the stale one reports success while nobody can hear anything.
            if (_connectionState.value != AvConnectionState.Disconnected) return Result.Success(Unit)
            disconnect()
        }
        val context = AvMediaBridge.appContext
            ?: return Result.Error(AppError.Unknown(AV_UNSUPPORTED_MESSAGE))

        _connectionState.value = AvConnectionState.Connecting
        val joining = LiveKit.create(appContext = context, overrides = AV_OVERRIDES)
        return try {
            joining.connect(url = credentials.serverUrl, token = credentials.token)
            room = joining
            mirror(joining)
            Result.Success(Unit)
        } catch (e: CancellationException) {
            joining.release()
            throw e
        } catch (e: Throwable) {
            joining.release()
            _connectionState.value = AvConnectionState.Disconnected
            Result.Error(AppError.Unknown(e.message ?: "Could not join the room"))
        }
    }

    actual fun disconnect() {
        val current = room ?: return
        room = null
        mirrorJob?.cancel()
        mirrorJob = null
        attachedTracks.keys.toList().forEach { renderer ->
            attachedTracks.remove(renderer)?.removeRenderer(renderer)
        }
        // Called from ViewModel.onCleared, where suspending is not available and leaving must
        // not be skipped: the relay would keep publishing this user in kind:39004 and the
        // microphone would stay hot.
        current.disconnect()
        current.release()
        AvMediaBridge.appContext?.let { AvCallService.stop(it) }
        _connectionState.value = AvConnectionState.Disconnected
        _participants.value = emptyList()
        _micEnabled.value = false
        _cameraEnabled.value = false
    }

    actual suspend fun setMicEnabled(enabled: Boolean): Result<Unit> {
        val current = room
            ?: return Result.Error(AppError.Unknown("Join the room before using the microphone"))
        if (enabled && !AvMediaBridge.ensurePermission(Manifest.permission.RECORD_AUDIO)) {
            return Result.Error(AppError.Unknown("Microphone access was denied"))
        }
        return try {
            current.localParticipant.setMicrophoneEnabled(enabled)
            _micEnabled.value = enabled
            updateForegroundService()
            Result.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.Error(AppError.Unknown(e.message ?: "Could not turn the microphone on"))
        }
    }

    actual suspend fun setCameraEnabled(enabled: Boolean): Result<Unit> {
        val current = room
            ?: return Result.Error(AppError.Unknown("Join the room before using the camera"))
        if (enabled && !AvMediaBridge.ensurePermission(Manifest.permission.CAMERA)) {
            return Result.Error(AppError.Unknown("Camera access was denied"))
        }
        return try {
            current.localParticipant.setCameraEnabled(enabled)
            _cameraEnabled.value = enabled
            updateForegroundService()
            Result.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.Error(AppError.Unknown(e.message ?: "Could not start the camera"))
        }
    }

    /**
     * Bind [identity]'s video track to [surface], a `SurfaceViewRenderer`.
     *
     * Returns false while that participant publishes no video; the tile shows its avatar
     * fallback and re-attaches when the roster reports a track.
     */
    actual fun attachVideo(identity: String, surface: Any): Boolean {
        val renderer = surface as? SurfaceViewRenderer ?: return false
        val current = room ?: return false
        val track = videoTrackOf(current, identity) ?: return false
        if (initialized.add(renderer)) current.initVideoRenderer(renderer)
        attachedTracks.put(renderer, track)?.removeRenderer(renderer)
        track.addRenderer(renderer)
        return true
    }

    actual fun detachVideo(identity: String, surface: Any) {
        val renderer = surface as? SurfaceViewRenderer ?: return
        attachedTracks.remove(renderer)?.removeRenderer(renderer)
    }

    private fun videoTrackOf(current: Room, identity: String): VideoTrack? {
        val participant = current.participantOf(identity) ?: return null
        return participant.videoTrackPublications.firstNotNullOfOrNull { it.second as? VideoTrack }
    }

    private fun updateForegroundService() {
        val context = AvMediaBridge.appContext ?: return
        AvCallService.update(context, mic = _micEnabled.value, camera = _cameraEnabled.value)
    }

    /**
     * Project the room onto the engine's flows, which is what the shared ViewModel reads.
     *
     * The snapshot is rebuilt on every room event rather than per observable property: joins,
     * mutes, subscriptions and speaker changes all land on the same bus, and one rebuild over a
     * handful of participants is cheaper than keeping a dozen flows in sync.
     */
    private fun mirror(joined: Room) {
        publish(joined)
        mirrorJob = scope.launch {
            joined.events.collect { publish(joined) }
        }
    }

    private fun publish(joined: Room) {
        _connectionState.value = joined.state.toAvState()
        _micEnabled.value = joined.localParticipant.isMicrophoneEnabled
        _cameraEnabled.value = joined.localParticipant.isCameraEnabled
        _participants.value = buildList {
            joined.localParticipant.toAvParticipant(isLocal = true)?.let { add(it) }
            joined.remoteParticipants.values.forEach { person ->
                person.toAvParticipant(isLocal = false)?.let { add(it) }
            }
        }
    }
}

/**
 * Play the room as media rather than as a phone call.
 *
 * The SDK default puts playback on `MODE_IN_COMMUNICATION` / `STREAM_VOICE_CALL`, which runs the
 * device's voice pipeline: its automatic gain rides the noise floor up whenever the room is quiet
 * and a listener hears a constant hiss. `MediaAudioType` takes playback off that path.
 */
private val AV_OVERRIDES = LiveKitOverrides(audioOptions = AudioOptions(audioOutputType = AudioType.MediaAudioType()))

private fun Room.participantOf(identity: String): Participant? = if (localParticipant.identity?.value == identity) {
    localParticipant
} else {
    remoteParticipants.values.firstOrNull { it.identity?.value == identity }
}

private fun Room.State.toAvState(): AvConnectionState = when (this) {
    Room.State.DISCONNECTED -> AvConnectionState.Disconnected
    Room.State.CONNECTING -> AvConnectionState.Connecting
    Room.State.CONNECTED -> AvConnectionState.Connected
    Room.State.RECONNECTING -> AvConnectionState.Reconnecting
}

/** Null before the server assigns an identity, which is the only key tracks are on. */
private fun Participant.toAvParticipant(isLocal: Boolean): AvParticipant? {
    val id = identity?.value ?: return null
    return AvParticipant(
        identity = id,
        pubkey = pubkeyFromLiveKitIdentity(id),
        isLocal = isLocal,
        isSpeaking = isSpeaking,
        micEnabled = isMicrophoneEnabled,
        cameraEnabled = isCameraEnabled,
    )
}
