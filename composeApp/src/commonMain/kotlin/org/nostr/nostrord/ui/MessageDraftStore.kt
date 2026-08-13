package org.nostr.nostrord.ui

import androidx.compose.runtime.Immutable

/**
 * An unsent composer draft for one group: the text plus the resolved @user / %group
 * mention maps. The mention-map values are platform-encoded strings (the web stores the
 * `nostr:` ref; native stores an encoded GroupInfo), so this type stays platform-agnostic.
 */
@Immutable
data class MessageDraft(
    val text: String = "",
    val mentions: Map<String, String> = emptyMap(),
    val groupMentions: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = text.isBlank() && mentions.isEmpty() && groupMentions.isEmpty()
}

/**
 * Per-chat composer drafts kept in memory for the session, so switching groups and
 * coming back preserves what you were typing.
 *
 * Keyed by [org.nostr.nostrord.utils.groupKey]: the same group id on two relays is two
 * chats, and one draft must not surface in the other's composer.
 *
 * Writes are plain map mutations (no StateFlow, no recomposition / re-render), so callers
 * can persist on every keystroke for free — this never triggers a chat re-render and so
 * does not reintroduce the composer input lag the draft state was split out to fix.
 */
class MessageDraftStore {
    private val drafts = mutableMapOf<String, MessageDraft>()

    fun get(chatKey: String): MessageDraft = drafts[chatKey] ?: MessageDraft()

    fun setText(chatKey: String, text: String) = update(chatKey) { it.copy(text = text) }

    fun setMentions(chatKey: String, mentions: Map<String, String>) = update(chatKey) { it.copy(mentions = mentions) }

    fun setGroupMentions(chatKey: String, groupMentions: Map<String, String>) = update(chatKey) { it.copy(groupMentions = groupMentions) }

    fun clear(chatKey: String) {
        drafts.remove(chatKey)
    }

    private inline fun update(chatKey: String, transform: (MessageDraft) -> MessageDraft) {
        val updated = transform(get(chatKey))
        if (updated.isEmpty) drafts.remove(chatKey) else drafts[chatKey] = updated
    }
}
