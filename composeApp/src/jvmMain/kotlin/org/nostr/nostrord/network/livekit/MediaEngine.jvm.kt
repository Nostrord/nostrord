package org.nostr.nostrord.network.livekit

import io.github.nostrord.livekit.LiveKitRoom
import io.github.nostrord.livekit.Participant
import io.github.nostrord.livekit.PlatformAudio
import io.github.nostrord.livekit.RoomState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.networkClientDispatcher

/**
 * Desktop AV transport, backed by livekit-kmp over WebRTC.
 *
 * Audio belongs to WebRTC's device module, so no PCM is pumped from here and echo cancellation
 * runs against the real device loop. Video is receive-only: the JDK has no camera API, so this
 * build publishes nothing.
 */
actual class MediaEngine actual constructor() {
    actual val isSupported: Boolean = true

    private val scope = CoroutineScope(SupervisorJob() + networkClientDispatcher)

    /**
     * Opened lazily and held for the engine's life. WebRTC's device module renders every
     * subscribed remote track and only runs while a handle is held, so dropping this would go
     * silent. Null on a machine with no sound devices, which is a state, not a failure.
     */
    private val audio: PlatformAudio? by lazy {
        runCatching { PlatformAudio.open() }.getOrNull()
    }

    private var room: LiveKitRoom? = null
    private var mirrorJob: Job? = null

    /** Frame-forwarding jobs keyed by the sink they feed, cancelled on detach. */
    private val videoJobs = mutableMapOf<VideoFrameSink, Job>()

    private val _connectionState = MutableStateFlow(AvConnectionState.Disconnected)
    actual val connectionState: StateFlow<AvConnectionState> = _connectionState.asStateFlow()

    private val _participants = MutableStateFlow<List<AvParticipant>>(emptyList())
    actual val participants: StateFlow<List<AvParticipant>> = _participants.asStateFlow()

    private val _micEnabled = MutableStateFlow(false)
    actual val micEnabled: StateFlow<Boolean> = _micEnabled.asStateFlow()

    private val _cameraEnabled = MutableStateFlow(false)
    actual val cameraEnabled: StateFlow<Boolean> = _cameraEnabled.asStateFlow()

    actual suspend fun connect(credentials: LiveKitCredentials): Result<Unit> {
        if (room != null) return Result.Success(Unit)
        val devices = audio
            ?: return Result.Error(AppError.Unknown("No audio device is available on this computer"))

        _connectionState.value = AvConnectionState.Connecting
        val joining = LiveKitRoom(scope, devices)
        return try {
            joining.connect(url = credentials.serverUrl, token = credentials.token)
            room = joining
            mirror(joining)
            Result.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _connectionState.value = AvConnectionState.Disconnected
            Result.Error(AppError.Unknown(e.message ?: "Could not join the room"))
        }
    }

    actual fun disconnect() {
        val current = room ?: return
        room = null
        mirrorJob?.cancel()
        mirrorJob = null
        // Called from ViewModel.onCleared, where suspending is not available and leaving must
        // not be skipped: the relay would keep publishing this user in kind:39004 and the
        // microphone would stay hot.
        videoJobs.values.forEach { it.cancel() }
        videoJobs.clear()
        runCatching { runBlocking { current.disconnect() } }
        _connectionState.value = AvConnectionState.Disconnected
        _participants.value = emptyList()
        _micEnabled.value = false
        _cameraEnabled.value = false
    }

    actual suspend fun setMicEnabled(enabled: Boolean): Result<Unit> {
        val current = room ?: return Result.Error(AppError.Unknown("Join the room before using the microphone"))
        return try {
            current.setMicrophoneEnabled(enabled)
            Result.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.Error(AppError.Unknown(e.message ?: "Microphone access was denied"))
        }
    }

    /** No camera on this platform: the JDK has no capture API, so nothing can be published. */
    actual suspend fun setCameraEnabled(enabled: Boolean): Result<Unit> = Result.Error(AppError.Unknown("Camera sharing is not available on desktop yet"))

    /**
     * Start forwarding [identity]'s video frames into [surface], a [VideoFrameSink].
     *
     * Returns false while that participant publishes no video; the tile shows its avatar
     * fallback and re-attaches when the roster reports a track.
     */
    actual fun attachVideo(identity: String, surface: Any): Boolean {
        val sink = surface as? VideoFrameSink ?: return false
        val frames = room?.videoFrames(identity) ?: return false
        videoJobs.remove(sink)?.cancel()
        videoJobs[sink] = scope.launch {
            frames.collect { frame ->
                sink.push(VideoFrameSink.RgbaFrame(frame.width, frame.height, frame.rgba))
            }
        }
        return true
    }

    actual fun detachVideo(identity: String, surface: Any) {
        (surface as? VideoFrameSink)?.let { videoJobs.remove(it)?.cancel() }
    }

    /** Project the room's flows onto the engine's, which is what the shared ViewModel reads. */
    private fun mirror(joined: LiveKitRoom) {
        mirrorJob = scope.launch {
            launch { joined.state.collect { _connectionState.value = it.toAvState() } }
            launch { joined.microphoneEnabled.collect { _micEnabled.value = it } }
            launch {
                joined.participants.collect { people ->
                    _participants.value = people.map { it.toAvParticipant() }
                }
            }
        }
    }
}

private fun RoomState.toAvState(): AvConnectionState = when (this) {
    RoomState.Disconnected -> AvConnectionState.Disconnected
    RoomState.Connecting -> AvConnectionState.Connecting
    RoomState.Connected -> AvConnectionState.Connected
    RoomState.Reconnecting -> AvConnectionState.Reconnecting
}

private fun Participant.toAvParticipant() = AvParticipant(
    identity = identity,
    pubkey = pubkeyFromLiveKitIdentity(identity),
    isLocal = isLocal,
    isSpeaking = isSpeaking,
    // An open microphone is "publishing and not muted": a subscribed but muted track is
    // someone sending silence, which reads the same as off.
    micEnabled = audioSubscribed && !audioMuted,
    cameraEnabled = videoSubscribed,
)
