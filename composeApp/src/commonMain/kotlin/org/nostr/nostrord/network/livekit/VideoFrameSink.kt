package org.nostr.nostrord.network.livekit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A render surface for [MediaEngine.attachVideo] on targets without a DOM.
 *
 * The web attaches an `HTMLVideoElement` and the browser does the drawing; Compose has no such
 * element, so it hands the engine one of these instead and draws whatever lands in [frame].
 * Only the latest frame is kept — video is not a queue, and a slow recomposition should skip
 * frames rather than fall behind.
 */
class VideoFrameSink {
    /** One decoded frame, tightly packed RGBA. */
    class RgbaFrame(val width: Int, val height: Int, val rgba: ByteArray)

    private val _frame = MutableStateFlow<RgbaFrame?>(null)
    val frame: StateFlow<RgbaFrame?> = _frame.asStateFlow()

    fun push(frame: RgbaFrame) {
        _frame.value = frame
    }
}
