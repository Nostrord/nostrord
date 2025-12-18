package org.nostr.nostrord.ui.screens.login.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepository
import org.nostr.nostrord.ui.theme.NostrordColors

@Composable
fun BunkerLoginTab(onLoginSuccess: () -> Unit) {
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
            enabled = bunkerUrl.isNotBlank() && !isConnecting,
            colors = ButtonDefaults.buttonColors(containerColor = NostrordColors.Primary)
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
                colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Approval Required",
                        color = NostrordColors.WarningOrange,
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
            Text(it, color = NostrordColors.Error)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Why use a Bunker?",
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
