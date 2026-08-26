package org.nostr.nostrord.web.components

import kotlinx.browser.document
import web.html.HTMLElement
import kotlin.math.abs

/**
 * Keeps the row a reader is looking at at the same place across content changes.
 *
 * A chat feed grows in both directions: pagination prepends a page, and the relay sends events
 * out of order, so a message with an older created_at merges into the MIDDLE of the list and
 * shifts everything below it. Either way the rows on screen move and the feed reads as if it
 * scrolled itself.
 *
 * [record] stores which row sits at the top of the viewport and how far below the top it is;
 * [restore] puts that row back at the same distance after the DOM changed. Call restore from a
 * layout effect or a ResizeObserver, before paint, so the shift is never visible.
 *
 * CSS scroll anchoring covers part of this on Chromium and Firefox, but WebKit shipped it only
 * in Safari 27, and no browser anchors at scrollTop 0 (where a prepend leaves the reader pinned
 * to the new top and pagination auto-fires page after page). Where the browser did hold the
 * position, restore resolves to the same scrollTop and is a no-op.
 *
 * [ChatScrollPolicy.shouldHoldAnchor][org.nostr.nostrord.ui.scroll.ChatScrollPolicy.shouldHoldAnchor]
 * decides WHEN to hold; this class is only the mechanism.
 */
class ScrollAnchor {
    private var anchorId: String? = null
    private var anchorDist = 0.0

    /** Drop the anchor: the next content change follows the feed instead of holding. */
    fun clear() {
        anchorId = null
    }

    /**
     * Anchor on the first row intersecting the viewport top. [rowParent] is the element whose
     * children are the rows; rows are identified by their DOM id, so rows without one (date
     * separators, the intro block) are skipped in favour of the next row down.
     */
    fun record(
        container: HTMLElement?,
        rowParent: HTMLElement?,
    ) {
        val node = container ?: return
        val kids = rowParent?.asDynamic()?.children ?: return
        val count = kids.length as Int
        if (count == 0) return
        val top = node.scrollTop.toDouble()
        // Children are siblings in document order, so offsetTop is monotonic: binary search for
        // the first one whose bottom is past the viewport top, O(log n) per scroll event.
        var lo = 0
        var hi = count - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            val child = kids[mid]
            val bottom = (child.offsetTop as Number).toDouble() + (child.offsetHeight as Number).toDouble()
            if (bottom <= top) lo = mid + 1 else hi = mid
        }
        for (i in lo until count) {
            val child = kids[i]
            val id = child.id as? String
            if (!id.isNullOrEmpty()) {
                anchorId = id
                anchorDist = (child.offsetTop as Number).toDouble() - top
                return
            }
        }
    }

    /**
     * Put the anchored row back where it was. offsetTop is read against the row's offsetParent
     * rather than the scroll container, which is fine: the same expression is used to record and
     * to restore, so a constant offset between the two cancels out.
     */
    fun restore(container: HTMLElement?) {
        val node = container ?: return
        val id = anchorId ?: return
        val row = document.getElementById(id) ?: return
        val target = (row.asDynamic().offsetTop as Number).toDouble() - anchorDist
        if (abs(target - node.scrollTop.toDouble()) > 0.5) node.scrollTop = target
    }
}
