package org.nostr.nostrord.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import org.nostr.nostrord.network.upload.BUILT_IN_MEDIA_SERVERS
import org.nostr.nostrord.network.upload.DEFAULT_MEDIA_SERVER
import org.nostr.nostrord.network.upload.MediaServer
import org.nostr.nostrord.network.upload.MediaServerProtocol
import org.nostr.nostrord.network.upload.mediaServerDisplayName
import org.nostr.nostrord.network.upload.normalizeMediaServerUrl
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result

/**
 * Which media host uploads go to — picked from Settings → Media.
 *
 * [servers] is the built-in list plus any Blossom server the user added; [selected] is the
 * one every upload uses. A stored selection that no longer exists (custom server removed,
 * built-in retired) falls back to the default so uploads never break.
 */
class MediaServerSettings {
    private val json = Json { ignoreUnknownKeys = true }

    private val _customServers = MutableStateFlow(loadCustomServers())
    val customServers: StateFlow<List<MediaServer>> = _customServers.asStateFlow()

    private val _servers = MutableStateFlow(BUILT_IN_MEDIA_SERVERS + _customServers.value)
    val servers: StateFlow<List<MediaServer>> = _servers.asStateFlow()

    private val _selected =
        MutableStateFlow(
            resolve(SecureStorage.getStringPref(KEY_SELECTED, ""), _servers.value),
        )
    val selected: StateFlow<MediaServer> = _selected.asStateFlow()

    fun select(server: MediaServer) {
        if (_servers.value.none { it.url == server.url }) return
        _selected.value = server
        SecureStorage.saveStringPref(KEY_SELECTED, server.url)
    }

    /**
     * Add a user-supplied Blossom server. The address is normalized to an https origin, so
     * "blossom.example.com/" and "https://Blossom.Example.com" are the same entry.
     */
    fun addCustomServer(input: String): Result<MediaServer> {
        val url =
            normalizeMediaServerUrl(input)
                ?: return Result.Error(AppError.Unknown("Enter a valid server address, for example blossom.example.com"))
        if (_servers.value.any { it.url == url }) {
            return Result.Error(AppError.Unknown("That server is already in the list."))
        }
        val server = MediaServer(url, mediaServerDisplayName(url), MediaServerProtocol.Blossom)
        _customServers.value = _customServers.value + server
        persistCustom()
        return Result.Success(server)
    }

    fun removeCustomServer(server: MediaServer) {
        if (server.builtIn) return
        _customServers.value = _customServers.value.filterNot { it.url == server.url }
        persistCustom()
        // Removing the server in use would strand uploads on a host that is gone.
        if (_selected.value.url == server.url) select(DEFAULT_MEDIA_SERVER)
    }

    private fun persistCustom() {
        _servers.value = BUILT_IN_MEDIA_SERVERS + _customServers.value
        SecureStorage.saveStringPref(KEY_CUSTOM, json.encodeToString(_customServers.value))
    }

    private fun loadCustomServers(): List<MediaServer> {
        val raw = SecureStorage.getStringPref(KEY_CUSTOM, "")
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<MediaServer>>(raw) }
            .getOrDefault(emptyList())
            .filterNot { it.builtIn }
    }

    private fun resolve(
        url: String,
        available: List<MediaServer>,
    ): MediaServer = available.firstOrNull { it.url == url } ?: DEFAULT_MEDIA_SERVER

    private companion object {
        const val KEY_SELECTED = "media_upload_server"
        const val KEY_CUSTOM = "media_upload_servers_custom"
    }
}
