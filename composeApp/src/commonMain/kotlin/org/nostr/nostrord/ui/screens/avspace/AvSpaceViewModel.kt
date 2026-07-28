package org.nostr.nostrord.ui.screens.avspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
)

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

    val connectionState: StateFlow<AvConnectionState> = engine.connectionState
    val micEnabled: StateFlow<Boolean> = engine.micEnabled
    val cameraEnabled: StateFlow<Boolean> = engine.cameraEnabled

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
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
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
        if (connectionState.value != AvConnectionState.Disconnected) return
        _error.value = null
        viewModelScope.launch {
            when (val credentials = repo.fetchLiveKitCredentials(groupId)) {
                is Result.Success -> {
                    val connected = engine.connect(credentials.data)
                    if (connected is Result.Error) _error.value = connected.error.message
                }

                is Result.Error -> _error.value = credentials.error.message
            }
        }
    }

    fun leave() {
        engine.disconnect()
    }

    fun toggleMic() {
        _error.value = null
        viewModelScope.launch {
            val result = engine.setMicEnabled(!micEnabled.value)
            if (result is Result.Error) _error.value = result.error.message
        }
    }

    fun toggleCamera() {
        _error.value = null
        viewModelScope.launch {
            val result = engine.setCameraEnabled(!cameraEnabled.value)
            if (result is Result.Error) _error.value = result.error.message
        }
    }

    fun dismissError() {
        _error.value = null
    }

    override fun onCleared() {
        // The room must be left when the screen goes away, otherwise the relay keeps
        // publishing this user in kind 39004 and the mic stays hot.
        engine.disconnect()
        super.onCleared()
    }
}
