package org.nostr.nostrord.ui.screens.login.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes

/**
 * The "why use this" card under a login method's action (web `.bunker-benefits`): an
 * icon + title header over checked one-liners, on the floating card surface. Shared by
 * the bunker and Google tabs so the two never drift.
 */
@Composable
fun BenefitsCard(
    title: String,
    icon: ImageVector,
    items: List<String>,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = NostrordShapes.shapeMedium,
        color = NostrordColors.SurfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = NostrordColors.Success,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    color = NostrordColors.TextPrimary,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            items.forEach { BenefitItem(it) }
        }
    }
}

/** One checked line. The check tops out with the first text line so wraps stay aligned. */
@Composable
private fun BenefitItem(text: String) {
    Row(
        modifier = Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = NostrordColors.Success.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 2.dp).size(14.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            color = NostrordColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
