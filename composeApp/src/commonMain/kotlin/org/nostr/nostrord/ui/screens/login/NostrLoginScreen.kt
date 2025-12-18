package org.nostr.nostrord.ui.screens.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.screens.login.components.BunkerLoginTab
import org.nostr.nostrord.ui.screens.login.components.PrivateKeyLoginTab
import org.nostr.nostrord.ui.theme.NostrordColors

sealed class LoginTab(val title: String) {
    object PrivateKey : LoginTab("Private Key")
    object Bunker : LoginTab("Bunker (NIP-46)")
}

@Composable
fun NostrLoginScreen(onLoginSuccess: () -> Unit) {
    var selectedTab by remember { mutableStateOf<LoginTab>(LoginTab.PrivateKey) }
    val tabs = listOf(LoginTab.PrivateKey, LoginTab.Bunker)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NostrordColors.Background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Nostr Login", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))

        TabRow(
            selectedTabIndex = tabs.indexOf(selectedTab),
            containerColor = NostrordColors.Surface,
            contentColor = Color.White
        ) {
            tabs.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = { Text(tab.title) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (selectedTab) {
            LoginTab.PrivateKey -> PrivateKeyLoginTab(onLoginSuccess)
            LoginTab.Bunker -> BunkerLoginTab(onLoginSuccess)
        }
    }
}
