package org.nostr.nostrord.web.screens

import org.nostr.nostrord.auth.pomegranate.PomegranateConfig
import org.nostr.nostrord.auth.pomegranate.PomegranatePopupClosedException
import org.nostr.nostrord.auth.pomegranate.PomegranateStatus
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.screens.login.LoginMethod
import org.nostr.nostrord.ui.screens.login.LoginViewModel
import org.nostr.nostrord.ui.screens.login.availableLoginMethods
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.GoogleLogo
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.formDivider
import org.nostr.nostrord.web.components.formError
import org.nostr.nostrord.web.components.formHint
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.iconInput
import org.nostr.nostrord.web.components.tabItem
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName
import web.html.InputType
import web.html.text

private enum class BunkerMode { Qr, Url }

/** Sprite icon for a shared [LoginMethod] (the signer app never reaches the web build). */
private val LoginMethod.ic: Ic
    get() =
        when (this) {
            LoginMethod.PrivateKey -> Ic.Key
            LoginMethod.Bunker -> Ic.Shield
            LoginMethod.Extension -> Ic.Extension
            LoginMethod.Signer -> Ic.Shield
            LoginMethod.Google -> Ic.Google
        }

/** One full-width row of the method list: icon, name, one-line explainer, chevron. */
private fun ChildrenBuilder.methodRow(
    method: LoginMethod,
    onClick: () -> Unit,
) {
    button {
        className = ClassName("login-method")
        this.onClick = { onClick() }
        span {
            className = ClassName("login-method-icon")
            icon(method.ic)
        }
        span {
            className = ClassName("login-method-text")
            span {
                className = ClassName("login-method-title")
                +method.title
            }
            span {
                className = ClassName("login-method-sub")
                +method.subtitle
            }
        }
        span {
            className = ClassName("login-method-chevron")
            icon(Ic.ChevronRight)
        }
    }
}

private fun googleStatusLabel(status: PomegranateStatus?): String = when (status) {
    PomegranateStatus.WaitingForGoogle -> "Waiting for Google sign-in…"
    PomegranateStatus.Checking -> "Checking your account…"
    PomegranateStatus.Creating -> "Setting up your secure account…"
    PomegranateStatus.Connecting -> "Connecting…"
    null -> "Continue with Google"
}

private fun ChildrenBuilder.benefit(text: String) {
    div {
        className = ClassName("benefit")
        span {
            className = ClassName("benefit-check")
            icon(Ic.Check)
        }
        span { +text }
    }
}

external interface LoginMethodsProps : Props {
    /** Submit button label (e.g. "Login" / "Add account"). */
    var submitLabel: String

    /** Busy-state button label (e.g. "Logging in…" / "Adding…"). */
    var busyLabel: String

    /**
     * Called after a successful auth action. On the login page this is a no-op (the auth
     * gate swaps the screen when repo.isLoggedIn flips); in the add-account modal it
     * closes the modal. The new account is warm-swapped active either way.
     */
    var onSuccess: () -> Unit
}

/**
 * The credential picker shared by the login page and the add-account modal: a list of the
 * available [LoginMethod]s, then the chosen method's form behind a back header. "Generate
 * New Key" sits at the list level because creating an identity is a separate path from
 * presenting one. Owns the method + busy + error state and reuses [KeyLoginForm] /
 * [BunkerQr] so the two entry points stay identical. Login and add-account call the same
 * LoginViewModel methods; the difference is only [onSuccess]. Mirrors the Compose
 * LoginMethods.
 */
val LoginMethods =
    FC<LoginMethodsProps> { props ->
        val vm = useViewModel { LoginViewModel(AppModule.nostrRepository) }
        val methods = availableLoginMethods()
        // null shows the method list; a value shows that method's form
        val (method, setMethod) = useState<LoginMethod?> { null }
        // Entered through "Generate New Key": the private key form opens on the wizard
        val (generating, setGenerating) = useState { false }
        val (bunkerMode, setBunkerMode) = useState { BunkerMode.Qr }
        val (bunkerUrl, setBunkerUrl) = useState { "" }
        val (busy, setBusy) = useState { false }
        val (error, setError) = useState<String?> { null }
        val (googleStatus, setGoogleStatus) = useState<PomegranateStatus?> { null }
        val (googleCentral, setGoogleCentral) = useState { PomegranateConfig.CENTRAL_URL }
        val (googleAdvanced, setGoogleAdvanced) = useState { false }

        // Non-null once a Google sign-in finds no account: the setup panel replaces the tab.
        val googleSetup = useStateFlow(vm.googleSetup)

        // Terminal callback of both Google phases (direct login and account creation).
        fun finishGoogleLogin(result: Result<Unit>) {
            setBusy(false)
            setGoogleStatus(null)
            val err = result.exceptionOrNull()
            when {
                err == null -> props.onSuccess()
                // User dismissed the popup: a cancel, not an error.
                err is PomegranatePopupClosedException -> {}
                else -> setError(err.message ?: "Google login failed")
            }
        }

        // Run a VM auth action. The VM launches on its own scope, so we just react to the
        // callback: success calls onSuccess, failure surfaces the error string.
        fun run(start: ((Result<Unit>) -> Unit) -> Unit) {
            setError(null)
            setBusy(true)
            start { result ->
                setBusy(false)
                val err = result.exceptionOrNull()
                if (err == null) props.onSuccess() else setError(err.message ?: "Login failed")
            }
        }

        if (method == null) {
            div {
                className = ClassName("login-method-list")
                methods.forEach { m ->
                    methodRow(m) {
                        setGenerating(false)
                        setMethod(m)
                    }
                }
            }
            formDivider()
            button {
                className = ClassName("btn-secondary btn-lg btn-full")
                onClick = {
                    setGenerating(true)
                    setMethod(LoginMethod.PrivateKey)
                }
                icon(Ic.AutoAwesome)
                +"Generate New Key"
            }
            return@FC
        }

        button {
            className = ClassName("login-back")
            onClick = {
                setError(null)
                setMethod(null)
            }
            icon(Ic.ArrowBack)
            icon(method.ic)
            span { +method.title }
        }

        div {
            className = ClassName("login-tab-content")
            formError(error)
            when (method) {
                LoginMethod.PrivateKey -> {
                    KeyLoginForm {
                        this.vm = vm
                        this.busy = busy
                        this.startInWizard = generating
                        onBack = { setMethod(null) }
                        submitLabel = props.submitLabel
                        busyLabel = props.busyLabel
                        onSubmit = { input, password, isNewIdentity ->
                            run { cb ->
                                vm.loginWithPrivateKeyInput(
                                    input,
                                    password = password,
                                    isNewIdentity = isNewIdentity,
                                    onResult = cb,
                                )
                            }
                        }
                        onSubmitProtected = { input, password, isNewIdentity ->
                            run { cb ->
                                vm.loginProtected(
                                    input,
                                    password,
                                    isNewIdentity = isNewIdentity,
                                    onResult = cb,
                                )
                            }
                        }
                    }
                }

                LoginMethod.Bunker -> {
                    div {
                        className = ClassName("bunker-desc")
                        icon(Ic.Shield)
                        span { +"Connect to a remote signer for secure key management" }
                    }
                    div {
                        className = ClassName("bunker-toggle")
                        tabItem(bunkerMode == BunkerMode.Qr, Ic.QrCode, "QR Code") { setBunkerMode(BunkerMode.Qr) }
                        tabItem(bunkerMode == BunkerMode.Url, Ic.Keyboard, "Bunker URL") { setBunkerMode(BunkerMode.Url) }
                    }
                    when (bunkerMode) {
                        BunkerMode.Qr -> BunkerQr { onSuccess = { props.onSuccess() } }
                        BunkerMode.Url -> {
                            val submitBunker = {
                                if (bunkerUrl.isNotBlank() && !busy) {
                                    run { cb -> vm.loginWithBunker(bunkerUrl, onResult = cb) }
                                }
                            }
                            iconInput(
                                ic = Ic.Link,
                                type = InputType.text,
                                placeholder = "bunker://<pubkey>?relay=wss://...",
                                value = bunkerUrl,
                                onChange = { setBunkerUrl(it) },
                                onEnter = { submitBunker() },
                            )
                            formHint("Get your bunker URL from nsec.app, Amber, or other NIP-46 signers")
                            button {
                                className = ClassName("btn-primary btn-lg btn-full login-submit")
                                disabled = bunkerUrl.isBlank() || busy
                                onClick = { submitBunker() }
                                if (busy) {
                                    span { className = ClassName("btn-spinner") }
                                }
                                +(if (busy) "Connecting…" else "Connect to Bunker")
                            }
                        }
                    }
                    div {
                        className = ClassName("bunker-benefits")
                        div {
                            className = ClassName("benefits-head")
                            icon(Ic.Lock)
                            span {
                                className = ClassName("benefits-title")
                                +"Why use a Bunker?"
                            }
                        }
                        benefit("Your private key never leaves the signer")
                        benefit("Approve each signing request")
                        benefit("Works with hardware signers")
                        benefit("Revoke access anytime")
                    }
                }

                LoginMethod.Extension -> {
                    div {
                        className = ClassName("ext-content")
                        span {
                            className = ClassName("ext-icon")
                            icon(Ic.Extension)
                        }
                        div {
                            className = ClassName("ext-title")
                            +"Browser Extension Login"
                        }
                        p {
                            className = ClassName("ext-desc")
                            +"Connect using a NIP-07 compatible extension such as nos2x or Nostrame."
                        }
                        button {
                            className = ClassName("btn-primary btn-lg btn-full")
                            disabled = busy
                            onClick = { run { cb -> vm.loginWithNip07Extension(onResult = cb) } }
                            if (busy) {
                                span { className = ClassName("btn-spinner") }
                            }
                            +(if (busy) "Connecting…" else "Connect Extension")
                        }
                    }
                }

                // "Login with Google" (pomegranate threshold signer), web-only.
                // The whole flow runs in the VM; this tab only reflects its status. A Google
                // identity with no account yet stops at the setup panel to back up its key.
                LoginMethod.Google -> if (googleSetup != null) {
                    GoogleAccountSetupPanel {
                        this.vm = vm
                        this.busy = busy
                        this.createLabel = if (busy) googleStatusLabel(googleStatus) else "Create account"
                        onCreate = {
                            if (!busy) {
                                setError(null)
                                setBusy(true)
                                setGoogleStatus(PomegranateStatus.Creating)
                                vm.createGoogleAccount(
                                    onStatus = { setGoogleStatus(it) },
                                    onResult = { result -> finishGoogleLogin(result) },
                                )
                            }
                        }
                    }
                } else {
                    div {
                        className = ClassName("ext-content")
                        span {
                            className = ClassName("ext-icon")
                            GoogleLogo()
                        }
                        div {
                            className = ClassName("ext-title")
                            +"Login with Google"
                        }
                        p {
                            className = ClassName("ext-desc")
                            +"Sign in with your Google account. First time here? A Nostr key is created for you automatically, nothing to install or back up."
                        }
                        button {
                            className = ClassName("btn-primary btn-lg btn-full")
                            disabled = busy || googleCentral.isBlank()
                            onClick = {
                                if (!busy && googleCentral.isNotBlank()) {
                                    setError(null)
                                    setBusy(true)
                                    vm.loginWithGoogle(
                                        centralUrl = googleCentral,
                                        onStatus = { setGoogleStatus(it) },
                                        // New account: the setup panel takes over, so drop the busy state.
                                        onNewAccount = {
                                            setBusy(false)
                                            setGoogleStatus(null)
                                        },
                                        onResult = { result -> finishGoogleLogin(result) },
                                    )
                                }
                            }
                            if (googleStatus != null) {
                                span { className = ClassName("btn-spinner") }
                            }
                            +googleStatusLabel(googleStatus)
                        }

                        div {
                            className = ClassName("bunker-benefits")
                            div {
                                className = ClassName("benefits-head")
                                icon(Ic.Lock)
                                span {
                                    className = ClassName("benefits-title")
                                    +"How it works"
                                }
                            }
                            benefit("Your key is split into shards held by independent operators")
                            benefit("No single server ever holds the whole key")
                            benefit("Google only proves who you are, it never touches your key")
                            benefit("You can export the full key (nsec) whenever you want")
                        }

                        // Advanced: swap the central server (self-hosted promenade). Same
                        // collapsed-by-default pattern as the bunker QR's signer relays.
                        div {
                            className = ClassName("advanced-section")
                            div {
                                className = ClassName("advanced-header")
                                onClick = { setGoogleAdvanced(!googleAdvanced) }
                                span {
                                    className =
                                        ClassName(if (googleAdvanced) "advanced-chevron" else "advanced-chevron collapsed")
                                    icon(Ic.ExpandMore)
                                }
                                span {
                                    className = ClassName("advanced-title")
                                    +"Advanced options"
                                }
                            }
                            if (googleAdvanced) {
                                p {
                                    className = ClassName("advanced-desc")
                                    +"Central server: checks your Google sign-in and forwards each signing request to the key operators. Change it to use a self-hosted one."
                                }
                                iconInput(
                                    ic = Ic.Public,
                                    type = InputType.text,
                                    placeholder = PomegranateConfig.CENTRAL_URL,
                                    value = googleCentral,
                                    onChange = { setGoogleCentral(it) },
                                )
                            }
                        }
                    }
                }

                // Android-only; availableLoginMethods() never offers it on the web.
                LoginMethod.Signer -> {}
            }
        }
    }
