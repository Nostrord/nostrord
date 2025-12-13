package org.nostr.nostrord.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

actual object SecureStorage {
    private const val PREFS_NAME = "nostr_secure_prefs"
    private const val PRIVATE_KEY_PREF = "nostr_private_key"
    private const val JOINED_GROUPS_PREFIX = "joined_groups_"
    private const val CURRENT_RELAY_URL = "current_relay_url"
    private const val BUNKER_URL_PREF = "nostr_bunker_url"
    private const val BUNKER_USER_PUBKEY_PREF = "nostr_bunker_user_pubkey"
    private const val BUNKER_CLIENT_PRIVATE_KEY_PREF = "nostr_bunker_client_private_key"
    
    private lateinit var prefs: SharedPreferences
    
    fun initialize(context: Context) {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        
        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        println("🔐 Secure storage initialized")
    }
    
    private fun ensureInitialized() {
        if (!::prefs.isInitialized) {
            throw IllegalStateException("SecureStorage not initialized. Call initialize(context) first.")
        }
    }
    
    actual fun savePrivateKey(privateKeyHex: String) {
        ensureInitialized()
        prefs.edit().putString(PRIVATE_KEY_PREF, privateKeyHex).apply()
        println("🔐 Private key saved securely")
    }
    
    actual fun getPrivateKey(): String? {
        ensureInitialized()
        return prefs.getString(PRIVATE_KEY_PREF, null)
    }
    
    actual fun hasPrivateKey(): Boolean {
        ensureInitialized()
        return prefs.contains(PRIVATE_KEY_PREF)
    }
    
    actual fun clearPrivateKey() {
        ensureInitialized()
        prefs.edit().remove(PRIVATE_KEY_PREF).apply()
        println("🗑️ Private key cleared")
    }
    
    actual fun saveCurrentRelayUrl(relayUrl: String) {
        ensureInitialized()
        prefs.edit().putString(CURRENT_RELAY_URL, relayUrl).apply()
        println("💾 Saved current relay URL: $relayUrl")
    }
    
    actual fun getCurrentRelayUrl(): String? {
        ensureInitialized()
        return prefs.getString(CURRENT_RELAY_URL, null)
    }
    
    actual fun clearCurrentRelayUrl() {
        ensureInitialized()
        prefs.edit().remove(CURRENT_RELAY_URL).apply()
        println("🗑️ Cleared current relay URL")
    }
    
    actual fun saveJoinedGroupsForRelay(relayUrl: String, groupIds: Set<String>) {
        ensureInitialized()
        val key = JOINED_GROUPS_PREFIX + relayUrl.hashCode()
        val json = Json.encodeToString(groupIds.toList())
        prefs.edit().putString(key, json).apply()
        println("💾 Saved ${groupIds.size} joined groups for relay: $relayUrl")
    }
    
    actual fun getJoinedGroupsForRelay(relayUrl: String): Set<String> {
        ensureInitialized()
        val key = JOINED_GROUPS_PREFIX + relayUrl.hashCode()
        val json = prefs.getString(key, null) ?: return emptySet()
        return try {
            Json.decodeFromString<List<String>>(json).toSet()
        } catch (e: Exception) {
            println("❌ Failed to parse joined groups for relay: ${e.message}")
            emptySet()
        }
    }
    
    actual fun clearJoinedGroupsForRelay(relayUrl: String) {
        ensureInitialized()
        val key = JOINED_GROUPS_PREFIX + relayUrl.hashCode()
        prefs.edit().remove(key).apply()
        println("🗑️ Cleared joined groups for relay: $relayUrl")
    }
    
    actual fun clearAllJoinedGroups() {
        ensureInitialized()
        // Note: We can't iterate over all keys with EncryptedSharedPreferences
        // as getAll() may fail if any key is corrupted.
        // Instead, we clear known relay-specific keys by using clearAll and re-saving other data,
        // or we just clear the entire storage and let the user re-login.
        // For now, we'll just try-catch and if it fails, we clear everything.
        try {
            val editor = prefs.edit()
            prefs.all.keys.filter { it.startsWith(JOINED_GROUPS_PREFIX) }.forEach { key ->
                editor.remove(key)
            }
            editor.apply()
            println("🗑️ Cleared all relay-specific joined groups")
        } catch (e: Exception) {
            println("⚠️ Failed to clear joined groups normally, clearing all storage: ${e.message}")
            try {
                prefs.edit().clear().apply()
                println("🗑️ Cleared all storage due to decryption error")
            } catch (e2: Exception) {
                println("❌ Failed to clear storage: ${e2.message}")
            }
        }
    }
    
    // NIP-46 Bunker URL support
    actual fun saveBunkerUrl(bunkerUrl: String) {
        ensureInitialized()
        prefs.edit().putString(BUNKER_URL_PREF, bunkerUrl).apply()
        println("🔐 Bunker URL saved securely")
    }
    
    actual fun getBunkerUrl(): String? {
        ensureInitialized()
        return prefs.getString(BUNKER_URL_PREF, null)
    }
    
    actual fun hasBunkerUrl(): Boolean {
        ensureInitialized()
        return prefs.contains(BUNKER_URL_PREF)
    }
    
    actual fun clearBunkerUrl() {
        ensureInitialized()
        prefs.edit().remove(BUNKER_URL_PREF).apply()
        println("🗑️ Bunker URL cleared")
    }
    
    // NIP-46 Bunker User Pubkey support
    actual fun saveBunkerUserPubkey(pubkey: String) {
        ensureInitialized()
        prefs.edit().putString(BUNKER_USER_PUBKEY_PREF, pubkey).apply()
        println("🔐 Bunker user pubkey saved securely")
    }
    
    actual fun getBunkerUserPubkey(): String? {
        ensureInitialized()
        return prefs.getString(BUNKER_USER_PUBKEY_PREF, null)
    }
    
    actual fun clearBunkerUserPubkey() {
        ensureInitialized()
        prefs.edit().remove(BUNKER_USER_PUBKEY_PREF).apply()
        println("🗑️ Bunker user pubkey cleared")
    }
    
    // NIP-46 Bunker Client Private Key (for session persistence)
    actual fun saveBunkerClientPrivateKey(privateKey: String) {
        ensureInitialized()
        prefs.edit().putString(BUNKER_CLIENT_PRIVATE_KEY_PREF, privateKey).apply()
        println("🔐 Bunker client private key saved securely")
    }
    
    actual fun getBunkerClientPrivateKey(): String? {
        ensureInitialized()
        return prefs.getString(BUNKER_CLIENT_PRIVATE_KEY_PREF, null)
    }
    
    actual fun clearBunkerClientPrivateKey() {
        ensureInitialized()
        prefs.edit().remove(BUNKER_CLIENT_PRIVATE_KEY_PREF).apply()
        println("🗑️ Bunker client private key cleared")
    }
    
    actual fun clearAll() {
        ensureInitialized()
        prefs.edit().clear().apply()
        println("🗑️ All secure storage cleared")
    }
}
