package org.nostr.nostrord.ui.text

/**
 * Inline marker rules shared by the Compose and web message renderers, so the
 * two render trees can't drift on what counts as formatting.
 *
 * Every marker here is a character that also occurs literally in ordinary chat
 * text - shell globs, operators, arithmetic, code names. Three guards keep those
 * literal uses intact, matching CommonMark's flanking-delimiter-run rule:
 *
 * 1. the opening marker is not followed by whitespace (`1 * 2 * 3` stays plain);
 * 2. the closing marker is not preceded by whitespace;
 * 3. the content is at least one character and holds no newline, so a stray
 *    marker cannot swallow the rest of the message.
 *
 * Underscore adds a fourth guard: it only marks emphasis outside a word, so
 * identifiers keep their underscores (`nip44_decrypt_batch`, `snake_case`,
 * `:emoji_shortcode:`). CommonMark draws the same line - only `*` does intraword
 * emphasis - because code names are underscore-heavy.
 *
 * `x**2 and y**2` still bolds, as it does under CommonMark: `**` is legal
 * intraword. Code spans are the escape hatch, and both renderers claim backticks
 * before reaching any of these patterns.
 *
 * Group 1 is the marked-up text. Patterns stay as strings so they can be
 * embedded in a larger alternation (the web renderer scans every inline marker
 * in one pass).
 */
object MarkdownEmphasis {
    /** Opener guard: no whitespace after the marker. */
    private const val OPEN = "(?!\\s)"

    /** Closer guard: no whitespace before the marker. */
    private const val CLOSE = "(?<!\\s)"

    /**
     * Trailing guard: the closing marker sits at an outer boundary. Without it a
     * lone `*` finds a partner anywhere later in the message - `rm *.kt and *.js`
     * next to an `x**2` pairs the two survivors and bolds everything between.
     */
    private const val AFTER = "(?![^\\s.,!?;:)\\]}])"

    /** Single-line content, at least one character. */
    private const val TEXT = "([^\\n]+?)"

    /** `*bold*`. Content cannot hold `*`, so `**bold**` is left to the double form. */
    const val BOLD_PATTERN = "\\*$OPEN([^*\\n]+?)$CLOSE\\*$AFTER"

    /** `**bold**`. No trailing guard: `x**2 and y**2` bolds, as it does in CommonMark. */
    const val BOLD_DOUBLE_PATTERN = "\\*\\*$OPEN$TEXT$CLOSE\\*\\*"

    /** `_italic_`, additionally word-bounded (see the class doc). */
    const val ITALIC_UNDERSCORE_PATTERN = "(?<![^\\s])_([^_\\n]+)_$AFTER"

    /** `~~strikethrough~~`. */
    const val STRIKETHROUGH_PATTERN = "~~$OPEN$TEXT$CLOSE~~"

    /** `||spoiler||`. The guard is what keeps `if (a || b || c)` readable. */
    const val SPOILER_PATTERN = "\\|\\|$OPEN$TEXT$CLOSE\\|\\|"

    val boldRegex = Regex(BOLD_PATTERN)
    val boldDoubleRegex = Regex(BOLD_DOUBLE_PATTERN)
    val italicUnderscoreRegex = Regex(ITALIC_UNDERSCORE_PATTERN)
    val strikethroughRegex = Regex(STRIKETHROUGH_PATTERN)
    val spoilerRegex = Regex(SPOILER_PATTERN)

    /**
     * All inline markers in one alternation, longest markers first so `**` wins
     * over `*` and the doubled `~~` / `||` aren't split into single characters.
     */
    val inlineMarkerRegex =
        Regex(
            listOf(
                STRIKETHROUGH_PATTERN,
                SPOILER_PATTERN,
                BOLD_DOUBLE_PATTERN,
                BOLD_PATTERN,
                ITALIC_UNDERSCORE_PATTERN,
            ).joinToString("|"),
        )
}
