package org.nostr.nostrord

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepository
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.ui.screens.home.HomeScreen
import org.nostr.nostrord.ui.screens.group.GroupScreen
import org.nostr.nostrord.ui.screens.relay.RelaySettingsScreen
import org.nostr.nostrord.ui.screens.login.NostrLoginScreen
import org.nostr.nostrord.ui.screens.backup.BackupScreen

@Composable
fun App() {
    val isLoggedIn by NostrRepository.isLoggedIn.collectAsState()
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
    val scope = rememberCoroutineScope()
    
    // Initialize repository on app start (checks for saved credentials)
    LaunchedEffect(Unit) {
        scope.launch {
            NostrRepository.initialize()
        }
    }
    
    MaterialTheme {
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
                    HomeScreen(onNavigate = { newScreen ->
                        currentScreen = newScreen
                    })
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
                        onNavigate = { newScreen ->
                            currentScreen = newScreen
                        }
                    )
                }
                is Screen.PAGE1 -> {
                    HomeScreen(onNavigate = { newScreen ->
                        currentScreen = newScreen
                    })
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
