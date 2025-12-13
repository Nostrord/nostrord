package org.nostr.nostrord.nostr

expect class Nip46Client(existingPrivateKey: String? = null) {
    var onAuthUrl: ((String) -> Unit)?
    val clientPubkey: String
    val clientPrivateKey: String
    
    suspend fun connect(
        remoteSignerPubkey: String,
        relays: List<String>,
        secret: String?
    ): String
    
    suspend fun getPublicKey(): String
    suspend fun signEvent(eventJson: String): String
    fun disconnect()
}
