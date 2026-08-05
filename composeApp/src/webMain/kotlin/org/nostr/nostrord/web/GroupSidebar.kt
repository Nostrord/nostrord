package org.nostr.nostrord.web

import js.objects.unsafeJso
import kotlinx.browser.document
import kotlinx.browser.window
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.navigation.GroupView
import org.nostr.nostrord.ui.navigation.RelayRoute
import org.nostr.nostrord.ui.screens.avspace.LiveSpaceBarViewModel
import org.nostr.nostrord.ui.screens.group.GroupViewModel
import org.nostr.nostrord.ui.screens.group.channelTree
import org.nostr.nostrord.ui.screens.group.isLockedChannel
import org.nostr.nostrord.ui.screens.group.moveChannelBefore
import org.nostr.nostrord.ui.screens.group.rootGroupId
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.normalizeRelayUrl
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.Portal
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.bannerGradientCss
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.modals.CreateGroupModal
import org.nostr.nostrord.web.modals.ManageGroupModal
import org.nostr.nostrord.web.modals.MembersModal
import org.nostr.nostrord.web.navigation.pushRoute
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useState
import web.cssom.Background
import web.cssom.ClassName

external interface GroupSidebarProps : Props {
    var route: GroupRoute
    var onNavigateGroup: (GroupRoute) -> Unit
}

/**
 * Second column when a group is open (prototype ChannelsSidebar group mode): the
 * gradient banner (ROOT group identity, Discord's "server") and below it the
 * root rows (General/Threads/Members/Manage all act on the ROOT group) plus the
 * channel list (the root's subgroup subtree). Opening a channel keeps this sidebar
 * anchored on the root; the channel itself is managed only via the header cog.
 * Mirrors the Compose ui/components/layout/GroupSidebar.
 */
val GroupSidebar =
    FC<GroupSidebarProps> { props ->
        val route = props.route
        val vm = useViewModel("${route.relayUrl}|${route.groupId}") {
            GroupViewModel(AppModule.nostrRepository, route.groupId, route.relayUrl)
        }
        val groupsByRelay = useStateFlow(vm.groupsByRelay)
        val childrenByParent = useStateFlow(vm.childrenByParent)
        val groupMembers = useStateFlow(vm.groupMembers)
        val groupAdmins = useStateFlow(vm.groupAdmins)
        val relayMetadata = useStateFlow(vm.relayMetadata)
        val joinedGroupsByRelay = useStateFlow(vm.joinedGroupsByRelay)
        val unreadCounts = useStateFlow(AppModule.nostrRepository.unreadCounts)
        val (showMembers, setShowMembers) = useState { false }
        val (showCreateSubgroup, setShowCreateSubgroup) = useState { false }
        val (showManage, setShowManage) = useState { false }
        // Tab the Manage modal opens on: the Members row jumps admins straight to "Members".
        val (manageTab, setManageTab) = useState<String?> { null }

        val relayGroups = groupsByRelay[route.relayUrl].orEmpty()
        val metaById = relayGroups.associateBy { it.id }
        val currentUserPubkey = vm.getPublicKey()
        // Discord-style channel model: the sidebar anchors on the ROOT of the open channel's
        // subgroup tree (the "server"); every row (General/Threads/Members/Manage) acts on the
        // root. The open channel only drives the chat pane; its members/settings are reachable
        // solely through the header cog.
        val rootId = rootGroupId(route.groupId) { metaById[it]?.parent }
        val rootMeta = metaById[rootId]
        val rootName = rootMeta?.name ?: rootId
        val isRootAdmin = currentUserPubkey != null && currentUserPubkey in groupAdmins[rootId].orEmpty()
        val memberCount = groupMembers[rootId].orEmpty().size
        // Same ViewModel the in-chat banner uses, so both surfaces agree on who is in the room:
        // the relay's kind 39004 lags a join by a webhook round-trip, and only the live engine
        // knows this client is already inside.
        val spaceVm = useViewModel(rootId) {
            LiveSpaceBarViewModel(AppModule.nostrRepository, rootId, currentUserPubkey, AppModule.avSpaceHost)
        }
        val liveCount = useStateFlow(spaceVm.participants).size
        // Optimistic channel order while a drag-reorder kind:9002 round-trips; cleared once
        // the relay's kind:39000 echoes it (or on publish failure).
        val (orderOverride, setOrderOverride) = useState<List<String>?> { null }
        useEffect(rootMeta?.children, orderOverride) {
            if (orderOverride != null && rootMeta?.children == orderOverride) setOrderOverride(null)
        }
        val effectiveMetaById =
            if (orderOverride != null && rootMeta != null) {
                metaById + (rootId to rootMeta.copy(children = orderOverride))
            } else {
                metaById
            }
        // Subgroup channels only: the root's own chat is the "General" row above the list,
        // so listing the root again would duplicate the banner identity.
        val channels = channelTree(rootId, childrenByParent, effectiveMetaById).drop(1)

        // Pointer-based drag reorder (mouse AND touch), ported from the prototype
        // ChannelsSidebar: rows are hit-tested via elementFromPoint + data-sgid, and the
        // drop inserts the dragged channel before the hovered one.
        val (dragId, setDragId) = useState<String?> { null }
        val (overId, setOverId) = useState<String?> { null }

        fun startDrag(id: String, e: react.dom.events.PointerEvent<*>) {
            e.preventDefault()
            e.stopPropagation()
            setDragId(id)
            val currentOrder = channels.filter { it.depth == 1 }.map { it.id }
            fun targetAt(x: Double, y: Double): String? = document.asDynamic().elementFromPoint(x, y)?.closest("[data-sgid]")?.getAttribute("data-sgid") as String?
            var onUpRef: ((dynamic) -> Unit)? = null
            val onMove: (dynamic) -> Unit = { ev -> setOverId(targetAt(ev.clientX as Double, ev.clientY as Double)) }
            val onUp: (dynamic) -> Unit = { ev ->
                val target = targetAt(ev.clientX as Double, ev.clientY as Double)
                if (target != null && target != id) {
                    // The end strip maps to the null target: drop after the last channel.
                    val newOrder = moveChannelBefore(currentOrder, id, target.takeIf { it != END_DROP })
                    if (newOrder != currentOrder) {
                        setOrderOverride(newOrder)
                        launchApp {
                            val r = AppModule.nostrRepository.reorderChildren(rootId, newOrder)
                            if (r is Result.Error) setOrderOverride(null)
                        }
                    }
                }
                setDragId(null)
                setOverId(null)
                window.asDynamic().removeEventListener("pointermove", onMove)
                window.asDynamic().removeEventListener("pointerup", onUpRef)
            }
            onUpRef = onUp
            window.asDynamic().addEventListener("pointermove", onMove)
            window.asDynamic().addEventListener("pointerup", onUp)
        }
        val joinedHere = joinedGroupsByRelay[route.relayUrl.normalizeRelayUrl()].orEmpty()
        // Only relays that advertise nip29:{subgroups:true} in their NIP-11 host subgroups.
        val supportsSubgroups =
            (relayMetadata[route.relayUrl] ?: relayMetadata[route.relayUrl.normalizeRelayUrl()])
                ?.supportsSubgroups == true
        // Host relay shown under the group name (same reference as the discovery .group-card),
        // so the same group on two relays is told apart.
        val relayHost = route.relayUrl.removePrefix("wss://").removePrefix("ws://").trimEnd('/')
        val relayIconUrl = (relayMetadata[route.relayUrl] ?: relayMetadata[route.relayUrl.normalizeRelayUrl()])?.icon

        div {
            className = ClassName("group-side")
            // Gradient identity banner (prototype GroupBanner): same hue pair as the
            // group's conic avatar, darkened for legible white text. Only the relay line is
            // a link (to the relay page); the banner itself is not clickable.
            div {
                className = ClassName("group-side-banner")
                style = unsafeJso { background = bannerGradientCss(rootId).unsafeCast<Background>() }
                div { className = ClassName("group-side-banner-scrim") }
                div {
                    className = ClassName("group-side-banner-row")
                    WebAvatar {
                        url = rootMeta?.picture
                        seed = rootId
                        this.name = rootName
                        kind = org.nostr.nostrord.web.components.AvatarKind.GROUP
                        cls = "group-side-banner-avatar"
                    }
                    div {
                        className = ClassName("group-side-banner-meta")
                        span {
                            className = ClassName("group-side-banner-name")
                            +rootName
                        }
                        if (relayHost.isNotBlank()) {
                            div {
                                className = ClassName("group-side-banner-relay")
                                WebAvatar {
                                    url = relayIconUrl
                                    seed = route.relayUrl
                                    this.name = relayHost
                                    kind = org.nostr.nostrord.web.components.AvatarKind.RELAY
                                    cls = "group-side-banner-relay-icon"
                                }
                                // Only the hostname opens the relay, so the row isn't a block link.
                                span {
                                    className = ClassName("group-side-banner-relay-host link")
                                    title = "Open relay"
                                    onClick = {
                                        it.stopPropagation()
                                        pushRoute(RelayRoute(route.relayUrl))
                                    }
                                    +relayHost
                                }
                            }
                        }
                    }
                }
            }

            div {
                className = ClassName("group-side-body")
                // The root group's own chat (Discord's #general); subgroup channels are listed below.
                // "General" only makes sense next to a channel list; without subgroup support it's "Chat".
                button {
                    className =
                        ClassName(
                            if (route.view == GroupView.Chat && route.groupId == rootId) "group-side-row active" else "group-side-row",
                        )
                    onClick = { props.onNavigateGroup(GroupRoute(route.relayUrl, rootId)) }
                    icon(Ic.Chat)
                    span {
                        className = ClassName("group-side-row-label")
                        +(if (supportsSubgroups) "General" else "Chat")
                    }
                }
                button {
                    className =
                        ClassName(
                            if (route.view == GroupView.Threads && route.groupId == rootId) "group-side-row active" else "group-side-row",
                        )
                    // Forum-style threads (kind:11 + kind:1111), shown in the centre pane.
                    onClick = { props.onNavigateGroup(GroupRoute(route.relayUrl, rootId, view = GroupView.Threads)) }
                    icon(Ic.Forum)
                    span {
                        className = ClassName("group-side-row-label")
                        +"Threads"
                    }
                }
                // Voice room row (prototype ChannelsSidebar): shown when the group has a
                // LiveKit space, with the live participant count. The room is mounted once
                // over the frame (AvSpaceModalHost), so this only asks the host to show it.
                if (rootMeta?.hasLiveKit == true) {
                    button {
                        className = ClassName("group-side-row")
                        onClick = { AppModule.avSpaceHost.show(rootId, AppModule.nostrRepository.activePubkey.value) }
                        icon(Ic.Mic)
                        span {
                            className = ClassName("group-side-row-label")
                            +"Voice room"
                        }
                        if (liveCount > 0) {
                            span {
                                className = ClassName("group-side-live-count")
                                span { className = ClassName("live-dot") }
                                +"$liveCount"
                            }
                        }
                    }
                }
                button {
                    className = ClassName("group-side-row")
                    // Admins manage members in the Manage modal; everyone else sees the roster.
                    onClick = {
                        if (isRootAdmin) {
                            setManageTab("members")
                            setShowManage(true)
                        } else {
                            setShowMembers(true)
                        }
                    }
                    icon(Ic.People)
                    span {
                        className = ClassName("group-side-row-label")
                        +(if (memberCount > 0) "Members · $memberCount" else "Members")
                    }
                }
                if (isRootAdmin) {
                    button {
                        className = ClassName("group-side-row")
                        onClick = {
                            setManageTab(null)
                            setShowManage(true)
                        }
                        icon(Ic.Settings)
                        span {
                            className = ClassName("group-side-row-label")
                            +"Manage group"
                        }
                    }
                }

                // Channels (Discord model): the root's subgroup subtree, depth-first with
                // indentation. Hidden on relays that can't host subgroups, and for non-admins
                // when there are no channels yet (admins keep the header for its add button).
                if (supportsSubgroups && (channels.isNotEmpty() || isRootAdmin)) {
                    div {
                        className = ClassName("group-side-label")
                        span { +"Channels · ${channels.size}" }
                        if (isRootAdmin) {
                            button {
                                className = ClassName("group-side-label-add")
                                title = "Add channel"
                                onClick = { setShowCreateSubgroup(true) }
                                icon(Ic.Add)
                            }
                        }
                    }
                    channels.forEach { entry ->
                        val channel = metaById[entry.id]
                        val channelName = channel?.name ?: entry.id
                        val active = route.groupId == entry.id
                        // First-level channels sit flush; only nesting below them indents,
                        // capped so deep foreign trees never crush the label.
                        val indent = entry.depth - 1
                        val depthCls = if (indent > 0) " depth${minOf(indent, 3)}" else ""
                        // Only first-level channels reorder: their order is the root's child
                        // tag positions; nested foreign rows just render.
                        val draggable = isRootAdmin && entry.depth == 1
                        button {
                            key = entry.id
                            className =
                                ClassName(
                                    buildString {
                                        append(if (active) "group-side-row active$depthCls" else "group-side-row$depthCls")
                                        if (dragId == entry.id) append(" dragging")
                                        if (overId == entry.id && dragId != null && dragId != entry.id) append(" drag-over")
                                    },
                                )
                            if (draggable) asDynamic()["data-sgid"] = entry.id
                            onClick = { props.onNavigateGroup(GroupRoute(route.relayUrl, entry.id)) }
                            if (draggable) {
                                span {
                                    className = ClassName("group-side-row-drag")
                                    title = "Drag to reorder"
                                    onPointerDown = { e -> startDrag(entry.id, e) }
                                    onClick = { it.stopPropagation() }
                                    icon(Ic.Drag)
                                }
                            }
                            WebAvatar {
                                url = channel?.picture
                                seed = entry.id
                                this.name = channelName
                                kind = org.nostr.nostrord.web.components.AvatarKind.GROUP
                                cls = "group-side-row-avatar"
                            }
                            span {
                                className = ClassName("group-side-row-label")
                                +channelName
                            }
                            if (isLockedChannel(channel, isJoined = entry.id in joinedHere)) {
                                span {
                                    className = ClassName("group-side-row-lock")
                                    title = "Members only"
                                    icon(Ic.Lock)
                                }
                            }
                            val unread = unreadCounts[entry.id] ?: 0
                            if (unread > 0) {
                                span {
                                    className = ClassName("count-badge")
                                    +(if (unread > 99) "99+" else "$unread")
                                }
                            }
                        }
                    }
                    // End drop strip, only while dragging: lets the channel land after the
                    // last row (a row target always inserts BEFORE it).
                    if (dragId != null) {
                        div {
                            className = ClassName(if (overId == END_DROP) "group-side-drop-end drag-over" else "group-side-drop-end")
                            asDynamic()["data-sgid"] = END_DROP
                        }
                    }
                }
            }
        }

        // The sidebar lives inside the mobile nav drawer, whose `transform` would trap a
        // position:fixed overlay inside the drawer; portal these modals to <body> so they cover
        // the viewport.
        if (showMembers) {
            Portal {
                MembersModal {
                    relayUrl = route.relayUrl
                    groupId = rootId
                    onClose = { setShowMembers(false) }
                }
            }
        }
        if (showManage) {
            Portal {
                ManageGroupModal {
                    group = rootMeta ?: placeholderMeta(rootId)
                    initialTab = manageTab
                    relayUrl = route.relayUrl
                    onClose = { setShowManage(false) }
                }
            }
        }
        if (showCreateSubgroup) {
            Portal {
                CreateGroupModal {
                    onClose = { setShowCreateSubgroup(false) }
                    subgroup = true
                    parentGroupId = rootId
                    relayUrl = route.relayUrl
                }
            }
        }
    }

/** `data-sgid` sentinel of the end drop strip (drop after the last channel). */
private const val END_DROP = "__end__"

private fun placeholderMeta(groupId: String): GroupMetadata = GroupMetadata(
    id = groupId,
    name = null,
    about = null,
    picture = null,
    isPublic = true,
    isOpen = true,
)
