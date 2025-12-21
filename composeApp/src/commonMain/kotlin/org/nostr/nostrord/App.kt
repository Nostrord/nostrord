package org.nostr.nostrord

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.nostr.nostrord.network.NostrRepository
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.ui.screens.home.HomeScreen
import org.nostr.nostrord.ui.screens.group.GroupScreen
import org.nostr.nostrord.ui.screens.relay.RelaySettingsScreen
import org.nostr.nostrord.ui.screens.login.NostrLoginScreen
import org.nostr.nostrord.ui.screens.backup.BackupScreen
import org.nostr.nostrord.ui.theme.NostrordColors

@Composable
fun App() {
    val isLoggedIn by NostrRepository.isLoggedIn.collectAsState()
    val isInitialized by NostrRepository.isInitialized.collectAsState()
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
    val scope = rememberCoroutineScope()

    // Remember scroll states across navigation
    val homeGridState = rememberLazyGridState()
    val relayListState = rememberLazyListState()

    // Initialize repository on app start (checks for saved credentials)
    // Add timeout to prevent indefinite loading on mobile browsers
    LaunchedEffect(Unit) {
        withTimeoutOrNull(30000) {
            NostrRepository.initialize()
        } ?: run {
            println("⚠️ Initialization timeout - forcing completion")
            // Force initialization to complete so the app is usable
            NostrRepository.forceInitialized()
        }
    }

    MaterialTheme {
        // Wait for initialization before deciding which screen to show
        if (!isInitialized) {
            // Show loading screen with app background color
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(NostrordColors.Background),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = NostrordColors.Primary)
            }
            return@MaterialTheme
        }

        if (!isLoggedIn) {
            // Show login screen if not logged in
            NostrLoginScreen {
                // After successful login, stay on home
                currentScreen = Screen.Home
            }
        } else {
            // Show main app if logged in
            when (val screen = currentScreen) {
                is Screen.Home -> {
                    HomeScreen(
                        gridState = homeGridState,
                        onNavigate = { newScreen ->
                            currentScreen = newScreen
                        }
                    )
                }
                is Screen.Group -> {
                    GroupScreen(
                        groupId = screen.groupId,
                        groupName = screen.groupName,
                        onBack = { currentScreen = Screen.Home }
                    )
                }
                is Screen.RelaySettings -> {
                    RelaySettingsScreen(
                        listState = relayListState,
                        onNavigate = { newScreen ->
                            currentScreen = newScreen
                        }
                    )
                }
                is Screen.PAGE1 -> {
                    HomeScreen(
                        gridState = homeGridState,
                        onNavigate = { newScreen ->
                            currentScreen = newScreen
                        }
                    )
                }
                is Screen.NostrLogin -> {
                    NostrLoginScreen {
                        currentScreen = Screen.Home
                    }
                }
                is Screen.BackupPrivateKey -> BackupScreen(
                    onNavigateBack = { currentScreen = Screen.Home }
                )
            }
        }
    }
}
