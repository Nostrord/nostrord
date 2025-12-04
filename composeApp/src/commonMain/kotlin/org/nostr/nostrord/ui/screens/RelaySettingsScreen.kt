package org.nostr.nostrord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepository
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.ui.components.Sidebar

data class RelayInfo(
    val url: String,
    var status: RelayStatus = RelayStatus.DISCONNECTED,
    var groupCount: Int? = null,
    var details: String = "No additional details available."
)

enum class RelayStatus {
    CONNECTED,
    DISCONNECTED,
    CONNECTING,
    ERROR
}

@Composable
fun RelaySettingsScreen(onNavigate: (Screen) -> Unit) {
    val scope = rememberCoroutineScope()
    
    val groups by NostrRepository.groups.collectAsState()
    val connectionState by NostrRepository.connectionState.collectAsState()
    val joinedGroups by NostrRepository.joinedGroups.collectAsState()
    val currentRelay by NostrRepository.currentRelayUrl.collectAsState()
    val pubKey = NostrRepository.getPublicKey()
    
    val connectionStatus = when (connectionState) {
        is NostrRepository.ConnectionState.Disconnected -> "Disconnected"
        is NostrRepository.ConnectionState.Connecting -> "Connecting..."
        is NostrRepository.ConnectionState.Connected -> "Connected"
        is NostrRepository.ConnectionState.Error ->
            "Error: ${(connectionState as NostrRepository.ConnectionState.Error).message}"
    }
    
    var relays by remember {
        mutableStateOf(
            listOf(
                RelayInfo("wss://groups.fiatjaf.com"),
                RelayInfo("wss://relay.groups.nip29.com"),
                RelayInfo("wss://groups.0xchat.com")
            )
        )
    }
    
    var newRelayUrl by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    
    LaunchedEffect(currentRelay) {
        relays = relays.map { relay ->
            relay.copy(
                status = if (relay.url == currentRelay) RelayStatus.CONNECTED else RelayStatus.DISCONNECTED
            )
        }
    }

    // Add Relay Dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add New Relay") },
            text = {
                Column {
                    Text("Enter the WebSocket URL of the relay:")
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = newRelayUrl,
                        onValueChange = { newRelayUrl = it },
                        placeholder = { Text("wss://example.com") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newRelayUrl.isNotBlank() && newRelayUrl.startsWith("wss://")) {
                            relays = relays + RelayInfo(newRelayUrl)
                            newRelayUrl = ""
                            showAddDialog = false
                        }
                    },
                    enabled = newRelayUrl.isNotBlank() && newRelayUrl.startsWith("wss://")
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showAddDialog = false
                    newRelayUrl = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Responsive layout
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompact = maxWidth < 600.dp

        if (isCompact) {
            MobileRelaySettingsScreen(
                relays = relays,
                currentRelay = currentRelay,
                connectionStatus = connectionStatus,
                pubKey = pubKey,
                joinedGroups = joinedGroups,
                groups = groups,
                onNavigate = onNavigate,
                onSelectRelay = { relayUrl ->
                    scope.launch {
                        NostrRepository.switchRelay(relayUrl)
                        relays = relays.map { r ->
                            r.copy(status = if (r.url == relayUrl) RelayStatus.CONNECTED else RelayStatus.DISCONNECTED)
                        }
                    }
                },
                onAddRelay = { showAddDialog = true }
            )
        } else {
            DesktopRelaySettingsScreen(
                relays = relays,
                currentRelay = currentRelay,
                connectionStatus = connectionStatus,
                pubKey = pubKey,
                joinedGroups = joinedGroups,
                groups = groups,
                onNavigate = onNavigate,
                onSelectRelay = { relayUrl ->
                    scope.launch {
                        NostrRepository.switchRelay(relayUrl)
                        relays = relays.map { r ->
                            r.copy(status = if (r.url == relayUrl) RelayStatus.CONNECTED else RelayStatus.DISCONNECTED)
                        }
                    }
                },
                onAddRelay = { showAddDialog = true }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileRelaySettingsScreen(
    relays: List<RelayInfo>,
    currentRelay: String,
    connectionStatus: String,
    pubKey: String?,
    joinedGroups: Set<String>,
    groups: List<org.nostr.nostrord.network.GroupMetadata>,
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
                        containerColor = Color(0xFF202225)
                    )
                )
            },
            containerColor = Color(0xFF36393F)
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

@Composable
private fun DesktopRelaySettingsScreen(
    relays: List<RelayInfo>,
    currentRelay: String,
    connectionStatus: String,
    pubKey: String?,
    joinedGroups: Set<String>,
    groups: List<org.nostr.nostrord.network.GroupMetadata>,
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
                .background(Color(0xFF36393F))
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(Color(0xFF202225))
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
            LazyColumn(
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
        }
    }
}

@Composable
private fun RelayCard(
    relay: RelayInfo,
    isActive: Boolean,
    isCompact: Boolean,
    onSelectRelay: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isActive) Modifier.border(2.dp, Color(0xFF5865F2), RoundedCornerShape(8.dp))
                else Modifier
            ),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF2F3136)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (isCompact) 12.dp else 16.dp)
        ) {
            // Relay URL
            Text(
                text = relay.url,
                color = Color.White,
                style = if (isCompact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(if (isCompact) 8.dp else 12.dp))
            
            // Status row
            Row(verticalAlignment = Alignment.CenterVertically) {
                val statusColor = when (relay.status) {
                    RelayStatus.CONNECTED -> Color(0xFF3BA55D)
                    RelayStatus.CONNECTING -> Color(0xFFFAA81A)
                    RelayStatus.ERROR -> Color(0xFFED4245)
                    RelayStatus.DISCONNECTED -> Color(0xFF747F8D)
                }
                
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(statusColor, shape = RoundedCornerShape(4.dp))
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = relay.status.name,
                    color = statusColor,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                
                if (!isCompact) {
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Text(
                        text = "Groups: ${relay.groupCount?.toString() ?: "N/A"}",
                        color = Color(0xFF99AAB5),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            
            if (!isCompact) {
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = relay.details,
                    color = Color(0xFF99AAB5),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            Spacer(modifier = Modifier.height(if (isCompact) 8.dp else 12.dp))
            
            // Select Button
            Button(
                onClick = onSelectRelay,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isActive) Color(0xFF3BA55D) else Color(0xFF5865F2),
                    disabledContainerColor = Color(0xFF3BA55D)
                ),
                enabled = !isActive
            ) {
                Text(
                    text = if (isActive) "Active" else if (isCompact) "Select" else "Select as Active Relay",
                    color = Color.White
                )            
            }
        }
    }
}

@Composable
private fun AddRelayCard(isCompact: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF2F3136)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (isCompact) 12.dp else 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Add New Relay",
                color = Color.White,
                style = if (isCompact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            if (!isCompact) {
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "Easily manage your groups in New Relay by including them in the list.",
                    color = Color(0xFF99AAB5),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            Spacer(modifier = Modifier.height(if (isCompact) 8.dp else 12.dp))
            
            Button(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF5865F2)
                )
            ) {
                Text(if (isCompact) "Add Relay" else "Add Relay URL")
            }
        }
    }
}
