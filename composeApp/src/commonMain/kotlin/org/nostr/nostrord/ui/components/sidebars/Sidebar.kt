package org.nostr.nostrord.ui.components.sidebars

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.NostrRepository
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.util.generateColorFromString

@Composable
fun Sidebar(
    onNavigate: (Screen) -> Unit,
    connectionStatus: String,
    pubKey: String?,
    joinedGroups: Set<String>,
    groups: List<GroupMetadata>
) {
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .widthIn(min = 80.dp, max = 250.dp)
            .fillMaxHeight()
            .background(NostrordColors.Surface)
            .padding(8.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Nostrord",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(8.dp)
            )

            IconButton(onClick = { onNavigate(Screen.BackupPrivateKey) }) {
                Icon(
                    Icons.Default.Key,
                    contentDescription = "Backup Private Key",
                    tint = Color.White
                )
            }
        }

        // Connection info
        Text(
            connectionStatus,
            color = NostrordColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )

        // Logged-in pubkey
        if (pubKey != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .background(NostrordColors.Surface, shape = RoundedCornerShape(8.dp))
                    .padding(vertical = 6.dp, horizontal = 12.dp)
            ) {
                Text(
                    text = "Logged in as: ${pubKey.take(8)}…",
                    color = NostrordColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    scope.launch {
                        NostrRepository.logout()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NostrordColors.Error)
            ) {
                Text("Logout", color = Color.White)
            }
        } else {
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { onNavigate(Screen.NostrLogin) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NostrordColors.Primary)
            ) {
                Text("Login with Nostr", color = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "Joined Groups",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(8.dp)
        )

        if (joinedGroups.isEmpty()) {
            Text(
                "No joined groups yet",
                color = NostrordColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp)
            )
        } else {
            val joinedList = joinedGroups.toList()
            Box(modifier = Modifier.fillMaxSize()) {
                val listState = rememberLazyListState()

                LazyColumn(state = listState) {
                    items(joinedList.size) { index ->
                        val groupId = joinedList[index]
                        val group = groups.find { it.id == groupId }
                        val groupName = group?.name ?: groupId

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .background(NostrordColors.Background, RoundedCornerShape(8.dp))
                                .clickable { onNavigate(Screen.Group(groupId, group?.name)) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(generateColorFromString(groupId)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = groupName.take(1).uppercase(),
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Text(
                                text = groupName,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                VerticalScrollbar(
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    adapter = rememberScrollbarAdapter(listState)
                )
            }
        }
    }
}
