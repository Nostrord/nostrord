package org.nostr.nostrord.ui.util

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Wrap tightly packed RGBA pixels in an [ImageBitmap].
 *
 * Compose has no common raw-pixel constructor, so the wrap is per-backend: an Android Bitmap
 * on Android, a Skia bitmap on the Skiko targets. Used for AV space video tiles, where frames
 * arrive as RGBA from the media engine.
 */
expect fun rgbaToImageBitmap(width: Int, height: Int, rgba: ByteArray): ImageBitmap
