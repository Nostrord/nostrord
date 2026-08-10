package org.nostr.nostrord.ui.text

/**
 * Markdown media embeds written by other clients: `![alt](url)` and the optional title form
 * `![alt](url "title")`. Both UIs already render a bare media url inline, so the syntax is
 * unwrapped to the url before parsing instead of carrying an image node through every renderer.
 * Alt text and title are dropped (no renderer shows them).
 */
object MarkdownMedia {
    /**
     * Only http(s) and inline `data:image/` targets match, and the url stops at whitespace or a
     * parenthesis, so a stray `![` in ordinary text is left alone.
     *
     * Every literal bracket is escaped, including the closing `\]`: Kotlin/JS builds this with the
     * unicode flag, where a lone `]` is a SyntaxError and the whole pattern fails to construct.
     */
    private val imageEmbed =
        Regex(
            """!\[[^\]\n]*\]\([ \t]*((?:https?://|data:image/)[^\s()]+)(?:[ \t]+"[^"\n]*")?[ \t]*\)""",
            RegexOption.IGNORE_CASE,
        )

    /** Replaces every `![alt](url)` embed in [content] with its bare url. */
    fun unwrapImages(content: String): String {
        if (!content.contains("![")) return content
        return imageEmbed.replace(content) { it.groupValues[1] }
    }
}
