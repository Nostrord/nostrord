package org.nostr.nostrord.utils

import androidx.compose.runtime.Composable

/** True on platforms that can let the user choose a text file to read. */
expect val supportsTextFilePick: Boolean

/**
 * Picks one text file and hands its whole content to [onPicked]. Cancelling calls nothing.
 * Reading is bounded by the caller's use: only small backup files go through here.
 */
expect class TextFilePicker {
    fun launch()
}

@Composable
expect fun rememberTextFilePicker(
    onError: (String) -> Unit = {},
    onPicked: (content: String) -> Unit,
): TextFilePicker
