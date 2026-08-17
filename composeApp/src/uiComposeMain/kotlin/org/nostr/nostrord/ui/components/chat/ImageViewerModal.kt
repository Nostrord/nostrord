package org.nostr.nostrord.ui.components.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nostr.nostrord.network.createHttpClient
import org.nostr.nostrord.ui.image.ImageBackdrop
import org.nostr.nostrord.ui.image.decideImageBackdrop
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.utils.decodeDataImageUri
import org.nostr.nostrord.utils.getImageUrl
import org.nostr.nostrord.utils.isAnimatedImageUrl
import org.nostr.nostrord.utils.isDataImageUri
import org.nostr.nostrord.utils.rememberImageDownloader
import org.nostr.nostrord.utils.supportsImageDownload

@Composable
fun ImageViewerModal(
    imageUrl: String,
    onDismiss: () -> Unit,
    /**
     * Plaintext of an image with no fetchable url: a NIP-17 attachment is ciphertext on the media
     * server, so the viewer is handed the decrypted bytes and [imageUrl] serves only as identity.
     * [fileName] and [mimeType] then describe what a download would save.
     */
    imageBytes: ByteArray? = null,
    fileName: String? = null,
    mimeType: String? = null,
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalPlatformContext.current
    // Bytes go through Coil, which animates a GIF on Android but not on desktop/iOS; the
    // frame-by-frame AnimatedImage path fetches by url and has nothing to fetch here.
    val isAnimated = imageBytes == null && isAnimatedImageUrl(imageUrl)
    // Decrypted bytes win; then an inline base64 image decoded for Coil; else the optimized URL.
    val imageModel: Any =
        remember(imageUrl, imageBytes) { imageBytes ?: decodeDataImageUri(imageUrl) ?: getImageUrl(imageUrl) }

    val scope = rememberCoroutineScope()
    val downloadImage = rememberImageDownloader()
    var isDownloading by remember { mutableStateOf(false) }

    var imageState by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    var backdrop by remember(imageUrl) { mutableStateOf<ImageBackdrop?>(null) }

    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    val animatedScale by animateFloatAsState(
        targetValue = scale,
        animationSpec = tween(150),
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties =
        DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.9f))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onDismiss() },
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                scale = 2.5f
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.5f, 5f)
                            if (scale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    }.graphicsLayer(
                        scaleX = animatedScale,
                        scaleY = animatedScale,
                        translationX = offsetX,
                        translationY = offsetY,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isAnimated) {
                    AnimatedImage(
                        url = imageUrl,
                        modifier =
                        Modifier
                            .widthIn(max = 1200.dp)
                            .fillMaxWidth(0.95f)
                            .fillMaxHeight(0.85f)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit,
                        onClick = { },
                    )
                } else {
                    AsyncImage(
                        model =
                        ImageRequest
                            .Builder(context)
                            .data(imageModel)
                            .crossfade(true)
                            .size(Size(1920, 1920)) // Cap decode size — display max is 1200dp
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .build(),
                        contentDescription = "Full size image",
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.High,
                        modifier =
                        Modifier
                            .widthIn(max = 1200.dp)
                            .fillMaxWidth(0.95f)
                            .fillMaxHeight(0.85f)
                            .clip(RoundedCornerShape(12.dp))
                            .chatImageBackdrop(backdrop, padding = 12.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { },
                        onState = { state ->
                            imageState = state
                            if (state is AsyncImagePainter.State.Success) {
                                backdrop = sampleImageArgb(state.result.image)?.let(::decideImageBackdrop)
                            }
                        },
                    )
                }
            }

            if (!isAnimated && imageState is AsyncImagePainter.State.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = NostrordColors.Primary,
                    strokeWidth = 4.dp,
                )
            }

            Row(
                modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (supportsImageDownload) {
                    IconButton(
                        onClick = {
                            if (isDownloading) return@IconButton
                            isDownloading = true
                            scope.launch {
                                try {
                                    if (imageBytes != null) {
                                        downloadImage(
                                            imageBytes,
                                            fileName ?: "attachment",
                                            mimeType ?: "application/octet-stream",
                                        )
                                    } else {
                                        resolveDownloadableImage(imageUrl)?.let { (bytes, name, mime) ->
                                            downloadImage(bytes, name, mime)
                                        }
                                    }
                                } finally {
                                    isDownloading = false
                                }
                            }
                        },
                        modifier =
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f))
                            .pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Download",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }

                // Inline base64 images have no external URL to open, and an encrypted attachment's
                // url serves ciphertext a browser cannot render.
                if (!isDataImageUri(imageUrl) && imageBytes == null) {
                    IconButton(
                        onClick = {
                            try {
                                uriHandler.openUri(imageUrl)
                            } catch (_: Exception) {
                            }
                        },
                        modifier =
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f))
                            .pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Open in browser",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier =
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

/**
 * Resolves the raw bytes to save for [url], plus a file name and MIME type. Inline base64
 * images decode locally; remote images are fetched at their original (un-proxied) URL for
 * full resolution. Returns null on failure.
 */
private suspend fun resolveDownloadableImage(url: String): Triple<ByteArray, String, String>? {
    decodeDataImageUri(url)?.let { bytes ->
        val mime = url.substringAfter("data:", "").substringBefore(";").ifBlank { "image/png" }
        val ext = mime.substringAfter('/', "png").substringBefore('+').ifBlank { "png" }
        return Triple(bytes, "nostrord_${url.hashCode().toUInt()}.$ext", mime)
    }

    val name = url.substringBefore('?').substringAfterLast('/').ifBlank { "image" }
    val ext = name.substringAfterLast('.', "").lowercase()
    val mime =
        when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "png" -> "image/png"
            else -> "image/*"
        }
    val fileName = if ('.' in name) name else "$name.png"

    val client = createHttpClient()
    return try {
        val bytes: ByteArray = withContext(Dispatchers.Default) { client.get(url).body() }
        Triple(bytes, fileName, mime)
    } catch (_: Exception) {
        null
    } finally {
        client.close()
    }
}
