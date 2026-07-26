package org.nostr.nostrord.nostr

/** Result of a NIP-55 `get_public_key` round-trip. [signerPackage] addresses every later request. */
data class Nip55Login(
    val pubkeyHex: String,
    val signerPackage: String?,
)

/**
 * Permissions requested at login so pre-authorized operations run silently through the
 * signer's ContentResolver instead of opening its approval UI: the kinds Nostrord signs
 * (NIP-42 AUTH, NIP-29 chat + membership, profile / contacts / lists, reports, NIP-17
 * seals) plus NIP-44 for direct messages. A kind missing here still works, it just falls
 * back to the signer's approval screen.
 */
const val NIP55_PERMISSIONS: String =
    """[{"type":"sign_event","kind":22242},{"type":"sign_event","kind":9},""" +
        """{"type":"sign_event","kind":9021},{"type":"sign_event","kind":9022},""" +
        """{"type":"sign_event","kind":0},{"type":"sign_event","kind":3},""" +
        """{"type":"sign_event","kind":7},{"type":"sign_event","kind":13},""" +
        """{"type":"sign_event","kind":1984},{"type":"sign_event","kind":10000},""" +
        """{"type":"sign_event","kind":10002},{"type":"sign_event","kind":10009},""" +
        """{"type":"nip44_encrypt"},{"type":"nip44_decrypt"}]"""

/**
 * NIP-55 Android signer application client (Amber).
 * https://github.com/nostr-protocol/nips/blob/master/55.md
 *
 * Real implementation on Android only: requests go through the signer's ContentResolver
 * when pre-authorized (silent) and fall back to an `nostrsigner:` Intent (approval UI).
 * All other targets are documented stubs ([isAvailable] false, calls throw).
 */
expect object Nip55 {
    /** True when a NIP-55 signer app is installed (an activity handles the `nostrsigner:` scheme). */
    fun isAvailable(): Boolean

    /** Opens the signer to pick/approve an account; returns its pubkey and package name. */
    suspend fun getPublicKey(permissionsJson: String): Nip55Login

    /** Signs [eventJson] as [currentUserHex]; returns the full signed event JSON. */
    suspend fun signEvent(
        eventJson: String,
        currentUserHex: String,
        signerPackage: String?,
    ): String

    /** NIP-44 encrypt [plaintext] for [peerPubkeyHex] with [currentUserHex]'s key. */
    suspend fun nip44Encrypt(
        peerPubkeyHex: String,
        plaintext: String,
        currentUserHex: String,
        signerPackage: String?,
    ): String

    /** NIP-44 decrypt [ciphertext] from [peerPubkeyHex] with [currentUserHex]'s key. */
    suspend fun nip44Decrypt(
        peerPubkeyHex: String,
        ciphertext: String,
        currentUserHex: String,
        signerPackage: String?,
    ): String
}
