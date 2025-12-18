package org.nostr.nostrord.nostr

/**
 * NIP-19: bech32-encoded entities
 * Supports npub, nsec, note, nprofile, nevent, naddr
 */
object Nip19 {

    /**
     * Decoded Nostr entity
     */
    sealed class Entity {
        data class Npub(val pubkey: String) : Entity()
        data class Nsec(val privkey: String) : Entity()
        data class Note(val eventId: String) : Entity()
        data class Nprofile(
            val pubkey: String,
            val relays: List<String> = emptyList()
        ) : Entity()
        data class Nevent(
            val eventId: String,
            val relays: List<String> = emptyList(),
            val author: String? = null,
            val kind: Int? = null
        ) : Entity()
        data class Naddr(
            val identifier: String,
            val pubkey: String,
            val kind: Int,
            val relays: List<String> = emptyList()
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
    fun encodeNpub(pubkeyHex: String): String {
        return Bech32.encode("npub", pubkeyHex.hexToByteArray())
    }

    /**
     * Encode a private key to nsec
     */
    fun encodeNsec(privkeyHex: String): String {
        return Bech32.encode("nsec", privkeyHex.hexToByteArray())
    }

    /**
     * Encode an event ID to note
     */
    fun encodeNote(eventIdHex: String): String {
        return Bech32.encode("note", eventIdHex.hexToByteArray())
    }

    /**
     * Decode any NIP-19 bech32 entity
     */
    fun decode(bech32: String): Entity? {
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
        val relays = tlvs[TLV_RELAY]?.map { String(it, Charsets.UTF_8) } ?: emptyList()
        return Entity.Nprofile(pubkey, relays)
    }

    private fun decodeNevent(data: ByteArray): Entity.Nevent? {
        val tlvs = parseTLV(data)
        val eventId = tlvs[TLV_SPECIAL]?.firstOrNull()?.toHexString() ?: return null
        val relays = tlvs[TLV_RELAY]?.map { String(it, Charsets.UTF_8) } ?: emptyList()
        val author = tlvs[TLV_AUTHOR]?.firstOrNull()?.toHexString()
        val kind = tlvs[TLV_KIND]?.firstOrNull()?.let { bytesToInt(it) }
        return Entity.Nevent(eventId, relays, author, kind)
    }

    private fun decodeNaddr(data: ByteArray): Entity.Naddr? {
        val tlvs = parseTLV(data)
        val identifier = tlvs[TLV_SPECIAL]?.firstOrNull()?.let { String(it, Charsets.UTF_8) } ?: return null
        val pubkey = tlvs[TLV_AUTHOR]?.firstOrNull()?.toHexString() ?: return null
        val kind = tlvs[TLV_KIND]?.firstOrNull()?.let { bytesToInt(it) } ?: return null
        val relays = tlvs[TLV_RELAY]?.map { String(it, Charsets.UTF_8) } ?: emptyList()
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
    fun getDisplayName(entity: Entity): String {
        return when (entity) {
            is Entity.Npub -> "@${entity.pubkey.take(8)}..."
            is Entity.Nsec -> "nsec:${entity.privkey.take(8)}..."
            is Entity.Note -> "note:${entity.eventId.take(8)}..."
            is Entity.Nprofile -> "@${entity.pubkey.take(8)}..."
            is Entity.Nevent -> "event:${entity.eventId.take(8)}..."
            is Entity.Naddr -> "addr:${entity.identifier.take(12)}..."
        }
    }

    /**
     * Get the primary identifier (pubkey or event ID) from an entity
     */
    fun getPrimaryId(entity: Entity): String {
        return when (entity) {
            is Entity.Npub -> entity.pubkey
            is Entity.Nsec -> entity.privkey
            is Entity.Note -> entity.eventId
            is Entity.Nprofile -> entity.pubkey
            is Entity.Nevent -> entity.eventId
            is Entity.Naddr -> entity.pubkey
        }
    }
}
