package org.nostr.nostrord.ui.screens.group

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.ui.components.avatars.ProfileAvatar
import org.nostr.nostrord.ui.components.buttons.AppButton
import org.nostr.nostrord.ui.components.buttons.AppButtonSize
import org.nostr.nostrord.ui.components.chat.MessageComposer
import org.nostr.nostrord.ui.components.chat.MessageStatusIndicator
import org.nostr.nostrord.ui.components.chat.SendStateIcon
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.navigation.HashRoute
import org.nostr.nostrord.ui.screens.group.components.CreateThreadDialog
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.formatTimestamp
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
    val myPubkey = remember { vm.getPublicKey() }

    // Keep the open thread synced with the route (#/g/<relay>/<id>/threads/<rootId>).
    LaunchedEffect(route.threadRootId) { vm.openThread(route.threadRootId) }

    var showCompose by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf(TextFieldValue("")) }
    var sending by remember { mutableStateOf(false) }

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
                    IconButton(onClick = { showDeleteConfirm = true }) {
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
                    ThreadMessage(
                        detail.root,
                        userMetadata,
                        isRoot = true,
                        myPubkey,
                        messageStatus[detail.root.id],
                        { vm.retrySend(detail.root.id) },
                        { vm.dismissFailed(detail.root.id) },
                    )
                    Text(
                        if (detail.replies.size == 1) "1 REPLY" else "${detail.replies.size} REPLIES",
                        color = NostrordColors.TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(vertical = Spacing.sm),
                    )
                    detail.replies.forEach {
                        ThreadMessage(
                            it,
                            userMetadata,
                            isRoot = false,
                            myPubkey,
                            messageStatus[it.id],
                            { vm.retrySend(it.id) },
                            { vm.dismissFailed(it.id) },
                        )
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
                            ThreadCard(t, userMetadata) { onNavigate(route.copy(threadRootId = t.rootId)) }
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

    if (showDeleteConfirm) {
        val ownRoot = openThread?.root
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete thread?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    if (ownRoot != null) {
                        vm.deleteThread(ownRoot.id)
                        onBack()
                    }
                }) { Text("Delete", color = NostrordColors.Error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
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
private fun ThreadCard(t: ThreadSummary, userMetadata: Map<String, UserMetadata>, onClick: () -> Unit) {
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
            val replies = if (t.replyCount == 1) "1 reply" else "${t.replyCount} replies"
            Text(
                "${threadDisplayName(t.authorPubkey, meta)} · $replies · ${formatTimestamp(t.lastActivity)}",
                color = NostrordColors.TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun ThreadMessage(
    msg: NostrGroupClient.NostrMessage,
    userMetadata: Map<String, UserMetadata>,
    isRoot: Boolean,
    myPubkey: String?,
    status: GroupManager.MessageStatus?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val meta = userMetadata[msg.pubkey]
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
                Text(
                    msg.content,
                    color = NostrordColors.TextContent,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f, fill = false).padding(top = 2.dp),
                )
                if (myPubkey != null && myPubkey == msg.pubkey && status != null) {
                    SendStateIcon(status)
                }
            }
            if (myPubkey != null && myPubkey == msg.pubkey && status is GroupManager.MessageStatus.Failed) {
                MessageStatusIndicator(status, onRetry, onDismiss)
            }
        }
    }
}
