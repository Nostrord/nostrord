package org.nostr.nostrord.ui.screens.login.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import org.nostr.nostrord.ui.screens.login.LoginMethod
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes

/** Platform icon for a shared [LoginMethod]. */
internal val LoginMethod.icon: ImageVector
    get() =
        when (this) {
            LoginMethod.PrivateKey -> Icons.Default.Key
            LoginMethod.Bunker -> Icons.Default.Shield
            LoginMethod.Extension -> Icons.Default.Extension
            LoginMethod.Amber -> Icons.Default.PhoneAndroid
            LoginMethod.Google -> GoogleGlyph
        }

/**
 * Row metrics. The web reference row is 36px of icon in ~384px of card, so the same
 * absolute sizes on a phone (~330dp of card) read as a zoomed-in list. [forWidth] keeps
 * the icon-to-row ratio instead of the raw dp.
 */
private data class RowMetrics(
    val icon: Dp,
    val glyph: Dp,
    val pad: Dp,
    val gap: Dp,
    val title: TextUnit,
    val subtitle: TextUnit,
    val chevron: Dp,
) {
    companion object {
        fun forWidth(width: Dp): RowMetrics = if (width < 340.dp) {
            RowMetrics(32.dp, 16.dp, 10.dp, 6.dp, 14.sp, 11.sp, 18.dp)
        } else {
            RowMetrics(36.dp, 18.dp, 12.dp, 8.dp, 15.sp, 12.sp, 20.dp)
        }
    }
}

/**
 * The credential picker as full-width rows (icon, name, one-line explainer, chevron).
 * Rows wrap their label, so the list stays readable for any number of methods, unlike
 * the equal-weight segmented strip it replaces.
 */
@Composable
fun LoginMethodList(
    methods: List<LoginMethod>,
    onSelect: (LoginMethod) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val metrics = RowMetrics.forWidth(maxWidth)
        Column(verticalArrangement = Arrangement.spacedBy(metrics.gap)) {
            methods.forEach { method ->
                LoginMethodRow(method = method, metrics = metrics, onClick = { onSelect(method) })
            }
        }
    }
}

@Composable
private fun LoginMethodRow(
    method: LoginMethod,
    metrics: RowMetrics,
    onClick: () -> Unit,
) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(NostrordShapes.shapeMedium)
            .background(NostrordColors.BackgroundFloating)
            .border(1.dp, NostrordColors.Divider, NostrordShapes.shapeMedium)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(metrics.pad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
            Modifier
                .size(metrics.icon)
                .clip(NostrordShapes.shapeSmall)
                .background(NostrordColors.PrimarySubtle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = method.icon,
                contentDescription = null,
                tint = NostrordColors.Primary,
                modifier = Modifier.size(metrics.glyph),
            )
        }
        Spacer(modifier = Modifier.width(metrics.pad))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = method.title,
                color = NostrordColors.TextPrimary,
                fontSize = metrics.title,
                lineHeight = metrics.title * 1.3f,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = method.subtitle,
                color = NostrordColors.TextMuted,
                fontSize = metrics.subtitle,
                lineHeight = metrics.subtitle * 1.3f,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = NostrordColors.TextMuted,
            modifier = Modifier.size(metrics.chevron),
        )
    }
}
