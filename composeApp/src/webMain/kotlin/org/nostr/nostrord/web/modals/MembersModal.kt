package org.nostr.nostrord.web.modals

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.screens.group.GroupViewModel
import org.nostr.nostrord.utils.shortNpub
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.useEscClose
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName

external interface MembersModalProps : Props {
    var groupId: String

    /** Relay hosting the group; scopes the roster (same-id groups elsewhere stay out). */
    var relayUrl: String?
    var onClose: () -> Unit
}

/**
 * Read-only member roster (port of the prototype GroupPanels "Members" panel): avatar +
 * name, with an ADMIN badge, and tapping a row opens that member's profile. This is the
 * non-admin view; admins get the editable Members tab inside ManageGroupModal instead.
 */
val MembersModal =
    FC<MembersModalProps> { props ->
        val repo = AppModule.nostrRepository
        val vm = useViewModel("${props.relayUrl}|${props.groupId}") { GroupViewModel(repo, props.groupId, props.relayUrl) }
        val members = useStateFlow(vm.groupMembers)[props.groupId].orEmpty()
        val admins = useStateFlow(vm.groupAdmins)[props.groupId].orEmpty().toSet()
        val userMetadata = useStateFlow(repo.userMetadata)
        val (selected, setSelected) = useState<String?> { null }
        useEscClose { props.onClose() }

        fun nameOf(pubkey: String): String {
            val meta = userMetadata[pubkey]
            return meta?.displayName?.takeIf { it.isNotBlank() }
                ?: meta?.name?.takeIf { it.isNotBlank() }
                ?: shortNpub(pubkey)
        }

        div {
            className = ClassName("modal-overlay")
            onClick = { props.onClose() }
            div {
                className = ClassName("modal-card")
                onClick = { it.stopPropagation() }

                div {
                    className = ClassName("modal-header")
                    div {
                        className = ClassName("modal-header-text")
                        div {
                            className = ClassName("modal-title")
                            +(if (members.isNotEmpty()) "Members · ${members.size}" else "Members")
                        }
                    }
                    button {
                        className = ClassName("modal-close")
                        onClick = { props.onClose() }
                        icon(Ic.Close)
                    }
                }

                div {
                    className = ClassName("mod-list")
                    if (members.isEmpty()) {
                        div {
                            className = ClassName("mod-empty")
                            +"Member list unavailable."
                        }
                    }
                    members.forEach { pubkey ->
                        button {
                            key = pubkey
                            className = ClassName("member-row-btn")
                            onClick = { setSelected(pubkey) }
                            WebAvatar {
                                url = userMetadata[pubkey]?.picture
                                seed = pubkey
                                name = nameOf(pubkey)
                                cls = "mod-avatar"
                            }
                            span {
                                className = ClassName("mod-name")
                                +nameOf(pubkey)
                            }
                            if (pubkey in admins) {
                                span {
                                    className = ClassName("member-admin")
                                    +"ADMIN"
                                }
                            }
                        }
                    }
                }
            }
        }

        selected?.let { pubkey ->
            UserProfileModal {
                this.pubkey = pubkey
                // Non-admin viewer: the profile modal's admin section stays hidden.
                groupId = props.groupId
                iAmAdmin = false
                targetIsAdmin = pubkey in admins
                onMention = null
                onClose = { setSelected(null) }
            }
        }
    }
