package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import org.nostr.nostrord.network.livekit.VideoFrameSink
import org.nostr.nostrord.ui.util.rgbaToImageBitmap

/**
 * Render [identity]'s AV space video, falling back to [fallback] while there is no track.
 *
 * The surface type is the media engine's business: Android hands WebRTC a real
 * `SurfaceViewRenderer` and lets it draw, while the Skiko targets pull RGBA frames out of a
 * [VideoFrameSink]. [attach] and [detach] carry that opaque surface back to the engine.
 */
@Composable
expect fun AvVideoSurface(
    identity: String?,
    hasVideo: Boolean,
    attach: (Any) -> Boolean,
    detach: (Any) -> Unit,
    modifier: Modifier,
    fallback: @Composable () -> Unit,
)

/**
 * [AvVideoSurface] for targets that draw video themselves: the engine pushes RGBA frames into
 * a sink and Compose paints the latest one.
 */
@Composable
internal fun VideoFrameSurface(
    identity: String?,
    hasVideo: Boolean,
    attach: (Any) -> Boolean,
    detach: (Any) -> Unit,
    modifier: Modifier,
    fallback: @Composable () -> Unit,
) {
    val sink = remember(identity) { VideoFrameSink() }
    var attached by remember(identity) { mutableStateOf(false) }

    DisposableEffect(identity, hasVideo) {
        attached = identity != null && hasVideo && attach(sink)
        onDispose { detach(sink) }
    }

    val frame by sink.frame.collectAsState()
    val current = frame
    if (attached && current != null) {
        // A new ImageBitmap per frame: correctness first. Tile-sized frames keep this cheap,
        // and a reused bitmap would need pixel-write APIs Compose does not share.
        Image(
            bitmap = rgbaToImageBitmap(current.width, current.height, current.rgba),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        fallback()
    }
}
