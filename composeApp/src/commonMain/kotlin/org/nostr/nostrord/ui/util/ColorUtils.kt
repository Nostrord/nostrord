package org.nostr.nostrord.ui.util

import androidx.compose.ui.graphics.Color
import org.nostr.nostrord.ui.theme.NostrordColors
import kotlin.math.abs

fun generateColorFromString(str: String): Color {
    val hash = str.hashCode()
    return NostrordColors.AvatarColors[abs(hash) % NostrordColors.AvatarColors.size]
}
