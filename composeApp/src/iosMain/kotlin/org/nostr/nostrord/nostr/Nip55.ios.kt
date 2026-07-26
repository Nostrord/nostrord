package org.nostr.nostrord.nostr

actual object Nip55 {
    actual fun isAvailable(): Boolean = false

    actual suspend fun getPublicKey(permissionsJson: String): Nip55Login = throw UnsupportedOperationException("NIP-55 signers are only available on Android")

    actual suspend fun signEvent(
        eventJson: String,
        currentUserHex: String,
        signerPackage: String?,
    ): String = throw UnsupportedOperationException("NIP-55 signers are only available on Android")

    actual suspend fun nip44Encrypt(
        peerPubkeyHex: String,
        plaintext: String,
        currentUserHex: String,
        signerPackage: String?,
    ): String = throw UnsupportedOperationException("NIP-55 signers are only available on Android")

    actual suspend fun nip44Decrypt(
        peerPubkeyHex: String,
        ciphertext: String,
        currentUserHex: String,
        signerPackage: String?,
    ): String = throw UnsupportedOperationException("NIP-55 signers are only available on Android")
}
