package org.nostr.nostrord.network.managers

import org.nostr.nostrord.network.NostrGroupClient

/**
 * Pure builders for forum-thread event tags (NIP-29 kind:11 root + NIP-22 kind:1111 reply).
 * Kept out of [GroupManager] so the protocol-critical tag shape is unit-testable in isolation
 * (GroupManager needs the whole network stack to instantiate; these are pure functions).
 */
internal object ThreadTags {
    /**
     * Tags for a kind:11 thread root: the NIP-29 group `h` tag (with a relay hint when known),
     * the relay-wide `-` marker for the id-less `_` group, and the optional thread title as BOTH
     * the NIP-7D `title` (what kind:11 readers like Squalk expect - issue #221) and the NIP-14
     * `subject` (what older Nostrord releases and chat-style readers expect); omitted when blank.
     */
    fun root(groupId: String, relayHint: String?, subject: String?): List<List<String>> = buildList {
        add(listOfNotNull("h", groupId, relayHint))
        if (groupId == "_") add(listOf("-"))
        val s = subject?.trim().orEmpty()
        if (s.isNotEmpty()) {
            add(listOf("title", s))
            add(listOf("subject", s))
        }
    }

    /**
     * Tags for a kind:1111 reply. Uppercase E/K/P carry the root scope (every reply has them, so
     * readers thread by `E`); the group `h` (and `-` for `_`) tag scopes it. The lowercase parent
     * triple e/k/p is added ONLY for a nested reply (parent != root): for a top-level reply the
     * parent IS the root, so e/k/p would just duplicate E/K/P and inflate the indexable-tag count,
     * which some NIP-29 relays reject ("blocked: too many indexable tags").
     */
    fun reply(
        groupId: String,
        relayHint: String?,
        root: NostrGroupClient.NostrMessage,
        parent: NostrGroupClient.NostrMessage,
    ): List<List<String>> = buildList {
        add(listOfNotNull("h", groupId, relayHint))
        if (groupId == "_") add(listOf("-"))
        val hint = relayHint ?: ""
        add(listOf("E", root.id, hint, root.pubkey))
        add(listOf("K", root.kind.toString()))
        add(listOf("P", root.pubkey))
        if (parent.id != root.id) {
            add(listOf("e", parent.id, hint, parent.pubkey))
            add(listOf("k", parent.kind.toString()))
            add(listOf("p", parent.pubkey))
        }
    }
}
