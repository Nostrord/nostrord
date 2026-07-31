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
    fun uploadUrlsMatchEachProtocol() {
        assertEquals("https://blossom.band/upload", BlossomUploader.uploadUrl("https://blossom.band"))
        assertEquals("https://blossom.band/upload", BlossomUploader.uploadUrl("https://blossom.band/"))
        assertEquals(
            "https://nostr.build/api/v2/upload/files",
            NostrBuildUploader.uploadUrl("https://nostr.build"),
        )
    }

    @Test
    fun builtInsAreUniqueAndDefaultIsNostrBuild() {
        assertEquals(BUILT_IN_MEDIA_SERVERS.size, BUILT_IN_MEDIA_SERVERS.map { it.url }.toSet().size)
        assertTrue(BUILT_IN_MEDIA_SERVERS.all { it.builtIn })
        // Every built-in URL is already normalized, so a user retyping one collides with it.
        assertTrue(BUILT_IN_MEDIA_SERVERS.all { normalizeMediaServerUrl(it.url) == it.url })
        assertEquals(MediaServerProtocol.NostrBuild, DEFAULT_MEDIA_SERVER.protocol)
    }

    @Test
    fun displayNameIsTheHost() {
        assertEquals("blossom.band", mediaServerDisplayName("https://blossom.band"))
    }

    @Test
    fun blobRefMimeWinsOverExtension() {
        assertEquals("image/png", mimeTypeForFilename("photo.PNG"))
        assertEquals("video/mp4", mimeTypeForFilename("clip.mp4"))
        assertEquals("image/webp", mimeTypeForFilename("nostrord-blob|image/webp|42"))
        assertEquals("application/octet-stream", mimeTypeForFilename("notes.txt"))
    }
}
