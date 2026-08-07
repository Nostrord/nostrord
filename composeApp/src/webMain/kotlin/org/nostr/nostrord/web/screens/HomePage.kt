package org.nostr.nostrord.web.screens

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.navigation.HomeTab
import org.nostr.nostrord.ui.navigation.RelayRoute
import org.nostr.nostrord.ui.screens.home.DiscoverGroup
import org.nostr.nostrord.ui.screens.home.Friend
import org.nostr.nostrord.ui.screens.home.HomePageViewModel
import org.nostr.nostrord.ui.screens.home.JoinedGroup
import org.nostr.nostrord.ui.screens.onboarding.onboardingFollowSuggestions
import org.nostr.nostrord.utils.isJoinedOn
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.AvatarKind
import org.nostr.nostrord.web.components.FollowAllButton
import org.nostr.nostrord.web.components.FollowSuggestionCard
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.groupTypeBadges
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.searchInput
import org.nostr.nostrord.web.components.tabItem
import org.nostr.nostrord.web.navigation.pushRoute
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useEffect
import web.cssom.ClassName

private val FILTERS = listOf("My groups", "From friends", "Recommended", "People")

/** Per-filter icons: own chats, friends, public discovery, people to follow (matches native). */
private val FILTER_ICONS = listOf(Ic.Forum, Ic.People, Ic.ThumbUp, Ic.PersonAdd)

external interface HomePageProps : Props {
    /** Active discovery tab, owned by the router so it survives refresh / is shareable. */
    var tab: HomeTab
    var onSelectTab: (HomeTab) -> Unit
    var onOpenGroup: (JoinedGroup) -> Unit
    var onCreateGroup: () -> Unit
    var onJoinGroup: () -> Unit
    var onOpenDms: () -> Unit
    var onOpenNotifications: () -> Unit
    var onOpenDrawer: () -> Unit
}

/**
 * New-design Home (prototype Home): header bar, title + join/create actions, search +
 * filter pills, and the per-filter content. "My groups" shows the real joined groups
 * (kind:10009) via the shared HomePageViewModel; friends / communities are
 * layout-only empty states and People reuses the curated follow suggestions until the
 * follow logic lands.
 */
val HomePage =
    FC<HomePageProps> { props ->
        val vm = useViewModel { HomePageViewModel(AppModule.nostrRepository) }
        val dmEnabled = useStateFlow(AppModule.dmSettings.dmEnabled)
        // The page list is filtered by the search box; the rail (AppFrame) keeps the full myGroups.
        val myGroups = useStateFlow(vm.filteredMyGroups)
        val query = useStateFlow(vm.query)
        val friends = useStateFlow(vm.friends)
        val friendsGroups = useStateFlow(vm.friendsGroups)
        val recommendedGroups = useStateFlow(vm.recommendedGroups)
        val myGroupsLoading = useStateFlow(vm.myGroupsLoading)
        val muteState = useStateFlow(AppModule.notificationSettings.muteState)
        val friendsGroupsLoading = useStateFlow(vm.friendsGroupsLoading)
        val recommendedGroupsLoading = useStateFlow(vm.recommendedGroupsLoading)
        val relayMeta = useStateFlow(vm.relayMetadata)
        // Membership per (relay, id), to mark the "Joined" badge on cards in mixed lists: ids
        // repeat across relays, so a flat id match badges a group you never joined.
        val joinedByRelay = useStateFlow(AppModule.nostrRepository.joinedGroupsByRelay)
        // Follow state + actor metadata for the "People" filter's follow suggestions.
        val following = useStateFlow(AppModule.nostrRepository.following)
        val actorMeta = useStateFlow(AppModule.nostrRepository.userMetadata)
        // Tab index derived from the router-owned tab; selecting a tab routes (mirror).
        val filter = props.tab.ordinal
        // Each tab is its own screen; carrying the filter text across tabs is confusing, so reset it.
        val setFilter = { index: Int ->
            vm.setQuery("")
            props.onSelectTab(HomeTab.entries[index])
        }

        // Fetch the discovery lists lazily, only when their tab is shown.
        useEffect(filter) {
            if (filter == 1) vm.loadFriendsGroups()
            if (filter == 2) vm.loadRecommended()
            if (filter == 3) {
                val pubkeys = onboardingFollowSuggestions.map { it.pubkey }.filter { it.isNotBlank() }.toSet()
                if (pubkeys.isNotEmpty()) launchApp { AppModule.nostrRepository.requestUserMetadata(pubkeys) }
            }
        }

        div {
            className = ClassName("home-page")

            div {
                className = ClassName("page-header")
                button {
                    className = ClassName("icon-btn frame-menu-btn")
                    title = "Menu"
                    onClick = { props.onOpenDrawer() }
                    icon(Ic.Menu)
                }
                icon(Ic.Home)
                span {
                    className = ClassName("page-header-title")
                    +"Home"
                }
                div {
                    className = ClassName("page-header-actions")
                    if (dmEnabled) {
                        button {
                            className = ClassName("icon-btn")
                            title = "Direct messages"
                            onClick = { props.onOpenDms() }
                            icon(Ic.Mail)
                        }
                    }
                    button {
                        className = ClassName("icon-btn")
                        title = "Notifications"
                        onClick = { props.onOpenNotifications() }
                        icon(Ic.Notifications)
                    }
                }
            }

            div {
                className = ClassName("home-scroll")
                div {
                    className = ClassName("home-content")

                    div {
                        className = ClassName("home-title-row")
                        div {
                            h1 {
                                className = ClassName("home-title")
                                +"Groups"
                            }
                            p {
                                className = ClassName("home-subtitle")
                                +"Your groups and new ones to discover through your friends"
                            }
                        }
                        div {
                            className = ClassName("home-actions")
                            button {
                                className = ClassName("btn-secondary")
                                onClick = { props.onJoinGroup() }
                                icon(Ic.Link)
                                +"Join group"
                            }
                            button {
                                className = ClassName("btn-primary")
                                onClick = { props.onCreateGroup() }
                                icon(Ic.Add)
                                +"Create group"
                            }
                        }
                    }

                    div {
                        className = ClassName("home-toolbar")
                        div {
                            className = ClassName("tab-strip")
                            FILTERS.forEachIndexed { index, label ->
                                tabItem(filter == index, FILTER_ICONS[index], label) { setFilter(index) }
                            }
                        }
                        // Icon-only pills (compact width) lose the active-tab label; restate it
                        // above the filter box. Hidden by CSS at wide widths.
                        h2 {
                            className = ClassName("home-active-filter")
                            +FILTERS[filter]
                        }
                        div {
                            className = ClassName("home-search")
                            searchInput(
                                placeholder = if (filter == 3) "Filter people" else "Filter groups",
                                value = query,
                                onChange = { vm.setQuery(it) },
                            )
                        }
                    }

                    when (filter) {
                        0 ->
                            when {
                                myGroups.isEmpty() && query.isNotBlank() ->
                                    emptyCard("🔍", "No group found", "Try another search term.")
                                myGroups.isEmpty() && myGroupsLoading -> skeletonGrid()
                                myGroups.isEmpty() ->
                                    emptyCard(
                                        emoji = "👋",
                                        title = "You're not in any group yet",
                                        description =
                                        "Join through an invite link, or check From friends for the " +
                                            "groups where people you follow already are.",
                                    ) {
                                        button {
                                            className = ClassName("btn-primary")
                                            onClick = { props.onJoinGroup() }
                                            icon(Ic.Link)
                                            +"Join by link"
                                        }
                                        button {
                                            className = ClassName("btn-secondary")
                                            onClick = { setFilter(1) }
                                            +"See friends' groups"
                                        }
                                    }
                                else ->
                                    div {
                                        className = ClassName("card-grid")
                                        myGroups.forEach { group ->
                                            // Every card here is joined; the badge earns its
                                            // place only in the mixed lists.
                                            discoverGroupCard(
                                                group,
                                                relayMeta[group.relayUrl]?.icon,
                                                isJoined = false,
                                                muted = muteState.isMuted(group.meta.id),
                                            ) {
                                                props.onOpenGroup(JoinedGroup(group.relayUrl, group.meta))
                                            }
                                        }
                                    }
                            }
                        1 ->
                            when {
                                friends.isEmpty() ->
                                    emptyCard(
                                        emoji = "🫂",
                                        title = "You don't follow anyone yet",
                                        description = "Follow some people to see your friends here and the groups where they are.",
                                    ) {
                                        button {
                                            className = ClassName("btn-secondary")
                                            onClick = { setFilter(3) }
                                            +"See people to follow"
                                        }
                                    }
                                friendsGroups.isEmpty() && friendsGroupsLoading -> skeletonGrid()
                                friendsGroups.isEmpty() ->
                                    emptyCard(
                                        emoji = "🔭",
                                        title = "No groups from your friends yet",
                                        description = "When people you follow join groups, those groups show up here to discover.",
                                    )
                                else ->
                                    div {
                                        className = ClassName("card-grid")
                                        friendsGroups.forEach { fg ->
                                            discoverGroupCard(fg, relayMeta[fg.relayUrl]?.icon, joinedByRelay.isJoinedOn(fg.relayUrl, fg.meta.id)) {
                                                props.onOpenGroup(JoinedGroup(fg.relayUrl, fg.meta))
                                            }
                                        }
                                    }
                            }
                        2 ->
                            if (recommendedGroups.isEmpty() && recommendedGroupsLoading) {
                                skeletonGrid()
                            } else if (recommendedGroups.isEmpty()) {
                                emptyCard(
                                    emoji = "✨",
                                    title = "No recommendations yet",
                                    description = "Hand-picked groups we curate will show up here.",
                                )
                            } else {
                                div {
                                    className = ClassName("card-grid")
                                    recommendedGroups.forEach { group ->
                                        discoverGroupCard(group, relayMeta[group.relayUrl]?.icon, joinedByRelay.isJoinedOn(group.relayUrl, group.meta.id)) {
                                            props.onOpenGroup(JoinedGroup(group.relayUrl, group.meta))
                                        }
                                    }
                                }
                            }
                        else ->
                            div {
                                className = ClassName("onb-pack-list")
                                // Curated people shared with the onboarding step, filtered
                                // by the search box; Follow / Following wired to NIP-02.
                                val needle = query.trim().lowercase()
                                val people =
                                    onboardingFollowSuggestions.filter {
                                        needle.isEmpty() ||
                                            it.name.lowercase().contains(needle) ||
                                            it.note.lowercase().contains(needle)
                                    }
                                if (needle.isEmpty()) {
                                    div {
                                        className = ClassName("follow-list-head")
                                        FollowAllButton {
                                            this.people = onboardingFollowSuggestions
                                            this.following = following
                                        }
                                    }
                                }
                                people.forEach { person ->
                                    FollowSuggestionCard {
                                        key = person.npub
                                        this.person = person
                                        pictureUrl = actorMeta[person.pubkey]?.picture
                                        isFollowing = person.pubkey in following
                                    }
                                }
                            }
                    }
                }
            }
        }
    }

/**
 * The one group card used everywhere (My groups / From friends / Recommended): a square
 * avatar, the name, then the people row directly under it (the friends you follow who are
 * here as overlapping avatars, falling back to members, plus the total "N people" count and
 * the restricted badge), and finally a single-line description.
 */
internal fun ChildrenBuilder.discoverGroupCard(
    dg: DiscoverGroup,
    relayIconUrl: String?,
    isJoined: Boolean,
    enableRelayLink: Boolean = true,
    showJoinCta: Boolean = false,
    interactive: Boolean = true,
    showRelay: Boolean = true,
    // Silenced group: the card recedes and carries a bell-off mark, matching the rail chip
    // so the same group doesn't read as two different states on two surfaces.
    muted: Boolean = false,
    onOpen: () -> Unit,
) {
    val meta = dg.meta
    val name = meta.name ?: meta.id
    val relayHost = dg.relayUrl.removePrefix("wss://").removePrefix("ws://").trimEnd('/')
    val count = if (dg.people.isNotEmpty()) maxOf(dg.memberCount, dg.people.size) else dg.memberCount
    // peopleLoading is owned by the VM: true only while the member list is in flight,
    // and flips off (skeleton stops) once the list arrives or the fetch times out.
    val peopleLoading = dg.peopleLoading
    button {
        key = "${dg.relayUrl}/${meta.id}"
        // Non-interactive in onboarding: the card itself does nothing (no pointer); only
        // the Join button acts, so the user can join several groups without leaving.
        className =
            ClassName(
                buildString {
                    append(if (interactive) "group-card" else "group-card static")
                    if (muted) append(" muted")
                },
            )
        if (interactive) onClick = { onOpen() }
        div {
            className = ClassName("group-card-head")
            WebAvatar {
                url = meta.picture
                seed = meta.id
                this.name = name
                kind = AvatarKind.GROUP
                cls = "group-card-avatar"
            }
            div {
                div {
                    className = ClassName("group-card-name-row")
                    div {
                        className = ClassName("group-card-name")
                        +name
                    }
                    if (muted) {
                        span {
                            className = ClassName("group-card-muted")
                            title = "Muted"
                            icon(Ic.NotificationsOff)
                        }
                    }
                    // The onboarding card carries its own trailing Join/Joined action
                    // (below); only the plain Home card shows the inline "Joined" badge.
                    if (isJoined && !showJoinCta) {
                        span {
                            className = ClassName("group-card-joined")
                            icon(Ic.Check)
                            +"Joined"
                        }
                    }
                }
                div {
                    className = ClassName("group-card-people")
                    // No one to preview (and not still loading): fall back to the group's
                    // access-tag badges so the row carries info instead of sitting empty.
                    val showTags = dg.people.isEmpty() && !peopleLoading && dg.hasMetadata
                    when {
                        dg.people.isNotEmpty() ->
                            div {
                                className = ClassName("discover-avatars")
                                dg.people.take(5).forEach { person ->
                                    WebAvatar {
                                        url = person.metadata?.picture
                                        seed = person.pubkey
                                        this.name = personName(person)
                                        cls = "discover-avatar"
                                    }
                                }
                            }
                        peopleLoading ->
                            div {
                                className = ClassName("discover-avatars")
                                repeat(4) {
                                    div { className = ClassName("discover-avatar skel") }
                                }
                            }
                        showTags -> groupTypeBadges(meta)
                    }
                    when {
                        !showTags && count > 0 ->
                            span {
                                className = ClassName("group-card-people-count")
                                +"$count people"
                            }
                        peopleLoading ->
                            div { className = ClassName("skel group-card-people-skel") }
                    }
                    if (meta.isRestricted) {
                        span {
                            className = ClassName("info-badge danger")
                            +"Restricted"
                        }
                    }
                }
            }
            // Trailing Join / Joined action, right-aligned (onboarding only). The state
            // shows inside the same button (like the follow toggle); joining marks the
            // card "Joined" in place rather than removing it.
            if (showJoinCta) {
                div {
                    className = ClassName("group-card-action")
                    button {
                        className = ClassName("group-card-join " + if (isJoined) "btn-secondary joined" else "btn-primary")
                        if (!isJoined) {
                            onClick = {
                                it.stopPropagation()
                                onOpen()
                            }
                        }
                        if (isJoined) {
                            icon(Ic.Check)
                            +"Joined"
                        } else {
                            icon(Ic.Add)
                            +"Join"
                        }
                    }
                }
            }
        }
        p {
            className = ClassName("group-card-desc")
            +(meta.about.orEmpty().ifBlank { "No description" })
        }
        // Host relay on its own muted line (icon + short hostname), so the same group on
        // two relays is told apart. Hidden in onboarding (no relay context there yet).
        if (relayHost.isNotBlank() && showRelay) {
            div {
                className = ClassName("group-card-relay")
                WebAvatar {
                    url = relayIconUrl
                    seed = dg.relayUrl
                    this.name = relayHost
                    kind = AvatarKind.RELAY
                    cls = "group-card-relay-icon"
                }
                // Only the hostname is the relay link, never the whole row, so tapping the card
                // body always opens the group. Inert where there is no relay route (onboarding).
                span {
                    className = ClassName(if (enableRelayLink) "group-card-relay-host link" else "group-card-relay-host")
                    if (enableRelayLink) {
                        title = "Open relay"
                        onClick = {
                            it.stopPropagation()
                            pushRoute(RelayRoute(dg.relayUrl))
                        }
                    }
                    +relayHost
                }
            }
        }
    }
}

/** A person's display name for a discovery card: profile name, then a short npub. */
private fun personName(friend: Friend): String = friend.metadata?.displayName?.takeIf { it.isNotBlank() }
    ?: friend.metadata?.name?.takeIf { it.isNotBlank() }
    ?: (runCatching { Nip19.encodeNpub(friend.pubkey) }.getOrDefault(friend.pubkey).take(10) + "…")

/** How many group-card skeletons to show while a discovery tab is still loading. */
private const val SKELETON_CARD_COUNT = 6

/** A grid of shimmer cards shown while a discovery tab is still loading. */
internal fun ChildrenBuilder.skeletonGrid() {
    div {
        className = ClassName("card-grid")
        repeat(SKELETON_CARD_COUNT) { groupCardSkeleton() }
    }
}

/** Shimmer placeholder shaped like a .group-card. */
internal fun ChildrenBuilder.groupCardSkeleton() {
    div {
        className = ClassName("group-card skel-card")
        div {
            className = ClassName("group-card-head")
            div { className = ClassName("group-card-avatar skel") }
            div {
                className = ClassName("skel-card-lines")
                div { className = ClassName("skel skel-line w-60") }
                div { className = ClassName("skel skel-line w-30") }
            }
        }
        div { className = ClassName("skel skel-line skel-card-desc") }
    }
}

private fun ChildrenBuilder.emptyCard(
    emoji: String,
    title: String,
    description: String,
    actions: (ChildrenBuilder.() -> Unit)? = null,
) {
    div {
        className = ClassName("empty-card")
        div {
            className = ClassName("empty-card-emoji")
            +emoji
        }
        h2 {
            className = ClassName("empty-card-title")
            +title
        }
        p {
            className = ClassName("empty-card-desc")
            +description
        }
        actions?.let {
            div {
                className = ClassName("empty-card-actions")
                it()
            }
        }
    }
}
