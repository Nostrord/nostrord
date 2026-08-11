package org.nostr.nostrord.web.components

import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.mentions.MentionAutocomplete
import org.nostr.nostrord.ui.mentions.MentionCtx
import org.nostr.nostrord.ui.screens.group.MentionableGroup
import org.nostr.nostrord.utils.shortNpub
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

/** Profile name for [pubkey], else a short npub. The label a mention is typed and rendered as. */
fun mentionDisplayName(pubkey: String, meta: UserMetadata?): String = meta?.displayName?.takeIf { it.isNotBlank() }
    ?: meta?.name?.takeIf { it.isNotBlank() }
    ?: shortNpub(pubkey)

/** A single autocomplete row: how to show it, its subtitle, and the `nostr:` ref it resolves to. */
data class MentionMatch(
    val label: String,
    val picture: String?,
    val seed: String,
    val group: Boolean,
    val ref: String,
    val sub: String,
)

/** Rows a web composer popup shows at once. */
const val MENTION_MATCH_LIMIT = 6

/**
 * Suggestions for the mention being typed: members for `@`, groups for `%`, empty when idle.
 * Shared by every web composer (chat, thread reply, new thread) so all three rank and label alike.
 */
fun mentionMatches(
    ctx: MentionCtx?,
    members: List<String>,
    userMetadata: Map<String, UserMetadata>,
    groups: List<MentionableGroup>,
    relayPubkeyOf: (String) -> String?,
): List<MentionMatch> = when (ctx?.trigger) {
    MentionAutocomplete.USER ->
        MentionAutocomplete
            .filter(members, ctx.query, MENTION_MATCH_LIMIT) { pk -> listOf(mentionDisplayName(pk, userMetadata[pk]), pk) }
            .map { pk ->
                MentionMatch(
                    label = mentionDisplayName(pk, userMetadata[pk]),
                    picture = userMetadata[pk]?.picture,
                    seed = pk,
                    group = false,
                    ref = "nostr:" + Nip19.encodeNpub(pk),
                    sub = shortNpub(pk) + pk.takeLast(4),
                )
            }

    MentionAutocomplete.GROUP ->
        MentionAutocomplete
            .filter(groups, ctx.query, MENTION_MATCH_LIMIT) { listOf(it.meta.name ?: it.meta.id, it.meta.id) }
            .map { mg ->
                // The naddr carries the mentioned group's OWN hosting relay (and that relay's
                // pubkey), not the current one, so following the mention navigates correctly.
                val host = mg.relayUrl.removePrefix("wss://").removePrefix("ws://").trimEnd('/')
                MentionMatch(
                    label = mg.meta.name ?: mg.meta.id,
                    picture = mg.meta.picture,
                    seed = mg.meta.id,
                    group = true,
                    ref = "nostr:" + Nip19.encodeNaddr(mg.meta.id, mg.relayUrl, 39000, relayPubkeyOf(mg.relayUrl)),
                    sub = host,
                )
            }

    else -> emptyList()
}

/** A run of composer text, flagged as a resolved mention (tinted) or plain. */
data class MentionSegment(
    val text: String,
    val mention: Boolean,
)

/**
 * Split [text] into plain / mention runs for a composer's colored mirror. Only the literal [tokens]
 * (`@alice`, `%my group`) that are resolved mentions are tinted, the same rule as native's
 * MentionVisualTransformation: it colors the chosen mentions, not every "@word".
 */
fun mentionSegments(text: String, tokens: Collection<String>): List<MentionSegment> {
    if (text.isEmpty()) return emptyList()
    val colored = BooleanArray(text.length)
    tokens.forEach { token ->
        if (token.isEmpty()) return@forEach
        var i = text.indexOf(token)
        while (i >= 0) {
            for (j in i until i + token.length) colored[j] = true
            i = text.indexOf(token, i + token.length)
        }
    }
    val segments = mutableListOf<MentionSegment>()
    var start = 0
    for (k in 1..text.length) {
        if (k == text.length || colored[k] != colored[start]) {
            segments.add(MentionSegment(text.substring(start, k), colored[start]))
            start = k
        }
    }
    return segments
}

/** The mirror's content: the draft with its resolved mentions wrapped in `.msg-mention`. */
fun ChildrenBuilder.mentionHighlight(text: String, tokens: Collection<String>) {
    mentionSegments(text, tokens).forEach { seg ->
        if (seg.mention) {
            span {
                className = ClassName("msg-mention")
                +seg.text
            }
        } else {
            +seg.text
        }
    }
}

external interface MentionPopupProps : Props {
    var matches: List<MentionMatch>

    /** Trigger being completed; picks the MEMBERS / GROUPS header. */
    var trigger: Char

    /** Highlighted row, driven by the composer's arrow keys and hover. */
    var selected: Int
    var onPick: (MentionMatch) -> Unit
    var onHover: (Int) -> Unit
}

/**
 * The `@user` / `%group` suggestion list. Absolutely positioned against the nearest positioned
 * ancestor (the composer area), so it floats above the composer instead of being clipped by it.
 */
val MentionPopup =
    FC<MentionPopupProps> { props ->
        if (props.matches.isEmpty()) return@FC
        div {
            className = ClassName("mention-popup")
            div {
                className = ClassName("mention-header")
                +(if (props.trigger == MentionAutocomplete.GROUP) "GROUPS" else "MEMBERS")
            }
            val sel = props.selected.coerceIn(0, props.matches.size - 1)
            props.matches.forEachIndexed { idx, mm ->
                div {
                    key = mm.ref
                    className = ClassName(if (idx == sel) "mention-row selected" else "mention-row")
                    // Keep the composer's focus and caret: the click must not blur the field.
                    onMouseDown = { e ->
                        e.preventDefault()
                        props.onPick(mm)
                    }
                    onMouseEnter = { props.onHover(idx) }
                    WebAvatar {
                        url = mm.picture
                        seed = mm.seed
                        this.name = mm.label
                        kind = if (mm.group) AvatarKind.GROUP else AvatarKind.USER
                        cls = "mention-avatar"
                    }
                    div {
                        className = ClassName("mention-text")
                        span {
                            className = ClassName("mention-name")
                            +mm.label
                        }
                        span {
                            className = ClassName("mention-key")
                            +mm.sub
                        }
                    }
                }
            }
        }
    }
