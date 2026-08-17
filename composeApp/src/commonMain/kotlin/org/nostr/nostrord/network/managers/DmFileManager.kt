package org.nostr.nostrord.network.managers

import io.ktor.client.request.get
import io.ktor.client.request.url
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.nostr.nostrord.network.createHttpClient
import org.nostr.nostrord.nostr.Crypto
import org.nostr.nostrord.nostr.Nip17File
import org.nostr.nostrord.nostr.hexToByteArray
import org.nostr.nostrord.nostr.toHexString
import org.nostr.nostrord.utils.AesGcm

/**
 * Reads the attachments behind NIP-17 kind:15 messages: download the blob, decrypt it with the
 * key from the rumor, hand the plaintext bytes to the UI (Compose feeds them to Coil, the web
 * wraps them in an object url).
 *
 * State is per rumor id and in-memory only: the plaintext of a private file has no business
 * being written to disk, and a re-open re-fetches. [MAX_CACHED_BYTES] bounds what a long
 * conversation can pin, evicting whole files oldest-first.
 */
class DmFileManager(
    private val scope: CoroutineScope,
) {
    /** What the UI knows about one attachment. */
    sealed class FileState {
        data object Loading : FileState()

        /** Decrypted bytes plus the mime type to render them as. */
        data class Ready(val bytes: ByteArray, val mimeType: String?) : FileState() {
            override fun equals(other: Any?): Boolean = this === other

            override fun hashCode(): Int = bytes.size * 31 + mimeType.hashCode()
        }

        data class Failed(val reason: String) : FileState()
    }

    private val _states = MutableStateFlow<Map<String, FileState>>(emptyMap())
    val states: StateFlow<Map<String, FileState>> = _states.asStateFlow()

    // Insertion order of ready files, for byte-bounded eviction. Every mutation of the
    // bookkeeping goes through [lock]: loads are started from the UI and finish on the manager
    // scope, so these are touched from more than one thread.
    private val loaded = mutableListOf<String>()
    private var loadedBytes = 0L
    private val inFlight = MutableStateFlow<Map<String, Job>>(emptyMap())
    private val lock = Mutex()
    private val http = createHttpClient()

    /**
     * Fetch and decrypt [file] for the message [rumorId], unless it is already loaded or in
     * flight. Idempotent, so a composable can call it on every recomposition.
     */
    fun load(rumorId: String, file: Nip17File) {
        if (_states.value[rumorId] is FileState.Ready || rumorId in inFlight.value) return
        if (!file.isDecryptable) {
            _states.update { it + (rumorId to FileState.Failed("Unsupported encryption")) }
            return
        }
        _states.update { it + (rumorId to FileState.Loading) }
        val job =
            scope.launch {
                val state =
                    try {
                        fetchAndDecrypt(file)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        FileState.Failed("Could not load this file")
                    }
                inFlight.update { it - rumorId }
                _states.update { it + (rumorId to state) }
                if (state is FileState.Ready) remember(rumorId, state.bytes.size)
            }
        inFlight.update { it + (rumorId to job) }
    }

    /** Drop a failed load so the UI's retry starts a fresh attempt. */
    fun retry(rumorId: String, file: Nip17File) {
        _states.update { it - rumorId }
        load(rumorId, file)
    }

    private suspend fun fetchAndDecrypt(file: Nip17File): FileState {
        val encrypted =
            withTimeout(DOWNLOAD_TIMEOUT_MS) {
                http.get { url(file.url) }.readRawBytes()
            }
        if (encrypted.isEmpty()) return FileState.Failed("The file is no longer on the server")
        val key = file.decryptionKeyHex?.decodeHexOrNull() ?: return FileState.Failed("Missing decryption key")
        val nonce = file.decryptionNonceHex?.decodeHexOrNull() ?: return FileState.Failed("Missing decryption nonce")
        val plaintext =
            AesGcm.decryptUnauthenticated(key, nonce, encrypted)
                ?: return FileState.Failed("Could not decrypt this file")
        // AesGcm skips the GCM tag, so the sender's `ox` is what authenticates the bytes: it
        // travels inside the sealed rumor, which makes it the stronger of the two anyway. A
        // sender that published no hash leaves the file unverified rather than unreadable.
        val expected = file.originalHashHex
        if (expected != null && Crypto.sha256(plaintext).toHexString() != expected.lowercase()) {
            return FileState.Failed("This file was altered on the server")
        }
        return FileState.Ready(plaintext, file.mimeType)
    }

    /** Record a newly loaded file and evict oldest-first until the cache fits again. */
    private suspend fun remember(rumorId: String, size: Int) = lock.withLock {
        loaded.remove(rumorId)
        loaded += rumorId
        loadedBytes += size
        while (loadedBytes > MAX_CACHED_BYTES && loaded.size > 1) {
            val evicted = loaded.removeAt(0)
            val bytes = (_states.value[evicted] as? FileState.Ready)?.bytes?.size ?: 0
            loadedBytes -= bytes
            _states.update { it - evicted }
        }
    }

    /** Drop everything on logout / account switch: another account must not see these bytes. */
    fun clear() {
        inFlight.value.values.forEach { it.cancel() }
        inFlight.value = emptyMap()
        scope.launch {
            lock.withLock {
                loaded.clear()
                loadedBytes = 0
            }
        }
        _states.value = emptyMap()
    }

    private fun String.decodeHexOrNull(): ByteArray? = runCatching { hexToByteArray() }.getOrNull()

    private companion object {
        const val DOWNLOAD_TIMEOUT_MS = 60_000L

        /** Plaintext held in memory across all open conversations. */
        const val MAX_CACHED_BYTES = 48L * 1024 * 1024
    }
}
