package org.nostr.nostrord.utils

import androidx.compose.runtime.Composable

/** True on platforms that can save a file to the device (gallery / music / downloads folder). */
expect val supportsMediaDownload: Boolean

/**
 * Returns a suspend callback that saves [bytes] to the device under [fileName] with [mimeType]
 * (e.g. "audio/ogg") and returns whether it succeeded. The call suspends for the duration of the
 * save (so callers can show a progress indicator) and surfaces its own user feedback (Toast /
 * system message) per platform. [fileName] carries the extension the file should land with; build
 * it with [downloadFileName] so a host that serves hashed blobs can't dictate a useless one.
 */
@Composable
expect fun rememberMediaDownloader(): suspend (bytes: ByteArray, fileName: String, mimeType: String) -> Boolean
