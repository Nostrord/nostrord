package org.nostr.nostrord.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.ui.components.scrollbar.VerticalScrollbarWrapper
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.ui.components.sidebars.Sidebar
import org.nostr.nostrord.ui.screens.home.components.GroupCard
import org.nostr.nostrord.ui.theme.NostrordColors

@Composable
fun HomeScreenDesktop(
    gridState: LazyGridState,
    onNavigate: (Screen) -> Unit,
    connectionStatus: String,
    pubKey: String?,
    joinedGroups: Set<String>,
    groups: List<GroupMetadata>,
    filteredGroups: List<GroupMetadata>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    currentRelayUrl: String,
    gridColumns: Int
) {
    Row(modifier = Modifier.fillMaxSize()) {
        // Sidebar
        Sidebar(
            onNavigate = onNavigate,
            connectionStatus = connectionStatus,
            pubKey = pubKey,
            joinedGroups = joinedGroups,
            groups = groups
        )

        // Main content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(NostrordColors.Background)
        ) {
            // Top bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(NostrordColors.BackgroundDark)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Relay: $currentRelayUrl",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                    IconButton(onClick = { onNavigate(Screen.RelaySettings) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                    }
                }
            }

            // Header section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
            ) {
                Text("Explore", color = Color.White)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Groups found: ${filteredGroups.size}", color = NostrordColors.TextSecondary)
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    placeholder = { Text("Search groups...", color = NostrordColors.TextSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = Color.White),
                    shape = RoundedCornerShape(8.dp)
                )
            }

            // Grid
            if (filteredGroups.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No groups found", color = NostrordColors.TextSecondary)
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(gridColumns),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredGroups) { group ->
                            GroupCard(group = group, onClick = {
                                onNavigate(Screen.Group(group.id, group.name))
                            })
                        }
                    }

                    VerticalScrollbarWrapper(
                        gridState = gridState,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                    )
                }
            }
        }
    }
}
