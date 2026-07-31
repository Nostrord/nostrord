package org.nostr.nostrord.settings

import org.nostr.nostrord.network.upload.DEFAULT_BLOSSOM_SERVERS
import org.nostr.nostrord.network.upload.DEFAULT_NIP96_SERVICE
import org.nostr.nostrord.network.upload.MediaUploadService
import org.nostr.nostrord.network.upload.RECOMMENDED_BLOSSOM_SERVERS
import org.nostr.nostrord.utils.Result
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaServerSettingsTest {
    private val settings = MediaServerSettings()

    @AfterTest
    fun reset() {
        settings.useNip96(DEFAULT_NIP96_SERVICE)
        settings.blossomServers.value.forEach { settings.removeBlossomServer(it) }
        DEFAULT_BLOSSOM_SERVERS.forEach { settings.addBlossomServer(it) }
    }

    @Test
    fun defaultsToTheDefaultNip96HostAndTheRecommendedBlossomList() {
        assertEquals(MediaUploadService.Nip96(DEFAULT_NIP96_SERVICE), settings.service.value)
        assertEquals(DEFAULT_BLOSSOM_SERVERS, settings.blossomServers.value)
    }

    @Test
    fun serviceChoicePersistsBothWays() {
        settings.useBlossom()
        assertEquals(MediaUploadService.Blossom, settings.service.value)
        assertEquals(MediaUploadService.Blossom, MediaServerSettings().service.value)

        val host = "https://nostrcheck.me"
        settings.useNip96(host)
        assertEquals(MediaUploadService.Nip96(host), settings.service.value)
        assertEquals(MediaUploadService.Nip96(host), MediaServerSettings().service.value)
    }

    @Test
    fun addBlossomServerNormalizesAppendsAndPersists() {
        val added = settings.addBlossomServer("  Blossom.Example.com/ ")
        assertTrue(added is Result.Success)
        assertEquals("https://blossom.example.com", added.data)
        // Appended, not promoted: the existing preferred server keeps its place.
        assertEquals(added.data, settings.blossomServers.value.last())
        assertTrue(MediaServerSettings().blossomServers.value.contains(added.data))
    }

    @Test
    fun addBlossomServerRejectsInvalidAndDuplicates() {
        assertTrue(settings.addBlossomServer("not a host") is Result.Error)
        assertTrue(settings.addBlossomServer(settings.blossomServers.value.first()) is Result.Error)
    }

    @Test
    fun moveReordersAndClampsAtTheEnds() {
        val original = settings.blossomServers.value
        val second = original[1]
        settings.moveBlossomServer(second, up = true)
        assertEquals(second, settings.blossomServers.value.first())

        // Already first: moving up again is a no-op rather than an error or a wrap-around.
        settings.moveBlossomServer(second, up = true)
        assertEquals(second, settings.blossomServers.value.first())

        val last = settings.blossomServers.value.last()
        settings.moveBlossomServer(last, up = false)
        assertEquals(last, settings.blossomServers.value.last())
    }

    @Test
    fun removeDropsTheServerAndItReappearsAsRecommended() {
        val target = RECOMMENDED_BLOSSOM_SERVERS.first { it in settings.blossomServers.value }
        settings.removeBlossomServer(target)
        assertFalse(settings.blossomServers.value.contains(target))
        assertTrue(settings.recommendedNotAdded().contains(target))
    }

    @Test
    fun recommendedExcludesWhatIsAlreadyListed() {
        val listed = settings.blossomServers.value.toSet()
        assertTrue(settings.recommendedNotAdded().none { it in listed })
    }
}
