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

    /** Device pairing: new client offers a throwaway pubkey (Phase 2b). */
    const val KIND_CLIENT_KEY = 4454

    /** Device pairing: holder sends the encryption privkey to that throwaway key (Phase 2b). */
    const val KIND_KEY_SHARE = 4455

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

    /** Throwaway pubkey a pairing event carries (uppercase `P`). */
    const val TAG_THROWAWAY_PUBKEY = "P"

    /** The same value under the name Coop reads; emitted alongside `P` for compatibility. */
    const val TAG_THROWAWAY_PUBKEY_LEGACY = "pubkey"

    /**
     * Device pairing, step 1: a device without the encryption key publishes a throwaway pubkey
     * [throwawayPubkey], identity-signed, so a device that holds the key can answer it.
     *
     * No device label: it would tell every relay which OS and browser the requesting device runs,
     * and the pairing code already lets the user confirm which request they are approving.
     */
    fun buildClientKeyRequest(
        identityPubkey: String,
        throwawayPubkey: String,
        createdAt: Long,
    ): Event = Event(
        pubkey = identityPubkey,
        createdAt = createdAt,
        kind = KIND_CLIENT_KEY,
        // Both names for the same key: the NIP defines `P`, Coop only reads `pubkey`.
        tags =
        listOf(
            listOf(TAG_THROWAWAY_PUBKEY_LEGACY, throwawayPubkey),
            listOf(TAG_THROWAWAY_PUBKEY, throwawayPubkey),
        ),
        content = "",
    )

    /**
     * Device pairing, step 2: the holding device answers with the encryption key, NIP-44 encrypted
     * from its own throwaway [senderThrowawayPubkey] to the requester's [recipientThrowawayPubkey].
     * Neither identity key is involved in the encryption, so the share is readable only by the
     * device that generated the request.
     */
    fun buildKeyShare(
        identityPubkey: String,
        senderThrowawayPubkey: String,
        recipientThrowawayPubkey: String,
        encryptedKey: String,
        createdAt: Long,
    ): Event = Event(
        pubkey = identityPubkey,
        createdAt = createdAt,
        kind = KIND_KEY_SHARE,
        tags =
        listOf(
            listOf(TAG_THROWAWAY_PUBKEY, senderThrowawayPubkey),
            listOf("p", recipientThrowawayPubkey),
        ),
        content = encryptedKey,
    )

    /** The throwaway pubkey [event] publishes: `P`, or Coop's `pubkey` when that is all it sent. */
    fun throwawayPubkeyFrom(event: Event): String? = event.tags.firstOrNull { it.firstOrNull() == TAG_THROWAWAY_PUBKEY && isPubkey(it.getOrNull(1)) }?.get(1)
        ?: event.tags.firstOrNull { it.firstOrNull() == TAG_THROWAWAY_PUBKEY_LEGACY && isPubkey(it.getOrNull(1)) }?.get(1)

    /** Which throwaway pubkey a kind:4455 is addressed to (lowercase `p`). */
    fun keyShareRecipientFrom(event: Event): String? = event.tags
        .firstOrNull { it.firstOrNull() == "p" && isPubkey(it.getOrNull(1)) }
        ?.get(1)

    /**
     * Short code both devices show so the user can confirm they are approving the request they
     * actually made. Derived from the throwaway pubkey rather than carried in a tag: the
     * requesting device generated it and the holding device reads it off the request, so nothing
     * new goes on the wire and there is nothing extra to agree on.
     *
     * Eight characters in two groups, matching Jumble exactly: the whole point is that the user
     * can compare the code across two devices, which fails if the clients format it differently.
     */
    fun pairingCode(throwawayPubkey: String): String {
        val code = throwawayPubkey.take(8).uppercase()
        return "${code.take(4)} ${code.drop(4)}"
    }

    private fun isPubkey(value: String?): Boolean = value != null && value.length == 64 && value.all { it.isHexDigit() }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
