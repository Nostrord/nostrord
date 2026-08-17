package org.nostr.nostrord.web.components

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.utils.resolveGroupRef
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.p
import react.useEffect
import web.cssom.ClassName

external interface GroupInviteCardProps : Props {
    var groupId: String
    var relayUrl: String?
    var onOpen: () -> Unit
}

/**
 * DM group-invite card (prototype InviteCard): "GROUP INVITE" eyebrow + group avatar/name,
 * two-line about, and a full-width primary "View group" button. Rendered by DmPage when a
 * message carries a kind:39000 naddr on its own line; the button (and the card) navigate
 * to the group. Metadata resolves from groupsByRelay with a preview fetch when unknown.
 */
val GroupInviteCard =
    FC<GroupInviteCardProps> { props ->
        val repo = AppModule.nostrRepository
        val groupsByRelay = useStateFlow(repo.groupsByRelay)
        // The invite's own relay decides: NIP-29 ids are relay-local, so a same-id group from
        // another relay must not paint this card.
        val meta = resolveGroupRef(groupsByRelay, props.groupId, props.relayUrl)
        val name = meta?.name?.takeIf { it.isNotBlank() } ?: props.groupId

        useEffect(props.groupId, props.relayUrl, meta?.name) {
            val relay = props.relayUrl
            if (relay != null && meta?.name == null) {
                launchApp { repo.fetchGroupPreview(props.groupId, relay) }
            }
        }

        div {
            className = ClassName("group-invite-card")
            onClick = { props.onOpen() }
            div {
                className = ClassName("gic-head")
                WebAvatar {
                    url = meta?.picture
                    seed = props.groupId
                    kind = AvatarKind.GROUP
                    this.name = name
                    cls = "gic-avatar"
                }
                div {
                    className = ClassName("gic-titles")
                    div {
                        className = ClassName("gic-eyebrow")
                        +"Group invite"
                    }
                    div {
                        className = ClassName("gic-name")
                        +name
                    }
                }
            }
            meta?.about?.takeIf { it.isNotBlank() }?.let { about ->
                p {
                    className = ClassName("gic-about")
                    +about
                }
            }
            button {
                className = ClassName("btn-primary gic-btn")
                onClick = {
                    it.stopPropagation()
                    props.onOpen()
                }
                +"View group"
                icon(Ic.ChevronRight, cls = "ico gic-btn-chevron")
            }
        }
    }
