package org.nostr.nostrord.web.components

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.useEffect
import react.useLayoutEffect
import react.useRef
import web.cssom.ClassName
import web.html.HTMLDivElement

// How long after opening a group the view is kept pinned to the bottom unconditionally and
// auto-pagination is suppressed. Covers the window where late media decode / streaming system
// events grow the content, so the open settles at the true bottom and entering a group never
// triggers a history load on its own.
private const val SETTLE_MS = 1500.0

external interface ChatMessageListProps : Props {
    /** Opaque rows; index 0 = oldest/top, last = newest/bottom. */
    var items: Array<dynamic>

    /** Render one row directly into the list (the row element carries its own key). */
    var renderRow: (ChildrenBuilder, dynamic) -> Unit

    /** Changing this (the group id) re-opens the list at the bottom. */
    var resetKey: String

    var hasMore: Boolean
    var isLoadingMore: Boolean

    /** Fired when the user nears the top — caller loads older history (no guards needed). */
    var onStartReached: () -> Unit

    /** Fired on at-bottom transitions — caller drives the FAB / mark-read. */
    var onAtBottomChange: (Boolean) -> Unit

    /** Fired (debounced) with the index of the bottom-most fully-visible row — mark-as-read. */
    var onRangeChange: (Int) -> Unit

    /** Fired (debounced) when the "New messages" divider row is within the viewport. The caller
     *  gates on a genuine scroll-away before consuming it, so the entry settle never dismisses
     *  the divider unseen (issue #83). Null when there is no divider dismissal wiring. */
    var onDividerVisible: (() -> Unit)?

    /** A DOM id to scroll into view (deep-link / reply); cleared via [onScrolledToKey]. */
    var scrollToKey: String?
    var onScrolledToKey: () -> Unit

    /** scrollIntoView block alignment: "center" for reply/deep-link, "start" for search (lands the
     *  hit just under the floating search overlay via scroll-padding-top, matching Compose). */
    var scrollToKeyBlock: String?

    /** Bump to scroll to the newest row (the FAB). */
    var jumpNonce: Int
}

/**
 * Non-virtualized chat list: renders every row as real DOM (history is bounded).
 * Pins to the bottom while following the feed; while reading history it relies on
 * the browser's overflow-anchor to hold the view across prepends and late media
 * layout, so older pages load without the scroll position jumping.
 */
val ChatMessageList =
    FC<ChatMessageListProps> { props ->
        val el = useRef<HTMLDivElement>(null)
        val innerEl = useRef<HTMLDivElement>(null)
        val items = props.items
        val loadingOlder = useRef(false)
        val firedSize = useRef(0)
        val atBottom = useRef(true)
        val openedFor = useRef<String>(null)
        val wasLoading = useRef(false)
        // Set once the user deliberately scrolls up (a real upward gesture, detected as a
        // scrollTop decrease — NOT a content-growth-induced latch flicker); cleared when they
        // return to the bottom or re-open the group. Gates the ResizeObserver's near-bottom
        // catch-up pin so growth above the fold (history prepend, a live message, late media)
        // can never yank a reader back to the tail. The at-bottom follow and open settle still
        // pin as normal — native gates its single pin purely on the at-bottom latch.
        val userScrolledUp = useRef(false)
        // True while a jump-to-bottom smooth scroll is in flight; cleared on arrival or on a
        // real upward gesture. Tapping the FAB blurs the composer on mobile, so the keyboard
        // closes mid-jump and the visualViewport resize handler would otherwise cancel the
        // smooth scroll with a scrollTop delta that lands the view short of the bottom.
        val jumpingToBottom = useRef(false)
        val lastScrollTop = useRef(0.0)
        val markDebounce = useRef<Int>(null)
        val prevScrollHeight = useRef(0.0)
        val openedAt = useRef(0.0)
        // True for SETTLE_MS after the group opened: keep pinning to the bottom and hold off
        // pagination so the open lands at the true bottom and entry never auto-loads history.
        val settling = { window.performance.now() - (openedAt.current ?: 0.0) < SETTLE_MS }

        // Report the "New messages" divider as seen whenever its row is within the viewport.
        // Called from the scroll handler AND from an entry effect, so the small-unread case
        // (divider already on screen at the bottom on open, no scroll) latches too.
        val reportDividerIfVisible = {
            val node = el.current
            val divider = document.getElementById("new-msg-divider")
            if (node != null && divider != null && props.onDividerVisible != null) {
                val containerRect = node.getBoundingClientRect()
                val containerTop = (containerRect.top as Number).toDouble()
                val containerBottom = (containerRect.bottom as Number).toDouble()
                val dr = divider.getBoundingClientRect()
                val dTop = (dr.top as Number).toDouble()
                val dBottom = (dr.bottom as Number).toDouble()
                if (dBottom > containerTop && dTop < containerBottom) props.onDividerVisible?.invoke()
            }
        }

        // Following the feed: pin to bottom, scroll-anchoring OFF. Reading history:
        // anchoring ON and never touch scrollTop, so the browser holds position across
        // prepends and late image/avatar layout (a manual scrollTop delta could not).
        useLayoutEffect(items.size, props.resetKey) {
            val node = el.current ?: return@useLayoutEffect
            val newHeight = node.scrollHeight.toDouble()

            if (openedFor.current != props.resetKey) {
                // First render of this group: open at the bottom, anchoring off.
                node.asDynamic().style.overflowAnchor = "none"
                node.scrollTop = node.scrollHeight.toDouble()
                openedFor.current = props.resetKey
                openedAt.current = window.performance.now()
                loadingOlder.current = false
                userScrolledUp.current = false
                jumpingToBottom.current = false
                lastScrollTop.current = node.scrollTop.toDouble()
                if (atBottom.current != true) {
                    atBottom.current = true
                    props.onAtBottomChange(true)
                }
                prevScrollHeight.current = node.scrollHeight.toDouble()
                return@useLayoutEffect
            }

            if ((atBottom.current == true || settling()) && userScrolledUp.current != true) {
                // Following (or inside the open settle window) AND the user has not scrolled up:
                // stay pinned to the bottom as content streams in / media decodes. Anchor OFF so it
                // doesn't re-anchor to a row above and drift us up.
                node.asDynamic().style.overflowAnchor = "none"
                node.scrollTop = node.scrollHeight.toDouble()
            } else {
                // Reading history: let the browser anchor the visible content. Do NOT
                // touch scrollTop — overflow-anchor holds the position across the prepend
                // and across late image/avatar layout, so there is no jump.
                node.asDynamic().style.overflowAnchor = "auto"
                // Release the latch the instant the page rendered (items grew past the
                // fire-time count) so continued scrolling loads the next page without
                // waiting out the settle timer. isLoadingMore still guards a double fire.
                if (loadingOlder.current == true && items.size > (firedSize.current ?: 0)) {
                    // overflow-anchor holds the position only when scrollTop > 0. At the very top
                    // (scrollTop ~0) the browser does NOT shift the viewport on a prepend, so the
                    // user stays pinned to the new top and pagination auto-fires page after page.
                    // Nudge scrollTop down by the height that was just prepended so the rows being
                    // read stay put and the user must scroll up again to load the next page.
                    val delta = newHeight - (prevScrollHeight.current ?: newHeight)
                    if (node.scrollTop.toDouble() < 4.0 && delta > 0.0) {
                        node.scrollTop = node.scrollTop + delta
                    }
                    loadingOlder.current = false
                }
            }
            prevScrollHeight.current = node.scrollHeight.toDouble()
        }

        // Latch the divider as seen when it sits within the viewport at the bottom on open
        // (a small unread batch shows it without any scroll). The scroll handler only checks
        // on scroll, so without this the entry-at-bottom case never reports the divider and the
        // line survives the later scroll-away (issue #83).
        useEffect(items.size) {
            reportDividerIfVisible()
        }

        // Fallback latch release: the layout effect frees the latch as soon as the page
        // renders; this only covers a page that returns nothing new (no growth), so the
        // latch can't get stuck. Pagination just trusts hasMore/isLoadingMore (like native).
        useEffect(props.isLoadingMore, items.size) {
            if (props.isLoadingMore) {
                wasLoading.current = true
                return@useEffect
            }
            if (wasLoading.current != true) return@useEffect
            delay(400)
            wasLoading.current = false
            if (loadingOlder.current == true) {
                loadingOlder.current = false
            }
        }

        // Stay glued to the bottom when content grows there WITHOUT an items.size
        // change — an image/video/reply-preview resolving height, a reaction landing
        // on the newest message (issue #74). Those don't trip the layout effect, so a
        // ResizeObserver on the inner content re-pins to the bottom. Guarded to ONLY
        // act while following (at bottom, not paginating); when reading history,
        // overflow-anchor handles above-viewport growth instead.
        useEffect(Unit) {
            val inner = innerEl.current ?: return@useEffect
            val onResize: () -> Unit = {
                // While following the feed, ANY content growth re-pins to the bottom: late
                // media (including imeta-less images that grow from the placeholder floor),
                // reactions, system rows. Not gated by loadingOlder: when at the bottom there
                // is no prepend-restore to protect, so the pin must always win, otherwise a
                // group whose tail has unsized media opens parked just above the true bottom.
                // During the open settle window we re-pin even if the latch briefly read false.
                el.current?.let { node ->
                    // Also re-pin when the viewport is still within ~1.5 screens of the bottom: a
                    // burst of image messages grows scrollHeight as each image decodes, and the
                    // atBottom latch can read false mid-growth, stranding the view above the new tail
                    // (live messages then land below the fold and look "not received"). A genuine
                    // scroll-up past this band clears the intent and stops the pin.
                    val distanceFromBottom = node.scrollHeight - (node.scrollTop + node.clientHeight)
                    val nearBottom = distanceFromBottom < node.clientHeight * 1.5
                    // A deliberate scroll-up disables every auto-pin: growth above the fold (history
                    // prepend, a live message, late media) must not yank the reader back to the tail.
                    // While following / settling / near the bottom and NOT scrolled up, re-pin.
                    if (userScrolledUp.current != true &&
                        (atBottom.current == true || settling() || nearBottom)
                    ) {
                        node.scrollTop = node.scrollHeight.toDouble()
                    }
                }
            }
            val factory =
                js(
                    "(function(node, cb){ var ro = new ResizeObserver(function(){ cb(); }); ro.observe(node); return function(){ ro.disconnect(); }; })",
                )
            val disconnect = factory(inner, onResize)
            try {
                awaitCancellation()
            } finally {
                disconnect()
            }
        }

        // Keep the reading position anchored above the on-screen keyboard (mobile). The page
        // shell is sized to visualViewport.height (index.html --app-height), so opening the
        // keyboard shrinks the message list from the bottom. By default the browser holds the
        // TOP of the view, so the rows being read slide down behind the keyboard. Native
        // (adjustResize) keeps the BOTTOM anchored: the same rows stay just above the keyboard.
        // Mirror that by pushing scrollTop down by the height the viewport lost (and back up by
        // the height it regains on close). When following the feed, pin straight to the bottom.
        val prevViewportHeight = useRef(0.0)
        useEffect(Unit) {
            val vv = window.asDynamic().visualViewport ?: return@useEffect
            prevViewportHeight.current = vv.height.unsafeCast<Double>()
            val onResize: (dynamic) -> Unit = {
                val newHeight = vv.height.unsafeCast<Double>()
                val delta = (prevViewportHeight.current ?: 0.0) - newHeight
                prevViewportHeight.current = newHeight
                if (delta != 0.0) {
                    // Defer to the next frame so the list has reflowed to its new height before
                    // we touch scrollTop (otherwise the shift fights the in-flight relayout).
                    window.requestAnimationFrame {
                        val node = el.current ?: return@requestAnimationFrame
                        if ((atBottom.current == true || jumpingToBottom.current == true) &&
                            loadingOlder.current != true
                        ) {
                            node.scrollTop = node.scrollHeight.toDouble()
                        } else {
                            // delta > 0 when the keyboard opened (viewport shrank): scroll down
                            // so the bottom-most visible row stays put; < 0 on close reverses it.
                            node.scrollTop = node.scrollTop + delta
                        }
                    }
                }
            }
            vv.addEventListener("resize", onResize)
            try {
                awaitCancellation()
            } finally {
                vv.removeEventListener("resize", onResize)
            }
        }

        // Deep-link / reply jump: scroll the row with the given DOM id into view.
        useEffect(props.scrollToKey, items.size) {
            val key = props.scrollToKey ?: return@useEffect
            val target = document.getElementById(key) ?: return@useEffect
            val opts = js("({ behavior: 'auto' })")
            opts.block = props.scrollToKeyBlock ?: "center"
            target.asDynamic().scrollIntoView(opts)
            props.onScrolledToKey()
        }

        // Jump-to-bottom (FAB). Clear the scroll-up intent so the smooth scroll lands at the true
        // bottom even if content grows mid-animation, and following resumes once it arrives.
        useEffect(props.jumpNonce) {
            if ((props.jumpNonce ?: 0) <= 0) return@useEffect
            val node = el.current ?: return@useEffect
            userScrolledUp.current = false
            jumpingToBottom.current = true
            node.asDynamic().scrollTo(js("({ top: node.scrollHeight, behavior: 'smooth' })"))
        }

        div {
            className = ClassName("chat-messages-list")
            ref = el
            // At the very top (scrollTop 0) the browser fires no scroll event for further
            // wheel-up, so onScroll-based pagination would stall there. The wheel event
            // still fires, so it loads the next page; the loadingOlder latch keeps it to
            // one page per load cycle, and stopping the wheel stops loading (no burst).
            onWheel = { ev ->
                if ((ev.deltaY as Double) < 0.0) {
                    // A wheel-up at the very top fires no scroll event (scrollTop is already 0), so
                    // record the scroll-up intent here too — otherwise the near-bottom pin could
                    // re-grab a reader parked at the top when the next page lands.
                    userScrolledUp.current = true
                    val node = ev.currentTarget
                    val sh = node.scrollHeight.toDouble()
                    val st = node.scrollTop.toDouble()
                    val ch = node.clientHeight.toDouble()
                    val mayPaginate = atBottom.current != true || sh <= ch + 4.0
                    if (!settling() &&
                        st < ch * 2.5 &&
                        mayPaginate &&
                        props.hasMore &&
                        !props.isLoadingMore &&
                        loadingOlder.current != true
                    ) {
                        loadingOlder.current = true
                        firedSize.current = items.size
                        props.onStartReached()
                    }
                }
            }
            onScroll = { ev ->
                val node = ev.currentTarget
                val sh = node.scrollHeight.toDouble()
                val st = node.scrollTop.toDouble()
                val ch = node.clientHeight.toDouble()
                // A scrollTop decrease is a real upward gesture (works for wheel AND touch); a pin
                // or an overflow-anchor restore only ever increases it, so this never trips on
                // content growth. Marks the reader as scrolled-up so every auto-pin lets go.
                val scrolledUpNow = st < (lastScrollTop.current ?: 0.0) - 1.0
                if (scrolledUpNow) {
                    userScrolledUp.current = true
                    jumpingToBottom.current = false
                }
                lastScrollTop.current = st
                // 80px threshold matches the prototype: the jump pill appears once the
                // user is more than ~80px up from the bottom.
                val isAtBottom = (sh - st - ch) < 80.0
                // Being at the bottom ends any scroll-up intent, however we got here — including
                // the browser CLAMPING scrollTop when content above shrinks (muting an author),
                // which reads as a scrollTop decrease and set the flag above. There is no at-bottom
                // TRANSITION in that case, so this must run unconditionally: with the flag stuck
                // true, the next growth (unmute re-inserting that author's history) would skip the
                // pin and strand the view mid-feed.
                if (isAtBottom) {
                    userScrolledUp.current = false
                    jumpingToBottom.current = false
                }
                // During the open settle window, ignore a transient "not at bottom" reading caused
                // by content still growing/reflowing — flipping the latch there would disarm the pin
                // and strand the open above the true bottom. But a genuine scroll-up (scrollTop
                // decreased) is NOT reflow: honor it even during settle, otherwise the latch sticks
                // `true` and the next growth snaps the reader back to the bottom.
                if (!(settling() && !isAtBottom && !scrolledUpNow) && atBottom.current != isAtBottom) {
                    atBottom.current = isAtBottom
                    props.onAtBottomChange(isAtBottom)
                    // Anchoring ON while reading history (holds position across prepends and
                    // late image layout), OFF at the bottom where it would fight the pin.
                    node.asDynamic().style.overflowAnchor = if (isAtBottom) "none" else "auto"
                }
                // Load older history as the user nears the top (prefetch ~2.5 viewports
                // ahead so the page lands before they reach the top). Don't paginate while
                // at the bottom, except when the feed doesn't fill the viewport (sh <= ch)
                // and the user can't scroll up to ask for more.
                val mayPaginate = atBottom.current != true || sh <= ch + 4.0
                if (!settling() &&
                    st < ch * 2.5 &&
                    mayPaginate &&
                    props.hasMore &&
                    !props.isLoadingMore &&
                    loadingOlder.current != true
                ) {
                    loadingOlder.current = true
                    firedSize.current = items.size
                    props.onStartReached()
                }
                // Mark-as-read: bottom-most fully-visible row (debounced).
                markDebounce.current?.let { window.clearTimeout(it) }
                markDebounce.current =
                    window.setTimeout(
                        {
                            val containerRect = node.getBoundingClientRect()
                            val containerBottom = (containerRect.bottom as Number).toDouble()
                            val kids = (innerEl.current?.asDynamic()?.children) ?: node.asDynamic().children
                            val n = kids.length as Int
                            var lastVisible = -1
                            for (i in 0 until n) {
                                val kb = (kids[i].getBoundingClientRect().bottom as Number).toDouble()
                                if (kb <= containerBottom + 1.0) lastVisible = i
                            }
                            if (lastVisible >= 0) props.onRangeChange(lastVisible)
                            // Report the "New messages" divider entering the viewport so the screen
                            // can dismiss it once the user has scrolled to look at it (issue #83).
                            reportDividerIfVisible()
                        },
                        400,
                    )
            }
            div {
                ref = innerEl
                className = ClassName("chat-messages-inner")
                items.forEach { item -> props.renderRow(this, item) }
            }
        }
    }
