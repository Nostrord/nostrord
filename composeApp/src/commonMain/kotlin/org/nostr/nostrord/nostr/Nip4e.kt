package org.nostr.nostrord.nostr

/**
 * NIP-4e: encryption decoupled from identity.
 *
 * A user announces a dedicated encryption pubkey `E` in a replaceable kind:10044 signed by their
 * identity key `A`. Senders then derive the NIP-44 conversation key against `E` (`ecdh(b, E)`)
 * instead of `A`, on BOTH NIP-17 layers, so a recipient holding `e` locally decrypts without a
 * remote signer. Identity is untouched: `p` tags, seal signatures and event authorship stay on `A`.
 *
 * This is the ONLY place that knows nip4e wire constants. The proposal
 * (nostr-protocol/nips#1647) is an open, contested draft and the deployed clients already
 * diverge from it, so kinds and tag names are expected to move; keep every format detail here.
 *
 * Divergence from the PR text, taken from the deployed Jumble implementation (authoritative for
 * interop): the `n` tag also appears on the kind:13 seal, naming the encryption key the recipient
 * must ECDH the seal content against, so no kind:10044 lookup is needed to read a message.
 */
object Nip4e {
    /** Replaceable announcement carrying the account's encryption pubkey. */
    const val KIND_ENCRYPTION_KEY = 10044

    /** Names an encryption pubkey. On kind:10044 it is the announcement; on a seal, the sender's. */
    const val TAG_ENCRYPTION_PUBKEY = "n"

    /**
     * The encryption pubkey [event] announces, or null when it announces none. A kind:10044 with
     * no valid `n` tag is a withdrawal: the author is back to identity-addressed encryption.
     */
    fun encryptionKeyFrom(event: Event): String? {
        if (event.kind != KIND_ENCRYPTION_KEY) return null
        return encryptionKeyFromTags(event.tags)
    }

    /** The `n` value carried by a seal (or any event's tags), validated. Null when absent. */
    fun encryptionKeyFromTags(tags: List<List<String>>): String? = tags
        .firstOrNull { it.firstOrNull() == TAG_ENCRYPTION_PUBKEY && isPubkey(it.getOrNull(1)) }
        ?.get(1)

    /**
     * Unsigned kind:10044 announcing [encPubkeyHex] for [identityPubkey]. A null key builds the
     * withdrawal shape (no `n` tag); replaceable latest-wins then retires the previous key for
     * senders. Withdrawing never means deleting the private key: messages already addressed to it
     * only ever open with it.
     */
    fun buildAnnouncement(
        identityPubkey: String,
        encPubkeyHex: String?,
        createdAt: Long,
    ): Event = Event(
        pubkey = identityPubkey,
        createdAt = createdAt,
        kind = KIND_ENCRYPTION_KEY,
        tags = encPubkeyHex?.takeIf { isPubkey(it) }?.let { listOf(listOf(TAG_ENCRYPTION_PUBKEY, it)) } ?: emptyList(),
        content = "",
    )

    private fun isPubkey(value: String?): Boolean = value != null && value.length == 64 && value.all { it.isHexDigit() }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
