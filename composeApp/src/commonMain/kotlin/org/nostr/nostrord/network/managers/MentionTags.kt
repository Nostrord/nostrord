package org.nostr.nostrord.network.managers

import org.nostr.nostrord.nostr.Nip19

/**
 * Resolution of composer `@displayName` mentions into event content + NIP-01 `p` tags. Shared by
 * every kind the composers publish (kind:9 chat, kind:11 thread root, kind:1111 thread reply) so a
 * mention notifies the same way wherever it was typed.
 *
 * `%group` mentions are resolved to `nostr:naddr...` by the UI before the send, since only the UI
 * knows which relay hosts the mentioned group.
 */
internal object MentionTags {
    /**
     * [content] with each `@displayName` replaced by its `nostr:npub...`, plus the `p` tags for the
     * mentioned pubkeys. Pubkeys already tagged by [existingTags] are skipped: a duplicate `p`
     * carries nothing and inflates the indexable-tag count some NIP-29 relays reject.
     */
    fun apply(
        content: String,
        mentions: Map<String, String>,
        existingTags: List<List<String>> = emptyList(),
    ): Pair<String, List<List<String>>> {
        if (mentions.isEmpty()) return content to emptyList()
        val tagged = existingTags.filter { it.size >= 2 && it[0] == "p" }.mapTo(mutableSetOf()) { it[1] }
        val tags = mutableListOf<List<String>>()
        var text = content
        mentions.forEach { (displayName, pubkeyHex) ->
            text = text.replace("@$displayName", "nostr:${Nip19.encodeNpub(pubkeyHex)}")
            if (tagged.add(pubkeyHex)) tags.add(listOf("p", pubkeyHex))
        }
        return text to tags
    }
}
