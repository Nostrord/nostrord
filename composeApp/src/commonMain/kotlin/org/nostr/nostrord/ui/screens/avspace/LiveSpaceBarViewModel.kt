package org.nostr.nostrord.ui.screens.avspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepositoryApi
import org.nostr.nostrord.network.livekit.AvConnectionState

/**
 * The in-chat banner for a group's AV space.
 *
 * Deliberately owns no [AvSpaceViewModel]: the banner renders for every group carrying the
 * `livekit` tag, and a session per banner would hang up the live call the moment the user
 * glanced at another group. It reads the relay's kind 39004 roster, and upgrades to the live
 * view only for the group [AvSpaceHost] actually has open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSpaceBarViewModel(
    private val repo: NostrRepositoryApi,
    private val groupId: String,
    private val selfPubkey: String?,
    private val host: AvSpaceHost,
) : ViewModel() {
    /** The group advertises a room. Shown even when empty: the relay creates it on first join. */
    val hasSpace: StateFlow<Boolean> = repo.groups
        .map { groups -> groups.firstOrNull { it.id == groupId }?.hasLiveKit == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        // This is usually the first thing to want kind 39004, and often the only thing: the
        // roster otherwise arrives only once someone opens the room, so a fresh load reads
        // "Nobody here yet" over a room that is busy. Gated on the group actually advertising a
        // room (and awaited, since metadata can land after the surface mounts) so the many
        // banners/rows without AV cost no REQ.
        viewModelScope.launch {
            hasSpace.first { it }
            repo.requestLiveKitParticipants(groupId)
        }
    }

    /**
     * Who is in the room.
     *
     * Kind 39004 is the truth about the room; the engine only ever adds to it, and only while
     * this client is connected, where it sees arrivals before the relay republishes. Gated on
     * being connected rather than on a session existing: a session outlives Leave (so the room
     * can be reopened), and reading a hung-up engine would report the room as this client last
     * saw it instead of as it is.
     */
    val participants: StateFlow<List<AvSpaceParticipant>> = host.session
        .flatMapLatest { live ->
            if (live?.groupId != groupId) {
                roster()
            } else {
                live.viewModel.connectionState.flatMapLatest { state ->
                    if (state == AvConnectionState.Disconnected) roster() else live.viewModel.participants
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun roster() = repo.liveKitParticipants.map { rooms ->
        rooms[groupId].orEmpty().map { AvSpaceParticipant(pubkey = it, isSelf = it == selfPubkey) }
    }

    /** The group has no text chat, so the room is the only thing its chat pane can offer. */
    private val isAvOnly: StateFlow<Boolean> = repo.groups
        .map { groups -> groups.firstOrNull { it.id == groupId }?.isAvOnly == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** This client is in THIS group's room, so the banner reads as a live call rather than an invite. */
    val joined: StateFlow<Boolean> = host.session
        .flatMapLatest { live ->
            if (live?.groupId == groupId) {
                live.viewModel.connectionState.map { it != AvConnectionState.Disconnected }
            } else {
                flowOf(false)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Whether the in-chat banner is worth a row of the chat pane.
     *
     * An empty room is noise to everyone standing outside it, so the banner shows a room with
     * someone in it, or the one this client is in. Being the first one in is still reachable:
     * the sidebar's Voice room row renders for the whole group. AV-only groups keep the banner
     * either way, since their chat pane has nothing else in it.
     */
    val visible: StateFlow<Boolean> = combine(
        hasSpace,
        participants,
        joined,
        isAvOnly,
    ) { hasSpace, participants, joined, avOnly ->
        hasSpace && (participants.isNotEmpty() || joined || avOnly)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
}
