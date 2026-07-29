package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Replaces the composer in an AV-only group (`supported_kinds` present and empty): the relay
 * accepts no text events at all, so offering an input would only produce rejected sends.
 * Mirrors the web's `.chat-av-only-note`.
 */
@Composable
fun AvOnlyComposerNote(modifier: Modifier = Modifier) {
    HorizontalDivider(color = NostrordColors.Divider)
    Text(
        text = "This group is audio and video only.",
        color = NostrordColors.TextMuted,
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
    )
}
