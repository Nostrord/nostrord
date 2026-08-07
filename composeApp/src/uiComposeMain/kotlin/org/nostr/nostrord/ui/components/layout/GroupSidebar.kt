package org.nostr.nostrord.ui.components.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.components.avatars.OptimizedSmallAvatar
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.navigation.GroupView
import org.nostr.nostrord.ui.screens.avspace.LiveSpaceBarViewModel
import org.nostr.nostrord.ui.screens.group.GroupViewModel
import org.nostr.nostrord.ui.screens.group.channelTree
import org.nostr.nostrord.ui.screens.group.components.CreateGroupModal
import org.nostr.nostrord.ui.screens.group.components.ManageGroupModal
import org.nostr.nostrord.ui.screens.group.components.ManageTab
import org.nostr.nostrord.ui.screens.group.components.MembersModal
import org.nostr.nostrord.ui.screens.group.isLockedChannel
import org.nostr.nostrord.ui.screens.group.moveChannelBefore
import org.nostr.nostrord.ui.screens.group.rootGroupId
import org.nostr.nostrord.ui.screens.home.RelayHeaderIcon
import org.nostr.nostrord.ui.theme.AvatarGradients
import org.nostr.nostrord.ui.theme.Hsl
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.ui.util.grabPointerIcon
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.normalizeRelayUrl
import kotlin.math.roundToInt

/**
 * Second column when a group is open (prototype ChannelsSidebar group mode): the
 * gradient banner (ROOT group identity, Discord's "server") and below it the
 * root rows (General/Threads/Members/Manage all act on the ROOT group) plus the
 * channel list (the root's subgroup subtree). Opening a channel keeps this sidebar
 * anchored on the root; the channel itself is managed only via the header cog.
 * Mirrors the web web/GroupSidebar.
 */
@Composable
fun GroupSidebar(
    route: GroupRoute,
    onNavigateGroup: (GroupRoute) -> Unit,
    onNavigateRelay: (String) -> Unit = {},
    onNavigateHome: () -> Unit = {},
) {
    val vm = viewModel(key = "${route.relayUrl}|${route.groupId}") {
        GroupViewModel(AppModule.nostrRepository, route.groupId, route.relayUrl)
    }
    val groupsByRelay by vm.groupsByRelay.collectAsState()
    val childrenByParent by vm.childrenByParent.collectAsState()
    val groupMembers by vm.groupMembers.collectAsState()
    val groupAdmins by vm.groupAdmins.collectAsState()
    val joinedGroupsByRelay by vm.joinedGroupsByRelay.collectAsState()
    val unreadCounts by AppModule.nostrRepository.unreadCounts.collectAsState()
    val muteState by AppModule.notificationSettings.muteState.collectAsState()
    val kind10009Relays by AppModule.nostrRepository.kind10009Relays.collectAsState()
    val relayMetadata by vm.relayMetadata.collectAsState()

    val relayGroups = groupsByRelay[route.relayUrl].orEmpty()
    val metaById = relayGroups.associateBy { it.id }
    val currentUserPubkey = remember { vm.getPublicKey() }
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
    val spaceVm = viewModel(key = "livespacebar-$rootId") {
        LiveSpaceBarViewModel(AppModule.nostrRepository, rootId, currentUserPubkey, AppModule.avSpaceHost)
    }
    val liveCount = spaceVm.participants.collectAsState().value.size
    // Optimistic channel order while a drag-reorder kind:9002 round-trips; cleared once
    // the relay's kind:39000 echoes it (or on publish failure).
    var orderOverride by remember(rootId) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(rootMeta?.children, orderOverride) {
        if (orderOverride != null && rootMeta?.children == orderOverride) orderOverride = null
    }
    val effectiveMetaById =
        orderOverride?.let { order -> rootMeta?.let { metaById + (rootId to it.copy(children = order)) } } ?: metaById
    // Subgroup channels only: the root's own chat is the "General" row above the list,
    // so listing the root again would duplicate the banner identity.
    val channels = channelTree(rootId, childrenByParent, effectiveMetaById).drop(1)

    // Drag reorder (prototype ChannelsSidebar): dragged row dims, the row the pointer is
    // over shows a top drop line, drop inserts the dragged channel before it. The target
    // row is derived from the accumulated vertical offset over the uniform row height.
    val scope = rememberCoroutineScope()
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragDelta by remember { mutableStateOf(0f) }
    var rowHeightPx by remember { mutableStateOf(0) }
    val dragFromIndex = channels.indexOfFirst { it.id == dragId }
    // Index channels.size is the end slot (drop after the last row); a row index
    // inserts BEFORE that row.
    val dragOverIndex =
        if (dragId != null && dragFromIndex >= 0 && rowHeightPx > 0) {
            (dragFromIndex + (dragDelta / rowHeightPx).roundToInt()).coerceIn(0, channels.size)
        } else {
            -1
        }

    fun endDrag(commit: Boolean) {
        val id = dragId
        val over = dragOverIndex
        dragId = null
        dragDelta = 0f
        if (!commit || id == null || over < 0 || over == dragFromIndex) return
        val currentOrder = channels.filter { it.depth == 1 }.map { it.id }
        val newOrder = moveChannelBefore(currentOrder, id, channels.getOrNull(over)?.id)
        if (newOrder == currentOrder) return
        orderOverride = newOrder
        scope.launch {
            val r = AppModule.nostrRepository.reorderChildren(rootId, newOrder)
            if (r is Result.Error) orderOverride = null
        }
    }
    val joinedHere = joinedGroupsByRelay[route.relayUrl.normalizeRelayUrl()].orEmpty()
    // Only relays that advertise nip29:{subgroups:true} in their NIP-11 host subgroups.
    val supportsSubgroups =
        (relayMetadata[route.relayUrl] ?: relayMetadata[route.relayUrl.normalizeRelayUrl()])
            ?.supportsSubgroups == true

    var showMembers by remember { mutableStateOf(false) }
    var showCreateSubgroup by remember { mutableStateOf(false) }
    var showManage by remember { mutableStateOf(false) }
    // Tab the Manage modal opens on: the Members row jumps admins straight to "Members".
    var manageTab by remember { mutableStateOf(ManageTab.Info) }

    val relayHost = route.relayUrl.removePrefix("wss://").removePrefix("ws://").trimEnd('/')
    val relayIconUrl =
        (relayMetadata[route.relayUrl] ?: relayMetadata[route.relayUrl.normalizeRelayUrl()])?.icon

    Column(modifier = Modifier.fillMaxSize()) {
        GroupBanner(
            seed = rootId,
            name = rootName,
            picture = rootMeta?.picture,
            relayUrl = route.relayUrl,
            relayHost = relayHost,
            relayIconUrl = relayIconUrl,
            onRelayClick = { onNavigateRelay(route.relayUrl) },
        )
        Column(
            modifier =
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.sm, vertical = Spacing.md),
        ) {
            // The root group's own chat (Discord's #general); subgroup channels are listed below.
            // "General" only makes sense next to a channel list; without subgroup support it's "Chat".
            SidebarRow(
                icon = Icons.AutoMirrored.Filled.Chat,
                label = if (supportsSubgroups) "General" else "Chat",
                active = route.view == GroupView.Chat && route.groupId == rootId,
            ) { onNavigateGroup(GroupRoute(route.relayUrl, rootId)) }
            SidebarRow(
                icon = Icons.Default.Forum,
                label = "Threads",
                active = route.view == GroupView.Threads && route.groupId == rootId,
            ) { onNavigateGroup(GroupRoute(route.relayUrl, rootId, view = GroupView.Threads)) }
            // Voice room row (prototype ChannelsSidebar): shown when the group has a LiveKit
            // space, with the live participant count. The room is mounted once over the frame
            // (AvSpaceRoomHost), so this only asks the host to show it.
            if (rootMeta?.hasLiveKit == true) {
                SidebarRow(
                    icon = Icons.Default.Mic,
                    label = if (liveCount > 0) "Voice room · $liveCount" else "Voice room",
                ) { AppModule.avSpaceHost.show(rootId, AppModule.nostrRepository.activePubkey.value) }
            }
            SidebarRow(
                icon = Icons.Default.People,
                label = if (memberCount > 0) "Members · $memberCount" else "Members",
            ) {
                // Admins manage members in the Manage modal; everyone else sees the roster.
                if (isRootAdmin) {
                    manageTab = ManageTab.Members
                    showManage = true
                } else {
                    showMembers = true
                }
            }
            if (isRootAdmin) {
                SidebarRow(icon = Icons.Default.Settings, label = "Manage group") {
                    manageTab = ManageTab.Info
                    showManage = true
                }
            }

            // Channels (Discord model): the root's subgroup subtree, depth-first with
            // indentation. Hidden on relays that can't host subgroups, and for non-admins
            // when there are no channels yet (admins keep the header for its add button).
            if (supportsSubgroups && (channels.isNotEmpty() || isRootAdmin)) {
                Spacer(modifier = Modifier.height(Spacing.md))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "CHANNELS · ${channels.size}",
                        color = NostrordColors.TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (isRootAdmin) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add channel",
                            tint = NostrordColors.TextMuted,
                            modifier =
                            Modifier
                                .size(16.dp)
                                .clip(NostrordShapes.shapeSmall)
                                .clickable { showCreateSubgroup = true },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(Spacing.xs))
                channels.forEachIndexed { index, entry ->
                    val channel = metaById[entry.id]
                    ChannelRow(
                        groupId = entry.id,
                        name = channel?.name ?: entry.id,
                        picture = channel?.picture,
                        // First-level channels sit flush; only nesting below them indents.
                        depth = entry.depth - 1,
                        locked = isLockedChannel(channel, isJoined = entry.id in joinedHere),
                        unread = unreadCounts[entry.id] ?: 0,
                        // Muting the root silences the whole tree, so a channel inside a
                        // muted group reads as muted without an override of its own.
                        muted = muteState.isMuted(entry.id) || muteState.isMuted(rootId),
                        onToggleMute = { AppModule.notificationSettings.toggleMute(entry.id) },
                        active = route.groupId == entry.id,
                        // Only first-level channels reorder: their order is the root's child
                        // tag positions; nested foreign rows just render.
                        draggable = isRootAdmin && entry.depth == 1,
                        dragging = dragId == entry.id,
                        dropIndicator = index == dragOverIndex && index != dragFromIndex,
                        onDragStart = {
                            dragId = entry.id
                            dragDelta = 0f
                        },
                        onDrag = { dy -> dragDelta += dy },
                        onDragEnd = { endDrag(commit = true) },
                        onDragCancel = { endDrag(commit = false) },
                        onHeight = { rowHeightPx = it },
                    ) { onNavigateGroup(GroupRoute(route.relayUrl, entry.id)) }
                }
                // End drop indicator: the slot after the last row.
                if (dragId != null && dragOverIndex == channels.size) {
                    Box(
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 1.dp)
                            .height(2.dp)
                            .clip(NostrordShapes.shapeSmall)
                            .background(NostrordColors.Primary),
                    )
                }
            }
        }
    }

    if (showMembers) {
        MembersModal(
            groupId = rootId,
            onDismiss = { showMembers = false },
            relayUrl = route.relayUrl,
        )
    }
    if (showManage) {
        ManageGroupModal(
            groupId = rootId,
            currentMetadata = rootMeta,
            relayUrl = route.relayUrl,
            onDismiss = { showManage = false },
            onDeleted = {
                showManage = false
                // The sidebar only manages the root; deleting it goes home.
                onNavigateHome()
            },
            initialTab = manageTab,
            supportsSubgroups = supportsSubgroups,
            onOpenGroup = { id ->
                showManage = false
                onNavigateGroup(GroupRoute(route.relayUrl, id))
            },
        )
    }
    if (showCreateSubgroup) {
        CreateGroupModal(
            // The channel must land on the parent's relay, not whatever relay is focused.
            currentRelayUrl = route.relayUrl,
            userRelays = kind10009Relays,
            parentGroupId = rootId,
            onDismiss = { showCreateSubgroup = false },
            onGroupCreated = { relayUrl, newId, _ ->
                showCreateSubgroup = false
                onNavigateGroup(GroupRoute(relayUrl, newId))
            },
        )
    }
}

/**
 * Gradient identity banner (prototype GroupBanner): same hue pair as the avatar, darkened.
 * Mirrors the web `.group-side-banner`: group avatar + name, with a tappable relay line
 * (icon + host) below the name that opens the relay page. Only the relay line is clickable;
 * the banner itself is not (parity with web).
 */
@Composable
private fun GroupBanner(
    seed: String,
    name: String,
    picture: String?,
    relayUrl: String,
    relayHost: String,
    relayIconUrl: String?,
    onRelayClick: () -> Unit,
) {
    val banner = remember(seed) { AvatarGradients.banner(seed) }
    // White text over a colored gradient needs the same drop shadow the web banner uses.
    val textShadow = Shadow(color = Color.Black.copy(alpha = 0.4f), offset = Offset(0f, 1f), blurRadius = 2f)
    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .height(84.dp)
            .drawBehind {
                // 135deg diagonal, top-left to bottom-right (CSS linear-gradient(135deg, …)).
                drawRect(
                    brush =
                    Brush.linearGradient(
                        colors = listOf(banner.start.toColor(), banner.end.toColor()),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    ),
                )
                // Bottom scrim for legible white text.
                drawRect(
                    brush =
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                        startY = size.height - 56.dp.toPx(),
                        endY = size.height,
                    ),
                    topLeft = Offset(0f, size.height - 56.dp.toPx()),
                )
            },
        contentAlignment = Alignment.BottomStart,
    ) {
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = Spacing.lg, end = Spacing.lg, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            OptimizedSmallAvatar(
                imageUrl = picture,
                identifier = seed,
                displayName = name,
                size = 24.dp,
                // Web group avatars are rounded squares at 25% radius (.avatar-stack.group).
                shape = RoundedCornerShape(percent = 25),
                isGroup = true,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    name,
                    color = Color.White,
                    fontSize = 15.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = LocalTextStyle.current.copy(shadow = textShadow),
                )
                if (relayHost.isNotBlank()) {
                    Row(
                        modifier =
                        Modifier
                            .clip(NostrordShapes.shapeSmall)
                            .clickable(onClick = onRelayClick)
                            .padding(end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        RelayHeaderIcon(
                            relayUrl = relayUrl,
                            iconUrl = relayIconUrl,
                            label = relayHost,
                            size = 13.dp,
                            // Web .group-side-banner-relay-icon: small rounded square, not a circle.
                            cornerRadius = 3.dp,
                        )
                        Text(
                            relayHost,
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 11.sp,
                            lineHeight = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = LocalTextStyle.current.copy(shadow = textShadow),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarRow(
    icon: ImageVector,
    label: String,
    muted: Boolean = false,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val highlighted = isHovered || active
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(NostrordShapes.shapeMedium)
            .background(if (highlighted) NostrordColors.HoverBackground else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs + Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (highlighted) {
                NostrordColors.TextPrimary
            } else if (muted) {
                NostrordColors.TextMuted
            } else {
                NostrordColors.TextSecondary
            },
            modifier = Modifier.size(17.dp),
        )
        Text(
            label,
            color =
            when {
                highlighted -> NostrordColors.TextPrimary
                muted -> NostrordColors.TextMuted
                else -> NostrordColors.TextSecondary
            },
            fontSize = if (muted) 12.sp else 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChannelRow(
    groupId: String,
    name: String,
    picture: String?,
    depth: Int,
    locked: Boolean,
    unread: Int,
    muted: Boolean = false,
    onToggleMute: () -> Unit = {},
    active: Boolean,
    draggable: Boolean = false,
    dragging: Boolean = false,
    dropIndicator: Boolean = false,
    onDragStart: () -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
    onHeight: (Int) -> Unit = {},
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val highlighted = isHovered || active
    var menuOffset by remember { mutableStateOf<Offset?>(null) }
    val indicatorColor = NostrordColors.Primary
    // pointerInput's coroutine only ever sees the lambdas captured when it launched,
    // but the drop math lives in callbacks the sidebar recreates every recomposition
    // (they close over the current drag indexes). Route through rememberUpdatedState
    // so the gesture always invokes the latest ones — without this the drop commits
    // against the stale first-composition state and never reorders.
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            // Nesting indent, capped so deep foreign trees never crush the label.
            .padding(start = Spacing.md * minOf(depth, 3))
            .onSizeChanged { onHeight(it.height) }
            .alpha(if (dragging) 0.4f else 1f)
            // Drop target line at the top edge (drop = insert before this row).
            .drawBehind {
                if (dropIndicator) {
                    drawRoundRect(
                        color = indicatorColor,
                        size = Size(size.width, 2.dp.toPx()),
                        cornerRadius = CornerRadius(1.dp.toPx()),
                    )
                }
            }
            .clip(NostrordShapes.shapeMedium)
            .background(if (highlighted) NostrordColors.HoverBackground else Color.Transparent)
            .hoverable(interactionSource)
            .secondaryClickMenu(groupId) { menuOffset = it }
            // Long-press is free on a channel row (reorder has its own drag handle), so
            // touch reaches the menu here even though the rail chip can't offer it.
            .combinedClickable(onClick = onClick, onLongClick = { menuOffset = Offset.Zero })
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs + Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs + Spacing.xxs),
    ) {
        GroupContextMenu(
            visible = menuOffset != null,
            muted = muted,
            onDismiss = { menuOffset = null },
            onToggleMute = onToggleMute,
            anchorOffsetPx = menuOffset,
        )
        if (draggable) {
            Icon(
                imageVector = Icons.Default.DragIndicator,
                contentDescription = "Drag to reorder",
                tint = NostrordColors.TextMuted,
                modifier =
                Modifier
                    .size(14.dp)
                    .alpha(if (isHovered || dragging) 1f else 0.6f)
                    .pointerHoverIcon(grabPointerIcon, overrideDescendants = true)
                    .pointerInput(groupId) {
                        detectDragGestures(
                            onDragStart = { currentOnDragStart() },
                            onDrag = { change, amount ->
                                change.consume()
                                currentOnDrag(amount.y)
                            },
                            onDragEnd = { currentOnDragEnd() },
                            onDragCancel = { currentOnDragCancel() },
                        )
                    },
            )
        }
        OptimizedSmallAvatar(
            imageUrl = picture,
            identifier = groupId,
            displayName = name,
            size = 20.dp,
            shape = NostrordShapes.shapeSmall,
            isGroup = true,
        )
        Text(
            name,
            color = when {
                muted -> NostrordColors.TextMuted
                highlighted -> NostrordColors.TextPrimary
                else -> NostrordColors.TextSecondary
            },
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (locked) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Members only",
                tint = NostrordColors.TextMuted,
                modifier = Modifier.size(12.dp),
            )
        }
        if (muted) {
            Icon(
                imageVector = Icons.Outlined.NotificationsOff,
                contentDescription = "Muted",
                tint = NostrordColors.TextMuted,
                modifier = Modifier.size(12.dp),
            )
        }
        if (unread > 0) {
            if (muted) {
                // Dot, not a count: the number is exactly the noise muting removes.
                Box(
                    modifier =
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(NostrordColors.TextMuted),
                )
            } else {
                Box(
                    modifier =
                    Modifier
                        .clip(CircleShape)
                        .background(NostrordColors.BadgeBackground)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(
                        if (unread > 99) "99+" else "$unread",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

private fun Hsl.toColor(): Color = Color.hsl(hue.toFloat(), saturation / 100f, lightness / 100f)
