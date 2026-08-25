package org.nostr.nostrord.ui.scroll

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatScrollPolicyTest {
    @Test
    fun entryDefaultsToBottom() {
        val s = ChatScrollPolicy.onEnterGroup()
        assertTrue(s.atBottom)
        assertFalse(s.openedAtDivider)
        assertFalse(s.restored)
        assertFalse(s.sawNotBottom)
        assertTrue(ChatScrollPolicy.shouldPinToBottom(s, isSeeking = false))
    }

    @Test
    fun noDividerKeepsBottomLatch() {
        val s = ChatScrollPolicy.onEnterGroup()
        val d = ChatScrollPolicy.onItemsChanged(s, hasDivider = false, isSeeking = false)
        assertNull(d.target)
        assertTrue(d.state.atBottom)
        assertFalse(d.state.openedAtDivider)
    }

    @Test
    fun dividerAlignsOnceAndClearsBottom() {
        val s = ChatScrollPolicy.onEnterGroup()
        val d = ChatScrollPolicy.onItemsChanged(s, hasDivider = true, isSeeking = false)
        assertEquals(ScrollEntryTarget.Divider, d.target)
        assertFalse(d.state.atBottom)
        assertTrue(d.state.openedAtDivider)
        assertFalse(ChatScrollPolicy.shouldPinToBottom(d.state, isSeeking = false))

        // Subsequent chunks must NOT re-align (latched).
        val again = ChatScrollPolicy.onItemsChanged(d.state, hasDivider = true, isSeeking = false)
        assertNull(again.target)
        assertEquals(d.state, again.state)
    }

    @Test
    fun seekingSuppressesDividerAndPin() {
        val s = ChatScrollPolicy.onEnterGroup()
        val d = ChatScrollPolicy.onItemsChanged(s, hasDivider = true, isSeeking = true)
        assertNull(d.target)
        assertFalse(d.state.openedAtDivider)
        // The seek IS the entry alignment: latched once, positioned mid-history.
        assertTrue(d.state.entryResolved)
        assertFalse(d.state.atBottom)
        assertFalse(ChatScrollPolicy.shouldPinToBottom(d.state, isSeeking = true))
        // After the seek lands (no longer seeking), a divider must not re-align the
        // view, and the latch must not flip back to bottom on its own.
        val later = ChatScrollPolicy.onItemsChanged(d.state, hasDivider = true, isSeeking = false)
        assertNull(later.target)
        assertEquals(d.state, later.state)
    }

    @Test
    fun bottomReadingIgnoredBeforeRestored() {
        val s = ChatScrollPolicy.onEnterGroup()
        // Streaming chunks before restore must not flip the latch.
        val after = ChatScrollPolicy.onBottomReadingChanged(s, reachedBottom = false)
        assertEquals(s, after)
        assertTrue(after.atBottom)
    }

    @Test
    fun scrollAwayThenBackRoundTrip() {
        var s = ChatScrollPolicy.onItemsReady(ChatScrollPolicy.onEnterGroup())
        // User scrolls up.
        s = ChatScrollPolicy.onBottomReadingChanged(s, reachedBottom = false)
        assertFalse(s.atBottom)
        assertTrue(s.sawNotBottom)
        assertTrue(ChatScrollPolicy.isScrolledAway(s))
        // User scrolls back to bottom.
        s = ChatScrollPolicy.onBottomReadingChanged(s, reachedBottom = true)
        assertTrue(s.atBottom)
        assertFalse(ChatScrollPolicy.isScrolledAway(s))
    }

    @Test
    fun scrolledUpAtRestoreBoundaryShowsFab() {
        // User scrolled up during initial load: the pre-restore reading is dropped.
        var s = ChatScrollPolicy.onEnterGroup()
        s = ChatScrollPolicy.onBottomReadingChanged(s, reachedBottom = false) // dropped (not restored)
        assertTrue(s.atBottom)
        // Restoration completes; the boundary re-evaluation feeds the CURRENT
        // (scrolled-up) reading, which must now flip the latch so the FAB appears.
        s = ChatScrollPolicy.onItemsReady(s)
        s = ChatScrollPolicy.onBottomReadingChanged(s, reachedBottom = false)
        assertFalse(s.atBottom)
        assertTrue(ChatScrollPolicy.isScrolledAway(s))
    }

    @Test
    fun dividerEntryWithinBottomToleranceHidesFab() {
        // Enter at divider (atBottom=false), restored, no genuine scroll-away yet.
        var s = ChatScrollPolicy.onItemsChanged(
            ChatScrollPolicy.onEnterGroup(),
            hasDivider = true,
            isSeeking = false,
        ).state
        s = ChatScrollPolicy.onItemsReady(s)
        // The divider sits within the bottom tolerance, so the newest message is on
        // screen. The pin latch stays false (no spurious re-pin without a round-trip),
        // but the FAB hides because there is nothing to jump to (#129).
        s = ChatScrollPolicy.onBottomReadingChanged(s, reachedBottom = true)
        assertFalse(s.atBottom)
        assertFalse(ChatScrollPolicy.isScrolledAway(s))
    }

    @Test
    fun lateDividerDoesNotRealignAfterBottomEntry() {
        // Open the group with everything read: no divider, latched at the bottom.
        var s = ChatScrollPolicy.onItemsChanged(
            ChatScrollPolicy.onEnterGroup(),
            hasDivider = false,
            isSeeking = false,
        ).state
        assertTrue(s.entryResolved)
        assertTrue(s.atBottom)
        // A divider that materialises later (a message arrives while reading history)
        // must NOT re-trigger the entry alignment and yank the view to the divider.
        val later = ChatScrollPolicy.onItemsChanged(s, hasDivider = true, isSeeking = false)
        assertNull(later.target)
        assertEquals(s, later.state)
    }

    @Test
    fun jumpPillFirstTapTargetsDividerOnlyWithUnread() {
        // Divider present + unread from others: two-stage, first tap lands on it.
        assertEquals(
            JumpPillTarget.Divider,
            ChatScrollPolicy.onJumpPillTap(hasDivider = true, dividerSeen = false, unreadFromOthers = 3),
        )
        // Divider present but nothing unread (stale landmark after a backfill):
        // go straight to the bottom (#168).
        assertEquals(
            JumpPillTarget.Bottom,
            ChatScrollPolicy.onJumpPillTap(hasDivider = true, dividerSeen = false, unreadFromOthers = 0),
        )
    }

    @Test
    fun jumpPillSecondTapAndNoDividerGoToBottom() {
        // Divider already seen: second tap drops to the latest.
        assertEquals(
            JumpPillTarget.Bottom,
            ChatScrollPolicy.onJumpPillTap(hasDivider = true, dividerSeen = true, unreadFromOthers = 3),
        )
        // No divider at all: always the bottom, unread or not.
        assertEquals(
            JumpPillTarget.Bottom,
            ChatScrollPolicy.onJumpPillTap(hasDivider = false, dividerSeen = false, unreadFromOthers = 3),
        )
    }

    @Test
    fun anchorHeldOnlyWhileReadingHistory() {
        // Following the feed: the bottom pin owns the position, no anchor hold.
        assertEquals(
            false,
            ChatScrollPolicy.shouldHoldAnchor(atBottom = true, settling = false, userScrolledUp = false),
        )
        // Open settle window: still pinning, even with the latch briefly false mid-reflow.
        assertEquals(
            false,
            ChatScrollPolicy.shouldHoldAnchor(atBottom = false, settling = true, userScrolledUp = false),
        )
        // Reading history: an out-of-order older message must not drag the view.
        assertEquals(
            true,
            ChatScrollPolicy.shouldHoldAnchor(atBottom = false, settling = false, userScrolledUp = false),
        )
        // A deliberate scroll-up wins over a stale bottom reading and over the settle window.
        assertEquals(
            true,
            ChatScrollPolicy.shouldHoldAnchor(atBottom = true, settling = true, userScrolledUp = true),
        )
    }
}
