package org.nostr.nostrord.web.screens

import org.nostr.nostrord.auth.AuthMethod
import org.nostr.nostrord.auth.logoutConfirmBody
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.outbox.Nip65Relay
import org.nostr.nostrord.network.outbox.RelayListManager
import org.nostr.nostrord.network.upload.MediaUploadService
import org.nostr.nostrord.network.upload.mediaServerDisplayName
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.notifications.NotificationPermission
import org.nostr.nostrord.notifications.notificationSoundSuppression
import org.nostr.nostrord.notifications.playNotificationSound
import org.nostr.nostrord.settings.AppTheme
import org.nostr.nostrord.settings.NotificationLevel
import org.nostr.nostrord.ui.Identifier
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.navigation.UserRoute
import org.nostr.nostrord.ui.screens.backup.BackupViewModel
import org.nostr.nostrord.ui.screens.backup.MIN_BACKUP_PASSWORD
import org.nostr.nostrord.ui.screens.backup.backupSecurityTips
import org.nostr.nostrord.ui.screens.profile.EditProfileViewModel
import org.nostr.nostrord.ui.screens.settings.DmRelaySettingsViewModel
import org.nostr.nostrord.ui.screens.settings.MutedGroupsViewModel
import org.nostr.nostrord.ui.screens.settings.MutedUsersViewModel
import org.nostr.nostrord.ui.screens.settings.SecurityViewModel
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.isValidRelayUrl
import org.nostr.nostrord.utils.toRelayUrl
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.AvatarKind
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.IdentifierRow
import org.nostr.nostrord.web.components.UploadButton
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.useEscClose
import org.nostr.nostrord.web.navigation.pushRoute
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.option
import react.dom.html.ReactHTML.select
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.textarea
import react.useEffect
import react.useState
import web.cssom.ClassName
import web.html.InputType
import web.html.checkbox
import web.html.password

external interface SettingsScreenProps : Props {
    var onClose: () -> Unit

    /**
     * Log Out clicked. The shell decides whether to show the account chooser
     * (multi-account case) or fall through to a direct logout — Settings only
     * has to close itself + delegate.
     */
    var onLogoutWithChoice: () -> Unit
}

private val sections =
    listOf("Profile", "Backup Keys", "Relays (NIP-65)", "Direct Messages", "Appearance", "Media", "Notifications", "Muted users", "Muted groups", "Security")

/**
 * Settings — real port of the Compose SettingsScreen: a full-screen overlay with a section
 * nav + Log Out, a content pane per section, and the close/ESC button. Panels read/write
 * the real repository, account store, notification settings and feature flags.
 */
val SettingsScreen =
    FC<SettingsScreenProps> { props ->
        val (active, setActive) = useState { "Profile" }
        // Confirm dialog state for the Log Out item — mirrors native
        // showLogoutConfirm in SettingsScreen.kt:492. Tap "Log Out" opens it
        // before delegating to props.onLogoutWithChoice, so the user has a
        // pause + method-tailored body before the account chooser / logout
        // path runs.
        val (logoutConfirmOpen, setLogoutConfirmOpen) = useState { false }
        val accounts = useStateFlow(AppModule.accountStore.accounts)
        val activeAccountId = useStateFlow(AppModule.accountStore.activeId)
        val activeAuthMethod =
            accounts.firstOrNull { it.id == activeAccountId }?.authMethod ?: AuthMethod.LOCAL
        // Mobile: false = section list, true = selected panel (with a back button). Ignored on desktop.
        val (mobilePanel, setMobilePanel) = useState { false }
        // Esc closes the settings overlay (parity with native's Escape handler). While the
        // Log Out confirm dialog is open it owns Esc, so closing it doesn't also drop settings.
        useEscClose { if (logoutConfirmOpen) setLogoutConfirmOpen(false) else props.onClose() }

        div {
            className = ClassName(if (mobilePanel) "settings-overlay show-panel" else "settings-overlay")

            div { className = ClassName("settings-fill dark") }

            div {
                className = ClassName("settings-sidebar")
                // Mobile-only list header with title + round close (CSS hides it on desktop).
                div {
                    className = ClassName("settings-mobile-header")
                    span {
                        className = ClassName("settings-mobile-title")
                        +"Settings"
                    }
                    button {
                        className = ClassName("settings-mobile-close")
                        onClick = { props.onClose() }
                        icon(Ic.Close)
                    }
                }
                sections.forEach { section ->
                    div {
                        key = section
                        className = ClassName(if (section == active) "settings-nav-item active" else "settings-nav-item")
                        onClick = {
                            setActive(section)
                            setMobilePanel(true)
                        }
                        span {
                            className = ClassName("settings-nav-label")
                            +section
                        }
                        span {
                            className = ClassName("settings-nav-chevron")
                            icon(Ic.ChevronRight)
                        }
                    }
                }
                div { className = ClassName("settings-nav-divider") }
                div {
                    className = ClassName("settings-nav-item danger")
                    onClick = { setLogoutConfirmOpen(true) }
                    span {
                        className = ClassName("settings-nav-label")
                        +"Log Out"
                    }
                }
            }

            div {
                className = ClassName("settings-content")
                // Mobile-only back row (CSS hides it on desktop) — returns to the section list.
                button {
                    className = ClassName("settings-back")
                    onClick = { setMobilePanel(false) }
                    icon(Ic.ArrowBack)
                    +active
                }
                // Desktop-only panel title with a divider (mirrors native; mobile uses the back row).
                div {
                    className = ClassName("settings-content-title")
                    +active
                }
                when (active) {
                    "Profile" -> ProfilePanel()
                    "Backup Keys" -> BackupPanel()
                    "Relays (NIP-65)" -> RelaysPanel()
                    "Direct Messages" -> DmRelaysPanel()
                    "Appearance" -> AppearancePanel()
                    "Media" -> MediaPanel()
                    "Notifications" -> NotificationsPanel()
                    "Muted users" -> MutedUsersPanel()
                    "Muted groups" -> MutedGroupsPanel()
                    "Security" -> SecurityPanel()
                }
            }

            div {
                className = ClassName("settings-close-col")
                button {
                    className = ClassName("settings-close")
                    onClick = { props.onClose() }
                    span {
                        className = ClassName("settings-close-x")
                        icon(Ic.Close)
                    }
                }
            }

            div { className = ClassName("settings-fill light") }

            // Log-out confirmation. Mirrors native AlertDialog (SettingsScreen.kt:494),
            // body string comes from the shared logoutConfirmBody helper so the
            // wording adapts to the user's auth method consistently across platforms.
            if (logoutConfirmOpen) {
                div {
                    className = ClassName("modal-overlay")
                    onClick = { setLogoutConfirmOpen(false) }
                    div {
                        className = ClassName("modal-card sm")
                        onClick = { it.stopPropagation() }
                        div {
                            className = ClassName("modal-title")
                            +"Log out?"
                        }
                        div {
                            className = ClassName("modal-subtitle tight")
                            +logoutConfirmBody(activeAuthMethod)
                        }
                        div {
                            className = ClassName("modal-footer")
                            button {
                                className = ClassName("btn-text")
                                onClick = { setLogoutConfirmOpen(false) }
                                +"Cancel"
                            }
                            button {
                                className = ClassName("btn-danger")
                                onClick = {
                                    setLogoutConfirmOpen(false)
                                    props.onLogoutWithChoice()
                                }
                                +"Log Out"
                            }
                        }
                    }
                }
            }
        }
    }

// ── Profile ──────────────────────────────────────────────────────────────────

private val ProfilePanel =
    FC<Props> {
        val vm = useViewModel { EditProfileViewModel(AppModule.nostrRepository) }
        val pubkey = vm.getPublicKey()
        val userMetadata = useStateFlow(vm.userMetadata)
        val meta = pubkey?.let { userMetadata[it] }

        val (name, setName) = useState { meta?.displayName ?: meta?.name ?: "" }
        val (about, setAbout) = useState { meta?.about ?: "" }
        val (picture, setPicture) = useState { meta?.picture ?: "" }
        val (banner, setBanner) = useState { meta?.banner ?: "" }
        val (nip05, setNip05) = useState { meta?.nip05 ?: "" }
        val (lud16, setLud16) = useState { meta?.lud16 ?: "" }
        val (website, setWebsite) = useState { meta?.website ?: "" }
        val (busy, setBusy) = useState { false }
        val (uploadError, setUploadError) = useState<String?> { null }
        val (saved, setSaved) = useState { false }

        div {
            className = ClassName("settings-card center")
            WebAvatar {
                url = picture.ifBlank { null }
                seed = pubkey
                this.name = name.ifBlank { "U" }
                cls = "settings-avatar"
            }
            div {
                className = ClassName("settings-avatar-caption")
                +"Avatar Preview"
            }
        }
        div {
            className = ClassName("settings-card")
            div {
                className = ClassName("settings-section-head")
                +"PROFILE INFORMATION"
            }
            settingsField("Name", "Your name", name) {
                setName(it)
                setSaved(false)
            }
            settingsTextarea("About", "Tell us about yourself", about) { setAbout(it) }
            settingsUploadField("Avatar URL", "https://example.com/avatar.jpg", picture, { setPicture(it) }) { setUploadError(it) }
            settingsUploadField("Banner URL", "https://example.com/banner.jpg", banner, { setBanner(it) }) { setUploadError(it) }
            uploadError?.let { err ->
                div {
                    className = ClassName("settings-status-line error")
                    +err
                }
            }
            settingsField("Nostr Address (NIP-05)", "you@example.com", nip05) { setNip05(it) }
            settingsField("Lightning Address", "you@walletofsatoshi.com", lud16) { setLud16(it) }
            settingsField("Website", "https://example.com", website) { setWebsite(it) }
            if (saved) {
                div {
                    className = ClassName("settings-status-line")
                    +"Profile updated successfully"
                }
            }
            div {
                className = ClassName("settings-form-actions")
                button {
                    className = ClassName("settings-save")
                    disabled = busy
                    onClick = {
                        setBusy(true)
                        vm.saveProfile(
                            displayName = name.trim().ifBlank { null },
                            name = name.trim().ifBlank { null },
                            about = about.trim().ifBlank { null },
                            picture = picture.trim().ifBlank { null },
                            banner = banner.trim().ifBlank { null },
                            nip05 = nip05.trim().ifBlank { null },
                            lud16 = lud16.trim().ifBlank { null },
                            website = website.trim().ifBlank { null },
                        ) { result ->
                            setBusy(false)
                            if (result.isSuccess) setSaved(true)
                        }
                    }
                    +(if (busy) "Saving…" else "Save")
                }
            }
        }
    }

// ── Backup keys ──────────────────────────────────────────────────────────────

private val BackupPanel =
    FC<Props> {
        val vm = useViewModel { BackupViewModel() }
        val revealed = useStateFlow(vm.revealed)

        // Public key: non-sensitive, shown immediately, cycles npub / nprofile / hex with a QR.
        div {
            className = ClassName("settings-card")
            div {
                className = ClassName("field-label")
                +"Public key"
            }
            IdentifierRow {
                ids = vm.publicIds
                showQr = true
            }
        }

        // Private key: LOCAL accounts only, reveal-gated. Bunker / NIP-07 hold no local key.
        if (vm.canExportPrivate) {
            div {
                className = ClassName("settings-card")
                div {
                    className = ClassName("field-label danger")
                    +"Private key"
                }
                if (!revealed) {
                    div {
                        className = ClassName("settings-key danger")
                        +"nsec1••••••••••••••••••••••••••••••••••••"
                    }
                    button {
                        className = ClassName("settings-outline-btn danger")
                        onClick = { vm.reveal() }
                        +"Reveal private key"
                    }
                } else {
                    IdentifierRow { ids = vm.privateDirectIds() }
                    EncryptedBackupSubsection { this.vm = vm }

                    div {
                        className = ClassName("backup-footer")
                        button {
                            className = ClassName("btn-text")
                            onClick = { vm.hide() }
                            +"Hide private key"
                        }
                    }
                }
            }
        } else if (vm.pomegranateCentral != null) {
            PomegranateKeySection { this.vm = vm }
        } else {
            div {
                className = ClassName("settings-card")
                div {
                    className = ClassName("field-label")
                    +"Private key"
                }
                div {
                    className = ClassName("settings-tip")
                    +when {
                        vm.pomegranateDisconnected ->
                            "This account was disconnected from its Google signer, so it can read but no longer sign. " +
                                "If you exported the nsec, log out and sign in with it via the Private key option to keep using it."
                        vm.authMethod == AuthMethod.BUNKER -> "Your private key stays in your bunker (NIP-46) and is never exposed here."
                        vm.authMethod == AuthMethod.NIP07 -> "Your private key stays in your browser extension (NIP-07) and is never exposed here."
                        else -> "No private key is available for this account."
                    }
                }
            }
        }

        div {
            className = ClassName("settings-card warning")
            div {
                className = ClassName("settings-section-head")
                +"SECURITY TIPS"
            }
            backupSecurityTips.forEach { tip ->
                div {
                    className = ClassName("settings-tip")
                    +"• $tip"
                }
            }
        }
    }

external interface EncryptedBackupProps : Props {
    var vm: BackupViewModel
}

/**
 * ncryptsec subsection (.backup-subsection object): password-encrypts the revealed
 * key into a NIP-49 backup. Shared by the local-key panel and the pomegranate export.
 */
val EncryptedBackupSubsection =
    FC<EncryptedBackupProps> { props ->
        val vm = props.vm
        val passphrase = useStateFlow(vm.passphrase)
        val ncryptsec = useStateFlow(vm.ncryptsec)
        val encrypting = useStateFlow(vm.encrypting)
        val error = useStateFlow(vm.error)

        div {
            className = ClassName("backup-subsection")
            div {
                className = ClassName("field-label")
                +"Encrypted backup (ncryptsec)"
            }
            if (ncryptsec == null) {
                div {
                    className = ClassName("backup-encrypt-row")
                    input {
                        className = ClassName("modal-input")
                        type = InputType.password
                        placeholder = "Choose a password"
                        value = passphrase
                        onChange = { event -> vm.setPassphrase(event.currentTarget.value) }
                        onKeyDown = { event ->
                            if (event.key == "Enter" && passphrase.length >= MIN_BACKUP_PASSWORD && !encrypting) {
                                vm.encrypt()
                            }
                        }
                    }
                    button {
                        className = ClassName("btn-primary")
                        disabled = encrypting || passphrase.length < MIN_BACKUP_PASSWORD
                        onClick = { vm.encrypt() }
                        +(if (encrypting) "Encrypting…" else "Encrypt")
                    }
                }
                error?.let {
                    div {
                        className = ClassName("settings-error")
                        +it
                    }
                }
                div {
                    className = ClassName("settings-tip")
                    +"Keep this password safe. Without it the encrypted backup cannot be recovered."
                }
            } else {
                IdentifierRow { ids = listOf(Identifier("ncryptsec", ncryptsec)) }
                button {
                    className = ClassName("btn-text backup-link")
                    onClick = { vm.setPassphrase("") }
                    +"Use a different password"
                }
            }
        }
    }

// ── Relays (NIP-65) ──────────────────────────────────────────────────────────

private fun defaultRelays(): List<Nip65Relay> = RelayListManager.DEFAULT_FALLBACK_RELAYS.map { Nip65Relay(it) }

private val RelaysPanel =
    FC<Props> {
        val repo = AppModule.nostrRepository
        val loaded = useStateFlow(repo.userRelayList)
        val usingDefaults = loaded.isEmpty()

        // Editable copy; reseeds when the server list first populates (or clears).
        val (relays, setRelays) = useState { if (loaded.isEmpty()) defaultRelays() else loaded }
        useEffect(loaded.isEmpty()) {
            setRelays(if (loaded.isEmpty()) defaultRelays() else loaded)
        }

        val (newUrl, setNewUrl) = useState { "" }
        val (newRead, setNewRead) = useState { true }
        val (newWrite, setNewWrite) = useState { true }
        val (busy, setBusy) = useState { false }
        val (status, setStatus) = useState<String?> { null }

        // Same canAdd contract as native RelayNip65PanelContent: valid URL,
        // at least one of R/W marked, and not already in the list. Drives both
        // the Enter-key shortcut and the Add button's disabled state below.
        val normalizedNewUrl = newUrl.trim().toRelayUrl()
        val canAddRelay =
            isValidRelayUrl(normalizedNewUrl) &&
                (newRead || newWrite) &&
                relays.none { it.url == normalizedNewUrl }

        fun addRelay() {
            if (!canAddRelay) return
            setRelays(relays + Nip65Relay(normalizedNewUrl, newRead, newWrite))
            setNewUrl("")
        }

        // Info card
        div {
            className = ClassName("settings-card")
            div {
                className = ClassName("settings-info-text")
                +(
                    "NIP-65 relay list (kind 10002) is where other clients find your profile and your joined " +
                        "groups list (kind 10009). Write relays are where you publish; read relays are for " +
                        "cross-network discoverability. Group messages are separate. They live on each group's relay."
                    )
            }
        }

        // Using-defaults warning
        if (usingDefaults) {
            div {
                className = ClassName("settings-warn-card")
                icon(Ic.Warning, "ico settings-warn-ico")
                div {
                    className = ClassName("settings-warn-text")
                    div {
                        className = ClassName("settings-warn-title")
                        +"Using default relays"
                    }
                    div {
                        className = ClassName("settings-warn-body")
                        +"No relay list (kind 10002) was found for this account. Publish your relay list so others can find your profile and groups."
                    }
                }
            }
        }

        // Relay list + add
        div {
            className = ClassName("settings-card")
            div {
                className = ClassName("settings-section-head")
                +"YOUR RELAYS"
            }
            relays.forEachIndexed { i, relay ->
                div {
                    key = relay.url
                    className = ClassName("relay-row")
                    span {
                        className = ClassName("relay-row-url")
                        +relay.url.removePrefix("wss://").removePrefix("ws://")
                    }
                    relayChip("R", relay.read) {
                        setRelays(relays.mapIndexed { j, r -> if (j == i) r.copy(read = !r.read) else r })
                    }
                    relayChip("W", relay.write) {
                        setRelays(relays.mapIndexed { j, r -> if (j == i) r.copy(write = !r.write) else r })
                    }
                    button {
                        className = ClassName("relay-row-remove")
                        onClick = { setRelays(relays.filterIndexed { j, _ -> j != i }) }
                        icon(Ic.Close)
                    }
                }
            }
            if (relays.none { it.read }) {
                div {
                    className = ClassName("relay-warn")
                    +"No read relay. Cross-network discoverability will be limited."
                }
            }
            if (relays.none { it.write }) {
                div {
                    className = ClassName("relay-warn")
                    +"No write relay. Your profile and joined groups list won't be discoverable."
                }
            }

            div {
                className = ClassName("settings-section-head relay-add-head")
                +"ADD RELAY"
            }
            input {
                className = ClassName("modal-input")
                placeholder = "relay.example.com"
                value = newUrl
                onChange = { event -> setNewUrl(event.currentTarget.value) }
                onKeyDown = { event ->
                    if (event.key == "Enter") {
                        event.preventDefault()
                        addRelay()
                    }
                }
            }
            div {
                className = ClassName("relay-add-row")
                label {
                    className = ClassName("relay-check")
                    input {
                        type = InputType.checkbox
                        checked = newRead
                        onChange = { event -> setNewRead(event.currentTarget.checked) }
                    }
                    +"Read"
                }
                label {
                    className = ClassName("relay-check")
                    input {
                        type = InputType.checkbox
                        checked = newWrite
                        onChange = { event -> setNewWrite(event.currentTarget.checked) }
                    }
                    +"Write"
                }
                button {
                    className = ClassName("relay-add-btn")
                    disabled = !canAddRelay
                    onClick = { addRelay() }
                    icon(Ic.Add)
                    +"Add"
                }
            }
        }

        // Save & Publish
        div {
            className = ClassName("relay-save-row")
            status?.let {
                span {
                    className = ClassName("relay-save-status")
                    +it
                }
            }
            button {
                className = ClassName("settings-save")
                disabled = busy
                onClick = {
                    setBusy(true)
                    setStatus(null)
                    launchApp {
                        val result = repo.publishRelayList(relays)
                        setBusy(false)
                        setStatus(if (result is Result.Success) "Relay list published" else "Failed to publish")
                    }
                }
                +(if (busy) "Publishing…" else "Save & Publish")
            }
        }
    }

// ── Direct message relays (NIP-17 kind:10050) ────────────────────────────────

private val DmRelaysPanel =
    FC<Props> {
        val dm = AppModule.dmSettings
        val dmEnabled = useStateFlow(dm.dmEnabled)
        val dmVm = useViewModel { DmRelaySettingsViewModel(AppModule.nostrRepository) }
        val published = useStateFlow(dmVm.relays)
        val saving = useStateFlow(dmVm.saving)
        val error = useStateFlow(dmVm.error)

        // Editable draft; reseeds when the published list first populates (or changes).
        val (relays, setRelays) = useState { published }
        useEffect(published) { setRelays(published) }

        val (newUrl, setNewUrl) = useState { "" }
        val normalizedNewUrl = newUrl.trim().toRelayUrl()
        val canAdd = isValidRelayUrl(normalizedNewUrl) && relays.none { it == normalizedNewUrl }

        fun addRelay() {
            if (!canAdd) return
            setRelays(relays + normalizedNewUrl)
            setNewUrl("")
        }

        div {
            className = ClassName("settings-card")
            settingsToggle(
                "Direct messages",
                "Send and receive private messages. When off, the app stops fetching and " +
                    "decrypting your DMs and hides all direct-message features.",
                dmEnabled,
            ) { dm.setDmEnabled(!dmEnabled) }
        }

        // Relay editor only matters while DMs are on; hidden with the rest of the feature when off.
        if (dmEnabled) {
            div {
                className = ClassName("settings-card")
                div {
                    className = ClassName("settings-info-text")
                    +(
                        "NIP-17 DM relays (kind 10050) tell other clients where to deliver your private " +
                            "direct messages. Pick a few reliable relays. They can be the same as your NIP-65 " +
                            "relays or dedicated ones. Until you publish a list, sensible defaults are used."
                        )
                }
            }

            div {
                className = ClassName("settings-card")
                div {
                    className = ClassName("settings-section-head")
                    +"YOUR DM RELAYS"
                }
                if (relays.isEmpty()) {
                    div {
                        className = ClassName("relay-warn")
                        +"No DM relays. Others won't be able to reach you. Add at least one."
                    }
                }
                relays.forEachIndexed { i, url ->
                    div {
                        key = url
                        className = ClassName("relay-row")
                        span {
                            className = ClassName("relay-row-url")
                            +url.removePrefix("wss://").removePrefix("ws://")
                        }
                        button {
                            className = ClassName("relay-row-remove")
                            onClick = { setRelays(relays.filterIndexed { j, _ -> j != i }) }
                            icon(Ic.Close)
                        }
                    }
                }

                div {
                    className = ClassName("settings-section-head relay-add-head")
                    +"ADD RELAY"
                }
                input {
                    className = ClassName("modal-input")
                    placeholder = "relay.example.com"
                    value = newUrl
                    onChange = { event -> setNewUrl(event.currentTarget.value) }
                    onKeyDown = { event ->
                        if (event.key == "Enter") {
                            event.preventDefault()
                            addRelay()
                        }
                    }
                }
                div {
                    className = ClassName("relay-add-row")
                    button {
                        className = ClassName("relay-add-btn")
                        disabled = !canAdd
                        onClick = { addRelay() }
                        icon(Ic.Add)
                        +"Add"
                    }
                }
            }

            div {
                className = ClassName("relay-save-row")
                error?.let {
                    span {
                        className = ClassName("relay-save-status")
                        +it
                    }
                }
                button {
                    className = ClassName("settings-save")
                    disabled = saving
                    onClick = { dmVm.publish(relays) }
                    +(if (saving) "Publishing…" else "Save & Publish")
                }
            }
        }
    }

private fun react.ChildrenBuilder.relayChip(label: String, active: Boolean, onToggle: () -> Unit) {
    button {
        className = ClassName(if (active) "relay-chip on" else "relay-chip")
        onClick = { onToggle() }
        +label
    }
}

// ── Muted users ──────────────────────────────────────────────────────────────

/**
 * The account's NIP-51 kind:10000 mute list with an Unmute action per user.
 * Compose parity: MutedUsersPanelContent.kt.
 */
private val MutedUsersPanel =
    FC<Props> {
        val vm = useViewModel { MutedUsersViewModel(AppModule.nostrRepository) }
        val muted = useStateFlow(vm.muted)
        val userMetadata = useStateFlow(vm.userMetadata)

        div {
            className = ClassName("settings-card")
            div {
                className = ClassName("settings-info-text")
                +(
                    "Muted users don't appear in chats, direct messages, or notifications. " +
                        "Mutes are stored encrypted (nobody can see who you muted) and sync to your other Nostr clients."
                    )
            }
        }

        div {
            className = ClassName("settings-card")
            div {
                className = ClassName("settings-section-head")
                +"MUTED USERS"
            }
            if (muted.isEmpty()) {
                div {
                    className = ClassName("settings-info-text")
                    +"You haven't muted anyone. Mute someone from their profile to hide their messages."
                }
            }
            muted.forEach { pubkey ->
                val meta = userMetadata[pubkey]
                val npub = Nip19.encodeNpub(pubkey)
                val name =
                    meta?.displayName?.takeIf { it.isNotBlank() }
                        ?: meta?.name?.takeIf { it.isNotBlank() }
                        ?: (npub.take(12) + "…")
                div {
                    key = pubkey
                    className = ClassName("member-row")
                    // Row opens the profile page; the route change closes the settings overlay.
                    onClick = { pushRoute(UserRoute(pubkey)) }
                    WebAvatar {
                        url = meta?.picture
                        seed = pubkey
                        this.name = name
                        cls = "member-avatar"
                    }
                    span {
                        className = ClassName("member-name")
                        +name
                    }
                    button {
                        className = ClassName("btn-ghost")
                        onClick = {
                            it.stopPropagation()
                            vm.unmute(pubkey)
                        }
                        +"Unmute"
                    }
                }
            }
        }
    }

/**
 * Every group with a notification level of its own, so an override can be found and
 * undone without opening the group. Compose parity: MutedGroupsPanelContent.kt.
 */
private val MutedGroupsPanel =
    FC<Props> {
        val vm = useViewModel { MutedGroupsViewModel(AppModule.nostrRepository, AppModule.notificationSettings) }
        val rows = useStateFlow(vm.rows)
        val defaultLevel = useStateFlow(vm.defaultLevel)

        div {
            className = ClassName("settings-card")
            div {
                className = ClassName("settings-info-text")
                +(
                    "Muted groups never notify and drop their unread count to a dot. " +
                        "These overrides are stored per account and sync to your other devices."
                    )
            }
        }

        div {
            className = ClassName("settings-card")
            div {
                className = ClassName("settings-section-head")
                +"MUTED GROUPS"
            }
            if (rows.isEmpty()) {
                div {
                    className = ClassName("settings-info-text")
                    +"No group overrides yet. Right-click a group in the sidebar, or open its info panel, to mute it."
                }
            }
            rows.forEach { row ->
                div {
                    key = row.groupId
                    className = ClassName("member-row")
                    // Row opens the group; the route change closes the settings overlay.
                    if (row.relayUrl.isNotBlank()) {
                        onClick = { pushRoute(GroupRoute(row.relayUrl, row.groupId)) }
                    }
                    WebAvatar {
                        url = row.picture
                        seed = row.groupId
                        this.name = row.name
                        kind = AvatarKind.GROUP
                        cls = "member-avatar"
                    }
                    span {
                        className = ClassName("member-name")
                        +row.name
                    }
                    span {
                        className = ClassName("member-level")
                        +webLevelLabel(row.level)
                    }
                    button {
                        className = ClassName("btn-ghost")
                        onClick = {
                            it.stopPropagation()
                            vm.clearOverride(row.groupId)
                        }
                        +(if (row.level == NotificationLevel.MUTED) "Unmute" else "Reset")
                    }
                }
            }
            if (rows.isNotEmpty()) {
                div {
                    className = ClassName("settings-info-text")
                    +"Clearing an override returns the group to the global default (${webLevelLabel(defaultLevel).lowercase()})."
                }
            }
        }
    }

private fun webLevelLabel(level: NotificationLevel): String = when (level) {
    NotificationLevel.ALL -> "All messages"
    NotificationLevel.MENTIONS_REPLIES -> "Mentions & replies"
    NotificationLevel.MUTED -> "Muted"
}

// ── Notifications ────────────────────────────────────────────────────────────

private val NotificationsPanel =
    FC<Props> {
        val settings = AppModule.notificationSettings
        val service = AppModule.notificationService
        val soundEnabled = useStateFlow(settings.soundEnabled)
        val soundSuppression = useStateFlow(notificationSoundSuppression)
        val systemEnabled = useStateFlow(settings.systemNotificationsEnabled)
        val defaultLevel = useStateFlow(settings.defaultLevel)
        val permission = useStateFlow(service.permission)

        div {
            className = ClassName("settings-card")
            settingsToggle("Notification sound", "Play a sound when a new message arrives.", soundEnabled) {
                settings.setSoundEnabled(!soundEnabled)
            }
            if (soundEnabled) {
                button {
                    className = ClassName("settings-test-sound")
                    disabled = soundSuppression != null
                    onClick = { playNotificationSound() }
                    +"Test sound"
                }
                soundSuppression?.let {
                    div {
                        className = ClassName("settings-sound-notice")
                        +it.notice
                    }
                }
            }
            // Desktop notifications live in the same box, separated by a divider (native layout).
            div { className = ClassName("settings-card-divider") }
            settingsToggle(
                "Desktop notifications",
                "Show a system popup outside the app when a new message arrives.",
                systemEnabled,
            ) { settings.setSystemNotificationsEnabled(!systemEnabled) }
            if (service.isSupported()) {
                div {
                    className = ClassName("settings-perm")
                    span {
                        className = ClassName("settings-perm-status")
                        +when (permission) {
                            NotificationPermission.Granted -> "Permission granted"
                            NotificationPermission.Denied -> "Permission denied in the browser."
                            else -> "Permission not granted yet."
                        }
                    }
                    if (permission != NotificationPermission.Granted) {
                        button {
                            className = ClassName("settings-outline-btn")
                            onClick = { service.requestPermission() }
                            +"Request permission"
                        }
                    }
                }
            }
        }
        div {
            className = ClassName("settings-card")
            div {
                className = ClassName("settings-section-head")
                +"DEFAULT FOR NEW GROUPS"
            }
            div {
                className = ClassName("settings-section-desc")
                +"Applied to groups you haven't set individually. Change a single group from its info screen."
            }
            levelRadio("All messages", "Notify for every message.", defaultLevel == NotificationLevel.ALL) {
                settings.setDefaultLevel(NotificationLevel.ALL)
            }
            levelRadio(
                "Mentions & replies only",
                "Only notify when you are mentioned or replied to.",
                defaultLevel == NotificationLevel.MENTIONS_REPLIES,
            ) { settings.setDefaultLevel(NotificationLevel.MENTIONS_REPLIES) }
            levelRadio("Muted", "Never notify.", defaultLevel == NotificationLevel.MUTED) {
                settings.setDefaultLevel(NotificationLevel.MUTED)
            }
        }
    }

// ── Appearance ───────────────────────────────────────────────────────────────

private val AppearancePanel =
    FC<Props> {
        val appearance = AppModule.appearanceSettings
        val theme = useStateFlow(appearance.theme)

        div {
            className = ClassName("settings-card")
            div {
                className = ClassName("settings-section-head")
                +"THEME"
            }
            div {
                className = ClassName("theme-grid")
                themeCard("Dark", AppTheme.DARK, theme) { appearance.setTheme(it) }
                themeCard("Light", AppTheme.LIGHT, theme) { appearance.setTheme(it) }
                themeCard("System", AppTheme.SYSTEM, theme) { appearance.setTheme(it) }
            }
        }
    }

private fun react.ChildrenBuilder.themeCard(
    label: String,
    theme: AppTheme,
    selected: AppTheme,
    onSelect: (AppTheme) -> Unit,
) {
    button {
        className = ClassName(if (theme == selected) "theme-card selected" else "theme-card")
        onClick = { onSelect(theme) }
        div {
            className = ClassName("theme-preview ${theme.name.lowercase()}")
            div { className = ClassName("theme-preview-pill brand") }
            div { className = ClassName("theme-preview-pill line") }
        }
        div {
            className = ClassName("theme-card-label")
            if (theme == selected) icon(Ic.Check)
            +label
        }
    }
}

// ── Media ────────────────────────────────────────────────────────────────────

private val MediaPanel =
    FC<Props> {
        val media = AppModule.mediaSettings
        val autoLoad = useStateFlow(media.autoLoadMedia)
        div {
            className = ClassName("settings-card")
            settingsToggle(
                "Auto-load media",
                "Automatically load images and videos in chat. When off, each one shows a tap-to-load placeholder so you choose what to fetch.",
                autoLoad,
            ) { media.setAutoLoadMedia(!autoLoad) }
        }
        MediaServerSection()
    }

/**
 * Upload destination: the service picker plus, under Blossom, the ordered server list.
 * Mirrors the native MediaServerPanelContent and shares MediaServerSettings, so ordering,
 * validation and persistence live in commonMain.
 */
private val MediaServerSection =
    FC<Props> {
        val settings = AppModule.mediaServerSettings
        val service = useStateFlow(settings.service)

        div {
            className = ClassName("settings-card")
            div {
                className = ClassName("settings-section-head")
                +"MEDIA UPLOAD"
            }
            div {
                className = ClassName("media-service-row")
                span {
                    className = ClassName("media-service-label")
                    span {
                        className = ClassName("media-server-name")
                        +"Media upload service"
                    }
                    span {
                        className = ClassName("media-server-url")
                        +"Blossom spreads your media across several servers. A NIP-96 host keeps it in one place."
                    }
                }
                select {
                    className = ClassName("modal-select media-service-select")
                    value = when (service) {
                        is MediaUploadService.Blossom -> BLOSSOM_OPTION
                        is MediaUploadService.Nip96 -> service.url
                    }
                    onChange = { event ->
                        val picked = event.currentTarget.value
                        if (picked == BLOSSOM_OPTION) settings.useBlossom() else settings.useNip96(picked)
                    }
                    option {
                        value = BLOSSOM_OPTION
                        +"Blossom"
                    }
                    settings.nip96Services.forEach { url ->
                        option {
                            key = url
                            value = url
                            +mediaServerDisplayName(url)
                        }
                    }
                }
            }
        }

        if (service is MediaUploadService.Blossom) BlossomServerList()
    }

private const val BLOSSOM_OPTION = "blossom"

private val BlossomServerList =
    FC<Props> {
        val settings = AppModule.mediaServerSettings
        val servers = useStateFlow(settings.blossomServers)
        val (newUrl, setNewUrl) = useState { "" }
        val (addError, setAddError) = useState<String?> { null }

        fun addServer(input: String) {
            when (val result = settings.addBlossomServer(input)) {
                is Result.Success -> {
                    setNewUrl("")
                    setAddError(null)
                }

                is Result.Error -> setAddError(result.error.message)
            }
        }

        div {
            className = ClassName("settings-card")
            div {
                className = ClassName("settings-section-head")
                +"BLOSSOM SERVERS"
            }
            if (servers.isEmpty()) {
                div {
                    className = ClassName("relay-warn")
                    +"No servers. Add one below, or uploads will fail."
                }
            }
            servers.forEachIndexed { index, url ->
                div {
                    key = url
                    className = ClassName("media-server-row")
                    span {
                        className = ClassName("media-server-info")
                        span {
                            className = ClassName("media-server-name")
                            +mediaServerDisplayName(url)
                        }
                        if (index == 0) {
                            span {
                                className = ClassName("media-server-preferred")
                                +"Preferred"
                            }
                        }
                    }
                    button {
                        className = ClassName("relay-row-remove")
                        disabled = index == 0
                        title = "Move up"
                        onClick = { settings.moveBlossomServer(url, true) }
                        icon(Ic.ExpandLess)
                    }
                    button {
                        className = ClassName("relay-row-remove")
                        disabled = index == servers.lastIndex
                        title = "Move down"
                        onClick = { settings.moveBlossomServer(url, false) }
                        icon(Ic.ExpandMore)
                    }
                    button {
                        className = ClassName("relay-row-remove")
                        title = "Remove"
                        onClick = { settings.removeBlossomServer(url) }
                        icon(Ic.Close)
                    }
                }
            }

            div {
                className = ClassName("settings-section-head relay-add-head")
                +"ADD BLOSSOM SERVER"
            }
            input {
                className = ClassName("modal-input")
                placeholder = "blossom.example.com"
                value = newUrl
                onChange = { event ->
                    setNewUrl(event.currentTarget.value)
                    setAddError(null)
                }
                onKeyDown = { event ->
                    if (event.key == "Enter") {
                        event.preventDefault()
                        addServer(newUrl)
                    }
                }
            }
            addError?.let {
                div {
                    className = ClassName("relay-warn")
                    +it
                }
            }
            div {
                className = ClassName("relay-add-row")
                button {
                    className = ClassName("relay-add-btn")
                    disabled = newUrl.isBlank()
                    onClick = { addServer(newUrl) }
                    icon(Ic.Add)
                    +"Add"
                }
            }

            val recommended = settings.recommendedNotAdded()
            if (recommended.isNotEmpty()) {
                div {
                    className = ClassName("media-server-hint")
                    +"Recommended servers"
                }
                div {
                    className = ClassName("media-server-chips")
                    recommended.forEach { url ->
                        button {
                            key = url
                            className = ClassName("media-server-chip")
                            onClick = { addServer(url) }
                            icon(Ic.Add)
                            +mediaServerDisplayName(url)
                        }
                    }
                }
            }

            div {
                className = ClassName("media-server-hint")
                +"Media is uploaded to the preferred server and mirrored to the others."
            }
        }
    }

// ── Security ─────────────────────────────────────────────────────────────────

private val SecurityPanel =
    FC<Props> {
        val vm = useViewModel { SecurityViewModel() }
        val current = useStateFlow(vm.current)
        val newPassword = useStateFlow(vm.new)
        val confirm = useStateFlow(vm.confirm)
        val busy = useStateFlow(vm.busy)
        val error = useStateFlow(vm.error)
        val successMessage = useStateFlow(vm.successMessage)
        val isProtected = useStateFlow(vm.isPasswordProtected)

        if (isProtected) {
            // The active account is unlocked per session from a stored ncryptsec; let the user
            // rotate that password (the key itself is unchanged).
            div {
                className = ClassName("settings-card")
                div {
                    className = ClassName("settings-section-head")
                    +"ACCOUNT PASSWORD"
                }
                div {
                    className = ClassName("settings-tip")
                    +"Your private key is encrypted with this password (NIP-49). It cannot be recovered if you forget it."
                }
                passwordField("Current password", current) { vm.setCurrent(it) }
                passwordField("New password", newPassword) { vm.setNew(it) }
                passwordField("Confirm new password", confirm) { vm.setConfirm(it) }
                error?.let {
                    div {
                        className = ClassName("settings-error")
                        +it
                    }
                }
                successMessage?.let {
                    div {
                        className = ClassName("settings-success")
                        +it
                    }
                }
                button {
                    className = ClassName("settings-outline-btn")
                    disabled = busy || current.isEmpty() || newPassword.isEmpty() || confirm.isEmpty()
                    onClick = { vm.changePassword() }
                    +(if (busy) "Saving…" else "Change password")
                }
            }
        } else if (vm.canProtect) {
            // Plain key at rest (the web default): offer the same protect-with-password
            // option the login screen has, after the fact.
            div {
                className = ClassName("settings-card")
                div {
                    className = ClassName("settings-section-head")
                    +"PROTECT YOUR KEY"
                }
                div {
                    className = ClassName("settings-tip")
                    +(
                        "Your private key is stored unencrypted in this browser. Set a password to keep it " +
                            "encrypted at rest (NIP-49); you will be asked for it when the app starts. " +
                            "It cannot be recovered if you forget it."
                        )
                }
                passwordField("Password", newPassword) { vm.setNew(it) }
                passwordField("Confirm password", confirm) { vm.setConfirm(it) }
                error?.let {
                    div {
                        className = ClassName("settings-error")
                        +it
                    }
                }
                button {
                    className = ClassName("settings-outline-btn")
                    disabled = busy || newPassword.isEmpty() || confirm.isEmpty()
                    onClick = { vm.protectWithPassword() }
                    +(if (busy) "Encrypting…" else "Set password")
                }
            }
        } else {
            div {
                className = ClassName("settings-card")
                div {
                    className = ClassName("settings-section-head")
                    +"APP SECURITY"
                }
                div {
                    className = ClassName("settings-status-line")
                    +"No app passphrase on the web. Your key is managed by the browser. Use Backup Keys to save it."
                }
            }
        }
    }

private fun react.ChildrenBuilder.passwordField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    div {
        className = ClassName("settings-field")
        div {
            className = ClassName("field-label")
            +label
        }
        input {
            className = ClassName("modal-input")
            type = InputType.password
            this.value = value
            this.onChange = { event -> onChange(event.currentTarget.value) }
        }
    }
}

// ── Shared bits ──────────────────────────────────────────────────────────────

private fun react.ChildrenBuilder.settingsField(
    label: String,
    placeholder: String,
    value: String,
    onChange: (String) -> Unit,
) {
    div {
        className = ClassName("settings-field")
        div {
            className = ClassName("field-label")
            +label
        }
        input {
            className = ClassName("modal-input")
            this.placeholder = placeholder
            this.value = value
            this.onChange = { event -> onChange(event.currentTarget.value) }
        }
    }
}

private fun react.ChildrenBuilder.settingsUploadField(
    label: String,
    placeholder: String,
    value: String,
    onChange: (String) -> Unit,
    onError: (String) -> Unit,
) {
    div {
        className = ClassName("settings-field")
        div {
            className = ClassName("field-label")
            +label
        }
        div {
            className = ClassName("upload-field")
            input {
                className = ClassName("modal-input flush")
                this.placeholder = placeholder
                this.value = value
                this.onChange = { event -> onChange(event.currentTarget.value) }
            }
            UploadButton {
                cls = "upload-btn"
                icon = Ic.Upload
                imagesOnly = true
                onUploaded = { onChange(it.url) }
                this.onError = onError
            }
        }
    }
}

private fun react.ChildrenBuilder.settingsTextarea(
    label: String,
    placeholder: String,
    value: String,
    onChange: (String) -> Unit,
) {
    div {
        className = ClassName("settings-field")
        div {
            className = ClassName("field-label")
            +label
        }
        textarea {
            className = ClassName("modal-textarea")
            this.placeholder = placeholder
            rows = 3
            this.value = value
            this.onChange = { event -> onChange(event.currentTarget.value) }
        }
    }
}

private fun react.ChildrenBuilder.settingsToggle(
    label: String,
    description: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    div {
        className = ClassName("settings-toggle-row")
        div {
            className = ClassName("settings-toggle-text")
            div {
                className = ClassName("settings-toggle-label")
                +label
            }
            div {
                className = ClassName("settings-toggle-desc")
                +description
            }
        }
        div {
            className = ClassName(if (checked) "switch on" else "switch")
            onClick = { onToggle() }
            div { className = ClassName("switch-thumb") }
        }
    }
}

private fun react.ChildrenBuilder.levelRadio(
    label: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    div {
        className = ClassName("settings-radio-row")
        onClick = { onSelect() }
        div {
            className = ClassName(if (selected) "settings-radio on" else "settings-radio")
            div { className = ClassName("settings-radio-dot") }
        }
        div {
            className = ClassName("settings-toggle-text")
            div {
                className = ClassName("settings-toggle-label")
                +label
            }
            div {
                className = ClassName("settings-toggle-desc")
                +description
            }
        }
    }
}
