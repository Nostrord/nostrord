package org.nostr.nostrord.ui.media

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaLabelsTest {
    @Test
    fun namesAMediaUrlByItsLastSegment() {
        assertEquals("clip.ogg", mediaDisplayName("https://cdn.example/a/b/clip.ogg?token=x"))
        assertEquals(40, mediaDisplayName("https://cdn.example/" + "a".repeat(80) + ".ogg").length)
    }

    @Test
    fun formatsPositionsAsMinutesAndSeconds() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:07", formatDuration(7_400))
        assertEquals("2:15", formatDuration(135_000))
        assertEquals("61:01", formatDuration(3_661_000))
    }
}
