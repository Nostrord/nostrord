package org.nostr.nostrord.ui.screens.backup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.components.cards.InfoCard
import org.nostr.nostrord.ui.components.cards.KeyCard
import org.nostr.nostrord.ui.components.cards.WarningCard
import org.nostr.nostrord.ui.screens.backup.components.NoKeyCard
import org.nostr.nostrord.ui.theme.NostrordColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreenMobile(
    privateKey: String?,
    publicKey: String?,
    showCopiedMessage: Boolean,
    onCopyPublicKey: () -> Unit,
    onCopyPrivateKey: () -> Unit,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup Keys", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NostrordColors.BackgroundDark
                )
            )
        },
        containerColor = NostrordColors.Background,
        snackbarHost = {
            if (showCopiedMessage) {
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    containerColor = NostrordColors.Success
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Success",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Copied to clipboard", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Warning icon
            Icon(
                Icons.Default.Warning,
                contentDescription = "Warning",
                tint = NostrordColors.WarningOrange,
                modifier = Modifier
                    .size(48.dp)
                    .padding(8.dp)
            )

            Text(
                "Backup Your Keys",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Warning card - compact version
            WarningCard(isCompact = true)

            Spacer(modifier = Modifier.height(16.dp))

            // Public Key Section
            if (publicKey != null) {
                KeyCard(
                    title = "Public Key (npub)",
                    titleColor = NostrordColors.TextSecondary,
                    keyValue = publicKey,
                    keyColor = Color.White,
                    buttonText = "Copy Public Key",
                    buttonColor = NostrordColors.Primary,
                    onCopy = onCopyPublicKey,
                    isCompact = true
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Private Key Section
            if (privateKey != null) {
                KeyCard(
                    title = "Private Key (nsec)",
                    titleColor = NostrordColors.Error,
                    keyValue = privateKey,
                    keyColor = NostrordColors.LightRed,
                    buttonText = "Copy Private Key",
                    buttonColor = NostrordColors.Error,
                    onCopy = onCopyPrivateKey,
                    isCompact = true,
                    showSecretBadge = true
                )
            } else {
                NoKeyCard()
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Security tips - compact
            InfoCard(
                title = "Security Tips",
                titleColor = NostrordColors.Warning,
                icon = Icons.Default.Lightbulb,
                content = "1. Write it down and store safely\n" +
                        "2. Use a password manager\n" +
                        "3. Never store in plain text\n" +
                        "4. Never send via messages",
                isCompact = true
            )
        }
    }
}
