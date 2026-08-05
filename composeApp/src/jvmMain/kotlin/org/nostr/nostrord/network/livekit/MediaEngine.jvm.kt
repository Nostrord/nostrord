package org.nostr.nostrord.network.livekit

import io.github.nostrord.livekit.CameraCapture
import io.github.nostrord.livekit.LiveKitRoom
import io.github.nostrord.livekit.Participant
import io.github.nostrord.livekit.PlatformAudio
import io.github.nostrord.livekit.RoomState
import io.github.nostrord.livekit.VideoPublication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.networkClientDispatcher

/**
 * Desktop AV transport, backed by livekit-kmp over WebRTC.
 *
 * Audio belongs to WebRTC's device module, so no PCM is pumped from here and echo cancellation
 * runs against the real device loop. The camera rides an ffmpeg subprocess, the one capture
 * stack a JVM desktop has; without ffmpeg installed the camera reports unavailable and
 * everything else keeps working.
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

    private var cameraPublication: VideoPublication? = null
    private var cameraCapture: CameraCapture? = null

    /** Sinks showing the local camera preview; fed straight from the capture pump. */
    private val localPreviewSinks = mutableSetOf<VideoFrameSink>()

    private val _connectionState = MutableStateFlow(AvConnectionState.Disconnected)
    actual val connectionState: StateFlow<AvConnectionState> = _connectionState.asStateFlow()

    private val _participants = MutableStateFlow<List<AvParticipant>>(emptyList())
    actual val participants: StateFlow<List<AvParticipant>> = _participants.asStateFlow()

    private val _micEnabled = MutableStateFlow(false)
    actual val micEnabled: StateFlow<Boolean> = _micEnabled.asStateFlow()

    private val _cameraEnabled = MutableStateFlow(false)
    actual val cameraEnabled: StateFlow<Boolean> = _cameraEnabled.asStateFlow()

    /** The ADM plays without permission; the browser-only autoplay block never happens here. */
    actual val audioPlaybackBlocked: StateFlow<Boolean> = MutableStateFlow(false)

    actual fun startAudio() {}

    actual suspend fun connect(credentials: LiveKitCredentials): Result<Unit> {
        if (room != null) {
            // A dropped room leaves its handle behind. Rejoining needs a fresh LiveKitRoom, and
            // without this the stale one reports success while nobody can hear anything.
            if (_connectionState.value != AvConnectionState.Disconnected) return Result.Success(Unit)
            disconnect()
        }
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
        stopCamera()
        cameraPublication = null
        synchronized(localPreviewSinks) { localPreviewSinks.clear() }
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
    actual suspend fun setCameraEnabled(enabled: Boolean): Result<Unit> {
        val current = room ?: return Result.Error(AppError.Unknown("Join the room before using the camera"))
        if (!enabled) {
            stopCamera()
            cameraPublication?.setMuted(true)
            _cameraEnabled.value = false
            return Result.Success(Unit)
        }
        val capture = CameraCapture.open(CAMERA_WIDTH, CAMERA_HEIGHT)
            ?: return Result.Error(
                AppError.Unknown(
                    if (CameraCapture.ffmpegAvailable) {
                        "No camera was found on this computer"
                    } else {
                        "Camera sharing needs ffmpeg installed"
                    },
                ),
            )
        return try {
            val publication = cameraPublication
                ?: current.publishVideo(CAMERA_WIDTH, CAMERA_HEIGHT).also { cameraPublication = it }
            publication.setMuted(false)
            capture.start { rgba, timestampUs ->
                publication.source.capture(CAMERA_WIDTH, CAMERA_HEIGHT, rgba, timestampUs)
                if (localPreviewSinks.isNotEmpty()) {
                    // The pump reuses its buffer, so the preview needs its own copy.
                    val frame = VideoFrameSink.RgbaFrame(CAMERA_WIDTH, CAMERA_HEIGHT, rgba.copyOf())
                    synchronized(localPreviewSinks) { localPreviewSinks.forEach { it.push(frame) } }
                }
            }
            cameraCapture = capture
            _cameraEnabled.value = true
            Result.Success(Unit)
        } catch (e: CancellationException) {
            capture.stop()
            throw e
        } catch (e: Throwable) {
            capture.stop()
            Result.Error(AppError.Unknown(e.message ?: "Could not start the camera"))
        }
    }

    private fun stopCamera() {
        cameraCapture?.stop()
        cameraCapture = null
    }

    /**
     * Start forwarding [identity]'s video frames into [surface], a [VideoFrameSink].
     *
     * Returns false while that participant publishes no video; the tile shows its avatar
     * fallback and re-attaches when the roster reports a track.
     */
    actual fun attachVideo(identity: String, surface: Any): Boolean {
        val sink = surface as? VideoFrameSink ?: return false
        // The local participant's own tile: fed from the capture pump, not from a stream —
        // LiveKit does not loop your published track back to you.
        if (identity == localIdentity()) {
            if (!_cameraEnabled.value) return false
            synchronized(localPreviewSinks) { localPreviewSinks.add(sink) }
            return true
        }
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
        val sink = surface as? VideoFrameSink ?: return
        synchronized(localPreviewSinks) { localPreviewSinks.remove(sink) }
        videoJobs.remove(sink)?.cancel()
    }

    private fun localIdentity(): String? = room?.participants?.value?.firstOrNull { it.isLocal }?.identity

    /** Project the room's flows onto the engine's, which is what the shared ViewModel reads. */
    private fun mirror(joined: LiveKitRoom) {
        mirrorJob = scope.launch {
            launch { joined.state.collect { _connectionState.value = it.toAvState() } }
            launch { joined.microphoneEnabled.collect { _micEnabled.value = it } }
            launch {
                // Subscription events only describe remote tracks; the local camera and mic
                // are this engine's own state, combined in so toggling them re-emits the
                // roster (the room's participant list alone never changes on a local toggle,
                // which would leave the local tile stuck on its join-time snapshot).
                combine(joined.participants, _cameraEnabled, _micEnabled) { people, camera, mic ->
                    people.map { person ->
                        val mapped = person.toAvParticipant()
                        if (person.isLocal) mapped.copy(cameraEnabled = camera, micEnabled = mic) else mapped
                    }
                }.collect { _participants.value = it }
            }
        }
    }
}

private const val CAMERA_WIDTH = 960
private const val CAMERA_HEIGHT = 540

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
