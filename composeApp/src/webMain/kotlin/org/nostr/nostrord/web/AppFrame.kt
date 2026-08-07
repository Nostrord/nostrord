package org.nostr.nostrord.web

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.awaitCancellation
import org.nostr.nostrord.auth.removeAccountBusyLabel
import org.nostr.nostrord.auth.removeAccountConfirmLabel
import org.nostr.nostrord.auth.removeAccountDialogBody
import org.nostr.nostrord.auth.removeAccountDialogTitle
import org.nostr.nostrord.auth.signerLabel
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.navigation.DmRoute
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.navigation.GroupView
import org.nostr.nostrord.ui.navigation.HomeRoute
import org.nostr.nostrord.ui.navigation.HomeTab
import org.nostr.nostrord.ui.navigation.NotificationsRoute
import org.nostr.nostrord.ui.navigation.RelayRoute
import org.nostr.nostrord.ui.navigation.SettingsRoute
import org.nostr.nostrord.ui.navigation.UserRoute
import org.nostr.nostrord.ui.screens.group.moveChannelBefore
import org.nostr.nostrord.ui.screens.group.rootGroupId
import org.nostr.nostrord.ui.screens.home.HomePageViewModel
import org.nostr.nostrord.ui.screens.home.railKey
import org.nostr.nostrord.ui.screens.notifications.NotificationsViewModel
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.accountDisplayLabel
import org.nostr.nostrord.utils.normalizeRelayUrl
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.BunkerStatusBanner
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.ImageViewerHost
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.ZapModalHost
import org.nostr.nostrord.web.components.confirmDialog
import org.nostr.nostrord.web.components.copyToClipboard
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.memberSkeleton
import org.nostr.nostrord.web.components.searchInput
import org.nostr.nostrord.web.components.tabItem
import org.nostr.nostrord.web.components.useTabBadge
import org.nostr.nostrord.web.modals.AddAccountModal
import org.nostr.nostrord.web.modals.AddGroupModal
import org.nostr.nostrord.web.modals.AvSpaceModalHost
import org.nostr.nostrord.web.modals.CreateGroupModal
import org.nostr.nostrord.web.modals.JoinGroupModal
import org.nostr.nostrord.web.modals.UserProfileModal
import org.nostr.nostrord.web.navigation.consumeInviteInHash
import org.nostr.nostrord.web.navigation.currentHashRoute
import org.nostr.nostrord.web.navigation.pushHashRoute
import org.nostr.nostrord.web.navigation.pushHome
import org.nostr.nostrord.web.navigation.pushRoute
import org.nostr.nostrord.web.navigation.replaceHashRoute
import org.nostr.nostrord.web.screens.ChatScreen
import org.nostr.nostrord.web.screens.DmPage
import org.nostr.nostrord.web.screens.HomePage
import org.nostr.nostrord.web.screens.NotificationsPage
import org.nostr.nostrord.web.screens.NotificationsSidebar
import org.nostr.nostrord.web.screens.ProfilePage
import org.nostr.nostrord.web.screens.RelayScreen
import org.nostr.nostrord.web.screens.SettingsScreen
import org.nostr.nostrord.web.screens.ThreadsScreen
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName

/** Squared pointer travel (px) past which a rail press is a drag, not a tap. */
private const val DRAG_SLOP_SQ = 36.0

/** Hold this long to pick a rail chip up by touch (matches the native rail's long press). */
private const val RAIL_LONG_PRESS_MS = 400

/** Hit-test id of the strip below the last rail chip: drop there to land last. */
private const val RAIL_END_DROP = "__rail_end__"

/** Distance from a rail edge where a drag starts auto-scrolling, and its minimum speed (px/frame). */
private const val RAIL_EDGE_PX = 56.0
private const val RAIL_SCROLL_STEP = 3.0

/**
 * New-design logged-in frame (prototype AppShell): the 72px groups rail (home,
 * joined groups with unread badges, add, DMs and notifications) plus the 240px
 * sidebar (home hub or the open group's sidebar, with the account bar) around the
 * content. Owns the new-design navigation: the #/g/<relay>/<groupId> hash is the
 * source of truth (back/forward work), parsed into a [GroupRoute]. Compact widths
 * hide the nav columns via CSS (the mobile drawer comes later). Mirrors the
 * Compose ui/components/layout/AppFrame.
 */
val AppFrame =
    FC<Props> {
        val repo = AppModule.nostrRepository
        val vm = useViewModel { HomePageViewModel(repo, AppModule.notificationHistoryStore) }
        // Channel model: the rail shows only root groups; a subgroup lives in its root's
        // channel list, with the root chip aggregating the subtree's unread.
        val railRoots = useStateFlow(vm.railRootGroups)
        val railUnread = useStateFlow(vm.railUnreadCounts)
        val groupParents = useStateFlow(vm.groupParents)
        val friends = useStateFlow(vm.friends)
        val friendsLoading = useStateFlow(vm.friendsLoading)
        val notificationUnread = useStateFlow(vm.notificationUnread)
        val dmUnread = useStateFlow(repo.totalDmUnread)
        val dmEnabled = useStateFlow(AppModule.dmSettings.dmEnabled)
        // Browser-tab badge = unread notifications + unread DMs, so the favicon alerts for both,
        // using the notification count (not the old per-group message-unread total, which
        // over-counted vs the Notifications bell). Web only; native uses OS notifications;
        // per-group message unread stays on the rail badges.
        useTabBadge(notificationUnread + dmUnread)
        val accounts = useStateFlow(AppModule.accountStore.accounts)
        val activeId = useStateFlow(AppModule.accountStore.activeId)
        val userMetadata = useStateFlow(AppModule.nostrRepository.userMetadata)
        val groupsByRelay = useStateFlow(repo.groupsByRelay)
        // Groups the relay CLOSED with restricted/auth-required: we are outside a private group and
        // cannot read its kind:39000, so the placeholder below must not claim it is public/open.
        val restrictedGroups = useStateFlow(repo.restrictedGroups)
        val (hub, setHub) = useState { 0 }
        val (friendQuery, setFriendQuery) = useState { "" }
        val (menuOpen, setMenuOpen) = useState { false }
        val (confirmLogout, setConfirmLogout) = useState { false }
        // Account whose npub was just copied from its switcher row (shows the check, resets after 1.2s).
        val (copiedNpub, setCopiedNpub) = useState<String?> { null }
        val (logoutBusy, setLogoutBusy) = useState { false }
        // Which step of the rail "+" add-group flow is open: "chooser" / "create" / "join".
        val (addGroupStep, setAddGroupStep) = useState<String?> { null }
        // Rail drag-reorder, same pointer hit-testing as the channel sidebar: chips are
        // found via elementFromPoint + data-rkey, and the drop inserts the dragged chip
        // before the hovered one. No optimistic override — reorderRail applies the new
        // order to the shared flow before the publish leaves, so the rail re-sorts at once.
        val (dragKey, setDragKey) = useState<String?> { null }
        val (overKey, setOverKey) = useState<String?> { null }

        // Set while a drag actually moved, so the pointerup's click navigates nowhere.
        val railDraggedRef = useRef<Boolean>(null)

        fun startRailDrag(
            key: String,
            e: react.dom.events.PointerEvent<*>,
        ) {
            // No preventDefault: the chip is a real button that must keep taking focus and
            // must let the mobile keyboard close when it navigates away from a chat.
            val startX = e.clientX
            val startY = e.clientY
            // Mouse picks the chip up as soon as it travels past the slop. Touch cannot:
            // the same early movement is how the rail scrolls, so it waits for a long
            // press, like the native rail. Until then the browser owns the gesture.
            val touch = e.pointerType.toString() != "mouse"
            railDraggedRef.current = false
            val currentOrder = railRoots.map { railKey(it.relayUrl, it.meta.id) }
            fun targetAt(
                x: Double,
                y: Double,
            ): String? = document.asDynamic().elementFromPoint(x, y)?.closest("[data-rkey]")?.getAttribute("data-rkey") as String?

            var cleanup: (() -> Unit)? = null
            var pressTimer: Int? = null
            var autoScrollFrame: Int? = null
            var pointerX = startX
            var pointerY = startY

            // Edge auto-scroll: the drop target is whatever sits under the pointer, so a chip
            // at the bottom could never reach the top of a scrolled rail without this. Runs
            // per frame (not per pointermove) so holding still at the edge keeps scrolling.
            fun autoScrollStep() {
                val rail = document.querySelector(".rail-scroll")
                if (rail == null || railDraggedRef.current != true) {
                    autoScrollFrame = null
                    return
                }
                val rect = rail.getBoundingClientRect()
                val speed =
                    when {
                        pointerY < rect.top + RAIL_EDGE_PX ->
                            -RAIL_SCROLL_STEP.coerceAtLeast((rect.top + RAIL_EDGE_PX - pointerY) / 4)
                        pointerY > rect.bottom - RAIL_EDGE_PX ->
                            RAIL_SCROLL_STEP.coerceAtLeast((pointerY - (rect.bottom - RAIL_EDGE_PX)) / 4)
                        else -> 0.0
                    }
                if (speed != 0.0) {
                    rail.asDynamic().scrollTop = rail.asDynamic().scrollTop as Double + speed
                    // Chips moved under a still pointer: re-read the hovered one.
                    setOverKey(targetAt(pointerX, pointerY))
                }
                autoScrollFrame = window.requestAnimationFrame { autoScrollStep() }
            }

            fun engage() {
                pressTimer = null
                railDraggedRef.current = true
                setDragKey(key)
                setOverKey(key)
                if (autoScrollFrame == null) autoScrollFrame = window.requestAnimationFrame { autoScrollStep() }
            }
            // Non-passive: once the chip is picked up, this is the only thing that stops
            // the rail from scrolling under the finger for the rest of the drag.
            val onTouchMove: (dynamic) -> Unit = { ev ->
                if (railDraggedRef.current == true) ev.preventDefault()
            }
            val onMove: (dynamic) -> Unit = { ev ->
                pointerX = ev.clientX as Double
                pointerY = ev.clientY as Double
                val dx = pointerX - startX
                val dy = pointerY - startY
                val movedPastSlop = dx * dx + dy * dy > DRAG_SLOP_SQ
                if (railDraggedRef.current != true && movedPastSlop) {
                    if (touch) {
                        // Moved before the press landed: this is a scroll, so drop the gesture.
                        pressTimer?.let { window.clearTimeout(it) }
                        cleanup?.invoke()
                    } else {
                        engage()
                    }
                }
                if (railDraggedRef.current == true) setOverKey(targetAt(ev.clientX as Double, ev.clientY as Double))
            }
            val onUp: (dynamic) -> Unit = { ev ->
                if (railDraggedRef.current == true) {
                    val target = targetAt(ev.clientX as Double, ev.clientY as Double)
                    if (target != null && target != key) {
                        // The end strip maps to a null target: drop after the last chip.
                        val newOrder = moveChannelBefore(currentOrder, key, target.takeIf { it != RAIL_END_DROP })
                        if (newOrder != currentOrder) vm.reorderRail(newOrder)
                    }
                }
                cleanup?.invoke()
            }
            val onCancel: (dynamic) -> Unit = { _ -> cleanup?.invoke() }

            cleanup = {
                pressTimer?.let { window.clearTimeout(it) }
                pressTimer = null
                autoScrollFrame?.let { window.cancelAnimationFrame(it) }
                autoScrollFrame = null
                setDragKey(null)
                setOverKey(null)
                window.asDynamic().removeEventListener("pointermove", onMove)
                window.asDynamic().removeEventListener("pointerup", onUp)
                window.asDynamic().removeEventListener("pointercancel", onCancel)
                window.asDynamic().removeEventListener("touchmove", onTouchMove)
            }

            window.asDynamic().addEventListener("pointermove", onMove)
            window.asDynamic().addEventListener("pointerup", onUp)
            window.asDynamic().addEventListener("pointercancel", onCancel)
            if (touch) {
                window.asDynamic().addEventListener("touchmove", onTouchMove, js("({ passive: false })"))
                pressTimer = window.setTimeout({ engage() }, RAIL_LONG_PRESS_MS)
            }
        }
        // Friend tapped in the home sidebar: open the quick profile modal first (no
        // chat composer around, so no Mention action), with "View profile" inside it
        // for the full page.
        val (profileUser, setProfileUser) = useState<String?> { null }
        // Add-account modal (login interface inside a modal card).
        val (addAccountOpen, setAddAccountOpen) = useState { false }
        // Notifications page open over the content (filter sidebar + list); one shared VM.
        // Open state is the #/notifications hash route (deep-linkable, survives refresh),
        // derived from [route] below.
        val notifVm = useViewModel { NotificationsViewModel(repo) }
        // Mobile nav drawer: below md the rail + sidebar slide in over the content.
        val (drawerOpen, setDrawerOpen) = useState { false }

        // The hash is the navigation source of truth: state follows it (hashchange
        // covers pushes from this frame, back/forward, and hand-edited URLs).
        val (route, setRoute) = useState { currentHashRoute() }
        useEffect(Unit) {
            val listener: (dynamic) -> Unit = { setRoute(currentHashRoute()) }
            window.asDynamic().addEventListener("hashchange", listener)
            try {
                awaitCancellation()
            } finally {
                window.asDynamic().removeEventListener("hashchange", listener)
            }
        }
        // Close the mobile nav drawer whenever the destination changes (a rail/sidebar tap
        // navigates or opens notifications), so it doesn't stay over the new screen.
        useEffect(route) { setDrawerOpen(false) }
        // DMs disabled while a DM route is showing (toggled off, or a restored/deep-linked #/dm):
        // bounce home so the hidden feature can't stay open.
        useEffect(dmEnabled, route) {
            if (!dmEnabled && route is DmRoute) pushHome()
        }
        // Switching accounts resets navigation to Home and strips the URL, so the previous
        // account's open group doesn't linger or get reopened on refresh. useStateFlow seeds
        // activeId synchronously, so the first run is the current account (no reset); only a
        // genuine switch triggers it.
        val prevActiveId = useRef(activeId)
        useEffect(activeId) {
            if (prevActiveId.current != activeId) {
                prevActiveId.current = activeId
                replaceHashRoute(null)
                setRoute(null)
            }
        }
        val groupRoute = route as? GroupRoute
        val notificationsOpen = route is NotificationsRoute
        // Settings is its own deep-linkable page (#/settings), survives refresh.
        val settingsOpen = route is SettingsRoute
        // Home discovery tab (My groups / From friends / Recommended / People). The
        // non-default tabs are HomeRoutes; Groups is plain Home (null route).
        val homeTab = (route as? HomeRoute)?.tab ?: HomeTab.Groups
        // Switching tabs PUSHES a hash history entry (so back/forward traverses the
        // discovery tabs) and updates the in-memory route. pushState fires no
        // hashchange, so we setRoute ourselves; the back/forward replay later changes
        // the hash and the hashchange listener above re-syncs.
        val selectHomeTab = { tab: HomeTab ->
            val r = if (tab == HomeTab.Groups) null else HomeRoute(tab)
            pushHashRoute(r)
            setRoute(r)
        }
        val isHome = route == null || route is HomeRoute

        // Mirror the legacy AppShell: cross-relay navigation switches the relay first;
        // the open group is tracked (notification suppression + unread clearing); an
        // explicit ?invite= auto-joins once and is stripped from the hash.
        useEffect(groupRoute?.relayUrl, groupRoute?.groupId, groupRoute?.inviteCode) {
            val r = groupRoute
            if (r == null) {
                repo.setActiveGroup(null)
            } else {
                repo.setActiveGroup(r.groupId)
                repo.markGroupAsRead(r.groupId)
                val code = r.inviteCode
                if (code != null) {
                    consumeInviteInHash(r)
                    setRoute(r.copy(inviteCode = null))
                }
                // ONE sequential launch (mirrors the legacy AppShell): the join must
                // run only after the relay switch completes, or it registers the group
                // under the previous relay.
                launchApp {
                    if (r.relayUrl != repo.currentRelayUrl.value) {
                        repo.switchRelay(r.relayUrl)
                    }
                    if (code != null) {
                        // No ViewModel on the deep-link path, so the rejection reason goes to the
                        // system snackbar; swallowing it made a bad invite code look like nothing
                        // happened at all.
                        val join = repo.joinGroup(r.groupId, code)
                        if (join is org.nostr.nostrord.utils.Result.Error) {
                            val reason = (join.error as? AppError.Group.JoinFailed)?.cause?.message
                            AppModule.postSystemMessage(
                                if (reason.isNullOrBlank()) "Could not join the group." else "Could not join: $reason",
                            )
                        }
                    }
                    // A forwarded / deep-linked group we are not a member of isn't in our joined
                    // list, so its kind:39000 never loads and the rail shows the raw id. Fetch the
                    // preview so the name resolves.
                    if (groupsByRelay[r.relayUrl].orEmpty().none { it.id == r.groupId }) {
                        repo.fetchGroupPreview(r.groupId, r.relayUrl)
                    }
                }
            }
        }

        // Render the chat as soon as the target group id is known (placeholder until
        // the kind:39000 arrives), caching the last real metadata to ride out account
        // swaps — same rationale as the legacy AppShell.
        val lastGroupRef = useRef<GroupMetadata>(null)
        if (lastGroupRef.current?.id != groupRoute?.groupId) lastGroupRef.current = null
        val selectedGroup: GroupMetadata? =
            groupRoute?.let { r ->
                val real = groupsByRelay[r.relayUrl].orEmpty().firstOrNull { it.id == r.groupId }
                if (real != null) {
                    lastGroupRef.current = real
                    real
                } else {
                    // No real kind:39000 yet. If the relay restricted this group we are a non-member
                    // of a private group, so default to private + closed (shows "Private"/"Closed"
                    // and the invite-code affordance) instead of the misleading public/open. A plain
                    // unknown group (metadata still loading on a public relay) keeps the permissive
                    // public/open default.
                    val restricted = r.groupId in restrictedGroups
                    lastGroupRef.current ?: GroupMetadata(
                        id = r.groupId,
                        name = null,
                        about = null,
                        picture = null,
                        isPublic = !restricted,
                        isOpen = !restricted,
                    )
                }
            }

        fun performLogout() {
            val id = activeId ?: return
            setLogoutBusy(true)
            launchApp {
                AppModule.accountManager.removeAccount(id)
                setLogoutBusy(false)
                setConfirmLogout(false)
            }
        }

        val active = accounts.firstOrNull { it.id == activeId }
        val meta = active?.pubkey?.let { userMetadata[it] }
        // Fall back to the npub (not the generic "Account N" label) when the active
        // account has no name metadata.
        val activeNpub = active?.pubkey?.let { runCatching { Nip19.encodeNpub(it) }.getOrNull() }
        val accountName =
            meta?.displayName?.takeIf { it.isNotBlank() }
                ?: meta?.name?.takeIf { it.isNotBlank() }
        val displayName = accountName ?: activeNpub ?: active?.label ?: "Account"
        // Name the account in the logout action only when it has a real name; an npub
        // there just wraps and reads as noise (the active account is already marked).
        val logoutLabel = accountName?.let { "Log out of $it" } ?: "Log out"

        div {
            className = ClassName(if (drawerOpen) "app-frame drawer-open" else "app-frame")
            // Floating signer-health warning (position: fixed, top-center): visible on
            // every logged-in page when the bunker is Unreachable/Reconnecting.
            BunkerStatusBanner {}
            // Always mounted so it can fade in/out with the drawer slide (CSS toggles it via
            // .drawer-open); conditionally rendering it would pop instantly and feel abrupt.
            div {
                className = ClassName("app-frame-backdrop")
                onClick = { setDrawerOpen(false) }
            }
            div {
                className = ClassName("app-frame-nav")

                // ── Groups rail ──────────────────────────────────────────────
                div {
                    className = ClassName("rail")
                    button {
                        className = ClassName(if (isHome && !notificationsOpen) "rail-btn active" else "rail-btn")
                        title = "Home"
                        onClick = { pushHome() }
                        icon(Ic.Home)
                    }

                    div {
                        className = ClassName("rail-scroll")
                        // An open channel highlights its root's rail chip (the chip a click
                        // would re-open); badge counts aggregate the root's whole subtree.
                        // Matched by (relay, id): a same-id group on another relay is a
                        // different chip and must not light up too.
                        val activeRootId = groupRoute?.groupId?.let { open -> rootGroupId(open) { groupParents[it] } }
                        val activeRelay = groupRoute?.relayUrl?.normalizeRelayUrl()
                        railRoots.forEach { group ->
                            val name = group.meta.name ?: group.meta.id
                            val rkey = railKey(group.relayUrl, group.meta.id)
                            div {
                                key = "${group.relayUrl}/${group.meta.id}"
                                className =
                                    ClassName(
                                        buildString {
                                            append("rail-group")
                                            if (dragKey == rkey) append(" dragging")
                                            if (overKey == rkey && dragKey != null && dragKey != rkey) append(" drag-over")
                                        },
                                    )
                                asDynamic()["data-rkey"] = rkey
                                // Kill any native HTML drag started inside the chip; it would
                                // take over the pointer stream the reorder needs.
                                onDragStart = { it.preventDefault() }
                                button {
                                    className =
                                        ClassName(
                                            if (activeRootId == group.meta.id &&
                                                activeRelay == group.relayUrl.normalizeRelayUrl() &&
                                                !notificationsOpen
                                            ) {
                                                "rail-group-btn active"
                                            } else {
                                                "rail-group-btn"
                                            },
                                        )
                                    title = name
                                    // Long-press-free: the chip drags from its own pointer down, and
                                    // a plain click still navigates (no move => no reorder).
                                    onPointerDown = { e -> startRailDrag(rkey, e) }
                                    onClick = {
                                        if (railDraggedRef.current != true) pushRoute(GroupRoute(group.relayUrl, group.meta.id))
                                    }
                                    WebAvatar {
                                        url = group.meta.picture
                                        seed = group.meta.id
                                        this.name = name
                                        kind = org.nostr.nostrord.web.components.AvatarKind.GROUP
                                        cls = "group-card-avatar"
                                    }
                                }
                                val unread = railUnread[group.meta.id] ?: 0
                                if (unread > 0) {
                                    span {
                                        className = ClassName("rail-badge")
                                        +(if (unread > 99) "99+" else "$unread")
                                    }
                                }
                            }
                        }
                        // End drop strip, only while dragging: a chip target always inserts
                        // BEFORE it, so this is the only way to land after the last group.
                        // Hidden for the last chip itself, which is already there.
                        val lastRailKey = railRoots.lastOrNull()?.let { railKey(it.relayUrl, it.meta.id) }
                        if (dragKey != null && dragKey != lastRailKey) {
                            div {
                                className = ClassName(if (overKey == RAIL_END_DROP) "rail-drop-end drag-over" else "rail-drop-end")
                                asDynamic()["data-rkey"] = RAIL_END_DROP
                            }
                        }
                        // Add-group is the last scrollable item (after the groups) so the
                        // group list keeps all the rail space and scrolls together with it.
                        button {
                            className = ClassName("rail-btn")
                            title = "Add group"
                            onClick = { setAddGroupStep("chooser") }
                            icon(Ic.Add)
                        }
                    }
                    div { className = ClassName("rail-spacer") }
                    div { className = ClassName("rail-divider") }
                    if (dmEnabled) {
                        div {
                            className = ClassName("rail-group")
                            button {
                                className = ClassName(if (route is DmRoute && !notificationsOpen) "rail-btn active" else "rail-btn")
                                title = "Direct messages"
                                onClick = { pushRoute(DmRoute()) }
                                icon(Ic.Mail)
                            }
                            if (dmUnread > 0) {
                                span {
                                    className = ClassName("rail-badge top")
                                    +(if (dmUnread > 99) "99+" else "$dmUnread")
                                }
                            }
                        }
                    }
                    div {
                        className = ClassName("rail-group")
                        button {
                            className = ClassName(if (notificationsOpen) "rail-btn active" else "rail-btn")
                            title = "Notifications"
                            onClick = { pushRoute(NotificationsRoute) }
                            icon(Ic.Notifications)
                        }
                        if (notificationUnread > 0) {
                            span {
                                className = ClassName("rail-badge top")
                                +(if (notificationUnread > 99) "99+" else "$notificationUnread")
                            }
                        }
                    }
                }

                // ── Sidebar: home hub or the open group's tree ───────────────
                div {
                    className = ClassName("sidebar")
                    val dmRoute = route as? DmRoute
                    if (notificationsOpen) {
                        NotificationsSidebar { this.vm = notifVm }
                    } else if (groupRoute != null) {
                        GroupSidebar {
                            this.route = groupRoute
                            onNavigateGroup = { pushRoute(it) }
                        }
                    } else if (dmRoute != null && dmEnabled) {
                        DmSidebar {
                            activePubkey = dmRoute.pubkey
                            onOpenConversation = { pushRoute(it) }
                        }
                    } else {
                        div {
                            className = ClassName("sidebar-header")
                            +"nostrord"
                        }
                        div {
                            className = ClassName("sidebar-body")
                            div {
                                className = ClassName("tab-strip")
                                tabItem(hub == 0, Ic.People, "Friends") { setHub(0) }
                                tabItem(hub == 1, Ic.Bookmark, "Saved") { setHub(1) }
                            }
                            if (hub == 0 && friends.isNotEmpty()) {
                                div {
                                    className = ClassName("sidebar-friends-search")
                                    searchInput(
                                        placeholder = "Search friends...",
                                        value = friendQuery,
                                        onChange = { setFriendQuery(it) },
                                        compact = true,
                                    )
                                }
                            }
                            when {
                                hub == 1 ->
                                    div {
                                        className = ClassName("sidebar-note")
                                        +"Coming soon"
                                    }
                                friendsLoading ->
                                    div {
                                        className = ClassName("sidebar-friends")
                                        repeat(6) { memberSkeleton() }
                                    }
                                friends.isEmpty() ->
                                    div {
                                        className = ClassName("sidebar-follow-empty")
                                        div {
                                            className = ClassName("sidebar-note")
                                            +"You don't follow anyone yet."
                                        }
                                        button {
                                            className = ClassName("sidebar-follow-cta")
                                            onClick = { AppModule.requestOnboarding() }
                                            icon(Ic.PersonAdd)
                                            +"Follow people"
                                        }
                                    }
                                else ->
                                    div {
                                        className = ClassName("sidebar-friends")
                                        val filtered =
                                            if (friendQuery.isBlank()) {
                                                friends
                                            } else {
                                                friends.filter { f ->
                                                    val n =
                                                        f.metadata?.displayName?.takeIf { it.isNotBlank() }
                                                            ?: f.metadata?.name?.takeIf { it.isNotBlank() }
                                                            ?: Nip19.encodeNpub(f.pubkey)
                                                    n.contains(friendQuery, ignoreCase = true)
                                                }
                                            }
                                        if (filtered.isEmpty()) {
                                            div {
                                                className = ClassName("sidebar-note")
                                                +"No friends match."
                                            }
                                        }
                                        filtered.forEach { friend ->
                                            val friendName =
                                                friend.metadata?.displayName?.takeIf { it.isNotBlank() }
                                                    ?: friend.metadata?.name?.takeIf { it.isNotBlank() }
                                                    ?: (Nip19.encodeNpub(friend.pubkey).take(12) + "…")
                                            button {
                                                key = friend.pubkey
                                                className = ClassName("sidebar-friend")
                                                onClick = {
                                                    setDrawerOpen(false)
                                                    setProfileUser(friend.pubkey)
                                                }
                                                WebAvatar {
                                                    url = friend.metadata?.picture
                                                    seed = friend.pubkey
                                                    name = friendName
                                                    cls = "sidebar-friend-avatar"
                                                }
                                                span {
                                                    className = ClassName("sidebar-friend-name")
                                                    +friendName
                                                }
                                            }
                                        }
                                    }
                            }
                        }
                    }
                    div {
                        className = ClassName("account-menu")
                        if (menuOpen) {
                            div {
                                className = ClassName("account-pop-backdrop")
                                onClick = { setMenuOpen(false) }
                            }
                            div {
                                className = ClassName("account-pop")
                                div {
                                    className = ClassName("account-pop-label")
                                    +"Accounts"
                                }
                                accounts.forEach { account ->
                                    val isActiveAccount = account.id == activeId
                                    val m = userMetadata[account.pubkey]
                                    val npub = runCatching { Nip19.encodeNpub(account.pubkey) }.getOrDefault("")
                                    // Fall back to the npub (not the generic "Account N" label)
                                    // when the account has no name metadata.
                                    val name =
                                        m?.displayName?.takeIf { it.isNotBlank() }
                                            ?: m?.name?.takeIf { it.isNotBlank() }
                                            ?: npub
                                    // Two sibling buttons, not a copy nested inside the switch
                                    // button: a tiny nested target let near-misses fall through
                                    // and change account by accident.
                                    div {
                                        key = account.id
                                        className =
                                            ClassName(
                                                if (isActiveAccount) "account-pop-row active" else "account-pop-row",
                                            )
                                        button {
                                            className = ClassName("account-pop-switch")
                                            onClick = {
                                                setMenuOpen(false)
                                                if (!isActiveAccount) {
                                                    launchApp { AppModule.accountManager.switchAccount(account.id) }
                                                }
                                            }
                                            WebAvatar {
                                                url = m?.picture
                                                seed = account.pubkey
                                                this.name = name
                                                cls = "account-pop-avatar"
                                            }
                                            div {
                                                className = ClassName("account-pop-meta")
                                                div {
                                                    className = ClassName("account-pop-name")
                                                    +name
                                                }
                                                div {
                                                    className = ClassName("account-pop-npub-row")
                                                    span {
                                                        className = ClassName("signer-chip")
                                                        +signerLabel(account)
                                                    }
                                                }
                                            }
                                        }
                                        button {
                                            className =
                                                ClassName(
                                                    if (copiedNpub == account.id) {
                                                        "account-pop-copy copied"
                                                    } else {
                                                        "account-pop-copy"
                                                    },
                                                )
                                            title = "Copy npub"
                                            onClick = {
                                                copyToClipboard(npub)
                                                setCopiedNpub(account.id)
                                                window.setTimeout({ setCopiedNpub(null) }, 1200)
                                            }
                                            icon(if (copiedNpub == account.id) Ic.Check else Ic.ContentCopy)
                                        }
                                    }
                                }
                                div { className = ClassName("account-pop-divider") }
                                active?.let { acct ->
                                    button {
                                        className = ClassName("account-pop-action")
                                        onClick = {
                                            setMenuOpen(false)
                                            pushRoute(UserRoute(acct.pubkey))
                                        }
                                        icon(Ic.Person)
                                        +"View profile"
                                    }
                                }
                                button {
                                    className = ClassName("account-pop-action")
                                    onClick = {
                                        setMenuOpen(false)
                                        setAddAccountOpen(true)
                                    }
                                    icon(Ic.Add)
                                    +"Add account"
                                }
                                button {
                                    className = ClassName("account-pop-action danger")
                                    onClick = {
                                        setMenuOpen(false)
                                        setConfirmLogout(true)
                                    }
                                    icon(Ic.Logout)
                                    +logoutLabel
                                }
                            }
                        }
                        div {
                            className = ClassName("account-bar")
                            button {
                                className = ClassName("account-bar-trigger")
                                title = "Switch account"
                                onClick = { setMenuOpen(!menuOpen) }
                                WebAvatar {
                                    url = meta?.picture
                                    seed = active?.pubkey
                                    this.name = displayName
                                    cls = "account-bar-avatar"
                                }
                                div {
                                    className = ClassName("account-bar-meta")
                                    div {
                                        className = ClassName("account-bar-name")
                                        +displayName
                                    }
                                    div {
                                        className = ClassName("account-bar-status")
                                        +"online"
                                    }
                                }
                                span {
                                    className = ClassName(if (menuOpen) "account-chevron open" else "account-chevron")
                                    icon(Ic.ChevronRight)
                                }
                            }
                            button {
                                className = ClassName("icon-btn")
                                title = "Settings"
                                onClick = { pushRoute(SettingsRoute) }
                                icon(Ic.Settings)
                            }
                        }
                    }
                }
            }

            div {
                className = ClassName("app-frame-main")
                val r = route
                when {
                    notificationsOpen ->
                        NotificationsPage {
                            this.vm = notifVm
                            onOpenDrawer = { setDrawerOpen(true) }
                            onOpenRoute = { pushRoute(it) }
                        }
                    r is UserRoute ->
                        ProfilePage {
                            pubkey = r.pubkey
                            onOpenGroup = { pushRoute(it) }
                            onEditProfile = { pushRoute(SettingsRoute) }
                            onOpenDrawer = { setDrawerOpen(true) }
                        }
                    r is DmRoute && dmEnabled ->
                        DmPage {
                            pubkey = r.pubkey
                            onOpenProfile = { pushRoute(it) }
                            onOpenConversation = { pushRoute(it) }
                            onOpenGroup = { pushRoute(it) }
                            onOpenDrawer = { setDrawerOpen(true) }
                        }
                    r is RelayRoute ->
                        RelayScreen {
                            relayUrl = r.relayUrl
                            onOpenGroup = { g -> pushRoute(GroupRoute(g.relayUrl, g.meta.id)) }
                            onOpenDrawer = { setDrawerOpen(true) }
                        }
                    r is GroupRoute && r.view == GroupView.Threads && selectedGroup != null ->
                        // Forum threads pane (kind:11 + kind:1111). The group rail + sidebar stay
                        // mounted, so only this centre pane swaps when leaving chat.
                        ThreadsScreen {
                            this.route = r
                            this.group = selectedGroup
                            onNavigate = { pushRoute(it) }
                            onOpenDrawer = { setDrawerOpen(true) }
                        }
                    r is GroupRoute && selectedGroup != null ->
                        // The legacy full-featured chat (messages, composer, search, member
                        // sidebar, info/invite modals), keyed so a group switch remounts it.
                        ChatScreen {
                            key = "${r.relayUrl}/${r.groupId}"
                            group = selectedGroup
                            relayUrl = r.relayUrl
                            onLeave = { pushHome() }
                            // Jump to + flash the message a notification (or shared link)
                            // pointed at; clear ?e= afterward so new messages or a back/
                            // forward replay don't re-trigger the highlight.
                            scrollToMessageId = r.messageId
                            onScrolledToMessage = {
                                val cleared = r.copy(messageId = null)
                                replaceHashRoute(cleared)
                                setRoute(cleared)
                            }
                            onNavigateGroup = { gid, relay ->
                                pushRoute(GroupRoute(relay ?: r.relayUrl, gid))
                            }
                            onOpenDrawer = { setDrawerOpen(true) }
                        }
                    else ->
                        HomePage {
                            this.tab = homeTab
                            onSelectTab = selectHomeTab
                            onOpenDrawer = { setDrawerOpen(true) }
                            onOpenGroup = { pushRoute(GroupRoute(it.relayUrl, it.meta.id)) }
                            onCreateGroup = { setAddGroupStep("create") }
                            onJoinGroup = { setAddGroupStep("join") }
                            onOpenDms = { pushRoute(DmRoute()) }
                            onOpenNotifications = { pushRoute(NotificationsRoute) }
                        }
                }
            }

            // Add-group flow (rail "+"): the new-design chooser, then the existing
            // create / join modals. Opening the created/joined group chat is not ported
            // to the new design yet; the group still shows up in the rail via kind:10009.
            when (addGroupStep) {
                "chooser" ->
                    AddGroupModal {
                        onClose = { setAddGroupStep(null) }
                        onJoin = { setAddGroupStep("join") }
                        onCreate = { setAddGroupStep("create") }
                    }
                "create" ->
                    CreateGroupModal {
                        onClose = { setAddGroupStep(null) }
                        // Open the freshly created group; normalized to match the relay
                        // keys used everywhere else (the route effect switches relays).
                        onCreated = { relayUrl, groupId ->
                            pushRoute(GroupRoute(relayUrl.normalizeRelayUrl(), groupId))
                        }
                    }
                "join" ->
                    JoinGroupModal {
                        onClose = { setAddGroupStep(null) }
                        onJoin = { relayUrl, groupId, inviteCode ->
                            // Open the group; the route effect switches relays and
                            // consumes the invite code (auto-join). Normalized so the
                            // route matches the relay keys used everywhere else.
                            pushRoute(GroupRoute(relayUrl.normalizeRelayUrl(), groupId, inviteCode))
                        }
                    }
            }

            // Quick profile modal for a friend tapped in the home sidebar. No groupId
            // and no onMention (we're not in a group chat, so the admin section and the
            // Mention row stay hidden); the modal's own "View profile" routes to the
            // full page.
            profileUser?.let { pubkey ->
                UserProfileModal {
                    this.pubkey = pubkey
                    groupId = null
                    iAmAdmin = false
                    targetIsAdmin = false
                    onClose = { setProfileUser(null) }
                }
            }

            // Add account: the login interface inside a modal. A successful add warm-swaps
            // to the new account and closes; the previous account stays registered.
            if (addAccountOpen) {
                AddAccountModal {
                    onClose = { setAddAccountOpen(false) }
                }
            }

            // Settings page (#/settings): a full-screen route reachable from the account
            // bar's gear. Close returns to the previous page via browser history.
            if (settingsOpen) {
                SettingsScreen {
                    onClose = { window.history.back() }
                    // Settings already confirmed the logout internally; performLogout tears
                    // down the session and the shell falls back to the login page.
                    onLogoutWithChoice = { performLogout() }
                }
            }

            // Sign-out confirmation (prototype AccountMenu -> confirmAction).
            if (confirmLogout && active != null) {
                val fallbackAccount = accounts.filter { it.id != active.id }.maxByOrNull { it.addedAt }
                val fallbackLabel = fallbackAccount?.let { accountDisplayLabel(it.label, it.pubkey) }
                // A bare-npub label is shortened so the title doesn't wrap to several lines.
                val signOutLabel = accountDisplayLabel(displayName, active.pubkey)
                confirmDialog(
                    title = removeAccountDialogTitle(isActive = true, accountLabel = signOutLabel),
                    body =
                    removeAccountDialogBody(
                        isActive = true,
                        accountLabel = signOutLabel,
                        fallbackLabel = fallbackLabel,
                        method = active.authMethod,
                    ),
                    confirmLabel =
                    if (logoutBusy) removeAccountBusyLabel(isActive = true) else removeAccountConfirmLabel(isActive = true),
                    danger = true,
                    confirmDisabled = logoutBusy,
                    cancelDisabled = logoutBusy,
                    onCancel = { setConfirmLogout(false) },
                    onConfirm = { performLogout() },
                )
            }

            // Zap modal host: mounted once so any WebZapController.request(...) from a
            // profile, profile modal or message renders the send-zap modal over the frame.
            ZapModalHost {}

            // Fullscreen image viewer host: mounted once so ImageViewer.show(...) from a
            // chat image opens the lightbox over the frame.
            ImageViewerHost {}

            // AV room host: mounted once so every entry point shows the same room, outside the
            // mobile drawer's transform.
            AvSpaceModalHost {}
        }
    }
