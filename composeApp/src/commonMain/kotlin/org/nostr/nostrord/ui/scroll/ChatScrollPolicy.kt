package org.nostr.nostrord.ui.scroll

/**
 * Platform-agnostic decision machine for chat-feed scroll behaviour, shared by the
 * native (Compose LazyColumn) and web (React/DOM) chat views.
 *
 * It holds ONLY the latch state and the transition rules; it performs no actual
 * scrolling. Callers translate the returned decisions into platform calls
 * (`LazyListState.scrollToItem` on native, `el.scrollTop` / `scrollIntoView` on
 * web). Keeping the rules here means the "open at divider vs bottom", "pin to
 * bottom", and "round-trip before re-pinning" policy is defined once and unit
 * tested in `commonTest`, instead of being re-derived per platform.
 *
 * State is immutable; every transition returns a fresh [ChatScrollState]. The
 * native holder wraps it in Compose `mutableStateOf` so the FAB recomposes.
 */
data class ChatScrollState(
    /**
     * Single source of truth for "is the viewport pinned to the bottom". A LATCH,
     * not derived continuously from layout: entry / divider alignment set it, and
     * only a real user scroll back to the bottom flips it true again.
     */
    val atBottom: Boolean = true,
    /** Latched once the one-shot entry alignment to the divider has run. */
    val openedAtDivider: Boolean = false,
    /** Items have appeared at least once; entry positioning is resolved. */
    val restored: Boolean = false,
    /**
     * Whether a genuine scroll-away from the bottom has been seen this entry. Gates
     * the promotion back to [atBottom] so a divider entry whose target sits within
     * the bottom tolerance is not spuriously re-pinned (the web's `wasNotAtBottom`).
     */
    val sawNotBottom: Boolean = false,
    /**
     * The entry alignment (divider vs bottom) has been decided once for this entry.
     * Latches even when the group opens at the bottom with NO divider, so a divider
     * that materialises later (an incoming message arriving while the user is reading
     * history) does not re-trigger the one-shot alignment and yank the view down.
     */
    val entryResolved: Boolean = false,
    /**
     * Raw "is the newest message currently on screen" reading from layout. Drives the
     * jump-to-bottom FAB independently of [atBottom]: when the last message is visible
     * there is nothing to jump to, so the FAB hides even while the pin latch is still
     * false (e.g. opened at a divider that sits within the bottom tolerance). Kept
     * separate from [atBottom] so the pin / entry-suppression behaviour stays intact.
     */
    val lastItemVisible: Boolean = true,
)

/** Where the chat should position itself on group entry. */
enum class ScrollEntryTarget { Bottom, Divider }

/** Where a jump-pill tap should land. */
enum class JumpPillTarget { Divider, Bottom }

/** Result of an entry-alignment decision: the next state plus the action to take. */
data class EntryDecision(
    val state: ChatScrollState,
    val target: ScrollEntryTarget?,
)

object ChatScrollPolicy {
    /** Fresh latch state for a group entry. */
    fun onEnterGroup(): ChatScrollState = ChatScrollState()

    /** Items first appeared; open the gate so user-scroll tracking starts applying. */
    fun onItemsReady(state: ChatScrollState): ChatScrollState = state.copy(restored = true)

    /**
     * One-shot entry alignment, latched by [ChatScrollState.entryResolved] so it runs
     * exactly once per entry. With a divider present it returns [ScrollEntryTarget.Divider],
     * latches [ChatScrollState.openedAtDivider], and clears [ChatScrollState.atBottom] so
     * later streaming chunks don't yank the view to the bottom. With NO divider it still
     * latches (target null, `atBottom` stays true), opening the group at the bottom AND
     * preventing a divider that appears later from re-aligning the view. While seeking a
     * deep-link target the seek IS the entry alignment: latch with no target and clear
     * [ChatScrollState.atBottom], since the seek positions the view mid-history and no
     * later chunk or divider may re-align it (a deferred decision here used to resolve
     * AFTER the seek and re-latch `atBottom = true` on a view sitting mid-history).
     */
    fun onItemsChanged(
        state: ChatScrollState,
        hasDivider: Boolean,
        isSeeking: Boolean,
    ): EntryDecision {
        if (state.entryResolved) return EntryDecision(state, null)
        if (isSeeking) return EntryDecision(state.copy(entryResolved = true, atBottom = false), null)
        if (!hasDivider) return EntryDecision(state.copy(entryResolved = true), null)
        return EntryDecision(
            state.copy(entryResolved = true, openedAtDivider = true, atBottom = false),
            ScrollEntryTarget.Divider,
        )
    }

    /**
     * Should the view pin to the newest item right now? True only while the latch
     * says we are at the bottom and we are not seeking a deep-link target.
     */
    fun shouldPinToBottom(
        state: ChatScrollState,
        isSeeking: Boolean,
    ): Boolean = state.atBottom && !isSeeking

    /**
     * Feed a layout-derived "is the last item visible" reading from real user
     * scrolling. Leaving the bottom clears [ChatScrollState.atBottom]; returning
     * re-pins only once a genuine scroll-away has been seen this entry. A no-op until
     * [ChatScrollState.restored], so entry positioning is never clobbered.
     */
    fun onBottomReadingChanged(
        state: ChatScrollState,
        reachedBottom: Boolean,
    ): ChatScrollState {
        if (!state.restored) return state
        // lastItemVisible tracks the raw reading and drives the FAB; atBottom keeps its
        // round-trip-gated latch semantics for the bottom-pin.
        val s = state.copy(lastItemVisible = reachedBottom)
        return when {
            !reachedBottom -> s.copy(sawNotBottom = true, atBottom = false)
            s.sawNotBottom -> s.copy(atBottom = true)
            else -> s
        }
    }

    /**
     * Whether a content change ABOVE the viewport must be compensated so the rows being
     * read stay put. False while following the feed or inside the open settle window: the
     * bottom pin owns positioning there and an anchor hold would fight it. A reader who
     * deliberately scrolled up always holds, even while the latch still reads bottom
     * (content growth can flip that reading a frame late).
     *
     * Native gets the hold for free: LazyListState anchors on the KEY of the first visible
     * item and re-derives its index when older messages land in front. Web applies it as a
     * scrollTop correction on browsers that ship no CSS scroll anchoring.
     */
    fun shouldHoldAnchor(
        atBottom: Boolean,
        settling: Boolean,
        userScrolledUp: Boolean,
    ): Boolean = userScrolledUp || !(atBottom || settling)

    /**
     * Jump-to-bottom affordance (FAB) visibility. Driven by whether the newest message
     * is on screen, NOT by the pin latch: opening at a divider that sits within the
     * bottom tolerance leaves [ChatScrollState.atBottom] false (so streaming chunks
     * don't yank the view) yet the newest message is visible, so there is nothing to
     * jump to and the FAB stays hidden (#129).
     */
    fun isScrolledAway(state: ChatScrollState): Boolean = !state.lastItemVisible

    /**
     * Two-stage jump pill. The first tap lands on the "New messages" divider ONLY
     * while there are unread messages from others to read there; with nothing unread
     * the divider is a stale landmark (after a backfill it can sit mid-history, with
     * newer content below it), so the tap goes straight to the bottom (#168).
     */
    fun onJumpPillTap(
        hasDivider: Boolean,
        dividerSeen: Boolean,
        unreadFromOthers: Int,
    ): JumpPillTarget = if (hasDivider && !dividerSeen && unreadFromOthers > 0) {
        JumpPillTarget.Divider
    } else {
        JumpPillTarget.Bottom
    }
}
