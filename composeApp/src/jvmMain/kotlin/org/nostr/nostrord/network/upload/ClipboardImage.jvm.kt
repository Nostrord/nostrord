package org.nostr.nostrord.network.upload

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

actual class ClipboardImageReader {
    private val clipboard =
        java.awt.Toolkit
            .getDefaultToolkit()
            .systemClipboard

    actual fun hasImage(): Boolean = runCatching {
        clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor) ||
            clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor)
    }.getOrDefault(false)

    actual suspend fun read(): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        when {
            runCatching { clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor) }.getOrDefault(false) -> {
                val image =
                    runCatching {
                        clipboard.getData(DataFlavor.imageFlavor) as? java.awt.Image
                    }.getOrNull() ?: return@withContext null
                val buffered =
                    if (image is BufferedImage) {
                        image
                    } else {
                        BufferedImage(
                            image.getWidth(null),
                            image.getHeight(null),
                            BufferedImage.TYPE_INT_ARGB,
                        ).also {
                            val g = it.createGraphics()
                            g.drawImage(image, 0, 0, null)
                            g.dispose()
                        }
                    }
                val baos = ByteArrayOutputStream()
                ImageIO.write(buffered, "png", baos)
                baos.toByteArray() to "clipboard.png"
            }
            runCatching { clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor) }.getOrDefault(false) -> {
                @Suppress("UNCHECKED_CAST")
                val files =
                    runCatching {
                        clipboard.getData(DataFlavor.javaFileListFlavor) as? List<java.io.File>
                    }.getOrNull()
                val file = files?.firstOrNull() ?: return@withContext null
                val mime = mimeTypeForFilename(file.name)
                if (!isSupportedMediaMime(mime)) throw UnsupportedFileTypeException(file.extension)
                if (file.length() > MAX_UPLOAD_BYTES) throw FileTooLargeException()
                file.readBytes() to file.name
            }
            else -> null
        }
    }
}

@Composable
actual fun rememberClipboardImageReader(): ClipboardImageReader = remember { ClipboardImageReader() }

@Composable
actual fun PasteMediaEffect(
    onMediaPasted: (ByteArray, String) -> Unit,
    onError: (String) -> Unit,
) {}
