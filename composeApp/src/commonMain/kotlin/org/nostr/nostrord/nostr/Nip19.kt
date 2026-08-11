package org.nostr.nostrord.nostr

import org.nostr.nostrord.utils.LruCache

/**
 * NIP-19: bech32-encoded entities
 * Supports npub, nsec, note, nprofile, nevent, naddr
 */
object Nip19 {
    /**
     * Decoded Nostr entity
     */
    sealed class Entity {
        data class Npub(
            val pubkey: String,
        ) : Entity()

        data class Nsec(
            val privkey: String,
        ) : Entity()

        data class Note(
            val eventId: String,
        ) : Entity()

        data class Nprofile(
            val pubkey: String,
            val relays: List<String> = emptyList(),
        ) : Entity()

        data class Nevent(
            val eventId: String,
            val relays: List<String> = emptyList(),
            val author: String? = null,
            val kind: Int? = null,
        ) : Entity()

        data class Naddr(
            val identifier: String,
            val pubkey: String,
            val kind: Int,
            val relays: List<String> = emptyList(),
        ) : Entity()
    }

    // TLV types for nprofile, nevent, naddr
    private const val TLV_SPECIAL = 0
    private const val TLV_RELAY = 1
    private const val TLV_AUTHOR = 2
    private const val TLV_KIND = 3

    /**
     * Encode a public key to npub
     */
    fun encodeNpub(pubkeyHex: String): String = Bech32.encode("npub", pubkeyHex.hexToByteArray())

    // pubkeyHex: relay's own pubkey from NIP-11; falls back to 32 zero bytes if unknown.
    fun encodeNaddr(
        identifier: String,
        relay: String,
        kind: Int = 39000,
        pubkeyHex: String? = null,
    ): String {
        val tlv = mutableListOf<Byte>()

        fun addTLV(
            type: Int,
            value: ByteArray,
        ) {
            tlv.add(type.toByte())
            tlv.add(value.size.toByte())
            tlv.addAll(value.toList())
        }
        val authorBytes = pubkeyHex?.runCatching { hexToByteArray() }?.getOrNull()?.takeIf { it.size == 32 } ?: ByteArray(32)
        addTLV(TLV_SPECIAL, identifier.encodeToByteArray())
        addTLV(TLV_RELAY, relay.encodeToByteArray())
        addTLV(TLV_AUTHOR, authorBytes)
        addTLV(
            TLV_KIND,
            byteArrayOf(
                (kind shr 24).toByte(),
                (kind shr 16).toByte(),
                (kind shr 8).toByte(),
                kind.toByte(),
            ),
        )
        return Bech32.encode("naddr", tlv.toByteArray())
    }

    /**
     * Encode an event id (+ optional relay hints / author / kind) to nevent
     * (TLV 0 = event id, 1 = relay, 2 = author, 3 = kind). Relay hints matter for
     * NIP-29 events: they live only on the group relay, so a hint-less nevent is
     * unfetchable by clients that don't already know where to look.
     */
    fun encodeNevent(
        eventIdHex: String,
        relays: List<String> = emptyList(),
        authorHex: String? = null,
        kind: Int? = null,
    ): String {
        val tlv = mutableListOf<Byte>()

        fun addTLV(
            type: Int,
            value: ByteArray,
        ) {
            tlv.add(type.toByte())
            tlv.add(value.size.toByte())
            tlv.addAll(value.toList())
        }
        addTLV(TLV_SPECIAL, eventIdHex.hexToByteArray())
        relays.forEach { addTLV(TLV_RELAY, it.encodeToByteArray()) }
        authorHex?.runCatching { hexToByteArray() }?.getOrNull()?.takeIf { it.size == 32 }?.let {
            addTLV(TLV_AUTHOR, it)
        }
        kind?.let {
            addTLV(
                TLV_KIND,
                byteArrayOf(
                    (it shr 24).toByte(),
                    (it shr 16).toByte(),
                    (it shr 8).toByte(),
                    it.toByte(),
                ),
            )
        }
        return Bech32.encode("nevent", tlv.toByteArray())
    }

    /**
     * Encode a pubkey (+ optional relay hints) to nprofile (TLV 0 = pubkey, 1 = relay).
     */
    fun encodeNprofile(
        pubkeyHex: String,
        relays: List<String> = emptyList(),
    ): String {
        val tlv = mutableListOf<Byte>()

        fun addTLV(
            type: Int,
            value: ByteArray,
        ) {
            tlv.add(type.toByte())
            tlv.add(value.size.toByte())
            tlv.addAll(value.toList())
        }
        addTLV(TLV_SPECIAL, pubkeyHex.hexToByteArray())
        relays.forEach { addTLV(TLV_RELAY, it.encodeToByteArray()) }
        return Bech32.encode("nprofile", tlv.toByteArray())
    }

    /**
     * Encode a private key to nsec
     */
    fun encodeNsec(privkeyHex: String): String = Bech32.encode("nsec", privkeyHex.hexToByteArray())

    /**
     * Encode an event ID to note
     */
    fun encodeNote(eventIdHex: String): String = Bech32.encode("note", eventIdHex.hexToByteArray())

    /**
     * Decode any NIP-19 bech32 entity
     */
    /**
     * Decoded entities for tokens seen before. Rendering a message re-decodes every reference it
     * contains on each render, and a decode is base32 + checksum + TLV parsing; entities are
     * immutable, so sharing them is safe. Secrets are never cached (see [decode]).
     */
    private val decodeCache = LruCache<String, Entity>(512)

    fun decode(bech32: String): Entity? {
        decodeCache.get(bech32)?.let { return it }
        val entity = decodeUncached(bech32) ?: return null
        // An nsec stays out of any long-lived structure: the app zeroes key material on dispose
        // and a cache would outlive that.
        if (entity !is Entity.Nsec) decodeCache.put(bech32, entity)
        return entity
    }

    private fun decodeUncached(bech32: String): Entity? {
        val result = Bech32.decode(bech32) ?: return null
        val (hrp, data) = result

        return when (hrp) {
            "npub" -> {
                if (data.size != 32) return null
                Entity.Npub(data.toHexString())
            }
            "nsec" -> {
                if (data.size != 32) return null
                Entity.Nsec(data.toHexString())
            }
            "note" -> {
                if (data.size != 32) return null
                Entity.Note(data.toHexString())
            }
            "nprofile" -> decodeNprofile(data)
            "nevent" -> decodeNevent(data)
            "naddr" -> decodeNaddr(data)
            else -> null
        }
    }

    private fun decodeNprofile(data: ByteArray): Entity.Nprofile? {
        val tlvs = parseTLV(data)
        val pubkey = tlvs[TLV_SPECIAL]?.firstOrNull()?.toHexString() ?: return null
        val relays = tlvs[TLV_RELAY]?.map { it.decodeToString() } ?: emptyList()
        return Entity.Nprofile(pubkey, relays)
    }

    private fun decodeNevent(data: ByteArray): Entity.Nevent? {
        val tlvs = parseTLV(data)
        val eventId = tlvs[TLV_SPECIAL]?.firstOrNull()?.toHexString() ?: return null
        val relays = tlvs[TLV_RELAY]?.map { it.decodeToString() } ?: emptyList()
        val author = tlvs[TLV_AUTHOR]?.firstOrNull()?.toHexString()
        val kind = tlvs[TLV_KIND]?.firstOrNull()?.let { bytesToInt(it) }
        return Entity.Nevent(eventId, relays, author, kind)
    }

    private fun decodeNaddr(data: ByteArray): Entity.Naddr? {
        val tlvs = parseTLV(data)
        val identifier = tlvs[TLV_SPECIAL]?.firstOrNull()?.decodeToString() ?: return null
        val pubkey = tlvs[TLV_AUTHOR]?.firstOrNull()?.toHexString() ?: return null
        val kind = tlvs[TLV_KIND]?.firstOrNull()?.let { bytesToInt(it) } ?: return null
        val relays = tlvs[TLV_RELAY]?.map { it.decodeToString() } ?: emptyList()
        return Entity.Naddr(identifier, pubkey, kind, relays)
    }

    private fun parseTLV(data: ByteArray): Map<Int, List<ByteArray>> {
        val result = mutableMapOf<Int, MutableList<ByteArray>>()
        var i = 0
        while (i < data.size) {
            if (i + 2 > data.size) break
            val type = data[i].toInt() and 0xFF
            val length = data[i + 1].toInt() and 0xFF
            i += 2
            if (i + length > data.size) break
            val value = data.sliceArray(i until i + length)
            result.getOrPut(type) { mutableListOf() }.add(value)
            i += length
        }
        return result
    }

    private fun bytesToInt(bytes: ByteArray): Int {
        var result = 0
        for (b in bytes) {
            result = (result shl 8) or (b.toInt() and 0xFF)
        }
        return result
    }

    /**
     * Extract the display-friendly identifier from a nostr: URI
     * Returns the pubkey/eventId in short form or the original if parsing fails
     */
    fun getDisplayName(entity: Entity): String = when (entity) {
        is Entity.Npub -> "@${entity.pubkey.take(8)}..."
        is Entity.Nsec -> "nsec:${entity.privkey.take(8)}..."
        is Entity.Note -> "note:${entity.eventId.take(8)}..."
        is Entity.Nprofile -> "@${entity.pubkey.take(8)}..."
        is Entity.Nevent -> "event:${entity.eventId.take(8)}..."
        is Entity.Naddr -> "addr:${entity.identifier.take(12)}..."
    }

    /**
     * Get the primary identifier (pubkey or event ID) from an entity
     */
    fun getPrimaryId(entity: Entity): String = when (entity) {
        is Entity.Npub -> entity.pubkey
        is Entity.Nsec -> entity.privkey
        is Entity.Note -> entity.eventId
        is Entity.Nprofile -> entity.pubkey
        is Entity.Nevent -> entity.eventId
        is Entity.Naddr -> entity.pubkey
    }

    /**
     * Outcome of parsing user-provided pubkey input — used by the add-member
     * modals and any other place that needs to validate "the user typed an
     * identity". Concrete error variants instead of a single Invalid so the
     * UI can say something more useful than "Invalid".
     */
    sealed class PubkeyParse {
        data class Ok(val hex: String) : PubkeyParse()
        object Empty : PubkeyParse()

        /** Looks like a bech32 entity but doesn't decode (bad checksum, wrong HRP, etc). */
        object Malformed : PubkeyParse()

        /** Decoded as something that isn't a user identity (note, nevent, naddr). */
        object NotAPubkey : PubkeyParse()

        /** User pasted their private key — never broadcast this. */
        object IsPrivateKey : PubkeyParse()
    }

    /**
     * Parse a string the user typed into a "user identity" field. Accepts:
     *  - 64-char hex pubkey, any case (lower / upper / mixed) — normalised to lower
     *  - npub1...   (bech32 npub)
     *  - nprofile1... (NIP-19 profile, takes the pubkey, drops relay hints)
     *
     * Rejects (with specific reasons):
     *  - nsec... (their private key) — explicit reject, never silently treat as pubkey
     *  - note / nevent / naddr — those aren't user identities
     *  - anything else — Malformed
     */
    fun parsePubkeyInput(input: String): PubkeyParse {
        val s = input.trim()
        if (s.isBlank()) return PubkeyParse.Empty

        // Plain 64-char hex pubkey (most common from copy-paste of a UI display).
        if (s.length == 64 && s.all { it.isAsciiHexDigit() }) {
            return PubkeyParse.Ok(s.lowercase())
        }

        // Bech32 entity: must have the "1" separator. Anything that starts with
        // "npub" / "nsec" / "nprofile" but no "1" is a typo, not a real entity.
        return when {
            s.startsWith("npub1") || s.startsWith("nprofile1") || s.startsWith("nsec1") -> {
                when (val entity = decode(s)) {
                    is Entity.Npub -> PubkeyParse.Ok(entity.pubkey)
                    is Entity.Nprofile -> PubkeyParse.Ok(entity.pubkey)
                    is Entity.Nsec -> PubkeyParse.IsPrivateKey
                    null -> PubkeyParse.Malformed
                    else -> PubkeyParse.NotAPubkey
                }
            }
            s.startsWith("note1") || s.startsWith("nevent1") || s.startsWith("naddr1") -> PubkeyParse.NotAPubkey
            else -> PubkeyParse.Malformed
        }
    }

    private fun Char.isAsciiHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
