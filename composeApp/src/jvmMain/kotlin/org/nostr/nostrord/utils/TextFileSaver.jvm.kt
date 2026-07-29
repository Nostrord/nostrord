package org.nostr.nostrord.utils

import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.nostr.nostrord.di.AppModule
import java.io.File

actual val supportsTextFileSave: Boolean = true

@Composable
actual fun rememberTextFileSaver(): suspend (content: String, fileName: String) -> Boolean = { content, fileName ->
    withContext(Dispatchers.IO) {
        try {
            val downloads = File(System.getProperty("user.home"), "Downloads").apply { if (!exists()) mkdirs() }
            val target = uniqueFile(downloads, fileName)
            target.writeText(content)
            AppModule.postSystemMessage("Saved to ${target.absolutePath}")
            true
        } catch (_: Exception) {
            AppModule.postSystemMessage("Couldn't save the file")
            false
        }
    }
}
