package org.nostr.nostrord.nostr

// Permissions requested up front on a NIP-46 connect (nostrconnect `perms=` and the connect
// request's 3rd param). A signer like Amber shows ONE "remember/always allow" prompt for these, so
// later nip44_decrypt RPCs (a whole DM backlog) are answered automatically instead of each one
// firing an approval notification and otherwise timing out unanswered. Comma-separated per NIP-46.
const val NIP46_REQUESTED_PERMS =
    "sign_event,nip04_encrypt,nip04_decrypt,nip44_encrypt,nip44_decrypt"

expect class Nip46Client(
    existingPrivateKey: String? = null,
) {
    var onAuthUrl: ((String) -> Unit)?
    val clientPubkey: String
    val clientPrivateKey: String

    suspend fun connect(
        remoteSignerPubkey: String,
        relays: List<String>,
        secret: String?,
    ): String

    /**
     * Connect to relay WebSockets only, without sending the NIP-46 "connect" RPC.
     * Used for session restore when the signer already knows this client key.
     */
    suspend fun connectRelaysOnly(
        remoteSignerPubkey: String,
        relays: List<String>,
    )

    /**
     * Send NIP-46 "connect" request in background without blocking.
     * Used after session restore so the signer can re-authorize this client.
     * [onSuccess] is called when the signer confirms the connection.
     * [onRevoked] is called if the signer rejects or does not respond (timeout = revoked).
     */
    fun backgroundConnect(
        secret: String?,
        onSuccess: (() -> Unit)? = null,
        onRevoked: (() -> Unit)? = null,
    )

    /**
     * Generate a nostrconnect:// URI for display as QR code.
     * The remote signer scans this to initiate the connection.
     */
    fun generateNostrConnectUri(
        relays: List<String>,
        name: String = "Nostrord",
    ): String

    /**
     * Connect to relays and subscribe for incoming NIP-46 events.
     * Must be called BEFORE showing the QR code so events aren't missed.
     */
    suspend fun startListeningForConnection(
        relays: List<String>,
        secret: String?,
    )

    /**
     * Wait for an incoming connection from a remote signer.
     * Must call startListeningForConnection() first.
     * Returns the signer's public key on success.
     */
    suspend fun awaitIncomingConnection(): String

    suspend fun getPublicKey(): String

    suspend fun signEvent(eventJson: String): String

    /** NIP-46 nip44_encrypt RPC: encrypt [plaintext] to [peerPubkey] with the remote signer's key. */
    suspend fun nip44Encrypt(peerPubkey: String, plaintext: String): String

    /** NIP-46 nip44_decrypt RPC: decrypt [ciphertext] from [peerPubkey] with the remote signer's key. */
    suspend fun nip44Decrypt(peerPubkey: String, ciphertext: String): String

    fun disconnect()
}
