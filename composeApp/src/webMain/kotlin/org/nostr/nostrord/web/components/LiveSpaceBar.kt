package org.nostr.nostrord.web.components

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.ui.screens.avspace.LiveSpaceBarViewModel
import org.nostr.nostrord.utils.shortNpub
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

external interface LiveSpaceBarProps : Props {
    var groupId: String
    var userMetadata: Map<String, UserMetadata>
    var onOpen: () -> Unit
}

/** How many participant avatars the bar stacks before it stops. */
private const val AVATAR_STACK = 4

/**
 * In-chat banner for a NIP-29 AV space: participant count, a stack of the first few faces and
 * a Join action.
 *
 * Self-hiding: an empty room costs the chat pane a row and tells the reader nothing. Starting
 * the first room lives in the sidebar's Voice room row, which is always there, so the chat only
 * carries the bar once there is a call to join (or one this browser is already in).
 */
val LiveSpaceBar =
    FC<LiveSpaceBarProps> { props ->
        val repo = AppModule.nostrRepository
        val selfPubkey = useStateFlow(repo.activePubkey)
        val vm = useViewModel(props.groupId) {
            LiveSpaceBarViewModel(repo, props.groupId, selfPubkey, AppModule.avSpaceHost)
        }
        val participants = useStateFlow(vm.participants)
        val joined = useStateFlow(vm.joined)
        val visible = useStateFlow(vm.visible)
        val count = participants.size
        val live = count > 0

        if (!visible) return@FC
        button {
            className = ClassName(if (live) "live-space-bar" else "live-space-bar live-space-idle")
            onClick = { props.onOpen() }

            if (live) {
                span {
                    className = ClassName("live-badge")
                    span { className = ClassName("live-dot") }
                    +"LIVE"
                }
            }
            span {
                className = ClassName("live-space-icon")
                icon(Ic.Mic)
            }
            div {
                className = ClassName("live-space-text")
                div {
                    className = ClassName("live-space-title")
                    +"Voice room"
                }
                div {
                    className = ClassName("live-space-sub")
                    +when (count) {
                        0 -> "Nobody here yet"
                        1 -> "1 person"
                        else -> "$count people"
                    }
                }
            }
            div {
                className = ClassName("live-space-avatars")
                participants.take(AVATAR_STACK).forEach { participant ->
                    val pubkey = participant.pubkey
                    WebAvatar {
                        key = pubkey
                        url = props.userMetadata[pubkey]?.picture
                        seed = pubkey
                        name = displayNameOf(pubkey, props.userMetadata)
                        cls = "live-space-avatar"
                    }
                }
            }
            span {
                className = ClassName("live-space-join")
                // Already inside: the pill returns you to the room instead of offering to
                // join one you are in, which is the mini-player every call app falls back to.
                +when {
                    joined -> "Open"
                    live -> "Join"
                    else -> "Start"
                }
            }
        }
    }

/** Display name for a room participant, falling back to the short npub. */
internal fun displayNameOf(pubkey: String, userMetadata: Map<String, UserMetadata>): String {
    val meta = userMetadata[pubkey]
    return meta?.displayName?.takeIf { it.isNotBlank() }
        ?: meta?.name?.takeIf { it.isNotBlank() }
        ?: shortNpub(pubkey)
}
