package org.nostr.nostrord.ui.text

/**
 * Whitespace rule for text runs that touch a block embed (image, video, audio, quoted event, code
 * block, blockquote).
 *
 * A block embed opens and closes its own line and carries its own vertical spacing, so the newline
 * the author typed around it would render as an extra empty line - a wide gap between the media and
 * the sentence under it. One newline per touching side is absorbed by the block; anything typed
 * beyond that is a deliberate blank line and survives.
 *
 * Shared by the Compose renderer (MessageContent) and the web one (renderMessageContent) so the two
 * space embeds identically.
 */
object BlockEmbedText {
    /** Text run that ends right before a block embed. */
    fun trimBefore(text: String): String = if (text.endsWith("\r\n")) text.dropLast(2) else text.removeSuffix("\n")

    /** Text run that starts right after a block embed. */
    fun trimAfter(text: String): String = if (text.startsWith("\r\n")) text.drop(2) else text.removePrefix("\n")
}
