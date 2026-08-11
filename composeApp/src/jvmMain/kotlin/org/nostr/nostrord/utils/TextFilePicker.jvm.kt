package org.nostr.nostrord.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lwjgl.system.MemoryStack
import org.lwjgl.util.tinyfd.TinyFileDialogs
import java.io.File

actual val supportsTextFilePick: Boolean = true

actual class TextFilePicker(
    private val doLaunch: () -> Unit,
) {
    actual fun launch() = doLaunch()
}

/**
 * tinyfd rather than java.awt.FileDialog: it shells out to zenity/kdialog, whose window the WM
 * raises on its own, so focus returns to the app on close without any AWT focus handling.
 */
@Composable
actual fun rememberTextFilePicker(
    onError: (String) -> Unit,
    onPicked: (content: String) -> Unit,
): TextFilePicker {
    val scope = rememberCoroutineScope()
    val currentOnError = rememberUpdatedState(onError)
    val currentOnPicked = rememberUpdatedState(onPicked)
    return remember {
        TextFilePicker {
            // tinyfd blocks on the dialog subprocess, so it cannot run on the UI thread.
            scope.launch(Dispatchers.IO) {
                val path = openTextFileDialog() ?: return@launch // cancelled
                val content =
                    try {
                        File(path).readText()
                    } catch (_: Exception) {
                        withContext(Dispatchers.Main) { currentOnError.value("Couldn't read that file.") }
                        return@launch
                    }
                withContext(Dispatchers.Main) { currentOnPicked.value(content) }
            }
        }
    }
}

private fun openTextFileDialog(): String? {
    MemoryStack.stackPush().use { stack ->
        val patterns = stack.mallocPointer(2)
        patterns.put(stack.UTF8("*.jsonl"))
        patterns.put(stack.UTF8("*.json"))
        patterns.flip()
        return TinyFileDialogs.tinyfd_openFileDialog("Select backup file", null, patterns, "Backup file", false)
    }
}
