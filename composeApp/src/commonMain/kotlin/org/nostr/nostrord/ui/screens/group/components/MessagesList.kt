package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.ui.components.chat.DateSeparator
import org.nostr.nostrord.ui.components.chat.MessageItem
import org.nostr.nostrord.ui.components.chat.SystemEventItem
import org.nostr.nostrord.ui.screens.group.model.ChatItem
import org.nostr.nostrord.ui.theme.NostrordColors

@Composable
fun MessagesList(
    chatItems: List<ChatItem>,
    userMetadata: Map<String, UserMetadata>,
    isJoined: Boolean
) {
    val listState = rememberLazyListState()

    if (chatItems.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "No messages yet",
                color = NostrordColors.TextSecondary,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                if (isJoined) "Be the first to send a message!" else "Join the group to participate!",
                color = NostrordColors.TextMuted,
                style = MaterialTheme.typography.bodySmall
            )
        }
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(chatItems, key = { item ->
                    when (item) {
                        is ChatItem.DateSeparator -> "date_${item.date}"
                        is ChatItem.SystemEvent -> "system_${item.id}"
                        is ChatItem.Message -> "msg_${item.message.id}"
                    }
                }) { item ->
                    when (item) {
                        is ChatItem.DateSeparator -> DateSeparator(item.date)
                        is ChatItem.SystemEvent -> SystemEventItem(
                            pubkey = item.pubkey,
                            action = item.action,
                            createdAt = item.createdAt,
                            metadata = userMetadata[item.pubkey]
                        )
                        is ChatItem.Message -> MessageItem(
                            message = item.message,
                            metadata = userMetadata[item.message.pubkey]
                        )
                    }
                }
            }

            VerticalScrollbar(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                adapter = rememberScrollbarAdapter(listState)
            )
        }

        LaunchedEffect(chatItems.size) {
            if (chatItems.isNotEmpty()) {
                listState.scrollToItem(chatItems.lastIndex)
            }
        }
    }
}
