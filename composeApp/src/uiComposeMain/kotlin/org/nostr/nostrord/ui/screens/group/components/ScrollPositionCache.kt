package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import org.nostr.nostrord.ui.scroll.ChatScrollPolicy
import org.nostr.nostrord.ui.scroll.ScrollEntryTarget

/**
 * Scroll position data using stable keys instead of indices.
 * Keys survive message list changes (prepending, removing), indices don't.
 */
data class ScrollPosition(
    val anchorKey: String,
    val offset: Int,
)

/**
 * In-memory cache for scroll positions per group. Keyed by [org.nostr.nostrord.utils.groupKey]:
 * the same group id on two relays is two chats with their own reading position.
 * Survives navigation but not process death.
 */
object ScrollPositionCache {
    private val positions = mutableMapOf<String, ScrollPosition>()

    fun save(
        chatKey: String,
        position: ScrollPosition,
    ) {
        positions[chatKey] = position
    }

    fun get(chatKey: String): ScrollPosition? = positions[chatKey]

    fun remove(chatKey: String) {
        positions.remove(chatKey)
    }

    fun clear() {
        positions.clear()
    }
}

/**
 * State holder for scroll position management per group.
 */
@Stable
class ScrollStateHolder(
    /** [org.nostr.nostrord.utils.groupKey] of the chat this holder tracks. */
    val chatKey: String,
    initialPosition: ScrollPosition? = null,
) {
    var savedPosition by mutableStateOf(initialPosition)
        private set

    var isRestored by mutableStateOf(false)
        private set

    var isRestorationPending by mutableStateOf(initialPosition != null)
        private set

    // Latch state for the bottom-pin / divider-entry behaviour. The transition
    // RULES live in commonMain (ChatScrollPolicy) so they are unit-tested and shared
    // with web; this holder only stores the state in Compose-observable form (so the
    // FAB recomposes) and applies the policy's transitions.
    private var scroll by mutableStateOf(ChatScrollPolicy.onEnterGroup())

    /** True while the viewport is pinned to the bottom (single latch authority). */
    val atBottom: Boolean get() = scroll.atBottom

    /** Latched once the one-shot entry alignment has been decided (divider OR bottom). */
    val entryResolved: Boolean get() = scroll.entryResolved

    /** Jump-to-bottom FAB visibility. */
    val isScrolledAway: Boolean get() = ChatScrollPolicy.isScrolledAway(scroll)

    /**
     * True once a genuine scroll-away from the bottom has happened this entry. Gates
     * the "New messages" divider dismissal so the divider is consumed only after the
     * user actually scrolled to look at it, never on the entry settle at the bottom.
     */
    val sawNotBottom: Boolean get() = scroll.sawNotBottom

    /** Apply the one-shot entry alignment decision; returns the target to scroll to. */
    fun applyEntryChange(
        hasDivider: Boolean,
        isSeeking: Boolean,
    ): ScrollEntryTarget? {
        val decision = ChatScrollPolicy.onItemsChanged(scroll, hasDivider, isSeeking)
        scroll = decision.state
        return decision.target
    }

    /** Feed a user-scroll "is the last item visible" reading into the latch. */
    fun applyBottomReading(reachedBottom: Boolean) {
        scroll = ChatScrollPolicy.onBottomReadingChanged(scroll, reachedBottom)
    }

    fun savePosition(
        anchorKey: String,
        offset: Int,
    ) {
        val position = ScrollPosition(anchorKey, offset)
        savedPosition = position
        ScrollPositionCache.save(chatKey, position)
    }

    fun markRestored() {
        isRestored = true
        isRestorationPending = false
        scroll = ChatScrollPolicy.onItemsReady(scroll)
    }

    companion object {
        fun saver(): Saver<ScrollStateHolder, List<Any?>> = Saver(
            save = { holder ->
                listOf(
                    holder.chatKey,
                    holder.savedPosition?.anchorKey,
                    holder.savedPosition?.offset,
                )
            },
            restore = { saved ->
                val chatKey = saved[0] as String
                val anchorKey = saved[1] as? String
                val offset = saved[2] as? Int
                val position =
                    if (anchorKey != null && offset != null) {
                        ScrollPosition(anchorKey, offset)
                    } else {
                        ScrollPositionCache.get(chatKey)
                    }
                ScrollStateHolder(chatKey, position)
            },
        )
    }
}

@Composable
fun rememberScrollStateHolder(chatKey: String): ScrollStateHolder = rememberSaveable(
    inputs = arrayOf(chatKey),
    saver = ScrollStateHolder.saver(),
) {
    val cached = ScrollPositionCache.get(chatKey)
    ScrollStateHolder(chatKey, cached)
}

/**
 * Scroll-to-bottom on load, stick-to-bottom during initial delivery,
 * and continuous position save for group-switching restore.
 */
@Composable
fun <T> ScrollPositionEffect(
    chatKey: String,
    listState: LazyListState,
    items: List<T>,
    stateHolder: ScrollStateHolder,
    getItemKey: (T) -> String,
    initialScrollToEnd: Boolean = true,
) {
    val currentItems by rememberUpdatedState(items)

    // initialScrollToEnd is false while a deep-link seek owns positioning, then flips true
    // once the target is consumed. The effects below are keyed on (chatKey, listState), so
    // they read this LIVE value instead of the launch-time capture: an early `return` on the
    // captured value would disarm bottom-pinning, the restore gate, and the latch tracker for
    // the whole visit (a notification-link entry then never shows the jump pill, never
    // demotes atBottom, and never paginates on scroll-up).
    val scrollToEnd by rememberUpdatedState(initialScrollToEnd)

    // Open settle window: for a short time after entering the group, keep pinning to the bottom
    // regardless of the atBottom latch, so late-decoding media or streaming system events can't
    // strand the open above the true bottom (the latch can briefly read "not at bottom" mid-reflow).
    // A seek entry gets NO settle window: the seek scrolls mid-history and the window's
    // latch-bypass would fight it toward the bottom.
    var settling by remember(chatKey) { mutableStateOf(initialScrollToEnd) }
    LaunchedEffect(chatKey) {
        if (!settling) return@LaunchedEffect
        kotlinx.coroutines.delay(1500)
        settling = false
    }

    // A real user gesture ends the settle window immediately. While it is open the
    // pin ignores the atBottom latch, so a scroll-up right after opening would fight
    // the pin frame by frame (web parity: 712fe5b3).
    LaunchedEffect(chatKey, listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) settling = false
        }
    }

    // Resolve the restoration gate once items first appear. The authoritative entry
    // positioning (divider vs bottom) is decided by the entry effect in
    // MessagesList; here we only flip isRestored so the user-scroll tracker and the
    // auto-pin start applying. Default latch (atBottom = true) means a group with no
    // unread divider simply opens at the bottom via the pin effect below.
    //
    // Keyed on listState (not just chatKey): on a cold load the list starts empty so
    // MessagesList builds a throwaway LazyListState, then swaps in a new one anchored
    // at the last item once messages arrive. If this effect stayed keyed on chatKey
    // it would keep awaiting totalItemsCount > 0 on the discarded (detached) state
    // forever, so markRestored never fires, the tracker's readings are dropped, and
    // the jump-to-bottom FAB never appears until the group is re-entered.
    // Runs on seek entries too: restoration only opens the user-scroll tracker gate, and a
    // deep-link visit needs the latch/FAB tracking just as much (the seek lands mid-history,
    // which is exactly when the jump pill must be able to appear).
    LaunchedEffect(chatKey, listState) {
        snapshotFlow { listState.layoutInfo.totalItemsCount }
            .first { it > 0 }
        stateHolder.markRestored()
    }

    // SINGLE bottom authority. Pins to the newest item whenever the latch says we
    // are at the bottom. Re-runs on list growth AND height growth (canScrollForward
    // flips as media resolves), so it subsumes the three former pinners. Gated
    // purely by stateHolder.atBottom — never derived from layout here — so the entry
    // divider alignment (which sets atBottom = false) is never overridden by a
    // streaming chunk. This is the fix for the "jumps to bottom / have to re-enter"
    // jank: there is now exactly one thing that scrolls to the bottom.
    //
    // Keyed on listState too: it must rebind to the live LazyListState after the
    // cold-load swap described above, otherwise stick-to-bottom would drive the
    // discarded state and never move the visible list.
    LaunchedEffect(chatKey, listState) {
        snapshotFlow {
            // Height-aware key. Re-pin on a new tail item (size) AND on tail height growth
            // (the last visible item's index and measured size), so a late-decoding image,
            // including imeta-less media that grows from text height, still drops the newest
            // content flush to the bottom. The former `canScrollForward` key missed this:
            // once it was already `true`, distinctUntilChanged dropped the unchanged value
            // and the view stayed parked above the true bottom while the image expanded.
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            val total = info.totalItemsCount
            // Same-snapshot bottom reading, required together with the latch. The latch
            // alone lags: the user's own scroll-up emits here (the last item leaving the
            // viewport changes the key) BEFORE the tracker below demotes it, and the N-2
            // demotion tolerance still reads "bottom" with the last item just out of view,
            // so the latch by itself would let this pin yank the user's gesture back down.
            val reachedBottom =
                (last != null && total > 0 && last.index >= total - 2) ||
                    (total > 0 && !listState.canScrollForward)
            PinReading(currentItems.size, last?.index ?: -1, last?.size ?: 0, reachedBottom)
        }
            .distinctUntilChanged()
            .collect { reading ->
                // Suspended while a deep-link seek owns positioning; re-arms live once
                // the target is consumed (scrollToEnd flips true, no relaunch needed).
                if (!scrollToEnd) return@collect
                // Never pin against an active gesture or fling; the next tail change
                // re-evaluates with the latch, which the gesture will have demoted.
                if (listState.isScrollInProgress) return@collect
                if ((stateHolder.atBottom && reading.reachedBottom) || settling) {
                    val idx = currentItems.lastIndex
                    if (idx >= 0) {
                        try {
                            listState.scrollToItem(idx, Int.MAX_VALUE)
                        } catch (e: CancellationException) {
                            // A gesture starting mid-pin preempts the scroll mutex
                            // (UserInput > Default) and cancels scrollToItem, NOT this
                            // effect. Rethrow only real cancellation; otherwise skip the
                            // pin and keep the effect alive for the rest of the visit.
                            if (!currentCoroutineContext().isActive) throw e
                        }
                    }
                }
            }
    }

    // Promote / demote the atBottom latch from REAL user scrolling, and only after
    // entry positioning has resolved (isRestored). Leaving the bottom sets the latch
    // false; returning to the bottom sets it true again — but only once we have
    // actually seen a scroll-away this session (sawNotBottom), so a divider entry
    // whose target happens to sit within the bottom tolerance is not spuriously
    // promoted back to bottom (web's `wasNotAtBottom` round-trip gate). The pin
    // effect reads the latch, not the layout, so this can never race it.
    LaunchedEffect(chatKey, listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = layoutInfo.totalItemsCount
            // At the bottom when the last item is in view OR the list simply cannot scroll down
            // any further. The index heuristic alone missed the case where the final message is
            // tall and only partly visible (its index is the last, but a strict reading failed),
            // leaving the jump-to-bottom FAB showing while the user is already at the last
            // message (#129). Only ever makes "reached bottom" more true, so a freshly-arrived
            // message at the end never spuriously demotes the latch.
            val reachedBottom =
                (lastVisible >= 0 && total > 0 && lastVisible >= total - 2) ||
                    (total > 0 && !listState.canScrollForward)
            // Pair with isRestored so the latch re-evaluates the CURRENT position the
            // moment restoration completes. Without it, a user who scrolls up during
            // the initial load (before isRestored) has that reading dropped by the
            // policy's restored-gate, and distinctUntilChanged then never re-delivers
            // the unchanged "not at bottom" value — leaving atBottom stuck true and
            // the jump-to-bottom FAB hidden until the group is re-entered.
            stateHolder.isRestored to reachedBottom
        }.distinctUntilChanged()
            .collect { (_, reachedBottom) ->
                stateHolder.applyBottomReading(reachedBottom)
            }
    }

    // Continuously save position for group-switch restore.
    LaunchedEffect(chatKey, listState) {
        snapshotFlow {
            Pair(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }.distinctUntilChanged()
            .collect { (index, offset) ->
                val curItems = currentItems
                if (index < curItems.size && curItems.isNotEmpty()) {
                    val key = getItemKey(curItems[index])
                    stateHolder.savePosition(key, offset)
                }
            }
    }

    // Save position when leaving group.
    DisposableEffect(chatKey) {
        onDispose {
            val index = listState.firstVisibleItemIndex
            if (index < items.size && items.isNotEmpty()) {
                val key = getItemKey(items[index])
                stateHolder.savePosition(key, listState.firstVisibleItemScrollOffset)
            }
        }
    }
}

/** Snapshot key for the bottom pin: tail identity/height plus a same-frame bottom reading. */
private data class PinReading(
    val itemCount: Int,
    val lastVisibleIndex: Int,
    val lastVisibleSize: Int,
    val reachedBottom: Boolean,
)

/**
 * Auto-scroll to new messages when user is near the bottom.
 * Always scrolls when the appended item is from the current user (their own send).
 */
@Composable
fun <T> AutoScrollEffect(
    listState: LazyListState,
    items: List<T>,
    getItemKey: ((T) -> String)? = null,
    enabled: Boolean = true,
    nearBottomThreshold: Int = 3,
    isFromCurrentUser: ((T) -> Boolean)? = null,
    isPinnedToBottom: () -> Boolean = { true },
) {
    var previousLastKey by remember { mutableStateOf<String?>(null) }
    var previousSize by remember { mutableStateOf(items.size) }

    LaunchedEffect(items.size) {
        val sizeBefore = previousSize
        val lastKeyBefore = previousLastKey
        // Trackers update BEFORE any scroll: a concurrent user gesture preempts the
        // scroll mutex and cancels scrollToItem below, and updating afterwards left
        // them stale, so the next pagination prepend read as a fresh append and
        // snapped the reader to the bottom from screens away.
        previousSize = items.size
        previousLastKey = items.lastOrNull()?.let { getItemKey?.invoke(it) }

        if (!enabled || items.isEmpty()) return@LaunchedEffect

        val lastItem = items.last()
        val currentLastKey = getItemKey?.invoke(lastItem)

        // Only for appended messages, not pagination prepends.
        val newMessageAppended =
            getItemKey != null &&
                currentLastKey != null &&
                currentLastKey != lastKeyBefore &&
                sizeBefore > 0

        if (!newMessageAppended) return@LaunchedEffect

        val ownAppend = isFromCurrentUser?.invoke(lastItem) == true
        val lastVisibleIndex =
            listState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index ?: 0
        // Nearness measured against the CURRENT size: a prepend shifts indices up, so
        // comparing against the pre-change size read "near bottom" screens away.
        val wasNearBottom = lastVisibleIndex >= items.size - 1 - nearBottomThreshold
        // Others' appends only follow while the bottom latch holds; own sends always
        // drop to the bottom.
        if (ownAppend || (isPinnedToBottom() && wasNearBottom)) {
            listState.scrollToItem(items.lastIndex)
        }
    }
}
