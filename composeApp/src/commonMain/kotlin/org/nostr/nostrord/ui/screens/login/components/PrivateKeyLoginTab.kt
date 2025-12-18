package org.nostr.nostrord.ui.screens.login.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepository
import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.ui.theme.NostrordColors
import kotlin.random.Random

@Composable
fun PrivateKeyLoginTab(onLoginSuccess: () -> Unit) {
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
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = NostrordColors.Primary)
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
            Text(it, color = NostrordColors.Error)
        }
    }
}
