package org.nostr.nostrord.utils

import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.nostr.nostrord.di.AppModule
import java.io.File

actual val supportsMediaDownload: Boolean = true

@Composable
actual fun rememberMediaDownloader(): suspend (bytes: ByteArray, fileName: String, mimeType: String) -> Boolean = { bytes, fileName, _ ->
    withContext(Dispatchers.IO) {
        try {
            val downloads = File(System.getProperty("user.home"), "Downloads").apply { if (!exists()) mkdirs() }
            val target = uniqueFile(downloads, fileName)
            target.writeBytes(bytes)
            AppModule.postSystemMessage("Saved to ${target.absolutePath}")
            true
        } catch (_: Exception) {
            AppModule.postSystemMessage("Couldn't save file")
            false
        }
    }
}

/** Avoids clobbering an existing file by appending " (1)", " (2)", ... before the extension. */
internal fun uniqueFile(
    dir: File,
    name: String,
): File {
    var candidate = File(dir, name)
    if (!candidate.exists()) return candidate
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var i = 1
    while (candidate.exists()) {
        candidate = File(dir, "$base ($i)$ext")
        i++
    }
    return candidate
}
