package org.nostr.nostrord.ui.screens.avspace

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.nostr.nostrord.network.NostrRepositoryApi

/** The one room being held open, and the ViewModel driving it. */
data class AvSpaceSession(val groupId: String, val viewModel: AvSpaceViewModel)

/**
 * Owner of the AV space session, so a call outlives the surface that started it.
 *
 * A voice room is not screen state. Closing the room UI, or walking over to another group, must
 * not hang up: only Leave does. So the ViewModel lives here instead of in a screen's store, and
 * every surface that shows the room (the in-chat banner, the room itself, the sidebar entry)
 * asks for the same instance.
 *
 * One room at a time, which is all a single microphone allows anyway. Opening a second one
 * retires the first.
 */
class AvSpaceHost(private val repo: NostrRepositoryApi) {
    private var store: ViewModelStore? = null

    private val _session = MutableStateFlow<AvSpaceSession?>(null)

    /** The live session, or null while no room is open. */
    val session: StateFlow<AvSpaceSession?> = _session.asStateFlow()

    private val _roomVisible = MutableStateFlow(false)

    /**
     * Whether the room UI is on screen. Separate from [session] on purpose: dismissing the room
     * hides it and keeps the call, which is the whole point of the host owning the session.
     */
    val roomVisible: StateFlow<Boolean> = _roomVisible.asStateFlow()

    /** Open [groupId]'s room and show it. The one entry point every surface calls. */
    fun show(groupId: String, selfPubkey: String?) {
        open(groupId, selfPubkey)
        _roomVisible.value = true
    }

    /** Put the room away without hanging up. */
    fun hide() {
        _roomVisible.value = false
    }

    /**
     * The ViewModel for [groupId], reusing the live one when it is already this group's.
     *
     * Only a surface that actually shows the room calls this. A banner must not: it renders for
     * every group carrying the tag, and creating a session per banner would hang up the real
     * call as soon as the user glanced at another group.
     */
    fun open(groupId: String, selfPubkey: String?): AvSpaceViewModel {
        _session.value?.let { live ->
            if (live.groupId == groupId) return live.viewModel
        }
        release()
        val viewModel = AvSpaceViewModel(repo, groupId, selfPubkey)
        store = ViewModelStore().also { it.put(STORE_KEY, viewModel) }
        _session.value = AvSpaceSession(groupId, viewModel)
        return viewModel
    }

    /**
     * Hang up and forget the session. Clearing the store runs `onCleared`, which disconnects the
     * engine, so this is the only teardown the room needs.
     */
    fun release() {
        store?.clear()
        store = null
        _session.value = null
        _roomVisible.value = false
    }

    private companion object {
        const val STORE_KEY = "avspace"
    }
}
