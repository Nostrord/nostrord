package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.nostr.nostrord.ui.components.buttons.AppButton
import org.nostr.nostrord.ui.screens.group.model.MemberInfo
import org.nostr.nostrord.ui.theme.NostrordColors

@Composable
fun MessageInput(
    isJoined: Boolean,
    selectedChannel: String,
    messageInput: String,
    onMessageInputChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onJoinGroup: () -> Unit,
    groupMembers: List<MemberInfo> = emptyList(),
    mentions: Map<String, String> = emptyMap(), // displayName -> pubkey
    onMentionsChange: (Map<String, String>) -> Unit = {}
) {
    var showMentionPopup by remember { mutableStateOf(false) }
    var mentionStartIndex by remember { mutableStateOf(-1) }
    var mentionQuery by remember { mutableStateOf("") }

    // Track text changes to detect "@" trigger
    fun handleTextChange(newText: String) {
        val oldText = messageInput

        // Detect if "@" was just typed
        if (newText.length > oldText.length) {
            val addedChar = newText.getOrNull(newText.length - 1)
            if (addedChar == '@') {
                // Check if it's at the start or after a space
                val prevChar = newText.getOrNull(newText.length - 2)
                if (prevChar == null || prevChar.isWhitespace()) {
                    showMentionPopup = true
                    mentionStartIndex = newText.length - 1
                    mentionQuery = ""
                }
            } else if (showMentionPopup && mentionStartIndex >= 0) {
                // Update the query as user types after "@"
                val queryPart = newText.substring(mentionStartIndex + 1)
                // Stop if space is typed
                if (queryPart.contains(' ')) {
                    showMentionPopup = false
                    mentionStartIndex = -1
                    mentionQuery = ""
                } else {
                    mentionQuery = queryPart
                }
            }
        } else if (newText.length < oldText.length && showMentionPopup) {
            // Text was deleted
            if (mentionStartIndex >= newText.length) {
                // "@" was deleted
                showMentionPopup = false
                mentionStartIndex = -1
                mentionQuery = ""
            } else {
                // Update query
                mentionQuery = newText.substring(mentionStartIndex + 1)
            }
        }

        onMessageInputChange(newText)
    }

    fun handleMemberSelect(member: MemberInfo) {
        // Replace "@query" with "@displayName "
        val beforeMention = messageInput.substring(0, mentionStartIndex)
        val afterMention = if (mentionStartIndex + 1 + mentionQuery.length < messageInput.length) {
            messageInput.substring(mentionStartIndex + 1 + mentionQuery.length)
        } else {
            ""
        }
        val newText = "$beforeMention@${member.displayName} $afterMention"

        onMessageInputChange(newText)

        // Add displayName -> pubkey mapping
        if (!mentions.containsKey(member.displayName)) {
            onMentionsChange(mentions + (member.displayName to member.pubkey))
        }

        showMentionPopup = false
        mentionStartIndex = -1
        mentionQuery = ""
    }

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
        val density = LocalDensity.current

        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NostrordColors.SurfaceVariant)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = messageInput,
                    onValueChange = { handleTextChange(it) },
                    placeholder = { Text("Message #$selectedChannel") },
                    modifier = Modifier
                        .weight(1f)
                        .onPreviewKeyEvent { event ->
                            when {
                                // Escape closes the popup
                                event.type == KeyEventType.KeyDown &&
                                event.key == Key.Escape &&
                                showMentionPopup -> {
                                    showMentionPopup = false
                                    mentionStartIndex = -1
                                    mentionQuery = ""
                                    true
                                }
                                // Shift+Enter sends the message
                                event.type == KeyEventType.KeyDown &&
                                event.key == Key.Enter &&
                                event.isShiftPressed -> {
                                    if (messageInput.isNotBlank()) {
                                        onSendMessage()
                                    }
                                    true
                                }
                                else -> false
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

            // Floating mention popup above the input
            if (showMentionPopup && groupMembers.isNotEmpty()) {
                val popupOffsetY = with(density) { (-8).dp.roundToPx() }

                Popup(
                    alignment = Alignment.BottomStart,
                    offset = IntOffset(
                        x = with(density) { 12.dp.roundToPx() },
                        y = popupOffsetY
                    ),
                    onDismissRequest = {
                        showMentionPopup = false
                        mentionStartIndex = -1
                        mentionQuery = ""
                    },
                    properties = PopupProperties(focusable = false)
                ) {
                    MentionPopup(
                        members = groupMembers,
                        query = mentionQuery,
                        onMemberSelect = { handleMemberSelect(it) }
                    )
                }
            }
        }
    }
}
