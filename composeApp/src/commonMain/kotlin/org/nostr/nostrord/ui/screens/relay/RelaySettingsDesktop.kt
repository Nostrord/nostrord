package org.nostr.nostrord.ui.screens.relay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.ui.components.scrollbar.VerticalScrollbarWrapper
import org.nostr.nostrord.ui.components.sidebars.Sidebar
import org.nostr.nostrord.ui.screens.relay.components.AddRelayCard
import org.nostr.nostrord.ui.screens.relay.components.RelayCard
import org.nostr.nostrord.ui.screens.relay.model.RelayInfo
import org.nostr.nostrord.ui.theme.NostrordColors

@Composable
fun RelaySettingsDesktop(
    listState: LazyListState,
    relays: List<RelayInfo>,
    currentRelay: String,
    connectionStatus: String,
    pubKey: String?,
    joinedGroups: Set<String>,
    groups: List<GroupMetadata>,
    onNavigate: (Screen) -> Unit,
    onSelectRelay: (String) -> Unit,
    onAddRelay: () -> Unit
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
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(NostrordColors.BackgroundDark)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onNavigate(Screen.Home) }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }

                Spacer(modifier = Modifier.width(16.dp))

                Text(
                    text = "Relay Settings",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // Content
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(relays) { relay ->
                        RelayCard(
                            relay = relay,
                            isActive = relay.url == currentRelay,
                            isCompact = false,
                            onSelectRelay = { onSelectRelay(relay.url) }
                        )
                    }

                    item {
                        AddRelayCard(isCompact = false, onClick = onAddRelay)
                    }
                }

                VerticalScrollbarWrapper(
                    listState = listState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                )
            }
        }
    }
}
