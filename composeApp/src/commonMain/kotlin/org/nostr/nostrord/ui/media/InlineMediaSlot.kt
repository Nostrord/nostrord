package org.nostr.nostrord.ui.media

import kotlin.math.roundToInt

/** Cap for inline chat media, shared by the web `.msg-image` rules and the Compose size modifier. */
const val INLINE_MEDIA_MAX_WIDTH = 360

const val INLINE_MEDIA_MAX_HEIGHT = 300

/** Floor for a slot with no NIP-68 `dim`, so the loading skeleton still has a box to fill. */
const val INLINE_MEDIA_MIN_SIDE = 120

/**
 * Displayed width for media whose NIP-68 dimensions are known: the natural width scaled down
 * to fit the [INLINE_MEDIA_MAX_WIDTH] x [INLINE_MEDIA_MAX_HEIGHT] box, never scaled up.
 *
 * The web needs this as a definite pixel width before the bitmap decodes, because an `<img>`
 * with no intrinsic size resolves `aspect-ratio` against a zero width and collapses the
 * reserved slot. Returns [INLINE_MEDIA_MIN_SIDE] for unusable dimensions.
 */
fun reservedWidthPx(
    width: Int,
    height: Int,
): Int {
    if (width <= 0 || height <= 0) return INLINE_MEDIA_MIN_SIDE
    val scale =
        minOf(
            1.0,
            INLINE_MEDIA_MAX_WIDTH.toDouble() / width,
            INLINE_MEDIA_MAX_HEIGHT.toDouble() / height,
        )
    return (width * scale).roundToInt().coerceAtLeast(1)
}
