package org.nostr.nostrord.utils

import androidx.compose.runtime.Composable

// Saving to the iOS photo library needs NSPhotoLibraryAddUsageDescription in Info.plist plus a
// PHPhotoLibrary add request; that plumbing is not in place yet, so the download button is hidden
// on iOS (supportsMediaDownload = false) rather than crashing at the save call.
actual val supportsMediaDownload: Boolean = false

@Composable
actual fun rememberMediaDownloader(): suspend (bytes: ByteArray, fileName: String, mimeType: String) -> Boolean = { _, _, _ -> false }
