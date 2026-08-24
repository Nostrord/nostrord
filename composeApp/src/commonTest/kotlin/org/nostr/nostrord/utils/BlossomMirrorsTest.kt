package org.nostr.nostrord.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BlossomMirrorsTest {
    private val hash = "c638e2183633f137fbd124fa8570b227dc2b6834630976f392e91e9566d55b15"
    private val servers = listOf("https://blossom.band", "https://blossom.primal.net/", "https://nostr.media")

    @Test
    fun readsTheHashWithOrWithoutAnExtension() {
        assertEquals(hash, blossomHashFromUrl("https://blossom.primal.net/$hash.ogg"))
        assertEquals(hash, blossomHashFromUrl("https://blossom.primal.net/$hash"))
        assertNull(blossomHashFromUrl("https://cdn.example/audio.ogg"))
    }

    @Test
    fun keepsTheExtensionAndSkipsTheHostThatAlreadyAnswered() {
        assertEquals(
            listOf("https://blossom.band/$hash.ogg", "https://nostr.media/$hash.ogg"),
            blossomMirrorUrls("https://blossom.primal.net/$hash.ogg", servers),
        )
    }

    @Test
    fun capsTheFanOut() {
        val many = List(10) { "https://server$it.example" }
        assertEquals(4, blossomMirrorUrls("https://blossom.primal.net/$hash.ogg", many).size)
        assertEquals(2, blossomMirrorUrls("https://blossom.primal.net/$hash.ogg", many, limit = 2).size)
    }

    @Test
    fun hasNoMirrorForAUrlThatIsNotHashKeyed() {
        assertEquals(emptyList(), blossomMirrorUrls("https://cdn.example/audio.ogg", servers))
    }
}
