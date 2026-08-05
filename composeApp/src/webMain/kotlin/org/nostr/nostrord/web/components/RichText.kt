package org.nostr.nostrord.web.components

import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.text.MarkdownEmphasis
import react.ChildrenBuilder
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.code
import react.dom.html.ReactHTML.em
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.strong
import web.cssom.ClassName

// Web links + NIP-27 entities in an about/description string. Mirrors the chat
// URL_REGEX in ChatScreen but without the data:image branch — bios don't embed
// base64 images. A bare bech32 needs a word boundary + min length so ordinary
// words aren't misread as entities.
private val ABOUT_REGEX =
    Regex(
        "(https?://[^\\s]+)" +
            "|(nostr:(?:npub1|nprofile1|nevent1|note1|naddr1)[0-9a-z]+)" +
            "|\\b((?:npub1|nprofile1|nevent1|note1|naddr1)[0-9a-z]{20,})",
    )

private fun aboutDisplayName(pubkey: String, meta: UserMetadata?): String = meta?.displayName?.takeIf { it.isNotBlank() }
    ?: meta?.name?.takeIf { it.isNotBlank() }
    ?: (Nip19.encodeNpub(pubkey).take(12) + "…")

/**
 * Pubkeys referenced as npub / nprofile inside [text], so a caller can prefetch
 * their metadata and show @display-name instead of a truncated npub. Mirrors
 * native RichAboutText's metadata-fetch pass.
 */
fun aboutMentionPubkeys(text: String): Set<String> = ABOUT_REGEX
    .findAll(text)
    .mapNotNull { m ->
        val token = m.value
        if (token.startsWith("http")) return@mapNotNull null
        when (val e = Nip19.decode(token.removePrefix("nostr:"))) {
            is Nip19.Entity.Npub -> e.pubkey
            is Nip19.Entity.Nprofile -> e.pubkey
            else -> null
        }
    }.toSet()

// Markdown subset mirrored from native MessageContentParser: *bold*, _italic_ (both
// guarded, see MarkdownEmphasis) and `inline code` (opaque: no entities inside).
private val INLINE_CODE_REGEX = Regex("`([^`]+)`")
private val BOLD_REGEX = MarkdownEmphasis.boldRegex
private val ITALIC_REGEX = MarkdownEmphasis.italicUnderscoreRegex

/**
 * Render an about / description string with clickable web links, NIP-27 mentions
 * and the markdown subset the chat understands (*bold*, _italic_, `code`),
 * mirroring native RichAboutText. http(s) URLs become links, npub / nprofile
 * become @name chips (resolved from [userMetadata], click → [onUser]), other
 * entities (note / nevent / naddr) link to njump.me, and everything else is
 * plain text. Newlines flow through (the host element needs `white-space:
 * pre-wrap`). Reuses the chat's `msg-link` / `msg-mention` / `msg-code` styles.
 */
fun ChildrenBuilder.renderAboutText(
    text: String,
    userMetadata: Map<String, UserMetadata>,
    onUser: (String) -> Unit,
) {
    var last = 0
    for (m in INLINE_CODE_REGEX.findAll(text)) {
        if (m.range.first > last) renderAboutMarkdown(text.substring(last, m.range.first), userMetadata, onUser)
        code {
            className = ClassName("msg-code")
            +m.groupValues[1]
        }
        last = m.range.last + 1
    }
    if (last < text.length) renderAboutMarkdown(text.substring(last), userMetadata, onUser)
}

private fun ChildrenBuilder.renderAboutMarkdown(
    text: String,
    userMetadata: Map<String, UserMetadata>,
    onUser: (String) -> Unit,
) {
    var last = 0
    for (m in BOLD_REGEX.findAll(text)) {
        if (m.range.first > last) renderAboutItalic(text.substring(last, m.range.first), userMetadata, onUser)
        strong { renderAboutEntities(m.groupValues[1], userMetadata, onUser) }
        last = m.range.last + 1
    }
    if (last < text.length) renderAboutItalic(text.substring(last), userMetadata, onUser)
}

private fun ChildrenBuilder.renderAboutItalic(
    text: String,
    userMetadata: Map<String, UserMetadata>,
    onUser: (String) -> Unit,
) {
    var last = 0
    for (m in ITALIC_REGEX.findAll(text)) {
        if (m.range.first > last) renderAboutEntities(text.substring(last, m.range.first), userMetadata, onUser)
        em { renderAboutEntities(m.groupValues[1], userMetadata, onUser) }
        last = m.range.last + 1
    }
    if (last < text.length) renderAboutEntities(text.substring(last), userMetadata, onUser)
}

private fun ChildrenBuilder.renderAboutEntities(
    text: String,
    userMetadata: Map<String, UserMetadata>,
    onUser: (String) -> Unit,
) {
    var last = 0
    for (m in ABOUT_REGEX.findAll(text)) {
        if (m.range.first > last) +text.substring(last, m.range.first)
        val token = m.value
        if (token.startsWith("http")) {
            // Trailing punctuation usually belongs to the sentence, not the URL.
            val url = token.trimEnd('.', ',', ')', '!', '?', ';', ':')
            a {
                className = ClassName("msg-link")
                href = url
                asDynamic().target = "_blank"
                rel = "noopener noreferrer"
                +url
            }
            if (url.length < token.length) +token.substring(url.length)
        } else {
            val bech = token.removePrefix("nostr:")
            when (val e = Nip19.decode(bech)) {
                is Nip19.Entity.Npub -> mention(e.pubkey, userMetadata, onUser)
                is Nip19.Entity.Nprofile -> mention(e.pubkey, userMetadata, onUser)
                is Nip19.Entity.Note, is Nip19.Entity.Nevent, is Nip19.Entity.Naddr ->
                    a {
                        className = ClassName("msg-link")
                        href = "https://njump.me/$bech"
                        asDynamic().target = "_blank"
                        rel = "noopener noreferrer"
                        +token
                    }
                else -> +token
            }
        }
        last = m.range.last + 1
    }
    if (last < text.length) +text.substring(last)
}

private fun ChildrenBuilder.mention(
    pubkey: String,
    userMetadata: Map<String, UserMetadata>,
    onUser: (String) -> Unit,
) {
    span {
        className = ClassName("msg-mention")
        onClick = { onUser(pubkey) }
        +"@${aboutDisplayName(pubkey, userMetadata[pubkey])}"
    }
}
