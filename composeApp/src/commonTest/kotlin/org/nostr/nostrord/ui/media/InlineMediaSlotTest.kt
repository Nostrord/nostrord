package org.nostr.nostrord.ui.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InlineMediaSlotTest {
    @Test
    fun landscapeIsCappedByWidth() {
        assertEquals(360, reservedWidthPx(1920, 1080))
        assertEquals(360, reservedWidthPx(720, 480))
    }

    @Test
    fun portraitIsCappedByHeightSoTheBoxStaysInside300() {
        // 1080x1920 scaled to 300 tall is ~169 wide, well under the 360 width cap.
        assertEquals(169, reservedWidthPx(1080, 1920))
        assertTrue(reservedWidthPx(1080, 1920) < INLINE_MEDIA_MAX_WIDTH)
    }

    @Test
    fun smallImagesKeepTheirNaturalWidth() {
        assertEquals(80, reservedWidthPx(80, 60))
        assertEquals(240, reservedWidthPx(240, 200))
    }

    @Test
    fun unusableDimensionsFallBackToTheFloor() {
        assertEquals(INLINE_MEDIA_MIN_SIDE, reservedWidthPx(0, 100))
        assertEquals(INLINE_MEDIA_MIN_SIDE, reservedWidthPx(100, 0))
        assertEquals(INLINE_MEDIA_MIN_SIDE, reservedWidthPx(-4, -4))
    }

    @Test
    fun reservedWidthIsNeverZeroForExtremeRatios() {
        // A 1px-wide banner still has to reserve something the skeleton can paint.
        assertTrue(reservedWidthPx(1, 4000) >= 1)
    }
}
