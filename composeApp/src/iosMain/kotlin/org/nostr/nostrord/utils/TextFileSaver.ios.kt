package org.nostr.nostrord.utils

import androidx.compose.runtime.Composable

// Exporting a file on iOS goes through a share sheet (UIActivityViewController), which is the
// same plumbing ShareUtils.ios.kt still lacks. Until that lands the save button is hidden here
// rather than writing into a sandbox the user cannot reach; copying the key still works.
actual val supportsTextFileSave: Boolean = false

@Composable
actual fun rememberTextFileSaver(): suspend (content: String, fileName: String) -> Boolean = { _, _ -> false }
