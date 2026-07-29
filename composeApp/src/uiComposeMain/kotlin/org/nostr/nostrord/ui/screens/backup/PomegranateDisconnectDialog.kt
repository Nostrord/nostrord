package org.nostr.nostrord.ui.screens.backup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.components.buttons.AppButton
import org.nostr.nostrord.ui.components.buttons.AppButtonVariant
import org.nostr.nostrord.ui.components.cards.InfoCard
import org.nostr.nostrord.ui.components.forms.FormError
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography

/**
 * Confirmation for unlinking a Login-with-Google account from its central server. The
 * outcome depends on whether the nsec was exported first: with it the account converts to
 * a local-key login and stays signed in, without it it can no longer sign and is signed
 * out. Mirrors the web PomegranateDisconnectModal.
 */
@Composable
fun PomegranateDisconnectDialog(
    vm: BackupViewModel,
    onClose: () -> Unit,
) {
    val disconnect by vm.pomDisconnect.collectAsState()
    val pomError by vm.pomError.collectAsState()
    var acknowledged by remember { mutableStateOf(false) }

    val keyExported = vm.pomKeyExported
    val working = disconnect == BackupViewModel.PomegranateDisconnect.Working
    val done = disconnect as? BackupViewModel.PomegranateDisconnect.Done
    val notice = if (done != null) vm.pomDisconnectedNotice(done.convertedToLocal) else vm.pomDisconnectNotice(keyExported)

    // Closing while the Google popup is still open abandons the attempt, so the next one
    // starts clean instead of finding the flow stuck on "Disconnecting...".
    val close = {
        if (done == null) {
            vm.cancelPomegranateDisconnect()
            onClose()
        } else {
            // Dismissing after the disconnect finished still has to settle the account: an
            // unlinked bunker account left in place can no longer sign.
            vm.finishPomegranateDisconnect(onClose)
        }
    }

    AlertDialog(
        onDismissRequest = { close() },
        containerColor = NostrordColors.Surface,
        titleContentColor = NostrordColors.TextPrimary,
        textContentColor = NostrordColors.TextSecondary,
        title = {
            Text(if (done != null) "Disconnected from central server" else "Disconnect from central server")
        },
        text = {
            Column {
                InfoCard(
                    title = notice.title,
                    titleColor = if (notice.alert) NostrordColors.WarningOrange else NostrordColors.Primary,
                    content = notice.body,
                    icon = if (notice.alert) Icons.Default.Warning else Icons.Default.Info,
                    isCompact = true,
                )
                if (done == null) {
                    pomError?.let {
                        Spacer(Modifier.height(12.dp))
                        FormError(it)
                    }
                    // The ack only guards the lossy path: with the key exported the app
                    // keeps signing locally, so there is nothing to lose by continuing.
                    if (!keyExported) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(NostrordShapes.shapeSmall)
                                .clickable(enabled = !working) { acknowledged = !acknowledged }
                                .pointerHoverIcon(PointerIcon.Hand),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = acknowledged,
                                onCheckedChange = { acknowledged = it },
                                enabled = !working,
                                colors = CheckboxDefaults.colors(checkedColor = NostrordColors.Primary),
                            )
                            Text(
                                "I have safely backed up my private key",
                                color = NostrordColors.TextContent,
                                style = NostrordTypography.Caption,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (done != null) {
                AppButton(text = "Done", onClick = { vm.finishPomegranateDisconnect(onClose) })
            } else {
                AppButton(
                    text = if (working) "Disconnecting..." else "Disconnect from central server",
                    onClick = { vm.disconnectPomegranate() },
                    enabled = !working && (keyExported || acknowledged),
                    variant = AppButtonVariant.Danger,
                    loading = working,
                )
            }
        },
        dismissButton = {
            if (done == null) {
                TextButton(onClick = close) {
                    Text("Cancel", color = NostrordColors.TextSecondary)
                }
            }
        },
    )
}
