package org.nostr.nostrord.nostr

/**
 * NIP-84 highlights (kind 9802): a verbatim excerpt of a source, optionally annotated with the
 * highlighter's own comment and attributed to the URL it was taken from.
 */
object Nip84 {
    const val KIND = 9802

    data class Highlight(
        /** The highlighted excerpt (the event content), rendered as a quote block. */
        val excerpt: String,
        /** The highlighter's own note about the excerpt (`comment` tag). */
        val comment: String?,
        /** Source URL (`r` tag) when the highlight came from the web. */
        val sourceUrl: String?,
    ) {
        /** Source URL without scheme or trailing slash, e.g. `x.com/varosbr/status/123`. */
        val sourceLabel: String?
            get() =
                sourceUrl
                    ?.removePrefix("https://")
                    ?.removePrefix("http://")
                    ?.trimEnd('/')
                    ?.takeIf { it.isNotEmpty() }
    }

    fun isHighlight(kind: Int?): Boolean = kind == KIND

    fun parse(
        content: String,
        tags: List<List<String>>,
    ): Highlight = Highlight(
        excerpt = content.trim(),
        comment = tags.tagValue("comment"),
        sourceUrl = tags.tagValue("r")?.takeIf { it.startsWith("http://") || it.startsWith("https://") },
    )

    private fun List<List<String>>.tagValue(name: String): String? = firstOrNull { it.size >= 2 && it[0] == name }
        ?.get(1)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
