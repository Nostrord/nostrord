package org.nostr.nostrord.ui.text

/**
 * Inline emphasis rules shared by the Compose and web message renderers, so the
 * two render trees can't drift on what counts as a marker.
 */
object MarkdownEmphasis {
    /**
     * `_italic_`, word-bounded: the markers only emphasize when they sit outside
     * a word, so identifiers keep their underscores (`nip44_decrypt_batch`,
     * `snake_case`, `:emoji_shortcode:`). CommonMark draws the same line - only
     * `*` does intraword emphasis - because code names are underscore-heavy.
     *
     * Group 1 is the emphasized text. Kept as a pattern string so it can be
     * embedded in a larger alternation (the web renderer scans every inline
     * marker in one pass).
     */
    const val ITALIC_UNDERSCORE_PATTERN = "(?<![^\\s])_([^_\\n]+)_(?![^\\s.,!?;:])"

    val italicUnderscoreRegex = Regex(ITALIC_UNDERSCORE_PATTERN)
}
