package org.nostr.nostrord.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.nostr.Nip46Client
import org.nostr.nostrord.storage.SecureStorage

/**
 * Manages authentication state and signing operations.
 * Handles both local keypair and NIP-46 bunker authentication.
 */
object AuthManager {

    private var keyPair: KeyPair? = null
    private var nip46Client: Nip46Client? = null
    private var isBunkerLogin = false
    private var bunkerUserPubkey: String? = null

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _isBunkerConnected = MutableStateFlow(false)
    val isBunkerConnected: StateFlow<Boolean> = _isBunkerConnected.asStateFlow()

    private val _authUrl = MutableStateFlow<String?>(null)
    val authUrl: StateFlow<String?> = _authUrl.asStateFlow()

    fun clearAuthUrl() {
        _authUrl.value = null
    }

    /**
     * Get the current user's public key (hex)
     */
    fun getPublicKey(): String? {
        return when {
            isBunkerLogin -> bunkerUserPubkey
            keyPair != null -> keyPair?.publicKeyHex
            else -> null
        }
    }

    /**
     * Get the current user's private key (hex) - only for local login
     */
    fun getPrivateKey(): String? {
        return if (isBunkerLogin) null else keyPair?.privateKeyHex
    }

    fun isUsingBunker(): Boolean = isBunkerLogin

    fun isBunkerReady(): Boolean = isBunkerLogin && nip46Client != null

    /**
     * Login with NIP-46 bunker URL
     * Returns the user's public key on success
     */
    suspend fun loginWithBunker(bunkerUrl: String): String {
        val bunkerInfo = parseBunkerUrl(bunkerUrl)

        // Check if we have an existing client key (from previous session)
        val existingClientKey = SecureStorage.getBunkerClientPrivateKey()
        val newNip46Client = if (existingClientKey != null) {
            println("🔑 Reusing existing client keypair for bunker connection")
            Nip46Client(existingClientKey)
        } else {
            println("🔑 Generating new client keypair for bunker connection")
            Nip46Client(null)
        }

        // Set up auth URL callback
        newNip46Client.onAuthUrl = { url ->
            println("🔐 Auth URL received: $url")
            _authUrl.value = url
        }

        try {
            newNip46Client.connect(
                remoteSignerPubkey = bunkerInfo.pubkey,
                relays = bunkerInfo.relays,
                secret = bunkerInfo.secret
            )
        } catch (e: Exception) {
            // "already connected" means the signer remembers us - success!
            if (e.message?.contains("already connected", ignoreCase = true) == true) {
                println("✅ Signer reports already connected - reusing session")
            } else {
                throw e
            }
        }

        val userPubkey = newNip46Client.getPublicKey()

        nip46Client = newNip46Client
        bunkerUserPubkey = userPubkey
        isBunkerLogin = true
        keyPair = null

        // Save bunker credentials for session persistence
        SecureStorage.saveBunkerUrl(bunkerUrl)
        SecureStorage.saveBunkerUserPubkey(userPubkey)
        SecureStorage.saveBunkerClientPrivateKey(newNip46Client.clientPrivateKey)
        SecureStorage.clearPrivateKey()

        _isBunkerConnected.value = true
        _authUrl.value = null

        println("✅ Bunker login successful, user: ${userPubkey.take(16)}...")

        return userPubkey
    }

    /**
     * Login with local private key
     */
    fun loginWithPrivateKey(privateKeyHex: String, publicKeyHex: String) {
        keyPair = KeyPair.fromPrivateKeyHex(privateKeyHex)
        isBunkerLogin = false
        bunkerUserPubkey = null
        nip46Client = null

        SecureStorage.savePrivateKey(privateKeyHex)
        SecureStorage.clearBunkerUrl()
        SecureStorage.clearBunkerUserPubkey()
        SecureStorage.clearBunkerClientPrivateKey()

        println("✅ Private key login successful")
    }

    /**
     * Restore session from saved credentials
     * Returns true if session was restored
     */
    suspend fun restoreSession(): Boolean {
        // Try bunker first
        val savedBunkerUrl = SecureStorage.getBunkerUrl()
        val savedUserPubkey = SecureStorage.getBunkerUserPubkey()

        if (savedBunkerUrl != null && savedUserPubkey != null) {
            return restoreBunkerSession(savedBunkerUrl, savedUserPubkey)
        }

        // Try private key
        val savedPrivateKey = SecureStorage.getPrivateKey()
        if (savedPrivateKey != null) {
            return restorePrivateKeySession(savedPrivateKey)
        }

        return false
    }

    private suspend fun restoreBunkerSession(bunkerUrl: String, savedUserPubkey: String): Boolean {
        try {
            println("🔄 Restoring bunker session for ${savedUserPubkey.take(16)}...")
            val bunkerInfo = parseBunkerUrl(bunkerUrl)
            val savedClientPrivateKey = SecureStorage.getBunkerClientPrivateKey()

            val newNip46Client = if (savedClientPrivateKey != null) {
                println("   Using saved client keypair")
                Nip46Client(savedClientPrivateKey)
            } else {
                println("   No saved client key, generating new")
                Nip46Client()
            }

            bunkerUserPubkey = savedUserPubkey
            isBunkerLogin = true

            newNip46Client.onAuthUrl = { url ->
                _authUrl.value = url
            }

            try {
                newNip46Client.connect(
                    remoteSignerPubkey = bunkerInfo.pubkey,
                    relays = bunkerInfo.relays,
                    secret = bunkerInfo.secret
                )
            } catch (e: Exception) {
                if (e.message?.contains("already connected", ignoreCase = true) == true) {
                    println("✅ Signer reports already connected")
                } else {
                    throw e
                }
            }

            nip46Client = newNip46Client
            _isBunkerConnected.value = true

            if (savedClientPrivateKey == null) {
                SecureStorage.saveBunkerClientPrivateKey(newNip46Client.clientPrivateKey)
            }

            // Verify pubkey
            try {
                val actualPubkey = newNip46Client.getPublicKey()
                if (actualPubkey != savedUserPubkey) {
                    bunkerUserPubkey = actualPubkey
                    SecureStorage.saveBunkerUserPubkey(actualPubkey)
                }
            } catch (e: Exception) {
                println("⚠️ Could not verify pubkey, using saved")
            }

            _isLoggedIn.value = true
            println("✅ Bunker session restored")
            return true

        } catch (e: Exception) {
            println("❌ Failed to restore bunker session: ${e.message}")
            clearBunkerCredentials()
            return false
        }
    }

    private fun restorePrivateKeySession(privateKeyHex: String): Boolean {
        return try {
            keyPair = KeyPair.fromPrivateKeyHex(privateKeyHex)
            isBunkerLogin = false
            _isLoggedIn.value = true
            println("✅ Private key session restored")
            true
        } catch (e: Exception) {
            println("❌ Failed to restore private key session: ${e.message}")
            SecureStorage.clearPrivateKey()
            false
        }
    }

    /**
     * Reconnect bunker if disconnected
     */
    suspend fun ensureBunkerConnected(): Boolean {
        if (!isBunkerLogin) return true
        if (nip46Client != null && _isBunkerConnected.value) return true

        return reconnectBunker()
    }

    private suspend fun reconnectBunker(): Boolean {
        val savedBunkerUrl = SecureStorage.getBunkerUrl() ?: return false
        val savedClientPrivateKey = SecureStorage.getBunkerClientPrivateKey()

        try {
            println("🔄 Attempting to reconnect bunker...")
            val bunkerInfo = parseBunkerUrl(savedBunkerUrl)

            val newNip46Client = if (savedClientPrivateKey != null) {
                Nip46Client(savedClientPrivateKey)
            } else {
                Nip46Client()
            }

            newNip46Client.onAuthUrl = { url ->
                _authUrl.value = url
            }

            try {
                newNip46Client.connect(
                    remoteSignerPubkey = bunkerInfo.pubkey,
                    relays = bunkerInfo.relays,
                    secret = bunkerInfo.secret
                )
            } catch (e: Exception) {
                if (e.message?.contains("already connected", ignoreCase = true) == true) {
                    println("✅ Signer reports already connected")
                } else {
                    throw e
                }
            }

            nip46Client = newNip46Client
            _isBunkerConnected.value = true

            if (savedClientPrivateKey == null) {
                SecureStorage.saveBunkerClientPrivateKey(newNip46Client.clientPrivateKey)
            }

            println("✅ Bunker reconnected successfully")
            return true
        } catch (e: Exception) {
            println("❌ Bunker reconnection failed: ${e.message}")
            return false
        }
    }

    /**
     * Sign an event using bunker or local keypair
     */
    suspend fun signEvent(event: Event): Event {
        return if (isBunkerLogin) {
            signWithBunker(event)
        } else {
            signWithKeyPair(event)
        }
    }

    private suspend fun signWithBunker(event: Event): Event {
        if (nip46Client == null) {
            val reconnected = reconnectBunker()
            if (!reconnected) {
                throw Exception("Bunker not connected and reconnection failed")
            }
        }

        val bunker = nip46Client ?: throw Exception("Bunker not connected")

        try {
            val eventJson = event.toJsonString()
            val signedEventJson = bunker.signEvent(eventJson)
            return parseSignedEvent(signedEventJson)
        } catch (e: Exception) {
            if (isPermissionError(e)) {
                handlePermissionDenied()
                throw Exception("Signing permission denied. Please login again.")
            }
            throw e
        }
    }

    private fun signWithKeyPair(event: Event): Event {
        val kp = keyPair ?: throw Exception("Not logged in")
        return event.sign(kp)
    }

    private fun isPermissionError(e: Exception): Boolean {
        val msg = e.message?.lowercase() ?: return false
        return msg.contains("no permission") ||
               msg.contains("not authorized") ||
               msg.contains("permission denied")
    }

    private fun handlePermissionDenied() {
        println("🔐 Permission denied - clearing session")
        nip46Client?.disconnect()
        nip46Client = null
        _isBunkerConnected.value = false
        clearBunkerCredentials()
        isBunkerLogin = false
        bunkerUserPubkey = null
        _isLoggedIn.value = false
    }

    private fun parseSignedEvent(jsonString: String): Event {
        val json = Json { ignoreUnknownKeys = true }
        val obj = json.parseToJsonElement(jsonString).jsonObject

        return Event(
            id = obj["id"]?.jsonPrimitive?.content,
            pubkey = obj["pubkey"]?.jsonPrimitive?.content ?: "",
            createdAt = obj["created_at"]?.jsonPrimitive?.long ?: 0L,
            kind = obj["kind"]?.jsonPrimitive?.int ?: 0,
            tags = obj["tags"]?.jsonArray?.map { tag ->
                tag.jsonArray.map { it.jsonPrimitive.content }
            } ?: emptyList(),
            content = obj["content"]?.jsonPrimitive?.content ?: "",
            sig = obj["sig"]?.jsonPrimitive?.content
        )
    }

    /**
     * Logout - clear all auth state
     */
    fun logout() {
        nip46Client?.disconnect()
        nip46Client = null
        isBunkerLogin = false
        bunkerUserPubkey = null
        keyPair = null

        _isLoggedIn.value = false
        _isBunkerConnected.value = false

        SecureStorage.clearPrivateKey()
        SecureStorage.clearBunkerUrl()
        SecureStorage.clearBunkerUserPubkey()
        // Keep client key for re-login
    }

    /**
     * Completely forget bunker connection
     */
    fun forgetBunkerConnection() {
        nip46Client?.disconnect()
        nip46Client = null
        isBunkerLogin = false
        bunkerUserPubkey = null
        _isBunkerConnected.value = false
        clearBunkerCredentials()
        SecureStorage.clearBunkerClientPrivateKey()
        println("🗑️ Bunker connection completely forgotten")
    }

    private fun clearBunkerCredentials() {
        SecureStorage.clearBunkerUrl()
        SecureStorage.clearBunkerUserPubkey()
    }

    /**
     * Set logged in state (called by repository after connections established)
     */
    fun setLoggedIn(value: Boolean) {
        _isLoggedIn.value = value
    }
}
