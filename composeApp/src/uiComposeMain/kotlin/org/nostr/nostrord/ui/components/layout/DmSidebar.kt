package org.nostr.nostrord.ui.components.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.components.avatars.OptimizedSmallAvatar
import org.nostr.nostrord.ui.components.forms.AppSearchField
import org.nostr.nostrord.ui.components.forms.InputSize
import org.nostr.nostrord.ui.navigation.DmRoute
import org.nostr.nostrord.ui.screens.dm.DmViewModel
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Second column on the DM section (prototype DMSidebar, obelisk-style): header with
 * search and the conversation list. The Follows / Others tabs (follows vs message
 * requests) live in DmConversationList so they travel to the mobile page too; searching a
 * valid npub/hex opens that conversation. Mirrors the web web/DmSidebar.
 */
@Composable
fun DmSidebar(
    onOpenConversation: (DmRoute) -> Unit,
    activePubkey: String? = null,
) {
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val parsed = Nip19.parsePubkeyInput(query) as? Nip19.PubkeyParse.Ok

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(start = Spacing.lg, end = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Direct messages",
                color = NostrordColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                searchOpen = !searchOpen
                query = ""
            }) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = if (searchOpen) NostrordColors.TextPrimary else NostrordColors.TextMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        HorizontalDivider(color = NostrordColors.Divider)

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (searchOpen) {
                AppSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search by name, nip-05, npub or hex",
                    size = InputSize.Compact,
                    autoFocus = true,
                    // Trailing X dismisses the whole box (and clears), like the web searchInput.
                    trailing = {
                        IconButton(
                            onClick = {
                                searchOpen = false
                                query = ""
                            },
                            modifier = Modifier.size(20.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = NostrordColors.TextMuted,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    },
                    onEscape = {
                        searchOpen = false
                        query = ""
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                )
                // A pasted npub/hex starts that conversation right away.
                if (parsed != null) {
                    Text(
                        "START A CONVERSATION",
                        color = NostrordColors.TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    )
                    StartConversationRow(pubkeyHex = parsed.hex) {
                        onOpenConversation(DmRoute(parsed.hex))
                    }
                }
            }

            DmConversationList(
                onOpenConversation = onOpenConversation,
                activePubkey = activePubkey,
                onStartConversation = { searchOpen = true },
            )
        }
    }
}

/**
 * The DM conversation list plus its empty state, shared by [DmSidebar] (desktop column) and the
 * mobile DM page (which has no visible sidebar). The caller provides the scroll container. Mirrors
 * the web web/DmSidebar DmConversationList.
 */
@Composable
fun DmConversationList(
    onOpenConversation: (DmRoute) -> Unit,
    activePubkey: String? = null,
    onStartConversation: (() -> Unit)? = null,
) {
    val dmVm = viewModel { DmViewModel(AppModule.nostrRepository) }
    var tab by remember { mutableStateOf(0) }
    val follows by dmVm.followsConversations.collectAsState()
    val others by dmVm.othersConversations.collectAsState()
    val othersUnread by dmVm.othersUnread.collectAsState()
    val userMetadata by dmVm.userMetadata.collectAsState()
    val unreadByPeer by dmVm.unreadByPeer.collectAsState()
    val conversations = if (tab == 0) follows else others

    fun nameOf(pubkey: String): String {
        val meta = userMetadata[pubkey]
        return meta?.displayName?.takeIf { it.isNotBlank() }
            ?: meta?.name?.takeIf { it.isNotBlank() }
            ?: (runCatching { Nip19.encodeNpub(pubkey) }.getOrDefault(pubkey).take(12) + "…")
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Follows = peers you follow, Others = message requests; the Others tab badges its unread.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf("Follows", "Others").forEachIndexed { index, label ->
                val selected = tab == index
                Row(
                    modifier =
                    Modifier
                        .clip(NostrordShapes.shapeSmall)
                        .clickable { tab = index }
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Text(
                        label,
                        color = if (selected) NostrordColors.Primary else NostrordColors.TextMuted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (index == 1 && othersUnread > 0) {
                        Box(
                            modifier =
                            Modifier
                                .clip(CircleShape)
                                .background(NostrordColors.Primary)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        ) {
                            Text(
                                if (othersUnread > 99) "99+" else "$othersUnread",
                                color = androidx.compose.ui.graphics.Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = NostrordColors.Divider)

        if (conversations.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier =
                    Modifier
                        .size(64.dp)
                        .clip(NostrordShapes.shapeXLarge)
                        .background(NostrordColors.BackgroundFloating),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (tab == 0) "✉️" else "📥", fontSize = 30.sp)
                }
                Spacer(modifier = Modifier.height(Spacing.md))
                Text(
                    if (tab == 0) "No messages yet" else "No message requests",
                    color = NostrordColors.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    if (tab == 0) {
                        "Your direct messages are end-to-end encrypted. Start one with someone you follow."
                    } else {
                        "Messages from people you don't follow show up here."
                    },
                    color = NostrordColors.TextMuted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 320.dp),
                )
                if (tab == 0) {
                    onStartConversation?.let { start ->
                        Spacer(modifier = Modifier.height(Spacing.md))
                        Text(
                            "Start a conversation",
                            color = NostrordColors.Primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier =
                            Modifier
                                .clip(NostrordShapes.shapeSmall)
                                .clickable { start() }
                                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                        )
                    }
                }
            }
        } else {
            conversations.forEach { convo ->
                // Keyed on the peer: without it the rows are positional, so switching tab
                // (Follows <-> Others) reuses each slot for a different person and carries that
                // row's retained state - its avatar among it - onto the new identity.
                key(convo.peerPubkey) {
                    ConversationRow(
                        pubkeyHex = convo.peerPubkey,
                        name = nameOf(convo.peerPubkey),
                        lastMessage = convo.lastMessage,
                        pictureUrl = userMetadata[convo.peerPubkey]?.picture,
                        unread = unreadByPeer[convo.peerPubkey] ?: 0,
                        selected = activePubkey == convo.peerPubkey,
                        onClick = { onOpenConversation(DmRoute(convo.peerPubkey)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    pubkeyHex: String,
    name: String,
    lastMessage: String,
    pictureUrl: String?,
    unread: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val background =
        when {
            selected -> NostrordColors.HoverBackground
            isHovered -> NostrordColors.HoverBackground
            else -> androidx.compose.ui.graphics.Color.Transparent
        }
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm)
            .clip(NostrordShapes.shapeMedium)
            .background(background)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs + Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        OptimizedSmallAvatar(
            imageUrl = pictureUrl,
            identifier = pubkeyHex,
            displayName = name,
            size = 28.dp,
            shape = CircleShape,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                color = NostrordColors.TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                lastMessage,
                color = NostrordColors.TextMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (unread > 0) {
            Box(
                modifier =
                Modifier
                    .clip(CircleShape)
                    .background(NostrordColors.Primary)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    unread.toString(),
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun StartConversationRow(
    pubkeyHex: String,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val npub = remember(pubkeyHex) { runCatching { Nip19.encodeNpub(pubkeyHex) }.getOrDefault(pubkeyHex) }
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm)
            .clip(NostrordShapes.shapeMedium)
            .background(if (isHovered) NostrordColors.HoverBackground else androidx.compose.ui.graphics.Color.Transparent)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs + Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        OptimizedSmallAvatar(
            imageUrl = null,
            identifier = pubkeyHex,
            displayName = npub,
            size = 28.dp,
            shape = CircleShape,
        )
        Text(
            npub,
            color = NostrordColors.TextSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
