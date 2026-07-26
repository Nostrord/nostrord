package org.nostr.nostrord.ui.components.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.nostr.nostrord.auth.Account
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.ui.components.avatars.ProfileAvatar
import org.nostr.nostrord.ui.theme.NostrordColors

/**
 * Centered modal shown when the user signs out of the active account while other
 * accounts are still signed in. Instead of silently dropping the user onto a
 * fallback identity, it lists the remaining accounts (one-tap continue) and
 * offers a new login. The active account is only wiped once the user picks where
 * to go, so dismissing leaves them on the current account.
 */
@Composable
fun AccountChooserDialog(
    visible: Boolean,
    signOutAccountId: String?,
    onDismiss: () -> Unit,
    onNewLogin: (signOutAccountId: String) -> Unit,
) {
    if (!visible || signOutAccountId == null) return

    val accounts by AppModule.accountStore.accounts.collectAsState()
    val userMetadata by AppModule.nostrRepository.userMetadata.collectAsState()
    val scope = rememberCoroutineScope()

    val current = accounts.firstOrNull { it.id == signOutAccountId }
    val others = remember(accounts, signOutAccountId) { accounts.filter { it.id != signOutAccountId } }

    var isBusy by remember { mutableStateOf(false) }
    var pendingError by remember { mutableStateOf<String?>(null) }

    // Fetch any account metadata not yet cached so rows show avatar/display name.
    LaunchedEffect(accounts) {
        val missing = accounts.map { it.pubkey }.filter { it !in userMetadata.keys }.toSet()
        if (missing.isNotEmpty()) {
            AppModule.nostrRepository.requestUserMetadata(missing)
        }
    }

    // The account being signed out has gone away (e.g. removed elsewhere) or no
    // alternatives remain — nothing left to choose, so close.
    if (current == null || others.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    val currentLabel = displayLabel(current, userMetadata[current.pubkey])

    val onContinueAs: (Account) -> Unit = { account ->
        if (!isBusy) {
            isBusy = true
            pendingError = null
            scope.launch {
                val r = AppModule.accountManager.removeAndSwitch(signOutAccountId, account.id)
                isBusy = false
                if (r.isFailure) {
                    pendingError = r.exceptionOrNull()?.message ?: "Could not switch account"
                } else {
                    onDismiss()
                }
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = NostrordColors.Surface,
            tonalElevation = 4.dp,
            modifier = Modifier.widthIn(min = 280.dp, max = 340.dp),
        ) {
            Column {
                Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                    Text(
                        "Choose an account",
                        color = NostrordColors.TextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Sign out of \"$currentLabel\" and continue as one of your other " +
                            "accounts, or add a new login.",
                        color = NostrordColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                HorizontalDivider(color = NostrordColors.BackgroundDark)

                pendingError?.let { msg ->
                    Text(
                        msg,
                        color = NostrordColors.Error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                Column(
                    modifier =
                    Modifier
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                ) {
                    others.forEach { account ->
                        ChooserAccountRow(
                            account = account,
                            metadata = userMetadata[account.pubkey],
                            isBusy = isBusy,
                            onClick = { onContinueAs(account) },
                        )
                    }
                }
                HorizontalDivider(color = NostrordColors.BackgroundDark)
                ChooserActionRow(
                    icon = Icons.Default.PersonAdd,
                    label = "Add a new login",
                    isBusy = isBusy,
                    onClick = { if (!isBusy) onNewLogin(signOutAccountId) },
                )
                HorizontalDivider(color = NostrordColors.BackgroundDark)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss, enabled = !isBusy) {
                        Text("Cancel", color = NostrordColors.TextSecondary)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ChooserAccountRow(
    account: Account,
    metadata: UserMetadata?,
    isBusy: Boolean,
    onClick: () -> Unit,
) {
    val displayName = displayLabel(account, metadata)
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !isBusy, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileAvatar(
            imageUrl = metadata?.picture?.takeIf { it.isNotBlank() },
            displayName = displayName,
            pubkey = account.pubkey,
            size = 36.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                displayName,
                color = NostrordColors.TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                authMethodChooserLabel(account),
                color = NostrordColors.TextMuted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = NostrordColors.TextSecondary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ChooserActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isBusy: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !isBusy, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = NostrordColors.TextPrimary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = NostrordColors.TextPrimary, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun displayLabel(
    account: Account,
    metadata: UserMetadata?,
): String = metadata?.displayName?.takeIf { it.isNotBlank() }
    ?: metadata?.name?.takeIf { it.isNotBlank() }
    ?: account.label

private fun authMethodChooserLabel(account: Account): String = when (account.authMethod) {
    org.nostr.nostrord.auth.AuthMethod.LOCAL -> "Private key"
    org.nostr.nostrord.auth.AuthMethod.BUNKER -> "Bunker (NIP-46)"
    org.nostr.nostrord.auth.AuthMethod.NIP07 -> "Browser extension (NIP-07)"
    org.nostr.nostrord.auth.AuthMethod.AMBER -> "Signer app (NIP-55)"
}
