package org.nostr.nostrord.ui.mentions

import org.nostr.nostrord.utils.normalizeForSearch

/**
 * The mention being typed in a composer: the trigger character, the query after it, and the
 * trigger's index in the text.
 */
data class MentionCtx(
    val trigger: Char,
    val query: String,
    val start: Int,
) {
    /** Index just past the typed token: where a replacement ends. */
    val end: Int get() = start + 1 + query.length
}

/** Text and caret position after a suggestion replaces the typed token. */
data class MentionInsertion(
    val text: String,
    val cursor: Int,
)

/**
 * Trigger detection, candidate filtering and insertion for the `@user` / `%group` autocomplete.
 * Shared by every composer on both UIs (chat, thread reply, new thread) so the two render trees
 * agree on when a mention is active and what typing a suggestion produces.
 */
object MentionAutocomplete {
    /** Mentions a person; resolved to `nostr:npub…` plus a `p` tag when the event is built. */
    const val USER = '@'

    /** Mentions a group; resolved to `nostr:naddr…` in the content by the composer. */
    const val GROUP = '%'

    /** Rows a popup shows at once; also the keyboard-navigation range. */
    const val MAX_SUGGESTIONS = 8

    /**
     * The mention active at [cursor]: the nearest trigger that starts a word and runs unbroken
     * (no whitespace) up to the caret. Null when there is none.
     */
    fun detect(text: String, cursor: Int): MentionCtx? {
        if (cursor <= 0 || cursor > text.length) return null
        val before = text.substring(0, cursor)
        val index = maxOf(before.lastIndexOf(USER), before.lastIndexOf(GROUP))
        if (index < 0) return null
        val charBefore = before.getOrNull(index - 1)
        if (charBefore != null && !charBefore.isWhitespace()) return null
        val query = before.substring(index + 1)
        if (query.any { it.isWhitespace() }) return null
        return MentionCtx(before[index], query, index)
    }

    /**
     * Keep [previous] alive when its trigger still runs unbroken to the end of [text], re-reading
     * the query from the text. The Android IME reports a stale cursor for a frame while composing,
     * which makes [detect] miss a mention mid-word and flicker the popup closed.
     */
    fun sustain(text: String, previous: MentionCtx?): MentionCtx? {
        val ctx = previous ?: return null
        if (ctx.start < 0 || ctx.start >= text.length || text[ctx.start] != ctx.trigger) return null
        val charBefore = text.getOrNull(ctx.start - 1)
        if (charBefore != null && !charBefore.isWhitespace()) return null
        val token = text.substring(ctx.start + 1)
        if (token.any { it.isWhitespace() }) return null
        return ctx.copy(query = token)
    }

    /** [detect] at the caret, falling back to [sustain] for the stale-cursor frame. */
    fun track(text: String, cursor: Int, previous: MentionCtx?): MentionCtx? = detect(text, cursor) ?: sustain(text, previous)

    /**
     * Candidates matching [query], capped at [limit]. [keys] returns the searchable strings of an
     * item (display name, pubkey, group id); an empty query offers the head of the list.
     */
    fun <T> filter(
        items: List<T>,
        query: String,
        limit: Int = MAX_SUGGESTIONS,
        keys: (T) -> List<String>,
    ): List<T> {
        if (query.isEmpty()) return items.take(limit)
        val needle = query.normalizeForSearch()
        return items
            .filter { item -> keys(item).any { it.normalizeForSearch().contains(needle) } }
            .take(limit)
    }

    /**
     * Replace the typed token with `<trigger><label> `, leaving the caret after the space. The
     * remainder is left-trimmed so completing mid-sentence never leaves a double space.
     */
    fun insert(text: String, ctx: MentionCtx, label: String): MentionInsertion {
        val before = text.substring(0, ctx.start.coerceIn(0, text.length))
        val after = text.substring(ctx.end.coerceIn(0, text.length)).trimStart()
        val token = "${ctx.trigger}$label "
        return MentionInsertion(before + token + after, before.length + token.length)
    }
}
