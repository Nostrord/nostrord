package org.nostr.nostrord.ui.screens.home

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepository
import org.nostr.nostrord.ui.Screen

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
        val isCompact = maxWidth < 600.dp
        val isMedium = maxWidth in 600.dp..840.dp

        if (isCompact) {
            HomeScreenMobile(
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
            HomeScreenDesktop(
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
