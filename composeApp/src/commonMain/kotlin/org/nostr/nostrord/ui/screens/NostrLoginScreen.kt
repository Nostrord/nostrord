package org.nostr.nostrord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepository
import org.nostr.nostrord.nostr.KeyPair
import kotlin.random.Random
import androidx.compose.ui.platform.LocalUriHandler

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
            .background(Color(0xFF36393F))
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Nostr Login", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))

        TabRow(
            selectedTabIndex = tabs.indexOf(selectedTab),
            containerColor = Color(0xFF2F3136),
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
            LoginTab.PrivateKey -> PrivateKeyLogin(onLoginSuccess)
            LoginTab.Bunker -> BunkerLogin(onLoginSuccess)
        }
    }
}

@Composable
private fun PrivateKeyLogin(onLoginSuccess: () -> Unit) {
    var privateKey by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showGeneratedKey by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun generatePrivateKey(): String {
        val bytes = Random.Default.nextBytes(32)
        return bytes.joinToString("") { byte ->
            byte.toUByte().toString(16).padStart(2, '0')
        }
    }

    Column {
        OutlinedTextField(
            value = privateKey,
            onValueChange = { privateKey = it },
            placeholder = { Text("Enter your private key (hex or nsec)", color = Color.Gray) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = LocalTextStyle.current.copy(color = Color.White)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                scope.launch {
                    try {
                        val keyPair = KeyPair.fromPrivateKeyHex(privateKey)
                        val pubKey = keyPair.publicKeyHex
                        NostrRepository.loginSuspend(privateKey, pubKey)
                        onLoginSuccess()
                    } catch (e: Exception) {
                        errorMessage = "Invalid private key or login failed: ${e.message}"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Login", color = Color.White)
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                scope.launch {
                    try {
                        val newPrivateKey = generatePrivateKey()
                        privateKey = newPrivateKey
                        showGeneratedKey = true

                        val keyPair = KeyPair.fromPrivateKeyHex(newPrivateKey)
                        val pubKey = keyPair.publicKeyHex
                        NostrRepository.loginSuspend(newPrivateKey, pubKey)
                        onLoginSuccess()
                    } catch (e: Exception) {
                        errorMessage = "Failed to generate key or login: ${e.message}"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
        ) {
            Text("Instant Login (Generate New Key)")
        }

        if (showGeneratedKey && privateKey.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            GeneratedKeyCard(privateKey)
        }

        errorMessage?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, color = Color.Red)
        }
    }
}

@Composable
private fun BunkerLogin(onLoginSuccess: () -> Unit) {
    var bunkerUrl by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isConnecting by remember { mutableStateOf(false) }
    var connectionStatus by remember { mutableStateOf<String?>(null) }
    val authUrl by NostrRepository.authUrl.collectAsState()
    val scope = rememberCoroutineScope()
    
    val uriHandler = LocalUriHandler.current
    
    LaunchedEffect(authUrl) {
        authUrl?.let { url ->
            connectionStatus = "Opening browser for approval..."
            try {
                uriHandler.openUri(url)
            } catch (e: Exception) {
                errorMessage = "Could not open browser. Please open this URL manually."
            }
        }
    }

    Column {
        Text(
            "Connect to a remote signer (bunker) for secure key management.",
            color = Color.Gray,
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = bunkerUrl,
            onValueChange = { bunkerUrl = it },
            placeholder = { Text("bunker://<pubkey>?relay=wss://...", color = Color.Gray) },
            singleLine = false,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
            textStyle = LocalTextStyle.current.copy(color = Color.White),
            enabled = !isConnecting
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "Paste your bunker URL from nsec.app, Amber, or other NIP-46 signers",
            color = Color.Gray,
            style = MaterialTheme.typography.labelSmall
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                scope.launch {
                    isConnecting = true
                    errorMessage = null
                    connectionStatus = "Connecting..."
                    NostrRepository.clearAuthUrl()

                    try {
                        NostrRepository.loginWithBunker(bunkerUrl)
                        connectionStatus = "Connected!"
                        onLoginSuccess()
                    } catch (e: Exception) {
                        errorMessage = "Connection failed: ${e.message}"
                        connectionStatus = null
                    } finally {
                        isConnecting = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = bunkerUrl.isNotBlank() && !isConnecting
        ) {
            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(if (isConnecting) "Connecting..." else "Connect to Bunker", color = Color.White)
        }

        // Show auth URL if waiting for approval
        authUrl?.let { url ->
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2F3136))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "🔐 Approval Required",
                        color = Color(0xFFFFA500),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "A browser window should have opened. Please approve the connection there, then wait...",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        url,
                        color = Color(0xFF7289DA),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        connectionStatus?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, color = Color(0xFF7289DA), style = MaterialTheme.typography.bodySmall)
        }

        errorMessage?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, color = Color.Red)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2F3136))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "🔐 Why use a Bunker?",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "• Your private key never leaves the signer\n" +
                    "• Approve each signing request\n" +
                    "• Works with hardware signers\n" +
                    "• Revoke access anytime",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun GeneratedKeyCard(privateKey: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2F3136))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "⚠️ SAVE YOUR PRIVATE KEY",
                color = Color(0xFFFFA500),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "This is your only copy. Store it safely!",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                privateKey,
                color = Color(0xFF00FF00),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
