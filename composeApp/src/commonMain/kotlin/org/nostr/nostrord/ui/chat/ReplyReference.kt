package org.nostr.nostrord.ui.chat

import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.nostr.Nip19

private val NOSTR_EVENT_REGEX = Regex("""nostr:(nevent1[a-zA-Z0-9]+|note1[a-zA-Z0-9]+|naddr1[a-zA-Z0-9]+)""")

/** Same pointer, anchored to the start of the body. */
private val LEADING_POINTER = Regex("""^\s*nostr:(nevent1[a-zA-Z0-9]+|note1[a-zA-Z0-9]+|naddr1[a-zA-Z0-9]+)""")

/** Event id (hex) or `kind:pubkey:identifier` coordinate a bech32 entity points at. */
private fun referenceOf(bech32: String): String? = when (val entity = Nip19.decode(bech32)) {
    is Nip19.Entity.Note -> entity.eventId
    is Nip19.Entity.Nevent -> entity.eventId
    is Nip19.Entity.Naddr -> "${entity.kind}:${entity.pubkey}:${entity.identifier}"
    else -> null
}

/** Event ids / coordinates embedded in [content] via nostr:nevent, nostr:note or nostr:naddr. */
fun extractEmbeddedEventIds(content: String): Set<String> = NOSTR_EVENT_REGEX
    .findAll(content)
    .mapNotNull { referenceOf(it.groupValues[1]) }
    .toSet()

/**
 * Body of [content] once an opening `nostr:` pointer to [parentId] is eaten.
 *
 * A NIP-C7 reply repeats its parent as a pointer ahead of the text (`nostr:nevent1…\n\ntext` next to
 * the "q" tag), which the reply quote already shows. Null when there is nothing to eat: the body
 * opens with something else, the pointer aims elsewhere, or the pointer is the whole body, which is
 * a share and would render blank.
 */
fun bodyWithoutLeadingPointer(content: String, parentId: String): String? {
    val match = LEADING_POINTER.find(content) ?: return null
    if (referenceOf(match.groupValues[1]) != parentId) return null
    return content.substring(match.range.last + 1).trimStart().ifBlank { null }
}

/**
 * Parent event id of a reply, or null when there is no reply tag or the reference already renders as
 * an inline quote card.
 *
 * Tag order: "q" (NIP-C7), the NIP-10 `["e", id, relay, "reply"]` marker, then a plain "e". An naddr
 * coordinate stays inline: a reply quote can't render an addressable event.
 */
fun getReplyParentId(message: NostrGroupClient.NostrMessage): String? {
    if (message.kind != 9) return null

    val embeddedEventIds = extractEmbeddedEventIds(message.content)

    // A pointer anywhere but the head of the body is a deliberate inline quote: its card already
    // carries the reference, so no reply quote goes on top of it.
    fun rendersAsInlineQuote(reference: String) = reference in embeddedEventIds && bodyWithoutLeadingPointer(message.content, reference) == null

    val qTag = message.tags.find { it.size >= 2 && it[0] == "q" && it[1].isNotBlank() }
    if (qTag != null) {
        val reference = qTag[1]
        if (rendersAsInlineQuote(reference)) return null
        if (reference.length == 64 && reference.all { it.isLetterOrDigit() }) return reference
        if (reference.contains(":")) return null
    }

    val replyMarkerTag = message.tags.find { it.size >= 4 && it[0] == "e" && it[3] == "reply" }
    if (replyMarkerTag != null) {
        val eventId = replyMarkerTag[1]
        if (rendersAsInlineQuote(eventId)) return null
        if (eventId.length == 64) return eventId
    }

    val eTag = message.tags.find { it.size >= 2 && it[0] == "e" && it[1].length == 64 }
    if (eTag != null) {
        val eventId = eTag[1]
        if (rendersAsInlineQuote(eventId)) return null
        return eventId
    }

    return null
}

/** [content] as the chat renders it: an opening pointer to [parentId] is eaten. */
fun messageBody(content: String, parentId: String?): String = parentId?.let { bodyWithoutLeadingPointer(content, it) } ?: content

/** [message] body as the chat renders it. For previews, where the parent id isn't at hand. */
fun messageBody(message: NostrGroupClient.NostrMessage): String = messageBody(message.content, getReplyParentId(message))
