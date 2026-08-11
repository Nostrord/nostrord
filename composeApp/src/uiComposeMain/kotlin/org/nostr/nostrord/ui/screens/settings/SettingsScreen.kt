package org.nostr.nostrord.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import org.nostr.nostrord.auth.AuthMethod
import org.nostr.nostrord.auth.logoutConfirmBody
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.managers.DmEncryptionManager
import org.nostr.nostrord.network.managers.DmPairingManager
import org.nostr.nostrord.network.outbox.Nip65Relay
import org.nostr.nostrord.network.outbox.RelayListManager
import org.nostr.nostrord.settings.AppTheme
import org.nostr.nostrord.settings.NotificationLevel
import org.nostr.nostrord.storage.PassphraseSettings
import org.nostr.nostrord.ui.Identifier
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.ui.components.ConfirmDialog
import org.nostr.nostrord.ui.components.IdentifierRow
import org.nostr.nostrord.ui.components.RadioCircle
import org.nostr.nostrord.ui.components.avatars.ProfileAvatar
import org.nostr.nostrord.ui.components.cards.InfoCard
import org.nostr.nostrord.ui.components.layout.responsiveDimension
import org.nostr.nostrord.ui.components.upload.UploadImageField
import org.nostr.nostrord.ui.navigation.PlatformBackHandler
import org.nostr.nostrord.ui.screens.backup.BackupKeysSections
import org.nostr.nostrord.ui.screens.backup.BackupViewModel
import org.nostr.nostrord.ui.screens.backup.backupSecurityTips
import org.nostr.nostrord.ui.screens.profile.EditProfileViewModel
import org.nostr.nostrord.ui.theme.DarkColorPalette
import org.nostr.nostrord.ui.theme.LightColorPalette
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing

enum class SettingsSection(val label: String) {
    Profile("Profile"),
    BackupKeys("Backup Keys"),
    RelaysNip65("Relays (NIP-65)"),
    DirectMessages("Direct Messages"),
    Appearance("Appearance"),
    Media("Media"),
    Notifications("Notifications"),
    MutedUsers("Muted users"),
    MutedGroups("Muted groups"),
    Security("Security"),
}

/**
 * Full-screen settings overlay.
 *
 * Desktop layout (matches new-user.html .settings-overlay):
 * ┌──────────────────┬───────────────────────────────────┬──────────┐
 * │  Sidebar 218dp   │  Content (scrollable, max 660dp)  │  80dp    │
 * │  BackgroundDark  │  Background                       │  Close   │
 * └──────────────────┴───────────────────────────────────┴──────────┘
 *
 * Mobile: nav list full-screen → tap item → panel with back button.
 * ESC key closes the overlay.
 */
@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    onNavigate: (Screen) -> Unit,
    onLogout: () -> Unit,
    forceDesktop: Boolean = false,
) {
    val vm = viewModel { EditProfileViewModel(AppModule.nostrRepository) }
    val userMetadata by vm.userMetadata.collectAsState()
    val publicKey = vm.getPublicKey()
    val currentUserMetadata = publicKey?.let { userMetadata[it] }

    // Form state — reinitialised when metadata loads
    // Single "Name" field populates both display_name and name in the event
    var name by remember(currentUserMetadata) {
        mutableStateOf(currentUserMetadata?.displayName ?: currentUserMetadata?.name ?: "")
    }
    var about by remember(currentUserMetadata) {
        mutableStateOf(currentUserMetadata?.about ?: "")
    }
    var pictureUrl by remember(currentUserMetadata) {
        mutableStateOf(currentUserMetadata?.picture ?: "")
    }
    var bannerUrl by remember(currentUserMetadata) {
        mutableStateOf(currentUserMetadata?.banner ?: "")
    }
    var nip05 by remember(currentUserMetadata) {
        mutableStateOf(currentUserMetadata?.nip05 ?: "")
    }
    var lightningAddress by remember(currentUserMetadata) {
        mutableStateOf(currentUserMetadata?.lud16 ?: "")
    }
    var website by remember(currentUserMetadata) {
        mutableStateOf(currentUserMetadata?.website ?: "")
    }
    var isSaving by remember { mutableStateOf(false) }
    var showSuccessMessage by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(showSuccessMessage) {
        if (showSuccessMessage) {
            delay(2000)
            showSuccessMessage = false
        }
    }
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            delay(3000)
            errorMessage = null
        }
    }

    val onSave: () -> Unit = {
        isSaving = true
        errorMessage = null
        val nameValue = name.ifBlank { null }
        vm.saveProfile(
            displayName = nameValue,
            name = nameValue,
            about = about.ifBlank { null },
            picture = pictureUrl.ifBlank { null },
            banner = bannerUrl.ifBlank { null },
            nip05 = nip05.ifBlank { null },
            lud16 = lightningAddress.ifBlank { null },
            website = website.ifBlank { null },
        ) { result ->
            isSaving = false
            if (result.isSuccess) {
                showSuccessMessage = true
            } else {
                errorMessage = result.exceptionOrNull()?.message ?: "Failed to update profile"
            }
        }
    }

    val profileContent: @Composable () -> Unit = {
        ProfileFormContent(
            name = name,
            about = about,
            pictureUrl = pictureUrl,
            bannerUrl = bannerUrl,
            nip05 = nip05,
            lightningAddress = lightningAddress,
            website = website,
            pubkey = publicKey,
            isSaving = isSaving,
            showSuccessMessage = showSuccessMessage,
            errorMessage = errorMessage,
            onNameChange = { name = it },
            onAboutChange = { about = it },
            onPictureUrlChange = { pictureUrl = it },
            onBannerUrlChange = { bannerUrl = it },
            onNip05Change = { nip05 = it },
            onLightningAddressChange = { lightningAddress = it },
            onWebsiteChange = { website = it },
            onSave = onSave,
        )
    }

    // Backup keys: the same shared screen as the standalone Backup route and the web Settings.
    // Keyed by the active account so switching accounts rebuilds the VM with the new keys.
    val activeAccountId by AppModule.accountStore.activeId.collectAsState()
    val backupVm = viewModel(key = activeAccountId) { BackupViewModel() }
    val backupContent: @Composable () -> Unit = {
        BackupPanelContent(backupVm)
    }

    // NIP-65 relay state
    val userRelayList by AppModule.nostrRepository.userRelayList.collectAsState()
    val usingDefaultRelays = userRelayList.isEmpty()
    val effectiveRelayList = if (usingDefaultRelays) {
        RelayListManager.DEFAULT_FALLBACK_RELAYS.map { Nip65Relay(it) }
    } else {
        userRelayList
    }
    val relaysContent: @Composable () -> Unit = {
        RelayNip65PanelContent(
            currentRelays = effectiveRelayList,
            usingDefaults = usingDefaultRelays,
            onPublish = { relays -> AppModule.nostrRepository.publishRelayList(relays) },
        )
    }

    val dmEnabled by AppModule.dmSettings.dmEnabled.collectAsState()
    val dmRelaysContent: @Composable () -> Unit = {
        DmSettingsPanelContent(
            dmEnabled = dmEnabled,
            onToggleDm = { AppModule.dmSettings.setDmEnabled(it) },
        )
    }

    val notificationsContent: @Composable () -> Unit = {
        NotificationsPanelContent()
    }

    val appTheme by AppModule.appearanceSettings.theme.collectAsState()
    val appearanceContent: @Composable () -> Unit = {
        AppearancePanelContent(
            selectedTheme = appTheme,
            onSelectTheme = { AppModule.appearanceSettings.setTheme(it) },
        )
    }

    val autoLoadMedia by AppModule.mediaSettings.autoLoadMedia.collectAsState()
    val mediaContent: @Composable () -> Unit = {
        MediaPanelContent(
            autoLoadMedia = autoLoadMedia,
            onToggleAutoLoad = { AppModule.mediaSettings.setAutoLoadMedia(it) },
        )
    }

    val mutedUsersVm = viewModel { MutedUsersViewModel(AppModule.nostrRepository) }
    val mutedUsersContent: @Composable () -> Unit = {
        MutedUsersPanelContent(mutedUsersVm)
    }

    val mutedGroupsVm = viewModel { MutedGroupsViewModel(AppModule.nostrRepository, AppModule.notificationSettings) }
    val mutedGroupsContent: @Composable () -> Unit = {
        MutedGroupsPanelContent(mutedGroupsVm)
    }

    val securityContent: @Composable () -> Unit = {
        SecurityPanelContent()
    }

    var activeSection by remember { mutableStateOf(SettingsSection.Profile) }
    var showMobilePanel by remember { mutableStateOf(false) }

    // Android back button: panel → list → close modal
    PlatformBackHandler(enabled = showMobilePanel) { showMobilePanel = false }
    PlatformBackHandler(enabled = !showMobilePanel) { onClose() }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(NostrordColors.Background)
            // Consume all pointer events so the chat behind is not interactive
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { /* consume clicks */ }
            .focusRequester(focusRequester)
            .focusTarget()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onClose()
                    true
                } else {
                    false
                }
            },
    ) {
        if (!forceDesktop && responsiveDimension < 912.dp) {
            MobileSettings(
                activeSection = activeSection,
                showPanel = showMobilePanel,
                onSelectSection = { section ->
                    activeSection = section
                    showMobilePanel = true
                },
                onBack = { showMobilePanel = false },
                onClose = onClose,
                onLogout = onLogout,
                onNavigate = onNavigate,
                profileContent = profileContent,
                backupContent = backupContent,
                relaysContent = relaysContent,
                dmRelaysContent = dmRelaysContent,
                appearanceContent = appearanceContent,
                mediaContent = mediaContent,
                notificationsContent = notificationsContent,
                mutedUsersContent = mutedUsersContent,
                mutedGroupsContent = mutedGroupsContent,
                securityContent = securityContent,
            )
        } else {
            DesktopSettings(
                activeSection = activeSection,
                onSelectSection = { activeSection = it },
                onClose = onClose,
                onLogout = onLogout,
                onNavigate = onNavigate,
                profileContent = profileContent,
                backupContent = backupContent,
                relaysContent = relaysContent,
                dmRelaysContent = dmRelaysContent,
                appearanceContent = appearanceContent,
                mediaContent = mediaContent,
                notificationsContent = notificationsContent,
                mutedUsersContent = mutedUsersContent,
                mutedGroupsContent = mutedGroupsContent,
                securityContent = securityContent,
            )
        }
    }
}

// ── Desktop ──────────────────────────────────────────────────────────────────

@Composable
private fun DesktopSettings(
    activeSection: SettingsSection,
    onSelectSection: (SettingsSection) -> Unit,
    onClose: () -> Unit,
    onLogout: () -> Unit,
    onNavigate: (Screen) -> Unit,
    profileContent: @Composable () -> Unit,
    backupContent: @Composable () -> Unit,
    relaysContent: @Composable () -> Unit,
    dmRelaysContent: @Composable () -> Unit,
    appearanceContent: @Composable () -> Unit,
    mediaContent: @Composable () -> Unit,
    notificationsContent: @Composable () -> Unit,
    mutedUsersContent: @Composable () -> Unit,
    mutedGroupsContent: @Composable () -> Unit,
    securityContent: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Centered layout: sidebar(218) + content(740) + close(80) = 1038dp center block.
        // Two equal fills extend the sidebar/content background colors to the screen edges,
        // mirroring the CSS: settings-sidebar-fill { flex:1 } / settings-content-fill { flex:1 }.
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val sidebarW = 218.dp
            val closeW = 80.dp
            val contentMaxW = 740.dp
            // On narrow screens shrink content rather than overflow
            val centerW = (sidebarW + contentMaxW + closeW).coerceAtMost(maxWidth)
            val contentW = (centerW - sidebarW - closeW).coerceAtLeast(0.dp)
            val fillW = ((maxWidth - centerW) / 2f).coerceAtLeast(0.dp)

            // Background layer: sidebar color bleeds to left edge, content color bleeds right
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(fillW + sidebarW).fillMaxHeight().background(NostrordColors.BackgroundDark))
                Box(Modifier.weight(1f).fillMaxHeight().background(NostrordColors.Background))
            }

            // Content layer: left spacer positions sidebar at the correct offset
            Row(Modifier.fillMaxSize()) {
                Spacer(Modifier.width(fillW))

                Column(
                    modifier = Modifier
                        .width(sidebarW)
                        .fillMaxHeight()
                        .padding(top = 24.dp, end = 8.dp, bottom = 24.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    SettingsSidebar(
                        activeSection = activeSection,
                        onSelectSection = onSelectSection,
                        onLogout = onLogout,
                    )
                }

                Column(
                    modifier = Modifier
                        .width(contentW)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 24.dp, start = 40.dp, end = 20.dp, bottom = 80.dp),
                ) {
                    Box(modifier = Modifier.widthIn(max = 660.dp)) {
                        SettingsPanel(activeSection, profileContent, backupContent, relaysContent, dmRelaysContent, appearanceContent, mediaContent, notificationsContent, mutedUsersContent, mutedGroupsContent, securityContent)
                    }
                }

                Column(modifier = Modifier.width(closeW).padding(top = 24.dp, start = 16.dp)) {
                    SettingsCloseButton(onClick = onClose)
                }
            }
        }
    }
}

// ── Mobile ───────────────────────────────────────────────────────────────────

@Composable
private fun MobileSettings(
    activeSection: SettingsSection,
    showPanel: Boolean,
    onSelectSection: (SettingsSection) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onLogout: () -> Unit,
    onNavigate: (Screen) -> Unit,
    profileContent: @Composable () -> Unit,
    backupContent: @Composable () -> Unit,
    relaysContent: @Composable () -> Unit,
    dmRelaysContent: @Composable () -> Unit,
    appearanceContent: @Composable () -> Unit,
    mediaContent: @Composable () -> Unit,
    notificationsContent: @Composable () -> Unit,
    mutedUsersContent: @Composable () -> Unit,
    mutedGroupsContent: @Composable () -> Unit,
    securityContent: @Composable () -> Unit,
) {
    if (!showPanel) {
        Column(modifier = Modifier.fillMaxSize().background(NostrordColors.BackgroundDark).statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Settings", color = NostrordColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                SettingsCloseButton(onClick = onClose)
            }
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SettingsSidebar(
                    activeSection = activeSection,
                    onSelectSection = onSelectSection,
                    onLogout = onLogout,
                    compact = true,
                )
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize().background(NostrordColors.Background).statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NostrordColors.BackgroundDark)
                    .clickable(onClick = onBack)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("‹", color = NostrordColors.TextSecondary, fontSize = 22.sp)
                Spacer(Modifier.width(8.dp))
                Text("Settings", color = NostrordColors.TextSecondary, fontSize = 14.sp)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 24.dp),
            ) {
                SettingsPanel(activeSection, profileContent, backupContent, relaysContent, dmRelaysContent, appearanceContent, mediaContent, notificationsContent, mutedUsersContent, mutedGroupsContent, securityContent)
            }
        }
    }
}

// ── Shared sidebar ────────────────────────────────────────────────────────────

@Composable
private fun SettingsSidebar(
    activeSection: SettingsSection,
    onSelectSection: (SettingsSection) -> Unit,
    onLogout: () -> Unit,
    compact: Boolean = false,
) {
    var showLogoutConfirm by remember { mutableStateOf(false) }

    if (showLogoutConfirm) {
        // Tailor the body to the active account's auth method so NIP-07 users
        // aren't told they need a "private key", and Bunker users aren't told
        // to dig out a private key either.
        val activeId = AppModule.accountStore.activeId.collectAsState().value
        val accounts = AppModule.accountStore.accounts.collectAsState().value
        val activeMethod = accounts.firstOrNull { it.id == activeId }?.authMethod ?: AuthMethod.LOCAL
        ConfirmDialog(
            title = "Log out?",
            message = logoutConfirmBody(activeMethod),
            confirmLabel = "Log Out",
            destructive = true,
            onConfirm = {
                showLogoutConfirm = false
                onLogout()
            },
            onDismiss = { showLogoutConfirm = false },
        )
    }

    SettingsNavItem("Profile", activeSection == SettingsSection.Profile, compact = compact) {
        onSelectSection(SettingsSection.Profile)
    }
    SettingsNavItem("Backup Keys", activeSection == SettingsSection.BackupKeys, compact = compact) {
        onSelectSection(SettingsSection.BackupKeys)
    }
    SettingsNavItem("Relays (NIP-65)", activeSection == SettingsSection.RelaysNip65, compact = compact) {
        onSelectSection(SettingsSection.RelaysNip65)
    }
    SettingsNavItem("Direct Messages", activeSection == SettingsSection.DirectMessages, compact = compact) {
        onSelectSection(SettingsSection.DirectMessages)
    }
    SettingsNavItem("Appearance", activeSection == SettingsSection.Appearance, compact = compact) {
        onSelectSection(SettingsSection.Appearance)
    }
    SettingsNavItem("Media", activeSection == SettingsSection.Media, compact = compact) {
        onSelectSection(SettingsSection.Media)
    }
    SettingsNavItem("Notifications", activeSection == SettingsSection.Notifications, compact = compact) {
        onSelectSection(SettingsSection.Notifications)
    }
    SettingsNavItem("Muted users", activeSection == SettingsSection.MutedUsers, compact = compact) {
        onSelectSection(SettingsSection.MutedUsers)
    }
    SettingsNavItem("Muted groups", activeSection == SettingsSection.MutedGroups, compact = compact) {
        onSelectSection(SettingsSection.MutedGroups)
    }
    if (PassphraseSettings.isApplicable) {
        SettingsNavItem("Security", activeSection == SettingsSection.Security, compact = compact) {
            onSelectSection(SettingsSection.Security)
        }
    }
    SettingsNavDivider(compact)
    SettingsNavItem(
        "Log Out",
        isActive = false,
        isDanger = true,
        compact = compact,
        onClick = { showLogoutConfirm = true },
    )
}

// ── Nav primitives ────────────────────────────────────────────────────────────

@Composable
private fun SettingsNavItem(
    label: String,
    isActive: Boolean,
    isDanger: Boolean = false,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val textColor = when {
        isDanger -> NostrordColors.Error
        isActive || isHovered -> NostrordColors.TextPrimary
        else -> NostrordColors.TextSecondary
    }

    if (compact) {
        val bg = if (isDanger && isHovered) NostrordColors.Error.copy(alpha = 0.1f) else NostrordColors.Background
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().background(bg)
                    .hoverable(interactionSource).clickable(onClick = onClick)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = label, color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (!isDanger) Text("›", color = NostrordColors.TextMuted, fontSize = 20.sp, lineHeight = 20.sp)
            }
            HorizontalDivider(color = NostrordColors.Divider)
        }
    } else {
        val bg = when {
            isDanger && isHovered -> NostrordColors.Error.copy(alpha = 0.15f)
            isActive || isHovered -> NostrordColors.HoverBackground
            else -> Color.Transparent
        }
        Box(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(bg)
                .hoverable(interactionSource).clickable(onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text = label,
                color = textColor,
                fontSize = 14.sp,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun SettingsNavDivider(compact: Boolean = false) {
    HorizontalDivider(
        modifier = if (compact) Modifier else Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        color = NostrordColors.Divider,
    )
}

// ── Close button ─────────────────────────────────────────────────────────────

@Composable
private fun SettingsCloseButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Column(
        modifier = Modifier.size(40.dp).clip(CircleShape)
            .background(if (isHovered) NostrordColors.HoverBackground else NostrordColors.SurfaceVariant)
            .hoverable(interactionSource).clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Close,
            contentDescription = "Close settings",
            tint = NostrordColors.TextSecondary,
            modifier = Modifier.size(16.dp),
        )
    }
}

// ── Content panels ────────────────────────────────────────────────────────────

@Composable
private fun SettingsPanel(
    section: SettingsSection,
    profileContent: @Composable () -> Unit,
    backupContent: @Composable () -> Unit,
    relaysContent: @Composable () -> Unit,
    dmRelaysContent: @Composable () -> Unit,
    appearanceContent: @Composable () -> Unit,
    mediaContent: @Composable () -> Unit,
    notificationsContent: @Composable () -> Unit,
    mutedUsersContent: @Composable () -> Unit,
    mutedGroupsContent: @Composable () -> Unit,
    securityContent: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = section.label,
            color = NostrordColors.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        HorizontalDivider(color = NostrordColors.Divider)
        Spacer(Modifier.height(24.dp))

        when (section) {
            SettingsSection.Profile -> profileContent()
            SettingsSection.BackupKeys -> backupContent()
            SettingsSection.RelaysNip65 -> relaysContent()
            SettingsSection.DirectMessages -> dmRelaysContent()
            SettingsSection.Appearance -> appearanceContent()
            SettingsSection.Media -> mediaContent()
            SettingsSection.Notifications -> notificationsContent()
            SettingsSection.MutedUsers -> mutedUsersContent()
            SettingsSection.MutedGroups -> mutedGroupsContent()
            SettingsSection.Security -> securityContent()
        }
    }
}

// ── Profile form panel ────────────────────────────────────────────────────────

@Composable
private fun ProfileFormContent(
    name: String,
    about: String,
    pictureUrl: String,
    bannerUrl: String,
    nip05: String,
    lightningAddress: String,
    website: String,
    pubkey: String?,
    isSaving: Boolean,
    showSuccessMessage: Boolean,
    errorMessage: String?,
    onNameChange: (String) -> Unit,
    onAboutChange: (String) -> Unit,
    onPictureUrlChange: (String) -> Unit,
    onBannerUrlChange: (String) -> Unit,
    onNip05Change: (String) -> Unit,
    onLightningAddressChange: (String) -> Unit,
    onWebsiteChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        // Avatar preview card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = NostrordShapes.cardShape,
            colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ProfileAvatar(
                    imageUrl = pictureUrl.ifBlank { null },
                    displayName = name.ifBlank { "User" },
                    pubkey = pubkey ?: "",
                    size = 100.dp,
                )
                Spacer(Modifier.height(Spacing.sm))
                Text("Avatar Preview", style = NostrordTypography.Caption, color = NostrordColors.TextMuted)
            }
        }

        // Form card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = NostrordShapes.cardShape,
            colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                Text(
                    text = "PROFILE INFORMATION",
                    style = NostrordTypography.SectionHeader,
                    color = NostrordColors.TextMuted,
                )

                ProfileField("Name", name, onNameChange, "Your name")
                ProfileField("About", about, onAboutChange, "Tell us about yourself", singleLine = false, maxLines = 4)
                UploadImageField(
                    label = "Avatar URL",
                    value = pictureUrl,
                    onValueChange = onPictureUrlChange,
                    placeholder = "https://example.com/avatar.jpg",
                )
                UploadImageField(
                    label = "Banner URL",
                    value = bannerUrl,
                    onValueChange = onBannerUrlChange,
                    placeholder = "https://example.com/banner.jpg",
                )
                ProfileField("Nostr Address (NIP-05)", nip05, onNip05Change, "you@example.com")
                ProfileField("Lightning Address", lightningAddress, onLightningAddressChange, "you@walletofsatoshi.com")
                ProfileField("Website", website, onWebsiteChange, "https://example.com")

                // Save button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onSave, enabled = !isSaving) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = NostrordColors.Primary,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Save", color = NostrordColors.Primary, style = NostrordTypography.Button)
                        }
                    }
                }
            }
        }

        // Inline feedback
        when {
            showSuccessMessage -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = NostrordColors.Success),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Profile updated successfully", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
            errorMessage != null -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = NostrordColors.Error),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(errorMessage, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = true,
    maxLines: Int = 1,
) {
    Column {
        Text(text = label, style = NostrordTypography.SectionHeader, color = NostrordColors.TextMuted)
        Spacer(Modifier.height(Spacing.sm))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder, color = NostrordColors.TextMuted) },
            singleLine = singleLine,
            maxLines = maxLines,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NostrordColors.Primary,
                unfocusedBorderColor = NostrordColors.Divider,
                focusedContainerColor = NostrordColors.BackgroundFloating,
                unfocusedContainerColor = NostrordColors.BackgroundFloating,
                cursorColor = NostrordColors.Primary,
                focusedTextColor = NostrordColors.TextContent,
                unfocusedTextColor = NostrordColors.TextContent,
            ),
            shape = NostrordShapes.shapeSmall,
        )
    }
}

// ── Backup panel content ──────────────────────────────────────────────────────

@Composable
private fun BackupPanelContent(vm: BackupViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        // Same keys UI as the web Settings backup panel: public cycles npub / nprofile / hex with a
        // QR, the private key is reveal-gated and offers nsec / hex plus a NIP-49 ncryptsec export.
        BackupKeysSections(vm)

        InfoCard(
            title = "Security Tips",
            titleColor = NostrordColors.Warning,
            icon = Icons.Default.Lightbulb,
            content = backupSecurityTips.joinToString("\n") { "• $it" },
            isCompact = false,
        )
    }
}

// ── Notifications panel content ───────────────────────────────────────────────

@Composable
private fun NotificationsPanelContent() {
    val settings = AppModule.notificationSettings
    val notificationService = AppModule.notificationService

    val soundEnabled by settings.soundEnabled.collectAsState()
    val soundSuppression by org.nostr.nostrord.notifications.notificationSoundSuppression.collectAsState()
    val systemEnabled by settings.systemNotificationsEnabled.collectAsState()
    val defaultLevel by settings.defaultLevel.collectAsState()
    val permission by notificationService.permission.collectAsState()
    val systemSupported = remember { notificationService.isSupported() }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        // ── Sound card ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = NostrordShapes.cardShape,
            colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface),
        ) {
            Column(modifier = Modifier.padding(Spacing.xl)) {
                ToggleRow(
                    icon = Icons.Default.Notifications,
                    label = "Notification sound",
                    description = "Play a chime when a new message arrives in a joined " +
                        "group you're not currently viewing.",
                    checked = soundEnabled,
                    onCheckedChange = { settings.setSoundEnabled(it) },
                )
                if (soundEnabled) {
                    // The system mutes the chime regardless of this setting, so the test
                    // button would look broken instead of blocked.
                    val suppression = soundSuppression
                    Spacer(Modifier.height(Spacing.sm))
                    TextButton(
                        onClick = {
                            org.nostr.nostrord.notifications.playNotificationSound()
                        },
                        enabled = suppression == null,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Text(
                            "Test sound",
                            color = if (suppression == null) NostrordColors.Primary else NostrordColors.TextMuted,
                            fontSize = 13.sp,
                        )
                    }
                    if (suppression != null) {
                        Text(
                            suppression.notice,
                            color = NostrordColors.Warning,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
        }

        // ── Default notification level card ──
        // Sets the fallback level for every group the user hasn't overridden via
        // a group's own info screen (issue #70).
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = NostrordShapes.cardShape,
            colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface),
        ) {
            Column(modifier = Modifier.padding(Spacing.xl)) {
                Text(
                    text = "Default for new groups",
                    color = NostrordColors.TextContent,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                Text(
                    text = "Applied to groups you haven't set individually. Change a " +
                        "single group from its info screen.",
                    color = NostrordColors.TextSecondary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(Spacing.md))
                NotificationLevelRow(
                    label = "All messages",
                    description = "Notify for every message.",
                    selected = defaultLevel == NotificationLevel.ALL,
                    onClick = { settings.setDefaultLevel(NotificationLevel.ALL) },
                )
                NotificationLevelRow(
                    label = "Mentions & replies only",
                    description = "Notify on replies, @mentions, and reactions to your messages.",
                    selected = defaultLevel == NotificationLevel.MENTIONS_REPLIES,
                    onClick = { settings.setDefaultLevel(NotificationLevel.MENTIONS_REPLIES) },
                )
                NotificationLevelRow(
                    label = "Muted",
                    description = "Silence everything, including replies, mentions and reactions.",
                    selected = defaultLevel == NotificationLevel.MUTED,
                    onClick = { settings.setDefaultLevel(NotificationLevel.MUTED) },
                )
            }
        }

        // ── System notification card ── (web-only; hidden on platforms where the
        // browser Notification API isn't available, to avoid showing UI the user
        // can't act on)
        if (systemSupported) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = NostrordShapes.cardShape,
                colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface),
            ) {
                Column(modifier = Modifier.padding(Spacing.xl)) {
                    ToggleRow(
                        icon = Icons.Default.Notifications,
                        label = "Desktop notifications",
                        description = "Show a system popup outside the app when a new " +
                            "message arrives and you're on another tab.",
                        checked = systemEnabled,
                        onCheckedChange = { settings.setSystemNotificationsEnabled(it) },
                    )

                    Spacer(Modifier.height(Spacing.lg))

                    // Permission status row
                    when (permission) {
                        org.nostr.nostrord.notifications.NotificationPermission.Granted -> {
                            PermissionStatusRow(
                                icon = Icons.Default.Check,
                                tint = NostrordColors.Success,
                                text = "Permission granted",
                            )
                        }
                        org.nostr.nostrord.notifications.NotificationPermission.Default -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "Permission not granted yet.",
                                    color = NostrordColors.TextSecondary,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = { notificationService.requestPermission() },
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                ) {
                                    Text("Request permission", color = NostrordColors.Primary, fontSize = 13.sp)
                                }
                            }
                        }
                        org.nostr.nostrord.notifications.NotificationPermission.Denied -> {
                            PermissionStatusRow(
                                icon = Icons.Default.Error,
                                tint = NostrordColors.Error,
                                text = "Blocked. Re-enable in your browser's site settings.",
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (checked) NostrordColors.Primary else NostrordColors.TextMuted,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = NostrordColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = description,
                style = NostrordTypography.Caption,
                color = NostrordColors.TextMuted,
            )
        }
        Spacer(Modifier.width(Spacing.md))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = NostrordColors.Primary,
                uncheckedThumbColor = NostrordColors.TextMuted,
                uncheckedTrackColor = NostrordColors.InputBackground,
            ),
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
        )
    }
}

@Composable
private fun NotificationLevelRow(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (isHovered) NostrordColors.SurfaceVariant else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        // Web-parity radio circle (matches .settings-radio in styles.css) so the
        // option group reads the same on both platforms.
        RadioCircle(
            selected = selected,
            modifier = Modifier.padding(top = 2.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (selected) NostrordColors.TextPrimary else NostrordColors.TextSecondary,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                text = description,
                style = NostrordTypography.Caption,
                color = NostrordColors.TextMuted,
            )
        }
    }
}

@Composable
private fun PermissionStatusRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(text = text, color = NostrordColors.TextSecondary, fontSize = 13.sp)
    }
}

// ── Appearance panel content ──────────────────────────────────────────────────

@Composable
private fun AppearancePanelContent(
    selectedTheme: AppTheme,
    onSelectTheme: (AppTheme) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = NostrordShapes.cardShape,
            colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(Spacing.xl)) {
                Text(
                    text = "THEME",
                    color = NostrordColors.TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(bottom = Spacing.md),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    ThemeCard("Dark", AppTheme.DARK, selectedTheme, Modifier.weight(1f), onSelectTheme)
                    ThemeCard("Light", AppTheme.LIGHT, selectedTheme, Modifier.weight(1f), onSelectTheme)
                    ThemeCard("System", AppTheme.SYSTEM, selectedTheme, Modifier.weight(1f), onSelectTheme)
                }
            }
        }
    }
}

@Composable
private fun ThemeCard(
    label: String,
    theme: AppTheme,
    selectedTheme: AppTheme,
    modifier: Modifier = Modifier,
    onSelect: (AppTheme) -> Unit,
) {
    val isSelected = theme == selectedTheme
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val borderColor = when {
        isSelected -> NostrordColors.Primary
        isHovered -> NostrordColors.TextMuted
        else -> NostrordColors.Divider
    }

    Column(
        modifier = modifier
            .clip(NostrordShapes.cardShape)
            .border(1.dp, borderColor, NostrordShapes.cardShape)
            .hoverable(interactionSource)
            .clickable { onSelect(theme) }
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(Spacing.sm),
    ) {
        ThemePreview(theme)
        Spacer(Modifier.height(Spacing.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = NostrordColors.Primary,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(Spacing.xs))
            }
            Text(label, color = NostrordColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ThemePreview(theme: AppTheme) {
    // Each card shows its own theme's surfaces from the fixed palettes, never the
    // active NostrordColors (otherwise the Dark card would turn white in light mode).
    // The pills are preview-only shades from the prototype, not palette tokens.
    val darkSurface = Color(DarkColorPalette.background)
    val lightSurface = Color(LightColorPalette.background)
    val lightPill = Color(0xFFC4C9D0)
    val darkPill = Color(0xFF4E5058)
    val background = when (theme) {
        AppTheme.DARK -> Brush.linearGradient(listOf(darkSurface, darkSurface))
        AppTheme.LIGHT -> Brush.linearGradient(listOf(lightSurface, lightSurface))
        AppTheme.SYSTEM -> Brush.linearGradient(listOf(darkSurface, lightSurface))
    }
    val pillShape = RoundedCornerShape(percent = 50)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, NostrordColors.Divider, RoundedCornerShape(6.dp))
            .background(background)
            .padding(6.dp),
    ) {
        Box(Modifier.width(32.dp).height(8.dp).clip(pillShape).background(NostrordColors.Primary))
        Spacer(Modifier.height(Spacing.xs))
        Box(
            Modifier.width(40.dp).height(6.dp).clip(pillShape)
                .background(if (theme == AppTheme.LIGHT) lightPill else darkPill),
        )
    }
}

// ── Media panel content ──────────────────────────────────────────────────────

@Composable
private fun MediaPanelContent(
    autoLoadMedia: Boolean,
    onToggleAutoLoad: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = NostrordShapes.cardShape,
            colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface),
        ) {
            SettingsToggleRow(
                label = "Auto-load media",
                description = "Automatically load images and videos in chat. When off, " +
                    "each one shows a tap-to-load placeholder so you choose what to fetch.",
                checked = autoLoadMedia,
                onCheckedChange = onToggleAutoLoad,
            )
        }

        MediaServerPanelContent()
    }
}

@Composable
private fun DmSettingsPanelContent(
    dmEnabled: Boolean,
    onToggleDm: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = NostrordShapes.cardShape,
            colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface),
        ) {
            SettingsToggleRow(
                label = "Direct messages",
                description = "Send and receive private messages. When off, the app stops " +
                    "fetching and decrypting your DMs and hides all direct-message features.",
                checked = dmEnabled,
                onCheckedChange = onToggleDm,
            )
        }
        // Relay editor only matters while DMs are on; hidden with the rest of the feature when off.
        if (dmEnabled) {
            DmEncryptionPanelContent()
            DmRelayPanelContent()
        }
    }
}

@Composable
private fun DmArchiveContent(vm: DmEncryptionViewModel) {
    val progress by vm.archiveProgress.collectAsState()
    val confirmOpen by vm.archiveConfirmOpen.collectAsState()
    val count by vm.archivableCount.collectAsState()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        when {
            progress.running -> {
                Text(
                    text = "Archiving ${progress.done} of ${progress.total}…",
                    style = NostrordTypography.Caption,
                    color = NostrordColors.TextSecondary,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { vm.cancelArchive() }) {
                        Text("Stop", color = NostrordColors.Error, style = NostrordTypography.Button)
                    }
                }
            }
            confirmOpen -> {
                Text(
                    text = "This publishes ${count ?: 0} wrapped copies of your message history to your DM " +
                        "relays, encrypted to your fast decryption key. New devices holding this key can " +
                        "then load your history without contacting your signer. Publishing takes a few " +
                        "minutes and reveals to your DM relays roughly how many messages you have.",
                    style = NostrordTypography.Caption,
                    color = NostrordColors.TextSecondary,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { vm.dismissArchiveConfirm() }) {
                        Text("Cancel", color = NostrordColors.TextSecondary, style = NostrordTypography.Button)
                    }
                    TextButton(onClick = { vm.confirmArchive() }, enabled = (count ?: 0) > 0) {
                        Text("Publish archive", color = NostrordColors.Primary, style = NostrordTypography.Button)
                    }
                }
            }
            else -> {
                // Reported here rather than through the VM error: the run outlives this screen.
                progress.error?.let {
                    Text(text = it, style = NostrordTypography.Caption, color = NostrordColors.Error)
                }
                if (progress.done > 0 && progress.error == null) {
                    Text(
                        text = "Archived ${progress.done} of ${progress.total}.",
                        style = NostrordTypography.Caption,
                        color = NostrordColors.Success,
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { vm.askToArchive() }) {
                        Text("Archive history to this key", color = NostrordColors.Primary, style = NostrordTypography.Button)
                    }
                }
            }
        }
    }
}

@Composable
private fun DmEncryptionPanelContent() {
    val activeAccountId by AppModule.accountStore.activeId.collectAsState()
    val vm = viewModel(key = activeAccountId) { DmEncryptionViewModel(AppModule.nostrRepository) }
    val state by vm.state.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val revealedKey by vm.revealedKey.collectAsState()
    val importInput by vm.importInput.collectAsState()
    val pairingState by vm.pairingState.collectAsState()

    // Nothing to offload when signing is already local.
    if (state is DmEncryptionManager.State.Unavailable) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = NostrordShapes.cardShape,
        colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Text(
                text = "Fast decryption key",
                style = NostrordTypography.Button,
                color = NostrordColors.TextPrimary,
            )
            Text(
                text = "Creates a separate encryption key stored on this device. New messages decrypt " +
                    "instantly without contacting your signer.",
                style = NostrordTypography.Caption,
                color = NostrordColors.TextSecondary,
            )

            when (val s = state) {
                is DmEncryptionManager.State.AnnouncedElsewhere -> {
                    InfoCard(
                        title = "Key held on another device",
                        titleColor = NostrordColors.Warning,
                        icon = Icons.Default.Key,
                        content = "This account already announced an encryption key from another device. " +
                            "Ask that device to send it, or paste it here from its Show key screen.",
                        isCompact = false,
                    )
                    when (val pairing = pairingState) {
                        is DmPairingManager.State.Requesting ->
                            Text(
                                text = "Waiting for your other device. Approve the request showing code " +
                                    "${pairing.code} there.",
                                style = NostrordTypography.Caption,
                                color = NostrordColors.TextSecondary,
                            )
                        is DmPairingManager.State.Failed ->
                            Text(text = pairing.reason, style = NostrordTypography.Caption, color = NostrordColors.Error)
                        else ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { vm.requestKeyFromOtherDevice() }) {
                                    Text("Ask my other device", color = NostrordColors.Primary, style = NostrordTypography.Button)
                                }
                            }
                    }
                    OutlinedTextField(
                        value = importInput,
                        onValueChange = { vm.setImportInput(it) },
                        label = { Text("Encryption key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(),
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { vm.importKey() }, enabled = importInput.isNotBlank()) {
                            Text("Import key", color = NostrordColors.Primary, style = NostrordTypography.Button)
                        }
                    }
                }
                is DmEncryptionManager.State.Active -> {
                    Text(
                        text = "Active. Senders are addressing ${s.encPubkey.take(12)}…",
                        style = NostrordTypography.Caption,
                        color = NostrordColors.Success,
                    )
                    // Another device of this account is asking for the key. The code comes from the
                    // request itself, so matching it against the other screen proves which one asked.
                    (pairingState as? DmPairingManager.State.IncomingRequest)?.let { request ->
                        InfoCard(
                            title = "Another device wants this key",
                            titleColor = NostrordColors.Warning,
                            icon = Icons.Default.Key,
                            content = "Approve only if that device is showing code ${request.code}.",
                            isCompact = false,
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { vm.declinePairing() }) {
                                Text("Decline", color = NostrordColors.TextSecondary, style = NostrordTypography.Button)
                            }
                            TextButton(onClick = { vm.approvePairing() }) {
                                Text("Send key", color = NostrordColors.Primary, style = NostrordTypography.Button)
                            }
                        }
                    }
                    // Same field as the private key on Backup: monospace, copy button, no wrapping
                    // of a value the user has to move to another device by hand.
                    revealedKey?.let {
                        IdentifierRow(ids = listOf(Identifier("hex", it)))
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { if (revealedKey == null) vm.revealKey() else vm.hideKey() }) {
                            Text(
                                if (revealedKey == null) "Show key" else "Hide key",
                                color = NostrordColors.Primary,
                                style = NostrordTypography.Button,
                            )
                        }
                        TextButton(onClick = { vm.rotate() }, enabled = !busy) {
                            Text("Replace key", color = NostrordColors.Primary, style = NostrordTypography.Button)
                        }
                        TextButton(onClick = { vm.disable() }, enabled = !busy) {
                            Text(
                                if (busy) "Working…" else "Turn off",
                                color = NostrordColors.Error,
                                style = NostrordTypography.Button,
                            )
                        }
                    }
                    Text(
                        text = "Replace the key if this device may have been compromised. Contacts start " +
                            "using the new one as they see it; the old key stays here so everything sent " +
                            "to it keeps opening.",
                        style = NostrordTypography.Caption,
                        color = NostrordColors.TextSecondary,
                    )
                    Text(
                        text = "Turning this off sends new messages back to your signer key. The key stays " +
                            "saved on this device because messages already sent to it can only ever be " +
                            "opened with it. It is never deleted, only no longer advertised.",
                        style = NostrordTypography.Caption,
                        color = NostrordColors.TextSecondary,
                    )
                    DmArchiveContent(vm)
                }
                else -> {
                    if (state is DmEncryptionManager.State.HeldNotAnnounced) {
                        Text(
                            text = "A key from an earlier session is still saved here. Turning this on " +
                                "advertises it again.",
                            style = NostrordTypography.Caption,
                            color = NostrordColors.TextSecondary,
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { vm.enable() }, enabled = !busy) {
                            Text(
                                if (busy) "Publishing…" else "Turn on",
                                color = NostrordColors.Primary,
                                style = NostrordTypography.Button,
                            )
                        }
                    }
                }
            }

            error?.let {
                Text(text = it, style = NostrordTypography.MessageBody, color = NostrordColors.Error)
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.xl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = NostrordColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = description,
                style = NostrordTypography.Caption,
                color = NostrordColors.TextMuted,
            )
        }
        Spacer(Modifier.width(Spacing.md))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = NostrordColors.Primary,
                uncheckedThumbColor = NostrordColors.TextMuted,
                uncheckedTrackColor = NostrordColors.InputBackground,
            ),
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
        )
    }
}

// ── Security panel content ───────────────────────────────────────────────────

@Composable
private fun SecurityPanelContent() {
    val activeAccountId by AppModule.accountStore.activeId.collectAsState()
    val accountSecurity = viewModel(key = activeAccountId) { SecurityViewModel() }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        // ncryptsec accounts are unlocked per session and own a rotatable password, independent
        // of the desktop app-store passphrase handled below. Plain-key accounts on platforms
        // without secure storage can opt into that protection here (web-only in practice).
        val isProtected by accountSecurity.isPasswordProtected.collectAsState()
        if (isProtected) {
            ChangeAccountPasswordForm(accountSecurity)
        } else if (accountSecurity.canProtect) {
            ProtectAccountPasswordForm(accountSecurity)
        }
        AppPassphraseContent()
    }
}

@Composable
private fun AppPassphraseContent() {
    when {
        !PassphraseSettings.isApplicable -> {
            InfoCard(
                title = "Not applicable",
                titleColor = NostrordColors.TextSecondary,
                icon = Icons.Default.Lightbulb,
                content = "This setting is only relevant on the desktop app.",
                isCompact = false,
            )
        }
        PassphraseSettings.usesKeychain() -> {
            InfoCard(
                title = "Protected by your OS keychain",
                titleColor = NostrordColors.Success,
                icon = Icons.Default.Check,
                content = "Your private key, bunker credentials and cached messages " +
                    "are encrypted with a key held in your operating system " +
                    "credential store (macOS Keychain, Windows Credential Manager " +
                    "or Linux Secret Service). No passphrase is needed.",
                isCompact = false,
            )
        }
        PassphraseSettings.usesPassphrase() -> {
            ChangePassphraseForm()
        }
        else -> {
            InfoCard(
                title = "No passphrase set",
                titleColor = NostrordColors.Warning,
                icon = Icons.Default.Warning,
                content = "Your OS keychain is not available. Nostrord is using an " +
                    "in-memory key for this session. Save a credential (private " +
                    "key or bunker URL) and you will be prompted to set a " +
                    "passphrase to persist your data securely.",
                isCompact = false,
            )
        }
    }
}

@Composable
private fun ChangeAccountPasswordForm(vm: SecurityViewModel) {
    val current by vm.current.collectAsState()
    val new by vm.new.collectAsState()
    val confirm by vm.confirm.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val successMessage by vm.successMessage.collectAsState()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        InfoCard(
            title = "Account password",
            titleColor = NostrordColors.TextSecondary,
            icon = Icons.Default.Key,
            content = "Your private key is encrypted with this password (NIP-49) and unlocked each " +
                "session. Choose a new password below to rotate it. It cannot be recovered if you " +
                "forget it.",
            isCompact = false,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = NostrordShapes.cardShape,
            colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                PassphraseField("Current password", current) { vm.setCurrent(it) }
                PassphraseField("New password", new) { vm.setNew(it) }
                PassphraseField("Confirm new password", confirm) { vm.setConfirm(it) }

                Text(
                    text = "At least 6 characters. Must match.",
                    style = NostrordTypography.Caption,
                    color = NostrordColors.TextSecondary,
                )

                error?.let {
                    Text(text = it, style = NostrordTypography.MessageBody, color = NostrordColors.Error)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = { vm.changePassword() },
                        enabled = !busy && current.isNotEmpty() && new.isNotEmpty() && confirm.isNotEmpty(),
                    ) {
                        Text(
                            if (busy) "Saving…" else "Change password",
                            color = NostrordColors.Primary,
                            style = NostrordTypography.Button,
                        )
                    }
                }
            }
        }

        successMessage?.let { SecuritySuccessCard(it) }
    }
}

@Composable
private fun SecuritySuccessCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = NostrordColors.Success),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(text, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ProtectAccountPasswordForm(vm: SecurityViewModel) {
    val new by vm.new.collectAsState()
    val confirm by vm.confirm.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        InfoCard(
            title = "Protect your key",
            titleColor = NostrordColors.Warning,
            icon = Icons.Default.Key,
            content = "Your private key is stored unencrypted on this device. Set a password to keep it " +
                "encrypted at rest (NIP-49); you will be asked for it when the app starts. It cannot be " +
                "recovered if you forget it.",
            isCompact = false,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = NostrordShapes.cardShape,
            colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                PassphraseField("Password", new) { vm.setNew(it) }
                PassphraseField("Confirm password", confirm) { vm.setConfirm(it) }

                Text(
                    text = "At least 6 characters. Must match.",
                    style = NostrordTypography.Caption,
                    color = NostrordColors.TextSecondary,
                )

                error?.let {
                    Text(text = it, style = NostrordTypography.MessageBody, color = NostrordColors.Error)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = { vm.protectWithPassword() },
                        enabled = !busy && new.isNotEmpty() && confirm.isNotEmpty(),
                    ) {
                        Text(
                            if (busy) "Encrypting…" else "Set password",
                            color = NostrordColors.Primary,
                            style = NostrordTypography.Button,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangePassphraseForm() {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }

    LaunchedEffect(success) {
        if (success) {
            delay(2000)
            success = false
        }
    }

    val canSubmit = current.isNotEmpty() && new.length >= 8 && new == confirm

    fun submit() {
        if (!canSubmit) return
        val ok = PassphraseSettings.changePassphrase(current, new)
        if (ok) {
            current = ""
            new = ""
            confirm = ""
            success = true
            error = null
        } else {
            error = "Current passphrase is incorrect."
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        InfoCard(
            title = "Passphrase-protected",
            titleColor = NostrordColors.TextSecondary,
            icon = Icons.Default.Key,
            content = "Your data is encrypted with a key derived from your passphrase. " +
                "Choose a new passphrase below to rotate it. The passphrase cannot " +
                "be recovered if you forget it.",
            isCompact = false,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = NostrordShapes.cardShape,
            colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                PassphraseField("Current passphrase", current) {
                    current = it
                    error = null
                }
                PassphraseField("New passphrase", new) {
                    new = it
                    error = null
                }
                PassphraseField("Confirm new passphrase", confirm) {
                    confirm = it
                    error = null
                }

                Text(
                    text = "At least 8 characters. Must match.",
                    style = NostrordTypography.Caption,
                    color = NostrordColors.TextSecondary,
                )

                error?.let {
                    Text(text = it, style = NostrordTypography.MessageBody, color = NostrordColors.Error)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = ::submit, enabled = canSubmit) {
                        Text("Change passphrase", color = NostrordColors.Primary, style = NostrordTypography.Button)
                    }
                }
            }
        }

        if (success) {
            Card(
                colors = CardDefaults.cardColors(containerColor = NostrordColors.Success),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Passphrase updated", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PassphraseField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    Column {
        Text(text = label, style = NostrordTypography.SectionHeader, color = NostrordColors.TextMuted)
        Spacer(Modifier.height(Spacing.sm))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NostrordColors.Primary,
                unfocusedBorderColor = NostrordColors.Divider,
                focusedContainerColor = NostrordColors.BackgroundFloating,
                unfocusedContainerColor = NostrordColors.BackgroundFloating,
                cursorColor = NostrordColors.Primary,
                focusedTextColor = NostrordColors.TextContent,
                unfocusedTextColor = NostrordColors.TextContent,
            ),
            shape = NostrordShapes.shapeSmall,
        )
    }
}
