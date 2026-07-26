package org.nostr.nostrord.ui.screens.login.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.components.buttons.AppButton
import org.nostr.nostrord.ui.components.buttons.AppButtonSize
import org.nostr.nostrord.ui.screens.login.LoginViewModel
import org.nostr.nostrord.ui.theme.NostrordColors

/** NIP-55 external signer login (Amber). Only reachable on Android with a signer installed. */
@Composable
fun AmberLoginTab(onLoginSuccess: () -> Unit) {
    val vm = viewModel { LoginViewModel(AppModule.nostrRepository) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = Icons.Default.PhoneAndroid,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = NostrordColors.Primary,
        )

        Text(
            text = "Signer App Login",
            style = MaterialTheme.typography.titleMedium,
            color = NostrordColors.TextPrimary,
            textAlign = TextAlign.Center,
        )

        Text(
            text = "Connect using a NIP-55 signer app such as Amber. Your key stays in the signer.",
            style = MaterialTheme.typography.bodySmall,
            color = NostrordColors.TextMuted,
            textAlign = TextAlign.Center,
        )

        if (errorMessage != null) {
            Text(
                text = errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }

        AppButton(
            text = if (isLoading) "Connecting..." else "Connect Signer",
            onClick = {
                isLoading = true
                errorMessage = null
                vm.loginWithAmber { result ->
                    isLoading = false
                    if (result.isSuccess) {
                        onLoginSuccess()
                    } else {
                        errorMessage = result.exceptionOrNull()?.message ?: "Failed to connect to the signer app"
                    }
                }
            },
            enabled = !isLoading,
            size = AppButtonSize.Large,
            fullWidth = true,
            loading = isLoading,
        )
    }
}
