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
 * The seal is encrypted/decrypted with the account's identity key through [NostrSigner.nip44Encrypt]
 * / [nip44Decrypt] so remote signers can plug in; the gift wrap is encrypted under a throwaway key
 * we generate and own.
 *
 * Both layers address the recipient's identity key by default (standard, interoperable NIP-17,
 * single `p` tag). A recipient who announced a NIP-4e encryption key is addressed at that key
 * instead: see [Nip4e] and the `encryptTo` parameters. Only the ECDH peer changes; authorship,
 * signatures and `p` routing stay on identity keys.
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
    ): Event {
        val encrypted = signer.nip44Encrypt(encryptTo, rumor.toJsonString())
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
    ): Event = giftWrap(
        seal(rumor, recipientPubkey, signer, sealCreatedAt, encryptTo, senderEncTag),
        recipientPubkey,
        wrapCreatedAt,
        encryptTo,
    )

    data class Unwrapped(
        val rumor: Event,
        val senderPubkey: String,
        val giftWrapId: String?,
    )

    /**
     * Unwrap a received kind:1059 with [signer] (the recipient): gift wrap -> seal -> rumor.
     * Returns null if anything is malformed, the seal signature is invalid, or the rumor author
     * differs from the seal author (NIP-59 forgery guard).
     */
    suspend fun unwrap(giftWrap: Event, signer: NostrSigner): Unwrapped? {
        if (giftWrap.kind != KIND_GIFT_WRAP) return null
        val seal =
            runCatching { parseEvent(signer.nip44Decrypt(giftWrap.pubkey, giftWrap.content)) }
                .getOrNull() ?: return null
        if (seal.kind != KIND_SEAL || !seal.verify()) return null
        val rumor =
            runCatching { parseEvent(signer.nip44Decrypt(seal.pubkey, seal.content)) }
                .getOrNull() ?: return null
        if (rumor.pubkey != seal.pubkey) return null
        return Unwrapped(rumor = rumor, senderPubkey = seal.pubkey, giftWrapId = giftWrap.id)
    }

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
