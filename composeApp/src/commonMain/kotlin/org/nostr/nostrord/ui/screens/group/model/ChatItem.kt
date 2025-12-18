package org.nostr.nostrord.ui.screens.group.model

import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.utils.getDateLabel

sealed class ChatItem {
    data class DateSeparator(val date: String) : ChatItem()
    data class SystemEvent(
        val pubkey: String,
        val action: String,
        val createdAt: Long,
        val id: String
    ) : ChatItem()
    data class Message(val message: NostrGroupClient.NostrMessage) : ChatItem()
}

fun buildChatItems(messages: List<NostrGroupClient.NostrMessage>): List<ChatItem> {
    val items = mutableListOf<ChatItem>()
    var lastDate: String? = null

    messages.sortedBy { it.createdAt }.forEach { message ->
        val messageDate = getDateLabel(message.createdAt)

        if (messageDate != lastDate) {
            items.add(ChatItem.DateSeparator(messageDate))
            lastDate = messageDate
        }

        when (message.kind) {
            9 -> items.add(ChatItem.Message(message))
            9021 -> items.add(ChatItem.SystemEvent(
                pubkey = message.pubkey,
                action = "Joined",
                createdAt = message.createdAt,
                id = message.id
            ))
            9022 -> items.add(ChatItem.SystemEvent(
                pubkey = message.pubkey,
                action = "Left",
                createdAt = message.createdAt,
                id = message.id
            ))
        }
    }

    return items
}
