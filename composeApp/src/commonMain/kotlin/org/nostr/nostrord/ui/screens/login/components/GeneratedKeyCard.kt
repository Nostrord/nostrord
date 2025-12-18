package org.nostr.nostrord.ui.screens.login.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.theme.NostrordColors

@Composable
fun GeneratedKeyCard(privateKey: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "SAVE YOUR PRIVATE KEY",
                color = NostrordColors.WarningOrange,
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
