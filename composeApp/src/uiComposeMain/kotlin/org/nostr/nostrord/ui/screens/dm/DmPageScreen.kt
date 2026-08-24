package org.nostr.nostrord.ui.screens.dm
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.managers.DmMessage
import org.nostr.nostrord.network.managers.previewText
import org.nostr.nostrord.network.upload.mimeTypeForFilename
import org.nostr.nostrord.nostr.DmOutgoingFile
import org.nostr.nostrord.ui.components.ConfirmDialog
import org.nostr.nostrord.ui.components.avatars.OptimizedSmallAvatar
import org.nostr.nostrord.ui.components.chat.DateSeparator
import org.nostr.nostrord.ui.components.chat.DmAttachment
import org.nostr.nostrord.ui.components.chat.DmEventSourceDialog
import org.nostr.nostrord.ui.components.chat.DmMessageContextMenu
import org.nostr.nostrord.ui.components.chat.DmRelaysDialog
import org.nostr.nostrord.ui.components.chat.GroupInviteCard
import org.nostr.nostrord.ui.components.chat.ImageViewerModal
import org.nostr.nostrord.ui.components.chat.LocalAnimatedImageHidden
import org.nostr.nostrord.ui.components.chat.LocalImageViewerUrl
import org.nostr.nostrord.ui.components.chat.MessageComposer
import org.nostr.nostrord.ui.components.chat.MessageContent
import org.nostr.nostrord.ui.components.chat.MessageStatusIndicator
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
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.formatTime
import org.nostr.nostrord.utils.rememberClipboardWriter

// Breathing room above a message jumped to from a reply quote, so it does not sit glued to the
// top edge of the feed.
private const val JUMP_TOP_GAP_PX = 24f

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
        val syncing by dmVm.syncing.collectAsState()
        val userMetadata by dmVm.userMetadata.collectAsState()
        val myPubkey = remember { dmVm.getPublicKey() }
        // Message the emoji picker was opened for; null while it is closed.
        var reactingTo by remember { mutableStateOf<String?>(null) }
        // Message being replied to; the composer shows its quote until it is sent or cancelled.
        var replyingTo by remember { mutableStateOf<String?>(null) }
        // Picking Reply puts the caret in the composer, ready to type (chat/web parity). Bumped per
        // pick so replying twice to the same message re-focuses.
        val composerFocus = remember { FocusRequester() }
        var replyFocusNonce by remember { mutableStateOf(0) }
        LaunchedEffect(replyingTo, replyFocusNonce) {
            if (replyingTo != null) runCatching { composerFocus.requestFocus() }
        }
        // Second tick: the wrap reached every inbox relay this peer publishes.
        val fullyDelivered by dmVm.fullyDelivered.collectAsState()
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
        val scrollScope = rememberCoroutineScope()
        // True while the user rests at the bottom; drives whether async media growth keeps the view
        // pinned. Recomputed only when a scroll gesture settles, so programmatic follow-scrolls (and
        // the moment media grows maxValue) don't flip it off.
        val pinnedToBottom = remember { mutableStateOf(true) }
        // Opening a conversation lands on the newest message; after that the position is the
        // reader's, not the stream's.
        LaunchedEffect(pubkey) {
            messagesScroll.scrollTo(messagesScroll.maxValue)
            pinnedToBottom.value = true
        }
        LaunchedEffect(messagesScroll.isScrollInProgress) {
            if (!messagesScroll.isScrollInProgress) {
                pinnedToBottom.value = messagesScroll.value >= messagesScroll.maxValue - 40
            }
        }
        // Inline images in a message body report their tap through LocalImageViewerUrl. Its default
        // is a throwaway state nobody reads, so a screen that renders message bodies has to provide
        // one and show the viewer, or every tap on an image is silently dropped.
        val imageViewerUrl = remember { mutableStateOf<String?>(null) }

        // Messages FROM THE PEER that landed while the reader stayed up in the history. Reported
        // on the jump pill so going back down is their decision with the count in hand. Own
        // messages are never counted: the reader wrote them, and writing already returns the view
        // to the bottom.
        val peerCount = messages.count { !it.mine }
        var seenPeerCount by remember(pubkey) { mutableStateOf(peerCount) }
        val newWhileAway = (peerCount - seenPeerCount).coerceAtLeast(0)
        LaunchedEffect(peerCount, pinnedToBottom.value) {
            if (pinnedToBottom.value) seenPeerCount = peerCount
        }
        // Our own send always returns to the bottom: the reader caused this one.
        val newestOwnId = messages.lastOrNull()?.takeIf { it.mine }?.id
        LaunchedEffect(newestOwnId) {
            if (newestOwnId != null) {
                pinnedToBottom.value = true
                messagesScroll.scrollTo(messagesScroll.maxValue)
            }
        }
        // The oldest message on screen: it changes exactly when the backlog inserts something
        // ABOVE what is rendered, which is the case the reader must not be dragged through.
        val oldestId = messages.firstOrNull()?.id
        val prependPending = remember { mutableStateOf(false) }
        LaunchedEffect(oldestId) { prependPending.value = true }
        // Growth from inline media loading, a new message, or the backlog filling in. Pinned to
        // the bottom means follow it. Reading further up means hold the reading position: when
        // the growth was above (a decrypted older message), the content the reader was on has
        // moved down by exactly that much, so compensating puts it back under their eyes.
        var lastMax by remember { mutableStateOf(0) }
        LaunchedEffect(messagesScroll.maxValue) {
            val delta = messagesScroll.maxValue - lastMax
            lastMax = messagesScroll.maxValue
            when {
                pinnedToBottom.value -> messagesScroll.scrollTo(messagesScroll.maxValue)
                delta > 0 && prependPending.value -> messagesScroll.scrollTo(messagesScroll.value + delta)
            }
            prependPending.value = false
        }

        // The message the composer is answering, if it is still in the thread.
        val replyParent = messages.firstOrNull { it.id == replyingTo }

        // Uploads whose url sits in the draft; each goes out as its own kind:15 on send.
        var pendingUploads by remember(pubkey) { mutableStateOf<List<DmOutgoingFile>>(emptyList()) }
        var uploadError by remember { mutableStateOf<String?>(null) }
        val send = {
            val body = textFieldValue.text.trim()
            if (body.isNotBlank() && !isSending) {
                // Snapshot for restore-on-failure (GroupScreen does the same). The field clears
                // in the gesture that sent: the publish round-trip runs for as long as the signer
                // takes, and a draft still sitting there reads as "not sent".
                val sentValue = textFieldValue
                val sentReply = replyParent?.id
                val sentUploads = pendingUploads
                isSending = true
                textFieldValue = TextFieldValue("")
                replyingTo = null
                pendingUploads = emptyList()
                dmVm.send(
                    pubkey,
                    body,
                    replyToId = sentReply,
                    uploads = sentUploads,
                    onSuccess = { isSending = false },
                    onFailure = {
                        isSending = false
                        // Push it back for a retry, unless a new message was started.
                        if (textFieldValue.text.isBlank()) {
                            textFieldValue = sentValue
                            replyingTo = sentReply
                            pendingUploads = sentUploads
                        }
                    },
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

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            CompositionLocalProvider(
                LocalAnimatedImageHidden provides (imageViewerUrl.value != null),
                LocalImageViewerUrl provides imageViewerUrl,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(messagesScroll).padding(Spacing.lg),
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
                    // Sitting above the thread, so older messages landing in are expected rather than
                    // startling. Sending stays available: no client can promise it holds every message.
                    if (syncing) {
                        Row(
                            modifier = Modifier.padding(vertical = Spacing.xs),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                color = NostrordColors.TextMuted,
                                strokeWidth = 1.5.dp,
                            )
                            Text(
                                "Catching up on older messages",
                                color = NostrordColors.TextMuted,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    val chatItems = remember(messages) { buildDmChatItems(messages) }
                    // Tapping a reply quote lands on the message it answers. The feed is a plain
                    // scrolling Column, so every row reports its content offset here and the jump
                    // scrolls straight to it. A plain map, not snapshot state: it is written on
                    // every layout pass and only ever read inside a click handler.
                    val messageOffsets = remember(pubkey) { mutableMapOf<String, Float>() }
                    var highlightedId by remember(pubkey) { mutableStateOf<String?>(null) }
                    val jumpToMessage: (String) -> Unit = { id ->
                        messageOffsets[id]?.let { y ->
                            scrollScope.launch {
                                // The reader asked to be up in the history: nothing arriving below
                                // may drag them back down.
                                pinnedToBottom.value = false
                                highlightedId = id
                                messagesScroll.animateScrollTo((y - JUMP_TOP_GAP_PX).toInt().coerceAtLeast(0))
                                delay(2500)
                                if (highlightedId == id) highlightedId = null
                            }
                        }
                        Unit
                    }
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
                                // Flash after a reply-quote jump: snaps on, fades out, same cue as
                                // the group feed's MessageItem.
                                val highlighted = highlightedId == m.id
                                val highlightColor by animateColorAsState(
                                    targetValue = if (highlighted) NostrordColors.Primary.copy(alpha = 0.18f) else Color.Transparent,
                                    animationSpec = if (highlighted) snap() else tween(durationMillis = 1200),
                                )
                                // WhatsApp/Telegram-style: a small clock inside every bubble,
                                // bottom-right under the text.
                                Row(
                                    modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .onGloballyPositioned { messageOffsets[m.id] = it.positionInParent().y }
                                        .background(highlightColor, NostrordShapes.shapeSmall)
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
                                        onReply = {
                                            replyingTo = m.id
                                            replyFocusNonce++
                                        },
                                    )
                                    // Web parity (.dm-bubble max-width 75%): the spacer eats the other
                                    // 25% on the bubble's growth side; the Box owns the 75% slot and
                                    // pins the bubble to the correct edge inside it.
                                    if (m.mine) Spacer(modifier = Modifier.weight(0.25f))
                                    Box(
                                        modifier = Modifier.weight(0.75f),
                                        contentAlignment = if (m.mine) Alignment.BottomEnd else Alignment.BottomStart,
                                    ) {
                                        // The failure row hangs under the bubble, so the bubble and it
                                        // stack in a column inside the 75% slot.
                                        Column(horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
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
                                                            // Tappable only while the answered message is loaded; a
                                                            // no-op click would still swallow the long-press that
                                                            // opens the row menu, so the quote offers it back.
                                                            onClick = parent?.let { p -> { jumpToMessage(p.id) } },
                                                            onLongClick = parent?.let {
                                                                {
                                                                    menuAnchorPx = null
                                                                    menuForId = m.id
                                                                }
                                                            },
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
                                                        modifier = Modifier.align(Alignment.End).padding(top = Spacing.xs),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                    ) {
                                                        Text(
                                                            formatTime(m.createdAt),
                                                            color = if (m.mine) Color.White.copy(alpha = 0.7f) else NostrordColors.TextMuted,
                                                            fontSize = 10.sp,
                                                        )
                                                        if (m.mine) {
                                                            dmStatus[m.id]?.let { st ->
                                                                SendStateIcon(
                                                                    status = st,
                                                                    tint = Color.White.copy(alpha = 0.7f),
                                                                    allInboxes = m.id in fullyDelivered,
                                                                )
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
                                            // Every relay refused it: "Not delivered" with Retry / Dismiss,
                                            // the same row the group chat shows, under the bubble.
                                            if (m.mine) {
                                                dmStatus[m.id]?.let { st ->
                                                    MessageStatusIndicator(
                                                        status = st,
                                                        onRetry = { dmVm.retry(m.id) },
                                                        onDismiss = { dmVm.dismiss(m.id) },
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
            } // CompositionLocalProvider

            imageViewerUrl.value?.let { url ->
                ImageViewerModal(
                    imageUrl = url,
                    onDismiss = { imageViewerUrl.value = null },
                )
            }

            // Returning to the newest message is a tap, never something the feed does on its own.
            if (!pinnedToBottom.value) {
                Row(
                    modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = Spacing.lg, bottom = Spacing.md)
                        .clip(NostrordShapes.shapeXLarge)
                        .background(NostrordColors.BackgroundFloating)
                        .clickable {
                            pinnedToBottom.value = true
                            scrollScope.launch { messagesScroll.animateScrollTo(messagesScroll.maxValue) }
                        }
                        .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    if (newWhileAway > 0) {
                        Text(
                            text = if (newWhileAway > 99) "99+ new" else "$newWhileAway new",
                            color = NostrordColors.TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Jump to latest message",
                        tint = NostrordColors.TextSecondary,
                        modifier = Modifier.size(16.dp),
                    )
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
            focusRequester = composerFocus,
            // A picked or pasted file is encrypted and uploaded here, and its url appended to the
            // draft like any other upload; on send each url becomes its own kind:15.
            onFilePicked = { bytes, filename ->
                when (val r = dmVm.uploadFile(bytes, filename, mimeTypeForFilename(filename))) {
                    is Result.Success -> {
                        val current = textFieldValue.text
                        val sep = if (current.isNotEmpty() && !current.endsWith(" ") && !current.endsWith("\n")) " " else ""
                        val newText = current + sep + r.data.url
                        textFieldValue = TextFieldValue(newText, TextRange(newText.length))
                        if (pendingUploads.none { it.url == r.data.url }) pendingUploads = pendingUploads + r.data
                    }
                    is Result.Error -> uploadError = r.error.message
                }
            },
        )

        uploadError?.let { error ->
            ConfirmDialog(
                title = "Upload Failed",
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
