package org.nostr.nostrord.nostr

/**
 * NIP-27: Text Note References
 * Handles nostr: URI scheme for referencing events and profiles in text
 */
object Nip27 {

    // nostr: URI regex pattern
    private val nostrUriRegex = Regex(
        """nostr:(npub1|nsec1|note1|nevent1|nprofile1|naddr1)[a-z0-9]+""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Parsed nostr: URI reference
     */
    data class NostrReference(
        val uri: String,
        val bech32: String,
        val entity: Nip19.Entity
    )

    /**
     * Find all nostr: URI references in text
     */
    fun findReferences(text: String): List<NostrReference> {
        return nostrUriRegex.findAll(text).mapNotNull { match ->
            val uri = match.value
            val bech32 = uri.removePrefix("nostr:")
            val entity = Nip19.decode(bech32)
            if (entity != null) {
                NostrReference(uri, bech32, entity)
            } else {
                null
            }
        }.toList()
    }

    /**
     * Find all nostr: URI matches with their positions in text
     */
    fun findReferenceMatches(text: String): List<Pair<IntRange, NostrReference>> {
        return nostrUriRegex.findAll(text).mapNotNull { match ->
            val uri = match.value
            val bech32 = uri.removePrefix("nostr:")
            val entity = Nip19.decode(bech32)
            if (entity != null) {
                match.range to NostrReference(uri, bech32, entity)
            } else {
                null
            }
        }.toList()
    }

    /**
     * Check if text contains any nostr: URI references
     */
    fun containsReferences(text: String): Boolean {
        return nostrUriRegex.containsMatchIn(text)
    }

    /**
     * Create a nostr: URI from a NIP-19 bech32 string
     */
    fun createUri(bech32: String): String {
        return "nostr:$bech32"
    }

    /**
     * Create a nostr: URI for a public key
     */
    fun createNpubUri(pubkeyHex: String): String {
        return "nostr:${Nip19.encodeNpub(pubkeyHex)}"
    }

    /**
     * Create a nostr: URI for an event
     */
    fun createNoteUri(eventIdHex: String): String {
        return "nostr:${Nip19.encodeNote(eventIdHex)}"
    }
}
