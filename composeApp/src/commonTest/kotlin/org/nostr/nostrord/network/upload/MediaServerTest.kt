package org.nostr.nostrord.network.upload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaServerTest {
    @Test
    fun normalizeAddsSchemeAndStripsPathAndCase() {
        assertEquals("https://blossom.band", normalizeMediaServerUrl("blossom.band"))
        assertEquals("https://blossom.band", normalizeMediaServerUrl("  blossom.band/  "))
        assertEquals("https://blossom.band", normalizeMediaServerUrl("https://Blossom.Band"))
        assertEquals("https://blossom.band", normalizeMediaServerUrl("https://blossom.band/upload?a=1"))
        assertEquals("https://cdn.example.com:8443", normalizeMediaServerUrl("cdn.example.com:8443/x"))
    }

    @Test
    fun normalizeRejectsNonServerInput() {
        assertNull(normalizeMediaServerUrl(""))
        assertNull(normalizeMediaServerUrl("   "))
        // Plaintext uploads would leak the authorization event.
        assertNull(normalizeMediaServerUrl("http://blossom.band"))
        assertNull(normalizeMediaServerUrl("wss://relay.example.com"))
        assertNull(normalizeMediaServerUrl("localhost"))
        assertNull(normalizeMediaServerUrl("blossom band.com"))
        assertNull(normalizeMediaServerUrl("https://"))
    }

    @Test
    fun blossomUploadUrlIsTheServerOrigin() {
        assertEquals("https://blossom.band/upload", BlossomUploader.uploadUrl("https://blossom.band"))
        assertEquals("https://blossom.band/upload", BlossomUploader.uploadUrl("https://blossom.band/"))
    }

    @Test
    fun presetsAreNormalizedAndUnique() {
        val presets = NIP96_SERVICES + RECOMMENDED_BLOSSOM_SERVERS
        assertEquals(presets.size, presets.toSet().size)
        // Every preset is already normalized, so a user retyping one collides with it.
        assertTrue(presets.all { normalizeMediaServerUrl(it) == it })
        assertTrue(DEFAULT_BLOSSOM_SERVERS.all { it in RECOMMENDED_BLOSSOM_SERVERS })
        assertTrue(DEFAULT_NIP96_SERVICE in NIP96_SERVICES)
    }

    @Test
    fun displayNameIsTheHost() {
        assertEquals("blossom.band", mediaServerDisplayName("https://blossom.band"))
        assertEquals("nostr.build", mediaServerDisplayName("https://nostr.build/"))
    }

    @Test
    fun blobRefMimeWinsOverExtension() {
        assertEquals("image/png", mimeTypeForFilename("photo.PNG"))
        assertEquals("video/mp4", mimeTypeForFilename("clip.mp4"))
        assertEquals("image/webp", mimeTypeForFilename("nostrord-blob|image/webp|42"))
        assertEquals("application/octet-stream", mimeTypeForFilename("notes.txt"))
    }
}
