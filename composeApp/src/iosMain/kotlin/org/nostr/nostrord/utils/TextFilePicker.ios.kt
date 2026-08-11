package org.nostr.nostrord.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// Choosing a file on iOS needs a UIDocumentPickerViewController presented from the root view
// controller, the same plumbing TextFileSaver.ios.kt is waiting on. Until that lands the import
// button is hidden here rather than opening a picker that resolves to nothing.
actual val supportsTextFilePick: Boolean = false

@Composable
actual fun rememberTextFilePicker(
    onError: (String) -> Unit,
    onPicked: (content: String) -> Unit,
): TextFilePicker = remember { TextFilePicker { } }

actual class TextFilePicker(
    private val doLaunch: () -> Unit,
) {
    actual fun launch() = doLaunch()
}
