package org.nostr.nostrord.ui.screens.relay

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepository
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.ui.screens.relay.model.RelayInfo
import org.nostr.nostrord.ui.screens.relay.model.RelayStatus

@Composable
fun RelaySettingsScreen(
    listState: LazyListState = rememberLazyListState(),
    onNavigate: (Screen) -> Unit
) {
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
            RelaySettingsMobile(
                listState = listState,
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
            RelaySettingsDesktop(
                listState = listState,
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
