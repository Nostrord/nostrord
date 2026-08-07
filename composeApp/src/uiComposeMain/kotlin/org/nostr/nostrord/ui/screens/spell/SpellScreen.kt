package org.nostr.nostrord.ui.screens.spell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.ui.components.avatars.OptimizedSmallAvatar
import org.nostr.nostrord.ui.components.chat.MessageContent
import org.nostr.nostrord.ui.components.layout.PageHeader
import org.nostr.nostrord.ui.components.loading.EmptyState
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.utils.formatTimestamp

/**
 * A saved query rendered as a feed. Read-only: a spell has no composer, no membership and no
 * moderation, which is why it does not go through the group chat screen.
 */
@Composable
fun SpellScreen(
    spellId: String,
    onOpenDrawer: (() -> Unit)? = null,
) {
    val vm: SpellViewModel = viewModel(key = "spell:$spellId") { SpellViewModel(spellId = spellId) }
    val events by vm.events.collectAsState()
    val state by vm.loadingState.collectAsState()
    val error by vm.error.collectAsState()
    val metadata by AppModule.nostrRepository.userMetadata.collectAsState()

    val listState = rememberLazyListState()
    val atEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= events.lastIndex - 3
        }
    }
    LaunchedEffect(atEnd, state.canLoadMore) {
        if (atEnd && state.canLoadMore) vm.loadMore()
    }

    Column(Modifier.fillMaxSize().background(NostrordColors.Background)) {
        PageHeader(icon = Icons.Outlined.AutoAwesome, title = vm.title, onOpenDrawer = onOpenDrawer)

        when {
            error != null && events.isEmpty() ->
                EmptyState(message = error.orEmpty(), icon = Icons.Outlined.AutoAwesome)

            events.isEmpty() && state.isLoading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = NostrordColors.Primary)
                }

            events.isEmpty() ->
                EmptyState(message = "Nothing here yet. ${vm.subtitle}", icon = Icons.Outlined.AutoAwesome)

            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(events, key = { it.id }) { event ->
                    SpellEventRow(
                        event = event,
                        displayName = metadata[event.pubkey]?.displayName
                            ?: metadata[event.pubkey]?.name
                            ?: event.pubkey.take(8),
                        avatarUrl = metadata[event.pubkey]?.picture,
                    )
                }
                if (state.isLoading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.height(20.dp), color = NostrordColors.Primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpellEventRow(
    event: NostrGroupClient.NostrMessage,
    displayName: String,
    avatarUrl: String?,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        OptimizedSmallAvatar(
            imageUrl = avatarUrl,
            identifier = event.pubkey,
            displayName = displayName,
            size = 36.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = displayName,
                    color = NostrordColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatTimestamp(event.createdAt),
                    color = NostrordColors.TextMuted,
                )
            }
            MessageContent(content = event.content, tags = event.tags)
        }
    }
}
