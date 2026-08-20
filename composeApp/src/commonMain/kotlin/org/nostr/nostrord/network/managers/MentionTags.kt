package org.nostr.nostrord.network.managers

import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.utils.normalizeForSearch

/**
 * A person a typed `@token` can resolve to: their pubkey and the names they are known by
 * (display name, name). Built from the group's member list at send time.
 */
internal data class MentionCandidate(
    val pubkey: String,
    val labels: List<String>,
)

/**
 * Resolution of composer `@displayName` mentions into event content + NIP-01 `p` tags. Shared by
 * every kind the composers publish (kind:9 chat, kind:11 thread root, kind:1111 thread reply) so a
 * mention notifies the same way wherever it was typed.
 *
 * `%group` mentions are resolved to `nostr:naddr...` by the UI before the send, since only the UI
 * knows which relay hosts the mentioned group.
 */
internal object MentionTags {
    // Trailing characters that end a sentence rather than a name, dropped off a typed token.
    private const val TOKEN_TRAILERS = ".,;:!?)]}>\"'`*_~…"

    /**
     * [content] with each `@displayName` replaced by its `nostr:npub...`, plus the `p` tags for the
     * mentioned pubkeys. Matching is token-aligned (longest name first), so `@ana` never eats the
     * head of `@anastasia`. Pubkeys already tagged by [existingTags] are skipped: a duplicate `p`
     * carries nothing and inflates the indexable-tag count some NIP-29 relays reject.
     */
    fun apply(
        content: String,
        mentions: Map<String, String>,
        existingTags: List<List<String>> = emptyList(),
    ): Pair<String, List<List<String>>> {
        if (mentions.isEmpty()) return content to emptyList()
        val names = mentions.keys.sortedByDescending { it.length }
        val tagged = existingTags.filter { it.size >= 2 && it[0] == "p" }.mapTo(mutableSetOf()) { it[1] }
        val tags = mutableListOf<List<String>>()
        val text = StringBuilder(content.length)
        var i = 0
        while (i < content.length) {
            val name = if (isTokenStart(content, i)) names.firstOrNull { endsToken(content, i + 1, it) } else null
            if (name == null) {
                text.append(content[i])
                i++
                continue
            }
            val pubkeyHex = mentions.getValue(name)
            text.append("nostr:").append(Nip19.encodeNpub(pubkeyHex))
            if (tagged.add(pubkeyHex)) tags.add(listOf("p", pubkeyHex))
            i += 1 + name.length
        }
        return text.toString() to tags
    }

    /**
     * The `@token`s in [content] that were typed out instead of picked from the autocomplete,
     * mapped to the pubkey they name, so a hand-typed mention notifies like a picked one. A token
     * resolves only on an exact name match (spaces optional) or a literal `npub`; an ambiguous name
     * shared by two members stays plain text rather than mentioning the wrong person.
     */
    fun resolveTyped(
        content: String,
        mentions: Map<String, String>,
        candidates: List<MentionCandidate>,
    ): Map<String, String> {
        val byLabel = mutableMapOf<String, MutableSet<String>>()
        candidates.forEach { candidate ->
            candidate.labels.filter { it.isNotBlank() }.forEach { label ->
                val normalized = label.normalizeForSearch()
                byLabel.getOrPut(normalized) { mutableSetOf() }.add(candidate.pubkey)
                // A multi-word name can't be typed as one token (the trigger dies at the space),
                // so "Alice Cooper" is also reachable as "@alicecooper".
                val compact = normalized.filterNot { it.isWhitespace() }
                if (compact != normalized && compact.isNotEmpty()) {
                    byLabel.getOrPut(compact) { mutableSetOf() }.add(candidate.pubkey)
                }
            }
        }
        val resolved = mutableMapOf<String, String>()
        var i = 0
        while (i < content.length) {
            if (!isTokenStart(content, i)) {
                i++
                continue
            }
            // A picked name can hold spaces, so its first word reads as a token of its own here.
            val picked = mentions.keys.firstOrNull { endsToken(content, i + 1, it) }
            if (picked != null) {
                i += 1 + picked.length
                continue
            }
            val raw = content.substring(i + 1).takeWhile { !it.isWhitespace() }
            val token = raw.trimEnd { it in TOKEN_TRAILERS }
            i += 1 + raw.length
            if (token.isEmpty() || token in resolved) continue
            val pubkey = npubPubkey(token) ?: byLabel[token.normalizeForSearch()]?.singleOrNull()
            if (pubkey != null) resolved[token] = pubkey
        }
        return resolved
    }

    /** [name] sits at [from] and ends the word there, so `@ana` does not eat the head of `@anastasia`. */
    private fun endsToken(content: String, from: Int, name: String): Boolean = content.startsWith(name, from) && content.getOrNull(from + name.length)?.isLetterOrDigit() != true

    /** An `@` that starts a word: mid-word (an email, a handle in a URL) is not a mention. */
    private fun isTokenStart(content: String, index: Int): Boolean = content[index] == '@' && content.getOrNull(index - 1)?.isLetterOrDigit() != true

    private fun npubPubkey(token: String): String? {
        if (!token.startsWith("npub1", ignoreCase = true)) return null
        return (Nip19.decode(token.lowercase()) as? Nip19.Entity.Npub)?.pubkey
    }
}
