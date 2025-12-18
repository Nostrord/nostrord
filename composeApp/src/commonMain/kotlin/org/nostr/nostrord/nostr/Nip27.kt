package org.nostr.nostrord.nostr

/**
 * NIP-27: Text Note References
 * Handles nostr: URI scheme for referencing events and profiles in text
 * Also handles bare bech32 references (nevent1..., note1..., npub1..., etc.)
 */
object Nip27 {

    // nostr: URI regex pattern
    private val nostrUriRegex = Regex(
        """nostr:(npub1|nsec1|note1|nevent1|nprofile1|naddr1)[a-z0-9]+""",
        RegexOption.IGNORE_CASE
    )

    // Bare bech32 regex pattern (without nostr: prefix)
    // Uses word boundary to avoid matching partial strings
    private val bareBech32Regex = Regex(
        """(?<![:/a-z0-9])(npub1|nsec1|note1|nevent1|nprofile1|naddr1)[a-z0-9]+""",
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
     * Also finds bare bech32 references (nevent1..., note1..., etc.)
     */
    fun findReferenceMatches(text: String): List<Pair<IntRange, NostrReference>> {
        val results = mutableListOf<Pair<IntRange, NostrReference>>()
        val matchedRanges = mutableSetOf<IntRange>()

        // First, find nostr: URI matches (these take priority)
        nostrUriRegex.findAll(text).forEach { match ->
            val uri = match.value
            val bech32 = uri.removePrefix("nostr:")
            val entity = Nip19.decode(bech32)
            if (entity != null) {
                results.add(match.range to NostrReference(uri, bech32, entity))
                matchedRanges.add(match.range)
            }
        }

        // Then, find bare bech32 matches (only if not already matched as nostr: URI)
        bareBech32Regex.findAll(text).forEach { match ->
            // Check if this range overlaps with any already matched range
            val overlaps = matchedRanges.any { existingRange ->
                match.range.first <= existingRange.last && match.range.last >= existingRange.first
            }

            if (!overlaps) {
                val bech32 = match.value
                val entity = Nip19.decode(bech32)
                if (entity != null) {
                    // Create a nostr: URI for consistency
                    val uri = "nostr:$bech32"
                    results.add(match.range to NostrReference(uri, bech32, entity))
                }
            }
        }

        // Sort by position
        return results.sortedBy { it.first.first }
    }

    /**
     * Check if text contains any nostr: URI references or bare bech32 references
     */
    fun containsReferences(text: String): Boolean {
        return nostrUriRegex.containsMatchIn(text) || bareBech32Regex.containsMatchIn(text)
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
