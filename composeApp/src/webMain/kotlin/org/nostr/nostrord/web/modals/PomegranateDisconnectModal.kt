package org.nostr.nostrord.web.modals

import org.nostr.nostrord.ui.screens.backup.BackupViewModel
import org.nostr.nostrord.ui.screens.backup.BackupViewModel.DisconnectNotice
import org.nostr.nostrord.ui.screens.backup.BackupViewModel.PomegranateDisconnect
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.formError
import org.nostr.nostrord.web.components.noticeCard
import org.nostr.nostrord.web.components.useEscClose
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName
import web.html.InputType
import web.html.checkbox

external interface PomegranateDisconnectModalProps : Props {
    var vm: BackupViewModel

    /** Closes the modal; the account may already be gone when the disconnect signed it out. */
    var onClose: () -> Unit
}

/**
 * Confirmation for unlinking a Login-with-Google account from its central server. The
 * outcome depends on whether the nsec was exported first: with it the account converts to
 * a local-key login and stays signed in, without it it can no longer sign and is signed
 * out. Mirrors the Compose PomegranateDisconnectDialog.
 */
val PomegranateDisconnectModal =
    FC<PomegranateDisconnectModalProps> { props ->
        val vm = props.vm
        val disconnect = useStateFlow(vm.pomDisconnect)
        val pomError = useStateFlow(vm.pomError)
        val (acknowledged, setAcknowledged) = useState { false }
        val keyExported = vm.pomKeyExported
        val working = disconnect == PomegranateDisconnect.Working
        val done = disconnect as? PomegranateDisconnect.Done

        // Closing while the Google popup is still open abandons the attempt, so the next
        // one starts clean instead of finding the flow stuck on "Disconnecting...".
        val close = {
            if (done == null) {
                vm.cancelPomegranateDisconnect()
                props.onClose()
            } else {
                // Esc / overlay / Cancel after the disconnect finished still has to settle
                // the account: an unlinked bunker account left in place can no longer sign.
                vm.finishPomegranateDisconnect { props.onClose() }
            }
        }
        useEscClose { close() }

        div {
            // over-settings: this modal is only ever opened from the settings overlay.
            className = ClassName("modal-overlay over-settings")
            onClick = { close() }
            div {
                className = ClassName("modal-card sm")
                onClick = { it.stopPropagation() }
                div {
                    className = ClassName("modal-title")
                    +(if (done != null) "Disconnected from central server" else "Disconnect from central server")
                }

                if (done != null) {
                    notice(vm.pomDisconnectedNotice(done.convertedToLocal))
                    div {
                        className = ClassName("modal-footer")
                        button {
                            className = ClassName("btn-primary")
                            onClick = { vm.finishPomegranateDisconnect { props.onClose() } }
                            +"Done"
                        }
                    }
                    return@div
                }

                notice(vm.pomDisconnectNotice(keyExported))
                formError(pomError)

                // The ack only guards the lossy path: with the key exported the app keeps
                // signing locally, so there is nothing to lose by continuing.
                if (!keyExported) {
                    div {
                        className = ClassName("protect-card")
                        onClick = { if (!working) setAcknowledged(!acknowledged) }
                        input {
                            type = InputType.checkbox
                            checked = acknowledged
                            disabled = working
                            onChange = { event -> setAcknowledged(event.currentTarget.checked) }
                        }
                        div {
                            className = ClassName("protect-card-title")
                            +"I have safely backed up my private key"
                        }
                    }
                }

                div {
                    className = ClassName("modal-footer")
                    button {
                        className = ClassName("btn-text")
                        onClick = { close() }
                        +"Cancel"
                    }
                    button {
                        className = ClassName("btn-danger")
                        disabled = working || (!keyExported && !acknowledged)
                        onClick = { vm.disconnectPomegranate() }
                        if (working) {
                            span { className = ClassName("btn-spinner") }
                        }
                        +(if (working) "Disconnecting…" else "Disconnect from central server")
                    }
                }
            }
        }
    }

private fun ChildrenBuilder.notice(notice: DisconnectNotice) = noticeCard(title = notice.title, body = notice.body, alert = notice.alert)
