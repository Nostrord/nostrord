package org.nostr.nostrord.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadFileNameTest {
    private val hash = "c638e2183633f137fbd124fa8570b227dc2b6834630976f392e91e9566d55b15"

    @Test
    fun keepsTheExtensionThePathAdvertises() {
        assertEquals("$hash.ogg", downloadFileName("https://blossom.primal.net/$hash.ogg", "audio/ogg"))
    }

    @Test
    fun replacesThePlaceholderExtensionWithTheOneTheContentTypeImplies() {
        assertEquals(
            "$hash.ogg",
            downloadFileName("https://r2a.primal.net/uploads2/c/63/8e/$hash.bin", "audio/ogg"),
        )
    }

    @Test
    fun keepsThePlaceholderWhenTheServerSaysNothingUseful() {
        assertEquals("$hash.bin", downloadFileName("https://r2a.primal.net/$hash.bin", "application/octet-stream"))
        assertEquals("$hash.bin", downloadFileName("https://r2a.primal.net/$hash.bin"))
    }

    @Test
    fun addsAnExtensionToAnExtensionlessPath() {
        assertEquals("$hash.mp3", downloadFileName("https://blossom.primal.net/$hash", "audio/mpeg"))
        assertEquals(hash, downloadFileName("https://blossom.primal.net/$hash"))
    }

    @Test
    fun stripsQueryAndFragment() {
        assertEquals("clip.mp4", downloadFileName("https://cdn.example/clip.mp4?token=abc#t=10", "video/mp4"))
    }

    @Test
    fun fallsBackToTheGivenBaseForAPathWithNoLastSegment() {
        assertEquals("audio.ogg", downloadFileName("https://cdn.example/", "audio/ogg", fallbackBase = "audio"))
    }

    @Test
    fun doesNotMistakeADomainForAnExtension() {
        assertEquals("cdn.example.ogg", downloadFileName("https://cdn.example", "audio/ogg"))
    }

    @Test
    fun mapsContentTypesWhoseSubtypeIsNotTheExtension() {
        assertEquals("jpg", extensionForMimeType("image/jpeg"))
        assertEquals("m4a", extensionForMimeType("audio/mp4"))
        assertEquals("mov", extensionForMimeType("video/quicktime"))
        assertEquals("ogg", extensionForMimeType("audio/ogg; codecs=opus"))
        assertNull(extensionForMimeType("image/*"))
        assertNull(extensionForMimeType(null))
    }
}
