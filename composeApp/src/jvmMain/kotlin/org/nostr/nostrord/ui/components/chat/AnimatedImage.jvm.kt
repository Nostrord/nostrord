package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.nostr.nostrord.ui.components.loading.shimmerEffect
import org.nostr.nostrord.utils.decodeDataImageUri
import org.nostr.nostrord.utils.getImageUrl
import org.nostr.nostrord.utils.normalizeAnimatedUrl
import org.nostr.nostrord.utils.proxyViaWeserv
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.MemoryCacheImageInputStream
import org.jetbrains.skia.Bitmap as SkiaBitmap
import org.jetbrains.skia.Codec as SkiaCodec
import org.jetbrains.skia.Data as SkiaData
import org.jetbrains.skia.Image as SkiaImage

/** Max bytes allowed for a single downloaded image before we refuse to decode. */
private const val MAX_IMAGE_DOWNLOAD_BYTES = 15L * 1024 * 1024 // 15 MB

/** HTTP timeouts for animated image fetching. */
private const val CONNECT_TIMEOUT_MS = 5_000
private const val READ_TIMEOUT_MS = 10_000

/**
 * Process-level LRU cache for decoded animation frames, bounded by total byte usage.
 *
 * WHY this is needed:
 * Compose's `remember` state is scoped to a composition instance. When a LazyColumn item
 * scrolls off screen, Compose destroys that item's composition — including the decoded
 * frames stored in `remember`. Storing frames here survives scrolling.
 *
 * Unlike the old count-based cache, this tracks estimated ARGB byte usage per entry
 * (width × height × 4 × frameCount) and evicts LRU entries when total exceeds the cap.
 * This prevents OOM from a few large GIFs blowing past a fixed entry count.
 */
private object FrameCache {
    private const val MAX_BYTES = 150L * 1024 * 1024 // 150 MB cap

    private data class Entry(
        val frames: List<Pair<ImageBitmap, Int>>,
        val estimatedBytes: Long,
    )

    private val cache = LinkedHashMap<String, Entry>(16, 0.75f, /* accessOrder = */ true)
    private var currentBytes = 0L

    @Synchronized
    fun get(url: String): List<Pair<ImageBitmap, Int>>? = cache[url]?.frames

    @Synchronized
    fun put(
        url: String,
        frames: List<Pair<ImageBitmap, Int>>,
    ) {
        if (frames.isEmpty()) return
        val first = frames[0].first
        val estimatedBytes = first.width.toLong() * first.height * 4 * frames.size
        // Don't cache entries larger than half the budget
        if (estimatedBytes > MAX_BYTES / 2) return

        // Remove existing entry if replacing
        cache.remove(url)?.let { currentBytes -= it.estimatedBytes }

        // Evict LRU until we fit
        val iter = cache.entries.iterator()
        while (currentBytes + estimatedBytes > MAX_BYTES && iter.hasNext()) {
            val eldest = iter.next()
            currentBytes -= eldest.value.estimatedBytes
            iter.remove()
        }

        cache[url] = Entry(frames, estimatedBytes)
        currentBytes += estimatedBytes
    }
}

/**
 * JVM Desktop implementation: frame-by-frame animation using Java's built-in GIF decoder.
 *
 * WHY Coil cannot be used here:
 * Coil 3's `coil-gif` module only ships Android decoders (AnimatedImageDecoder / GifDecoder).
 * On JVM, Coil uses Java's ImageIO for decoding but only reads the first frame, producing
 * a static image. There is no first-party Coil animated GIF decoder for JVM Desktop.
 *
 * HOW this works:
 * 1. Decoded frames are looked up in FrameCache (process-level LRU) first. Cache hits
 *    restore frames instantly, with no loading state and no network request.
 * 2. On cache miss: GIF bytes are fetched on Dispatchers.IO and decoded per format:
 *    GIF → ImageIO (preserves disposal methods natively)
 *    WebP → Skia Codec (ImageIO has no WebP support in the standard JDK)
 * 3. Frame delay from GIF/WebP metadata is applied (floored at 50 ms per browser spec).
 * 4. A LaunchedEffect cycles currentFrame at each declared delay, driving recomposition.
 */
@Composable
actual fun AnimatedImage(
    url: String,
    modifier: Modifier,
    contentScale: ContentScale,
    onClick: () -> Unit,
    onError: () -> Unit,
) {
    // Initialise directly from cache — composables that re-enter after scrolling away
    // will already have frames and skip the loading state entirely.
    var frames by remember(url) { mutableStateOf(FrameCache.get(url)) }
    var currentFrame by remember { mutableIntStateOf(0) }

    LaunchedEffect(url) {
        // Cache hit: frames were set in the remember initialiser — nothing to do.
        if (frames != null) return@LaunchedEffect

        val decoded: List<Pair<ImageBitmap, Int>> =
            withContext(Dispatchers.IO) {
                try {
                    // Inline base64 animation: decode the payload directly, no HTTP fetch.
                    val inlineBytes = decodeDataImageUri(url)
                    if (inlineBytes != null) return@withContext decodeBytes(inlineBytes)

                    // Try optimized URL first (proxy + Giphy page→media conversion)
                    val optimizedUrl = getImageUrl(normalizeAnimatedUrl(url))
                    val bytes = fetchWithTimeout(optimizedUrl)
                    val result = bytes?.let { decodeBytes(it) } ?: emptyList()

                    if (result.isNotEmpty()) return@withContext result

                    // Fallback 1: try original URL directly (if different)
                    if (optimizedUrl != url) {
                        val rawBytes = fetchWithTimeout(url)
                        val rawResult = rawBytes?.let { decodeBytes(it) } ?: emptyList()
                        if (rawResult.isNotEmpty()) return@withContext rawResult
                    }

                    // Fallback 2: force proxy via wsrv.nl (normalizes format to GIF)
                    val proxyUrl = proxyViaWeserv(normalizeAnimatedUrl(url))
                    if (proxyUrl != optimizedUrl) {
                        val proxyBytes = fetchWithTimeout(proxyUrl)
                        val proxyResult = proxyBytes?.let { decodeBytes(it) } ?: emptyList()
                        if (proxyResult.isNotEmpty()) return@withContext proxyResult
                    }

                    emptyList()
                } catch (e: Exception) {
                    println("[AnimatedImage/JVM] Failed to load $url: ${e.message}")
                    emptyList()
                }
            }
        frames = decoded
        if (decoded.isNotEmpty()) FrameCache.put(url, decoded)
    }

    val baseModifier = modifier.clickable(onClick = onClick)

    when {
        frames == null -> {
            // Decoding: the same shimmer skeleton the other image paths use, so a GIF and a
            // still in the same conversation don't load with two different affordances.
            Box(modifier = baseModifier.shimmerEffect())
        }
        frames!!.isEmpty() -> {
            // Error — notify parent so it can show a text link fallback
            LaunchedEffect(Unit) { onError() }
        }
        frames!!.size == 1 -> {
            // Single-frame GIF — render as a static image, no animation loop needed
            Image(
                bitmap = frames!![0].first,
                contentDescription = "Image",
                contentScale = contentScale,
                modifier = baseModifier,
            )
        }
        else -> {
            // Multi-frame animated GIF
            val frameList = frames!!
            LaunchedEffect(frameList) {
                while (isActive) {
                    val delayMs = frameList[currentFrame].second.toLong()
                    delay(delayMs)
                    currentFrame = (currentFrame + 1) % frameList.size
                }
            }
            Image(
                bitmap = frameList[currentFrame].first,
                contentDescription = "Animated GIF",
                contentScale = contentScale,
                modifier = baseModifier,
            )
        }
    }
}

/**
 * Decodes raw image bytes into animation frames based on detected format.
 */
private fun decodeBytes(bytes: ByteArray): List<Pair<ImageBitmap, Int>> = when (detectImageFormat(bytes)) {
    ImageFormat.GIF -> decodeGifFrames(bytes)
    ImageFormat.WEBP -> decodeWebpFrames(bytes)
    ImageFormat.OTHER -> decodeStaticViaSkia(bytes)
}

/**
 * Fetches image bytes from a URL with connect/read timeouts and a size cap.
 * Returns null if the fetch fails or the response is too large.
 */
private fun fetchWithTimeout(url: String): ByteArray? {
    val conn = URI(url).toURL().openConnection() as HttpURLConnection
    return try {
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Nostrord/1.0")
        conn.setRequestProperty("Accept", "image/gif, image/webp, image/png, image/jpeg, image/*")

        if (conn.responseCode !in 200..299) return null

        // Reject responses larger than our budget before reading them fully
        val contentLength = conn.contentLengthLong
        if (contentLength > MAX_IMAGE_DOWNLOAD_BYTES) return null

        conn.inputStream.use { stream ->
            val buffer =
                java.io.ByteArrayOutputStream(
                    if (contentLength > 0) contentLength.toInt() else 65536,
                )
            val chunk = ByteArray(8192)
            var totalRead = 0L
            while (true) {
                val n = stream.read(chunk)
                if (n == -1) break
                totalRead += n
                if (totalRead > MAX_IMAGE_DOWNLOAD_BYTES) return null
                buffer.write(chunk, 0, n)
            }
            buffer.toByteArray()
        }
    } catch (_: Exception) {
        null
    } finally {
        conn.disconnect()
    }
}

/** Image format detected from file magic bytes. */
private enum class ImageFormat { GIF, WEBP, OTHER }

/**
 * Detects image format from the first bytes of the file (magic bytes).
 * - GIF: starts with "GIF87a" or "GIF89a"
 * - WebP: starts with "RIFF" + 4 bytes + "WEBP"
 * - OTHER: PNG, JPEG, or anything else
 */
private fun detectImageFormat(bytes: ByteArray): ImageFormat {
    if (bytes.size < 12) return ImageFormat.OTHER
    // GIF: 47 49 46 38 (GIF8)
    if (bytes[0] == 0x47.toByte() &&
        bytes[1] == 0x49.toByte() &&
        bytes[2] == 0x46.toByte() &&
        bytes[3] == 0x38.toByte()
    ) {
        return ImageFormat.GIF
    }
    // WebP: RIFF....WEBP
    if (bytes[0] == 0x52.toByte() &&
        bytes[1] == 0x49.toByte() &&
        bytes[2] == 0x46.toByte() &&
        bytes[3] == 0x46.toByte() &&
        bytes[8] == 0x57.toByte() &&
        bytes[9] == 0x45.toByte() &&
        bytes[10] == 0x42.toByte() &&
        bytes[11] == 0x50.toByte()
    ) {
        return ImageFormat.WEBP
    }
    return ImageFormat.OTHER
}

/**
 * Decodes a static image (PNG, JPEG, etc.) via Skia into a single-frame list.
 * This allows AnimatedImage to handle any image format, not just GIF/WebP.
 */
private fun decodeStaticViaSkia(bytes: ByteArray): List<Pair<ImageBitmap, Int>> = try {
    val skiaImage = SkiaImage.makeFromEncoded(bytes)
    val composeBitmap = skiaImage.toComposeImageBitmap()
    skiaImage.close()
    listOf(composeBitmap to 0)
} catch (e: Exception) {
    println("[Static/JVM] Decode error: ${e.message}")
    emptyList()
}

/**
 * Decodes animated WebP frames using org.jetbrains.skia.Codec.
 *
 * WHY Skia (not ImageIO): Java's standard ImageIO has no WebP reader. Skia (bundled with
 * Compose Desktop via skiko) supports both static and animated WebP natively via Codec.
 *
 * Uses Skia Bitmap → toComposeImageBitmap() directly, avoiding the previous PNG
 * encode/decode round-trip which wasted ~8x memory per frame.
 */
private fun decodeWebpFrames(bytes: ByteArray): List<Pair<ImageBitmap, Int>> {
    return try {
        val data = SkiaData.makeFromBytes(bytes)
        val codec = SkiaCodec.makeFromData(data)
        val info = codec.imageInfo

        // Reject absurdly large images (e.g. 16000×16000) before allocating frames
        if (info.width.toLong() * info.height > 8_000_000) { // ~8 megapixels max
            codec.close()
            data.close()
            return emptyList()
        }

        // Static WebP (VP8/VP8L): frameCount is 0 or 1 — fall back to SkiaImage
        // which handles all WebP variants reliably for single-frame decode.
        if (codec.frameCount <= 1) {
            codec.close()
            data.close()
            return decodeStaticViaSkia(bytes)
        }

        // Reject video-length WebPs to prevent hundreds of MB of frame allocations
        if (codec.frameCount > 300) {
            codec.close()
            data.close()
            return emptyList()
        }

        val result =
            (0 until codec.frameCount).map { i ->
                val bitmap = SkiaBitmap()
                bitmap.allocPixels(info)
                codec.readPixels(bitmap, i)

                val delayMs = codec.getFrameInfo(i).duration.coerceAtLeast(50)
                // SkiaBitmap → SkiaImage → Compose ImageBitmap (no PNG round-trip)
                val skiaImage = SkiaImage.makeFromBitmap(bitmap)
                val composeBitmap = skiaImage.toComposeImageBitmap()
                skiaImage.close()
                bitmap.close()

                composeBitmap to delayMs
            }

        codec.close()
        data.close()
        result
    } catch (e: Exception) {
        println("[WebP/JVM] Decode error: ${e.message}")
        emptyList()
    }
}

/**
 * Decodes all frames of a GIF from its raw bytes using javax.imageio.
 *
 * Returns a list of (ImageBitmap, delayMs) pairs.
 * Frame delays are read from the GIF GraphicControlExtension metadata node.
 * An empty list signals decode failure.
 *
 * Handles GIF disposal methods correctly:
 * - "none" / "doNotDispose": keep canvas as-is for next frame
 * - "restoreToBackgroundColor": clear the frame region before next frame
 * - "restoreToPrevious": restore canvas to state before this frame was drawn
 *
 * Also reads frame offsets (ImageDescriptor left/top) so sub-frames are
 * composited at the correct position on the logical screen.
 */
private fun decodeGifFrames(bytes: ByteArray): List<Pair<ImageBitmap, Int>> {
    val readers = ImageIO.getImageReadersByFormatName("gif")
    if (!readers.hasNext()) {
        println("[GIF/JVM] No GIF ImageReader available in this JVM")
        return emptyList()
    }
    val reader = readers.next()
    return try {
        val iis = MemoryCacheImageInputStream(ByteArrayInputStream(bytes))
        reader.input = iis

        val frameCount = reader.getNumImages(true)
        if (frameCount <= 0) return emptyList()
        // Reject video-length GIFs to prevent hundreds of MB of frame allocations
        if (frameCount > 300) return emptyList()

        // Read the logical screen size from the stream metadata
        val streamMeta = reader.streamMetadata
        var canvasWidth = 0
        var canvasHeight = 0
        if (streamMeta != null) {
            runCatching {
                val tree = streamMeta.getAsTree("javax_imageio_gif_stream_1.0") as IIOMetadataNode
                val lsd = tree.getElementsByTagName("LogicalScreenDescriptor").item(0) as? IIOMetadataNode
                canvasWidth = lsd?.getAttribute("logicalScreenWidth")?.toIntOrNull() ?: 0
                canvasHeight = lsd?.getAttribute("logicalScreenHeight")?.toIntOrNull() ?: 0
            }
        }

        // Read first frame to check dimensions
        val firstFrame = reader.read(0)
        if (canvasWidth <= 0) canvasWidth = firstFrame.width
        if (canvasHeight <= 0) canvasHeight = firstFrame.height

        // Reject absurdly large GIFs
        if (canvasWidth.toLong() * canvasHeight > 8_000_000) {
            return emptyList()
        }

        val canvas = BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB)
        // Snapshot for "restoreToPrevious" disposal
        var previousSnapshot: BufferedImage? = null

        (0 until frameCount).map { i ->
            val frame: BufferedImage = if (i == 0) firstFrame else reader.read(i)
            val meta = reader.getImageMetadata(i)

            // Parse frame metadata: delay, disposal method, and position offset
            var delayMs = 100
            var disposalMethod = "none"
            var frameX = 0
            var frameY = 0

            runCatching {
                val tree = meta.getAsTree("javax_imageio_gif_image_1.0") as IIOMetadataNode

                // Frame delay from GraphicControlExtension
                val gce =
                    tree.getElementsByTagName("GraphicControlExtension").item(0)
                        as? IIOMetadataNode
                val cs = gce?.getAttribute("delayTime")?.toIntOrNull() ?: 10
                delayMs = (cs * 10).coerceAtLeast(50)
                disposalMethod = gce?.getAttribute("disposalMethod") ?: "none"

                // Frame offset from ImageDescriptor
                val desc =
                    tree.getElementsByTagName("ImageDescriptor").item(0)
                        as? IIOMetadataNode
                frameX = desc?.getAttribute("imageLeftPosition")?.toIntOrNull() ?: 0
                frameY = desc?.getAttribute("imageTopPosition")?.toIntOrNull() ?: 0
            }

            // For "restoreToPrevious", save canvas state BEFORE drawing this frame
            if (disposalMethod == "restoreToPrevious") {
                previousSnapshot = BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB)
                previousSnapshot!!.createGraphics().let { g ->
                    g.drawImage(canvas, 0, 0, null)
                    g.dispose()
                }
            }

            // Composite frame at its offset position
            val g = canvas.createGraphics()
            g.drawImage(frame, frameX, frameY, null)
            g.dispose()

            // Capture the composited frame for output
            val outputBitmap = canvas.toComposeImageBitmap()

            // Apply disposal method AFTER capturing the frame
            when (disposalMethod) {
                "restoreToBackgroundColor" -> {
                    // Clear the region this frame occupied
                    val g2 = canvas.createGraphics()
                    g2.composite = java.awt.AlphaComposite.Clear
                    g2.fillRect(frameX, frameY, frame.width, frame.height)
                    g2.dispose()
                }
                "restoreToPrevious" -> {
                    // Restore canvas to snapshot taken before this frame
                    previousSnapshot?.let { snap ->
                        val g2 = canvas.createGraphics()
                        g2.composite = java.awt.AlphaComposite.Src
                        g2.drawImage(snap, 0, 0, null)
                        g2.dispose()
                    }
                }
                // "none", "doNotDispose" → leave canvas as-is
            }

            outputBitmap to delayMs
        }
    } catch (e: Exception) {
        println("[GIF/JVM] Decode error: ${e.message}")
        emptyList()
    } finally {
        reader.dispose()
    }
}
