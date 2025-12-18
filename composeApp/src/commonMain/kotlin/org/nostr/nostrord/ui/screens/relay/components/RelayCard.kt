package org.nostr.nostrord.ui.screens.relay.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.screens.relay.model.RelayInfo
import org.nostr.nostrord.ui.screens.relay.model.RelayStatus
import org.nostr.nostrord.ui.theme.NostrordColors

@Composable
fun RelayCard(
    relay: RelayInfo,
    isActive: Boolean,
    isCompact: Boolean,
    onSelectRelay: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isActive) Modifier.border(2.dp, NostrordColors.Primary, RoundedCornerShape(8.dp))
                else Modifier
            ),
        colors = CardDefaults.cardColors(
            containerColor = NostrordColors.Surface
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (isCompact) 12.dp else 16.dp)
        ) {
            // Relay URL
            Text(
                text = relay.url,
                color = Color.White,
                style = if (isCompact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(if (isCompact) 8.dp else 12.dp))

            // Status row
            Row(verticalAlignment = Alignment.CenterVertically) {
                val statusColor = when (relay.status) {
                    RelayStatus.CONNECTED -> Color(0xFF3BA55D)
                    RelayStatus.CONNECTING -> Color(0xFFFAA81A)
                    RelayStatus.ERROR -> NostrordColors.Error
                    RelayStatus.DISCONNECTED -> Color(0xFF747F8D)
                }

                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(statusColor, shape = RoundedCornerShape(4.dp))
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = relay.status.name,
                    color = statusColor,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )

                if (!isCompact) {
                    Spacer(modifier = Modifier.width(16.dp))

                    Text(
                        text = "Groups: ${relay.groupCount?.toString() ?: "N/A"}",
                        color = NostrordColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (!isCompact) {
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = relay.details,
                    color = NostrordColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(if (isCompact) 8.dp else 12.dp))

            // Select Button
            Button(
                onClick = onSelectRelay,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isActive) Color(0xFF3BA55D) else NostrordColors.Primary,
                    disabledContainerColor = Color(0xFF3BA55D)
                ),
                enabled = !isActive
            ) {
                Text(
                    text = if (isActive) "Active" else if (isCompact) "Select" else "Select as Active Relay",
                    color = Color.White
                )
            }
        }
    }
}
