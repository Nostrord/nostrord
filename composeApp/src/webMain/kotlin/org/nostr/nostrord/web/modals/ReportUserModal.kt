package org.nostr.nostrord.web.modals

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.screens.report.REPORT_REASONS
import org.nostr.nostrord.ui.screens.report.ReportUserViewModel
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.useEscClose
import react.FC
import react.Props
import react.dom.html.ReactHTML.b
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.span
import react.useEffect
import web.cssom.ClassName

external interface ReportUserModalProps : Props {
    var pubkey: String

    /** Pins the report to a specific message; null reports the user. */
    var eventId: String?
    var onClose: () -> Unit
}

/**
 * NIP-56 report modal (prototype ReportModal): reason radio cards, optional note,
 * the "also mute" toggle and an in-modal success state. Same shared
 * [ReportUserViewModel] as the Compose modal.
 */
val ReportUserModal =
    FC<ReportUserModalProps> { props ->
        val vm =
            useViewModel("report:${props.pubkey}:${props.eventId}") {
                ReportUserViewModel(AppModule.nostrRepository, props.pubkey, props.eventId)
            }
        val selected = useStateFlow(vm.selected)
        val note = useStateFlow(vm.note)
        val alsoMute = useStateFlow(vm.alsoMute)
        val phase = useStateFlow(vm.phase)
        val error = useStateFlow(vm.error)
        val alreadyMuted = useStateFlow(vm.targetAlreadyMuted)
        val didMute = useStateFlow(vm.didMute)

        val allMeta = useStateFlow(AppModule.nostrRepository.userMetadata)
        val meta = allMeta[props.pubkey]
        val npub = Nip19.encodeNpub(props.pubkey)
        val name =
            meta?.displayName?.takeIf { it.isNotBlank() }
                ?: meta?.name?.takeIf { it.isNotBlank() }
                ?: (npub.take(12) + "…")

        useEffect(props.pubkey) {
            launchApp { AppModule.nostrRepository.requestUserMetadata(setOf(props.pubkey)) }
        }
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
                        className = ClassName("modal-title")
                        +(if (phase == ReportUserViewModel.Phase.Sent) "Report sent" else "Report")
                    }
                    button {
                        className = ClassName("modal-close")
                        onClick = { props.onClose() }
                        icon(Ic.Close)
                    }
                }

                div {
                    className = ClassName("report-head")
                    WebAvatar {
                        url = meta?.picture
                        seed = props.pubkey
                        this.name = name
                        cls = "report-head-avatar"
                    }
                    div {
                        div {
                            className = ClassName("report-head-name")
                            +name
                        }
                        div {
                            className = ClassName("report-head-sub")
                            +(if (props.eventId != null) "Report this message" else "Report user")
                        }
                    }
                }

                if (phase == ReportUserViewModel.Phase.Sent) {
                    div {
                        className = ClassName("report-success")
                        div {
                            className = ClassName("report-success-icon")
                            +"🛡️"
                        }
                        div {
                            className = ClassName("report-success-title")
                            +"Report submitted"
                        }
                        div {
                            className = ClassName("report-success-text")
                            +(
                                if (didMute) {
                                    "The report was published to your relays (kind:1984 event). $name was also muted."
                                } else {
                                    "The report was published to your relays (kind:1984 event). Thank you."
                                }
                                )
                        }
                        button {
                            className = ClassName("btn-primary")
                            onClick = { props.onClose() }
                            +"Done"
                        }
                    }
                } else {
                    div {
                        className = ClassName("field-label")
                        +"Reason"
                    }
                    div {
                        className = ClassName("report-reasons")
                        REPORT_REASONS.forEach { reason ->
                            val isSelected = selected == reason.type
                            button {
                                className = ClassName(if (isSelected) "report-reason selected" else "report-reason")
                                onClick = { vm.select(reason.type) }
                                span {
                                    className = ClassName(if (isSelected) "report-radio on" else "report-radio")
                                }
                                div {
                                    div {
                                        className = ClassName("report-reason-label")
                                        +reason.label
                                    }
                                    div {
                                        className = ClassName("report-reason-hint")
                                        +reason.hint
                                    }
                                }
                            }
                        }
                    }

                    input {
                        className = ClassName("modal-input")
                        placeholder = "Details (optional)"
                        value = note
                        onChange = { event -> vm.setNote(event.currentTarget.value) }
                    }

                    if (!alreadyMuted) {
                        button {
                            className = ClassName("report-mute-row")
                            onClick = { vm.toggleAlsoMute() }
                            span {
                                className = ClassName(if (alsoMute) "report-check on" else "report-check")
                                if (alsoMute) icon(Ic.Check)
                            }
                            span {
                                className = ClassName("report-mute-label")
                                +"Also mute "
                                b { +name }
                                +" (recommended)"
                            }
                        }
                    }

                    if (error != null) {
                        div {
                            className = ClassName("modal-error")
                            +error
                        }
                    }

                    div {
                        className = ClassName("modal-footer")
                        button {
                            className = ClassName("btn-text")
                            onClick = { props.onClose() }
                            +"Cancel"
                        }
                        button {
                            className = ClassName("btn-danger")
                            disabled = selected == null || phase != ReportUserViewModel.Phase.Editing
                            onClick = { vm.send() }
                            +(if (phase == ReportUserViewModel.Phase.Sending) "Sending…" else "Send report")
                        }
                    }
                }
            }
        }
    }
