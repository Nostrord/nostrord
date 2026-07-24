package org.nostr.nostrord.web.modals

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.groupIdentifiers
import org.nostr.nostrord.ui.screens.group.GroupViewModel
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.IdentifierRow
import org.nostr.nostrord.web.components.copyToClipboard
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.useEscClose
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName

external interface InviteCodesModalProps : Props {
    var groupId: String

    /** Relay hosting the group; scopes the code list (same-id groups elsewhere stay out). */
    var relayUrl: String?
    var onClose: () -> Unit
}

/**
 * Invite-codes modal — real port of the Compose InviteCodesModal (admin): active codes
 * derived from the group's `messages` (kind:9009 minus revoked kind:9005), create via
 * `createInviteCode`, revoke via `revokeInviteCode`, copy code / URL to the clipboard.
 */
val InviteCodesModal =
    FC<InviteCodesModalProps> { props ->
        val repo = AppModule.nostrRepository
        // Relay-scoped VM: the raw bucket can hold a same-id group's kind:9009s from
        // another relay.
        val vm = useViewModel("${props.relayUrl}|${props.groupId}") { GroupViewModel(repo, props.groupId, props.relayUrl) }
        val msgs = useStateFlow(vm.messages)[props.groupId].orEmpty()
        val focusedRelay = useStateFlow(repo.currentRelayUrl)
        val relayUrl = props.relayUrl ?: focusedRelay
        val (busy, setBusy) = useState { false }

        val revoked =
            msgs.filter { it.kind == 9005 }
                .flatMap { m -> m.tags.filter { it.firstOrNull() == "e" }.mapNotNull { it.getOrNull(1) } }
                .toSet()
        // code -> eventId, newest first
        val codes =
            msgs.filter { it.kind == 9009 && it.id !in revoked }
                .mapNotNull { m ->
                    val code = m.tags.firstOrNull { it.firstOrNull() == "code" }?.getOrNull(1) ?: return@mapNotNull null
                    Triple(code, m.id, m.createdAt)
                }
                .sortedByDescending { it.third }

        // Author = the relay's own pubkey (NIP-11) for the naddr format.
        val relayMetadata = useStateFlow(repo.relayMetadata)
        val relayPubkey = relayMetadata[relayUrl]?.groupNaddrAuthor ?: relayMetadata[relayUrl.trimEnd('/')]?.groupNaddrAuthor

        useEscClose { props.onClose() }

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
                            +"Invite Codes"
                        }
                        div {
                            className = ClassName("modal-subtitle")
                            +"This group requires an invite code to join."
                        }
                    }
                    button {
                        className = ClassName("modal-close")
                        onClick = { props.onClose() }
                        icon(Ic.Close)
                    }
                }

                button {
                    className = ClassName("btn-primary block")
                    disabled = busy
                    onClick = {
                        setBusy(true)
                        launchApp {
                            repo.createInviteCode(props.groupId)
                            setBusy(false)
                        }
                    }
                    +(if (busy) "Creating…" else "Create Invite Code")
                }

                div {
                    className = ClassName("access-section-title")
                    +"ACTIVE CODES (${codes.size})"
                }
                div {
                    className = ClassName("mod-list")
                    if (codes.isEmpty()) {
                        div {
                            className = ClassName("mod-empty")
                            +"No active invite codes"
                        }
                    }
                    codes.forEach { (code, eventId, _) ->
                        div {
                            key = eventId
                            className = ClassName("mod-row invite-code-row")
                            div {
                                className = ClassName("invite-code-head")
                                span {
                                    className = ClassName("mod-code")
                                    +code
                                }
                                div {
                                    className = ClassName("mod-actions")
                                    button {
                                        className = ClassName("mod-btn")
                                        onClick = { copyToClipboard(code) }
                                        +"Copy code"
                                    }
                                    button {
                                        className = ClassName("mod-btn danger")
                                        onClick = { launchApp { repo.revokeInviteCode(props.groupId, eventId) } }
                                        +"Revoke"
                                    }
                                }
                            }
                            // Shareable forms with the ?invite= suffix (address / naddr / link);
                            // parseGroupJoinInput auto-joins from any of them.
                            IdentifierRow { ids = groupIdentifiers(relayUrl, props.groupId, relayPubkey, code) }
                        }
                    }
                }
            }
        }
    }
