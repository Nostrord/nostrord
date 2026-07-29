package org.nostr.nostrord.web.screens

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import org.nostr.nostrord.auth.pomegranate.pomegranateOperatorLabel
import org.nostr.nostrord.ui.screens.login.GoogleAccountSetup
import org.nostr.nostrord.ui.screens.login.LoginViewModel
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.copyToClipboard
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.noticeCard
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.code
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useState
import web.cssom.ClassName
import web.html.InputType
import web.html.checkbox

external interface GoogleAccountSetupPanelProps : Props {
    var vm: LoginViewModel

    /** Account creation in flight: everything is read-only until it finishes. */
    var busy: Boolean

    /** Primary button label (progress text while [busy]). */
    var createLabel: String

    var onCreate: () -> Unit
}

/**
 * "Create your account": the step the Google login stops at when that Google identity has
 * no pomegranate account yet. The key is generated client-side and shown here because the
 * shards are dealt from it — this is the only moment the whole key exists for the user to
 * back up. Operators and threshold sit behind Advanced options; the defaults are fine.
 * Mirrors the Compose GoogleAccountSetupSection.
 */
val GoogleAccountSetupPanel =
    FC<GoogleAccountSetupPanelProps> { props ->
        val vm = props.vm
        val setup = useStateFlow(vm.googleSetup)
        val (acknowledged, setAcknowledged) = useState { false }
        val (advanced, setAdvanced) = useState { false }
        val (copied, setCopied) = useState { false }
        val (draft, setDraft) = useState { "" }
        val (addError, setAddError) = useState<String?> { null }
        val (importing, setImporting) = useState { false }
        val (keyDraft, setKeyDraft) = useState { "" }
        val (keyError, setKeyError) = useState<String?> { null }
        // Probe on entry so a dead operator is visible before the user hits Create.
        useEffect(Unit) { vm.checkGoogleOperators() }
        if (setup == null) return@FC
        val busy = props.busy

        val useKey = {
            val error = vm.importGoogleKey(keyDraft)
            setKeyError(error)
            if (error == null) {
                setKeyDraft("")
                setImporting(false)
            }
        }

        val addOperator = { raw: String ->
            val error = vm.addGoogleOperator(raw)
            setAddError(error)
            if (error == null) setDraft("")
        }

        div {
            className = ClassName("wizard-title")
            +"Create your account"
        }

        p {
            className = ClassName("wizard-sub")
            +setup.introLine
        }

        // Everything about the key lives in one panel: the value, why it matters, and the
        // two ways to keep it. The rest of the step stays loose so this reads as the block
        // that needs attention.
        div {
            className = ClassName("key-panel")
            div {
                className = ClassName("key-panel-label")
                +"YOUR PRIVATE KEY"
            }
            div {
                className = ClassName("input-group sm")
                code {
                    className = ClassName("keyline-value")
                    +setup.nsec
                }
                button {
                    className = ClassName("keyline-copy")
                    title = if (copied) "Copied" else "Copy private key"
                    disabled = busy
                    onClick = {
                        copyToClipboard(setup.nsec)
                        setCopied(true)
                        launchApp {
                            delay(2_000)
                            setCopied(false)
                        }
                    }
                    if (copied) icon(Ic.Check) else icon(Ic.ContentCopy)
                }
                button {
                    className = ClassName("keyline-copy")
                    title = "Generate new key"
                    disabled = busy
                    onClick = { vm.regenerateGoogleKey() }
                    icon(Ic.Refresh)
                }
            }
            div {
                className = ClassName("key-warning")
                icon(Ic.Warning)
                span { +setup.keyWarning }
            }
            div {
                className = ClassName("key-actions")
                button {
                    className = ClassName("btn-secondary btn-sm")
                    disabled = busy
                    onClick = { downloadTextFile("nostr-private-key.txt", setup.nsec) }
                    icon(Ic.Download)
                    +"Download backup"
                }
                if (!importing) {
                    button {
                        className = ClassName("link-inline")
                        disabled = busy
                        onClick = { setImporting(true) }
                        icon(Ic.Key)
                        +"Use a key I already have"
                    }
                }
            }

            // Bringing an identity you already have: the shards are dealt from the pasted
            // key instead of the generated one, so the account keeps your pubkey.
            if (importing) {
                div {
                    className = ClassName("input-group sm key-import")
                    input {
                        className = ClassName("input")
                        placeholder = "nsec1... or 64-character hex"
                        value = keyDraft
                        disabled = busy
                        autoFocus = true
                        onChange = { event ->
                            setKeyDraft(event.currentTarget.value)
                            setKeyError(null)
                        }
                        onKeyDown = { event ->
                            if (event.key == "Enter") {
                                event.preventDefault()
                                useKey()
                            }
                        }
                    }
                    button {
                        className = ClassName("btn-secondary btn-sm")
                        disabled = busy || keyDraft.isBlank()
                        onClick = { useKey() }
                        +"Use key"
                    }
                }
                keyError?.let {
                    div {
                        className = ClassName("po-add-error")
                        +it
                    }
                }
            }
        }

        label {
            className = ClassName("ack-row")
            input {
                type = InputType.checkbox
                checked = acknowledged
                disabled = busy
                onChange = { event -> setAcknowledged(event.currentTarget.checked) }
            }
            span { +"I have safely backed up my private key" }
        }

        div {
            className = ClassName("advanced-section")
            div {
                className = ClassName("advanced-header")
                onClick = { setAdvanced(!advanced) }
                span {
                    className = ClassName(if (advanced) "advanced-chevron" else "advanced-chevron collapsed")
                    icon(Ic.ExpandMore)
                }
                span {
                    className = ClassName("advanced-title")
                    +"Advanced options"
                }
            }
            if (advanced) {
                div {
                    className = ClassName("po-section-label")
                    +"OPERATORS"
                }
                p {
                    className = ClassName("advanced-desc")
                    +"Independent servers that each hold a shard of your private key, so no single operator can sign on its own."
                }
                setup.operators.forEach { url ->
                    div {
                        key = url
                        className = ClassName("advanced-relay-row")
                        span {
                            className = ClassName("advanced-relay-url")
                            +pomegranateOperatorLabel(url)
                        }
                        when (setup.statusOf(url)) {
                            GoogleAccountSetup.OperatorStatus.Checking -> span { className = ClassName("btn-spinner") }
                            GoogleAccountSetup.OperatorStatus.Reachable -> {
                                span {
                                    className = ClassName("benefit-check")
                                    icon(Ic.Check)
                                }
                            }

                            GoogleAccountSetup.OperatorStatus.Unreachable -> {
                                span {
                                    className = ClassName("po-operator-down")
                                    +"not responding"
                                }
                            }

                            GoogleAccountSetup.OperatorStatus.Unknown -> {}
                        }
                        button {
                            className = ClassName("advanced-relay-remove")
                            title = "Remove operator"
                            disabled = busy || !setup.canRemoveOperator
                            onClick = { vm.removeGoogleOperator(url) }
                            icon(Ic.Close)
                        }
                    }
                }
                div {
                    className = ClassName("input-group sm advanced-add")
                    input {
                        className = ClassName("input")
                        placeholder = "Add operator URL"
                        value = draft
                        disabled = busy
                        onChange = { event ->
                            setDraft(event.currentTarget.value)
                            setAddError(null)
                        }
                        onKeyDown = { event ->
                            if (event.key == "Enter") {
                                event.preventDefault()
                                addOperator(draft)
                            }
                        }
                    }
                    button {
                        className = ClassName("btn-secondary btn-sm")
                        disabled = busy || draft.isBlank()
                        onClick = { addOperator(draft) }
                        +"Add"
                    }
                }
                addError?.let {
                    div {
                        className = ClassName("po-add-error")
                        +it
                    }
                }
                if (setup.recommendedOperators.isNotEmpty()) {
                    div {
                        className = ClassName("advanced-desc")
                        +"Recommended"
                    }
                    div {
                        className = ClassName("po-chips")
                        setup.recommendedOperators.forEach { url ->
                            button {
                                key = url
                                className = ClassName("btn-secondary btn-sm")
                                disabled = busy
                                onClick = { addOperator(url) }
                                icon(Ic.Add)
                                +pomegranateOperatorLabel(url)
                            }
                        }
                    }
                }

                div {
                    className = ClassName("po-section-label")
                    +"SIGNING THRESHOLD"
                }
                div {
                    className = ClassName("po-threshold")
                    button {
                        className = ClassName("btn-secondary po-step")
                        disabled = busy || !setup.canLowerThreshold
                        title = "Lower threshold"
                        onClick = { vm.setGoogleThreshold(setup.threshold - 1) }
                        icon(Ic.Remove)
                    }
                    span {
                        className = ClassName("po-threshold-value")
                        +setup.threshold.toString()
                    }
                    button {
                        className = ClassName("btn-secondary po-step")
                        disabled = busy || !setup.canRaiseThreshold
                        title = "Raise threshold"
                        onClick = { vm.setGoogleThreshold(setup.threshold + 1) }
                        icon(Ic.Add)
                    }
                    span {
                        className = ClassName("po-threshold-desc")
                        +"of ${setup.usableOperators.size} operators are enough to sign"
                    }
                }
            }
        }

        // Operator trouble is reported outside Advanced options, which is collapsed by
        // default: a quiet line while the account can still be created, a callout when it
        // cannot.
        if (setup.unreachableOperators.isNotEmpty()) {
            if (setup.tooFewOperators) {
                noticeCard(title = "Not enough operators are responding", body = setup.operatorWarning, alert = true)
            } else {
                div {
                    className = ClassName("po-operator-note")
                    +setup.operatorWarning
                    // Inline with the sentence it answers: on its own line it read as a
                    // stray action with no object.
                    button {
                        className = ClassName("link-inline")
                        disabled = busy
                        onClick = { vm.checkGoogleOperators() }
                        icon(Ic.Refresh)
                        +"Check operators again"
                    }
                }
            }
            if (setup.tooFewOperators) {
                button {
                    className = ClassName("link-inline")
                    disabled = busy
                    onClick = { vm.checkGoogleOperators() }
                    icon(Ic.Refresh)
                    +"Check operators again"
                }
            }
        }

        button {
            className = ClassName("btn-primary btn-lg btn-full login-submit")
            // Create waits for the probe: the operator set is decided by its verdicts.
            disabled = busy || !acknowledged || setup.tooFewOperators || setup.operatorsPending
            onClick = { props.onCreate() }
            if (busy || setup.operatorsPending) {
                span { className = ClassName("btn-spinner") }
            }
            +(if (!busy && setup.operatorsPending) "Checking operators…" else props.createLabel)
        }
    }

/** Saves [content] as a downloaded text file (data URL: the nsec is small and ASCII). */
private fun downloadTextFile(
    name: String,
    content: String,
) {
    val anchor = document.createElement("a")
    val a = anchor.asDynamic()
    a.href = "data:text/plain;charset=utf-8," + window.asDynamic().encodeURIComponent(content)
    a.download = name
    document.body?.appendChild(anchor)
    a.click()
    anchor.remove()
}
