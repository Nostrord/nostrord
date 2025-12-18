package org.nostr.nostrord.ui.components.cards

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.theme.NostrordColors

@Composable
fun WarningCard(
    isCompact: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NostrordColors.Error),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(if (isCompact) 12.dp else 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = "Warning",
                    tint = Color.White,
                    modifier = Modifier.size(if (isCompact) 18.dp else 20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "CRITICAL SECURITY WARNING",
                    color = Color.White,
                    style = if (isCompact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                if (isCompact) {
                    "• Never share your private key\n" +
                    "• Store it in a secure password manager\n" +
                    "• If lost, you cannot recover your account"
                } else {
                    "• Never share your private key with anyone\n" +
                    "• Anyone with this key has full access to your account\n" +
                    "• Store it in a secure password manager\n" +
                    "• If lost, you cannot recover your account\n" +
                    "• Make multiple secure backups"
                },
                color = Color.White,
                style = if (isCompact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
            )
        }
    }
}
