package org.nostr.nostrord.ui.components.cards

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.theme.NostrordColors

@Composable
fun InfoCard(
    title: String,
    titleColor: Color,
    content: String,
    icon: ImageVector = Icons.Default.Lightbulb,
    isCompact: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(if (isCompact) 12.dp else 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = title,
                    tint = titleColor,
                    modifier = Modifier.size(if (isCompact) 18.dp else 20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    title,
                    color = titleColor,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                content,
                color = Color.White,
                style = if (isCompact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodySmall
            )
        }
    }
}
