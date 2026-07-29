package org.nostr.nostrord.web.screens

import org.nostr.nostrord.ui.screens.backup.BackupViewModel
import org.nostr.nostrord.ui.screens.backup.BackupViewModel.PomegranateDisconnect
import org.nostr.nostrord.ui.screens.backup.BackupViewModel.PomegranateExport
import org.nostr.nostrord.ui.screens.backup.BackupViewModel.ShardStatus
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.IdentifierRow
import org.nostr.nostrord.web.components.Portal
import org.nostr.nostrord.web.components.formError
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.modals.PomegranateDisconnectModal
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName

external interface PomegranateKeySectionProps : Props {
    var vm: BackupViewModel
}

/**
 * Backup-keys section for pomegranate (Login with Google) accounts: explains the
 * sharded-key model, exports the nsec by recovering shards from the operators one
 * popup at a time, and disconnects the account from the central server. States all
 * live in [BackupViewModel]; this component is layout only.
 */
val PomegranateKeySection =
    FC<PomegranateKeySectionProps> { props ->
        val vm = props.vm
        val export = useStateFlow(vm.pomExport)
        val disconnect = useStateFlow(vm.pomDisconnect)
        val pomError = useStateFlow(vm.pomError)
        val (confirming, setConfirming) = useState { false }

        div {
            className = ClassName("settings-card")
            div {
                className = ClassName("field-label")
                +"Private key"
            }
            div {
                className = ClassName("settings-tip")
                +(
                    "This account signs in with Google: the key was created for you, split into shards held by " +
                        "independent operators, and never stored whole anywhere. Signing happens remotely (NIP-46) " +
                        "through the central server. You can reassemble and export it below."
                    )
            }
            formError(pomError)
            when (export) {
                PomegranateExport.Idle -> {
                    div {
                        className = ClassName("pom-actions")
                        button {
                            className = ClassName("btn-secondary")
                            onClick = { vm.startPomegranateExport() }
                            +"Export private key"
                        }
                    }
                }

                PomegranateExport.Authing -> {
                    div {
                        className = ClassName("pom-actions")
                        button {
                            className = ClassName("btn-secondary")
                            disabled = true
                            span { className = ClassName("btn-spinner") }
                            +"Waiting for Google sign-in…"
                        }
                    }
                }

                is PomegranateExport.Recovering -> {
                    div {
                        className = ClassName("settings-tip")
                        +"Recovered ${export.recovered} of ${export.threshold} shards. Any ${export.threshold} operators are enough; a failing one can be skipped."
                    }
                    export.operators.forEach { op ->
                        div {
                            key = op.operator.url
                            className = ClassName("pom-operator-row")
                            span {
                                className = ClassName("pom-operator-host")
                                +op.host
                            }
                            when (op.status) {
                                ShardStatus.Recovered -> {
                                    span {
                                        className = ClassName("benefit-check")
                                        icon(Ic.Check)
                                    }
                                }

                                else -> {
                                    button {
                                        className = ClassName("btn-secondary btn-sm")
                                        disabled = export.operators.any { it.status == ShardStatus.Recovering }
                                        onClick = { vm.recoverPomegranateShard(op.operator.url) }
                                        if (op.status == ShardStatus.Recovering) {
                                            span { className = ClassName("btn-spinner") }
                                        }
                                        +when (op.status) {
                                            ShardStatus.Recovering -> "Waiting…"
                                            ShardStatus.Failed -> "Retry"
                                            else -> "Recover"
                                        }
                                    }
                                }
                            }
                        }
                    }
                    div {
                        className = ClassName("pom-actions")
                        button {
                            className = ClassName("btn-text")
                            onClick = { vm.cancelPomegranateExport() }
                            +"Cancel"
                        }
                    }
                }

                is PomegranateExport.Done -> {
                    IdentifierRow { ids = vm.pomDirectIds() }
                    div {
                        className = ClassName("settings-tip")
                        +"Store it somewhere safe. With the nsec you can log in on any device via the Private Key tab, with or without Google."
                    }
                    EncryptedBackupSubsection { this.vm = vm }
                    div {
                        className = ClassName("backup-footer")
                        button {
                            className = ClassName("btn-text")
                            onClick = { vm.cancelPomegranateExport() }
                            +"Hide private key"
                        }
                    }
                }
            }
        }

        div {
            className = ClassName("settings-card warning")
            div {
                className = ClassName("settings-section-head")
                +"DISCONNECT FROM CENTRAL SERVER"
            }
            div {
                className = ClassName("settings-tip")
                +(
                    "Removes this account from the central server and turns off Google login for it. " +
                        "Export your private key first: with it exported, this device keeps the account and " +
                        "signs with that key locally. Without it the account can no longer sign anything."
                    )
            }
            if (disconnect is PomegranateDisconnect.Done && !confirming) {
                div {
                    className = ClassName("settings-tip")
                    +(
                        if (disconnect.convertedToLocal) {
                            "Disconnected from Google. This account now signs with the exported key on this device."
                        } else {
                            "Disconnected. This account can read but no longer sign; log in with its exported key to keep using it."
                        }
                        )
                }
            } else {
                div {
                    className = ClassName("pom-actions")
                    button {
                        className = ClassName("btn-danger")
                        onClick = { setConfirming(true) }
                        +"Disconnect from central server"
                    }
                }
            }
        }

        // Portaled to <body>: the settings panel this section renders inside establishes a
        // containing block, which would pin the overlay to the panel instead of the viewport.
        if (confirming) {
            Portal {
                PomegranateDisconnectModal {
                    this.vm = vm
                    onClose = { setConfirming(false) }
                }
            }
        }
    }
