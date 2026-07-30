package org.nostr.nostrord.ui.components.layout

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nostr.nostrord.auth.removeAccountBusyLabel
import org.nostr.nostrord.auth.removeAccountConfirmLabel
import org.nostr.nostrord.auth.removeAccountDialogBody
import org.nostr.nostrord.auth.removeAccountDialogTitle
import org.nostr.nostrord.auth.signerLabel
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.startup.ExternalLaunchContext
import org.nostr.nostrord.startup.StartupResolver
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.ui.components.BunkerStatusBanner
import org.nostr.nostrord.ui.components.ConfirmDialog
import org.nostr.nostrord.ui.components.accounts.AddAccountSheet
import org.nostr.nostrord.ui.components.avatars.OptimizedSmallAvatar
import org.nostr.nostrord.ui.components.buttons.AppButton
import org.nostr.nostrord.ui.components.buttons.AppButtonSize
import org.nostr.nostrord.ui.components.buttons.AppButtonVariant
import org.nostr.nostrord.ui.components.forms.AppSearchField
import org.nostr.nostrord.ui.components.forms.AppSegmentedTabs
import org.nostr.nostrord.ui.components.forms.InputSize
import org.nostr.nostrord.ui.components.forms.SegmentedTab
import org.nostr.nostrord.ui.components.loading.SkeletonCircle
import org.nostr.nostrord.ui.components.loading.SkeletonLine
import org.nostr.nostrord.ui.components.navigation.NavigationToolbar
import org.nostr.nostrord.ui.components.zap.ZapModalHost
import org.nostr.nostrord.ui.navigation.DmRoute
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.navigation.GroupView
import org.nostr.nostrord.ui.navigation.HashRoute
import org.nostr.nostrord.ui.navigation.HomeRoute
import org.nostr.nostrord.ui.navigation.HomeTab
import org.nostr.nostrord.ui.navigation.LocalFrameNavigator
import org.nostr.nostrord.ui.navigation.NavigationHistory
import org.nostr.nostrord.ui.navigation.NotificationsRoute
import org.nostr.nostrord.ui.navigation.PlatformBackHandler
import org.nostr.nostrord.ui.navigation.RelayRoute
import org.nostr.nostrord.ui.navigation.SettingsRoute
import org.nostr.nostrord.ui.navigation.UserRoute
import org.nostr.nostrord.ui.navigation.persistedRouteHash
import org.nostr.nostrord.ui.navigation.restoredRoute
import org.nostr.nostrord.ui.screens.dm.DmPageScreen
import org.nostr.nostrord.ui.screens.group.GroupScreen
import org.nostr.nostrord.ui.screens.group.ThreadsScreen
import org.nostr.nostrord.ui.screens.group.components.AddGroupModal
import org.nostr.nostrord.ui.screens.group.components.CreateGroupModal
import org.nostr.nostrord.ui.screens.group.components.JoinGroupModal
import org.nostr.nostrord.ui.screens.group.components.UserProfileModal
import org.nostr.nostrord.ui.screens.group.rootGroupId
import org.nostr.nostrord.ui.screens.home.Friend
import org.nostr.nostrord.ui.screens.home.HomePageScreen
import org.nostr.nostrord.ui.screens.home.HomePageViewModel
import org.nostr.nostrord.ui.screens.notifications.NotificationsPage
import org.nostr.nostrord.ui.screens.notifications.NotificationsSidebar
import org.nostr.nostrord.ui.screens.notifications.NotificationsViewModel
import org.nostr.nostrord.ui.screens.profile.ProfilePageScreen
import org.nostr.nostrord.ui.screens.relay.RelayPageScreen
import org.nostr.nostrord.ui.screens.settings.SettingsScreen
import org.nostr.nostrord.ui.theme.NostrordAnimation
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.window.LocalDesktopWindowControls
import org.nostr.nostrord.ui.window.onBackForwardMouseButtons
import org.nostr.nostrord.utils.accountDisplayLabel
import org.nostr.nostrord.utils.normalizeRelayUrl
import org.nostr.nostrord.utils.rememberClipboardWriter

/**
 * New-design logged-in frame (prototype AppShell): the 72px groups rail (home,
 * joined groups with unread badges, add, DMs and notifications) plus the 240px
 * sidebar (home hub or the open group's sidebar, with the account bar) around the
 * content. Owns the new-design navigation: home vs an open group ([GroupRoute]),
 * mirroring the web's #/g/<relay>/<groupId> hash. Below md (768dp) the rail +
 * sidebar collapse into a ModalNavigationDrawer opened from each screen's hamburger.
 */
@Composable
fun AppFrame() {
    // HomePageViewModel re-arms its per-account state when the active account changes
    // (it observes repo.activePubkey), so a single long-lived instance is correct here.
    val vm = viewModel { HomePageViewModel(AppModule.nostrRepository, AppModule.notificationHistoryStore) }
    // railGroups (not myGroups): the rail + back-history label only read meta/relayUrl, so this
    // meta-only projection (distinctUntilChanged) skips the member-avatar metadata waves that would
    // otherwise recompose the whole rail dozens of times on home open.
    val groups by vm.railGroups.collectAsState()
    // Channel model: the rail shows only root groups; a subgroup lives in its root's
    // channel list. `groups` stays the full joined list for restore validation and
    // history labels, which must keep resolving subgroup routes.
    val railRoots by vm.railRootGroups.collectAsState()
    val railUnread by vm.railUnreadCounts.collectAsState()
    val groupParents by vm.groupParents.collectAsState()
    val notificationUnread by vm.notificationUnread.collectAsState()
    val dmUnread by AppModule.nostrRepository.totalDmUnread.collectAsState()
    val dmEnabled by AppModule.dmSettings.dmEnabled.collectAsState()
    var addGroupStep by remember { mutableStateOf<AddGroupStep?>(null) }
    // Friend tapped in the home sidebar: open the quick profile modal first (no
    // chat composer around, so no Mention action), with "View profile" inside it
    // for the full page.
    var profileUser by remember { mutableStateOf<String?>(null) }
    // Notifications page open over the content, with the filter sidebar; one shared VM
    // keeps the sidebar filters and the list in sync.
    val notifVm = viewModel { NotificationsViewModel(AppModule.nostrRepository) }

    // Discord-style restore: reopen straight into the last page this account had open (a
    // group or a home tab). Read synchronously when the history is first built so there is no
    // Home -> group flash; it is a single per-account pref read. seedDeepLink keeps Home under
    // a group so back returns Home instead of leaving the app, and no-ops for plain Home.
    fun restoredStartRoute(): HashRoute? = restoredRoute(AppModule.nostrRepository.getPublicKey()?.let { SecureStorage.getLastRoute(it) })
    val restoredSeed = remember { restoredStartRoute() }
    val history = remember { NavigationHistory().apply { seedDeepLink(restoredSeed) } }

    // Deep links, cold start and running app alike (argv / Android intent post to
    // runtimeLaunchEvents; replay = 1 holds an event posted before this collector
    // mounts). GroupRoute carries invite/message, so the existing groupRoute effect
    // below does the relay switch and the group page does the auto-join, same as any
    // navigation.
    LaunchedEffect(Unit) {
        StartupResolver.runtimeLaunchEvents.collect { ctx ->
            StartupResolver.clearExternalLaunchContext()
            when (ctx) {
                is ExternalLaunchContext.OpenGroup ->
                    history.navigate(
                        GroupRoute(
                            relayUrl = ctx.relayUrl ?: AppModule.nostrRepository.currentRelayUrl.value,
                            groupId = ctx.groupId,
                            inviteCode = ctx.inviteCode,
                            messageId = ctx.messageId,
                        ),
                    )
                is ExternalLaunchContext.OpenRelay -> history.navigate(RelayRoute(ctx.relayUrl))
                is ExternalLaunchContext.OpenNotifications -> history.navigate(NotificationsRoute)
                is ExternalLaunchContext.OpenHome -> history.navigate(null)
            }
        }
    }
    val nav by history.state.collectAsState()
    val route = nav.current
    val groupRoute = route as? GroupRoute
    // Notifications is a real route now; the rail and sidebar gate their layout on it.
    val showNotifications = route is NotificationsRoute

    // DMs disabled while a DM route is on screen (toggled off, or a restored/deep-linked DM
    // route): bounce home so the hidden feature can't stay visible.
    LaunchedEffect(dmEnabled, route) {
        if (!dmEnabled && route is DmRoute) history.navigate(null)
    }

    // A genuine account switch drops the previous account's history (its open group or
    // profile must never leak into the new session) and re-seeds into the new account's own
    // last open page, mirroring the cold-start restore. The first activeId emission (the
    // initial composition) is a no-op so the route seeded at startup survives.
    val activeId by AppModule.accountStore.activeId.collectAsState()
    var seenActiveId by remember { mutableStateOf(false) }
    LaunchedEffect(activeId) {
        if (seenActiveId) {
            val restored = withContext(Dispatchers.Default) { restoredStartRoute() }
            if (restored != null) history.seedDeepLink(restored) else history.reset()
        } else {
            seenActiveId = true
        }
    }

    // Desktop draws the NavigationToolbar (back/forward arrows + window controls) at the top
    // of the frame; mobile has no window chrome. backHistory feeds the back arrow's
    // long-press dropdown, most-recent first.
    val hasWindowControls = LocalDesktopWindowControls.current != null
    val backHistory =
        nav.backStack.asReversed().map { entry ->
            navEntryLabel(entry) { gid -> groups.firstOrNull { it.meta.id == gid }?.meta?.name }
        }

    // Mirror the legacy AppShell: cross-relay navigation switches the relay first,
    // and the open group is tracked for notification suppression + unread clearing.
    LaunchedEffect(groupRoute) {
        // Off the Main dispatcher: switchRelay and markGroupAsRead do blocking
        // EncryptedSharedPreferences reads/writes; on Android, running them on the LaunchedEffect's
        // Main context froze the UI (and could ANR) on every group / rail switch.
        withContext(Dispatchers.Default) {
            val r = groupRoute
            if (r != null && r.relayUrl != AppModule.nostrRepository.currentRelayUrl.value) {
                AppModule.nostrRepository.switchRelay(r.relayUrl)
            }
            AppModule.nostrRepository.setActiveGroup(r?.groupId)
            if (r != null) AppModule.nostrRepository.markGroupAsRead(r.groupId)
        }
    }

    // Persist the current page (groups and home only) so the next launch reopens it.
    // Other pages return null from persistedRouteHash and leave the slot unchanged.
    LaunchedEffect(route) {
        val hash = persistedRouteHash(route) ?: return@LaunchedEffect
        withContext(Dispatchers.Default) {
            AppModule.nostrRepository.getPublicKey()?.let { pk ->
                SecureStorage.saveLastRoute(pk, hash)
            }
        }
    }

    // If the restored group was left on another device/session it is no longer in the rail;
    // once the group list loads, drop a now-missing restored route back to Home. Scoped to
    // the first non-empty groups emission so it never fights normal in-session navigation,
    // and only to the persisted seed: a deep-linked route may point at a not-yet-joined
    // group (auto-join flow) and must survive this check.
    var validatedRestore by remember { mutableStateOf(false) }
    LaunchedEffect(groups) {
        if (!validatedRestore && groups.isNotEmpty()) {
            val r = history.current as? GroupRoute
            if (r != null && r == restoredSeed && groups.none { it.relayUrl == r.relayUrl && it.meta.id == r.groupId }) {
                history.reset()
            }
            validatedRestore = true
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    val closeDrawer: () -> Unit = { drawerScope.launch { drawerState.close() } }

    // Dismiss the soft keyboard as soon as the drawer starts opening (hamburger tap or
    // swipe-in gesture), so it never overlaps the slide-over sidebar.
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(drawerState.targetValue) {
        if (drawerState.targetValue == DrawerValue.Open) {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    // Android system back / swipe-back: close an open drawer or modal first, otherwise step
    // back through the shared history. At Home with nothing left to pop the handler is
    // disabled, so the gesture leaves the app to the launcher as Android users expect (the
    // session stays warm in the background). No-op on desktop and iOS.
    PlatformBackHandler(
        enabled = drawerState.isOpen || addGroupStep != null || profileUser != null || nav.canGoBack,
    ) {
        when {
            drawerState.isOpen -> closeDrawer()
            addGroupStep != null -> addGroupStep = null
            profileUser != null -> profileUser = null
            else -> history.back()
        }
    }

    // The 72px rail + 240px sidebar, shared by the desktop Row and the mobile drawer.
    val railContent: @Composable () -> Unit = {
        Column(
            modifier =
            Modifier
                .width(72.dp)
                .fillMaxHeight()
                .background(NostrordColors.BackgroundDark)
                // Background bleeds to the screen edges; only the icons are inset off the
                // status bar (top), gesture bar (bottom) and any left-edge cutout.
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Top cluster fills all remaining height so the divider + DMs +
            // notifications below it stay pinned to the bottom of the rail.
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RailButton(icon = Icons.Default.Home, label = "Home", active = (route == null || route is HomeRoute) && !showNotifications) {
                    history.navigate(null)
                    closeDrawer()
                }
                Column(
                    modifier =
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // An open channel highlights its root's rail chip (the chip a click
                    // would re-open); badge counts aggregate the root's whole subtree.
                    // Matched by (relay, id): a same-id group on another relay is a
                    // different chip and must not light up too.
                    val activeRootId = groupRoute?.groupId?.let { open -> rootGroupId(open) { groupParents[it] } }
                    val activeRelay = groupRoute?.relayUrl?.normalizeRelayUrl()
                    railRoots.forEach { group ->
                        RailGroupButton(
                            name = group.meta.name ?: group.meta.id,
                            picture = group.meta.picture,
                            groupId = group.meta.id,
                            unread = railUnread[group.meta.id] ?: 0,
                            active = activeRootId == group.meta.id &&
                                activeRelay == group.relayUrl.normalizeRelayUrl() &&
                                !showNotifications,
                        ) {
                            history.navigate(GroupRoute(group.relayUrl, group.meta.id))
                            closeDrawer()
                        }
                    }
                    // Add-group is the last scrollable item (after the groups) so the group
                    // list keeps all the rail space and scrolls together with it.
                    RailButton(icon = Icons.Default.Add, label = "Add group") {
                        addGroupStep = AddGroupStep.CHOOSER
                        closeDrawer()
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.width(32.dp), color = NostrordColors.Divider)
            if (dmEnabled) {
                Box {
                    RailButton(icon = Icons.Default.Mail, label = "Direct messages", active = route is DmRoute && !showNotifications) {
                        history.navigate(DmRoute())
                        closeDrawer()
                    }
                    if (dmUnread > 0) {
                        RailBadge(
                            count = dmUnread,
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                    }
                }
            }
            Box {
                RailButton(
                    icon = Icons.Default.Notifications,
                    label = "Notifications",
                    active = showNotifications,
                ) {
                    history.navigate(NotificationsRoute)
                    closeDrawer()
                }
                if (notificationUnread > 0) {
                    RailBadge(
                        count = notificationUnread,
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
            }
        }
    }

    val sidebarContent: @Composable () -> Unit = {
        Column(
            modifier =
            Modifier
                .width(240.dp)
                .fillMaxHeight()
                .background(NostrordColors.Surface)
                // Background bleeds; the header and the account bar are inset off the
                // status bar (top) and gesture bar (bottom).
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical)),
        ) {
            if (showNotifications) {
                Box(modifier = Modifier.weight(1f)) {
                    NotificationsSidebar(vm = notifVm)
                }
            } else if (groupRoute != null) {
                Box(modifier = Modifier.weight(1f)) {
                    GroupSidebar(
                        route = groupRoute,
                        onNavigateGroup = {
                            history.navigate(it)
                            closeDrawer()
                        },
                        onNavigateRelay = {
                            history.navigate(RelayRoute(it))
                            closeDrawer()
                        },
                        onNavigateHome = {
                            history.navigate(null)
                            closeDrawer()
                        },
                    )
                }
            } else if (route is DmRoute && dmEnabled) {
                Box(modifier = Modifier.weight(1f)) {
                    DmSidebar(
                        onOpenConversation = {
                            history.navigate(it)
                            closeDrawer()
                        },
                        activePubkey = route.pubkey,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        // Matches the web .sidebar-header: uppercase, muted, bold (13sp/700).
                        "NOSTRORD",
                        color = NostrordColors.TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                HorizontalDivider(color = NostrordColors.Divider)
                HomeHub(
                    vm = vm,
                    onOpenUser = {
                        profileUser = it
                        closeDrawer()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            AccountBar(
                onOpenSettings = { history.navigate(SettingsRoute) },
                onViewProfile = { pubkey -> history.navigate(UserRoute(pubkey)) },
            )
        }
    }

    val contentArea: @Composable (forceDesktop: Boolean, onOpenDrawer: (() -> Unit)?) -> Unit = { forceDesktop, onOpenDrawer ->
        FrameContent(
            route = route,
            forceDesktop = forceDesktop,
            notifVm = notifVm,
            onNavigate = { history.navigate(it) },
            onNavigateBackOr = { history.navigateBackOr(it) },
            onSelectHomeTab = { tab -> history.navigate(if (tab == HomeTab.Groups) null else HomeRoute(tab)) },
            onCloseGroup = { history.navigate(null) },
            onConsumeInvite = { (route as? GroupRoute)?.let { history.replace(it.copy(inviteCode = null)) } },
            onConsumeMessageTarget = { (route as? GroupRoute)?.let { history.replace(it.copy(messageId = null)) } },
            onEditProfile = { history.navigate(SettingsRoute) },
            onCreateGroup = { addGroupStep = AddGroupStep.CREATE },
            onJoinGroup = { addGroupStep = AddGroupStep.JOIN },
            onOpenNotifications = { history.navigate(NotificationsRoute) },
            onOpenDrawer = onOpenDrawer,
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (hasWindowControls) {
            NavigationToolbar(
                canGoBack = nav.canGoBack,
                canGoForward = nav.canGoForward,
                onBack = { history.back() },
                onForward = { history.forward() },
                backHistory = backHistory,
                onJumpBack = { stepsBack -> history.goToIndex(nav.index - 1 - stepsBack) },
            )
        }
        BoxWithConstraints(
            modifier =
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .onBackForwardMouseButtons(onBack = { history.back() }, onForward = { history.forward() })
                .onKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown && keyEvent.isAltPressed) {
                        when (keyEvent.key) {
                            Key.DirectionLeft -> {
                                history.back()
                                true
                            }
                            Key.DirectionRight -> {
                                history.forward()
                                true
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                },
        ) {
            // Below md (768) the rail + sidebar collapse into a slide-over drawer; a hamburger
            // in each screen header opens it. At md+ they're persistent columns.
            if (maxWidth < 768.dp) {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet(
                            drawerContainerColor = NostrordColors.BackgroundDark,
                            modifier = Modifier.width(312.dp),
                        ) {
                            Row(modifier = Modifier.fillMaxSize()) {
                                railContent()
                                sidebarContent()
                            }
                        }
                    },
                ) {
                    // Full-width content: background bleeds, content stays clear of both
                    // side cutouts, the bars and the keyboard.
                    Box(
                        modifier =
                        Modifier
                            .fillMaxSize()
                            .background(NostrordColors.Background)
                            .windowInsetsPadding(WindowInsets.safeDrawing),
                    ) {
                        contentArea(false) { drawerScope.launch { drawerState.open() } }
                    }
                }
            } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    railContent()
                    sidebarContent()
                    // Content sits to the right of the sidebar: background bleeds, content
                    // is inset off the right cutout, the bars and the keyboard.
                    Box(
                        modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(NostrordColors.Background)
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical),
                            ),
                    ) {
                        contentArea(true, null)
                    }
                }
            }

            // Floating signer-health warning, drawn over the content on every page
            // when the bunker is Unreachable/Reconnecting.
            BunkerStatusBanner(
                modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
            )

            // Add-group flow (rail "+"): the new-design chooser, then the existing
            // create / join modals, landing on the group's chat afterwards.
            when (addGroupStep) {
                AddGroupStep.CHOOSER ->
                    AddGroupModal(
                        onJoin = { addGroupStep = AddGroupStep.JOIN },
                        onCreate = { addGroupStep = AddGroupStep.CREATE },
                        onDismiss = { addGroupStep = null },
                    )
                AddGroupStep.CREATE -> {
                    val currentRelayUrl by AppModule.nostrRepository.currentRelayUrl.collectAsState()
                    val kind10009Relays by AppModule.nostrRepository.kind10009Relays.collectAsState()
                    CreateGroupModal(
                        currentRelayUrl = currentRelayUrl,
                        userRelays = kind10009Relays,
                        onDismiss = { addGroupStep = null },
                        onGroupCreated = { relayUrl, groupId, _ ->
                            addGroupStep = null
                            history.navigate(GroupRoute(relayUrl, groupId))
                        },
                    )
                }
                AddGroupStep.JOIN ->
                    JoinGroupModal(
                        onJoin = { relayUrl, groupId, inviteCode ->
                            addGroupStep = null
                            // Open the group; the route effect switches relays and the
                            // group screen consumes the invite code (auto-join). Normalized
                            // so the route matches the relay keys used everywhere else.
                            history.navigate(GroupRoute(relayUrl.normalizeRelayUrl(), groupId, inviteCode))
                        },
                        onDismiss = { addGroupStep = null },
                    )
                null -> {}
            }

            // Quick profile modal for a friend tapped in the home sidebar. No onMention
            // (we're not in a group chat, so the Mention row stays hidden); "View profile"
            // and "Message" route through the frame navigator to the full page / DM.
            profileUser?.let { pubkey ->
                val userMetadata by AppModule.nostrRepository.userMetadata.collectAsState()
                CompositionLocalProvider(
                    LocalFrameNavigator provides {
                        history.navigate(it)
                        profileUser = null
                    },
                ) {
                    UserProfileModal(
                        pubkey = pubkey,
                        metadata = userMetadata[pubkey],
                        userMetadata = userMetadata,
                        onUserClick = { profileUser = it },
                        onDismiss = { profileUser = null },
                    )
                }
            }

            // Settings opens full-screen over the rail and sidebar (its own layout carries
            // the close button); the toolbar above stays visible so back/forward still leave it.
            if (route is SettingsRoute) {
                // Settings content can navigate (Muted users rows open the profile page);
                // the route change away from SettingsRoute closes the overlay. Also close
                // the mobile drawer: settings is usually opened from it, and the drawer
                // would otherwise still be sitting open over the destination page.
                CompositionLocalProvider(
                    LocalFrameNavigator provides {
                        closeDrawer()
                        history.navigate(it)
                    },
                ) {
                    // Full-screen overlay: background bleeds to the edges, content inset off the bars.
                    Box(
                        modifier =
                        Modifier
                            .fillMaxSize()
                            .background(NostrordColors.Background)
                            .windowInsetsPadding(WindowInsets.safeDrawing),
                    ) {
                        SettingsScreen(
                            onClose = { history.back() },
                            // Legacy in-page navigation targets the old Screen graph the new frame
                            // doesn't route; just close, like the old overlay did.
                            onNavigate = { history.back() },
                            onLogout = {
                                history.back()
                                AppModule.accountStore.activeId.value?.let { AppModule.accountManager.removeAccountAsync(it) }
                            },
                        )
                    }
                }
            }

            // Zap modal host: mounted once so any ZapController.request(...) from a profile,
            // profile modal or message renders the send-zap modal over the frame.
            ZapModalHost()
        }
    }
}

/** Home page, the open group's chat (the legacy GroupScreen, full-featured) or a profile. */
@Composable
private fun FrameContent(
    route: HashRoute?,
    forceDesktop: Boolean,
    notifVm: NotificationsViewModel,
    onNavigate: (HashRoute) -> Unit,
    onNavigateBackOr: (HashRoute) -> Unit,
    onSelectHomeTab: (HomeTab) -> Unit,
    onCloseGroup: () -> Unit,
    onConsumeInvite: () -> Unit,
    onConsumeMessageTarget: () -> Unit,
    onEditProfile: () -> Unit,
    onCreateGroup: () -> Unit,
    onJoinGroup: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenDrawer: (() -> Unit)? = null,
) {
    CompositionLocalProvider(LocalFrameNavigator provides onNavigate) {
        when (route) {
            null, is HomeRoute ->
                HomePageScreen(
                    tab = (route as? HomeRoute)?.tab ?: HomeTab.Groups,
                    onSelectTab = onSelectHomeTab,
                    onOpenGroup = { onNavigate(GroupRoute(it.relayUrl, it.meta.id)) },
                    onOpenRelay = { onNavigate(RelayRoute(it)) },
                    onCreateGroup = onCreateGroup,
                    onJoinGroup = onJoinGroup,
                    onOpenDms = { onNavigate(DmRoute()) },
                    onOpenNotifications = onOpenNotifications,
                    onOpenDrawer = onOpenDrawer,
                )
            is UserRoute ->
                ProfilePageScreen(
                    pubkey = route.pubkey,
                    onOpenGroup = onNavigate,
                    onEditProfile = onEditProfile,
                    onOpenDrawer = onOpenDrawer,
                )
            is DmRoute -> {
                // Guarded: a LaunchedEffect bounces DmRoute home when DMs are off, so this renders
                // only while enabled (the guard covers the frame before the redirect lands).
                val dmEnabled by AppModule.dmSettings.dmEnabled.collectAsState()
                if (dmEnabled) {
                    DmPageScreen(
                        pubkey = route.pubkey,
                        onOpenProfile = onNavigate,
                        onOpenConversation = onNavigate,
                        onOpenGroup = { relay, gid -> onNavigate(GroupRoute(relay, gid)) },
                        onOpenDrawer = onOpenDrawer,
                    )
                }
            }
            // Notifications is a real route rendered here so back/forward traverse it like
            // any page. Settings is a full-screen overlay in AppFrame (over the rail and
            // sidebar), so its arm is empty.
            is NotificationsRoute ->
                NotificationsPage(
                    vm = notifVm,
                    onOpenGroupAtRelay = { gid, _, relay, mid -> onNavigate(GroupRoute(relay, gid, messageId = mid)) },
                    onOpenDrawer = onOpenDrawer,
                )
            is SettingsRoute -> {}
            is RelayRoute ->
                RelayPageScreen(
                    relayUrl = route.relayUrl,
                    onOpenGroup = { relay, gid -> onNavigate(GroupRoute(relay, gid)) },
                    onOpenDrawer = onOpenDrawer,
                )
            is GroupRoute ->
                if (route.view == GroupView.Threads) {
                    // Forum threads pane. The group rail + sidebar stay mounted, so only this
                    // centre pane swaps when leaving chat (mirrors the web AppFrame branch).
                    ThreadsScreen(
                        route = route,
                        onNavigate = onNavigate,
                        // Smart "up": pop to the threads list when it's the entry we came from,
                        // else push it (a deep link straight to a thread has no list behind it).
                        onBack = { onNavigateBackOr(route.copy(threadRootId = null)) },
                        onOpenDrawer = onOpenDrawer,
                    )
                } else {
                    val groupsByRelay by AppModule.nostrRepository.groupsByRelay.collectAsState()
                    val name = groupsByRelay[route.relayUrl]?.firstOrNull { it.id == route.groupId }?.name
                    GroupScreen(
                        groupId = route.groupId,
                        groupName = name,
                        relayUrl = route.relayUrl,
                        onNavigateHome = onCloseGroup,
                        onNavigateToGroup = { gid, _, relay, mid ->
                            onNavigate(GroupRoute(relay ?: route.relayUrl, gid, messageId = mid))
                        },
                        targetMessageId = route.messageId,
                        onTargetMessageConsumed = onConsumeMessageTarget,
                        onOpenRelay = { onNavigate(RelayRoute(it)) },
                        showServerRail = false,
                        forceDesktop = forceDesktop,
                        onOpenDrawer = onOpenDrawer ?: {},
                        pendingInviteCode = route.inviteCode,
                        onInviteCodeConsumed = onConsumeInvite,
                    )
                }
        }
    }
}

@Composable
private fun RailButton(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val highlighted = active || isHovered
    // Morph the corner radius smoothly (16dp squircle -> 12dp on hover/active) instead of
    // snapping the shape, matching the rail feel on the main branch.
    val cornerRadius by animateDpAsState(
        targetValue = if (highlighted) NostrordShapes.serverIconActive else NostrordShapes.serverIconDefault,
        animationSpec = NostrordAnimation.standardSpec(),
        label = "railBtnCorner",
    )
    val shape = RoundedCornerShape(cornerRadius)
    Surface(
        modifier =
        Modifier
            .size(48.dp)
            .clip(shape)
            .hoverable(interactionSource),
        shape = shape,
        color = if (highlighted) NostrordColors.Primary else NostrordColors.Background,
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (highlighted) Color.White else NostrordColors.TextPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun RailGroupButton(
    name: String,
    picture: String?,
    groupId: String,
    unread: Int,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val highlighted = active || isHovered
    // Smoothly morph the corner radius (16dp -> 12dp) rather than snapping the shape,
    // matching the softer rail feel on the main branch.
    val cornerRadius by animateDpAsState(
        targetValue = if (highlighted) NostrordShapes.serverIconActive else NostrordShapes.serverIconDefault,
        animationSpec = NostrordAnimation.standardSpec(),
        label = "railGroupCorner",
    )
    val shape = RoundedCornerShape(cornerRadius)
    // Span the full rail width so the active pill can sit on the left edge while
    // the icon stays centered.
    Box(modifier = Modifier.width(72.dp), contentAlignment = Alignment.Center) {
        // Left pill marker: grows from 0 to 20dp on hover and 36dp when active (web .rail-item::before).
        val pillHeight by animateDpAsState(
            targetValue = if (active) {
                36.dp
            } else if (isHovered) {
                20.dp
            } else {
                0.dp
            },
            animationSpec = NostrordAnimation.indicatorSpec(),
            label = "railPill",
        )
        Box(
            modifier =
            Modifier
                .align(Alignment.CenterStart)
                .width(4.dp)
                .height(pillHeight)
                .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                .background(NostrordColors.TextPrimary),
        )
        Box {
            Surface(
                modifier =
                Modifier
                    .size(48.dp)
                    .clip(shape)
                    .hoverable(interactionSource),
                shape = shape,
                color = NostrordColors.Background,
                onClick = onClick,
            ) {
                OptimizedSmallAvatar(
                    imageUrl = picture,
                    identifier = groupId,
                    displayName = name,
                    size = 48.dp,
                    shape = shape,
                    isGroup = true,
                )
            }
            if (unread > 0) {
                RailBadge(count = unread, modifier = Modifier.align(Alignment.BottomEnd))
            }
        }
    }
}

@Composable
private fun RailBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
        modifier
            .clip(CircleShape)
            .background(NostrordColors.BadgeBackground)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            if (count > 99) "99+" else "$count",
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun HomeHub(
    vm: HomePageViewModel,
    onOpenUser: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var hub by remember { mutableStateOf(0) }
    var friendQuery by remember { mutableStateOf("") }
    val friends by vm.friends.collectAsState()
    val friendsLoading by vm.friendsLoading.collectAsState()
    // No outer verticalScroll: the tabs (and, in the friends branch, the search field) are a fixed
    // header and only the friend list scrolls, as a LazyColumn. The caller bounds this Column's
    // height with Modifier.weight(1f), so the LazyColumn's own weight(1f) is safe (not infinite).
    Column(
        modifier = modifier.fillMaxWidth().padding(8.dp),
    ) {
        AppSegmentedTabs(
            tabs =
            listOf(
                SegmentedTab("Friends", Icons.Default.People),
                SegmentedTab("Saved", Icons.Default.Bookmark),
            ),
            selectedIndex = hub,
            onSelect = { hub = it },
        )
        Spacer(modifier = Modifier.height(12.dp))
        when {
            hub == 1 ->
                Text(
                    "Coming soon",
                    color = NostrordColors.TextMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
            friendsLoading ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    repeat(6) { FriendRowSkeleton() }
                }
            friends.isEmpty() ->
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
                    Text(
                        "You don't follow anyone yet.",
                        color = NostrordColors.TextMuted,
                        fontSize = 13.sp,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    AppButton(
                        text = "Follow people",
                        onClick = { AppModule.requestOnboarding() },
                        variant = AppButtonVariant.Secondary,
                        size = AppButtonSize.Small,
                        icon = Icons.Default.PersonAdd,
                    )
                }
            else ->
                Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    AppSearchField(
                        value = friendQuery,
                        onValueChange = { friendQuery = it },
                        placeholder = "Search friends...",
                        size = InputSize.Compact,
                        // No extra horizontal inset: the field spans the full hub width so it
                        // lines up with the friend rows below (which clip/click edge-to-edge).
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val filtered =
                        remember(friends, friendQuery) {
                            if (friendQuery.isBlank()) {
                                friends
                            } else {
                                friends.filter { friendDisplayName(it).contains(friendQuery, ignoreCase = true) }
                            }
                        }
                    if (filtered.isEmpty()) {
                        Text(
                            "No friends match.",
                            color = NostrordColors.TextMuted,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                    } else {
                        // Virtualized: composes only the visible FriendRows (and defers their avatar
                        // loads) instead of building all N eagerly. key = pubkey keeps each row's
                        // avatar load state stable across search filtering.
                        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            items(filtered, key = { it.pubkey }) { friend ->
                                FriendRow(friend = friend, onClick = { onOpenUser(friend.pubkey) })
                            }
                        }
                    }
                }
        }
    }
}

/** Friend's display name for the sidebar list and its search filter. */
private fun friendDisplayName(friend: Friend): String = friend.metadata?.displayName?.takeIf { it.isNotBlank() }
    ?: friend.metadata?.name?.takeIf { it.isNotBlank() }
    ?: (Nip19.encodeNpub(friend.pubkey).take(12) + "…")

@Composable
private fun FriendRow(
    friend: Friend,
    onClick: () -> Unit,
) {
    val name = friendDisplayName(friend)
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(NostrordShapes.shapeMedium)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OptimizedSmallAvatar(
            imageUrl = friend.metadata?.picture,
            identifier = friend.pubkey,
            displayName = name,
            size = 32.dp,
            shape = CircleShape,
        )
        Text(
            name,
            color = NostrordColors.TextPrimary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Placeholder row shown while the contact list / metadata are still loading. */
@Composable
private fun FriendRowSkeleton() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SkeletonCircle(size = 32.dp)
        SkeletonLine(width = 120.dp, height = 12.dp)
    }
}

@Composable
private fun AccountBar(onOpenSettings: () -> Unit, onViewProfile: (String) -> Unit) {
    val accounts by AppModule.accountStore.accounts.collectAsState()
    val activeId by AppModule.accountStore.activeId.collectAsState()
    val userMetadata by AppModule.nostrRepository.userMetadata.collectAsState()
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

    var menuOpen by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }
    var showAddAccount by remember { mutableStateOf(false) }
    // Account whose npub was just copied from the switcher row (shows the check, resets after 1.2s).
    var copiedNpubId by remember { mutableStateOf<String?>(null) }
    val clipboardWriter = rememberClipboardWriter()
    LaunchedEffect(copiedNpubId) {
        if (copiedNpubId != null) {
            delay(1200)
            copiedNpubId = null
        }
    }

    AddAccountSheet(
        visible = showAddAccount,
        onDismiss = { showAddAccount = false },
        onAdded = { showAddAccount = false },
    )

    if (confirmLogout && active != null) {
        val fallbackAccount = accounts.filter { it.id != active.id }.maxByOrNull { it.addedAt }
        val fallbackLabel = fallbackAccount?.let { accountDisplayLabel(it.label, it.pubkey) }
        // A bare-npub label is shortened so the title doesn't wrap to several lines.
        val signOutLabel = accountDisplayLabel(displayName, active.pubkey)
        ConfirmDialog(
            title = removeAccountDialogTitle(isActive = true, accountLabel = signOutLabel),
            message =
            removeAccountDialogBody(
                isActive = true,
                accountLabel = signOutLabel,
                fallbackLabel = fallbackLabel,
                method = active.authMethod,
            ),
            confirmLabel =
            if (isBusy) removeAccountBusyLabel(isActive = true) else removeAccountConfirmLabel(isActive = true),
            destructive = true,
            confirmEnabled = !isBusy,
            cancelEnabled = !isBusy,
            onConfirm = {
                if (isBusy) return@ConfirmDialog
                isBusy = true
                AppModule.accountManager.removeAccountAsync(active.id) {
                    isBusy = false
                    confirmLogout = false
                }
            },
            onDismiss = { confirmLogout = false },
        )
    }

    val density = LocalDensity.current
    val popupGapPx = with(density) { 8.dp.roundToPx() }
    val menuPosition = remember(popupGapPx) { AboveAnchorStartPositionProvider(popupGapPx) }
    Box {
        // Account switcher popover (prototype AccountMenu): accounts with npub + signer chip,
        // add account, and the sign-out action. A Popup anchored just above the account bar
        // (web .account-pop bottom: 56px) keeps the gap tight on every platform; the Material
        // DropdownMenu's flip-above placement drifted far above the bar on Android.
        if (menuOpen) {
            Popup(
                popupPositionProvider = menuPosition,
                onDismissRequest = { menuOpen = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    modifier = Modifier.width(280.dp),
                    color = NostrordColors.BackgroundFloating,
                    shape = NostrordShapes.shapeMedium,
                    shadowElevation = 8.dp,
                ) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            "ACCOUNTS",
                            color = NostrordColors.TextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                        accounts.forEach { account ->
                            val isActiveAccount = account.id == activeId
                            val m = userMetadata[account.pubkey]
                            val npub = runCatching { Nip19.encodeNpub(account.pubkey) }.getOrDefault("")
                            // Fall back to the npub (not the generic "Account N" label) when the
                            // account has no name metadata.
                            val name =
                                m?.displayName?.takeIf { it.isNotBlank() }
                                    ?: m?.name?.takeIf { it.isNotBlank() }
                                    ?: npub
                            // Row split into two distinct hit targets: the switch area (avatar +
                            // name/npub + signer chip) and a dedicated copy-npub button on the right.
                            // Embedding a tiny copy icon inside the switch row made near-misses fall
                            // through to the switch and change account by accident.
                            Row(
                                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Active-account marker: a full-height accent bar on the left edge
                                // (kept on every row, transparent when inactive, so content stays aligned).
                                Box(
                                    modifier =
                                    Modifier
                                        .width(3.dp)
                                        .fillMaxHeight()
                                        .background(if (isActiveAccount) NostrordColors.Primary else Color.Transparent),
                                )
                                Row(
                                    modifier =
                                    Modifier
                                        .weight(1f)
                                        .clickable {
                                            menuOpen = false
                                            if (!isActiveAccount) {
                                                AppModule.accountManager.switchAccountAsync(account.id)
                                            }
                                        }
                                        .padding(start = 9.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OptimizedSmallAvatar(
                                        imageUrl = m?.picture,
                                        identifier = account.pubkey,
                                        displayName = name,
                                        size = 36.dp,
                                        shape = CircleShape,
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            name,
                                            color = NostrordColors.TextPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(modifier = Modifier.height(3.dp))
                                        // Signer chip lives on the second meta line (the npub moved up
                                        // to the name slot as its fallback).
                                        Surface(
                                            shape = NostrordShapes.shapeSmall,
                                            color = NostrordColors.BackgroundDark,
                                        ) {
                                            Text(
                                                signerLabel(account),
                                                color = NostrordColors.TextMuted,
                                                fontSize = 10.sp,
                                                lineHeight = 12.sp,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                            )
                                        }
                                    }
                                }
                                val npubCopied = copiedNpubId == account.id
                                Box(
                                    modifier =
                                    Modifier
                                        .padding(end = 4.dp)
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .clickable {
                                            clipboardWriter(npub)
                                            copiedNpubId = account.id
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = if (npubCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                                        contentDescription = "Copy npub",
                                        tint = if (npubCopied) NostrordColors.Success else NostrordColors.TextMuted,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = NostrordColors.Divider)
                        // Compact action rows (web .account-pop-action: 8/12 padding). DropdownMenuItem
                        // forces a 48dp min height, which made these taller than the web equivalent.
                        active?.let { acct ->
                            AccountPopAction(
                                icon = Icons.Default.Person,
                                label = "View profile",
                                tint = NostrordColors.TextSecondary,
                                onClick = {
                                    menuOpen = false
                                    onViewProfile(acct.pubkey)
                                },
                            )
                        }
                        AccountPopAction(
                            icon = Icons.Default.Add,
                            label = "Add account",
                            tint = NostrordColors.TextSecondary,
                            onClick = {
                                menuOpen = false
                                showAddAccount = true
                            },
                        )
                        AccountPopAction(
                            icon = Icons.AutoMirrored.Filled.Logout,
                            label = logoutLabel,
                            tint = NostrordColors.Error,
                            onClick = {
                                menuOpen = false
                                confirmLogout = true
                            },
                        )
                    }
                }
            }
        }

        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(NostrordColors.BackgroundFloating)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val triggerInteraction = remember { MutableInteractionSource() }
            val triggerHovered by triggerInteraction.collectIsHoveredAsState()
            Row(
                modifier =
                Modifier
                    .weight(1f)
                    .clip(NostrordShapes.shapeSmall)
                    .background(if (triggerHovered) NostrordColors.HoverBackground else Color.Transparent)
                    .hoverable(triggerInteraction)
                    .clickable { menuOpen = !menuOpen }
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OptimizedSmallAvatar(
                    imageUrl = meta?.picture,
                    identifier = active?.pubkey ?: "",
                    displayName = displayName,
                    size = 32.dp,
                    shape = CircleShape,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        displayName,
                        color = NostrordColors.TextPrimary,
                        fontSize = 13.sp,
                        // Tight line height (web .account-bar-meta line-height: 1.25) so the name
                        // and status hug each other; the font's default leading is what was
                        // inflating the gap and pushing the trigger taller than the avatar.
                        lineHeight = 16.25.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "online",
                        color = NostrordColors.Success,
                        fontSize = 11.sp,
                        lineHeight = 13.75.sp,
                    )
                }
                Icon(
                    imageVector = if (menuOpen) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    contentDescription = "Switch account",
                    tint = NostrordColors.TextMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = NostrordColors.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Which step of the rail "+" add-group flow is open. */
private enum class AddGroupStep { CHOOSER, CREATE, JOIN }

/**
 * A short human label for a route, for the desktop back arrow's history dropdown.
 * [groupName] resolves a joined group's display name; falls back to the raw id.
 */
private fun navEntryLabel(route: HashRoute?, groupName: (String) -> String?): String = when (route) {
    null -> "Home"
    is HomeRoute -> when (route.tab) {
        HomeTab.Groups -> "Home"
        HomeTab.Friends -> "Friends"
        HomeTab.Recommended -> "Recommended"
        HomeTab.People -> "People"
    }
    is GroupRoute -> groupName(route.groupId) ?: route.groupId
    is RelayRoute -> route.relayUrl.removePrefix("wss://").removePrefix("ws://")
    is UserRoute -> runCatching { Nip19.encodeNpub(route.pubkey).take(12) + "…" }.getOrDefault("Profile")
    is DmRoute -> if (route.pubkey == null) "Direct messages" else "Direct message"
    is NotificationsRoute -> "Notifications"
    is SettingsRoute -> "Settings"
}

/**
 * Positions the account-switcher popup just above the account bar, left-aligned with it
 * (web .account-pop: bottom 56px, left 8px). Anchor bounds are the account bar; the popup
 * sits its full height above the bar's top with a small gap.
 */
private class AboveAnchorStartPositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left
        val y = anchorBounds.top - popupContentSize.height - gapPx
        return IntOffset(x, y.coerceAtLeast(0))
    }
}

/** Compact action row for the account switcher (web .account-pop-action parity). */
@Composable
private fun AccountPopAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, color = tint, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
