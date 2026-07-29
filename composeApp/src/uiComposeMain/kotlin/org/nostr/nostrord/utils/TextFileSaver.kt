package org.nostr.nostrord.utils

import androidx.compose.runtime.Composable

/** True on platforms that can write a text file to the user's downloads. */
expect val supportsTextFileSave: Boolean

/**
 * Returns a suspend callback that saves [content] as [fileName] in the platform's downloads
 * location and reports whether it succeeded. The call suspends for the duration of the write
 * and surfaces its own user feedback (Toast / system message) per platform.
 */
@Composable
expect fun rememberTextFileSaver(): suspend (content: String, fileName: String) -> Boolean
