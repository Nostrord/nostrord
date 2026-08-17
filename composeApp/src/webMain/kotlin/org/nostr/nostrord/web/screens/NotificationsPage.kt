package org.nostr.nostrord.web.screens

import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.notifications.NotificationEntry
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.navigation.notificationRoute
import org.nostr.nostrord.ui.screens.notifications.NotifFilter
import org.nostr.nostrord.ui.screens.notifications.NotificationsViewModel
import org.nostr.nostrord.ui.screens.notifications.notificationActionLabel
import org.nostr.nostrord.utils.formatTimestamp
import org.nostr.nostrord.utils.normalizeRelayUrl
import org.nostr.nostrord.utils.resolveGroupRef
import org.nostr.nostrord.utils.shortNpub
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.AvatarKind
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.icon
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.useEffect
import web.cssom.ClassName

external interface NotificationsPageProps : Props {
    var vm: NotificationsViewModel
    var onOpenRoute: (GroupRoute) -> Unit
    var onOpenDrawer: () -> Unit
}

/**
 * New-design notifications page (prototype Notifications): header (bell + title + unread pill
 * + "Mark all as read") over the filtered list. The type / group / unread-only filters live in
 * [NotificationsSidebar], sharing the same [NotificationsPageProps.vm].
 */
val NotificationsPage =
    FC<NotificationsPageProps> { props ->
        val vm = props.vm
        val shown = useStateFlow(vm.filtered)
        val unread = useStateFlow(vm.unreadCount)
        val userMetadata = useStateFlow(vm.userMetadata)
        val groupsByRelay = useStateFlow(vm.groupsByRelay)
        val total = useStateFlow(vm.entries)

        useEffect(shown.size) {
            vm.requestUserMetadata(shown.map { it.actorPubkey }.toSet())
        }

        div {
            className = ClassName("npage")
            div {
                className = ClassName("npage-header")
                button {
                    className = ClassName("icon-btn frame-menu-btn")
                    title = "Menu"
                    onClick = { props.onOpenDrawer() }
                    icon(Ic.Menu)
                }
                icon(Ic.Notifications)
                span {
                    className = ClassName("npage-title")
                    +"Notifications"
                }
                if (unread > 0) {
                    span {
                        className = ClassName("npage-unread")
                        +"$unread"
                    }
                }
                button {
                    className = ClassName("npage-markall")
                    disabled = unread == 0
                    onClick = { vm.markAllRead() }
                    icon(Ic.Check)
                    +"Mark all as read"
                }
            }
            div {
                className = ClassName("npage-scroll")
                div {
                    className = ClassName("npage-list")
                    if (shown.isEmpty()) {
                        div {
                            className = ClassName("npage-empty")
                            +(if (total.isEmpty()) "No notifications" else "No notifications match this filter")
                        }
                    } else {
                        shown.forEach { entry ->
                            val meta = userMetadata[entry.actorPubkey]
                            // Live metadata first: the snapshot taken at notification time can
                            // be the truncated id (metadata hadn't landed yet) and would
                            // otherwise shadow the real name forever. The entry's own relay
                            // decides: NIP-29 group ids are relay-local, so a same-id group
                            // elsewhere must not name or paint this row.
                            val groupMeta = resolveGroupRef(groupsByRelay, entry.groupId, entry.relayUrl)
                            val groupName =
                                groupMeta?.name?.takeIf { it.isNotBlank() }
                                    ?: entry.groupName?.takeIf { it.isNotBlank() }
                                    ?: entry.groupId.take(8)
                            notifRow(entry, meta, groupName, groupMeta?.picture) {
                                vm.markRead(entry.id)
                                props.onOpenRoute(notificationRoute(entry.relayUrl, entry.groupId, entry.messageId, entry.threadRootId))
                            }
                        }
                    }
                }
            }
        }
    }

private fun actorName(meta: UserMetadata?, pubkey: String): String = meta?.displayName?.takeIf { it.isNotBlank() }
    ?: meta?.name?.takeIf { it.isNotBlank() }
    ?: shortNpub(pubkey)

private fun actionLabel(entry: NotificationEntry): String = notificationActionLabel(entry)

private fun ChildrenBuilder.notifRow(
    entry: NotificationEntry,
    meta: UserMetadata?,
    groupName: String,
    groupPicture: String?,
    onSelect: () -> Unit,
) {
    val actor = actorName(meta, entry.actorPubkey)
    button {
        className = ClassName(if (entry.read) "npage-row read" else "npage-row")
        onClick = { onSelect() }
        span { className = ClassName(if (entry.read) "npage-dot read" else "npage-dot") }
        WebAvatar {
            url = meta?.picture
            seed = entry.actorPubkey
            name = actor
            kind = AvatarKind.USER
            cls = "npage-avatar"
        }
        div {
            className = ClassName("npage-body")
            div {
                className = ClassName("npage-head")
                span {
                    className = ClassName("npage-name")
                    +actor
                }
                span {
                    className = ClassName("npage-action")
                    +" ${actionLabel(entry)} in "
                }
                span {
                    className = ClassName("npage-group")
                    WebAvatar {
                        url = groupPicture
                        seed = entry.groupId
                        name = groupName
                        kind = AvatarKind.GROUP
                        cls = "npage-group-avatar"
                    }
                    +groupName
                }
            }
            entry.preview.takeIf { it.isNotBlank() }?.let { preview ->
                div {
                    className = ClassName("npage-preview")
                    +preview
                }
            }
        }
        span {
            className = ClassName("npage-time")
            +formatTimestamp(entry.createdAt)
        }
    }
}

external interface NotificationsSidebarProps : Props {
    var vm: NotificationsViewModel
}

/** Notifications filter column (prototype NotificationsSidebar): type tabs + unread-only + groups. */
val NotificationsSidebar =
    FC<NotificationsSidebarProps> { props ->
        val vm = props.vm
        val typeFilter = useStateFlow(vm.typeFilter)
        val unreadOnly = useStateFlow(vm.unreadOnly)
        val groupFilter = useStateFlow(vm.groupFilter)
        val counts = useStateFlow(vm.typeCounts)
        val buckets = useStateFlow(vm.groupBuckets)
        val groupsByRelay = useStateFlow(vm.groupsByRelay)

        div {
            className = ClassName("nside")
            ntab(Ic.Notifications, null, "All", counts.all, typeFilter == NotifFilter.ALL) {
                vm.setTypeFilter(NotifFilter.ALL)
            }
            ntab(null, "@", "Mentions", counts.mentions, typeFilter == NotifFilter.MENTIONS) {
                vm.setTypeFilter(NotifFilter.MENTIONS)
            }
            ntab(Ic.Reply, null, "Replies", counts.replies, typeFilter == NotifFilter.REPLIES) {
                vm.setTypeFilter(NotifFilter.REPLIES)
            }
            ntab(Ic.Forum, null, "Messages", counts.messages, typeFilter == NotifFilter.MESSAGES) {
                vm.setTypeFilter(NotifFilter.MESSAGES)
            }

            button {
                className = ClassName("nside-tab")
                onClick = { vm.setUnreadOnly(!unreadOnly) }
                span {
                    className = ClassName("nside-tab-icon")
                    icon(Ic.Check)
                }
                span {
                    className = ClassName("nside-tab-label")
                    +"Unread only"
                }
                span {
                    className = ClassName(if (unreadOnly) "nside-toggle on" else "nside-toggle")
                    span { className = ClassName("nside-toggle-thumb") }
                }
            }

            div {
                className = ClassName("nside-section")
                +"GROUPS"
            }
            ngroupTab(null, "all", "All groups", 0, groupFilter == null, Ic.People) { vm.setGroupFilter(null) }
            buckets.forEach { bucket ->
                // This relay's kind:39000 first: a same-id group elsewhere is a different group and
                // must not lend its avatar to this tab.
                val picture =
                    groupsByRelay.entries
                        .firstOrNull { it.key.normalizeRelayUrl() == bucket.relayUrl.normalizeRelayUrl() }
                        ?.value?.firstOrNull { it.id == bucket.groupId }?.picture
                ngroupTab(picture, bucket.key, bucket.name, bucket.unread, groupFilter == bucket.key, null) {
                    vm.setGroupFilter(bucket.key)
                }
            }
        }
    }

private fun ChildrenBuilder.ntab(
    ic: Ic?,
    glyph: String?,
    label: String,
    count: Int,
    active: Boolean,
    onSelect: () -> Unit,
) {
    button {
        className = ClassName(if (active) "nside-tab active" else "nside-tab")
        onClick = { onSelect() }
        span {
            className = ClassName("nside-tab-icon")
            if (glyph != null) {
                +glyph
            } else if (ic != null) {
                icon(ic)
            }
        }
        span {
            className = ClassName("nside-tab-label")
            +label
        }
        if (count > 0) {
            span {
                className = ClassName("nside-badge")
                +(if (count > 99) "99+" else "$count")
            }
        }
    }
}

private fun ChildrenBuilder.ngroupTab(
    picture: String?,
    identifier: String,
    label: String,
    unread: Int,
    active: Boolean,
    ic: Ic?,
    onSelect: () -> Unit,
) {
    button {
        className = ClassName(if (active) "nside-tab active" else "nside-tab")
        onClick = { onSelect() }
        if (ic != null) {
            span {
                className = ClassName("nside-tab-icon")
                icon(ic)
            }
        } else {
            WebAvatar {
                url = picture
                seed = identifier
                name = label
                kind = AvatarKind.GROUP
                cls = "nside-group-avatar"
            }
        }
        span {
            className = ClassName("nside-tab-label")
            +label
        }
        if (unread > 0) {
            span {
                className = ClassName("nside-badge")
                +(if (unread > 99) "99+" else "$unread")
            }
        }
    }
}
