package org.nostr.nostrord.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import org.nostr.nostrord.network.upload.DEFAULT_BLOSSOM_SERVERS
import org.nostr.nostrord.network.upload.DEFAULT_NIP96_SERVICE
import org.nostr.nostrord.network.upload.MediaUploadService
import org.nostr.nostrord.network.upload.NIP96_SERVICES
import org.nostr.nostrord.network.upload.RECOMMENDED_BLOSSOM_SERVERS
import org.nostr.nostrord.network.upload.normalizeMediaServerUrl
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result

/**
 * Where uploads go — set from Settings → Media.
 *
 * [service] is either Blossom or one NIP-96 host. Under Blossom, [blossomServers] is an
 * ordered list: the first server that accepts the file owns the URL and the others receive
 * a mirror, so the order is a preference, not just presentation.
 */
class MediaServerSettings {
    private val json = Json { ignoreUnknownKeys = true }

    private val _service = MutableStateFlow(loadService())
    val service: StateFlow<MediaUploadService> = _service.asStateFlow()

    private val _blossomServers = MutableStateFlow(loadBlossomServers())
    val blossomServers: StateFlow<List<String>> = _blossomServers.asStateFlow()

    /** NIP-96 hosts offered in the picker. */
    val nip96Services: List<String> = NIP96_SERVICES

    /** Recommended servers the user hasn't added yet, offered as one-tap additions. */
    fun recommendedNotAdded(): List<String> = RECOMMENDED_BLOSSOM_SERVERS - _blossomServers.value.toSet()

    fun useBlossom() {
        _service.value = MediaUploadService.Blossom
        SecureStorage.saveStringPref(KEY_SERVICE, BLOSSOM_MARKER)
    }

    fun useNip96(url: String) {
        val normalized = normalizeMediaServerUrl(url) ?: return
        _service.value = MediaUploadService.Nip96(normalized)
        SecureStorage.saveStringPref(KEY_SERVICE, normalized)
    }

    /**
     * Add a Blossom server. The address is normalized to an https origin, so
     * "blossom.example.com/" and "https://Blossom.Example.com" are the same entry.
     */
    fun addBlossomServer(input: String): Result<String> {
        val url =
            normalizeMediaServerUrl(input)
                ?: return Result.Error(AppError.Unknown("Enter a valid server address, for example blossom.example.com"))
        if (_blossomServers.value.contains(url)) {
            return Result.Error(AppError.Unknown("That server is already in the list."))
        }
        setBlossomServers(_blossomServers.value + url)
        return Result.Success(url)
    }

    fun removeBlossomServer(url: String) {
        setBlossomServers(_blossomServers.value - url)
    }

    /** Move a server one place up (toward preferred) or down in the upload order. */
    fun moveBlossomServer(
        url: String,
        up: Boolean,
    ) {
        val current = _blossomServers.value
        val index = current.indexOf(url).takeIf { it >= 0 } ?: return
        val target = if (up) index - 1 else index + 1
        if (target !in current.indices) return
        val reordered = current.toMutableList()
        reordered[index] = reordered[target]
        reordered[target] = url
        setBlossomServers(reordered)
    }

    private fun setBlossomServers(servers: List<String>) {
        _blossomServers.value = servers
        SecureStorage.saveStringPref(KEY_BLOSSOM_SERVERS, json.encodeToString(servers))
    }

    private fun loadService(): MediaUploadService {
        val stored = SecureStorage.getStringPref(KEY_SERVICE, "")
        return when {
            stored == BLOSSOM_MARKER -> MediaUploadService.Blossom
            stored.isNotBlank() -> MediaUploadService.Nip96(stored)
            else -> MediaUploadService.Nip96(DEFAULT_NIP96_SERVICE)
        }
    }

    private fun loadBlossomServers(): List<String> {
        val raw = SecureStorage.getStringPref(KEY_BLOSSOM_SERVERS, "")
        if (raw.isBlank()) return DEFAULT_BLOSSOM_SERVERS
        // An empty stored list is a real choice (the user removed every server), so it is
        // kept as-is; only a missing/corrupt entry falls back to the defaults.
        return runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(DEFAULT_BLOSSOM_SERVERS)
    }

    private companion object {
        const val KEY_SERVICE = "media_upload_service"
        const val KEY_BLOSSOM_SERVERS = "media_upload_blossom_servers"

        /** Stored in the same slot as a NIP-96 URL; not a valid URL, so the two can't collide. */
        const val BLOSSOM_MARKER = "blossom"
    }
}
