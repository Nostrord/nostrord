package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing

/**
 * The single confirm step every join goes through, so the public/private choice is offered the
 * same way from the composer bar, the header button and an invite code. Mirrors the web
 * `JoinGroupConfirmModal`.
 *
 * The choice belongs here rather than after the join: a group added publicly and made private
 * afterwards has already gone out in the clear once, and relays keep that version.
 */
@Composable
fun JoinGroupConfirmDialog(
    groupName: String?,
    isGroupClosed: Boolean,
    onConfirm: (listPrivately: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var listPrivately by remember { mutableStateOf(false) }
    val name = groupName?.takeIf { it.isNotBlank() } ?: "this group"

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(480.dp),
        containerColor = NostrordColors.Surface,
        titleContentColor = NostrordColors.TextPrimary,
        textContentColor = NostrordColors.TextSecondary,
        shape = RoundedCornerShape(16.dp),
        title = { Text(if (isGroupClosed) "Request to join $name" else "Join $name") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    if (isGroupClosed) {
                        "An admin has to approve your request before you can post."
                    } else {
                        "The group is added to your list so it follows you to your other apps."
                    },
                )
                Row(
                    modifier = Modifier
                        .clickable { listPrivately = !listPrivately }
                        .pointerHoverIcon(PointerIcon.Hand),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = listPrivately,
                        onCheckedChange = { listPrivately = it },
                        colors = CheckboxDefaults.colors(checkedColor = NostrordColors.Primary),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Column {
                        Text("Add privately", color = NostrordColors.TextPrimary)
                        Text(
                            "Encrypted on your list, so nobody can see you are in this group. " +
                                "People who follow you stop discovering it.",
                            style = NostrordTypography.Caption,
                            color = NostrordColors.TextMuted,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(listPrivately) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NostrordColors.Primary,
                    contentColor = Color.White,
                ),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(if (isGroupClosed) "Send request" else "Join")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = NostrordColors.TextSecondary)
            }
        },
    )
}
