package org.nostr.nostrord.ui.screens.relay

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.ui.components.sidebars.Sidebar
import org.nostr.nostrord.ui.screens.relay.components.AddRelayCard
import org.nostr.nostrord.ui.screens.relay.components.RelayCard
import org.nostr.nostrord.ui.screens.relay.model.RelayInfo
import org.nostr.nostrord.ui.theme.NostrordColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelaySettingsMobile(
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
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = NostrordColors.Surface
            ) {
                Sidebar(
                    onNavigate = { screen ->
                        scope.launch { drawerState.close() }
                        onNavigate(screen)
                    },
                    connectionStatus = connectionStatus,
                    pubKey = pubKey,
                    joinedGroups = joinedGroups,
                    groups = groups
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Relay Settings", color = Color.White, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        Row {
                            IconButton(onClick = { onNavigate(Screen.Home) }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color.White)
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(relays) { relay ->
                    RelayCard(
                        relay = relay,
                        isActive = relay.url == currentRelay,
                        isCompact = true,
                        onSelectRelay = { onSelectRelay(relay.url) }
                    )
                }

                item {
                    AddRelayCard(isCompact = true, onClick = onAddRelay)
                }
            }
        }
    }
}
