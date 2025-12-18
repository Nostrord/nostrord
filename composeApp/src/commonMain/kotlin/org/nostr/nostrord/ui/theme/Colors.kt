package org.nostr.nostrord.ui.theme

import androidx.compose.ui.graphics.Color

object NostrordColors {
    // Background colors
    val Background = Color(0xFF36393F)
    val BackgroundDark = Color(0xFF202225)
    val Surface = Color(0xFF2F3136)
    val SurfaceVariant = Color(0xFF40444B)
    val InputBackground = Color(0xFF383A40)

    // Primary colors
    val Primary = Color(0xFF5865F2)
    val PrimaryVariant = Color(0xFF4752C4)

    // Accent colors
    val Success = Color(0xFF57F287)
    val Error = Color(0xFFED4245)
    val Warning = Color(0xFFFEE75C)
    val WarningOrange = Color(0xFFFFA500)
    val Pink = Color(0xFFEB459E)
    val LightRed = Color(0xFFFF6B6B)
    val Teal = Color(0xFF4ECDC4)
    val Mint = Color(0xFF95E1D3)

    // Text colors
    val TextPrimary = Color.White
    val TextSecondary = Color(0xFF99AAB5)
    val TextMuted = Color(0xFF72767D)
    val TextContent = Color(0xFFDCDDDE)

    // Divider
    val Divider = Color(0xFF40444B)

    // Avatar colors palette (for generateColorFromString)
    val AvatarColors = listOf(
        Color(0xFF5865F2),
        Color(0xFF57F287),
        Color(0xFFFEE75C),
        Color(0xFFEB459E),
        Color(0xFFED4245),
        Color(0xFFFF6B6B),
        Color(0xFF4ECDC4),
        Color(0xFF95E1D3)
    )
}
