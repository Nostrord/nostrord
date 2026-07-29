package org.nostr.nostrord.ui.screens.login.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nostr.nostrord.auth.pomegranate.PomegranateConfig
import org.nostr.nostrord.auth.pomegranate.PomegranatePopupClosedException
import org.nostr.nostrord.auth.pomegranate.PomegranateStatus
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.components.buttons.AppButton
import org.nostr.nostrord.ui.components.buttons.AppButtonSize
import org.nostr.nostrord.ui.screens.login.LoginViewModel
import org.nostr.nostrord.ui.theme.NostrordColors

/**
 * "Login with Google" (pomegranate threshold signer). Runs the whole flow in the shared
 * [LoginViewModel]; this tab only reflects its status and offers the central-server override.
 * A Google identity with no account yet stops at [GoogleAccountSetupSection] to back up its
 * key; the resulting bunker session then logs in like any other.
 */
@Composable
fun GoogleLoginTab(onLoginSuccess: () -> Unit) {
    val vm = viewModel { LoginViewModel(AppModule.nostrRepository) }
    val setup by vm.googleSetup.collectAsState()
    var status by remember { mutableStateOf<PomegranateStatus?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var central by remember { mutableStateOf(PomegranateConfig.CENTRAL_URL) }
    var advancedOpen by remember { mutableStateOf(false) }
    val busy = status != null

    // Shared by both phases: success closes the login, a dismissed popup is a silent cancel.
    val handleResult = { result: Result<Unit> ->
        status = null
        val err = result.exceptionOrNull()
        when {
            err == null -> onLoginSuccess()
            err is PomegranatePopupClosedException -> {}
            else -> errorMessage = err.message ?: "Google login failed"
        }
    }

    setup?.let { current ->
        GoogleAccountSetupSection(
            setup = current,
            busy = busy,
            createLabel = if (busy) statusLabel(status) else "Create account",
            errorMessage = errorMessage,
            onRegenerateKey = vm::regenerateGoogleKey,
            onImportKey = vm::importGoogleKey,
            onAddOperator = vm::addGoogleOperator,
            onRemoveOperator = vm::removeGoogleOperator,
            onThresholdChange = vm::setGoogleThreshold,
            onCheckOperators = vm::checkGoogleOperators,
            onCreate = {
                if (!busy) {
                    errorMessage = null
                    status = PomegranateStatus.Creating
                    vm.createGoogleAccount(onStatus = { status = it }, onResult = handleResult)
                }
            },
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Image(
            imageVector = GoogleLogoIcon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
        )

        Text(
            text = "Login with Google",
            style = MaterialTheme.typography.titleMedium,
            color = NostrordColors.TextPrimary,
            textAlign = TextAlign.Center,
        )

        Text(
            text =
            "Sign in with your Google account. First time here? A Nostr key is created for you " +
                "automatically, nothing to install or back up.",
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
            text = statusLabel(status),
            onClick = {
                if (!busy && central.isNotBlank()) {
                    errorMessage = null
                    status = PomegranateStatus.WaitingForGoogle
                    vm.loginWithGoogle(
                        centralUrl = central,
                        onStatus = { status = it },
                        // New account: the setup step takes over, so drop the busy state.
                        onNewAccount = { status = null },
                        onResult = handleResult,
                    )
                }
            },
            enabled = !busy && central.isNotBlank(),
            size = AppButtonSize.Large,
            fullWidth = true,
            loading = busy,
        )

        BenefitsCard(
            title = "How it works",
            icon = Icons.Default.Lock,
            items =
            listOf(
                "Your key is split into shards held by independent operators",
                "No single server ever holds the whole key",
                "Google only proves who you are, it never touches your key",
                "You can export the full key (nsec) whenever you want",
            ),
        )

        // Advanced: self-hosted central server override.
        AdvancedDisclosure(open = advancedOpen, onToggle = { advancedOpen = !advancedOpen }) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text =
                    "Central server: checks your Google sign-in and forwards each signing request " +
                        "to the key operators. Change it to use a self-hosted one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NostrordColors.TextMuted,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                OutlinedTextField(
                    value = central,
                    onValueChange = { central = it },
                    label = { Text("Central server") },
                    placeholder = { Text(PomegranateConfig.CENTRAL_URL) },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun statusLabel(status: PomegranateStatus?): String = when (status) {
    PomegranateStatus.WaitingForGoogle -> "Waiting for Google sign-in..."
    PomegranateStatus.Checking -> "Checking your account..."
    PomegranateStatus.Creating -> "Setting up your secure account..."
    PomegranateStatus.Connecting -> "Connecting..."
    null -> "Continue with Google"
}
