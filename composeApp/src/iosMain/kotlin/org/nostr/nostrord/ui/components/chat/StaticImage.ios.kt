package org.nostr.nostrord.ui.components.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.BitmapImage
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import org.nostr.nostrord.ui.components.loading.shimmerEffect
import org.nostr.nostrord.ui.image.ImageBackdrop
import org.nostr.nostrord.ui.image.decideImageBackdrop
import org.nostr.nostrord.utils.decodeDataImageUri
import org.nostr.nostrord.utils.getImageUrl

/**
 * iOS implementation: delegates to Coil AsyncImage.
 */
@Composable
actual fun StaticImage(
    url: String,
    modifier: Modifier,
    contentScale: ContentScale,
    onClick: () -> Unit,
    onError: () -> Unit,
) {
    val context = LocalPlatformContext.current
    // Inline base64 image: decode to bytes for Coil; otherwise use the optimized URL.
    val model: Any = remember(url) { decodeDataImageUri(url) ?: getImageUrl(url) }
    var backdrop by remember(url) { mutableStateOf<ImageBackdrop?>(null) }
    // Shimmers over the reserved slot until Coil resolves, so a pending image reads as
    // loading instead of as blank space that pops in.
    var loading by remember(url) { mutableStateOf(true) }
    AsyncImage(
        model =
        ImageRequest
            .Builder(context)
            .data(model)
            .crossfade(true)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .size(Size(800, 600))
            .build(),
        contentDescription = "Image",
        contentScale = contentScale,
        modifier = modifier
            .then(if (loading) Modifier.shimmerEffect() else Modifier)
            .chatImageBackdrop(backdrop),
        onState = { state ->
            when (state) {
                is AsyncImagePainter.State.Success -> {
                    loading = false
                    backdrop = sampleImageArgb(state.result.image)?.let(::decideImageBackdrop)
                }
                is AsyncImagePainter.State.Error -> {
                    loading = false
                    onError()
                }
                else -> {}
            }
        },
    )
}

/** iOS (Skia): sample the decoded [org.jetbrains.skia.Bitmap] on a 24x24 grid. */
actual fun sampleImageArgb(image: coil3.Image): IntArray? {
    val bitmap = (image as? BitmapImage)?.bitmap ?: return null
    return try {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val n = 24
        val out = IntArray(n * n)
        for (y in 0 until n) {
            val py = y * (h - 1) / (n - 1)
            for (x in 0 until n) {
                val px = x * (w - 1) / (n - 1)
                out[y * n + x] = bitmap.getColor(px, py)
            }
        }
        out
    } catch (_: Throwable) {
        null
    }
}
