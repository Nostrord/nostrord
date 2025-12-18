package org.nostr.nostrord.ui.screens.group

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.ui.components.sidebars.GroupSidebar
import org.nostr.nostrord.ui.screens.group.components.MessageInput
import org.nostr.nostrord.ui.screens.group.components.MessagesList
import org.nostr.nostrord.ui.screens.group.model.ChatItem
import org.nostr.nostrord.ui.theme.NostrordColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreenMobile(
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
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = NostrordColors.Surface
            ) {
                GroupSidebar(
                    groupName = groupName,
                    selectedId = selectedChannel,
                    onSelect = { channel ->
                        scope.launch { drawerState.close() }
                        onChannelSelect(channel)
                    }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("#$selectedChannel", color = Color.White, fontWeight = FontWeight.Bold)
                            Text(
                                "$connectionStatus • ${messages.size} messages",
                                color = NostrordColors.TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    navigationIcon = {
                        Row {
                            IconButton(onClick = onBack) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Channels", tint = Color.White)
                            }
                        }
                    },
                    actions = {
                        if (!isJoined) {
                            TextButton(onClick = onJoinGroup) {
                                Text("Join", color = NostrordColors.Primary)
                            }
                        } else {
                            TextButton(onClick = onLeaveGroup) {
                                Text("Leave", color = NostrordColors.Error)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = NostrordColors.BackgroundDark
                    )
                )
            },
            containerColor = NostrordColors.Background
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
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
}
