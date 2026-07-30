package org.nostr.nostrord.ui.screens.group

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.network.toEventJson
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.components.ConfirmDialog
import org.nostr.nostrord.ui.components.avatars.ProfileAvatar
import org.nostr.nostrord.ui.components.buttons.AppButton
import org.nostr.nostrord.ui.components.buttons.AppButtonSize
import org.nostr.nostrord.ui.components.chat.EmojiImage
import org.nostr.nostrord.ui.components.chat.ImageViewerModal
import org.nostr.nostrord.ui.components.chat.LocalAnimatedImageHidden
import org.nostr.nostrord.ui.components.chat.LocalImageViewerUrl
import org.nostr.nostrord.ui.components.chat.MessageComposer
import org.nostr.nostrord.ui.components.chat.MessageContent
import org.nostr.nostrord.ui.components.chat.MessageContextAction
import org.nostr.nostrord.ui.components.chat.MessageStatusIndicator
import org.nostr.nostrord.ui.components.chat.ReactionBadges
import org.nostr.nostrord.ui.components.chat.ReplyPreview
import org.nostr.nostrord.ui.components.chat.SendStateIcon
import org.nostr.nostrord.ui.components.chat.ThreadMessageContextMenu
import org.nostr.nostrord.ui.components.chat.rightClickContextMenuModifier
import org.nostr.nostrord.ui.components.emoji.EmojiPicker
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.navigation.HashRoute
import org.nostr.nostrord.ui.navigation.threadShareLink
import org.nostr.nostrord.ui.screens.group.components.CreateThreadDialog
import org.nostr.nostrord.ui.screens.group.components.GroupHeaderIcon
import org.nostr.nostrord.ui.screens.group.components.UserProfileModal
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.ui.theme.rememberEmojiFontFamily
import org.nostr.nostrord.utils.formatTimestamp
import org.nostr.nostrord.utils.getDateLabel
import org.nostr.nostrord.utils.rememberClipboardWriter
import org.nostr.nostrord.utils.shortNpub

/**
 * Forum-style Threads pane (native mirror of the web ThreadsScreen): the list of kind:11 roots,
 * or a single open thread (root + kind:1111 replies) when [GroupRoute.threadRootId] is set. The
 * group rail + sidebar stay mounted in AppFrame, so only this centre pane swaps when leaving chat.
 * Consumes the shared [ThreadsViewModel]; logic lives there, this is layout only.
 */
@Composable
fun ThreadsScreen(
    route: GroupRoute,
    onNavigate: (HashRoute) -> Unit,
    onBack: () -> Unit = { onNavigate(route.copy(threadRootId = null)) },
    // Non-null only on the mobile layout, where it opens the groups drawer (chat parity).
    onOpenDrawer: (() -> Unit)? = null,
) {
    // Distinct key prefix: GroupSidebar/GroupScreen use viewModel(key = groupId) for GroupViewModel
    // in the same ViewModelStore, so a bare groupId key here collided with it and the two evicted +
    // recreated each other every recomposition - churning the thread sub so it never loaded
    // (blank/stuck list on the mobile layout where the sidebar VM is also composed).
    val vm = viewModel(key = "threads:${route.relayUrl}|${route.groupId}") {
        ThreadsViewModel(AppModule.nostrRepository, route.groupId, route.relayUrl)
    }
    val threads by vm.threads.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    val openThread by vm.openThread.collectAsState()
    val userMetadata by vm.userMetadata.collectAsState()
    val messageStatus by vm.messageStatus.collectAsState()
    val reactions by vm.reactions.collectAsState()
    val pendingReactions by vm.pendingReactions.collectAsState()
    val reactionError by vm.reactionError.collectAsState()
    val deleteError by vm.deleteError.collectAsState()
    val myPubkey = remember { vm.getPublicKey() }

    // Full-picker target: the (eventId, authorPubkey) of the message being reacted to.
    var reactingTo by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Avatar / author-name / mention tap target: opens the user profile modal (chat parity).
    var selectedUserPubkey by remember { mutableStateOf<String?>(null) }

    // Message being answered by the composer (context-menu Reply); null posts top-level.
    var replyingTo by remember { mutableStateOf<NostrGroupClient.NostrMessage?>(null) }

    // Deep-link target (?e=): the message to scroll to and flash once the thread loads.
    var highlightId by remember { mutableStateOf<String?>(null) }
    val highlightLoaded = route.messageId != null &&
        openThread?.let { d -> (d.replies + d.root).any { it.id == route.messageId } } == true
    LaunchedEffect(route.messageId, highlightLoaded) {
        if (route.messageId != null && highlightLoaded) {
            highlightId = route.messageId
            delay(HIGHLIGHT_FLASH_MS)
            highlightId = null
        }
    }
    val threadScroll = rememberScrollState()
    // Scroll target: the flashed message's y inside the scrolled column, reported on layout.
    var highlightTargetY by remember(route.messageId) { mutableStateOf<Int?>(null) }
    LaunchedEffect(highlightTargetY) {
        highlightTargetY?.let { threadScroll.animateScrollTo((it - HIGHLIGHT_SCROLL_MARGIN_PX).coerceAtLeast(0)) }
    }

    // Keep the open thread synced with the route (#/g/<relay>/<id>/threads/<rootId>).
    LaunchedEffect(route.threadRootId) {
        vm.openThread(route.threadRootId)
        replyingTo = null
    }

    var showCompose by remember { mutableStateOf(false) }
    // Message pending delete confirmation (the root or any reply, from the header or the menu).
    var deleteTarget by remember { mutableStateOf<NostrGroupClient.NostrMessage?>(null) }
    var reply by remember { mutableStateOf(TextFieldValue("")) }
    var sending by remember { mutableStateOf(false) }
    // Shared with MessageContent via LocalImageViewerUrl: tap an inline image -> fullscreen viewer.
    val imageViewerUrl = remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(NostrordColors.Background)) {
        // Page header (mobile only, chat-header metrics): group identity + the drawer ≡.
        // Desktop skips it - the sidebar already names the group.
        if (onOpenDrawer != null) {
            val groupsByRelay by AppModule.nostrRepository.groupsByRelay.collectAsState()
            val groupMeta = groupsByRelay[route.relayUrl]?.firstOrNull { it.id == route.groupId }
                ?: groupsByRelay.values.flatten().firstOrNull { it.id == route.groupId }
            val groupName = groupMeta?.name?.takeIf { it.isNotBlank() } ?: "#${route.groupId.take(8)}"
            Row(
                modifier = Modifier.fillMaxWidth().height(Spacing.headerHeight).padding(horizontal = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Default.Menu, contentDescription = "Open groups", tint = NostrordColors.TextSecondary)
                }
                GroupHeaderIcon(
                    pictureUrl = groupMeta?.picture,
                    groupId = route.groupId,
                    displayName = groupName,
                    size = 26.dp,
                    cornerRadius = 8.dp,
                )
                Text(
                    groupName,
                    color = NostrordColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider(color = NostrordColors.Divider)
        }

        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Discord-style split on large widths: the list keeps living on the left and the open
            // thread docks on the right; compact widths keep the swap (detail replaces the list).
            // 768dp matches the web media query and AppFrame's own mobile boundary.
            val split = maxWidth >= 768.dp
            val detailPane: @Composable ColumnScope.() -> Unit = {
                // ---- Single thread (detail) ----
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    if (!split) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to threads",
                                tint = NostrordColors.TextSecondary,
                            )
                        }
                    }
                    Text(
                        "Thread",
                        color = NostrordColors.TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    val ownRoot = openThread?.root
                    if (myPubkey != null && ownRoot != null && ownRoot.pubkey == myPubkey) {
                        IconButton(onClick = { deleteTarget = ownRoot }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete thread", tint = NostrordColors.TextSecondary)
                        }
                    }
                    if (split) {
                        // Desktop closes the docked thread via the X, Discord-style.
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.Close, contentDescription = "Close thread", tint = NostrordColors.TextSecondary)
                        }
                    }
                }
                HorizontalDivider(color = NostrordColors.Divider)

                val detail = openThread
                if (detail == null) {
                    EmptyState("Loading thread...")
                } else {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth()
                            .verticalScroll(threadScroll).padding(Spacing.md),
                    ) {
                        CompositionLocalProvider(
                            LocalImageViewerUrl provides imageViewerUrl,
                            LocalAnimatedImageHidden provides (imageViewerUrl.value != null),
                        ) {
                            // Nested replies resolve their lowercase-e parent from the loaded thread.
                            val messagesById = remember(detail) { (detail.replies + detail.root).associateBy { it.id } }
                            val renderMessage: @Composable (NostrGroupClient.NostrMessage, Boolean) -> Unit = { msg, isRoot ->
                                ThreadMessage(
                                    msg = msg,
                                    userMetadata = userMetadata,
                                    isRoot = isRoot,
                                    myPubkey = myPubkey,
                                    route = route,
                                    status = messageStatus[msg.id],
                                    reactions = reactions[msg.id] ?: emptyMap(),
                                    // Pending sends for this message: "eventId|emoji" keys -> emojis.
                                    pendingEmojis = pendingReactions
                                        .filter { it.startsWith("${msg.id}|") }
                                        .map { it.substringAfter('|') }
                                        .toSet(),
                                    parentMsg = msg.threadParentIdTag()?.let { messagesById[it] },
                                    highlighted = msg.id == highlightId,
                                    onPositioned = if (msg.id == route.messageId) ({ y -> highlightTargetY = y }) else null,
                                    resolveMetadata = { userMetadata[it] },
                                    onReact = { emoji -> vm.sendReaction(msg.id, msg.pubkey, emoji) },
                                    onOpenReactionPicker = { reactingTo = msg.id to msg.pubkey },
                                    onReply = { replyingTo = msg },
                                    onShareToChat = { vm.shareThreadToChat(msg) },
                                    onUserClick = { selectedUserPubkey = it },
                                    onDelete = { deleteTarget = msg },
                                    // A group ref in the body opens that group's chat page.
                                    onNavigateToGroup = { gid, _, relay, _ ->
                                        onNavigate(GroupRoute(relay ?: route.relayUrl, gid))
                                    },
                                    onRetry = { vm.retrySend(msg.id) },
                                    onDismiss = { vm.dismissFailed(msg.id) },
                                )
                            }
                            renderMessage(detail.root, true)
                            Text(
                                if (detail.replies.size == 1) "1 REPLY" else "${detail.replies.size} REPLIES",
                                color = NostrordColors.TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                                modifier = Modifier.padding(vertical = Spacing.sm),
                            )
                            detail.replies.forEach { renderMessage(it, false) }
                        }
                    }
                    // Reply chip above the composer while answering a specific message (web parity).
                    replyingTo?.let { target ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md).padding(top = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        ) {
                            Text("Replying to", color = NostrordColors.TextMuted, fontSize = 12.sp)
                            Text(
                                threadDisplayName(target.pubkey, userMetadata[target.pubkey]),
                                color = NostrordColors.Primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                target.content.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty(),
                                color = NostrordColors.TextMuted,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { replyingTo = null }, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Cancel reply",
                                    tint = NostrordColors.TextMuted,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                    MessageComposer(
                        value = reply,
                        onValueChange = { reply = it },
                        onSend = {
                            if (reply.text.isNotBlank() && !sending) {
                                sending = true
                                vm.sendReply(
                                    reply.text.trim(),
                                    parent = replyingTo,
                                    onSuccess = {
                                        reply = TextFieldValue("")
                                        replyingTo = null
                                        sending = false
                                    },
                                    onFailure = { sending = false },
                                )
                            }
                        },
                        placeholder = "Write a reply...",
                        isSending = sending,
                        modifier = Modifier.padding(Spacing.md),
                    )
                }
            }
            val listPane: @Composable ColumnScope.() -> Unit = {
                // ---- Threads list ----
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Text(
                        "Threads",
                        color = NostrordColors.TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    AppButton(
                        text = "New thread",
                        onClick = { showCompose = true },
                        icon = Icons.Filled.Forum,
                        size = AppButtonSize.Small,
                    )
                }
                HorizontalDivider(color = NostrordColors.Divider)

                when {
                    isLoading && threads.isEmpty() -> EmptyState("Loading threads...")
                    threads.isEmpty() -> EmptyState("No threads yet. Start the first one.")
                    else ->
                        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(Spacing.sm)) {
                            items(threads, key = { it.rootId }) { t ->
                                ThreadCard(
                                    t,
                                    userMetadata,
                                    chips = topReactionChips(reactions[t.rootId] ?: emptyMap()),
                                    selected = t.rootId == route.threadRootId,
                                ) { onNavigate(route.copy(threadRootId = t.rootId)) }
                            }
                        }
                }
            }

            if (split && route.threadRootId != null) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) { listPane() }
                    VerticalDivider(color = NostrordColors.Divider)
                    Column(modifier = Modifier.weight(1.2f).fillMaxHeight()) { detailPane() }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (route.threadRootId != null) detailPane() else listPane()
                }
            }
        }
    }

    if (showCompose) {
        CreateThreadDialog(
            onDismiss = { showCompose = false },
            onCreate = { title, content, shareToChat ->
                // Open the new thread right away (Discord parity; the optimistic root is
                // already in the store, so the detail renders instantly).
                vm.createThread(title, content, shareToChat) { rootId ->
                    onNavigate(route.copy(threadRootId = rootId))
                }
            },
        )
    }

    deleteTarget?.let { target ->
        val isRoot = target.id == openThread?.root?.id
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(if (isRoot) "Delete thread?" else "Delete message?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    vm.deleteThread(target.id)
                    if (isRoot) onBack()
                }) { Text("Delete", color = NostrordColors.Error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }

    imageViewerUrl.value?.let { url ->
        ImageViewerModal(
            imageUrl = url,
            onDismiss = { imageViewerUrl.value = null },
        )
    }

    // Full emoji picker for a reaction (opened by the add-reaction button).
    reactingTo?.let { (targetEventId, targetPubkey) ->
        Popup(
            alignment = Alignment.Center,
            onDismissRequest = { reactingTo = null },
            properties =
            PopupProperties(
                focusable = true,
                dismissOnClickOutside = false,
                dismissOnBackPress = true,
            ),
        ) {
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
                        vm.sendReaction(targetEventId, targetPubkey, emoji)
                        reactingTo = null
                    },
                    onDismiss = { reactingTo = null },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }

    // User profile modal (avatar / author-name / mention tap), same modal as chat.
    selectedUserPubkey?.let { pk ->
        UserProfileModal(
            pubkey = pk,
            metadata = userMetadata[pk],
            userMetadata = userMetadata,
            onDismiss = { selectedUserPubkey = null },
        )
    }

    // Relay rejected the kind:5/9005 delete - show the reason instead of silently swallowing
    // (chat parity: "Could Not Delete Message" + the relay's OK message).
    deleteError?.let { error ->
        ConfirmDialog(
            title = "Could Not Delete Message",
            message = error,
            confirmLabel = "OK",
            cancelLabel = null,
            onConfirm = { vm.clearDeleteError() },
            onDismiss = { vm.clearDeleteError() },
        )
    }

    // Reaction error dialog (relay rejected the kind:7), same classification as chat.
    reactionError?.let { error ->
        val errorKind = classifyReactionError(error)
        val isUnknownMember = errorKind == ReactionErrorKind.JoinRequired
        ConfirmDialog(
            title = if (isUnknownMember) "Join Required" else "Cannot React",
            message =
            when (errorKind) {
                ReactionErrorKind.JoinRequired -> "You need to join this group before you can react to messages."
                ReactionErrorKind.SignerFailure -> "Your signer could not sign the reaction. Please try again.\n\n$error"
                ReactionErrorKind.RelayRejected -> "This relay does not support reactions.\n\n$error"
            },
            confirmLabel = if (isUnknownMember) "Join Group" else "OK",
            cancelLabel = if (isUnknownMember) "Cancel" else null,
            onConfirm = {
                vm.clearReactionError()
                if (isUnknownMember) vm.joinGroup()
            },
            onDismiss = { vm.clearReactionError() },
        )
    }
}

// Deep-link flash: duration mirrors the web msg-flash animation; the margin keeps a bit of
// context visible above the target instead of pinning it to the very top.
private const val HIGHLIGHT_FLASH_MS = 2_400L
private const val HIGHLIGHT_SCROLL_MARGIN_PX = 120

private fun threadDisplayName(pubkey: String, meta: UserMetadata?): String = meta?.displayName?.takeIf { it.isNotBlank() }
    ?: meta?.name?.takeIf { it.isNotBlank() }
    ?: shortNpub(pubkey)

@Composable
private fun ColumnScope.EmptyState(text: String) {
    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(text, color = NostrordColors.TextMuted, fontSize = 14.sp)
    }
}

@Composable
private fun ThreadCard(
    t: ThreadSummary,
    userMetadata: Map<String, UserMetadata>,
    chips: List<ReactionChip>,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val meta = userMetadata[t.authorPubkey]
    Row(
        modifier = Modifier.fillMaxWidth().clip(NostrordShapes.shapeLarge)
            // The thread open in the split view stays visibly selected in the list.
            .background(if (selected) NostrordColors.MessageHover else Color.Transparent)
            .clickable(onClick = onClick).padding(Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        ProfileAvatar(
            imageUrl = meta?.picture,
            displayName = threadDisplayName(t.authorPubkey, meta),
            pubkey = t.authorPubkey,
            size = 36.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                t.title,
                color = NostrordColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (t.preview.isNotBlank()) {
                Text(
                    t.preview,
                    color = NostrordColors.TextMuted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Meta row: top reactions on the root, then author / replies / publication date.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                chips.forEach { ReactionChipBadge(it) }
                val replies = if (t.replyCount == 1) "1 reply" else "${t.replyCount} replies"
                Text(
                    "${threadDisplayName(t.authorPubkey, meta)} · $replies · ${getDateLabel(t.createdAt)}",
                    color = NostrordColors.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Compact emoji+count chip on a thread list card (a root's top reactions, Discord-style). */
@Composable
private fun ReactionChipBadge(chip: ReactionChip) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier =
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(NostrordColors.SurfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        if (chip.emojiUrl != null) {
            EmojiImage(url = chip.emojiUrl, contentDescription = chip.emoji, modifier = Modifier.size(14.dp))
        } else {
            Text(chip.emoji, fontSize = 12.sp, fontFamily = rememberEmojiFontFamily())
        }
        Text("${chip.count}", color = NostrordColors.TextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun ThreadMessage(
    msg: NostrGroupClient.NostrMessage,
    userMetadata: Map<String, UserMetadata>,
    isRoot: Boolean,
    myPubkey: String?,
    route: GroupRoute,
    status: GroupManager.MessageStatus?,
    reactions: Map<String, GroupManager.ReactionInfo>,
    pendingEmojis: Set<String>,
    parentMsg: NostrGroupClient.NostrMessage?,
    highlighted: Boolean,
    onPositioned: ((Int) -> Unit)?,
    resolveMetadata: (String) -> UserMetadata?,
    onReact: (String) -> Unit,
    onOpenReactionPicker: () -> Unit,
    onReply: () -> Unit,
    onShareToChat: () -> Unit,
    onUserClick: (String) -> Unit,
    onDelete: () -> Unit,
    onNavigateToGroup: (groupId: String, groupName: String?, relayUrl: String?, messageId: String?) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val meta = userMetadata[msg.pubkey]
    val isAuthor = myPubkey != null && myPubkey == msg.pubkey
    var menuVisible by remember { mutableStateOf(false) }
    var menuAnchorPx by remember { mutableStateOf<Offset?>(null) }
    val writeClipboard = rememberClipboardWriter()
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()
    // Hover tint like chat rows; the deep-link flash overrides it and fades back out.
    val rowBackground by animateColorAsState(
        when {
            highlighted -> NostrordColors.Primary.copy(alpha = 0.18f)
            isHovered -> NostrordColors.MessageHover
            else -> Color.Transparent
        },
    )
    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(NostrordShapes.shapeMedium)
            .background(rowBackground)
            .hoverable(interaction)
            .then(
                if (onPositioned != null) {
                    Modifier.onGloballyPositioned { onPositioned(it.positionInParent().y.toInt()) }
                } else {
                    Modifier
                },
            )
            // Right-click (desktop) / long-press (mobile) opens the thread context menu.
            .then(
                rightClickContextMenuModifier { clickOffset ->
                    menuAnchorPx = clickOffset
                    menuVisible = true
                },
            ),
    ) {
        ThreadMessageContextMenu(
            visible = menuVisible,
            onDismiss = { menuVisible = false },
            anchorOffsetPx = menuAnchorPx,
            isAuthor = isAuthor,
            canShareToChat = isRoot,
            onAction = { action ->
                when (action) {
                    is MessageContextAction.QuickReact -> onReact(action.emoji)
                    MessageContextAction.AddReaction -> onOpenReactionPicker()
                    MessageContextAction.Reply -> onReply()
                    MessageContextAction.ShareToChat -> onShareToChat()
                    MessageContextAction.CopyText -> writeClipboard(msg.content)
                    // A reply links to its thread page (root id from the E tag), targeting
                    // this message via ?e= so opening it scrolls to and flashes it.
                    MessageContextAction.CopyMessageLink ->
                        writeClipboard(
                            threadShareLink(route.relayUrl, route.groupId, msg.threadRootIdTag() ?: msg.id, messageId = msg.id),
                        )
                    MessageContextAction.CopyNevent ->
                        writeClipboard(
                            Nip19.encodeNevent(msg.id, relays = listOf(route.relayUrl), authorHex = msg.pubkey, kind = msg.kind),
                        )
                    MessageContextAction.CopyEventJson -> writeClipboard(msg.toEventJson())
                    MessageContextAction.DeleteMessage -> onDelete()
                    else -> Unit
                }
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            // Avatar and author name open the user profile modal (chat parity).
            Box(modifier = Modifier.clip(NostrordShapes.shapeMedium).clickable { onUserClick(msg.pubkey) }) {
                ProfileAvatar(
                    imageUrl = meta?.picture,
                    displayName = threadDisplayName(msg.pubkey, meta),
                    pubkey = msg.pubkey,
                    size = 36.dp,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(
                        threadDisplayName(msg.pubkey, meta),
                        color = NostrordColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { onUserClick(msg.pubkey) },
                    )
                    Text(formatTimestamp(msg.createdAt), color = NostrordColors.TextMuted, fontSize = 12.sp)
                }
                if (isRoot) {
                    Text(
                        msg.threadTitle(),
                        color = NostrordColors.TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = Spacing.xs),
                    )
                }
                // Nested reply: quote the answered message above the body (placeholder when the
                // parent has not loaded), like chat's reply preview.
                if (!isRoot && (parentMsg != null || msg.threadParentIdTag() != null)) {
                    ReplyPreview(
                        parentMessage = parentMsg,
                        parentMetadata = parentMsg?.let { resolveMetadata(it.pubkey) },
                        resolveMetadata = resolveMetadata,
                    )
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    // Same rich renderer as chat: media embeds, links, mentions, custom emoji, markdown.
                    MessageContent(
                        content = msg.content,
                        tags = msg.tags,
                        currentGroupId = route.groupId,
                        currentRelayUrl = route.relayUrl,
                        onMentionClick = onUserClick,
                        onNavigateToGroup = onNavigateToGroup,
                        modifier = Modifier.weight(1f, fill = false).padding(top = 2.dp),
                    )
                    if (myPubkey != null && myPubkey == msg.pubkey && status != null) {
                        SendStateIcon(status)
                    }
                }
                if (myPubkey != null && myPubkey == msg.pubkey && status is GroupManager.MessageStatus.Failed) {
                    MessageStatusIndicator(status, onRetry, onDismiss)
                }
                // Reaction badges; adding a reaction goes through the context menu (quick row
                // or the full picker), so there is no always-visible add button.
                ReactionBadges(
                    reactions = reactions,
                    currentUserPubkey = myPubkey,
                    resolveMetadata = resolveMetadata,
                    onReactionClick = onReact,
                    pendingEmojis = pendingEmojis,
                )
            }
        }
    }
}
