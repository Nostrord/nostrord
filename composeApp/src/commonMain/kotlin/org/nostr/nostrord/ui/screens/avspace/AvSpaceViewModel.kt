package org.nostr.nostrord.ui.screens.avspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.NostrRepositoryApi
import org.nostr.nostrord.network.livekit.AvConnectionState
import org.nostr.nostrord.network.livekit.MediaEngine
import org.nostr.nostrord.utils.Result

/**
 * One person shown in the AV space, merging the relay's roster with what the media engine sees.
 *
 * [inRoom] comes from kind 39004 (authoritative for "who is live"); [isSpeaking] and the track
 * flags only exist for participants the local engine is actually connected to.
 */
data class AvSpaceParticipant(
    val pubkey: String,
    val isSelf: Boolean = false,
    val inRoom: Boolean = true,
    val isSpeaking: Boolean = false,
    val micEnabled: Boolean = false,
    val cameraEnabled: Boolean = false,
    /**
     * LiveKit identity, present only for participants the local engine is connected to. It is
     * what the media layer keys tracks on: the pubkey alone cannot address one person's two
     * sessions, which the spec's random JWT suffix explicitly allows.
     */
    val identity: String? = null,
) {
    /** Sending something, which is what puts a person on stage rather than in the audience. */
    val isPublishing: Boolean get() = micEnabled || cameraEnabled
}

/**
 * Stable seating: the local user first, then everyone by pubkey.
 *
 * The roster is assembled from a relay list unioned with a map of live participants, so its
 * natural order shifts on every update and the tiles shuffle under the cursor. Sorting on
 * anything live (speaking, mute state, display name as it arrives) would reintroduce the same
 * churn, so the key is the one thing about a person that never changes.
 */
private val rosterOrder = compareByDescending<AvSpaceParticipant> { it.isSelf }.thenBy { it.pubkey }

/**
 * Shared logic for the NIP-29 AV space of one group. Both the web room and the Compose surface
 * consume this, so the roster, the join handshake and the track toggles live in one place.
 *
 * The roster renders on every platform. Joining requires a [MediaEngine] that can capture and
 * play media, which today is the web only; elsewhere [canJoin] is false and the UI says so.
 */
class AvSpaceViewModel(
    private val repo: NostrRepositoryApi,
    private val groupId: String,
    private val selfPubkey: String?,
    private val engine: MediaEngine = MediaEngine(),
) : ViewModel() {
    /** Whether this build can capture and play media at all. */
    val canJoin: Boolean = engine.isSupported

    private val _rejoining = MutableStateFlow(false)

    /**
     * Set the instant Join is pressed, well before the engine knows anything.
     *
     * Joining starts with minting a relay token, which signs a NIP-98 header: over a bunker that
     * waits on a human tapping approve, and it can run for seconds. The engine is still
     * Disconnected throughout, so without this the button keeps offering to join something the
     * user already asked for.
     */
    private val _joining = MutableStateFlow(false)

    /**
     * Connection as the user should read it: minting a token reads as [Connecting] and an
     * automatic rejoin as [Reconnecting] rather than as having left, so the room never flashes a
     * Join button at someone who is on their way in.
     */
    val connectionState: StateFlow<AvConnectionState> =
        combine(engine.connectionState, _rejoining, _joining) { state, rejoining, joining ->
            when {
                state != AvConnectionState.Disconnected -> state
                rejoining -> AvConnectionState.Reconnecting
                joining -> AvConnectionState.Connecting
                else -> state
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, engine.connectionState.value)
    val micEnabled: StateFlow<Boolean> = engine.micEnabled
    val cameraEnabled: StateFlow<Boolean> = engine.cameraEnabled

    /** Browser autoplay block; the web UI shows an enable-audio tap while true. */
    val audioPlaybackBlocked: StateFlow<Boolean> = engine.audioPlaybackBlocked

    fun startAudio() = engine.startAudio()

    private val _error = MutableStateFlow<String?>(null)

    /** Last join or capture failure, for a dismissible banner. Cleared on the next attempt. */
    val error: StateFlow<String?> = _error.asStateFlow()

    val userMetadata = repo.userMetadata

    /** Group metadata drives the `livekit` flag and the AV-only decision for the composer. */
    private fun metadataOf(groups: List<GroupMetadata>) = groups.firstOrNull { it.id == groupId }

    val hasSpace: StateFlow<Boolean> = repo.groups
        .map { metadataOf(it)?.hasLiveKit == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, metadataOf(repo.groups.value)?.hasLiveKit == true)

    /** True when the group declares an empty `supported_kinds`: no text chat, AV only. */
    val isAvOnly: StateFlow<Boolean> = repo.groups
        .map { metadataOf(it)?.isAvOnly == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, metadataOf(repo.groups.value)?.isAvOnly == true)

    /**
     * Room roster. Kind 39004 decides who is present; the engine adds live speaking and track
     * state for the participants it can see, and the local user appears as soon as they connect
     * even if the relay has not republished 39004 yet.
     */
    val participants: StateFlow<List<AvSpaceParticipant>> =
        combine(repo.liveKitParticipants, engine.participants) { roster, live ->
            val liveByPubkey = live.mapNotNull { p -> p.pubkey?.let { it to p } }.toMap()
            val pubkeys = (roster[groupId].orEmpty() + liveByPubkey.keys).distinct()
            pubkeys.map { pubkey ->
                val engineView = liveByPubkey[pubkey]
                AvSpaceParticipant(
                    pubkey = pubkey,
                    isSelf = pubkey == selfPubkey,
                    inRoom = pubkey in roster[groupId].orEmpty() || engineView != null,
                    isSpeaking = engineView?.isSpeaking == true,
                    micEnabled = engineView?.micEnabled == true,
                    cameraEnabled = engineView?.cameraEnabled == true,
                    identity = engineView?.identity,
                )
            }.sortedWith(rosterOrder)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Who is on stage: whoever is publishing audio or video.
     *
     * Speaking is deliberately not part of it. Active-speaker updates flip in well under a
     * second, so folding them in here moves a person between sections on every syllable and the
     * room reflows while they talk. Speaking lights the ring and nothing else.
     */
    val onStage: StateFlow<List<AvSpaceParticipant>> = participants
        .map { people -> people.filter { it.isPublishing } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val listeners: StateFlow<List<AvSpaceParticipant>> = participants
        .map { people -> people.filterNot { it.isPublishing } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Whether to lay the room out as video tiles rather than as avatars. */
    val hasVideo: StateFlow<Boolean> = participants
        .map { people -> people.any { it.cameraEnabled } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * What the user asked for, which is not what the engine currently has: a drop must restore
     * the room and the tracks that were live when it happened, and a deliberate Leave must not
     * be undone by the rejoin.
     */
    private var wantsRoom = false
    private var wantsMic = false
    private var wantsCamera = false
    private var rejoinJob: Job? = null
    private var joinJob: Job? = null

    init {
        viewModelScope.launch {
            engine.connectionState.collect { state ->
                if (state == AvConnectionState.Disconnected && wantsRoom) rejoin()
            }
        }
        viewModelScope.launch { repo.requestLiveKitParticipants(groupId) }
        viewModelScope.launch {
            repo.liveKitParticipants.collect { roster ->
                val pubkeys = roster[groupId].orEmpty().toSet()
                if (pubkeys.isNotEmpty()) repo.requestUserMetadata(pubkeys)
            }
        }
    }

    /** Mint a token from the relay and connect. No-op while already connected or connecting. */
    fun join() {
        // Guarded on the flag, not on connectionState: that one is a stateIn, so its value
        // trails by a dispatch and two quick taps would both get through.
        if (_joining.value || engine.connectionState.value != AvConnectionState.Disconnected) return
        _error.value = null
        // Set here rather than inside the coroutine: the press and the repaint are the same
        // frame, and a state change one dispatch later is the delay this exists to remove.
        _joining.value = true
        joinJob = viewModelScope.launch {
            try {
                when (val credentials = repo.fetchLiveKitCredentials(groupId)) {
                    is Result.Success -> {
                        when (val connected = engine.connect(credentials.data)) {
                            is Result.Success -> wantsRoom = true
                            is Result.Error -> _error.value = connected.error.message
                        }
                    }

                    is Result.Error -> _error.value = credentials.error.message
                }
            } finally {
                _joining.value = false
            }
        }
    }

    fun leave() {
        wantsRoom = false
        wantsMic = false
        wantsCamera = false
        rejoinJob?.cancel()
        // A join still minting its token counts as leaving: the pending connect must not land
        // after the user backed out.
        joinJob?.cancel()
        _joining.value = false
        _rejoining.value = false
        engine.disconnect()
    }

    fun toggleMic() {
        _error.value = null
        val wanted = !micEnabled.value
        viewModelScope.launch {
            when (val result = engine.setMicEnabled(wanted)) {
                is Result.Success -> wantsMic = wanted
                is Result.Error -> _error.value = result.error.message
            }
        }
    }

    fun toggleCamera() {
        _error.value = null
        val wanted = !cameraEnabled.value
        viewModelScope.launch {
            when (val result = engine.setCameraEnabled(wanted)) {
                is Result.Success -> wantsCamera = wanted
                is Result.Error -> _error.value = result.error.message
            }
        }
    }

    /**
     * Come back after a drop nobody asked for.
     *
     * Each attempt mints a fresh token: the relay's is short-lived and single-join, so replaying
     * the old one fails where a new one succeeds. Tracks that were live are restored, so a
     * reconnect the user did not cause does not silently mute them either.
     */
    private fun rejoin() {
        if (rejoinJob?.isActive == true) return
        _rejoining.value = true
        rejoinJob = viewModelScope.launch {
            repeat(AvReconnect.MAX_ATTEMPTS) { attempt ->
                delay(AvReconnect.delayMs(attempt))
                if (!wantsRoom) return@launch
                val credentials = repo.fetchLiveKitCredentials(groupId)
                if (credentials is Result.Success && engine.connect(credentials.data) is Result.Success) {
                    _rejoining.value = false
                    if (wantsMic) engine.setMicEnabled(true)
                    if (wantsCamera) engine.setCameraEnabled(true)
                    return@launch
                }
            }
            wantsRoom = false
            _rejoining.value = false
            _error.value = "The connection to the room dropped. Join again to come back."
        }
    }

    fun dismissError() {
        _error.value = null
    }

    /**
     * Route a render surface to [identity]'s video track. The surface type is per platform
     * (an HTMLVideoElement on the web, a [org.nostr.nostrord.network.livekit.VideoFrameSink]
     * in Compose); the engine dispatches on it.
     */
    fun attachVideo(identity: String, surface: Any): Boolean = engine.attachVideo(identity, surface)

    fun detachVideo(identity: String, surface: Any) = engine.detachVideo(identity, surface)

    override fun onCleared() {
        wantsRoom = false
        // The room must be left when the screen goes away, otherwise the relay keeps
        // publishing this user in kind 39004 and the mic stays hot.
        engine.disconnect()
        super.onCleared()
    }
}
