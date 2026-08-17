package org.nostr.nostrord.ui.screens.dm
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.managers.DmMessage
import org.nostr.nostrord.network.managers.previewText
import org.nostr.nostrord.network.upload.mimeTypeForFilename
import org.nostr.nostrord.ui.components.ConfirmDialog
import org.nostr.nostrord.ui.components.avatars.OptimizedSmallAvatar
import org.nostr.nostrord.ui.components.chat.DateSeparator
import org.nostr.nostrord.ui.components.chat.DmAttachment
import org.nostr.nostrord.ui.components.chat.DmEventSourceDialog
import org.nostr.nostrord.ui.components.chat.DmMessageContextMenu
import org.nostr.nostrord.ui.components.chat.DmRelaysDialog
import org.nostr.nostrord.ui.components.chat.GroupInviteCard
import org.nostr.nostrord.ui.components.chat.MessageComposer
import org.nostr.nostrord.ui.components.chat.MessageContent
import org.nostr.nostrord.ui.components.chat.ReactionBadges
import org.nostr.nostrord.ui.components.chat.ReplyQuote
import org.nostr.nostrord.ui.components.chat.SendStateIcon
import org.nostr.nostrord.ui.components.chat.rightClickContextMenuModifier
import org.nostr.nostrord.ui.components.emoji.EmojiPicker
import org.nostr.nostrord.ui.components.layout.DmConversationList
import org.nostr.nostrord.ui.components.layout.FrameMenuButton
import org.nostr.nostrord.ui.components.layout.PageHeader
import org.nostr.nostrord.ui.extractDmGroupInvite
import org.nostr.nostrord.ui.navigation.DmRoute
import org.nostr.nostrord.ui.navigation.UserRoute
import org.nostr.nostrord.ui.screens.profile.ProfilePageViewModel
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.formatTime
import org.nostr.nostrord.utils.rememberClipboardWriter

/**
 * Direct-message conversation page (NIP-17). Renders the decrypted thread and a composer that
 * seals + gift-wraps each message through the active signer (local, bunker, or NIP-07) via
 * [DmViewModel.send]. Mirrors the web web/screens/DmPage.
 */
@Composable
fun DmPageScreen(
    pubkey: String?,
    onOpenProfile: (UserRoute) -> Unit,
    onOpenConversation: (DmRoute) -> Unit = {},
    onOpenGroup: (relayUrl: String, groupId: String) -> Unit = { _, _ -> },
    // Non-null only on compact/mobile (sidebar is in the drawer). Drives the hamburger and, on the
    // empty landing, the conversation list shown in the page body (no visible DM sidebar there),
    // mirroring the web `.dm-page-convos` media query.
    onOpenDrawer: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().background(NostrordColors.Background)) {
        if (pubkey == null) {
            PageHeader(
                icon = Icons.Default.Mail,
                title = "Direct messages",
                onOpenDrawer = onOpenDrawer,
            )
            if (onOpenDrawer != null) {
                // Compact / mobile: the DM sidebar is in the drawer, so show the conversation list
                // in the page body (web `.dm-page-convos`). The empty-state CTA opens the drawer,
                // where search starts a new conversation.
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                ) {
                    DmConversationList(
                        onOpenConversation = onOpenConversation,
                        onStartConversation = onOpenDrawer,
                    )
                }
            } else {
                // Desktop: the conversation list lives in the sidebar, so the main area is the hero.
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier =
                        Modifier
                            .size(64.dp)
                            .clip(NostrordShapes.shapeXLarge)
                            .background(NostrordColors.BackgroundFloating),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✉️", fontSize = 30.sp)
                    }
                    Spacer(modifier = Modifier.height(Spacing.md))
                    Text(
                        "Your direct messages",
                        color = NostrordColors.TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        "Pick a conversation on the side or start a new one with someone you follow.",
                        color = NostrordColors.TextMuted,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 320.dp),
                    )
                }
            }
            return@Column
        }

        val vm = viewModel(key = "dm-$pubkey") { ProfilePageViewModel(AppModule.nostrRepository, pubkey) }
        val metadata by vm.metadata.collectAsState()
        val dmVm = viewModel { DmViewModel(AppModule.nostrRepository) }
        val messagesByPeer by dmVm.messagesByPeer.collectAsState()
        val messages = messagesByPeer[pubkey].orEmpty()
        val dmStatus by dmVm.messageStatus.collectAsState()
        val dmFiles by dmVm.fileStates.collectAsState()
        val dmReactions by dmVm.reactions.collectAsState()
        val userMetadata by dmVm.userMetadata.collectAsState()
        val myPubkey = remember { dmVm.getPublicKey() }
        // Message the emoji picker was opened for; null while it is closed.
        var reactingTo by remember { mutableStateOf<String?>(null) }
        var uploadError by remember { mutableStateOf<String?>(null) }
        // Message being replied to; the composer shows its quote until it is sent or cancelled.
        var replyingTo by remember { mutableStateOf<String?>(null) }
        // Resolve where this peer reads before the first message is written, not after their reply.
        LaunchedEffect(pubkey) { dmVm.openConversation(pubkey) }
        // Mark the conversation read while it is open (and as new messages stream in).
        LaunchedEffect(pubkey, messages.size) {
            if (messages.isNotEmpty()) dmVm.markRead(pubkey)
        }
        // TextFieldValue for caret-aware emoji/paste insertion in the shared MessageComposer.
        var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
        var isSending by remember { mutableStateOf(false) }

        // Open a conversation pinned to the latest message (scroll to the bottom), like a chat.
        val messagesScroll = rememberScrollState()
        // True while the user rests at the bottom; drives whether async media growth keeps the view
        // pinned. Recomputed only when a scroll gesture settles, so programmatic follow-scrolls (and
        // the moment media grows maxValue) don't flip it off.
        val pinnedToBottom = remember { mutableStateOf(true) }
        LaunchedEffect(pubkey, messages.size) {
            messagesScroll.scrollTo(messagesScroll.maxValue)
            pinnedToBottom.value = true
        }
        LaunchedEffect(messagesScroll.isScrollInProgress) {
            if (!messagesScroll.isScrollInProgress) {
                pinnedToBottom.value = messagesScroll.value >= messagesScroll.maxValue - 40
            }
        }
        // Inline media (images/video) loads after render and raises maxValue; follow it to the
        // bottom when the user was pinned, so the newest message stays in view.
        LaunchedEffect(messagesScroll.maxValue) {
            if (pinnedToBottom.value) messagesScroll.scrollTo(messagesScroll.maxValue)
        }

        // The message the composer is answering, if it is still in the thread.
        val replyParent = messages.firstOrNull { it.id == replyingTo }

        val send = {
            val body = textFieldValue.text.trim()
            if (body.isNotBlank() && !isSending) {
                isSending = true
                dmVm.send(
                    pubkey,
                    body,
                    replyToId = replyParent?.id,
                    onSuccess = {
                        textFieldValue = TextFieldValue("")
                        replyingTo = null
                        isSending = false
                    },
                    onFailure = { isSending = false },
                )
            }
        }

        val name =
            metadata?.displayName?.takeIf { it.isNotBlank() }
                ?: metadata?.name?.takeIf { it.isNotBlank() }
                ?: vm.npub.take(12) + "..."

        val copyToClipboard = rememberClipboardWriter()
        val isFollowing by vm.isFollowing.collectAsState()
        val isMuted by vm.isMuted.collectAsState()
        var headerMenuOpen by remember { mutableStateOf(false) }
        var relaysDialogOpen by remember { mutableStateOf(false) }
        val peerRelays by remember(pubkey) { dmVm.peerDmRelays(pubkey) }.collectAsState()
        if (relaysDialogOpen) {
            DmRelaysDialog(relays = peerRelays, onDismiss = { relaysDialogOpen = false })
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            onOpenDrawer?.let { open ->
                FrameMenuButton(onClick = open)
            }
            Row(
                modifier =
                Modifier
                    .clip(NostrordShapes.shapeSmall)
                    .clickable { onOpenProfile(UserRoute(pubkey)) }
                    .padding(Spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                OptimizedSmallAvatar(
                    imageUrl = metadata?.picture,
                    identifier = pubkey,
                    displayName = name,
                    size = 24.dp,
                    shape = CircleShape,
                )
                Text(
                    name,
                    color = NostrordColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { headerMenuOpen = true }) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "More",
                        tint = NostrordColors.TextSecondary,
                    )
                }
                DropdownMenu(
                    expanded = headerMenuOpen,
                    onDismissRequest = { headerMenuOpen = false },
                    containerColor = NostrordColors.Surface,
                ) {
                    DropdownMenuItem(
                        text = { Text("View profile") },
                        leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                        onClick = {
                            headerMenuOpen = false
                            onOpenProfile(UserRoute(pubkey))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (isFollowing) "Unfollow" else "Follow") },
                        leadingIcon = {
                            Icon(
                                if (isFollowing) Icons.Outlined.PersonRemove else Icons.Outlined.PersonAdd,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            headerMenuOpen = false
                            vm.toggleFollow()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (isMuted) "Unmute user" else "Mute user") },
                        leadingIcon = { Icon(Icons.Outlined.NotificationsOff, contentDescription = null) },
                        onClick = {
                            headerMenuOpen = false
                            vm.toggleMute()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Copy npub") },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                        onClick = {
                            headerMenuOpen = false
                            copyToClipboard(vm.npub)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("View DM relays") },
                        leadingIcon = { Icon(Icons.Outlined.Public, contentDescription = null) },
                        onClick = {
                            headerMenuOpen = false
                            dmVm.loadPeerDmRelays(pubkey)
                            relaysDialogOpen = true
                        },
                    )
                }
            }
        }
        HorizontalDivider(color = NostrordColors.Divider)

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(messagesScroll).padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Avatar + name open the peer's profile, like the header peer button.
            OptimizedSmallAvatar(
                imageUrl = metadata?.picture,
                identifier = pubkey,
                displayName = name,
                size = 64.dp,
                shape = CircleShape,
                modifier = Modifier.clip(CircleShape).clickable { onOpenProfile(UserRoute(pubkey)) },
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                name,
                color = NostrordColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier =
                Modifier
                    .clip(NostrordShapes.shapeSmall)
                    .clickable { onOpenProfile(UserRoute(pubkey)) }
                    .padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
            )
            Text(
                "Beginning of your direct conversation with $name. Direct messages are encrypted (NIP-17).",
                color = NostrordColors.TextMuted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp),
            )
            val chatItems = remember(messages) { buildDmChatItems(messages) }
            var menuForId by remember { mutableStateOf<String?>(null) }
            var menuAnchorPx by remember { mutableStateOf<Offset?>(null) }
            var sourceForId by remember { mutableStateOf<String?>(null) }
            messages.firstOrNull { it.id == sourceForId }?.let { src ->
                DmEventSourceDialog(
                    json = src.prettyEventJson(),
                    relays = src.relays,
                    onCopyJson = { copyToClipboard(src.eventJson()) },
                    onDismiss = { sourceForId = null },
                )
            }
            chatItems.forEach { item ->
                when (item) {
                    is DmChatItem.DateSeparator -> DateSeparator(item.label)
                    is DmChatItem.Message -> {
                        val m = item.message
                        // WhatsApp/Telegram-style: a small clock inside every bubble,
                        // bottom-right under the text.
                        Row(
                            modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = if (item.firstInGroup) Spacing.sm else Spacing.xxs)
                                // Tap (mobile) / right-click (desktop) opens the context menu
                                // at the pointer, same interaction as group chat rows.
                                .then(
                                    rightClickContextMenuModifier { clickOffset ->
                                        menuAnchorPx = clickOffset
                                        menuForId = m.id
                                    },
                                ),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            DmMessageContextMenu(
                                visible = menuForId == m.id,
                                anchorOffsetPx = menuAnchorPx,
                                onDismiss = { menuForId = null },
                                onViewSource = { sourceForId = m.id },
                                onCopyText = { copyToClipboard(m.content) },
                                onReact = { dmVm.react(pubkey, m.id, it) },
                                onOpenReactionPicker = { reactingTo = m.id },
                                onReply = { replyingTo = m.id },
                            )
                            // Web parity (.dm-bubble max-width 75%): the spacer eats the other
                            // 25% on the bubble's growth side; the Box owns the 75% slot and
                            // pins the bubble to the correct edge inside it.
                            if (m.mine) Spacer(modifier = Modifier.weight(0.25f))
                            Box(
                                modifier = Modifier.weight(0.75f),
                                contentAlignment = if (m.mine) Alignment.BottomEnd else Alignment.BottomStart,
                            ) {
                                Surface(
                                    shape = NostrordShapes.shapeMedium,
                                    color = if (m.mine) NostrordColors.Primary else NostrordColors.BackgroundFloating,
                                ) {
                                    Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
                                        // A group naddr on its own line renders as the prototype
                                        // invite card (text above, card + View group button below).
                                        // Quote of the message this one answers, above its body.
                                        m.replyToId?.let { parentId ->
                                            val parent = messages.firstOrNull { it.id == parentId }
                                            ReplyQuote(
                                                authorName = replyAuthorName(parent, userMetadata, name, myPubkey),
                                                snippet = parent?.previewText() ?: "Message not loaded",
                                                modifier = Modifier.padding(bottom = Spacing.xxs),
                                            )
                                        }
                                        val attachment = m.file
                                        val invite = remember(m.content) { extractDmGroupInvite(m.content) }
                                        val body = if (attachment != null) "" else invite?.remainingText ?: m.content
                                        if (attachment != null) {
                                            DmAttachment(
                                                file = attachment,
                                                state = dmFiles[m.id],
                                                onLoad = { dmVm.loadFile(m) },
                                                onRetry = { dmVm.retryFile(m) },
                                                onImage = m.mine,
                                            )
                                        }
                                        if (body.isNotBlank()) {
                                            // Rich body: inline images/video/audio/links/mentions/markdown,
                                            // reusing the group chat renderer. White text on the "mine" bubble.
                                            MessageContent(
                                                content = body,
                                                // The rumor's own tags: custom emoji and the imeta
                                                // hints that pre-size an inline image, same as chat.
                                                tags = m.tags,
                                                onMentionClick = { onOpenProfile(UserRoute(it)) },
                                                textColor = if (m.mine) Color.White else NostrordColors.TextPrimary,
                                            )
                                        }
                                        if (invite != null) {
                                            GroupInviteCard(
                                                groupId = invite.groupId,
                                                relayUrl = invite.relayUrl,
                                                onOpen = { onOpenGroup(invite.relayUrl, invite.groupId) },
                                                modifier = Modifier.padding(vertical = Spacing.xxs),
                                            )
                                        }
                                        // Time + send-state (clock while Sending, check once Delivered),
                                        // reusing the group chat's SendStateIcon on own messages.
                                        Row(
                                            modifier = Modifier.align(Alignment.End).padding(top = Spacing.xxs),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                formatTime(m.createdAt),
                                                color = if (m.mine) Color.White.copy(alpha = 0.7f) else NostrordColors.TextMuted,
                                                fontSize = 10.sp,
                                            )
                                            if (m.mine) {
                                                dmStatus[m.id]?.let { st ->
                                                    SendStateIcon(status = st, tint = Color.White.copy(alpha = 0.7f))
                                                }
                                            }
                                        }
                                        // Reactions sit inside the bubble so they follow its edge,
                                        // the way the group chat hangs them under a message.
                                        dmReactions[m.id]?.let { byEmoji ->
                                            ReactionBadges(
                                                reactions = byEmoji,
                                                currentUserPubkey = myPubkey,
                                                resolveMetadata = { userMetadata[it] },
                                                onReactionClick = { emoji -> dmVm.react(pubkey, m.id, emoji) },
                                                modifier = Modifier.padding(top = Spacing.xxs),
                                            )
                                        }
                                    }
                                }
                            }
                            if (!m.mine) Spacer(modifier = Modifier.weight(0.25f))
                        }
                    }
                }
            }
        }

        MessageComposer(
            value = textFieldValue,
            onValueChange = { textFieldValue = it },
            onSend = send,
            placeholder = "Message $name",
            isSending = isSending,
            modifier = Modifier.padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl, top = Spacing.xs),
            replyAuthorName = replyParent?.let { replyAuthorName(it, userMetadata, name, myPubkey) },
            replySnippet = replyParent?.previewText(),
            onCancelReply = { replyingTo = null },
            // A DM attachment is encrypted before it reaches a media server, so the picked bytes
            // go out as a kind:15 message instead of being uploaded and pasted as a url.
            onFilePicked = { bytes, filename ->
                dmVm.sendFile(
                    recipientPubkey = pubkey,
                    bytes = bytes,
                    filename = filename,
                    mimeType = mimeTypeForFilename(filename),
                    onFailure = { uploadError = it },
                )
            },
        )

        uploadError?.let { error ->
            ConfirmDialog(
                title = "Could not send the file",
                message = error,
                confirmLabel = "OK",
                cancelLabel = null,
                onConfirm = { uploadError = null },
                onDismiss = { uploadError = null },
            )
        }

        // Full emoji picker for a reaction, opened from a bubble's context menu. Tapping the
        // scrim closes it, same as the group chat's.
        if (reactingTo != null) {
            Box(
                modifier =
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { reactingTo = null },
                    ),
            ) {
                EmojiPicker(
                    onEmojiSelect = { emoji ->
                        reactingTo?.let { dmVm.react(pubkey, it, emoji) }
                        reactingTo = null
                    },
                    onDismiss = { reactingTo = null },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

/**
 * Name to show on a reply quote. The peer's own name is already in the header, so an unknown
 * parent falls back to it rather than to a raw npub.
 */
private fun replyAuthorName(
    parent: DmMessage?,
    metadata: Map<String, UserMetadata>,
    peerName: String,
    myPubkey: String?,
): String = when {
    parent == null -> peerName
    parent.senderPubkey == myPubkey -> "You"
    else ->
        metadata[parent.senderPubkey]?.displayName
            ?: metadata[parent.senderPubkey]?.name
            ?: peerName
}
