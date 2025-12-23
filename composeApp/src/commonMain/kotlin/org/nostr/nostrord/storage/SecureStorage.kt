package org.nostr.nostrord.storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

expect object SecureStorage {
    fun savePrivateKey(privateKeyHex: String)
    fun getPrivateKey(): String?
    fun hasPrivateKey(): Boolean
    fun clearPrivateKey()
    
    fun saveCurrentRelayUrl(relayUrl: String)
    fun getCurrentRelayUrl(): String?
    fun clearCurrentRelayUrl()
    
    // Account-scoped joined groups (namespaced by pubkey)
    fun saveJoinedGroupsForRelay(pubkey: String, relayUrl: String, groupIds: Set<String>)
    fun getJoinedGroupsForRelay(pubkey: String, relayUrl: String): Set<String>
    fun clearJoinedGroupsForRelay(pubkey: String, relayUrl: String)
    fun clearAllJoinedGroupsForAccount(pubkey: String)
    
    // NIP-46 Bunker support
    fun saveBunkerUrl(bunkerUrl: String)
    fun getBunkerUrl(): String?
    fun hasBunkerUrl(): Boolean
    fun clearBunkerUrl()
    
    // NIP-46 Bunker User Pubkey support
    fun saveBunkerUserPubkey(pubkey: String)
    fun getBunkerUserPubkey(): String?
    fun clearBunkerUserPubkey()
    
    // NIP-46 Bunker Client Private Key (for session persistence)
    fun saveBunkerClientPrivateKey(privateKey: String)
    fun getBunkerClientPrivateKey(): String?
    fun clearBunkerClientPrivateKey()
    
    fun clearAll()
}

// Legacy support functions (deprecated - use account-scoped versions)
@Deprecated("Use account-scoped saveJoinedGroupsForRelay with pubkey")
suspend fun SecureStorage.saveJoinedGroups(groups: Set<String>) {
    saveJoinedGroupsForRelay("legacy", "legacy", groups)
}

@Deprecated("Use account-scoped getJoinedGroupsForRelay with pubkey")
suspend fun SecureStorage.getJoinedGroups(): Set<String> {
    return getJoinedGroupsForRelay("legacy", "legacy")
}

@Deprecated("Use account-scoped clearJoinedGroupsForRelay with pubkey")
suspend fun SecureStorage.clearJoinedGroups() {
    clearJoinedGroupsForRelay("legacy", "legacy")
}
