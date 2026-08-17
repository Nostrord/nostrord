package org.nostr.nostrord.ui.components.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.nostr.nostrord.auth.ActiveAccountManager
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.nostr.Nip27
import org.nostr.nostrord.nostr.Nip57
import org.nostr.nostrord.ui.components.ReportUserModal
import org.nostr.nostrord.ui.components.avatars.ProfileAvatar
import org.nostr.nostrord.ui.components.zap.ZapBadge
import org.nostr.nostrord.ui.components.zap.ZapController
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.formatTime
import org.nostr.nostrord.utils.normalizeRelayUrl
import org.nostr.nostrord.utils.rememberClipboardWriter
import org.nostr.nostrord.utils.shortNpub
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Enhanced message item with grouping support.
 *
 * Interaction behavior:
 * - Desktop: right-click opens the context menu at the cursor
 * - Mobile (Android / touch): single tap opens the context menu; swipe left to reply
 *
 * Spacing:
 * - 72dp total left column (16dp padding + 40dp avatar + 16dp gap)
 * - 6dp uniform top/bottom padding on every row (no asymmetric first/last bumps)
 * - Hairline divider above each grouped message to mark the boundary
 */
@Composable
fun MessageItem(
    message: NostrGroupClient.NostrMessage,
    metadata: UserMetadata? = null,
    resolveReplyMessage: (String) -> NostrGroupClient.NostrMessage? = { null },
    resolveMetadata: (String) -> UserMetadata? = { null },
    isFirstInGroup: Boolean = true,
    isLastInGroup: Boolean = true,
    isAuthor: Boolean = false,
    isAdmin: Boolean = false,
    reactions: Map<String, GroupManager.ReactionInfo> = emptyMap(),
    pendingReactionEmojis: Set<String> = emptySet(),
    currentUserPubkey: String? = null,
    currentGroupId: String? = null,
    currentRelayUrl: String? = null,
    onReplyClick: () -> Unit = {},
    onReactionClick: () -> Unit = {},
    onReactionBadgeClick: (emoji: String) -> Unit = {},
    onCopyText: () -> Unit = {},
    onCopyLink: () -> Unit = {},
    onShareLink: () -> Unit = {},
    onCopyJson: () -> Unit = {},
    onPinMessage: () -> Unit = {},
    onDeleteMessage: () -> Unit = {},
    onUsernameClick: (String) -> Unit = {},
    onScrollToMessage: (String) -> Unit = {},
    onNavigateToGroup: (groupId: String, groupName: String?, relayUrl: String?, messageId: String?) -> Unit = { _, _, _, _ -> },
    // Relay that hosts this message's group (its kind:39000 home), embedded in the copied nevent
    // so readers fetch the event from its real relay, not the one we happen to be viewing through.
    neventRelayHint: String? = null,
    isHighlighted: Boolean = false,
    // A search hit (query matches this message) gets a light tint; the current hit a stronger one.
    isSearchHit: Boolean = false,
    isCurrentSearchHit: Boolean = false,
    isContextMenuOpen: Boolean = false,
    onContextMenuOpenChange: (Boolean) -> Unit = {},
    swipeToReplyEnabled: Boolean = false,
    // Optimistic-send delivery status for the author's own message. Null = delivered
    // (no indicator). Sending shows a clock; Failed shows a retry/dismiss row.
    messageStatus: GroupManager.MessageStatus? = null,
    onRetrySend: () -> Unit = {},
    onDismissFailed: () -> Unit = {},
) {
    // Use rememberUpdatedState to avoid recomposition when callbacks change reference
    val copyToClipboard = rememberClipboardWriter()
    var showReportModal by remember { mutableStateOf(false) }
    val currentOnUsernameClick by rememberUpdatedState(onUsernameClick)
    val currentOnReplyClick by rememberUpdatedState(onReplyClick)
    val currentOnReactionClick by rememberUpdatedState(onReactionClick)
    val currentOnReactionBadgeClick by rememberUpdatedState(onReactionBadgeClick)
    val currentOnCopyText by rememberUpdatedState(onCopyText)
    val currentOnCopyLink by rememberUpdatedState(onCopyLink)
    val currentOnShareLink by rememberUpdatedState(onShareLink)
    val currentOnCopyJson by rememberUpdatedState(onCopyJson)
    val currentOnPinMessage by rememberUpdatedState(onPinMessage)
    val currentOnDeleteMessage by rememberUpdatedState(onDeleteMessage)

    // Memoize display name calculation
    val displayName =
        remember(metadata?.displayName, metadata?.name, message.pubkey) {
            metadata?.displayName ?: metadata?.name ?: shortNpub(message.pubkey)
        }

    // Check if this message is a reply and find the parent message
    val replyParentId = remember(message.tags) { getReplyParentId(message) }

    // Collect cached events from repository for parent message lookup
    val cachedEvents by AppModule.nostrRepository.cachedEvents.collectAsState()

    // NIP-57 zaps: offer the action when the author has a Lightning address (and isn't
    // the current user), and surface the running total for this message.
    val zapTotals by AppModule.nostrRepository.zaps.collectAsState()
    val zapInfo = zapTotals[message.id]
    // Zaps require signing a kind:9734 request, so only offer them when an account
    // with a usable signer is active.
    val activeSession by ActiveAccountManager.session.collectAsState()
    val canZap = activeSession != null &&
        message.pubkey != currentUserPubkey &&
        Nip57.resolvePayEndpoint(metadata?.lud16, metadata?.lud06) != null

    // Look up parent via caller-provided resolver, then in cachedEvents
    val parentMessage =
        remember(replyParentId) {
            if (replyParentId != null) resolveReplyMessage(replyParentId) else null
        }

    // If not in allMessages, check cachedEvents and convert to NostrMessage. Only this (relay,
    // group)'s own events qualify: a `q` tag can point at an event another group put in the
    // shared by-id cache, and painting that shows a different group's message inside this one.
    // An unstamped copy (restored from disk) is accepted, like an unstamped NostrMessage.
    val parentFromCache: NostrGroupClient.NostrMessage? =
        remember(replyParentId, cachedEvents, parentMessage, currentGroupId, currentRelayUrl) {
            if (replyParentId != null && parentMessage == null) {
                cachedEvents[replyParentId]
                    ?.takeIf { cached ->
                        val cachedGroup = cached.tags.firstOrNull { it.size >= 2 && it[0] == "h" }?.get(1)
                        val groupOk = currentGroupId == null || cachedGroup == null || cachedGroup == currentGroupId
                        val relayOk = currentRelayUrl == null ||
                            cached.relayUrl == null ||
                            cached.relayUrl == currentRelayUrl.normalizeRelayUrl()
                        groupOk && relayOk
                    }?.let { cached ->
                        NostrGroupClient.NostrMessage(
                            id = cached.id,
                            pubkey = cached.pubkey,
                            content = cached.content,
                            createdAt = cached.createdAt,
                            kind = cached.kind,
                            tags = cached.tags,
                        )
                    }
            } else {
                null
            }
        }

    // Use whichever parent we found
    val resolvedParentMessage = parentMessage ?: parentFromCache

    // Request parent event from relay if not found locally
    LaunchedEffect(replyParentId, resolvedParentMessage) {
        if (replyParentId != null && resolvedParentMessage == null) {
            // Extract relay hints from q tag if available
            val qTag = message.tags.find { it.size >= 2 && it[0] == "q" && it[1] == replyParentId }
            val relayHints = qTag?.getOrNull(2)?.let { listOf(it) } ?: emptyList()

            // Also check e tag for relay hints
            val eTag = message.tags.find { it.size >= 2 && it[0] == "e" && it[1] == replyParentId }
            val eRelayHints = eTag?.getOrNull(2)?.let { listOf(it) } ?: emptyList()

            val allRelayHints = (relayHints + eRelayHints).distinct()

            AppModule.nostrRepository.requestEventById(replyParentId, allRelayHints)
        }
    }

    val parentMetadata =
        remember(resolvedParentMessage?.pubkey) {
            resolvedParentMessage?.let { resolveMetadata(it.pubkey) }
        }

    // Request metadata for parent message author if not available
    LaunchedEffect(resolvedParentMessage?.pubkey, parentMetadata) {
        if (resolvedParentMessage != null && parentMetadata == null) {
            AppModule.nostrRepository.requestUserMetadata(setOf(resolvedParentMessage.pubkey))
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    // Where to open the context menu, in px relative to this row's top-left.
    // Set from the right-click / tap position so the menu appears at the cursor (web parity).
    var menuAnchorPx by remember { mutableStateOf<Offset?>(null) }
    // Row size in px, so the position provider can offset relative to the row.
    var rowSizePx by remember { mutableStateOf(IntSize.Zero) }

    var highlightActive by remember(isHighlighted) { mutableStateOf(isHighlighted) }
    LaunchedEffect(isHighlighted) {
        if (isHighlighted) {
            highlightActive = true
            delay(2500)
            highlightActive = false
        }
    }
    val highlightColor by animateColorAsState(
        targetValue = if (highlightActive) NostrordColors.Primary.copy(alpha = 0.18f) else Color.Transparent,
        animationSpec = if (highlightActive) snap() else tween(durationMillis = 1200),
    )

    // Swipe-to-reply (touch layouts only): drag the message left to reveal a reply
    // affordance; releasing past the threshold enters reply mode and focuses the input.
    // Left direction is deliberate so it doesn't fight the left-edge swipe that opens
    // the side menu (that gesture pulls content the other way).
    val swipeScope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val swipeOffset = remember { Animatable(0f) }
    val triggerPx = with(density) { 56.dp.toPx() }
    val maxOffsetPx = with(density) { 80.dp.toPx() }
    var swipeArmed by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Hairline sits outside the hover Box so the hover/highlight background
        // doesn't extend across the boundary between two grouped messages.
        // Aligned with the content column (starts after the avatar gutter).
        if (!isFirstInGroup) {
            androidx.compose.material3.HorizontalDivider(
                thickness = Spacing.dividerThickness,
                color = NostrordColors.Divider,
                modifier =
                Modifier.padding(
                    start = Spacing.avatarColumnWidth,
                    end = Spacing.messagePaddingHorizontal,
                ),
            )
        }
        Box(
            modifier =
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { rowSizePx = it.size }
                .hoverable(interactionSource)
                // Tap (mobile) / right-click (desktop) opens the context menu directly.
                // Closing is handled by the menu's full-screen scrim (see MessageContextMenu).
                .then(
                    rightClickContextMenuModifier { clickOffset ->
                        menuAnchorPx = clickOffset // open at the cursor (web parity)
                        onContextMenuOpenChange(true)
                    },
                ).then(
                    if (swipeToReplyEnabled) {
                        Modifier.pointerInput(Unit) {
                            val touchSlop = viewConfiguration.touchSlop
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var totalX = 0f
                                var totalY = 0f
                                var claimed = false
                                var offset = 0f
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null || !change.pressed) break
                                    val delta = change.positionChange()
                                    if (!claimed) {
                                        totalX += delta.x
                                        totalY += delta.y
                                        // Vertical intent → let the list scroll; never consume.
                                        if (abs(totalY) > touchSlop && abs(totalY) >= abs(totalX)) break
                                        // Rightward intent → let the left-edge swipe open the side menu.
                                        if (totalX >= touchSlop) break
                                        // Leftward past slop → this is a reply swipe; claim it.
                                        if (totalX <= -touchSlop) claimed = true
                                    }
                                    if (claimed) {
                                        change.consume()
                                        offset = (offset + delta.x).coerceIn(-maxOffsetPx, 0f)
                                        swipeScope.launch { swipeOffset.snapTo(offset) }
                                        if (!swipeArmed && offset <= -triggerPx) {
                                            swipeArmed = true
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        } else if (swipeArmed && offset > -triggerPx) {
                                            swipeArmed = false
                                        }
                                    }
                                }
                                if (claimed && offset <= -triggerPx) currentOnReplyClick()
                                swipeArmed = false
                                swipeScope.launch { swipeOffset.animateTo(0f) }
                            }
                        }
                    } else {
                        Modifier
                    },
                ).background(
                    when {
                        isContextMenuOpen -> NostrordColors.SurfaceVariant
                        // Search-hit tints win over hover so the current-match cue survives the
                        // pointer landing on the row (matches the web cascade, where .msg.search-*
                        // is declared after .msg:hover at equal specificity).
                        isCurrentSearchHit -> NostrordColors.Primary.copy(alpha = 0.30f)
                        isSearchHit -> NostrordColors.Primary.copy(alpha = 0.12f)
                        // Hover tint is a desktop-only pointer affordance; a touch tap fires
                        // isPressed too, so suppress it in compact/touch layouts.
                        !swipeToReplyEnabled && (isHovered || isPressed) -> NostrordColors.MessageHover
                        else -> highlightColor
                    },
                ),
        ) {
            // Reply affordance revealed underneath the message as it slides left.
            if (swipeToReplyEnabled && swipeOffset.value < 0f) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Reply,
                    contentDescription = null,
                    tint = if (swipeArmed) NostrordColors.Primary else NostrordColors.TextMuted,
                    modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = Spacing.messagePaddingHorizontal)
                        .size(24.dp)
                        .alpha((-swipeOffset.value / triggerPx).coerceIn(0f, 1f)),
                )
            }
            // Main message content - this defines the Box size
            Row(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(swipeOffset.value.roundToInt(), 0) }
                    .padding(
                        start = Spacing.messagePaddingHorizontal,
                        end = Spacing.messagePaddingHorizontal,
                        top = Spacing.messageGroupGap,
                        bottom = Spacing.messageGroupGap,
                    ),
            ) {
                // Avatar column - 72dp total width
                Box(
                    modifier = Modifier.width(Spacing.avatarColumnWidth - Spacing.messagePaddingHorizontal),
                    contentAlignment = Alignment.TopStart,
                ) {
                    if (isFirstInGroup) {
                        Box(
                            modifier =
                            Modifier
                                .size(Spacing.avatarSize)
                                .clip(CircleShape)
                                .clickable { currentOnUsernameClick(message.pubkey) }
                                .pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            ProfileAvatar(
                                imageUrl = metadata?.picture,
                                displayName = displayName,
                                pubkey = message.pubkey,
                                size = Spacing.avatarSize,
                            )
                        }
                    } else if (isHovered) {
                        // No padding/fixed height: this lives in the main Row, so any
                        // extra vertical footprint here would grow the row on hover.
                        Text(
                            text = formatTime(message.createdAt),
                            color = NostrordColors.TextMuted,
                            style = NostrordTypography.Timestamp,
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    // Header with name and time - only for first in group
                    if (isFirstInGroup) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = displayName,
                                color = NostrordColors.TextPrimary,
                                style = NostrordTypography.Username,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier =
                                Modifier
                                    .weight(1f, fill = false)
                                    .clickable { currentOnUsernameClick(message.pubkey) }
                                    .pointerHoverIcon(PointerIcon.Hand),
                            )
                            Spacer(modifier = Modifier.width(Spacing.sm))
                            Text(
                                text = formatTime(message.createdAt),
                                color = NostrordColors.TextMuted,
                                style = NostrordTypography.Timestamp,
                                maxLines = 1,
                            )
                        }
                        Spacer(modifier = Modifier.height(Spacing.xs))
                    }

                    // Reply preview — shown whenever this message is a reply,
                    // even if the parent hasn't loaded yet (ReplyPreview handles null with a placeholder)
                    if (replyParentId != null) {
                        ReplyPreview(
                            parentMessage = resolvedParentMessage,
                            parentMetadata = parentMetadata,
                            resolveMetadata = resolveMetadata,
                            onReplyClick = { onScrollToMessage(replyParentId) },
                        )
                        Spacer(modifier = Modifier.height(Spacing.xs))
                    }

                    // Message content with NIP-30 custom emoji support. The send-state
                    // icon (own messages: clock while sending, check when delivered)
                    // shares this row so the chat never gains an extra line and shifts.
                    Row(verticalAlignment = Alignment.Bottom) {
                        Box(modifier = Modifier.weight(1f, fill = false)) {
                            MessageContent(
                                content = message.content,
                                tags = message.tags,
                                onMentionClick = currentOnUsernameClick,
                                onHashtagClick = { hashtag ->
                                    // TODO: Implement hashtag click handler (e.g., search for hashtag)
                                },
                                currentGroupId = currentGroupId,
                                currentRelayUrl = currentRelayUrl,
                                onNavigateToGroup = onNavigateToGroup,
                            )
                        }
                        if (isAuthor && messageStatus != null) {
                            SendStateIcon(messageStatus)
                        }
                    }

                    // Failure row (own messages only): Not delivered + Retry / Dismiss.
                    if (isAuthor && messageStatus is GroupManager.MessageStatus.Failed) {
                        MessageStatusIndicator(
                            status = messageStatus,
                            onRetry = onRetrySend,
                            onDismiss = onDismissFailed,
                        )
                    }

                    // Reaction badges (plus spinner placeholders for in-flight reactions)
                    if (reactions.isNotEmpty() || pendingReactionEmojis.isNotEmpty()) {
                        ReactionBadges(
                            reactions = reactions,
                            currentUserPubkey = currentUserPubkey,
                            resolveMetadata = resolveMetadata,
                            onReactionClick = onReactionBadgeClick,
                            pendingEmojis = pendingReactionEmojis,
                        )
                    }

                    if (zapInfo != null && zapInfo.totalMsats > 0) {
                        ZapBadge(
                            totalMsats = zapInfo.totalMsats,
                            count = zapInfo.count,
                            zappedByMe = currentUserPubkey != null && currentUserPubkey in zapInfo.zappers,
                            onClick = { if (canZap) ZapController.request(message.pubkey, message.id) },
                        )
                    }
                }
            }

            // Context menu - appears on right-click (desktop) or tap (touch)
            // This is a Popup so it floats outside the layout tree
            MessageContextMenu(
                visible = isContextMenuOpen,
                anchorOffsetPx = menuAnchorPx,
                anchorWidthPx = rowSizePx.width,
                onDismiss = { onContextMenuOpenChange(false) },
                onAction = { action ->
                    when (action) {
                        MessageContextAction.AddReaction -> currentOnReactionClick()
                        is MessageContextAction.QuickReact -> currentOnReactionBadgeClick(action.emoji)
                        MessageContextAction.Reply -> currentOnReplyClick()
                        MessageContextAction.CopyText -> currentOnCopyText()
                        MessageContextAction.CopyMessageLink -> currentOnCopyLink()
                        MessageContextAction.ShareMessageLink -> currentOnShareLink()
                        // The message carries id + author + kind; the group relay rides as the
                        // relay hint so the nevent is fetchable. Copied with the NIP-21 prefix:
                        // pasted into a message it has to read as a reference, and clients that
                        // follow the spec (Jumble among them) render nothing without it.
                        MessageContextAction.CopyNevent ->
                            copyToClipboard(
                                Nip27.createUri(
                                    Nip19.encodeNevent(
                                        message.id,
                                        relays = listOfNotNull(neventRelayHint ?: currentRelayUrl),
                                        authorHex = message.pubkey,
                                        kind = message.kind,
                                    ),
                                ),
                            )
                        MessageContextAction.CopyEventJson -> currentOnCopyJson()
                        MessageContextAction.PinMessage -> currentOnPinMessage()
                        MessageContextAction.DeleteMessage -> currentOnDeleteMessage()
                        MessageContextAction.ZapMessage -> ZapController.request(message.pubkey, message.id)
                        MessageContextAction.ReportMessage -> showReportModal = true
                        // Thread-menu-only action; the chat menu never offers it.
                        MessageContextAction.ShareToChat -> Unit
                    }
                },
                isAuthor = isAuthor,
                isAdmin = isAdmin,
                canZap = canZap,
            )

            if (showReportModal) {
                ReportUserModal(
                    pubkey = message.pubkey,
                    metadata = metadata ?: resolveMetadata(message.pubkey),
                    eventId = message.id,
                    onDismiss = { showReportModal = false },
                )
            }
        }
    }
}

// MessageStatusIndicator lives in its own file now (shared by chat + threads).
