package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.image.ImageBackdrop

/**
 * Platform-specific static image renderer.
 *
 * Web (JS/WasmJS): uses the browser's createImageBitmap() API to decode images off the
 * JS main thread, eliminating the UI freeze that occurs when Skia decodes images inline.
 *
 * Other platforms (Android, JVM, iOS): delegates to Coil AsyncImage with crossfade and
 * disk/memory cache, identical to the previous ChatImage behaviour.
 *
 * [url]          The original (un-proxied) image URL.
 * [modifier]     Compose modifier (size, clip, etc.) applied to the image container.
 * [contentScale] How to scale the image within its bounds.
 * [onClick]      Invoked when the user taps/clicks the image.
 * [onError]      Invoked if the image fails to load (so the caller can show a fallback).
 * [onIntrinsicSize] Reports the decoded pixel size, which stands in for a missing NIP-68 `imeta`
 *                 hint so the slot can take the image's real shape instead of the square floor.
 */
@Composable
expect fun StaticImage(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    onClick: () -> Unit = {},
    onError: () -> Unit = {},
    onIntrinsicSize: (width: Int, height: Int) -> Unit = { _, _ -> },
)

/**
 * Sample the decoded [image] into a small grid of ARGB pixels (0xAARRGGBB) for the
 * transparent-image backdrop heuristic, or null if the bitmap can't be read. The
 * sampling is platform-specific (Android Bitmap vs Skia Bitmap); the decision itself
 * lives in commonMain ([org.nostr.nostrord.ui.image.decideImageBackdrop]).
 */
expect fun sampleImageArgb(image: coil3.Image): IntArray?

/**
 * Apply a contrasting backdrop behind a transparent chat image: a white or dark
 * rounded panel with a small inset so a dark/light logo stays visible on the chat
 * surface. Mirrors the web's `.msg-image.on-light` / `.on-dark`. The caller's outer
 * `.clip(...)` rounds the panel; here we only paint and inset. [padding] is the inset
 * (4dp inline, 12dp in the fullscreen viewer, matching the web).
 */
fun Modifier.chatImageBackdrop(
    backdrop: ImageBackdrop?,
    padding: Dp = 4.dp,
): Modifier = when (backdrop) {
    ImageBackdrop.OnLight -> this.background(Color.White).padding(padding)
    ImageBackdrop.OnDark -> this.background(Color(0xFF15171A)).padding(padding)
    null -> this
}
