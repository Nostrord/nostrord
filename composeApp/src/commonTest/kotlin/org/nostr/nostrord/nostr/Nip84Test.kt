package org.nostr.nostrord.nostr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Nip84Test {
    private val tags =
        listOf(
            listOf("comment", "Ótima thread para você entender."),
            listOf("r", "https://x.com/varosbr/status/2083221408901902834"),
            listOf("textpositionselector", "2790", "3442"),
        )

    @Test
    fun parsesCommentAndSource() {
        val h = Nip84.parse("  the highlighted excerpt  ", tags)
        assertEquals("the highlighted excerpt", h.excerpt)
        assertEquals("Ótima thread para você entender.", h.comment)
        assertEquals("https://x.com/varosbr/status/2083221408901902834", h.sourceUrl)
        assertEquals("x.com/varosbr/status/2083221408901902834", h.sourceLabel)
    }

    @Test
    fun missingTagsAreNull() {
        val h = Nip84.parse("excerpt", listOf(listOf("e", "abc")))
        assertNull(h.comment)
        assertNull(h.sourceUrl)
        assertNull(h.sourceLabel)
    }

    @Test
    fun blankCommentIsNull() {
        assertNull(Nip84.parse("x", listOf(listOf("comment", "   "))).comment)
    }

    @Test
    fun nonHttpSourceIsIgnored() {
        // An `r` tag can also carry a relay URL; only web sources are shown as "From <url>".
        assertNull(Nip84.parse("x", listOf(listOf("r", "wss://relay.example"))).sourceUrl)
    }

    @Test
    fun sourceLabelDropsTrailingSlash() {
        assertEquals("example.com/post", Nip84.parse("x", listOf(listOf("r", "http://example.com/post/"))).sourceLabel)
    }

    @Test
    fun kindCheck() {
        assertTrue(Nip84.isHighlight(9802))
        assertFalse(Nip84.isHighlight(1))
        assertFalse(Nip84.isHighlight(null))
    }
}
