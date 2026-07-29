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
 * In-chat banner for a live NIP-29 AV space: LIVE badge, participant count, a stack of the
 * first few faces and a Join action. Rendered only while the room has someone in it, so an
 * idle AV-capable group shows nothing.
 */
val LiveSpaceBar =
    FC<LiveSpaceBarProps> { props ->
        val count = props.participants.size
        button {
            className = ClassName("live-space-bar")
            onClick = { props.onOpen() }

            span {
                className = ClassName("live-badge")
                span { className = ClassName("live-dot") }
                +"LIVE"
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
                    +(if (count == 1) "1 person" else "$count people")
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
                +"Join"
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
