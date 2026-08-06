package org.nostr.nostrord.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.nostr.nostrord.auth.NostrSigner
import org.nostr.nostrord.utils.epochSeconds
import kotlin.random.Random

/**
 * NIP-17 private direct messages, carried over NIP-59 gift wraps:
 *
 *   rumor (kind 14, unsigned) -> seal (kind 13, identity-signed, NIP-44 to the recipient)
 *   -> gift wrap (kind 1059, ephemeral-signed, NIP-44 to the recipient, randomized timestamp)
 *
 * The gift wrap is encrypted under a throwaway key we generate and own. The seal goes through
 * [NostrSigner.nip44Encrypt] / [nip44Decrypt] so remote signers plug in.
 *
 * Both layers address the recipient's identity key by default (standard, interoperable NIP-17,
 * single `p` tag). NIP-4e recipients are addressed at their announced encryption key instead:
 * see [Nip4e], `encryptTo`, and the shape taxonomy on [unwrap]. Only the ECDH peer changes;
 * authorship, signatures and `p` routing stay on identity keys.
 */
object Nip17 {
    const val KIND_CHAT = 14
    const val KIND_SEAL = 13
    const val KIND_GIFT_WRAP = 1059
    const val KIND_DM_RELAYS = 10050

    private const val TWO_DAYS_SECONDS = 172800L

    /** Random gift-wrap timestamp: up to 2 days before [now] (NIP-59 metadata obfuscation). */
    fun randomizedWrapTime(now: Long = epochSeconds()): Long = now - Random.nextLong(0, TWO_DAYS_SECONDS)

    /**
     * Unsigned kind:14 chat rumor (id computed, no signature) from [senderPubkey] to
     * [recipientPubkey]. [extraTags] can carry a reply `["e", id, relay]` etc.
     */
    fun buildRumor(
        senderPubkey: String,
        recipientPubkey: String,
        content: String,
        createdAt: Long = epochSeconds(),
        extraTags: List<List<String>> = emptyList(),
    ): Event {
        val rumor =
            Event(
                pubkey = senderPubkey,
                createdAt = createdAt,
                kind = KIND_CHAT,
                tags = listOf(listOf("p", recipientPubkey)) + extraTags,
                content = content,
            )
        return rumor.copy(id = rumor.calculateId())
    }

    /**
     * Seal a [rumor] (kind:13): NIP-44 encrypt it to [encryptTo] with the account key via [signer]
     * and identity-sign, so `seal.pubkey == rumor.pubkey`.
     *
     * [encryptTo] is the NIP-44 ECDH peer, which is the recipient's identity key by default and
     * their NIP-4e encryption key when they announced one. [senderEncTag] names the key the
     * recipient must ECDH this seal against (`["n", ...]`, NIP-4e); pass it whenever [encryptTo]
     * is not the recipient's identity key, or readers fall back to a kind:10044 lookup.
     */
    suspend fun seal(
        rumor: Event,
        recipientPubkey: String,
        signer: NostrSigner,
        createdAt: Long = rumor.createdAt,
        encryptTo: String = recipientPubkey,
        senderEncTag: String? = null,
        encryptWith: String? = null,
    ): Event {
        // Holding an encryption key turns the seal's NIP-44 into a local operation; only the
        // signature still needs the signer.
        val encrypted =
            if (encryptWith != null) {
                Nip44.encrypt(rumor.toJsonString(), encryptWith, encryptTo)
            } else {
                signer.nip44Encrypt(encryptTo, rumor.toJsonString())
            }
        val unsigned =
            Event(
                pubkey = signer.pubkey,
                createdAt = createdAt,
                kind = KIND_SEAL,
                tags = senderEncTag?.let { listOf(listOf(Nip4e.TAG_ENCRYPTION_PUBKEY, it)) } ?: emptyList(),
                content = encrypted,
            )
        return signer.signEvent(unsigned)
    }

    /**
     * NIP-4e legacy seal: signed by the encryption key itself, so `seal.pubkey` is that key and
     * there is no `n` tag. Deployed readers that predate the identity-signed shape only accept
     * this one, and it costs no signer call. A recipient must authenticate it by checking
     * `seal.pubkey` against the author's announced kind:10044.
     */
    fun legacySeal(
        rumor: Event,
        encryptionPrivateKeyHex: String,
        encryptTo: String,
        createdAt: Long = rumor.createdAt,
    ): Event {
        val encryptionKey = KeyPair.fromPrivateKeyHex(encryptionPrivateKeyHex)
        val unsigned =
            Event(
                pubkey = encryptionKey.publicKeyHex,
                createdAt = createdAt,
                kind = KIND_SEAL,
                content = Nip44.encrypt(rumor.toJsonString(), encryptionPrivateKeyHex, encryptTo),
            )
        return unsigned.sign(encryptionKey)
    }

    /**
     * Gift-wrap a [seal] (kind:1059): NIP-44 encrypt it to [encryptTo] under a throwaway key,
     * `p`-tag the recipient, randomize the timestamp, and sign with the throwaway key.
     *
     * The `p` tags always include [recipientPubkey], the identity key: that is what relays route on
     * and what every inbox REQ filters by. When [encryptTo] is a NIP-4e encryption key it leads the
     * list, because deployed readers take `p[0]` as the key the wrap is encrypted to.
     */
    fun giftWrap(
        seal: Event,
        recipientPubkey: String,
        createdAt: Long = randomizedWrapTime(),
        encryptTo: String = recipientPubkey,
    ): Event {
        val ephemeral = KeyPair.generate()
        val encrypted = Nip44.encrypt(seal.toJsonString(), ephemeral.privateKeyHex, encryptTo)
        val tags =
            if (encryptTo == recipientPubkey) {
                listOf(listOf("p", recipientPubkey))
            } else {
                listOf(listOf("p", encryptTo), listOf("p", recipientPubkey))
            }
        val unsigned =
            Event(
                pubkey = ephemeral.publicKeyHex,
                createdAt = createdAt,
                kind = KIND_GIFT_WRAP,
                tags = tags,
                content = encrypted,
            )
        return unsigned.sign(ephemeral)
    }

    /**
     * End-to-end: build the seal for [rumor] (identity-signed via [signer]) and wrap it for
     * [recipientPubkey]. Returns the kind:1059 ready to publish to the recipient's DM relays.
     * Defaults produce classic NIP-17; pass [encryptTo]/[senderEncTag] for a NIP-4e recipient.
     */
    suspend fun wrap(
        rumor: Event,
        recipientPubkey: String,
        signer: NostrSigner,
        sealCreatedAt: Long = rumor.createdAt,
        wrapCreatedAt: Long = randomizedWrapTime(),
        encryptTo: String = recipientPubkey,
        senderEncTag: String? = null,
        encryptWith: String? = null,
    ): Event = giftWrap(
        seal(rumor, recipientPubkey, signer, sealCreatedAt, encryptTo, senderEncTag, encryptWith),
        recipientPubkey,
        wrapCreatedAt,
        encryptTo,
    )

    /** One way of running a NIP-44 decrypt: a locally held key, or a round-trip to the signer. */
    fun interface Nip44Decryptor {
        suspend fun decrypt(peerPubkeyHex: String, ciphertext: String): String
    }

    data class Unwrapped(
        val rumor: Event,
        val senderPubkey: String,
        val giftWrapId: String?,
        /** The `n` value on the seal, when the sender named their NIP-4e encryption key. */
        val senderEncryptionPubkey: String? = null,
        val sealPubkey: String = senderPubkey,
        /**
         * False for a seal signed by the sender's encryption key rather than their identity: the
         * sender is unauthenticated here, and the caller MUST check [sealPubkey] against the
         * author's announced kind:10044 before showing the message.
         */
        val sealSignedByIdentity: Boolean = true,
    )

    /**
     * Unwrap a received kind:1059 for [myPubkey]: gift wrap -> seal -> rumor, trying each of
     * [decryptors] in turn on both layers. Order matters: locally held keys fail in microseconds
     * on a bad HMAC, a remote signer costs a network round-trip, so put local keys first.
     *
     * Returns null if anything is malformed, the seal signature is invalid, or the rumor author
     * differs from the seal author on a shape where they must match (NIP-59 forgery guard).
     *
     * Four shapes are accepted, all of which are live on relays today:
     * - classic NIP-17: no `n`, seal identity-signed, both layers addressed to the identity key;
     * - NIP-4e modern: `n` names the sender's encryption key, seal still identity-signed;
     * - NIP-4e legacy: seal signed by the sender's ENCRYPTION key, no `n`, so
     *   `seal.pubkey != rumor.pubkey` and the caller authenticates via kind:10044;
     * - our own seal over someone else's rumor: only our signer can produce our signature, so the
     *   author guard is relaxed for it (self-archive).
     */
    suspend fun unwrap(giftWrap: Event, myPubkey: String, decryptors: List<Nip44Decryptor>): Unwrapped? {
        if (giftWrap.kind != KIND_GIFT_WRAP) return null
        val sealJson = tryDecrypt(decryptors, giftWrap.pubkey, giftWrap.content) ?: return null
        val seal = runCatching { parseEvent(sealJson) }.getOrNull() ?: return null
        if (seal.kind != KIND_SEAL || !seal.verify()) return null
        // The seal names the key its content is encrypted against; without it the peer is the
        // seal's own author, which is both the classic and the legacy behavior.
        val senderEncPubkey = Nip4e.encryptionKeyFromTags(seal.tags)
        val rumorJson = tryDecrypt(decryptors, senderEncPubkey ?: seal.pubkey, seal.content) ?: return null
        val rumor = runCatching { parseEvent(rumorJson) }.getOrNull() ?: return null

        return when {
            seal.pubkey == myPubkey ->
                Unwrapped(rumor, rumor.pubkey, giftWrap.id, senderEncPubkey, seal.pubkey, sealSignedByIdentity = true)
            senderEncPubkey != null || seal.pubkey == rumor.pubkey -> {
                if (rumor.pubkey != seal.pubkey) return null
                Unwrapped(rumor, seal.pubkey, giftWrap.id, senderEncPubkey, seal.pubkey, sealSignedByIdentity = true)
            }
            else ->
                Unwrapped(rumor, rumor.pubkey, giftWrap.id, senderEncPubkey, seal.pubkey, sealSignedByIdentity = false)
        }
    }

    /** Unwrap with a single signer, the classic path. */
    suspend fun unwrap(giftWrap: Event, signer: NostrSigner): Unwrapped? = unwrap(giftWrap, signer.pubkey, listOf(Nip44Decryptor { peer, ciphertext -> signer.nip44Decrypt(peer, ciphertext) }))

    private suspend fun tryDecrypt(decryptors: List<Nip44Decryptor>, peerPubkeyHex: String, ciphertext: String): String? {
        for (decryptor in decryptors) {
            val plaintext =
                try {
                    decryptor.decrypt(peerPubkeyHex, ciphertext)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }
            if (plaintext != null) return plaintext
        }
        return null
    }

    /** Parse a stored kind:14 rumor back into an [Event]; null when the JSON is unusable. */
    fun parseRumor(json: String): Event? = runCatching { parseEvent(json) }.getOrNull()?.takeIf { it.kind == KIND_CHAT }

    private val lenientJson = Json { ignoreUnknownKeys = true }

    /** Parse a (possibly unsigned) event JSON into [Event]; id/sig optional. */
    private fun parseEvent(json: String): Event {
        val o = lenientJson.parseToJsonElement(json).jsonObject
        return Event(
            id = o["id"]?.jsonPrimitive?.contentOrNull,
            pubkey = o["pubkey"]?.jsonPrimitive?.content ?: error("event json: missing pubkey"),
            createdAt = o["created_at"]?.jsonPrimitive?.long ?: 0L,
            kind = o["kind"]?.jsonPrimitive?.int ?: error("event json: missing kind"),
            tags = o["tags"]?.jsonArray?.map { t -> t.jsonArray.map { it.jsonPrimitive.content } } ?: emptyList(),
            content = o["content"]?.jsonPrimitive?.content ?: "",
            sig = o["sig"]?.jsonPrimitive?.contentOrNull,
        )
    }
}
