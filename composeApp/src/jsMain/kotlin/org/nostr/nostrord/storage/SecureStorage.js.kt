package org.nostr.nostrord.storage

import kotlinx.browser.localStorage

actual object SecureStorage {
    private const val PRIVATE_KEY_PREF = "nostr_private_key"
    private const val JOINED_GROUPS_PREFIX = "joined_groups_"
    private const val CURRENT_RELAY_URL = "current_relay_url"
    private const val BUNKER_URL_PREF = "nostr_bunker_url"
    private const val BUNKER_USER_PUBKEY_PREF = "nostr_bunker_user_pubkey"
    private const val BUNKER_CLIENT_PRIVATE_KEY_PREF = "nostr_bunker_client_private_key"
    
    actual fun savePrivateKey(privateKeyHex: String) {
        localStorage.setItem(PRIVATE_KEY_PREF, privateKeyHex)
        println("🔐 Private key saved")
    }
    
    actual fun getPrivateKey(): String? {
        return localStorage.getItem(PRIVATE_KEY_PREF)
    }
    
    actual fun hasPrivateKey(): Boolean {
        return localStorage.getItem(PRIVATE_KEY_PREF) != null
    }
    
    actual fun clearPrivateKey() {
        localStorage.removeItem(PRIVATE_KEY_PREF)
        println("🗑️ Private key cleared")
    }
    
    actual fun saveCurrentRelayUrl(relayUrl: String) {
        localStorage.setItem(CURRENT_RELAY_URL, relayUrl)
        println("💾 Saved current relay URL: $relayUrl")
    }
    
    actual fun getCurrentRelayUrl(): String? {
        return localStorage.getItem(CURRENT_RELAY_URL)
    }
    
    actual fun clearCurrentRelayUrl() {
        localStorage.removeItem(CURRENT_RELAY_URL)
        println("🗑️ Cleared current relay URL")
    }
    
    actual fun saveJoinedGroupsForRelay(pubkey: String, relayUrl: String, groupIds: Set<String>) {
        val key = JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_" + relayUrl.hashCode()
        val json = groupIds.joinToString(",")
        localStorage.setItem(key, json)
        println("💾 Saved ${groupIds.size} joined groups for ${pubkey.take(8)} on relay: $relayUrl")
    }

    actual fun getJoinedGroupsForRelay(pubkey: String, relayUrl: String): Set<String> {
        val key = JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_" + relayUrl.hashCode()
        val json = localStorage.getItem(key) ?: return emptySet()
        return if (json.isBlank()) emptySet() else json.split(",").toSet()
    }

    actual fun clearJoinedGroupsForRelay(pubkey: String, relayUrl: String) {
        val key = JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_" + relayUrl.hashCode()
        localStorage.removeItem(key)
        println("🗑️ Cleared joined groups for ${pubkey.take(8)} on relay: $relayUrl")
    }

    actual fun clearAllJoinedGroupsForAccount(pubkey: String) {
        val accountPrefix = JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_"
        val keysToRemove = mutableListOf<String>()
        for (i in 0 until localStorage.length) {
            val key = localStorage.key(i)
            if (key != null && key.startsWith(accountPrefix)) {
                keysToRemove.add(key)
            }
        }
        keysToRemove.forEach { localStorage.removeItem(it) }
        println("🗑️ Cleared all joined groups for account ${pubkey.take(8)}")
    }
    
    // NIP-46 Bunker URL support
    actual fun saveBunkerUrl(bunkerUrl: String) {
        localStorage.setItem(BUNKER_URL_PREF, bunkerUrl)
        println("🔐 Bunker URL saved")
    }
    
    actual fun getBunkerUrl(): String? {
        return localStorage.getItem(BUNKER_URL_PREF)
    }
    
    actual fun hasBunkerUrl(): Boolean {
        return localStorage.getItem(BUNKER_URL_PREF) != null
    }
    
    actual fun clearBunkerUrl() {
        localStorage.removeItem(BUNKER_URL_PREF)
        println("🗑️ Bunker URL cleared")
    }
    
    // NIP-46 Bunker User Pubkey support
    actual fun saveBunkerUserPubkey(pubkey: String) {
        localStorage.setItem(BUNKER_USER_PUBKEY_PREF, pubkey)
        println("🔐 Bunker user pubkey saved")
    }
    
    actual fun getBunkerUserPubkey(): String? {
        return localStorage.getItem(BUNKER_USER_PUBKEY_PREF)
    }
    
    actual fun clearBunkerUserPubkey() {
        localStorage.removeItem(BUNKER_USER_PUBKEY_PREF)
        println("🗑️ Bunker user pubkey cleared")
    }
    
    // NIP-46 Bunker Client Private Key (for session persistence)
    actual fun saveBunkerClientPrivateKey(privateKey: String) {
        localStorage.setItem(BUNKER_CLIENT_PRIVATE_KEY_PREF, privateKey)
        println("🔐 Bunker client private key saved")
    }
    
    actual fun getBunkerClientPrivateKey(): String? {
        return localStorage.getItem(BUNKER_CLIENT_PRIVATE_KEY_PREF)
    }
    
    actual fun clearBunkerClientPrivateKey() {
        localStorage.removeItem(BUNKER_CLIENT_PRIVATE_KEY_PREF)
        println("🗑️ Bunker client private key cleared")
    }
    
    actual fun clearAll() {
        localStorage.clear()
        println("🗑️ All storage cleared")
    }
}
