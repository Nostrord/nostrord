package org.nostr.nostrord.ui.components.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.SurfaceViewRenderer

/** WebRTC draws straight into its own surface here, so no frame ever crosses into Compose. */
@Composable
actual fun AvVideoSurface(
    identity: String?,
    hasVideo: Boolean,
    attach: (Any) -> Boolean,
    detach: (Any) -> Unit,
    modifier: Modifier,
    fallback: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val renderer = remember(identity) {
        SurfaceViewRenderer(context).apply {
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        }
    }
    var attached by remember(identity) { mutableStateOf(false) }

    DisposableEffect(identity, hasVideo) {
        attached = identity != null && hasVideo && attach(renderer)
        onDispose { detach(renderer) }
    }
    DisposableEffect(renderer) {
        // Frees the EGL surface the engine bound on attach; a no-op if it never got one.
        onDispose { renderer.release() }
    }

    if (attached) {
        AndroidView(factory = { renderer }, modifier = modifier)
    } else {
        fallback()
    }
}
