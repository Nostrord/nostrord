package org.nostr.nostrord.utils

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual val supportsTextFileSave: Boolean = true

@Composable
actual fun rememberTextFileSaver(): suspend (content: String, fileName: String) -> Boolean {
    val context = LocalContext.current.applicationContext
    return { content, fileName ->
        val target = withContext(Dispatchers.IO) { saveText(context, content, fileName) }
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                target?.let { "Saved to $it" } ?: "Couldn't save the file",
                Toast.LENGTH_SHORT,
            ).show()
        }
        target != null
    }
}

/**
 * On API 29+ inserts into MediaStore Downloads/Nostrord (no permission needed under scoped
 * storage). Below that writes to the app-specific external Downloads dir, which is also
 * permission-free but only reachable through a file manager that shows app storage.
 * Returns the location shown to the user, or null when the write failed.
 */
private fun saveText(
    context: Context,
    content: String,
    fileName: String,
): String? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Nostrord")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            null
        } else {
            resolver.openOutputStream(uri)?.use { it.write(content.encodeToByteArray()) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "Downloads/Nostrord"
        }
    } else {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        val target = File(dir, fileName)
        target.writeText(content)
        target.absolutePath
    }
} catch (_: Exception) {
    null
}
