package org.nostr.nostrord.ui.text

/**
 * Whether a `nostr:` reference is the only thing on its line. Alone on a line it renders as a rich
 * block (profile card, group card, quote); mixed with text it stays a compact inline chip. The rule
 * is per line and not per message, so an invite ("You've been added to X" + the naddr underneath)
 * still gets the card.
 */
object StandaloneRef {
    fun isStandalone(content: String, token: String): Boolean {
        val bech32 = token.removePrefix("nostr:")
        return content.lineSequence().any { line ->
            val trimmed = line.trim()
            trimmed == bech32 || trimmed == "nostr:$bech32"
        }
    }
}
