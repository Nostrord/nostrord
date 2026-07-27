package org.nostr.nostrord.web.screens

import kotlinx.coroutines.delay
import org.nostr.nostrord.ui.screens.login.LoginViewModel
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.copyToClipboard
import org.nostr.nostrord.web.components.formError
import org.nostr.nostrord.web.components.formHint
import org.nostr.nostrord.web.components.formLabel
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.iconInput
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.code
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName
import web.html.InputType
import web.html.checkbox
import web.html.password
import web.html.text

external interface KeyLoginFormProps : Props {
    var vm: LoginViewModel
    var busy: Boolean

    /** Button labels, e.g. "Login"/"Logging in…" or "Add account"/"Adding…". */
    var submitLabel: String
    var busyLabel: String

    /** Open straight on the generate wizard (entered from "Generate New Key"). */
    var startInWizard: Boolean

    /** Leave the private-key flow; the wizard's first step has no form behind it. */
    var onBack: () -> Unit

    /** Run the actual login: (input, ncryptsec password or null, isNewIdentity). */
    var onSubmit: (String, String?, Boolean) -> Unit

    /**
     * Password-protected login (input, protect password, isNewIdentity): the key is
     * stored encrypted (ncryptsec) and the unlock password is asked at next startup.
     */
    var onSubmitProtected: (String, String, Boolean) -> Unit
}

/**
 * The private-key credential form (prototype flow), shared by LoginScreen and the
 * add-account sheet so the two never drift:
 *  - nsec / hex / ncryptsec input (label + hint + eye toggle), strict validity gating;
 *  - pasting an ncryptsec reveals the key-password field;
 *  - a plain hex/nsec offers "Protect with password", which stores the key encrypted
 *    (ncryptsec) on this device — the unlock dialog asks the password at startup;
 *  - `startInWizard` opens straight on the two-step generate wizard (backup npub/nsec,
 *    then optional password with the same protected-storage semantics), entered from the
 *    "Generate New Key" action on the method list.
 * Mirrors the Compose PrivateKeyLoginTab; logic lives in the shared LoginViewModel.
 */
val KeyLoginForm =
    FC<KeyLoginFormProps> { props ->
        val vm = props.vm
        val busy = props.busy
        val startInWizard = props.startInWizard

        val (privateKey, setPrivateKey) = useState { "" }
        val (keyPassword, setKeyPassword) = useState { "" }
        val (showKey, setShowKey) = useState { false }
        val (localError, setLocalError) = useState<String?> { null }

        // Protect-with-password (plain keys)
        val (protect, setProtect) = useState { false }
        val (protectPwd, setProtectPwd) = useState { "" }
        val (protectConfirm, setProtectConfirm) = useState { "" }

        // Generate wizard: 0 = form, 1 = backup step, 2 = password step
        val (wizardStep, setWizardStep) = useState { if (startInWizard) 1 else 0 }
        val (wizardKey, setWizardKey) = useState { if (startInWizard) vm.generateNewKeyHex() else "" }
        val (wizardPwd, setWizardPwd) = useState { "" }
        val (wizardConfirm, setWizardConfirm) = useState { "" }

        val isEncrypted = vm.isEncryptedKeyInput(privateKey)
        val isPlain = vm.isPlainKeyInput(privateKey)
        val protectActive = protect && isPlain
        val canSubmit =
            vm.isValidKeyInput(privateKey) &&
                (!isEncrypted || keyPassword.isNotEmpty()) &&
                (!protectActive || protectPwd.isNotEmpty()) &&
                !busy

        fun clearError() = setLocalError(null)

        fun submit() {
            if (!canSubmit) return
            val input = privateKey.trim()
            when {
                isEncrypted -> props.onSubmit(input, keyPassword, false)
                protectActive -> {
                    if (protectPwd != protectConfirm) {
                        setLocalError("Passwords don't match")
                        return
                    }
                    props.onSubmitProtected(input, protectPwd, false)
                }
                else -> props.onSubmit(input, null, false)
            }
        }

        formError(localError)

        when {
            // ── Generate wizard ──────────────────────────────────────────────
            wizardStep > 0 -> {
                div {
                    className = ClassName("wizard-dots")
                    span { className = ClassName(if (wizardStep == 1) "wizard-dot active" else "wizard-dot") }
                    span { className = ClassName(if (wizardStep == 2) "wizard-dot active" else "wizard-dot") }
                }
                if (wizardStep == 1) {
                    div {
                        className = ClassName("wizard-title")
                        +"Your new key"
                    }
                    p {
                        className = ClassName("wizard-sub")
                        +"Save the nsec somewhere safe. Whoever has it controls your account."
                    }
                    val bech = vm.deriveBech32Keys(wizardKey)
                    div {
                        className = ClassName("keyline-box")
                        KeyLineRow {
                            this.label = "npub"
                            this.value = bech?.first ?: ""
                            this.danger = false
                        }
                        KeyLineRow {
                            this.label = "nsec"
                            this.value = bech?.second ?: ""
                            this.danger = true
                        }
                    }
                    button {
                        className = ClassName("link-inline")
                        onClick = { setWizardKey(vm.generateNewKeyHex()) }
                        icon(Ic.Refresh)
                        +"Generate another key"
                    }
                    div {
                        className = ClassName("wizard-actions")
                        button {
                            className = ClassName("btn-ghost")
                            // Entered from the method list: there is no key form behind
                            // this step, so back leaves the private-key flow entirely.
                            onClick = { if (startInWizard) props.onBack() else setWizardStep(0) }
                            +"Back"
                        }
                        button {
                            className = ClassName("btn-primary grow")
                            onClick = { setWizardStep(2) }
                            +"Continue"
                        }
                    }
                } else {
                    div {
                        className = ClassName("wizard-title")
                        +"Protect your account"
                    }
                    p {
                        className = ClassName("wizard-sub")
                        +"Add an extra layer of protection with a password"
                    }
                    div {
                        className = ClassName("protect-info")
                        icon(Ic.Info)
                        div {
                            div {
                                className = ClassName("protect-info-title")
                                +"Password protection (recommended)"
                            }
                            div {
                                className = ClassName("protect-info-desc")
                                +"Encrypts your new key on this device (ncryptsec); the password is asked to unlock the app. Optional, but strongly recommended."
                            }
                        }
                    }
                    formLabel("Password (optional)")
                    iconInput(
                        ic = Ic.Lock,
                        type = InputType.password,
                        placeholder = "Create a password (or skip)",
                        value = wizardPwd,
                        onChange = {
                            setWizardPwd(it)
                            clearError()
                        },
                    )
                    if (wizardPwd.isNotEmpty()) {
                        formLabel("Confirm password", spaced = true)
                        iconInput(
                            ic = Ic.Lock,
                            type = InputType.password,
                            placeholder = "Repeat the password",
                            value = wizardConfirm,
                            onChange = {
                                setWizardConfirm(it)
                                clearError()
                            },
                        )
                    }
                    div {
                        className = ClassName("wizard-actions")
                        button {
                            className = ClassName("btn-ghost")
                            disabled = busy
                            onClick = { setWizardStep(1) }
                            +"Back"
                        }
                        button {
                            className = ClassName("btn-primary grow")
                            disabled = busy || (wizardPwd.isNotEmpty() && wizardConfirm.isEmpty())
                            onClick = {
                                when {
                                    wizardPwd.isEmpty() -> props.onSubmit(wizardKey, null, true)
                                    wizardPwd != wizardConfirm -> setLocalError("Passwords don't match")
                                    else -> props.onSubmitProtected(wizardKey, wizardPwd, true)
                                }
                            }
                            if (busy) {
                                span { className = ClassName("btn-spinner") }
                            }
                            +(if (wizardPwd.isEmpty()) "Finish without password" else "Finish with password")
                        }
                    }
                }
            }

            // ── Login form ───────────────────────────────────────────────────
            else -> {
                formLabel("Private key (hex, nsec or ncryptsec)")
                iconInput(
                    ic = Ic.Key,
                    type = if (showKey) InputType.text else InputType.password,
                    placeholder = "hex, nsec1, ncryptsec1",
                    value = privateKey,
                    onChange = {
                        setPrivateKey(it)
                        clearError()
                    },
                    onEnter = { submit() },
                    trailing = {
                        button {
                            className = ClassName("input-eye")
                            onClick = { setShowKey(!showKey) }
                            if (showKey) icon(Ic.VisibilityOff) else icon(Ic.Visibility)
                        }
                    },
                )
                formHint("Your key never leaves this device.")

                if (isEncrypted) {
                    formLabel("Key password", spaced = true)
                    iconInput(
                        ic = Ic.Lock,
                        type = InputType.password,
                        placeholder = "Password",
                        value = keyPassword,
                        onChange = {
                            setKeyPassword(it)
                            clearError()
                        },
                        onEnter = { submit() },
                    )
                    formHint("This key is encrypted (NIP-49); enter its password to unlock it. The password is asked again to unlock the app at startup.")
                }

                if (isPlain) {
                    label {
                        className = ClassName("protect-card")
                        input {
                            type = InputType.checkbox
                            checked = protect
                            onChange = { event ->
                                setProtect(event.currentTarget.checked)
                                clearError()
                            }
                        }
                        div {
                            div {
                                className = ClassName("protect-card-title")
                                icon(Ic.Shield)
                                +"Protect with password (recommended)"
                            }
                            div {
                                className = ClassName("protect-card-desc")
                                +"Encrypts your key on this device (ncryptsec); the password is asked to unlock the app."
                            }
                        }
                    }
                    if (protect) {
                        formLabel("Password", spaced = true)
                        iconInput(
                            ic = Ic.Lock,
                            type = InputType.password,
                            placeholder = "Create a password",
                            value = protectPwd,
                            onChange = {
                                setProtectPwd(it)
                                clearError()
                            },
                        )
                        if (protectPwd.isNotEmpty()) {
                            formLabel("Confirm password", spaced = true)
                            iconInput(
                                ic = Ic.Lock,
                                type = InputType.password,
                                placeholder = "Repeat the password",
                                value = protectConfirm,
                                onChange = {
                                    setProtectConfirm(it)
                                    clearError()
                                },
                                onEnter = { submit() },
                            )
                        }
                    }
                }

                button {
                    className = ClassName("btn-primary btn-lg btn-full login-submit")
                    disabled = !canSubmit
                    onClick = { submit() }
                    if (busy) {
                        span { className = ClassName("btn-spinner") }
                    } else {
                        icon(Ic.Login)
                    }
                    +(if (busy) props.busyLabel else props.submitLabel)
                }
            }
        }
    }

private external interface KeyLineRowProps : Props {
    var label: String
    var value: String
    var danger: Boolean
}

/** One npub/nsec row in the wizard backup box: label, truncated mono value, copy. */
private val KeyLineRow =
    FC<KeyLineRowProps> { props ->
        val (copied, setCopied) = useState { false }
        div {
            className = ClassName("keyline")
            span {
                className = ClassName(if (props.danger) "keyline-label danger" else "keyline-label")
                +props.label
            }
            code {
                className = ClassName("keyline-value")
                +props.value
            }
            button {
                className = ClassName("keyline-copy")
                title = if (copied) "Copied" else "Copy ${props.label}"
                onClick = {
                    if (props.value.isNotBlank()) copyToClipboard(props.value)
                    setCopied(true)
                    launchApp {
                        delay(1_500)
                        setCopied(false)
                    }
                }
                if (copied) icon(Ic.Check) else icon(Ic.ContentCopy)
            }
        }
    }
