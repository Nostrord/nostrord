package org.nostr.nostrord.ui.screens.group

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.managers.GroupManager
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
import org.nostr.nostrord.ui.components.chat.SendStateIcon
import org.nostr.nostrord.ui.components.chat.ThreadMessageContextMenu
import org.nostr.nostrord.ui.components.chat.rightClickContextMenuModifier
import org.nostr.nostrord.ui.components.emoji.EmojiPicker
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.navigation.HashRoute
import org.nostr.nostrord.ui.screens.group.components.CreateThreadDialog
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
    onOpenDrawer: () -> Unit = {},
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
    val myPubkey = remember { vm.getPublicKey() }

    // Full-picker target: the (eventId, authorPubkey) of the message being reacted to.
    var reactingTo by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Keep the open thread synced with the route (#/g/<relay>/<id>/threads/<rootId>).
    LaunchedEffect(route.threadRootId) { vm.openThread(route.threadRootId) }

    var showCompose by remember { mutableStateOf(false) }
    // Message pending delete confirmation (the root or any reply, from the header or the menu).
    var deleteTarget by remember { mutableStateOf<NostrGroupClient.NostrMessage?>(null) }
    var reply by remember { mutableStateOf(TextFieldValue("")) }
    var sending by remember { mutableStateOf(false) }
    // Shared with MessageContent via LocalImageViewerUrl: tap an inline image -> fullscreen viewer.
    val imageViewerUrl = remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(NostrordColors.Background)) {
        if (route.threadRootId != null) {
            // ---- Single thread (detail) ----
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to threads",
                        tint = NostrordColors.TextSecondary,
                    )
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
            }
            HorizontalDivider(color = NostrordColors.Divider)

            val detail = openThread
            if (detail == null) {
                EmptyState("Loading thread...")
            } else {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .verticalScroll(rememberScrollState()).padding(Spacing.md),
                ) {
                    CompositionLocalProvider(
                        LocalImageViewerUrl provides imageViewerUrl,
                        LocalAnimatedImageHidden provides (imageViewerUrl.value != null),
                    ) {
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
                                resolveMetadata = { userMetadata[it] },
                                onReact = { emoji -> vm.sendReaction(msg.id, msg.pubkey, emoji) },
                                onOpenReactionPicker = { reactingTo = msg.id to msg.pubkey },
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
                MessageComposer(
                    value = reply,
                    onValueChange = { reply = it },
                    onSend = {
                        if (reply.text.isNotBlank() && !sending) {
                            sending = true
                            vm.sendReply(
                                reply.text.trim(),
                                onSuccess = {
                                    reply = TextFieldValue("")
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
        } else {
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
                            ) { onNavigate(route.copy(threadRootId = t.rootId)) }
                        }
                    }
            }
        }
    }

    if (showCompose) {
        CreateThreadDialog(
            onDismiss = { showCompose = false },
            onCreate = { title, content -> vm.createThread(title, content) },
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
    onClick: () -> Unit,
) {
    val meta = userMetadata[t.authorPubkey]
    Row(
        modifier = Modifier.fillMaxWidth().clip(NostrordShapes.shapeLarge).clickable(onClick = onClick).padding(Spacing.md),
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
    resolveMetadata: (String) -> UserMetadata?,
    onReact: (String) -> Unit,
    onOpenReactionPicker: () -> Unit,
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
    Box(
        modifier =
        Modifier
            .fillMaxWidth()
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
            onAction = { action ->
                when (action) {
                    is MessageContextAction.QuickReact -> onReact(action.emoji)
                    MessageContextAction.AddReaction -> onOpenReactionPicker()
                    MessageContextAction.CopyText -> writeClipboard(msg.content)
                    MessageContextAction.DeleteMessage -> onDelete()
                    else -> Unit
                }
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            ProfileAvatar(
                imageUrl = meta?.picture,
                displayName = threadDisplayName(msg.pubkey, meta),
                pubkey = msg.pubkey,
                size = 36.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(
                        threadDisplayName(msg.pubkey, meta),
                        color = NostrordColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
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
                Row(verticalAlignment = Alignment.Bottom) {
                    // Same rich renderer as chat: media embeds, links, mentions, custom emoji, markdown.
                    MessageContent(
                        content = msg.content,
                        tags = msg.tags,
                        currentGroupId = route.groupId,
                        currentRelayUrl = route.relayUrl,
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
