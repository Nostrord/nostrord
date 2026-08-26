package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.NostrGroupClient.NostrMessage
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.network.toEventJson
import org.nostr.nostrord.ui.components.badges.UnreadBadge
import org.nostr.nostrord.ui.components.chat.DateSeparator
import org.nostr.nostrord.ui.components.chat.ImageViewerModal
import org.nostr.nostrord.ui.components.chat.LocalAnimatedImageHidden
import org.nostr.nostrord.ui.components.chat.LocalImageViewerUrl
import org.nostr.nostrord.ui.components.chat.MessageItem
import org.nostr.nostrord.ui.components.chat.MessageSelectionContainer
import org.nostr.nostrord.ui.components.chat.NewMessagesDivider
import org.nostr.nostrord.ui.components.chat.SystemEventItem
import org.nostr.nostrord.ui.components.chat.ZapEventItem
import org.nostr.nostrord.ui.components.emoji.EmojiPicker
import org.nostr.nostrord.ui.components.scrollbar.VerticalScrollbarWrapper
import org.nostr.nostrord.ui.screens.group.model.ChatItem
import org.nostr.nostrord.ui.scroll.ChatScrollPolicy
import org.nostr.nostrord.ui.scroll.JumpPillTarget
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.ui.util.buildShareMessageLink
import org.nostr.nostrord.utils.groupKey
import org.nostr.nostrord.utils.rememberClipboardWriter
import org.nostr.nostrord.utils.rememberTextSharer
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Jump so the match at [index] lands at the top of the viewport (just below the search-bar overlay,
 * which the list reserves via its top contentPadding). This is the native analogue of the web's
 * scrollIntoView: [requestScrollToItem] resolves the jump in a single measure pass, so it is
 * virtualized (no intermediate rows are composed even when the match is thousands of items away) and
 * is not cancelled by a focused field's BringIntoView. The previous pixel-estimation loop scrolled by
 * an estimated distance and waited a frame between tries while holding the scroll lock; on a far jump
 * with variable row heights it could thrash against that BringIntoView arbitration and saturate the
 * frame loop, freezing the feed. A measure-time request has nothing to contend with.
 */
private fun LazyListState.scrollSearchHitToTop(index: Int) {
    requestScrollToItem(index)
}

/**
 * Messages list with infinite scroll pagination.
 * LazyListState starts at the last item; key-based anchor correction
 * keeps the viewport stable when older messages are prepended.
 */
@Composable
fun MessagesList(
    groupId: String,
    /** Relay this group is open on. Every copied nevent/link points here. */
    hostRelayUrl: String = "",
    chatItems: List<ChatItem>,
    messages: List<NostrGroupClient.NostrMessage> = emptyList(),
    userMetadata: Map<String, UserMetadata>,
    reactions: Map<String, Map<String, GroupManager.ReactionInfo>> = emptyMap(),
    // In-flight reactions keyed "$messageId|$emoji"; rendered as spinner placeholders.
    pendingReactions: Set<String> = emptySet(),
    // Optimistic-send delivery status for own messages, keyed by event id.
    messageStatus: Map<String, GroupManager.MessageStatus> = emptyMap(),
    onRetrySend: (eventId: String) -> Unit = {},
    onDismissFailed: (eventId: String) -> Unit = {},
    currentUserPubkey: String? = null,
    isJoined: Boolean,
    isInitialLoading: Boolean = false,
    isPendingApproval: Boolean = false,
    isGroupRestricted: Boolean = false,
    // True while the composer is in reply mode; entering it nudges the replied-to
    // message into view if the grown reply bar would cover it.
    isReplying: Boolean = false,
    // Id of the message being replied to, for the reply-scroll nudge above.
    replyTargetId: String? = null,
    isLoadingMore: Boolean = false,
    hasMoreMessages: Boolean = true,
    // Pagination stalled after repeated unanswered scroll-back REQs; shows the retry row.
    isLoadStalled: Boolean = false,
    onLoadMore: () -> Unit = {},
    onRetryLoadMore: () -> Unit = {},
    onUsernameClick: (String) -> Unit = {},
    onReplyClick: (NostrMessage) -> Unit = {},
    onDeleteMessage: (NostrMessage) -> Unit = {},
    onReactionBadgeClick: (messageId: String, emoji: String) -> Unit = { _, _ -> },
    onScrollToMessage: (String) -> Unit = {},
    onNavigateToGroup: (groupId: String, groupName: String?, relayUrl: String?, messageId: String?) -> Unit = { _, _, _, _ -> },
    onReachedBottom: () -> Unit = {},
    // Fired when the user scrolls up away from the bottom. Used by the
    // "New messages" divider dismissal (issue #83).
    onLeftBottom: () -> Unit = {},
    // Fired with the createdAt of the latest fully-visible message — drives
    // partial-read tracking (mark-as-read up to the message the user has
    // actually reached, instead of the binary "all or nothing" of
    // onReachedBottom).
    onSeenUpTo: (Long) -> Unit = {},
    // Fired once the "New messages" divider has entered the viewport as a result
    // of a genuine user scroll (gated on sawNotBottom, so the entry settle at the
    // bottom never triggers it). The caller consumes the divider: the user has now
    // seen where new messages begin (issue #83).
    onDividerSeen: () -> Unit = {},
    // Count of unread messages from other users — drives the FAB badge
    // (Telegram pattern: when there are unread messages and the user has
    // scrolled away, the jump-to-bottom button shows a count badge).
    unreadFromOthersCount: Int = 0,
    targetMessageId: String? = null,
    onTargetConsumed: () -> Unit = {},
    onFetchTargetById: (String) -> Unit = {},
    swipeToReplyEnabled: Boolean = false,
    // In-chat search: ids of all matching messages (light tint) and the current match
    // (stronger tint + scroll-into-view). Empty / null when search is inactive.
    searchHitIds: Set<String> = emptySet(),
    currentSearchHitId: String? = null,
    // Bumped on every prev/next press so re-pressing scrolls back to the current match even when the
    // id does not change (e.g. a single match, or re-centering after the user scrolled away).
    searchScrollNonce: Int = 0,
    // True while the in-chat search bar is shown. The bar renders as an OVERLAY over the top of the
    // message area (drawn by `searchBar`), not as an in-flow sibling, so toggling it never resizes
    // the LazyColumn viewport. That removes the relayout that made the FIRST programmatic
    // search-scroll race and get dropped (web parity: there the bar is position:absolute). The list
    // gets a matching top contentPadding so content rides below the bar instead of under it.
    searchActive: Boolean = false,
    searchBar: @Composable () -> Unit = {},
) {
    val currentOnUsernameClick by rememberUpdatedState(onUsernameClick)
    val currentOnReplyClick by rememberUpdatedState(onReplyClick)
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    val currentOnReachedBottom by rememberUpdatedState(onReachedBottom)
    val currentOnLeftBottom by rememberUpdatedState(onLeftBottom)
    val currentOnSeenUpTo by rememberUpdatedState(onSeenUpTo)
    val currentOnDividerSeen by rememberUpdatedState(onDividerSeen)
    val currentOnFetchTargetById by rememberUpdatedState(onFetchTargetById)
    val currentChatItems by rememberUpdatedState(chatItems)

    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var reactingToMessageId by remember { mutableStateOf<String?>(null) }
    // Hoisted so only one message context menu can be open at a time
    var openContextMenuId by remember { mutableStateOf<String?>(null) }
    // Guard against the web-mobile "blink": a single tap on the source message can both
    // dismiss the menu and re-fire the open gesture, reopening it. After a menu closes,
    // briefly ignore an open request for that same message so it does not flash back open.
    var lastClosedMenuId by remember { mutableStateOf<String?>(null) }
    var lastClosedMark by remember { mutableStateOf<TimeMark?>(null) }
    val closeContextMenu = {
        lastClosedMenuId = openContextMenuId
        lastClosedMark = TimeSource.Monotonic.markNow()
        openContextMenuId = null
    }
    val imageViewerUrl = remember { mutableStateOf<String?>(null) }
    val currentRelayUrl by AppModule.nostrRepository.currentRelayUrl.collectAsState()
    // Copied nevents and links carry the relay this group is open on. Scanning for "some relay
    // serving this id" would hand out the other relay's same-id group, and the link would open a
    // stranger's chat.
    val neventRelay = hostRelayUrl.ifBlank { currentRelayUrl.orEmpty() }.takeIf { it.isNotBlank() }
    val copyToClipboard = rememberClipboardWriter()
    val shareText = rememberTextSharer()

    // Hide ALL animated HTML overlays when the image viewer modal is open
    val parentHidden = LocalAnimatedImageHidden.current

    // Initialize at the bottom so the chat opens at the newest messages. The
    // entry-time alignment to the "New messages" divider (Telegram pattern)
    // happens via a one-shot LaunchedEffect below — keying that off the divider
    // index here would re-anchor every time pagination prepended older messages
    // and pushed the index forward.
    val hasItems = chatItems.isNotEmpty()
    val listState =
        remember(groupId, hasItems) {
            if (hasItems) {
                LazyListState(firstVisibleItemIndex = chatItems.lastIndex)
            } else {
                LazyListState()
            }
        }
    // Scroll position belongs to (relay, group): the twin group on another relay has its own.
    val chatKey = groupKey(hostRelayUrl, groupId)
    val scrollStateHolder = rememberScrollStateHolder(chatKey)
    val isSeekingTarget = targetMessageId != null

    // Two-stage jump pill: the first tap focuses the "New messages" divider, the next
    // drops to the bottom. Landing on the divider at entry counts as the first stage,
    // so the first pill tap from there goes straight to the latest.
    var dividerSeen by remember(groupId) { mutableStateOf(false) }

    // The group always opens at the bottom (newest). The "New messages" line still
    // renders in the list, and the jump pill still offers a tap to it (see dividerSeen
    // below), but we no longer auto-scroll to the divider on entry: it landed far up in
    // unread history and dragged the view away from the latest. Resolving the entry as
    // "bottom" (hasDivider = false) keeps atBottom = true and still latches entryResolved,
    // so a divider that appears LATER (a message arriving while reading history) does not
    // re-anchor the view.
    LaunchedEffect(groupId, chatItems) {
        if (scrollStateHolder.entryResolved || chatItems.isEmpty()) return@LaunchedEffect
        scrollStateHolder.applyEntryChange(hasDivider = false, isSeeking = isSeekingTarget)
    }

    fun getItemKey(item: ChatItem): String = when (item) {
        is ChatItem.DateSeparator -> "date_${item.date}"
        is ChatItem.NewMessagesDivider -> "new_messages_divider"
        is ChatItem.SystemEvent -> "system_${item.id}"
        is ChatItem.Message -> "msg_${item.message.id}"
        is ChatItem.ZapEvent -> "zap_${item.id}"
    }

    val currentOnTargetConsumed by rememberUpdatedState(onTargetConsumed)

    // Correct scroll position after pagination prepends items.
    val currentHasMore by rememberUpdatedState(hasMoreMessages)
    val currentIsLoadingMore by rememberUpdatedState(isLoadingMore)
    // While search is open the scroll-to-top auto-pagination is suppressed (see the load-more trigger
    // below): walking prev/next lands on the oldest-loaded matches near the top, which would otherwise
    // trip that trigger on every press and snowball history loads (each new page also fans out a quote
    // prefetch), freezing the feed. Deliberate digs go through the search bar's "search older" affordance.
    val currentSearchActive by rememberUpdatedState(searchActive)

    ScrollPositionEffect(
        chatKey = chatKey,
        listState = listState,
        items = chatItems,
        stateHolder = scrollStateHolder,
        getItemKey = ::getItemKey,
        initialScrollToEnd = !isSeekingTarget,
    )

    AutoScrollEffect(
        listState = listState,
        items = chatItems,
        getItemKey = ::getItemKey,
        enabled = (scrollStateHolder.isRestored || !scrollStateHolder.isRestorationPending) && !isSeekingTarget,
        isFromCurrentUser = { item ->
            item is ChatItem.Message &&
                currentUserPubkey != null &&
                item.message.pubkey == currentUserPubkey
        },
        isPinnedToBottom = { scrollStateHolder.atBottom },
    )

    // Fetch by ID immediately — covers cursor-drift misses independently of pagination.
    LaunchedEffect(groupId, targetMessageId) {
        val id = targetMessageId ?: return@LaunchedEffect
        currentOnFetchTargetById(id)
    }

    var highlightedMessageId by remember(groupId) { mutableStateOf<String?>(null) }
    var internalScrollTarget by remember(groupId) { mutableStateOf<String?>(null) }

    // Measured height of the search-bar overlay, used as the top contentPadding for the LazyColumn
    // (content rides below the bar, never under it) and as the scroll offset so a matched row lands
    // just below the bar instead of behind it. Zero while search is inactive.
    var searchBarHeightPx by remember { mutableStateOf(0) }

    // In-chat search: scroll the current match into view (parity with web's scrollIntoView; the
    // search-current tint is the cue, no flash). searchScrollTarget holds the id to seek: armed from
    // currentSearchHitId and re-armed by searchScrollNonce so re-pressing prev/next onto the same id
    // scrolls again, and cleared one-shot once landed so later messages don't yank the view back.
    var searchScrollTarget by remember(groupId) { mutableStateOf<String?>(null) }
    LaunchedEffect(currentSearchHitId, searchScrollNonce) {
        if (currentSearchHitId != null) searchScrollTarget = currentSearchHitId
    }
    // Same seek shape as the deep-link / reply seeks below: keyed on the target plus the list size and
    // pagination flags, so it pages older history when the match is not loaded yet. The list's top
    // contentPadding (the overlay bar height) drops the matched row just below the bar.
    LaunchedEffect(searchScrollTarget, chatItems.size, hasMoreMessages, isLoadingMore) {
        val id = searchScrollTarget ?: return@LaunchedEffect
        val idx = chatItems.indexOfFirst { it is ChatItem.Message && it.message.id == id }
        when {
            idx >= 0 -> {
                // UserInput priority: the search TextField holds focus while typing, and a focused
                // TextField's BringIntoView runs a Default-priority scroll that cancels a plain
                // scrollToItem (also Default), leaving the jump a no-op (the tint moves but the feed
                // does not). UserInput preempts it, the same way the user's own scroll gesture
                // "unsticks" it. Pages toward the row, then nudges it to the top.
                listState.scrollSearchHitToTop(idx)
                searchScrollTarget = null
            }
            chatItems.isNotEmpty() && hasMoreMessages && !isLoadingMore -> currentOnLoadMore()
        }
    }

    LaunchedEffect(internalScrollTarget, chatItems.size, hasMoreMessages, isLoadingMore) {
        val id = internalScrollTarget ?: return@LaunchedEffect
        val idx = chatItems.indexOfFirst { it is ChatItem.Message && it.message.id == id }
        when {
            idx >= 0 -> {
                highlightedMessageId = id
                listState.animateScrollToItem(idx)
                internalScrollTarget = null
            }
            chatItems.isNotEmpty() && hasMoreMessages && !isLoadingMore -> {
                currentOnFetchTargetById(id)
                currentOnLoadMore()
            }
        }
    }

    LaunchedEffect(internalScrollTarget, hasMoreMessages, isLoadingMore) {
        val id = internalScrollTarget ?: return@LaunchedEffect
        if (hasMoreMessages || isLoadingMore) return@LaunchedEffect
        kotlinx.coroutines.delay(500)
        val snapshot = currentChatItems
        val idx = snapshot.indexOfFirst { it is ChatItem.Message && it.message.id == id }
        if (idx >= 0) {
            highlightedMessageId = id
            listState.animateScrollToItem(idx)
        }
        internalScrollTarget = null
    }

    // Whether the feed is pinned at the bottom, tracked continuously and ignoring transient
    // empty layouts (during recomposition layoutInfo can be momentarily empty). Reading
    // layoutInfo at the instant reply mode toggles was racy — an empty read skipped the
    // re-pin and let the bar hide the last message. This holds the last known-good state.
    val pinnedAtBottom = remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index
            val total = info.totalItemsCount
            if (last == null || total == 0) null else last >= total - 2
        }.collect { atBottom -> if (atBottom != null) pinnedAtBottom.value = atBottom }
    }

    // Entering reply mode grows the composer with the reply bar, which can cover the
    // message being replied to. Nudge that row just into view above the composer
    // (Compose equivalent of the web's scrollIntoView block:'nearest'): only scroll
    // if the row is actually below the shrunken viewport, so replying to an already
    // visible message doesn't yank the feed.
    LaunchedEffect(replyTargetId) {
        val id = replyTargetId ?: return@LaunchedEffect
        // Wait for the reply bar to grow and the list to relayout before measuring.
        kotlinx.coroutines.delay(50)
        val idx = chatItems.indexOfFirst { it is ChatItem.Message && it.message.id == id }
        if (idx < 0) return@LaunchedEffect
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == idx }
        if (item == null) {
            // Not in view at all (covered below, or scrolled off): bring it to the bottom.
            listState.animateScrollToItem(idx)
        } else {
            val overflow = (item.offset + item.size) - info.viewportEndOffset
            if (overflow > 0) listState.animateScrollBy(overflow.toFloat())
        }
    }

    // hasMoreMessages and isLoadingMore are keys so the effect re-fires on the
    // InitialLoading→HasMore transition (state change without chatItems.size changing).
    LaunchedEffect(chatItems.size, targetMessageId, hasMoreMessages, isLoadingMore) {
        val id = targetMessageId ?: return@LaunchedEffect
        val idx = chatItems.indexOfFirst { it is ChatItem.Message && it.message.id == id }
        when {
            idx >= 0 -> {
                highlightedMessageId = id
                listState.scrollToItem(idx)
                currentOnTargetConsumed()
            }
            chatItems.isNotEmpty() && hasMoreMessages && !isLoadingMore -> currentOnLoadMore()
        }
    }

    // Fallback after exhaustion: delay 500ms to let the eventOrderingBuffer (300ms debounce)
    // flush the last page, then check once more before giving up.
    // Empty snapshot means the relay served nothing (relay switch in progress) — preserve target.
    LaunchedEffect(targetMessageId, hasMoreMessages, isLoadingMore) {
        val id = targetMessageId ?: return@LaunchedEffect
        if (hasMoreMessages || isLoadingMore) return@LaunchedEffect
        kotlinx.coroutines.delay(500)
        val snapshot = currentChatItems
        val idx = snapshot.indexOfFirst { it is ChatItem.Message && it.message.id == id }
        if (idx >= 0) {
            highlightedMessageId = id
            listState.scrollToItem(idx)
            currentOnTargetConsumed()
            return@LaunchedEffect
        }
        if (snapshot.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(snapshot.lastIndex)
        currentOnTargetConsumed()
    }

    // NO manual restore after a prepend: LazyListState anchors on the KEY of the first
    // visible item and re-derives its index itself when older messages land in front, so
    // the rows being read stay put. A second, hand-rolled restore raced the position-save
    // collector that feeds it and, with the reader at the bottom, out-ran the bottom pin:
    // a batch that both merged an out-of-order older event (the head key changes) and
    // appended a live one put the old top row back at the top and dragged the view up.
    // Keys must stay stable (getItemKey) for that anchoring to hold.

    // Load more when scrolled near top.
    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val firstVisibleItem = layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: Int.MAX_VALUE
            val totalItems = layoutInfo.totalItemsCount
            Triple(firstVisibleItem, totalItems, currentHasMore && !currentIsLoadingMore && totalItems > 0 && !currentSearchActive)
        }.distinctUntilChanged()
            .filter { (firstVisible, _, canLoad) ->
                // Only paginate once the user has actually scrolled up off the bottom. On open the
                // view sits at the bottom (atBottom = true), so entering a group never triggers a
                // history load on its own; pagination resumes when the user scrolls toward the top.
                firstVisible <= 5 && canLoad && !scrollStateHolder.atBottom
            }.collect {
                currentOnLoadMore()
            }
    }

    // Mark as read when the user has scrolled to (or is pinned at) the bottom.
    // Debounced so rapid scrolls don't hammer storage. Also fires onLeftBottom on
    // the opposite transition so callers can detect a round-trip (used by the
    // "New messages" divider dismissal — issue #83).
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = layoutInfo.totalItemsCount
            lastVisible >= 0 && total > 0 && lastVisible >= total - 2
        }.distinctUntilChanged()
            .debounce(500)
            .collect { atBottom ->
                if (atBottom) currentOnReachedBottom() else currentOnLeftBottom()
            }
    }

    // Partial-read tracking: emit the createdAt of the latest *fully-visible*
    // message message so the screen can advance lastReadTimestamp incrementally.
    // Fixes the Telegram bug where scrolling one of ten unread messages marked
    // all ten as read — the user only gets credit for what they actually saw.
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val viewportEnd = layoutInfo.viewportEndOffset
            layoutInfo.visibleItemsInfo
                .filter { it.offset + it.size <= viewportEnd }
                .mapNotNull { info ->
                    (currentChatItems.getOrNull(info.index) as? ChatItem.Message)
                        ?.message?.createdAt
                }
                .maxOrNull() ?: 0L
        }.debounce(500)
            .filter { it > 0L }
            .distinctUntilChanged()
            .collect { currentOnSeenUpTo(it) }
    }

    // Latches once the divider row has been on screen this entry — sitting at the bottom
    // on open (a small unread batch) or scrolled into view (a large one). The dismissal
    // below reads this latch instead of live visibility: with a single unread message the
    // divider sits one row above the newest, so it slides off the bottom edge in the same
    // frame sawNotBottom flips, and a live check would miss the overlap and strand the
    // line until the next reach-bottom or send.
    var dividerEverVisible by remember(groupId) { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow {
            val dividerIndex = currentChatItems.indexOfFirst { it is ChatItem.NewMessagesDivider }
            dividerIndex >= 0 && listState.layoutInfo.visibleItemsInfo.any { it.index == dividerIndex }
        }.distinctUntilChanged().filter { it }.collect { dividerEverVisible = true }
    }

    // Dismiss the "New messages" divider once the user has both seen it (dividerEverVisible)
    // and engaged a genuine scroll-away this entry (sawNotBottom). Gating on sawNotBottom
    // stops the entry settle at the bottom from consuming it unseen (issue #83); gating on
    // the latch rather than live visibility clears it even after it has left the viewport.
    LaunchedEffect(listState) {
        snapshotFlow {
            currentChatItems.any { it is ChatItem.NewMessagesDivider } &&
                scrollStateHolder.sawNotBottom &&
                dividerEverVisible
        }.distinctUntilChanged()
            .filter { it }
            .collect { currentOnDividerSeen() }
    }

    // Compensate the LazyColumn's scroll as the IME animates so visible content rides
    // with the input bar. The IME absorbs the navigation bar inset on Android, so the
    // viewport actually shrinks by (ime - navBars), not by ime alone. Skip while any
    // overlay is showing — opening an image viewer or modal closes the IME as a focus
    // side-effect and we don't want the chat to scroll behind the overlay. Negative
    // deltas are also skipped at the end of the list because LazyColumn auto-clamps.
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val navInsets = WindowInsets.navigationBars
    val overlayObscured by rememberUpdatedState(parentHidden || imageViewerUrl.value != null)
    LaunchedEffect(listState, density) {
        var previous: Int? = null
        snapshotFlow {
            maxOf(0, imeInsets.getBottom(density) - navInsets.getBottom(density))
        }
            .distinctUntilChanged()
            .collect { current ->
                val prev = previous
                previous = current
                if (prev == null) return@collect
                if (overlayObscured) return@collect
                val delta = current - prev
                if (delta > 0 || listState.canScrollForward) {
                    listState.scrollBy(delta.toFloat())
                }
            }
    }

    CompositionLocalProvider(
        LocalAnimatedImageHidden provides (parentHidden || imageViewerUrl.value != null || reactingToMessageId != null),
        LocalImageViewerUrl provides imageViewerUrl,
    ) {
        when {
            // The lock placeholder only when there are no messages to show: a PUBLIC group
            // that needs approval still serves its chat, so once messages exist we render
            // them even while the join request is pending (parity with web). Only private /
            // truly-restricted groups stay empty and fall here.
            (isPendingApproval || isGroupRestricted) && chatItems.isEmpty() -> {
                Column(
                    modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = NostrordColors.TextMuted,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        if (isPendingApproval) "Awaiting admin approval" else "Private group",
                        color = NostrordColors.TextSecondary,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (isPendingApproval) {
                            "Messages will appear once an admin approves your request."
                        } else {
                            "You need an invite code or admin approval to see messages."
                        },
                        color = NostrordColors.TextMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            isInitialLoading && chatItems.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = NostrordColors.Primary,
                            strokeWidth = 2.5.dp,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Loading messages…",
                            color = NostrordColors.TextMuted,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
            chatItems.isEmpty() -> {
                Column(
                    modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "No messages yet",
                        color = NostrordColors.TextSecondary,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (isJoined) "Be the first to send a message!" else "Join the group to participate!",
                        color = NostrordColors.TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            else -> {
                MessageSelectionContainer {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Top padding reserves space for the overlay search bar so the newest
                        // messages aren't hidden under it. Stays at Spacing.sm when search is off.
                        val topPadding =
                            with(LocalDensity.current) {
                                Spacing.sm + if (searchActive) searchBarHeightPx.toDp() else 0.dp
                            }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding =
                            PaddingValues(top = topPadding, bottom = Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(0.dp),
                        ) {
                            itemsIndexed(
                                items = chatItems,
                                key = { _, item -> getItemKey(item) },
                                contentType = { _, item ->
                                    when (item) {
                                        is ChatItem.DateSeparator -> "date_separator"
                                        is ChatItem.NewMessagesDivider -> "new_messages_divider"
                                        is ChatItem.SystemEvent -> "system_event"
                                        is ChatItem.Message -> "message"
                                        is ChatItem.ZapEvent -> "zap_event"
                                    }
                                },
                            ) { _, item ->
                                when (item) {
                                    is ChatItem.DateSeparator -> DateSeparator(item.date)
                                    is ChatItem.NewMessagesDivider -> NewMessagesDivider()
                                    is ChatItem.SystemEvent ->
                                        SystemEventItem(
                                            pubkey = item.pubkey,
                                            action = item.action,
                                            createdAt = item.createdAt,
                                            type = item.type,
                                            metadata = userMetadata[item.pubkey],
                                            additionalUsers = item.additionalUsers,
                                            allUserMetadata = userMetadata,
                                            onAvatarClick = currentOnUsernameClick,
                                        )
                                    is ChatItem.Message ->
                                        MessageItem(
                                            message = item.message,
                                            metadata = userMetadata[item.message.pubkey],
                                            resolveReplyMessage = { id -> messages.find { it.id == id } },
                                            resolveMetadata = { pubkey -> userMetadata[pubkey] },
                                            isFirstInGroup = item.isFirstInGroup,
                                            isLastInGroup = item.isLastInGroup,
                                            reactions = reactions[item.message.id] ?: emptyMap(),
                                            pendingReactionEmojis =
                                            pendingReactions
                                                .asSequence()
                                                .filter { it.startsWith("${item.message.id}|") }
                                                .map { it.substringAfter('|') }
                                                .toSet(),
                                            isAuthor = currentUserPubkey != null && item.message.pubkey == currentUserPubkey,
                                            messageStatus = messageStatus[item.message.id],
                                            onRetrySend = { onRetrySend(item.message.id) },
                                            onDismissFailed = { onDismissFailed(item.message.id) },
                                            currentUserPubkey = currentUserPubkey,
                                            currentGroupId = groupId,
                                            currentRelayUrl = currentRelayUrl,
                                            neventRelayHint = neventRelay,
                                            swipeToReplyEnabled = swipeToReplyEnabled,
                                            onUsernameClick = currentOnUsernameClick,
                                            onReplyClick = { currentOnReplyClick(item.message) },
                                            onReactionClick = { reactingToMessageId = item.message.id },
                                            onDeleteMessage = { onDeleteMessage(item.message) },
                                            onReactionBadgeClick = { emoji ->
                                                onReactionBadgeClick(item.message.id, emoji)
                                            },
                                            onScrollToMessage = { id -> internalScrollTarget = id },
                                            onNavigateToGroup = onNavigateToGroup,
                                            isHighlighted = item.message.id == highlightedMessageId,
                                            isSearchHit = item.message.id in searchHitIds,
                                            isCurrentSearchHit = item.message.id == currentSearchHitId,
                                            isContextMenuOpen = openContextMenuId == item.message.id,
                                            onContextMenuOpenChange = { open ->
                                                val id = item.message.id
                                                if (open) {
                                                    val mark = lastClosedMark
                                                    val recentlyClosed =
                                                        id == lastClosedMenuId &&
                                                            mark != null &&
                                                            mark.elapsedNow() < 350.milliseconds
                                                    if (!recentlyClosed) {
                                                        // Drop input focus before the focusable
                                                        // menu Popup opens. Otherwise dismissing
                                                        // it (e.g. Android back) returns focus to
                                                        // the message field and re-shows the
                                                        // keyboard instead of just closing the menu.
                                                        focusManager.clearFocus(force = true)
                                                        openContextMenuId = id
                                                    }
                                                } else if (openContextMenuId == id) {
                                                    closeContextMenu()
                                                }
                                            },
                                            onCopyText = { copyToClipboard(item.message.content) },
                                            onCopyLink = {
                                                val relay = neventRelay ?: return@MessageItem
                                                copyToClipboard(buildShareMessageLink(relay, groupId, item.message.id))
                                            },
                                            onShareLink = {
                                                val relay = neventRelay ?: return@MessageItem
                                                shareText(buildShareMessageLink(relay, groupId, item.message.id))
                                            },
                                            onCopyJson = {
                                                copyToClipboard(item.message.toEventJson())
                                            },
                                        )
                                    is ChatItem.ZapEvent ->
                                        ZapEventItem(
                                            senderPubkey = item.senderPubkey,
                                            recipientPubkey = item.recipientPubkey,
                                            amount = item.amount,
                                            content = item.content,
                                            senderMetadata = userMetadata[item.senderPubkey],
                                            recipientMetadata = userMetadata[item.recipientPubkey],
                                            onSenderClick = currentOnUsernameClick,
                                            onRecipientClick = currentOnUsernameClick,
                                        )
                                }
                            }
                        }

                        // Search bar overlay (web parity: position:absolute over the scroller). It is
                        // drawn OVER the top of the list, not in-flow above it, so showing it never
                        // resizes the LazyColumn viewport. Measuring its height feeds the list's top
                        // contentPadding and the search-scroll offset above. Reset to 0 when search
                        // closes so the padding collapses.
                        if (searchActive) {
                            Box(
                                modifier =
                                Modifier
                                    .align(Alignment.TopCenter)
                                    .fillMaxWidth()
                                    .zIndex(1f)
                                    .onSizeChanged { searchBarHeightPx = it.height },
                            ) {
                                searchBar()
                            }
                        } else if (searchBarHeightPx != 0) {
                            searchBarHeightPx = 0
                        }

                        VerticalScrollbarWrapper(
                            listState = listState,
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        )

                        AnimatedVisibility(
                            visible = isLoadingMore && hasMoreMessages,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            // Sits below the search-bar overlay when search is active so the two
                            // don't stack on top of each other at the top edge.
                            modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .offset { IntOffset(0, if (searchActive) searchBarHeightPx else 0) },
                        ) {
                            Row(
                                modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .background(NostrordColors.Background.copy(alpha = 0.85f))
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    color = NostrordColors.TextMuted,
                                    strokeWidth = 1.5.dp,
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Loading messages…",
                                    color = NostrordColors.TextMuted,
                                    fontSize = 11.sp,
                                )
                            }
                        }

                        // Pagination stalled (repeated unanswered scroll-back REQs): honest
                        // failure row with a manual retry, instead of an endless spinner.
                        AnimatedVisibility(
                            visible = isLoadStalled,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .offset { IntOffset(0, if (searchActive) searchBarHeightPx else 0) },
                        ) {
                            Row(
                                modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .background(NostrordColors.Background.copy(alpha = 0.85f))
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Couldn't load older messages",
                                    color = NostrordColors.TextMuted,
                                    fontSize = 11.sp,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Retry",
                                    color = NostrordColors.TextPrimary,
                                    fontSize = 11.sp,
                                    modifier = Modifier.clickable { onRetryLoadMore() },
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = scrollStateHolder.isScrolledAway,
                            // Pop-in: rise + slight scale anchored at the bottom-right,
                            // matching the web `.chat-jump-bottom` pill animation.
                            enter = fadeIn() + scaleIn(initialScale = 0.96f, transformOrigin = TransformOrigin(1f, 1f)),
                            exit = fadeOut() + scaleOut(targetScale = 0.96f, transformOrigin = TransformOrigin(1f, 1f)),
                            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp),
                        ) {
                            // Jump-to-bottom pill (prototype design): a floating-bg rounded
                            // pill with an optional unread count and a down chevron. Two-stage
                            // only while there are unread messages from others (shared policy):
                            // the first tap focuses the "New messages" divider so they're read
                            // top-down, the next drops to the very latest.
                            val interaction = remember { MutableInteractionSource() }
                            val hovered by interaction.collectIsHoveredAsState()
                            val contentColor = if (hovered) NostrordColors.TextPrimary else NostrordColors.TextSecondary
                            Row(
                                modifier =
                                Modifier
                                    .clip(RoundedCornerShape(percent = 50))
                                    .background(NostrordColors.BackgroundFloating)
                                    .hoverable(interaction)
                                    .clickable {
                                        val dividerIdx = chatItems.indexOfFirst { it is ChatItem.NewMessagesDivider }
                                        val target = ChatScrollPolicy.onJumpPillTap(
                                            hasDivider = dividerIdx >= 0,
                                            dividerSeen = dividerSeen,
                                            unreadFromOthers = unreadFromOthersCount,
                                        )
                                        if (target == JumpPillTarget.Divider) {
                                            dividerSeen = true
                                            coroutineScope.launch { listState.animateScrollToItem(dividerIdx) }
                                        } else {
                                            dividerSeen = false
                                            coroutineScope.launch {
                                                val lastIndex = chatItems.lastIndex
                                                val distance = lastIndex - listState.firstVisibleItemIndex
                                                if (distance <= 30) {
                                                    listState.animateScrollToItem(lastIndex)
                                                } else {
                                                    listState.scrollToItem(lastIndex, Int.MAX_VALUE)
                                                }
                                            }
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                // Badge + count label only while the NEXT tap goes to the
                                // divider; once it's been seen the pill reads as stage 2.
                                if (unreadFromOthersCount > 0 && !dividerSeen) {
                                    UnreadBadge(count = unreadFromOthersCount, size = 18.dp)
                                }
                                Text(
                                    text = when {
                                        dividerSeen || unreadFromOthersCount == 0 -> "Jump to latest"
                                        unreadFromOthersCount > 99 -> "99+ new"
                                        else -> "$unreadFromOthersCount new"
                                    },
                                    color = contentColor,
                                    fontSize = 13.sp,
                                )
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Jump to latest message",
                                    tint = contentColor,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }

                        // Dismiss the open message context menu when tapping anywhere
                        // outside it. Popup.dismissOnClickOutside is unreliable for touch on
                        // web; the menu Popup still renders above this overlay.
                        if (openContextMenuId != null) {
                            Box(
                                modifier =
                                Modifier
                                    .fillMaxSize()
                                    .pointerInput(Unit) {
                                        detectTapGestures { closeContextMenu() }
                                    },
                            )
                        }
                    }
                }
            }
        }
    } // CompositionLocalProvider

    // Image viewer modal — rendered at MessagesList level so ALL animated images are hidden
    imageViewerUrl.value?.let { url ->
        ImageViewerModal(
            imageUrl = url,
            onDismiss = { imageViewerUrl.value = null },
        )
    }

    // Reaction emoji picker popup
    if (reactingToMessageId != null) {
        Popup(
            alignment = Alignment.Center,
            onDismissRequest = { reactingToMessageId = null },
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
                        onClick = { reactingToMessageId = null },
                    ),
            ) {
                EmojiPicker(
                    onEmojiSelect = { emoji ->
                        val msgId = reactingToMessageId
                        if (msgId != null) {
                            onReactionBadgeClick(msgId, emoji)
                        }
                        reactingToMessageId = null
                    },
                    onDismiss = { reactingToMessageId = null },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}
