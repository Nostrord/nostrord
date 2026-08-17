package org.nostr.nostrord.ui.screens.dm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.nostr.nostrord.network.managers.DmMessage
import org.nostr.nostrord.utils.getDateLabel

/**
 * NIP-01 JSON of the decrypted kind:14 rumor for the context menu's "Copy event JSON".
 * Messages cached before rumorJson existed fall back to a reconstruction from the
 * reduced model (tags survive only as the recipient `p` on own messages).
 */
/** [eventJson] pretty-printed for the "View source" modal. */
fun DmMessage.prettyEventJson(): String = runCatching {
    dmPrettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(eventJson()))
}.getOrElse { eventJson() }

private val dmPrettyJson = Json { prettyPrint = true }

fun DmMessage.eventJson(): String = rumorJson ?: buildJsonObject {
    put("id", id)
    put("pubkey", senderPubkey)
    put("created_at", createdAt)
    put("kind", kind)
    putJsonArray("tags") {
        if (mine) {
            addJsonArray {
                add("p")
                add(peerPubkey)
            }
        }
    }
    put("content", content)
}.toString()

/** Consecutive same-side bubbles within this window render as one visual group. */
const val DM_GROUP_WINDOW_SECONDS = 5 * 60L

/**
 * Render list for a DM thread, shared by the Compose and web UIs: a day separator
 * whenever the calendar day changes, and bubbles with their group edges resolved
 * (tight spacing inside a group; every bubble carries its own clock).
 */
sealed class DmChatItem {
    data class DateSeparator(val label: String) : DmChatItem()

    data class Message(
        val message: DmMessage,
        /** Opens a visual group: gets the full inter-group spacing above it. */
        val firstInGroup: Boolean,
        /** Closes a visual group. */
        val lastInGroup: Boolean,
    ) : DmChatItem()
}

fun buildDmChatItems(messages: List<DmMessage>): List<DmChatItem> {
    val sorted = messages.sortedBy { it.createdAt }
    val items = mutableListOf<DmChatItem>()
    sorted.forEachIndexed { i, m ->
        val prev = sorted.getOrNull(i - 1)
        val next = sorted.getOrNull(i + 1)
        val label = getDateLabel(m.createdAt)
        val newDay = prev == null || getDateLabel(prev.createdAt) != label
        if (newDay) items += DmChatItem.DateSeparator(label)
        val firstInGroup =
            newDay ||
                prev == null ||
                prev.mine != m.mine ||
                m.createdAt - prev.createdAt > DM_GROUP_WINDOW_SECONDS
        val lastInGroup =
            next == null ||
                next.mine != m.mine ||
                next.createdAt - m.createdAt > DM_GROUP_WINDOW_SECONDS ||
                getDateLabel(next.createdAt) != label
        items += DmChatItem.Message(m, firstInGroup, lastInGroup)
    }
    return items
}
