package org.nostr.nostrord.web.components

import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.utils.shortNpub
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

external interface LiveSpaceBarProps : Props {
    /** Pubkeys currently in the room, from kind 39004. */
    var participants: List<String>
    var userMetadata: Map<String, UserMetadata>
    var onOpen: () -> Unit
}

/** How many participant avatars the bar stacks before it stops. */
private const val AVATAR_STACK = 4

/**
 * In-chat banner for a NIP-29 AV space: participant count, a stack of the first few faces and
 * a Join action.
 *
 * Shown for every group carrying the `livekit` tag, including an empty room. NIP-29 has no
 * "open the room" event - the relay creates it lazily on the first token request - so hiding
 * the empty state would leave nobody able to be the first one in.
 */
val LiveSpaceBar =
    FC<LiveSpaceBarProps> { props ->
        val count = props.participants.size
        val live = count > 0
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
                props.participants.take(AVATAR_STACK).forEach { pubkey ->
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
                +(if (live) "Join" else "Start")
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
