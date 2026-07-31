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
import coil3.size.Size
import org.nostr.nostrord.ui.components.loading.shimmerEffect
import org.nostr.nostrord.utils.decodeDataImageUri
import org.nostr.nostrord.utils.getImageUrl

/**
 * Android implementation: delegates to Coil's AsyncImage.
 *
 * With coil-gif on the classpath and AnimatedImageDecoder.Factory() (API 28+) or
 * GifDecoder.Factory() (API < 28) registered in NostrordApplication.newImageLoader(),
 * Coil automatically detects the GIF format and plays all frames. No extra code is
 * needed here — the decoder registration is what makes it work.
 *
 * crossfade is explicitly disabled for GIFs: a fade-in on each frame loop would
 * look broken. Coil already ignores crossfade for animated images, but this makes
 * the intent explicit and future-proof.
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
    // Inline base64 animation (gif/webp): decode to bytes so Coil's animated decoder runs on them.
    val dataBytes = remember(url) { decodeDataImageUri(url) }

    if (loadError) {
        return
    }

    AsyncImage(
        model =
        ImageRequest
            .Builder(context)
            .data(dataBytes ?: getImageUrl(url))
            .crossfade(false) // false: crossfade interferes with per-frame rendering
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .size(Size.ORIGINAL)
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
