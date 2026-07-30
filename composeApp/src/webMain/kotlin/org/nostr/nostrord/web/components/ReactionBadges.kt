package org.nostr.nostrord.web.components

import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.utils.shortNpub
import react.ChildrenBuilder
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

/**
 * Reaction badge row under a message (`.msg-reactions`): one badge per emoji with the reactor
 * avatar stack and +N overflow, plus spinner badges for sends still in flight. Clicking a badge
 * reacts with (or toggles) that emoji. Shared by the chat message row and the thread messages.
 * Pending emojis already merged into [reactions] by the optimistic update are hidden so a
 * spinner badge never shows next to its real counterpart.
 */
/** NIP-25 "+"/"-" display as thumbs, never as a bare sign (parity with the native badges). */
private fun displayEmoji(emoji: String): String = when (emoji) {
    "+" -> "👍"
    "-" -> "👎"
    else -> emoji
}

/** Hover tooltip content: who reacted, capped so a popular badge doesn't build a huge string. */
private fun reactorNames(
    reactors: List<String>,
    userMetadata: Map<String, UserMetadata>,
): String {
    val names = reactors.take(MAX_TOOLTIP_NAMES).map { pk ->
        val meta = userMetadata[pk]
        meta?.displayName?.takeIf { it.isNotBlank() }
            ?: meta?.name?.takeIf { it.isNotBlank() }
            ?: shortNpub(pk)
    }
    val extra = reactors.size - MAX_TOOLTIP_NAMES
    return names.joinToString(", ") + if (extra > 0) " +$extra" else ""
}

private const val MAX_TOOLTIP_NAMES = 20

fun ChildrenBuilder.reactionBadges(
    reactions: Map<String, GroupManager.ReactionInfo>,
    pendingEmojis: Collection<String>,
    myPubkey: String?,
    userMetadata: Map<String, UserMetadata>,
    onReact: (String) -> Unit,
) {
    val visiblePending = pendingEmojis.filter { it !in reactions }
    if (reactions.isEmpty() && visiblePending.isEmpty()) return
    div {
        className = ClassName("msg-reactions")
        reactions.forEach { (emoji, info) ->
            val mine = myPubkey != null && myPubkey in info.reactors
            button {
                className = ClassName(if (mine) "reaction-badge mine" else "reaction-badge")
                // Desktop hover shows who reacted (mobile already stacks the reactor avatars).
                title = reactorNames(info.reactors, userMetadata)
                onClick = { onReact(emoji) }
                val emojiUrl = info.emojiUrl
                if (!emojiUrl.isNullOrBlank()) {
                    img {
                        className = ClassName("reaction-emoji")
                        src = emojiUrl
                        alt = emoji
                    }
                } else {
                    +displayEmoji(emoji)
                }
                // Stacked avatars of who reacted (up to 3, overlapping), then +N overflow.
                div {
                    className = ClassName("reaction-avatars")
                    info.reactors.take(3).forEach { reactor ->
                        val meta = userMetadata[reactor]
                        WebAvatar {
                            url = meta?.picture
                            seed = reactor
                            this.name = meta?.displayName?.takeIf { it.isNotBlank() }
                                ?: meta?.name?.takeIf { it.isNotBlank() }
                                ?: shortNpub(reactor)
                            cls = "reaction-avatar"
                        }
                    }
                }
                if (info.reactors.size > 3) {
                    span {
                        className = ClassName("reaction-count")
                        +"+${info.reactors.size - 3}"
                    }
                }
            }
        }
        visiblePending.forEach { emoji ->
            div {
                className = ClassName("reaction-badge pending")
                +displayEmoji(emoji)
                span { className = ClassName("reaction-spinner") }
            }
        }
    }
}
