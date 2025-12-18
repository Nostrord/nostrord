package org.nostr.nostrord.ui.screens.group

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepository
import org.nostr.nostrord.ui.screens.group.model.buildChatItems
import org.nostr.nostrord.ui.theme.NostrordColors

@Composable
fun GroupScreen(
    groupId: String,
    groupName: String?,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var selectedChannel by remember { mutableStateOf("general") }

    val allMessages by NostrRepository.messages.collectAsState()
    val allGroupMessages = allMessages[groupId] ?: emptyList()

    val messages = remember(allGroupMessages, selectedChannel) {
        if (selectedChannel == "general") {
            allGroupMessages.filter { message ->
                !message.tags.any { it.size >= 2 && it[0] == "channel" }
            }
        } else {
            allGroupMessages.filter { message ->
                message.tags.any { it.size >= 2 && it[0] == "channel" && it[1] == selectedChannel }
            }
        }
    }

    val connectionState by NostrRepository.connectionState.collectAsState()
    val joinedGroups by NostrRepository.joinedGroups.collectAsState()
    val userMetadata by NostrRepository.userMetadata.collectAsState()

    var messageInput by remember { mutableStateOf("") }
    var showLeaveDialog by remember { mutableStateOf(false) }
    val isJoined = joinedGroups.contains(groupId)

    val connectionStatus = when (connectionState) {
        is NostrRepository.ConnectionState.Disconnected -> "Disconnected"
        is NostrRepository.ConnectionState.Connecting -> "Connecting..."
        is NostrRepository.ConnectionState.Connected -> "Connected"
        is NostrRepository.ConnectionState.Error -> "Error"
    }

    val chatItems = remember(messages) {
        buildChatItems(messages)
    }

    LaunchedEffect(groupId) {
        scope.launch {
            NostrRepository.requestGroupMessages(groupId, selectedChannel)
        }
    }

    LaunchedEffect(selectedChannel) {
        scope.launch {
            NostrRepository.requestGroupMessages(groupId, selectedChannel)
        }
    }

    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = { Text("Leave Group") },
            text = { Text("Are you sure you want to leave ${groupName ?: "this group"}? You can rejoin later if you change your mind.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            NostrRepository.leaveGroup(groupId)
                            showLeaveDialog = false
                            onBack()
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = NostrordColors.Error
                    )
                ) {
                    Text("Leave")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Responsive layout
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompact = maxWidth < 600.dp

        if (isCompact) {
            GroupScreenMobile(
                groupId = groupId,
                groupName = groupName,
                selectedChannel = selectedChannel,
                onChannelSelect = { selectedChannel = it },
                messages = messages,
                chatItems = chatItems,
                connectionStatus = connectionStatus,
                isJoined = isJoined,
                userMetadata = userMetadata,
                messageInput = messageInput,
                onMessageInputChange = { messageInput = it },
                onSendMessage = {
                    scope.launch {
                        NostrRepository.sendMessage(groupId, messageInput, selectedChannel)
                        messageInput = ""
                    }
                },
                onJoinGroup = {
                    scope.launch { NostrRepository.joinGroup(groupId) }
                },
                onLeaveGroup = { showLeaveDialog = true },
                onBack = onBack
            )
        } else {
            GroupScreenDesktop(
                groupId = groupId,
                groupName = groupName,
                selectedChannel = selectedChannel,
                onChannelSelect = { selectedChannel = it },
                messages = messages,
                chatItems = chatItems,
                connectionStatus = connectionStatus,
                isJoined = isJoined,
                userMetadata = userMetadata,
                messageInput = messageInput,
                onMessageInputChange = { messageInput = it },
                onSendMessage = {
                    scope.launch {
                        NostrRepository.sendMessage(groupId, messageInput, selectedChannel)
                        messageInput = ""
                    }
                },
                onJoinGroup = {
                    scope.launch { NostrRepository.joinGroup(groupId) }
                },
                onLeaveGroup = { showLeaveDialog = true },
                onBack = onBack
            )
        }
    }
}
