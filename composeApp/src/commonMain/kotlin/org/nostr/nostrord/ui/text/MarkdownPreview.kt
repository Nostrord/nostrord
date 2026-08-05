package org.nostr.nostrord.ui.text

/**
 * Stands in for spoiler text in a plain-text preview. The chat hides a spoiler
 * visually and keeps it one tap away; a notification row and an OS notification
 * body have neither the blur nor the tap, so the content is dropped outright.
 */
const val SPOILER_MASK = "▮▮▮"

/** Code fence, with the optional language tag dropped along with the backticks. */
private val CODE_FENCE_REGEX = Regex("```(?:[A-Za-z0-9_+-]*\n)?([\\s\\S]*?)```")

private val INLINE_CODE_REGEX = Regex("`([^`\n]+)`")

/** Emphasis nests (`**bold with _italic_**`), so unwrapping runs until stable. */
private const val MAX_UNWRAP_PASSES = 4

/**
 * Flatten chat markdown to plain text for a notification preview: markers are
 * dropped and their text kept, except spoilers, which are replaced by
 * [SPOILER_MASK] so a hidden message never surfaces on a lock screen.
 *
 * Idempotent, so it is safe both where the preview is built and where an already
 * persisted one is rendered.
 */
fun flattenMarkdownForPreview(text: String): String {
    if (text.isEmpty()) return text

    var out = CODE_FENCE_REGEX.replace(text) { it.groupValues[1] }
    out = INLINE_CODE_REGEX.replace(out) { it.groupValues[1] }
    // Before unwrapping, so spoiler text is discarded rather than exposed by an
    // outer marker being stripped first.
    out = MarkdownEmphasis.spoilerRegex.replace(out, SPOILER_MASK)

    repeat(MAX_UNWRAP_PASSES) {
        val next = unwrapEmphasis(out)
        if (next == out) return out
        out = next
    }
    return out
}

private fun unwrapEmphasis(text: String): String {
    var out = MarkdownEmphasis.boldDoubleRegex.replace(text) { it.groupValues[1] }
    out = MarkdownEmphasis.boldRegex.replace(out) { it.groupValues[1] }
    out = MarkdownEmphasis.strikethroughRegex.replace(out) { it.groupValues[1] }
    return MarkdownEmphasis.italicUnderscoreRegex.replace(out) { it.groupValues[1] }
}
