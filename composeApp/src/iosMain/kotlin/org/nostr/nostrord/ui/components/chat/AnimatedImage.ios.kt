package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.nostr.nostrord.ui.components.loading.shimmerEffect
import org.nostr.nostrord.utils.decodeDataImageUri
import org.nostr.nostrord.utils.getImageUrl

/**
 * iOS implementation: delegates to Coil's AsyncImage.
 *
 * Coil 3 on iOS renders through Skia (via skiko). Skia's Codec supports animated GIFs,
 * but Coil 3 does not yet expose a first-party iOS animated GIF decoder. Depending on
 * the Coil/skiko version, this may or may not animate. The correct long-term fix mirrors
 * the JVM implementation (Skia Codec frame loop), but requires KMP-accessible Skia APIs
 * on the iOS target — not yet straightforward due to iosMain/skia bridging constraints.
 */
@Composable
actual fun AnimatedImage(
    url: String,
    modifier: Modifier,
    contentScale: ContentScale,
    onClick: () -> Unit,
    onError: () -> Unit,
) {
    val context = LocalPlatformContext.current
    var loadError by remember(url) { mutableStateOf(false) }
    // Shimmers over the reserved slot until the first frame decodes, matching StaticImage
    // and the web, where GIFs go through the same skeleton as any other image.
    var loading by remember(url) { mutableStateOf(true) }
    // Inline base64 animation (gif/webp): decode to bytes for Coil.
    val dataBytes = remember(url) { decodeDataImageUri(url) }

    if (loadError) return

    AsyncImage(
        model =
        ImageRequest
            .Builder(context)
            .data(dataBytes ?: getImageUrl(url))
            .crossfade(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build(),
        contentDescription = "Animated GIF",
        contentScale = contentScale,
        modifier = modifier
            .then(if (loading) Modifier.shimmerEffect() else Modifier)
            .clickable(onClick = onClick),
        onState = { state ->
            when (state) {
                is AsyncImagePainter.State.Success -> loading = false
                is AsyncImagePainter.State.Error -> {
                    loading = false
                    loadError = true
                    onError()
                }
                else -> {}
            }
        },
    )
}
