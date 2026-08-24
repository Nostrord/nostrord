package org.nostr.nostrord.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual val supportsMediaDownload: Boolean = true

@Composable
actual fun rememberMediaDownloader(): suspend (bytes: ByteArray, fileName: String, mimeType: String) -> Boolean {
    val context = LocalContext.current.applicationContext
    return { bytes, fileName, mimeType ->
        val ok = withContext(Dispatchers.IO) { saveMedia(context, bytes, fileName, mimeType) }
        // Toast (not a snackbar): the fullscreen image Dialog is a separate window, so a
        // snackbar in the host window would render behind it and never be seen.
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                if (ok) "Saved to ${targetDirectory(mimeType)}" else "Couldn't save file",
                Toast.LENGTH_SHORT,
            ).show()
        }
        ok
    }
}

/**
 * On API 29+ inserts into the MediaStore collection that matches [mimeType] (no permission needed
 * under scoped storage), so audio lands in Music and shows up in music players rather than in the
 * gallery. On older versions falls back to the app-specific external dir, which is also
 * permission-free but not indexed.
 */
private fun saveMedia(
    context: Context,
    bytes: ByteArray,
    fileName: String,
    mimeType: String,
): Boolean {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, targetDirectory(mimeType) + "/Nostrord")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            val uri = resolver.insert(collectionFor(mimeType), values) ?: return false
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } else {
            val dir = context.getExternalFilesDir(targetDirectory(mimeType)) ?: return false
            if (!dir.exists()) dir.mkdirs()
            File(dir, fileName).outputStream().use { it.write(bytes) }
            true
        }
    } catch (_: Exception) {
        false
    }
}

private fun collectionFor(mimeType: String): Uri = when (mimeType.substringBefore('/')) {
    "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
}

private fun targetDirectory(mimeType: String): String = when (mimeType.substringBefore('/')) {
    "image" -> Environment.DIRECTORY_PICTURES
    "audio" -> Environment.DIRECTORY_MUSIC
    "video" -> Environment.DIRECTORY_MOVIES
    else -> Environment.DIRECTORY_DOWNLOADS
}
