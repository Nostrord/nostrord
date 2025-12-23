package org.nostr.nostrord.storage

import java.util.prefs.Preferences
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

actual object SecureStorage {
    private val prefs = Preferences.userNodeForPackage(SecureStorage::class.java)
    private const val PRIVATE_KEY_PREF = "nostr_private_key"
    private const val ENCRYPTION_KEY_PREF = "encryption_key"
    private const val JOINED_GROUPS_PREFIX = "joined_groups_"
    private const val CURRENT_RELAY_URL = "current_relay_url"
    private const val BUNKER_URL_PREF = "nostr_bunker_url"
    private const val BUNKER_USER_PUBKEY_PREF = "nostr_bunker_user_pubkey"
    private const val BUNKER_CLIENT_PRIVATE_KEY_PREF = "nostr_bunker_client_private_key"
    
    init {
        if (prefs.get(ENCRYPTION_KEY_PREF, null) == null) {
            val key = generateEncryptionKey()
            prefs.put(ENCRYPTION_KEY_PREF, Base64.getEncoder().encodeToString(key.encoded))
        }
    }
    
    private fun generateEncryptionKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256, SecureRandom())
        return keyGen.generateKey()
    }
    
    private fun getEncryptionKey(): SecretKey {
        val keyString = prefs.get(ENCRYPTION_KEY_PREF, null)
            ?: throw IllegalStateException("Encryption key not found")
        val keyBytes = Base64.getDecoder().decode(keyString)
        return SecretKeySpec(keyBytes, "AES")
    }
    
    private fun encrypt(data: String): String {
        val cipher = Cipher.getInstance("AES")
        cipher.init(Cipher.ENCRYPT_MODE, getEncryptionKey())
        val encrypted = cipher.doFinal(data.toByteArray())
        return Base64.getEncoder().encodeToString(encrypted)
    }
    
    private fun decrypt(encryptedData: String): String {
        val cipher = Cipher.getInstance("AES")
        cipher.init(Cipher.DECRYPT_MODE, getEncryptionKey())
        val decrypted = cipher.doFinal(Base64.getDecoder().decode(encryptedData))
        return String(decrypted)
    }
    
    actual fun savePrivateKey(privateKeyHex: String) {
        val encrypted = encrypt(privateKeyHex)
        prefs.put(PRIVATE_KEY_PREF, encrypted)
        prefs.flush()
        println("🔐 Private key saved securely")
    }
    
    actual fun getPrivateKey(): String? {
        val encrypted = prefs.get(PRIVATE_KEY_PREF, null) ?: return null
        return try {
            decrypt(encrypted)
        } catch (e: Exception) {
            println("❌ Failed to decrypt private key: ${e.message}")
            null
        }
    }
    
    actual fun hasPrivateKey(): Boolean {
        return prefs.get(PRIVATE_KEY_PREF, null) != null
    }
    
    actual fun clearPrivateKey() {
        prefs.remove(PRIVATE_KEY_PREF)
        prefs.flush()
        println("🗑️ Private key cleared")
    }
    
    actual fun saveCurrentRelayUrl(relayUrl: String) {
        saveString(CURRENT_RELAY_URL, relayUrl)
        println("💾 Saved current relay URL: $relayUrl")
    }
    
    actual fun getCurrentRelayUrl(): String? {
        return getString(CURRENT_RELAY_URL)
    }
    
    actual fun clearCurrentRelayUrl() {
        remove(CURRENT_RELAY_URL)
        println("🗑️ Cleared current relay URL")
    }
    
    actual fun saveJoinedGroupsForRelay(pubkey: String, relayUrl: String, groupIds: Set<String>) {
        // Account-scoped key: prefix + pubkey hash + relay hash
        val key = JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_" + relayUrl.hashCode()
        val json = Json.encodeToString(groupIds.toList())
        saveString(key, json)
        println("💾 Saved ${groupIds.size} joined groups for ${pubkey.take(8)} on relay: $relayUrl")
    }

    actual fun getJoinedGroupsForRelay(pubkey: String, relayUrl: String): Set<String> {
        val key = JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_" + relayUrl.hashCode()
        val json = getString(key) ?: return emptySet()
        return try {
            Json.decodeFromString<List<String>>(json).toSet()
        } catch (e: Exception) {
            println("❌ Failed to parse joined groups for relay: ${e.message}")
            emptySet()
        }
    }

    actual fun clearJoinedGroupsForRelay(pubkey: String, relayUrl: String) {
        val key = JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_" + relayUrl.hashCode()
        remove(key)
        println("🗑️ Cleared joined groups for ${pubkey.take(8)} on relay: $relayUrl")
    }

    actual fun clearAllJoinedGroupsForAccount(pubkey: String) {
        try {
            val accountPrefix = JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_"
            prefs.keys().forEach { key ->
                if (key.startsWith(accountPrefix)) {
                    prefs.remove(key)
                }
            }
            prefs.flush()
            println("🗑️ Cleared all joined groups for account ${pubkey.take(8)}")
        } catch (e: Exception) {
            println("❌ Failed to clear joined groups for account: ${e.message}")
        }
    }
    
    // NIP-46 Bunker URL support
    actual fun saveBunkerUrl(bunkerUrl: String) {
        val encrypted = encrypt(bunkerUrl)
        prefs.put(BUNKER_URL_PREF, encrypted)
        prefs.flush()
        println("🔐 Bunker URL saved securely")
    }
    
    actual fun getBunkerUrl(): String? {
        val encrypted = prefs.get(BUNKER_URL_PREF, null) ?: return null
        return try {
            decrypt(encrypted)
        } catch (e: Exception) {
            println("❌ Failed to decrypt bunker URL: ${e.message}")
            null
        }
    }
    
    actual fun hasBunkerUrl(): Boolean {
        return prefs.get(BUNKER_URL_PREF, null) != null
    }
    
    actual fun clearBunkerUrl() {
        prefs.remove(BUNKER_URL_PREF)
        prefs.flush()
        println("🗑️ Bunker URL cleared")
    }
    
    // NIP-46 Bunker User Pubkey support
    actual fun saveBunkerUserPubkey(pubkey: String) {
        val encrypted = encrypt(pubkey)
        prefs.put(BUNKER_USER_PUBKEY_PREF, encrypted)
        prefs.flush()
        println("🔐 Bunker user pubkey saved securely")
    }
    
    actual fun getBunkerUserPubkey(): String? {
        val encrypted = prefs.get(BUNKER_USER_PUBKEY_PREF, null) ?: return null
        return try {
            decrypt(encrypted)
        } catch (e: Exception) {
            println("❌ Failed to decrypt bunker user pubkey: ${e.message}")
            null
        }
    }
    
    actual fun clearBunkerUserPubkey() {
        prefs.remove(BUNKER_USER_PUBKEY_PREF)
        prefs.flush()
        println("🗑️ Bunker user pubkey cleared")
    }
    
    // NIP-46 Bunker Client Private Key (for session persistence)
    actual fun saveBunkerClientPrivateKey(privateKey: String) {
        val encrypted = encrypt(privateKey)
        prefs.put(BUNKER_CLIENT_PRIVATE_KEY_PREF, encrypted)
        prefs.flush()
        println("🔐 Bunker client private key saved securely")
    }
    
    actual fun getBunkerClientPrivateKey(): String? {
        val encrypted = prefs.get(BUNKER_CLIENT_PRIVATE_KEY_PREF, null) ?: return null
        return try {
            decrypt(encrypted)
        } catch (e: Exception) {
            println("❌ Failed to decrypt bunker client private key: ${e.message}")
            null
        }
    }
    
    actual fun clearBunkerClientPrivateKey() {
        prefs.remove(BUNKER_CLIENT_PRIVATE_KEY_PREF)
        prefs.flush()
        println("🗑️ Bunker client private key cleared")
    }
    
    actual fun clearAll() {
        prefs.clear()
        prefs.flush()
        println("🗑️ All secure storage cleared")
    }
    
    private fun saveString(key: String, value: String) {
        val encrypted = encrypt(value)
        prefs.put(key, encrypted)
        prefs.flush()
    }
    
    private fun getString(key: String): String? {
        val encrypted = prefs.get(key, null) ?: return null
        return try {
            decrypt(encrypted)
        } catch (e: Exception) {
            null
        }
    }
    
    private fun remove(key: String) {
        prefs.remove(key)
        prefs.flush()
    }
}
