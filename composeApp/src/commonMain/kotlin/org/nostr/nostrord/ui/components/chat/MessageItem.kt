package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.ui.components.avatars.ProfileAvatar
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.utils.formatTime

@Composable
fun MessageItem(
    message: NostrGroupClient.NostrMessage,
    metadata: UserMetadata? = null
) {
    val displayName = metadata?.displayName ?: metadata?.name ?: message.pubkey.take(8)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        ProfileAvatar(
            imageUrl = metadata?.picture,
            displayName = displayName,
            pubkey = message.pubkey
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = displayName,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatTime(message.createdAt),
                    color = NostrordColors.TextMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            MessageContent(content = message.content)
        }
    }
}
