package org.nostr.nostrord.ui.screens.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.ui.components.sidebars.GroupSidebar
import org.nostr.nostrord.ui.screens.group.components.MessageInput
import org.nostr.nostrord.ui.screens.group.components.MessagesList
import org.nostr.nostrord.ui.screens.group.model.ChatItem
import org.nostr.nostrord.ui.theme.NostrordColors

@Composable
fun GroupScreenDesktop(
    groupId: String,
    groupName: String?,
    selectedChannel: String,
    onChannelSelect: (String) -> Unit,
    messages: List<NostrGroupClient.NostrMessage>,
    chatItems: List<ChatItem>,
    connectionStatus: String,
    isJoined: Boolean,
    userMetadata: Map<String, UserMetadata>,
    messageInput: String,
    onMessageInputChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onJoinGroup: () -> Unit,
    onLeaveGroup: () -> Unit,
    onBack: () -> Unit
) {
    Row(modifier = Modifier.fillMaxSize()) {
        GroupSidebar(
            groupName = groupName,
            selectedId = selectedChannel,
            onSelect = onChannelSelect
        )

        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .background(NostrordColors.Background)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(NostrordColors.BackgroundDark)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "#$selectedChannel",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "$connectionStatus • ${messages.size} messages",
                        color = NostrordColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (!isJoined) {
                    Button(
                        onClick = onJoinGroup,
                        colors = ButtonDefaults.buttonColors(containerColor = NostrordColors.Primary)
                    ) {
                        Text("Join Group")
                    }
                } else {
                    Button(
                        onClick = onLeaveGroup,
                        colors = ButtonDefaults.buttonColors(containerColor = NostrordColors.Error)
                    ) {
                        Text("Leave Group")
                    }
                }
            }

            // Messages area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                MessagesList(
                    chatItems = chatItems,
                    userMetadata = userMetadata,
                    isJoined = isJoined
                )
            }

            // Input area
            MessageInput(
                isJoined = isJoined,
                selectedChannel = selectedChannel,
                messageInput = messageInput,
                onMessageInputChange = onMessageInputChange,
                onSendMessage = onSendMessage,
                onJoinGroup = onJoinGroup
            )
        }
    }
}
