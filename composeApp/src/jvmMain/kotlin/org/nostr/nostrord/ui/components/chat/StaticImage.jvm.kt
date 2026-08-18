package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.clickable
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
 * JVM Desktop implementation: delegates to Coil AsyncImage.
 * Tries the optimized (proxy) URL first; if that fails, retries with the original URL.
 */
@Composable
actual fun StaticImage(
    url: String,
    modifier: Modifier,
    contentScale: ContentScale,
    onClick: () -> Unit,
    onError: () -> Unit,
    onIntrinsicSize: (width: Int, height: Int) -> Unit,
) {
    val context = LocalPlatformContext.current
    // Inline base64 image: decode to bytes and hand them straight to Coil (no proxy/retry).
    val dataBytes = remember(url) { decodeDataImageUri(url) }
    val optimizedUrl = remember(url) { getImageUrl(url) }
    var useOriginal by remember(url) { mutableStateOf(false) }

    val model: Any = dataBytes ?: if (useOriginal) url else optimizedUrl

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
            .size(Size.ORIGINAL)
            .build(),
        contentDescription = "Image",
        contentScale = contentScale,
        modifier = modifier
            .then(if (loading) Modifier.shimmerEffect() else Modifier)
            .chatImageBackdrop(backdrop)
            .clickable(onClick = onClick),
        onState = { state ->
            when (state) {
                is AsyncImagePainter.State.Success -> {
                    loading = false
                    onIntrinsicSize(state.result.image.width, state.result.image.height)
                    backdrop = sampleImageArgb(state.result.image)?.let(::decideImageBackdrop)
                }
                is AsyncImagePainter.State.Error -> {
                    if (!useOriginal && optimizedUrl != url) {
                        useOriginal = true
                    } else {
                        loading = false
                        onError()
                    }
                }
                else -> {}
            }
        },
    )
}

/** JVM (Skia): sample the decoded [org.jetbrains.skia.Bitmap] on a 24x24 grid. */
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
