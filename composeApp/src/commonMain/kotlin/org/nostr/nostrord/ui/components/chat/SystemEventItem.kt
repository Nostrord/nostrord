package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.utils.formatTimestamp

@Composable
fun SystemEventItem(
    pubkey: String,
    action: String,
    createdAt: Long,
    metadata: UserMetadata? = null
) {
    val displayName = metadata?.displayName ?: metadata?.name ?: pubkey.take(8)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = displayName,
            color = NostrordColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = action,
            color = NostrordColors.TextMuted,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = formatTimestamp(createdAt),
            color = NostrordColors.TextMuted,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
