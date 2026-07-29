package org.nostr.nostrord.ui.util

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.nio.ByteBuffer

actual fun rgbaToImageBitmap(width: Int, height: Int, rgba: ByteArray): ImageBitmap {
    // ARGB_8888's in-memory layout is RGBA bytes, so the buffer copies straight in.
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba, 0, width * height * 4))
    return bitmap.asImageBitmap()
}
