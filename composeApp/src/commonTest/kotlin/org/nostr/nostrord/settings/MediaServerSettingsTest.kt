package org.nostr.nostrord.settings

import org.nostr.nostrord.network.upload.BUILT_IN_MEDIA_SERVERS
import org.nostr.nostrord.network.upload.DEFAULT_MEDIA_SERVER
import org.nostr.nostrord.network.upload.MediaServerProtocol
import org.nostr.nostrord.utils.Result
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaServerSettingsTest {
    private val settings = MediaServerSettings()

    @AfterTest
    fun reset() {
        settings.customServers.value.forEach { settings.removeCustomServer(it) }
        settings.select(DEFAULT_MEDIA_SERVER)
    }

    @Test
    fun defaultsToNostrBuildWithOnlyBuiltIns() {
        assertEquals(DEFAULT_MEDIA_SERVER, settings.selected.value)
        assertEquals(BUILT_IN_MEDIA_SERVERS, settings.servers.value)
    }

    @Test
    fun selectSwitchesToABuiltInBlossomServer() {
        val blossom = BUILT_IN_MEDIA_SERVERS.first { it.protocol == MediaServerProtocol.Blossom }
        settings.select(blossom)
        assertEquals(blossom, settings.selected.value)
        // A fresh instance reads the same persisted choice.
        assertEquals(blossom, MediaServerSettings().selected.value)
    }

    @Test
    fun addCustomServerNormalizesAndPersists() {
        val added = settings.addCustomServer("  Blossom.Example.com/ ")
        assertTrue(added is Result.Success)
        assertEquals("https://blossom.example.com", added.data.url)
        assertEquals(MediaServerProtocol.Blossom, added.data.protocol)
        assertTrue(settings.servers.value.contains(added.data))
        assertTrue(MediaServerSettings().servers.value.any { it.url == added.data.url && !it.builtIn })
    }

    @Test
    fun addCustomServerRejectsInvalidAndDuplicates() {
        assertTrue(settings.addCustomServer("not a host") is Result.Error)
        assertTrue(settings.addCustomServer(BUILT_IN_MEDIA_SERVERS[1].url) is Result.Error)
        settings.addCustomServer("blossom.example.com")
        assertTrue(settings.addCustomServer("https://blossom.example.com/") is Result.Error)
    }

    @Test
    fun removingTheSelectedCustomServerFallsBackToDefault() {
        val added = settings.addCustomServer("blossom.example.com")
        assertTrue(added is Result.Success)
        settings.select(added.data)
        settings.removeCustomServer(added.data)
        assertEquals(DEFAULT_MEDIA_SERVER, settings.selected.value)
        assertTrue(settings.servers.value.none { it.url == added.data.url })
    }

    @Test
    fun builtInServersCannotBeRemoved() {
        settings.removeCustomServer(BUILT_IN_MEDIA_SERVERS.first())
        assertEquals(BUILT_IN_MEDIA_SERVERS, settings.servers.value)
    }
}
