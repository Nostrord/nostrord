package org.nostr.nostrord.nostr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Event URIs we generate must be nevent, never a bare note (issue #290). */
class Nip27UriTest {

    private val eventIdHex = "b9f5441e45ca39179320e0031cfb18e34078f374526e496f1c1b0d53d26b7e7e"
    private val authorHex = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"

    @Test
    fun `createEventUri encodes nevent with author, relay and kind`() {
        val uri = Nip27.createEventUri(eventIdHex, listOf("wss://relay.example"), authorHex, 9)
        assertTrue(uri.startsWith("nostr:nevent1"), uri)

        val decoded = Nip19.decode(uri.removePrefix("nostr:"))
        assertIs<Nip19.Entity.Nevent>(decoded)
        assertEquals(eventIdHex, decoded.eventId)
        assertEquals(authorHex, decoded.author)
        assertEquals(9, decoded.kind)
        assertEquals(listOf("wss://relay.example"), decoded.relays)
    }

    @Test
    fun `createEventUri stays nevent without hints`() {
        assertTrue(Nip27.createEventUri(eventIdHex).startsWith("nostr:nevent1"))
    }
}
