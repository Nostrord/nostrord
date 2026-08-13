package org.nostr.nostrord.web.modals

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.ui.groupIdentifiers
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.IdentifierRow
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.useEscClose
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import web.cssom.ClassName

external interface ShareGroupModalProps : Props {
    var group: GroupMetadata

    /** Relay the group is open on. The shared address must point at this relay's copy of it. */
    var relayUrl: String
    var onClose: () -> Unit
}

/**
 * Share-group modal — real port of the Compose ShareGroupModal: a single cycling identifier field
 * (relay'groupId / naddr / nostrord link) instead of one copy input per format.
 */
val ShareGroupModal =
    FC<ShareGroupModalProps> { props ->
        val group = props.group
        val relayUrl = props.relayUrl
        val relayMetadata = useStateFlow(AppModule.nostrRepository.relayMetadata)
        // Author = NIP-11 `self` (relay signing key), else `pubkey`; falls back to zero bytes inside encodeNaddr.
        val relayPubkey = relayMetadata[relayUrl]?.groupNaddrAuthor ?: relayMetadata[relayUrl.trimEnd('/')]?.groupNaddrAuthor

        useEscClose { props.onClose() }

        div {
            className = ClassName("modal-overlay")
            onClick = { props.onClose() }
            div {
                className = ClassName("modal-card sm")
                onClick = { it.stopPropagation() }

                div {
                    className = ClassName("modal-header")
                    div {
                        className = ClassName("modal-header-text")
                        div {
                            className = ClassName("modal-title")
                            +"Share Group"
                        }
                    }
                    button {
                        className = ClassName("modal-close")
                        onClick = { props.onClose() }
                        icon(Ic.Close)
                    }
                }

                div {
                    className = ClassName("settings-section-head")
                    +"GROUP ADDRESS"
                }
                IdentifierRow { ids = groupIdentifiers(relayUrl, group.id, relayPubkey) }
            }
        }
    }
