package org.nostr.nostrord.ui.screens.backup

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
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

@Composable
fun BackupScreenDesktop(
    privateKey: String?,
    publicKey: String?,
    showCopiedMessage: Boolean,
    onCopyPublicKey: () -> Unit,
    onCopyPrivateKey: () -> Unit,
    onNavigateBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NostrordColors.Background)
    ) {
        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        // Back button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            TextButton(onClick = onNavigateBack) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = NostrordColors.Primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Back", color = NostrordColors.Primary)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Centered content with max width
        Column(
            modifier = Modifier.widthIn(max = 600.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Warning icon
            Icon(
                Icons.Default.Warning,
                contentDescription = "Warning",
                tint = NostrordColors.WarningOrange,
                modifier = Modifier
                    .size(64.dp)
                    .padding(16.dp)
            )

            // Title
            Text(
                "Backup Your Private Key",
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Warning card
            WarningCard(isCompact = false)

            Spacer(modifier = Modifier.height(24.dp))

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
                    isCompact = false
                )
                Spacer(modifier = Modifier.height(16.dp))
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
                    isCompact = false,
                    showSecretBadge = true
                )
            } else {
                NoKeyCard()
            }

            // Copied message
            if (showCopiedMessage) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = NostrordColors.Success),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Success",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Copied to clipboard",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Security tips
            InfoCard(
                title = "Security Tips",
                titleColor = NostrordColors.Warning,
                icon = Icons.Default.Lightbulb,
                content = "1. Write it down on paper and store in a safe place\n" +
                        "2. Use a password manager like 1Password or Bitwarden\n" +
                        "3. Never store it in plain text files or screenshots\n" +
                        "4. Never send it via email or messaging apps\n" +
                        "5. Consider using a hardware wallet for long-term storage",
                isCompact = false
            )
        }
        }

        VerticalScrollbar(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            adapter = rememberScrollbarAdapter(scrollState)
        )
    }
}
