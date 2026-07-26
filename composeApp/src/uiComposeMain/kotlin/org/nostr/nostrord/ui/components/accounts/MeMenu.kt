package org.nostr.nostrord.ui.components.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import org.nostr.nostrord.auth.Account
import org.nostr.nostrord.auth.AuthMethod
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.components.ConfirmDialog
import org.nostr.nostrord.ui.components.avatars.ProfileAvatar
import org.nostr.nostrord.ui.components.badges.UnreadBadge
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.utils.accountDisplayLabel
import org.nostr.nostrord.utils.rememberClipboardWriter

private val DESKTOP_WIDTH_THRESHOLD = 912.dp
private val DESKTOP_RAIL_WIDTH = 72.dp
private val DESKTOP_MENU_WIDTH = 320.dp

/**
 * Sheet/popup that opens from the user avatar. Lists every signed-in account
 * (one-tap switch), plus add-account, settings, and logout. Replaces the
 * standalone accounts screen.
 *
 * Desktop (wide windows): popup anchored above the rail avatar.
 * Mobile / narrow: modal bottom sheet.
 */
@Composable
fun MeMenu(
    visible: Boolean,
    onDismiss: () -> Unit,
    onAddAccount: () -> Unit,
    onSettings: () -> Unit,
    onSignOutWithChoice: () -> Unit,
) {
    if (!visible) return

    val density = LocalDensity.current
    val containerWidthPx = LocalWindowInfo.current.containerSize.width
    val containerWidthDp = with(density) { containerWidthPx.toDp() }
    val isDesktop = containerWidthDp >= DESKTOP_WIDTH_THRESHOLD

    val accounts by AppModule.accountStore.accounts.collectAsState()
    val activeId by AppModule.accountStore.activeId.collectAsState()
    val userMetadata by AppModule.nostrRepository.userMetadata.collectAsState()
    val activeNotifications by AppModule.notificationHistoryStore.entries.collectAsState()
    val activeAccount = accounts.firstOrNull { it.id == activeId }
    val scope = rememberCoroutineScope()

    var removeTarget by remember { mutableStateOf<Account?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var pendingError by remember { mutableStateOf<String?>(null) }

    // Fetch any account metadata not yet cached so rows show avatar/display name.
    LaunchedEffect(accounts) {
        val missing = accounts.map { it.pubkey }.filter { it !in userMetadata.keys }.toSet()
        if (missing.isNotEmpty()) {
            AppModule.nostrRepository.requestUserMetadata(missing)
        }
    }

    val onSwitchAccount: (Account) -> Unit = { account ->
        if (account.id != activeId && !isBusy) {
            isBusy = true
            pendingError = null
            // switchAccountAsync runs on the manager's long-lived scope, so
            // onDismiss()-then-leave-composition can't cancel the in-flight
            // reloadForActiveAccount mid-step. Using a rememberCoroutineScope
            // here was killing the new account's MY GROUPS load with
            // ForgottenCoroutineScopeException on JVM (account-switch fix).
            AppModule.accountManager.switchAccountAsync(account.id) { r ->
                isBusy = false
                if (r.isFailure) {
                    // A password-protected account can't be switched to directly: the
                    // failed credential load raises the unlock dialog instead, so close
                    // the menu quietly rather than surfacing a misleading error.
                    if (AppModule.nostrRepository.pendingUnlockAccount.value?.pubkey == account.pubkey) {
                        onDismiss()
                    } else {
                        pendingError = r.exceptionOrNull()?.message ?: "Switch failed"
                    }
                } else {
                    onDismiss()
                }
            }
        }
    }

    // Signing out of the active account while others are signed in routes to
    // the account chooser instead of auto-switching. Removing a non-active
    // account (or the last one) keeps the plain confirmation dialog.
    val requestRemove: (Account) -> Unit = { account ->
        if (account.id == activeId && accounts.size > 1) {
            onDismiss()
            onSignOutWithChoice()
        } else {
            removeTarget = account
        }
    }

    val content: @Composable ColumnScope.() -> Unit = {
        if (activeAccount != null) {
            MeHeader(account = activeAccount, metadata = userMetadata[activeAccount.pubkey])
            HorizontalDivider(color = NostrordColors.BackgroundDark)
        }
        pendingError?.let { msg ->
            Text(
                msg,
                color = NostrordColors.Error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            accounts.forEach { account ->
                val unread =
                    remember(account.pubkey, activeId, activeNotifications) {
                        AppModule.notificationHistoryStore.unreadCountFor(account.pubkey)
                    }
                AccountRow(
                    account = account,
                    metadata = userMetadata[account.pubkey],
                    isActive = account.id == activeId,
                    isBusy = isBusy,
                    unreadCount = unread,
                    onClick = { onSwitchAccount(account) },
                    onRemove = { requestRemove(account) },
                )
            }
        }
        HorizontalDivider(color = NostrordColors.BackgroundDark)
        ActionRow(
            icon = Icons.Default.Add,
            label = "Add account",
            onClick = {
                onDismiss()
                onAddAccount()
            },
        )
        HorizontalDivider(color = NostrordColors.BackgroundDark)
        ActionRow(
            icon = Icons.Default.Settings,
            label = "Settings",
            onClick = {
                onDismiss()
                onSettings()
            },
        )
        HorizontalDivider(color = NostrordColors.BackgroundDark)
        ActionRow(
            icon = Icons.AutoMirrored.Filled.Logout,
            label = "Sign out",
            tint = NostrordColors.Error,
            onClick = { activeAccount?.let { requestRemove(it) } },
        )
        Spacer(Modifier.height(8.dp))
    }

    if (isDesktop) {
        DesktopMeMenu(onDismiss = onDismiss, content = content)
    } else {
        MobileMeMenu(onDismiss = onDismiss, content = content)
    }

    removeTarget?.let { target ->
        val isActiveTarget = target.id == activeId
        // Same fallback the AccountManager will pick, surfaced in the copy
        // so the user knows which identity they'll land on after confirming.
        val fallback =
            if (isActiveTarget) {
                accounts.filter { it.id != target.id }.maxByOrNull { it.addedAt }
            } else {
                null
            }
        val fallbackLabel =
            fallback?.let { fb ->
                val label =
                    userMetadata[fb.pubkey]?.displayName?.takeIf { it.isNotBlank() }
                        ?: userMetadata[fb.pubkey]?.name?.takeIf { it.isNotBlank() }
                        ?: fb.label
                accountDisplayLabel(label, fb.pubkey)
            }
        // Resolve the same way the avatar/list rows do: kind:0 displayName,
        // then kind:0 name, falling back to the persisted "Account N" only when
        // metadata is still unknown. A bare npub fallback is shortened so it never
        // blows up the dialog title.
        val targetLabel =
            accountDisplayLabel(
                userMetadata[target.pubkey]?.displayName?.takeIf { it.isNotBlank() }
                    ?: userMetadata[target.pubkey]?.name?.takeIf { it.isNotBlank() }
                    ?: target.label,
                target.pubkey,
            )
        RemoveAccountDialog(
            accountLabel = targetLabel,
            method = target.authMethod,
            isActive = isActiveTarget,
            fallbackLabel = fallbackLabel,
            isBusy = isBusy,
            onDismiss = { if (!isBusy) removeTarget = null },
            onConfirm = {
                if (isBusy) return@RemoveAccountDialog
                isBusy = true
                scope.launch {
                    AppModule.accountManager.removeAccount(target.id)
                    isBusy = false
                    removeTarget = null
                }
            },
        )
    }
}

@Composable
private fun DesktopMeMenu(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Anchor the popup so its bottom-left corner sits just above the rail
    // avatar (rail is DESKTOP_RAIL_WIDTH from the left edge; avatar is at the
    // bottom). BottomStart alignment + offset achieves this without measuring
    // the avatar's exact position.
    val density = LocalDensity.current
    val offsetX = with(density) { DESKTOP_RAIL_WIDTH.roundToPx() }
    val offsetY = with(density) { (-12).dp.roundToPx() }
    Popup(
        alignment = Alignment.BottomStart,
        offset = IntOffset(offsetX, offsetY),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnClickOutside = true),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = NostrordColors.Surface,
            shadowElevation = 12.dp,
            tonalElevation = 4.dp,
            modifier = Modifier.widthIn(min = DESKTOP_MENU_WIDTH, max = 360.dp),
        ) {
            Column(content = content)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileMeMenu(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NostrordColors.Surface,
        dragHandle = null,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun MeHeader(
    account: Account,
    metadata: UserMetadata?,
) {
    val displayName =
        metadata?.displayName?.takeIf { it.isNotBlank() }
            ?: metadata?.name?.takeIf { it.isNotBlank() }
            ?: account.label
    val npub =
        remember(account.pubkey) {
            try {
                Nip19.encodeNpub(account.pubkey)
            } catch (_: Exception) {
                account.pubkey
            }
        }
    val copy = rememberClipboardWriter()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1500)
            copied = false
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileAvatar(
            imageUrl = metadata?.picture?.takeIf { it.isNotBlank() },
            displayName = displayName,
            pubkey = account.pubkey,
            size = 56.dp,
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                displayName,
                color = NostrordColors.TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier =
                Modifier
                    .clickable {
                        copy(npub)
                        copied = true
                    }.background(NostrordColors.BackgroundDark, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    npub.take(16) + "…",
                    color = NostrordColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = if (copied) "Copied" else "Copy npub",
                    tint = NostrordColors.TextSecondary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

@Composable
private fun AccountRow(
    account: Account,
    metadata: UserMetadata?,
    isActive: Boolean,
    isBusy: Boolean,
    unreadCount: Int,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val profileName =
        metadata?.displayName?.takeIf { it.isNotBlank() }
            ?: metadata?.name?.takeIf { it.isNotBlank() }
    val displayName = profileName ?: account.label

    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !isBusy && !isActive, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            ProfileAvatar(
                imageUrl = metadata?.picture?.takeIf { it.isNotBlank() },
                displayName = displayName,
                pubkey = account.pubkey,
                size = 36.dp,
            )
            if (unreadCount > 0) {
                UnreadBadge(
                    count = unreadCount,
                    modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-4).dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                displayName,
                color = NostrordColors.TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                authMethodLabel(account.authMethod),
                color = NostrordColors.TextMuted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isActive) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Active",
                tint = NostrordColors.Primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        IconButton(onClick = onRemove, enabled = !isBusy) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Remove",
                tint = NostrordColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = NostrordColors.TextPrimary,
) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = tint, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RemoveAccountDialog(
    accountLabel: String,
    method: AuthMethod,
    isActive: Boolean,
    fallbackLabel: String?,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    // Strings live in commonMain so web and native share the exact copy. The
    // body branches on `method` so Bunker / NIP-07 users see accurate wording —
    // the LOCAL "Credentials and local data" line was misleading for them.
    val title = org.nostr.nostrord.auth.removeAccountDialogTitle(isActive, accountLabel)
    val body = org.nostr.nostrord.auth.removeAccountDialogBody(isActive, accountLabel, fallbackLabel, method)
    val confirmLabel = org.nostr.nostrord.auth.removeAccountConfirmLabel(isActive)
    val busyLabel = org.nostr.nostrord.auth.removeAccountBusyLabel(isActive)
    ConfirmDialog(
        title = title,
        message = body,
        confirmLabel = if (isBusy) busyLabel else confirmLabel,
        destructive = true,
        confirmEnabled = !isBusy,
        cancelEnabled = !isBusy,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

private fun authMethodLabel(method: AuthMethod): String = when (method) {
    AuthMethod.LOCAL -> "Private key"
    AuthMethod.BUNKER -> "Bunker (NIP-46)"
    AuthMethod.NIP07 -> "Browser extension (NIP-07)"
    AuthMethod.AMBER -> "Signer app (NIP-55)"
}
