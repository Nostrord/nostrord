package org.nostr.nostrord.utils

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

actual val supportsTextFilePick: Boolean = true

actual class TextFilePicker(
    private val doLaunch: () -> Unit,
) {
    actual fun launch() = doLaunch()
}

@Composable
actual fun rememberTextFilePicker(
    onError: (String) -> Unit,
    onPicked: (content: String) -> Unit,
): TextFilePicker {
    val context = LocalContext.current
    val currentOnError = rememberUpdatedState(onError)
    val currentOnPicked = rememberUpdatedState(onPicked)
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult // cancelled
            val content =
                try {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                } catch (_: Exception) {
                    null
                }
            if (content == null) currentOnError.value("Couldn't read that file.") else currentOnPicked.value(content)
        }
    return remember(launcher) {
        // Backups are written as application/jsonl, which many file providers do not recognise;
        // the wildcard keeps the file selectable instead of greying it out.
        TextFilePicker { launcher.launch(arrayOf("application/jsonl", "application/json", "text/*", "*/*")) }
    }
}
