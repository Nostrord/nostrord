package org.nostr.nostrord.network.livekit

import kotlinx.browser.document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result
import org.w3c.dom.Node

/**
 * `RoomEvent` string values from the livekit-client SDK. Declared here rather than as an
 * external enum: the TypeScript enum compiles to a plain object, and the string values are
 * public API, so a literal is both simpler and stable across patch releases.
 */
private object RoomEvent {
    const val CONNECTED = "connected"
    const val DISCONNECTED = "disconnected"
    const val RECONNECTING = "reconnecting"
    const val RECONNECTED = "reconnected"
    const val PARTICIPANT_CONNECTED = "participantConnected"
    const val PARTICIPANT_DISCONNECTED = "participantDisconnected"
    const val ACTIVE_SPEAKERS_CHANGED = "activeSpeakersChanged"
    const val TRACK_SUBSCRIBED = "trackSubscribed"
    const val TRACK_UNSUBSCRIBED = "trackUnsubscribed"
    const val TRACK_MUTED = "trackMuted"
    const val TRACK_UNMUTED = "trackUnmuted"
    const val LOCAL_TRACK_PUBLISHED = "localTrackPublished"
    const val LOCAL_TRACK_UNPUBLISHED = "localTrackUnpublished"
}

/** Web AV transport, backed by the livekit-client SDK over WebRTC. */
actual class MediaEngine actual constructor() {
    actual val isSupported: Boolean = true

    private val _connectionState = MutableStateFlow(AvConnectionState.Disconnected)
    actual val connectionState: StateFlow<AvConnectionState> = _connectionState.asStateFlow()

    private val _participants = MutableStateFlow<List<AvParticipant>>(emptyList())
    actual val participants: StateFlow<List<AvParticipant>> = _participants.asStateFlow()

    private val _micEnabled = MutableStateFlow(false)
    actual val micEnabled: StateFlow<Boolean> = _micEnabled.asStateFlow()

    private val _cameraEnabled = MutableStateFlow(false)
    actual val cameraEnabled: StateFlow<Boolean> = _cameraEnabled.asStateFlow()

    private var room: Room? = null

    /** `<audio>` elements created for subscribed remote audio, removed on unsubscribe/leave. */
    private val audioSinks = mutableListOf<dynamic>()

    actual suspend fun connect(credentials: LiveKitCredentials): Result<Unit> {
        if (room != null) {
            // A dropped room leaves its handle behind. Rejoining needs a fresh Room, and
            // without this the stale one reports success while nobody can hear anything.
            if (_connectionState.value != AvConnectionState.Disconnected) return Result.Success(Unit)
            disconnect()
        }
        _connectionState.value = AvConnectionState.Connecting
        val newRoom = Room()
        return try {
            wire(newRoom)
            newRoom.connect(credentials.serverUrl, credentials.token).await()
            room = newRoom
            _connectionState.value = AvConnectionState.Connected
            refresh(newRoom)
            Result.Success(Unit)
        } catch (e: CancellationException) {
            teardown(newRoom)
            throw e
        } catch (e: Throwable) {
            // A rejected JS promise surfaces here as a Throwable, not an Exception.
            teardown(newRoom)
            Result.Error(AppError.Unknown(errorMessage(e, "Could not join the room")))
        }
    }

    actual fun disconnect() {
        val current = room ?: return
        teardown(current)
    }

    actual suspend fun setMicEnabled(enabled: Boolean): Result<Unit> {
        val current = room ?: return Result.Error(AppError.Unknown("Join the room before using the microphone"))
        return try {
            current.localParticipant.setMicrophoneEnabled(enabled).await()
            _micEnabled.value = enabled
            refresh(current)
            Result.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.Error(AppError.Unknown(errorMessage(e, "Microphone access was denied")))
        }
    }

    actual suspend fun setCameraEnabled(enabled: Boolean): Result<Unit> {
        val current = room ?: return Result.Error(AppError.Unknown("Join the room before using the camera"))
        return try {
            current.localParticipant.setCameraEnabled(enabled).await()
            _cameraEnabled.value = enabled
            refresh(current)
            Result.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.Error(AppError.Unknown(errorMessage(e, "Camera access was denied")))
        }
    }

    actual fun attachVideo(identity: String, surface: Any): Boolean {
        val participant = participantByIdentity(identity) ?: return false
        var attached = false
        participant.videoTrackPublications.forEach { publication: dynamic ->
            val track = publication?.videoTrack
            if (track != null && !attached) {
                track.attach(surface)
                attached = true
            }
        }
        return attached
    }

    actual fun detachVideo(identity: String, surface: Any) {
        val participant = participantByIdentity(identity) ?: return
        participant.videoTrackPublications.forEach { publication: dynamic ->
            publication?.videoTrack?.detach(surface)
        }
    }

    private fun participantByIdentity(identity: String): dynamic {
        val current = room ?: return null
        if (current.localParticipant.identity == identity) return current.localParticipant
        var found: dynamic = null
        current.remoteParticipants.forEach { participant: dynamic ->
            if (found == null && participant?.identity == identity) found = participant
        }
        return found
    }

    /** Any event that can change who is visible or audible re-derives the whole snapshot. */
    private fun wire(target: Room) {
        val onChange: (dynamic) -> Unit = { refresh(target) }
        listOf(
            RoomEvent.PARTICIPANT_CONNECTED,
            RoomEvent.PARTICIPANT_DISCONNECTED,
            RoomEvent.ACTIVE_SPEAKERS_CHANGED,
            RoomEvent.TRACK_MUTED,
            RoomEvent.TRACK_UNMUTED,
            RoomEvent.LOCAL_TRACK_PUBLISHED,
            RoomEvent.LOCAL_TRACK_UNPUBLISHED,
        ).forEach { target.on(it, onChange) }

        // Remote audio is only audible once its track is attached to an <audio> element:
        // livekit-client does not play subscribed audio on its own. Video needs no element
        // here because the tiles attach it themselves via attachVideo.
        target.on(RoomEvent.TRACK_SUBSCRIBED) { track: dynamic ->
            if (track?.kind == "audio") {
                val element = track.attach()
                document.body?.appendChild(element.unsafeCast<Node>())
                audioSinks.add(element)
            }
            refresh(target)
        }
        target.on(RoomEvent.TRACK_UNSUBSCRIBED) { track: dynamic ->
            if (track?.kind == "audio") {
                val detached = track.detach().unsafeCast<Array<dynamic>>()
                detached.forEach { el ->
                    audioSinks.remove(el)
                    el?.remove()
                }
            }
            refresh(target)
        }

        target.on(RoomEvent.CONNECTED) {
            _connectionState.value = AvConnectionState.Connected
            refresh(target)
        }
        target.on(RoomEvent.RECONNECTING) { _connectionState.value = AvConnectionState.Reconnecting }
        target.on(RoomEvent.RECONNECTED) {
            _connectionState.value = AvConnectionState.Connected
            refresh(target)
        }
        // Server-initiated close: the room object is dead, so drop it like a local leave.
        target.on(RoomEvent.DISCONNECTED) { teardown(target) }
    }

    private fun refresh(target: Room) {
        val snapshot = mutableListOf<AvParticipant>()
        snapshot += toAvParticipant(target.localParticipant, isLocal = true)
        target.remoteParticipants.forEach { participant: dynamic ->
            if (participant != null) snapshot += toAvParticipant(participant, isLocal = false)
        }
        _participants.value = snapshot
        _micEnabled.value = target.localParticipant.isMicrophoneEnabled
        _cameraEnabled.value = target.localParticipant.isCameraEnabled
    }

    private fun toAvParticipant(participant: dynamic, isLocal: Boolean): AvParticipant {
        val identity = participant.identity as String
        return AvParticipant(
            identity = identity,
            pubkey = pubkeyFromLiveKitIdentity(identity),
            isLocal = isLocal,
            isSpeaking = participant.isSpeaking == true,
            micEnabled = participant.isMicrophoneEnabled == true,
            cameraEnabled = participant.isCameraEnabled == true,
        )
    }

    private fun teardown(target: Room) {
        try {
            target.removeAllListeners()
            target.disconnect()
        } catch (e: Throwable) {
            // Already gone; the state reset below is what matters.
        }
        audioSinks.forEach { el -> runCatching { el?.remove() } }
        audioSinks.clear()
        if (room === target) room = null
        _connectionState.value = AvConnectionState.Disconnected
        _participants.value = emptyList()
        _micEnabled.value = false
        _cameraEnabled.value = false
    }
}

/** JS rejections arrive as opaque Throwables; dig out a readable message when there is one. */
private fun errorMessage(error: Throwable, fallback: String): String {
    val message = error.message?.takeIf { it.isNotBlank() } ?: return fallback
    return message
}
