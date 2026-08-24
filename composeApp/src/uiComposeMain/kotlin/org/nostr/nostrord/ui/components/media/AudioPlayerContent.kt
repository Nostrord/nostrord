package org.nostr.nostrord.ui.components.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.media.INLINE_MEDIA_MAX_WIDTH
import org.nostr.nostrord.ui.media.mediaDisplayName
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography

/**
 * Inline audio player with play/pause, progress bar, and duration.
 *
 * The playback engine differs per platform (Android: MediaPlayer; Desktop: the same
 * kdroidfilter player the video uses, so it shares the system codec set; iOS: stub), so
 * this is the expect/actual boundary. All actuals render the shared [AudioPlayerChrome].
 */
@Composable
expect fun AudioPlayerContent(
    url: String,
    modifier: Modifier = Modifier,
)

/**
 * Shared visual: play/pause button, filename, progress bar, the position/duration row and the
 * save-to-device button. Takes a normalized [progress] (0..1) and pre-formatted
 * [positionText]/[durationText] so each platform can source them from its own engine (the desktop
 * player's own text avoids the raw, sometimes-bogus, duration a streamed file reports).
 * [durationText] null hides the duration.
 */
@Composable
internal fun AudioPlayerChrome(
    isPlaying: Boolean,
    progress: Float,
    positionText: String,
    durationText: String?,
    url: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clampedProgress = progress.coerceIn(0f, 1f)

    Row(
        modifier =
        modifier
            // Cap before filling: fillMaxWidth pins min = max = the incoming width, and a later
            // widthIn can no longer shrink below that pinned minimum. A chat clip is a widget,
            // not a banner, so it gets the same 360dp box as the web player and inline images.
            .widthIn(max = INLINE_MEDIA_MAX_WIDTH.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(NostrordColors.SurfaceVariant)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(NostrordColors.Primary.copy(alpha = 0.2f))
                .clickable { onToggle() }
                .pointerHoverIcon(PointerIcon.Hand),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = NostrordColors.Primary,
                modifier = Modifier.size(18.dp),
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = mediaDisplayName(url),
                style = NostrordTypography.MessageBody,
                color = NostrordColors.TextContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(4.dp))

            LinearProgressIndicator(
                progress = { clampedProgress },
                modifier =
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = NostrordColors.Primary,
                trackColor = NostrordColors.Primary.copy(alpha = 0.15f),
            )

            Spacer(Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = positionText,
                    style = NostrordTypography.Caption,
                    color = NostrordColors.TextMuted,
                )
                if (durationText != null) {
                    Text(
                        text = durationText,
                        style = NostrordTypography.Caption,
                        color = NostrordColors.TextMuted,
                    )
                }
            }
        }

        Spacer(Modifier.width(8.dp))
        MediaSaveButton(url = url, fallbackBase = "audio", contentDescription = "Save audio")
    }
}
