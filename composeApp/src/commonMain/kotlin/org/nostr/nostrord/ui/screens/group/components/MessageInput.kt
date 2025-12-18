package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.components.buttons.AppButton
import org.nostr.nostrord.ui.theme.NostrordColors

@Composable
fun MessageInput(
    isJoined: Boolean,
    selectedChannel: String,
    messageInput: String,
    onMessageInputChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onJoinGroup: () -> Unit
) {
    if (!isJoined) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(NostrordColors.SurfaceVariant)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Join the group to send messages",
                    color = NostrordColors.TextMuted,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = onJoinGroup,
                    colors = ButtonDefaults.textButtonColors(contentColor = NostrordColors.Primary)
                ) {
                    Text("Join Now")
                }
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(NostrordColors.SurfaceVariant)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = messageInput,
                onValueChange = onMessageInputChange,
                placeholder = { Text("Message #$selectedChannel") },
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { event ->
                        if (
                            event.type == KeyEventType.KeyDown &&
                            event.key == Key.Enter &&
                            event.isShiftPressed
                        ) {
                            if (messageInput.isNotBlank()) {
                                onSendMessage()
                            }
                            true
                        } else {
                            false
                        }
                    },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = NostrordColors.InputBackground,
                    unfocusedContainerColor = NostrordColors.InputBackground,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedPlaceholderColor = NostrordColors.TextMuted,
                    unfocusedPlaceholderColor = NostrordColors.TextMuted,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                shape = RoundedCornerShape(8.dp),
                singleLine = false,
                maxLines = 4
            )

            Spacer(modifier = Modifier.width(8.dp))

            AppButton(
                text = "Send",
                enabled = messageInput.isNotBlank(),
                onClick = onSendMessage,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}
