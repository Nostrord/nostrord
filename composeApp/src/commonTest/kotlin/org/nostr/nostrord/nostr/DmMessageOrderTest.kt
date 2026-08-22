package org.nostr.nostrord.nostr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DmMessageOrderTest {
    @Test
    fun `stamps within one second stay strictly increasing`() {
        val a = DmMessageOrder.next(1_000_500)
        val b = DmMessageOrder.next(1_000_500)
        val c = DmMessageOrder.next(1_000_400)
        assertEquals(1_000L to 500, a)
        assertEquals(1_000L to 501, b)
        assertEquals(1_000L to 502, c)
    }

    @Test
    fun `the ms tag breaks a created_at tie and a missing or bad tag counts as zero`() {
        val image = DmMessageOrder.orderKey(10, listOf(listOf("ms", "120")))
        val text = DmMessageOrder.orderKey(10, listOf(listOf("ms", "121")))
        val legacy = DmMessageOrder.orderKey(10, emptyList())
        val bad = DmMessageOrder.orderKey(10, listOf(listOf("ms", "5000")))
        assertTrue(image < text)
        assertEquals(10_000, legacy)
        assertEquals(legacy, bad)
        assertEquals(text, DmMessageOrder.orderKey(10, """[["p","x"],["ms","121"]]"""))
    }

    @Test
    fun `withOrderTag replaces a stale copy`() {
        val tags = DmMessageOrder.withOrderTag(listOf(listOf("ms", "1"), listOf("e", "id")), 42)
        assertEquals(listOf(listOf("e", "id"), listOf("ms", "42")), tags)
    }
}
