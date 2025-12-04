package org.nostr.nostrord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepository
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.ui.components.Sidebar
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onNavigate: (Screen) -> Unit) {
    val scope = rememberCoroutineScope()

    val groups by NostrRepository.groups.collectAsState()
    val connectionState by NostrRepository.connectionState.collectAsState()
    val currentRelayUrl by NostrRepository.currentRelayUrl.collectAsState()
    val joinedGroups by NostrRepository.joinedGroups.collectAsState()

    var searchQuery by remember { mutableStateOf("") }

    val filteredGroups = remember(groups, searchQuery) {
        if (searchQuery.isBlank()) groups
        else groups.filter {
            it.name?.contains(searchQuery, ignoreCase = true) == true ||
                    it.id.contains(searchQuery, ignoreCase = true)
        }
    }

    val connectionStatus = when (connectionState) {
        is NostrRepository.ConnectionState.Disconnected -> "Disconnected"
        is NostrRepository.ConnectionState.Connecting -> "Connecting..."
        is NostrRepository.ConnectionState.Connected -> "Connected"
        is NostrRepository.ConnectionState.Error ->
            "Error: ${(connectionState as NostrRepository.ConnectionState.Error).message}"
    }

    val pubKey = NostrRepository.getPublicKey()

    LaunchedEffect(Unit) {
        scope.launch {
            NostrRepository.connect()
        }
    }

    // Detect screen width
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompact = maxWidth < 600.dp  // Mobile
        val isMedium = maxWidth in 600.dp..840.dp  // Tablet
        // Large = > 840.dp (Desktop)

        if (isCompact) {
            // MOBILE LAYOUT - Drawer + single column
            MobileHomeScreen(
                onNavigate = onNavigate,
                connectionStatus = connectionStatus,
                pubKey = pubKey,
                joinedGroups = joinedGroups,
                groups = groups,
                filteredGroups = filteredGroups,
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                currentRelayUrl = currentRelayUrl
            )
        } else {
            // DESKTOP/TABLET LAYOUT - Sidebar + grid
            DesktopHomeScreen(
                onNavigate = onNavigate,
                connectionStatus = connectionStatus,
                pubKey = pubKey,
                joinedGroups = joinedGroups,
                groups = groups,
                filteredGroups = filteredGroups,
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                currentRelayUrl = currentRelayUrl,
                gridColumns = if (isMedium) 2 else 3
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileHomeScreen(
    onNavigate: (Screen) -> Unit,
    connectionStatus: String,
    pubKey: String?,
    joinedGroups: Set<String>,
    groups: List<org.nostr.nostrord.network.GroupMetadata>,
    filteredGroups: List<org.nostr.nostrord.network.GroupMetadata>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    currentRelayUrl: String
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color(0xFF2F3136)
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
                    title = { Text("Nostrord", color = Color.White) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color.White)
                        }
                    },
                    actions = {
                        IconButton(onClick = { onNavigate(Screen.RelaySettings) }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF202225)
                    )
                )
            },
            containerColor = Color(0xFF36393F)
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Search and header
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text("Explore", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Groups found: ${filteredGroups.size}", color = Color(0xFF99AAB5))
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchChange,
                        placeholder = { Text("Search groups...", color = Color(0xFF99AAB5)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(color = Color.White),
                        shape = RoundedCornerShape(8.dp)
                    )
                }

                // Single column grid for mobile
                if (filteredGroups.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No groups found", color = Color(0xFF99AAB5))
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(1),  // Single column on mobile
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredGroups) { group ->
                            GroupCard(group = group, onClick = {
                                onNavigate(Screen.Group(group.id, group.name))
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopHomeScreen(
    onNavigate: (Screen) -> Unit,
    connectionStatus: String,
    pubKey: String?,
    joinedGroups: Set<String>,
    groups: List<org.nostr.nostrord.network.GroupMetadata>,
    filteredGroups: List<org.nostr.nostrord.network.GroupMetadata>,
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
                .background(Color(0xFF36393F))
        ) {
            // Top bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(Color(0xFF202225))
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
                Text("Groups found: ${filteredGroups.size}", color = Color(0xFF99AAB5))
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    placeholder = { Text("Search groups...", color = Color(0xFF99AAB5)) },
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
                    Text("No groups found", color = Color(0xFF99AAB5))
                }
            } else {
                LazyVerticalGrid(
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
            }
        }
    }
}

@Composable
private fun GroupCard(
    group: org.nostr.nostrord.network.GroupMetadata,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2F3136), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(generateColorFromString(group.id)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = (group.name ?: group.id).take(1).uppercase(),
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = group.name ?: group.id,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }

        group.about?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = it,
                color = Color(0xFF99AAB5),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2
            )
        }
    }
}

private fun generateColorFromString(str: String): Color {
    val hash = str.hashCode()
    val colors = listOf(
        Color(0xFF5865F2), Color(0xFF57F287), Color(0xFFFEE75C), Color(0xFFEB459E),
        Color(0xFFED4245), Color(0xFFFF6B6B), Color(0xFF4ECDC4), Color(0xFF95E1D3)
    )
    return colors[abs(hash) % colors.size]
}
