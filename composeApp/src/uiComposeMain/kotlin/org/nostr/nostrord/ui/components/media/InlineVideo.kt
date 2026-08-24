package org.nostr.nostrord.ui.components.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A chat video: the platform player plus the save button, overlaid in the corner so the file can
 * be kept with the name and extension it really has. The player itself is per-platform (see
 * [PlatformVideoPlayer]); everything around it is shared with the web's `.msg-video-wrap`.
 */
@Composable
fun InlineVideo(
    url: String,
    thumbnailUrl: String?,
    aspectRatio: Float,
    onFallbackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        PlatformVideoPlayer(
            url = url,
            thumbnailUrl = thumbnailUrl,
            aspectRatio = aspectRatio,
            onFallbackClick = onFallbackClick,
            modifier = Modifier,
        )
        MediaSaveButton(
            url = url,
            fallbackBase = "video",
            contentDescription = "Save video",
            // White over the frame: the video is the backdrop, not the surface color.
            tint = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
        )
    }
}
