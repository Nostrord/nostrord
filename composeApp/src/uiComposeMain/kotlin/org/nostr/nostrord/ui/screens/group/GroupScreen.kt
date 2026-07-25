package org.nostr.nostrord.ui.screens.group

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.network.managers.GroupLoadingState
import org.nostr.nostrord.network.upload.UploadResult
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.components.ConfirmDialog
import org.nostr.nostrord.ui.components.avatars.OptimizedSmallAvatar
import org.nostr.nostrord.ui.components.chat.LocalAnimatedImageHidden
import org.nostr.nostrord.ui.screens.group.components.GroupInfoModal
import org.nostr.nostrord.ui.screens.group.components.InviteCode
import org.nostr.nostrord.ui.screens.group.components.InviteCodesModal
import org.nostr.nostrord.ui.screens.group.components.ManageGroupModal
import org.nostr.nostrord.ui.screens.group.components.ManageTab
import org.nostr.nostrord.ui.screens.group.components.OrphanedGroupContent
import org.nostr.nostrord.ui.screens.group.components.UserProfileModal
import org.nostr.nostrord.ui.screens.group.model.GroupInfo
import org.nostr.nostrord.ui.screens.group.model.MemberInfo
import org.nostr.nostrord.ui.screens.group.model.buildChatItems
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.epochSeconds
import org.nostr.nostrord.utils.normalizeRelayUrl
import org.nostr.nostrord.utils.shortNpub

// Unit separator — safe field delimiter for encoding GroupInfo into the platform-agnostic
// String values the shared MessageDraftStore holds. It cannot appear in ids, names or URLs.
private const val DRAFT_FIELD_SEP = "\u001f"

private fun encodeGroupMentions(groupMentions: Map<String, GroupInfo>): Map<String, String> = groupMentions.mapValues { (_, g) ->
    listOf(g.id, g.name, g.picture ?: "", g.relay).joinToString(DRAFT_FIELD_SEP)
}

private fun decodeGroupMentions(encoded: Map<String, String>): Map<String, GroupInfo> = encoded.mapNotNull { (name, value) ->
    val parts = value.split(DRAFT_FIELD_SEP)
    if (parts.size < 4) return@mapNotNull null
    name to GroupInfo(id = parts[0], name = parts[1], picture = parts[2].ifEmpty { null }, relay = parts[3])
}.toMap()

@Composable
fun GroupScreen(
    groupId: String,
    groupName: String?,
    // Relay the route carried; scopes the VM (and keys it) so same-id groups on two
    // relays get separate screens instead of sharing one.
    relayUrl: String? = null,
    onNavigateHome: () -> Unit = {},
    onNavigateToGroup: (groupId: String, groupName: String?, relayUrl: String?, messageId: String?) -> Unit = { _, _, _, _ -> },
    onOpenRelay: (relayUrl: String) -> Unit = {},
    showServerRail: Boolean = true, // When false, server rail is handled by parent shell
    onOpenDrawer: () -> Unit = {},
    forceDesktop: Boolean = false,
    pendingInviteCode: String? = null,
    onInviteCodeConsumed: () -> Unit = {},
    targetMessageId: String? = null,
    onTargetMessageConsumed: () -> Unit = {},
) {
    val vm = viewModel(key = relayUrl?.let { "$it|$groupId" } ?: groupId) {
        GroupViewModel(AppModule.nostrRepository, groupId, relayUrl)
    }

    var selectedChannel by remember { mutableStateOf("general") }

    val allMessages by vm.messages.collectAsState()

    // derivedStateOf: only recomposes downstream when the filtered result actually changes,
    // not when unrelated state (reactions, metadata) triggers a recomposition pass.
    val messages by remember(groupId) {
        derivedStateOf {
            val allGroupMessages = allMessages[groupId] ?: emptyList()
            if (selectedChannel == "general") {
                allGroupMessages.filter { message ->
                    !message.tags.any { it.size >= 2 && it[0] == "channel" }
                }
            } else {
                allGroupMessages.filter { message ->
                    message.tags.any { it.size >= 2 && it[0] == "channel" && it[1] == selectedChannel }
                }
            }
        }
    }

    val isSending by vm.isSending.collectAsState()
    val friends by vm.friends.collectAsState()
    val sendError by vm.sendError.collectAsState()
    val deleteMessageError by vm.deleteMessageError.collectAsState()
    val reactionError by vm.reactionError.collectAsState()
    val joinError by vm.joinError.collectAsState()
    val moderationError by vm.moderationError.collectAsState()
    val moderationBusy by vm.moderationBusy.collectAsState()
    val connectionState by vm.connectionState.collectAsState()
    // Cross-relay membership view. `vm.joinedGroups` is filtered by
    // _currentRelayUrl, which is null/stale during fresh-login bootstrap (kind:10009
    // arrives before the primary relay is set, especially in NIP-46 flows where
    // the remote signer dance delays everything). Flattening joinedGroupsByRelay
    // gives a stable "any relay has me as a member" view that doesn't race.
    val joinedGroupsByRelay by vm.joinedGroupsByRelay.collectAsState()
    val joinedGroups =
        remember(joinedGroupsByRelay) {
            joinedGroupsByRelay.values.flatten().toSet()
        }
    val groups by vm.groups.collectAsState()
    val relayMetadata by vm.relayMetadata.collectAsState()
    val userMetadata by vm.userMetadata.collectAsState()
    // The add-member DM default: whether the target's kind:10009 pins a group on this
    // group's host relay (in-app notification reaches them there, DM redundant).
    val userGroupLists by vm.userGroupLists.collectAsState()
    val allGroupsByRelay by vm.groupsByRelay.collectAsState()
    val allReactions by vm.reactions.collectAsState()
    val pendingReactions by vm.pendingReactions.collectAsState()
    val messageStatus by vm.messageStatus.collectAsState()
    val allGroupMembers by vm.groupMembers.collectAsState()
    val allGroupAdmins by vm.groupAdmins.collectAsState()
    val loadingMembersSet by vm.loadingMembers.collectAsState()
    val currentRelayUrl by vm.currentRelayUrl.collectAsState()
    val allRestrictedGroups by vm.restrictedGroups.collectAsState()
    // Orphaned: pinned in kind:10009 but no kind:39000 from the relay (deleted / gone). Shows a
    // "no longer available" panel instead of the perpetual loading skeletons.
    val isOrphaned by vm.isOrphaned.collectAsState()
    val childrenByParent by vm.childrenByParent.collectAsState()
    // Subgroup UI is gated on the relay advertising nip29:{subgroups:true} in its NIP-11 (mirrors GroupSidebar).
    val screenRelay = relayUrl ?: currentRelayUrl
    val supportsSubgroups =
        (relayMetadata[screenRelay] ?: relayMetadata[screenRelay.normalizeRelayUrl()])?.supportsSubgroups == true
    val currentUserPubkey = vm.getPublicKey()

    // This relay's kind:39000 first: a same-id group on another relay must not paint this
    // screen's name/about. The flat list stays the fallback while the route has no relay.
    val currentGroupMetadata =
        remember(groups, allGroupsByRelay, groupId, relayUrl) {
            relayUrl?.let { r ->
                (allGroupsByRelay[r] ?: allGroupsByRelay.entries.firstOrNull { it.key.normalizeRelayUrl() == r.normalizeRelayUrl() }?.value)
                    ?.firstOrNull { it.id == groupId }
            } ?: groups.find { it.id == groupId }
        }

    // %group mention candidates: only joined + friends' groups (the new discovery),
    // not every group the relay served.
    val mentionableGroups by vm.mentionableGroups.collectAsState()
    val availableGroups =
        remember(mentionableGroups) {
            mentionableGroups
                .map { mg ->
                    GroupInfo(
                        id = mg.meta.id,
                        name = mg.meta.name ?: mg.meta.id,
                        picture = mg.meta.picture,
                        relay = mg.relayUrl,
                    )
                }.distinctBy { it.id }
        }

    val isGroupRestricted = allRestrictedGroups.containsKey(groupId)

    val parentGroupName =
        remember(groups, currentGroupMetadata?.parent, childrenByParent, groupId) {
            val parentId = currentGroupMetadata?.parent ?: return@remember null
            // Only show breadcrumb when the parent-child relationship is confirmed
            val isConfirmed = childrenByParent[parentId]?.contains(groupId) == true
            if (!isConfirmed) return@remember null
            groups.find { it.id == parentId }?.name ?: parentId.take(8)
        }

    val isAdmin =
        remember(allGroupAdmins, groupId, currentUserPubkey) {
            currentUserPubkey != null && currentUserPubkey in (allGroupAdmins[groupId] ?: emptyList())
        }

    val isLoadingMoreMap by vm.isLoadingMore.collectAsState()
    val hasMoreMessagesMap by vm.hasMoreMessages.collectAsState()
    val awaitingAuthReadSet by vm.groupsAwaitingAuthRead.collectAsState()
    val groupStatesMap by vm.groupStates.collectAsState()
    val isLoadingMore = isLoadingMoreMap[groupId] ?: false
    val hasMoreMessages = hasMoreMessagesMap[groupId] ?: true
    val isLoadStalled = groupStatesMap[groupId] is GroupLoadingState.Stalled

    // messageInput is only the failure-restore channel into the composer (the live text
    // lives in MessageInput); keyed by groupId so a restore value can't leak across groups.
    var messageInput by remember(groupId) { mutableStateOf("") }
    // Mention maps are part of the per-group draft: seed from the store on group change so
    // an @user / %group typed before switching is restored (and doesn't bleed into the next
    // group, which the un-keyed remember used to do).
    var mentions by remember(groupId) { mutableStateOf(AppModule.messageDraftStore.get(groupId).mentions) }
    var groupMentions by remember(groupId) {
        mutableStateOf(decodeGroupMentions(AppModule.messageDraftStore.get(groupId).groupMentions))
    }
    var replyingToMessage by remember(groupId) { mutableStateOf<NostrGroupClient.NostrMessage?>(null) }
    // Bumped on EVERY reply tap so re-replying to the same message still refocuses the
    // composer and re-summons the keyboard (the message alone doesn't change then).
    var replyNonce by remember(groupId) { mutableStateOf(0) }
    var pendingUploads by remember { mutableStateOf<List<UploadResult>>(emptyList()) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    var showGroupInfoModal by remember { mutableStateOf(false) }
    var showEditGroupModal by remember { mutableStateOf(false) }
    var showDeleteGroupDialog by remember { mutableStateOf(false) }
    var deleteInProgress by remember { mutableStateOf(false) }
    var deleteErrorMessage by remember { mutableStateOf<String?>(null) }
    var messageToDelete by remember { mutableStateOf<NostrGroupClient.NostrMessage?>(null) }
    var selectedUserPubkey by remember { mutableStateOf<String?>(null) }
    var showMemberSheet by remember { mutableStateOf(false) }

    // Desktop members column visibility, toggled from the header (prototype behavior).
    var membersVisible by remember { mutableStateOf(true) }
    var memberToRemove by remember { mutableStateOf<MemberInfo?>(null) }
    var memberToChangeRole by remember { mutableStateOf<MemberInfo?>(null) }
    var showJoinRequestsModal by remember { mutableStateOf(false) }
    var showMemberManagementModal by remember { mutableStateOf(false) }
    var showInviteCodesModal by remember { mutableStateOf(false) }
    var inputOverlayOpen by remember { mutableStateOf(false) }
    var createdInviteCode by remember { mutableStateOf<String?>(null) }
    var resolvedRequestPubkeys by remember(groupId) { mutableStateOf(emptySet<String>()) }
    val isJoined =
        remember(joinedGroups, groupId) {
            joinedGroups.contains(groupId)
        }

    val initialInviteCode = remember { pendingInviteCode }
    val isConnected = connectionState is ConnectionManager.ConnectionState.Connected
    val effectiveInviteCode = if (isConnected) initialInviteCode else null

    var autoJoinFired by remember { mutableStateOf(false) }

    // Auto-join waits for relay connection before firing
    LaunchedEffect(pendingInviteCode, isConnected) {
        val code = pendingInviteCode ?: return@LaunchedEffect
        if (!isConnected) return@LaunchedEffect
        onInviteCodeConsumed()
        if (!autoJoinFired) {
            autoJoinFired = true
            vm.joinGroup(code)
        }
    }

    val memberPubkeys by remember(groupId) {
        derivedStateOf {
            val k39002 = allGroupMembers[groupId] ?: emptyList()
            if (k39002.isNotEmpty()) {
                k39002
            } else {
                (allMessages[groupId] ?: emptyList()).map { it.pubkey }.distinct()
            }
        }
    }

    val adminPubkeys by remember(groupId) {
        derivedStateOf { allGroupAdmins[groupId] ?: emptyList() }
    }

    val groupMembers by remember(groupId) {
        derivedStateOf {
            memberPubkeys
                .map { pubkey ->
                    val metadata = userMetadata[pubkey]
                    MemberInfo(
                        pubkey = pubkey,
                        displayName =
                        metadata?.displayName
                            ?: metadata?.name
                            ?: shortNpub(pubkey),
                        picture = metadata?.picture,
                        isAdmin = pubkey in adminPubkeys,
                    )
                }.sortedWith(compareByDescending<MemberInfo> { it.isAdmin }.thenBy { it.displayName.lowercase() })
        }
    }

    // Shares the Manage > Requests logic (pendingJoinRequests) so the header badge and the list
    // never disagree; resolvedRequestPubkeys drops requests approved/rejected this session before
    // the relay echo lands. Open groups count too: some relays leave a kind:9021 pending when a
    // member leaves and asks to rejoin, and the admin clears it from Manage > Requests.
    val pendingRequests by remember(groupId) {
        derivedStateOf {
            val msgs = allMessages[groupId] ?: emptyList()
            val members = (allGroupMembers[groupId] ?: emptyList()).toSet()
            pendingJoinRequests(msgs, members).filterNot { it.pubkey in resolvedRequestPubkeys }
        }
    }

    // Membership standing (None / Resolving / Pending / Member / Admin) is derived once in the
    // shared GroupViewModel so the Compose and web UIs can't drift on the rules. The pending bar
    // and its "Requested ..." line both read from it.
    val membership by vm.membershipState.collectAsState()
    val isPendingApproval = membership.status == GroupMembership.PENDING
    val pendingRequestedAtSeconds = membership.requestedAtSeconds

    // Switching accounts while a group is open leaves groupId unchanged, so the
    // per-session REQ effects must also key on the active account; otherwise the new
    // session never subscribes and the chat sits in skeletons until a reload.
    val activeAccountId by AppModule.accountStore.activeId.collectAsState()

    // Refresh group data on join / account switch; poll while pending approval
    LaunchedEffect(isPendingApproval, isJoined, groupId, activeAccountId) {
        if (!isJoined) return@LaunchedEffect
        vm.refreshGroupData()
        if (isPendingApproval) {
            while (true) {
                delay(10_000)
                vm.refreshGroupData()
            }
        }
    }

    val inviteCodes by remember(groupId) {
        derivedStateOf {
            val msgs = allMessages[groupId] ?: emptyList()
            val revokedEventIds =
                msgs
                    .filter { it.kind == 9005 }
                    .flatMap { msg -> msg.tags.filter { it.firstOrNull() == "e" }.mapNotNull { it.getOrNull(1) } }
                    .toSet()
            msgs
                .filter { it.kind == 9009 }
                .mapNotNull { msg ->
                    val code = msg.tags.firstOrNull { it.firstOrNull() == "code" }?.getOrNull(1) ?: return@mapNotNull null
                    if (msg.id in revokedEventIds) return@mapNotNull null
                    InviteCode(code = code, createdAt = msg.createdAt, eventId = msg.id)
                }.sortedByDescending { it.createdAt }
        }
    }

    val recentlyActiveMembers =
        remember(messages) {
            val tenMinutesAgo = epochSeconds() - (10 * 60)
            messages
                .filter { it.createdAt >= tenMinutesAgo }
                .map { it.pubkey }
                .toSet()
        }

    val connectionStatus =
        when (connectionState) {
            is ConnectionManager.ConnectionState.Disconnected -> "Disconnected"
            is ConnectionManager.ConnectionState.Connecting -> "Connecting..."
            is ConnectionManager.ConnectionState.Connected -> "Connected"
            is ConnectionManager.ConnectionState.Reconnecting -> {
                val state = connectionState as ConnectionManager.ConnectionState.Reconnecting
                "Reconnecting (${state.attempt}/${state.maxAttempts})..."
            }
            is ConnectionManager.ConnectionState.Error -> "Connection lost"
        }

    // Snapshot of the last-read timestamp at screen entry. The "new messages" divider
    // anchors on this value and stays in place even after markAsRead updates storage,
    // so the user keeps visual context for the session.
    //
    // The group opens at the bottom (newest), with the divider above. It is consumed
    // (see consumeDivider) only once the user acknowledges it: scrolls it into view
    // (onDividerSeen), sends a message, or taps the jump pill to the bottom. Reaching
    // the bottom on the entry settle must NOT clear it (issue #83).
    var lastReadSnapshot by remember(groupId) { mutableStateOf<Long?>(vm.getLastReadTimestamp()) }

    val chatItems =
        remember(messages, lastReadSnapshot) {
            buildChatItems(messages, lastReadSnapshot, currentUserPubkey)
        }

    // Persist the per-group draft's mention maps whenever they change (MessageInput owns
    // and persists the text itself). Plain in-memory map writes, no recomposition. When a
    // send clears mentions (and the text), the store entry empties and is dropped.
    LaunchedEffect(groupId) {
        snapshotFlow { mentions }.collect { AppModule.messageDraftStore.setMentions(groupId, it) }
    }
    LaunchedEffect(groupId) {
        snapshotFlow { groupMentions }.collect {
            AppModule.messageDraftStore.setGroupMentions(groupId, encodeGroupMentions(it))
        }
    }

    // Unread-from-others count for the FAB badge (Telegram pattern). Mirrors
    // the divider's filter — own messages and membership/moderation events (9021
    // join, 9022 leave, 9000/9001) don't count as unread, only kind:9 chat. Without
    // the kind guard a single chat plus a join would read as "3 new".
    val unreadFromOthersCount =
        remember(messages, lastReadSnapshot, currentUserPubkey) {
            val snapshot = lastReadSnapshot ?: return@remember 0
            messages.count { it.createdAt > snapshot && it.pubkey != currentUserPubkey && it.kind == 9 }
        }

    // Consume the "New messages" divider: the user has acknowledged the boundary
    // (sent a message, scrolled it into view, or tapped the jump pill to it). Re-anchor
    // the snapshot to the newest loaded message so the current divider disappears AND a
    // later arrival still re-arms a fresh divider + "N new" count without depending on a
    // leave-bottom event. markAsRead persists it so the divider does not return next entry.
    val consumeDivider: () -> Unit = {
        lastReadSnapshot = messages.maxOfOrNull { it.createdAt }
        vm.markAsRead()
    }

    // Bottom-follow tracking for the divider. atBottomNow mirrors the list's pin state;
    // engagedThisEntry latches once the user has genuinely scrolled away this entry.
    var atBottomNow by remember(groupId) { mutableStateOf(true) }
    var engagedThisEntry by remember(groupId) { mutableStateOf(false) }
    val handleReachedBottom: () -> Unit = {
        atBottomNow = true
        // Persist read state when caught up; the entry divider itself is NOT cleared here
        // (issue #83) — only consumeDivider does that, via scroll-to-it / send / jump.
        vm.markAsRead()
    }
    val handleLeftBottom: () -> Unit = {
        atBottomNow = false
        engagedThisEntry = true
        // Re-arm the unread baseline when leaving the bottom already caught up, so a later
        // arrival while scrolled up still shows "N new". Anchor to the current newest.
        if (lastReadSnapshot == null) {
            messages.maxOfOrNull { it.createdAt }?.let { lastReadSnapshot = it }
        }
    }

    // While following at the bottom after the user engaged the scroll this entry, keep the read
    // snapshot at the newest message. The divider is an entry artifact (consumed by scroll-up,
    // send, or jump); messages watched as they land are already read, so they must not flash a
    // fresh divider above the latest while sitting at the bottom.
    LaunchedEffect(messages, atBottomNow, engagedThisEntry) {
        if (atBottomNow && engagedThisEntry) {
            messages.maxOfOrNull { it.createdAt }?.let { lastReadSnapshot = it }
        }
    }

    // `awaitingAuthReadSet` keeps the skeleton up while a private group's initial read waits on
    // NIP-42 AUTH (relay challenges in response to the read, e.g. opened from the homepage); the
    // controller briefly bounces through Idle during resubscribeAfterAuth, so without this the
    // empty-state would flash before the authenticated read lands.
    val isInitialLoading =
        (isLoadingMoreMap[groupId] == true || groupId in awaitingAuthReadSet) && chatItems.isEmpty()
    // Pending/restricted relays never deliver the member list, so the skeleton
    // would spin forever — force-off so the sidebar can render its empty state.
    val isMembersLoading =
        groupId in loadingMembersSet &&
            groupMembers.isEmpty() &&
            !isPendingApproval &&
            !isGroupRestricted

    LaunchedEffect(groupId, activeAccountId) {
        vm.requestGroupMessages(selectedChannel)
        // Entering the group persists the current time as the last read point and
        // clears the in-memory counter. Runs after `remember(groupId)` captured
        // the old value for the divider snapshot above.
        vm.markAsRead()
    }

    // Re-mark the open group as read when the app regains focus: messages that
    // arrived while active-but-unfocused are now on screen, so clear their
    // notification-feed entries instead of leaving a stale count.
    val isAppFocused by AppModule.focusTracker.isAppFocused.collectAsState()
    LaunchedEffect(isAppFocused) {
        if (isAppFocused) vm.markAsRead()
    }

    LaunchedEffect(selectedChannel) {
        vm.requestGroupMessages(selectedChannel)
    }

    // Fetch kind:0 for every message author (plus members), mirroring the web
    // ChatScreen. The repository auto-requests metadata for members, reactors and
    // quoted events, but not plain message authors who aren't members. Without
    // their profile the Zap action (gated on lud16/lud06) never appears for them,
    // so the native menu was missing Zap where the web one showed it.
    LaunchedEffect(groupId, messages.size, memberPubkeys.size) {
        val pubkeys = (memberPubkeys + messages.map { it.pubkey }).toSet()
        if (pubkeys.isNotEmpty()) AppModule.nostrRepository.requestUserMetadata(pubkeys)
    }

    // Re-request messages when connection is restored so the open group reloads after
    // a reconnect, even if it wasn't in the joined list or messages cache.
    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionManager.ConnectionState.Connected) {
            vm.requestGroupMessages(selectedChannel)
        }
    }

    // Group info modal
    if (showGroupInfoModal) {
        GroupInfoModal(
            groupId = groupId,
            groupName = groupName,
            groupMetadata = currentGroupMetadata,
            relayUrl = relayUrl ?: currentRelayUrl,
            onOpenRelay = onOpenRelay,
            isMember = isJoined,
            memberCount = groupMembers.size,
            userMetadata = userMetadata,
            onUserClick = { pubkey ->
                showGroupInfoModal = false
                selectedUserPubkey = pubkey
            },
            onLeave = {
                showGroupInfoModal = false
                vm.leaveGroup { onNavigateHome() }
            },
            onDismiss = { showGroupInfoModal = false },
        )
    }

    // "Edit group" opens the unified Manage group modal on its Info tab (admin only), so editing
    // shares the same surface as members / requests instead of a separate modal.
    if (showEditGroupModal) {
        ManageGroupModal(
            groupId = groupId,
            currentMetadata = currentGroupMetadata,
            relayUrl = relayUrl ?: currentRelayUrl,
            onDismiss = { showEditGroupModal = false },
            onDeleted = {
                showEditGroupModal = false
                // Deleting a channel lands on its parent group; only a root delete goes home.
                val parentId = currentGroupMetadata?.parent
                if (parentId != null) onNavigateToGroup(parentId, parentGroupName, relayUrl ?: currentRelayUrl, null) else onNavigateHome()
            },
            initialTab = ManageTab.Info,
            supportsSubgroups = supportsSubgroups,
            onOpenGroup = { id ->
                showEditGroupModal = false
                onNavigateToGroup(id, null, relayUrl ?: currentRelayUrl, null)
            },
        )
    }

    // Managing members opens the unified Manage group modal on its Members tab (admin only),
    // so there is a single members surface across the app instead of a separate modal.
    if (showMemberManagementModal) {
        ManageGroupModal(
            groupId = groupId,
            currentMetadata = currentGroupMetadata,
            relayUrl = relayUrl ?: currentRelayUrl,
            onDismiss = { showMemberManagementModal = false },
            onDeleted = {
                showMemberManagementModal = false
                val parentId = currentGroupMetadata?.parent
                if (parentId != null) onNavigateToGroup(parentId, parentGroupName, relayUrl ?: currentRelayUrl, null) else onNavigateHome()
            },
            initialTab = ManageTab.Members,
            supportsSubgroups = supportsSubgroups,
            onOpenGroup = { id ->
                showMemberManagementModal = false
                onNavigateToGroup(id, null, relayUrl ?: currentRelayUrl, null)
            },
        )
    }

    // Invite codes modal (admin + closed groups)
    if (showInviteCodesModal) {
        InviteCodesModal(
            inviteCodes = inviteCodes,
            onCreateInviteCode = {
                vm.createInviteCode { code -> createdInviteCode = code }
            },
            onRevokeInviteCode = { eventId -> vm.revokeInviteCode(eventId) },
            onDismiss = {
                showInviteCodesModal = false
                createdInviteCode = null
                if (moderationError != null) vm.clearModerationError()
            },
            relayUrl = relayUrl ?: currentRelayUrl,
            groupId = groupId,
            createdCode = createdInviteCode,
            errorMessage = moderationError,
            onClearError = { vm.clearModerationError() },
        )
    }

    // Delete group confirmation dialog (admin only).
    // Per NIP-29: when a parent is deleted, its children become roots.
    if (showDeleteGroupDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteGroupDialog = false },
            containerColor = NostrordColors.Surface,
            titleContentColor = NostrordColors.TextPrimary,
            textContentColor = NostrordColors.TextSecondary,
            title = { Text("Delete Group") },
            text = {
                Column {
                    Text(
                        "Are you sure you want to permanently delete \"${currentGroupMetadata?.name ?: groupName ?: "this group"}\"? This action cannot be undone.",
                    )
                    deleteErrorMessage?.let { err ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(err, color = NostrordColors.Error)
                    }
                    val childCount = childrenByParent[groupId]?.size ?: 0
                    if (childCount > 0) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "This group has $childCount channel${if (childCount == 1) "" else "s"} that will become root groups.",
                            color = NostrordColors.TextPrimary,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !deleteInProgress,
                    onClick = {
                        deleteInProgress = true
                        deleteErrorMessage = null
                        // Catch everything (incl. LinkageError / NoClassDefFoundError
                        // which composeHotReload can synthesize during a partial
                        // recompile that renumbers anonymous Compose lambdas) so a
                        // stale-bytecode bug surfaces as an inline error message
                        // instead of the bare native error dialog the EDT shows
                        // when a Throwable escapes a click callback.
                        try {
                            vm.deleteGroup { result ->
                                deleteInProgress = false
                                when (result) {
                                    is Result.Success -> {
                                        showDeleteGroupDialog = false
                                        onNavigateHome()
                                    }
                                    is Result.Error -> {
                                        deleteErrorMessage = result.error.cause?.message
                                            ?: result.error.message
                                            ?: "Failed to delete group."
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            deleteInProgress = false
                            deleteErrorMessage = "Could not delete group: ${t.message ?: t::class.simpleName}"
                        }
                    },
                ) {
                    Text("Delete", color = NostrordColors.Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteGroupDialog = false }) {
                    Text("Cancel", color = NostrordColors.TextSecondary)
                }
            },
        )
    }

    // Delete message confirmation dialog
    messageToDelete?.let { msg ->
        ConfirmDialog(
            title = "Delete Message",
            message = "Are you sure you want to delete this message? This action cannot be undone.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                vm.deleteMessage(msg.id)
                messageToDelete = null
            },
            onDismiss = { messageToDelete = null },
        )
    }

    // Delete message error dialog (relay rejected the deletion)
    deleteMessageError?.let { error ->
        ConfirmDialog(
            title = "Could Not Delete Message",
            message = error,
            confirmLabel = "OK",
            cancelLabel = null,
            onConfirm = { vm.clearDeleteMessageError() },
            onDismiss = { vm.clearDeleteMessageError() },
        )
    }

    // Reaction error dialog (relay rejected kind 7)
    reactionError?.let { error ->
        val isUnknownMember = error.contains("unknown member", ignoreCase = true)
        ConfirmDialog(
            title = if (isUnknownMember) "Join Required" else "Cannot React",
            message =
            if (isUnknownMember) {
                "You need to join this group before you can react to messages."
            } else {
                "This relay does not support reactions.\n\n$error"
            },
            confirmLabel = if (isUnknownMember) "Join Group" else "OK",
            cancelLabel = if (isUnknownMember) "Cancel" else null,
            onConfirm = {
                vm.clearReactionError()
                // Closed groups surface the invite-code modal elsewhere; only open groups join here.
                if (isUnknownMember && currentGroupMetadata?.isOpen != false) vm.joinGroup()
            },
            onDismiss = { vm.clearReactionError() },
        )
    }

    // Accept/decline prompt for an external add (an admin's kind:9000). The group stays
    // readable as a preview while undecided; backdrop/Esc = decide later (the prompt
    // returns on the next open). Accept adopts it into the joined list; Decline leaves.
    val pendingInvite by vm.pendingInvite.collectAsState()
    val inviteActorLabel by vm.pendingInviteActorLabel.collectAsState()
    var invitePromptDismissed by remember(groupId) { mutableStateOf(false) }
    if (!invitePromptDismissed) {
        pendingInvite?.let { invite ->
            GroupInviteDialog(
                groupName = currentGroupMetadata?.name?.takeIf { it.isNotBlank() }
                    ?: groupName?.takeIf { it.isNotBlank() }
                    ?: "#${groupId.take(8)}",
                groupPicture = currentGroupMetadata?.picture,
                groupId = groupId,
                relayHost = invite.relayUrl.removePrefix("wss://").removePrefix("ws://").trimEnd('/'),
                actorLabel = inviteActorLabel,
                onAccept = { vm.acceptInvite() },
                onDecline = { vm.declineInvite { onNavigateHome() } },
                onDismiss = { invitePromptDismissed = true },
            )
        }
    }

    // Join error dialog (relay rejected the kind:9021 join request)
    joinError?.let { error ->
        ConfirmDialog(
            title = "Could Not Join",
            message = error,
            confirmLabel = "OK",
            cancelLabel = null,
            onConfirm = { vm.clearJoinError() },
            onDismiss = { vm.clearJoinError() },
        )
    }

    // Send message error dialog
    sendError?.let { error ->
        val isPendingError = error.contains("pending admin approval", ignoreCase = true)
        ConfirmDialog(
            title = if (isPendingError) "Pending Approval" else "Message Not Sent",
            message = error,
            confirmLabel = "OK",
            cancelLabel = null,
            onConfirm = { vm.clearSendError() },
            onDismiss = { vm.clearSendError() },
        )
    }

    // User profile modal
    selectedUserPubkey?.let { pubkey ->
        val targetMember = groupMembers.firstOrNull { it.pubkey == pubkey }
        UserProfileModal(
            pubkey = pubkey,
            metadata = userMetadata[pubkey],
            userMetadata = userMetadata,
            iAmAdmin = isAdmin,
            targetIsAdmin = targetMember?.isAdmin == true,
            // Pipes into the existing remove-member confirmation dialog below.
            onRemoveFromGroup =
            targetMember?.let { member ->
                {
                    selectedUserPubkey = null
                    memberToRemove = member
                }
            },
            // Pipes into the role-change confirmation dialog below.
            onToggleAdminRole =
            targetMember?.let { member ->
                {
                    selectedUserPubkey = null
                    memberToChangeRole = member
                }
            },
            // Inserts a resolved @mention into the composer draft (prototype behavior).
            onMention = { pk ->
                val meta = userMetadata[pk]
                val name =
                    meta?.displayName?.takeIf { it.isNotBlank() }
                        ?: meta?.name?.takeIf { it.isNotBlank() }
                        ?: (Nip19.encodeNpub(pk).take(12) + "…")
                messageInput = (if (messageInput.isBlank()) "" else messageInput.trimEnd() + " ") + "@$name "
                mentions = mentions + (name to pk)
                selectedUserPubkey = null
            },
            onUserClick = { clickedPubkey ->
                selectedUserPubkey = clickedPubkey
            },
            onDismiss = { selectedUserPubkey = null },
        )
    }

    // Remove member confirmation dialog
    memberToRemove?.let { member ->
        ConfirmDialog(
            title = "Remove Member",
            message = "Remove ${member.displayName} from this group?",
            confirmLabel = "Remove",
            destructive = true,
            confirmEnabled = !moderationBusy,
            onConfirm = {
                vm.removeUser(member.pubkey)
                memberToRemove = null
            },
            onDismiss = { memberToRemove = null },
        )
    }

    // Role-change confirmation dialog (same copy as the manage modal's member list).
    memberToChangeRole?.let { member ->
        ConfirmDialog(
            title = if (member.isAdmin) "Remove Admin Role" else "Promote to Admin",
            message =
            if (member.isAdmin) {
                "${member.displayName} will lose admin privileges."
            } else {
                "${member.displayName} will be able to manage members and group settings."
            },
            confirmLabel = if (member.isAdmin) "Demote" else "Promote",
            confirmEnabled = !moderationBusy,
            onConfirm = {
                if (member.isAdmin) vm.demoteFromAdmin(member.pubkey) else vm.promoteToAdmin(member.pubkey)
                memberToChangeRole = null
            },
            onDismiss = { memberToChangeRole = null },
        )
    }

    // Moderation errors from actions launched at this level (remove / role change). The
    // manage and invite modals render the same VM error inline, so defer to them when open.
    if (!showMemberManagementModal && !showJoinRequestsModal && !showInviteCodesModal) {
        moderationError?.let { error ->
            ConfirmDialog(
                title = "Action Failed",
                message = error,
                confirmLabel = "OK",
                cancelLabel = null,
                onConfirm = { vm.clearModerationError() },
                onDismiss = { vm.clearModerationError() },
            )
        }
    }

    // Join requests open the unified Manage group modal on its Requests tab (parity with the
    // web requests badge, which opens ManageGroupModal at "requests").
    if (showJoinRequestsModal) {
        ManageGroupModal(
            groupId = groupId,
            currentMetadata = currentGroupMetadata,
            relayUrl = relayUrl ?: currentRelayUrl,
            onDismiss = { showJoinRequestsModal = false },
            onDeleted = {
                showJoinRequestsModal = false
                val parentId = currentGroupMetadata?.parent
                if (parentId != null) onNavigateToGroup(parentId, parentGroupName, relayUrl ?: currentRelayUrl, null) else onNavigateHome()
            },
            initialTab = ManageTab.Requests,
            supportsSubgroups = supportsSubgroups,
            onOpenGroup = { id ->
                showJoinRequestsModal = false
                onNavigateToGroup(id, null, relayUrl ?: currentRelayUrl, null)
            },
        )
    }

    if (showLeaveDialog) {
        ConfirmDialog(
            title = "Leave Group",
            message = "Are you sure you want to leave ${groupName ?: "this group"}? You can rejoin later if you change your mind.",
            confirmLabel = "Leave",
            destructive = true,
            onConfirm = {
                vm.leaveGroup {
                    showLeaveDialog = false
                    onNavigateHome()
                }
            },
            onDismiss = { showLeaveDialog = false },
        )
    }

    // Responsive layout
    val parentHidden = LocalAnimatedImageHidden.current
    val anyDialogOpen =
        parentHidden ||
            showLeaveDialog ||
            showGroupInfoModal ||
            showEditGroupModal ||
            showDeleteGroupDialog ||
            messageToDelete != null ||
            selectedUserPubkey != null ||
            showMemberSheet ||
            memberToRemove != null ||
            memberToChangeRole != null ||
            showJoinRequestsModal ||
            showInviteCodesModal ||
            inputOverlayOpen
    CompositionLocalProvider(LocalAnimatedImageHidden provides anyDialogOpen) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isCompact = !forceDesktop

            if (isOrphaned) {
                OrphanedGroupContent(onForget = { vm.forget { onNavigateHome() } })
            } else if (isCompact) {
                GroupScreenMobile(
                    groupId = groupId,
                    groupName = groupName,
                    relayUrl = relayUrl ?: currentRelayUrl,
                    groupMetadata = currentGroupMetadata,
                    selectedChannel = selectedChannel,
                    onChannelSelect = { selectedChannel = it },
                    onOpenDrawer = onOpenDrawer,
                    messages = messages,
                    chatItems = chatItems,
                    connectionStatus = connectionStatus,
                    connectionState = connectionState,
                    isJoined = isJoined,
                    isAdmin = isAdmin,
                    userMetadata = userMetadata,
                    reactions = allReactions,
                    pendingReactions = pendingReactions,
                    messageStatus = messageStatus,
                    onRetrySend = { vm.retrySend(it) },
                    onDismissFailed = { vm.dismissFailed(it) },
                    currentUserPubkey = currentUserPubkey,
                    messageInput = messageInput,
                    onSendMessage = { text ->
                        val imetaTags = pendingUploads.map { it.toImetaTag() }
                        var content = text
                        groupMentions.forEach { (name, group) ->
                            val relayPubkey = relayMetadata[group.relay]?.groupNaddrAuthor
                            val naddr = Nip19.encodeNaddr(group.id, group.relay, pubkeyHex = relayPubkey)
                            content = content.replace("%$name", "nostr:$naddr")
                        }
                        // Snapshot for restore-on-failure. The composer owns and clears
                        // its own field; here we clear the rest of the draft state and
                        // reset messageInput so a failure can push the text back through
                        // the restore channel (a transition from "" guarantees it fires).
                        val sentInput = text
                        val sentMentions = mentions
                        val sentGroupMentions = groupMentions
                        val sentReply = replyingToMessage
                        val sentUploads = pendingUploads
                        messageInput = ""
                        mentions = emptyMap()
                        groupMentions = emptyMap()
                        replyingToMessage = null
                        pendingUploads = emptyList()
                        // Sending acknowledges the "New messages" boundary: drop the divider.
                        consumeDivider()
                        vm.sendMessage(
                            content,
                            selectedChannel,
                            sentMentions,
                            sentReply?.id,
                            imetaTags,
                            onFailure = {
                                // Restore so it can be retried. The composer re-accepts
                                // the text only if the user hasn't started a new message.
                                messageInput = sentInput
                                mentions = sentMentions
                                groupMentions = sentGroupMentions
                                replyingToMessage = sentReply
                                pendingUploads = sentUploads
                            },
                        )
                    },
                    onJoinGroup = { inviteCode -> vm.joinGroup(inviteCode) },
                    onLeaveGroup = { showLeaveDialog = true },
                    onShowGroupInfo = { showGroupInfoModal = true },
                    onEditGroup = { showEditGroupModal = true },
                    onDeleteGroup = { showDeleteGroupDialog = true },
                    onManageMembers = { showMemberManagementModal = true },
                    showSubgroupControls = supportsSubgroups,
                    parentGroupName = parentGroupName,
                    onParentClick = {
                        val parentId = currentGroupMetadata?.parent
                        if (!parentId.isNullOrBlank()) {
                            onNavigateToGroup(parentId, parentGroupName, null, null)
                        }
                    },
                    subgroupCount = childrenByParent[groupId]?.size ?: 0,
                    groupMembers = groupMembers,
                    recentlyActiveMembers = recentlyActiveMembers,
                    mentions = mentions,
                    onMentionsChange = { mentions = it },
                    availableGroups = availableGroups,
                    groupMentions = groupMentions,
                    onGroupMentionsChange = { groupMentions = it },
                    replyingToMessage = replyingToMessage,
                    replyNonce = replyNonce,
                    onReplyClick = { message ->
                        replyingToMessage = message
                        replyNonce++
                    },
                    onDeleteMessage = { message -> messageToDelete = message },
                    onReactionBadgeClick = { messageId, emoji ->
                        val targetMessage = messages.find { it.id == messageId }
                        if (targetMessage != null) {
                            vm.sendReaction(messageId, targetMessage.pubkey, emoji)
                        }
                    },
                    onCancelReply = { replyingToMessage = null },
                    isMembersLoading = isMembersLoading,
                    isInitialLoading = isInitialLoading,
                    isLoadingMore = isLoadingMore,
                    hasMoreMessages = hasMoreMessages,
                    isLoadStalled = isLoadStalled,
                    onLoadMore = { vm.loadMoreMessages(selectedChannel) },
                    onRetryLoadMore = { vm.retryLoadMore(selectedChannel) },
                    joinedGroups = joinedGroups,
                    groups = groups,
                    onNavigateToGroup = onNavigateToGroup,
                    onUserClick = { pubkey -> selectedUserPubkey = pubkey },
                    isSending = isSending,
                    onMediaUploaded = { upload ->
                        if (pendingUploads.none { it.url == upload.url }) {
                            pendingUploads = pendingUploads + upload
                        }
                    },
                    isCurrentUserAdmin = isAdmin,
                    onRemoveMember = { member -> memberToRemove = member },
                    onAddMember = { pubkey, notify -> vm.addUser(pubkey, notifyViaDm = notify) },
                    friends = friends,
                    groupRelay = groupHostRelay(groupId, allGroupsByRelay, currentRelayUrl),
                    userGroupLists = userGroupLists,
                    onPrefetchTarget = { vm.prefetchUserGroupList(it) },
                    pendingJoinRequestCount = pendingRequests.size,
                    onJoinRequestsClick = { showJoinRequestsModal = true },
                    isPendingApproval = isPendingApproval,
                    pendingRequestedAtSeconds = pendingRequestedAtSeconds,
                    onCancelJoinRequest = {
                        vm.leaveGroup { onNavigateHome() }
                    },
                    onInviteCodesClick = { showInviteCodesModal = true },
                    isClosed = currentGroupMetadata?.isOpen == false,
                    isGroupRestricted = isGroupRestricted,
                    initialInviteCode = effectiveInviteCode,
                    onReachedBottom = handleReachedBottom,
                    onLeftBottom = handleLeftBottom,
                    onSeenUpTo = { ts -> vm.markAsReadUpTo(ts) },
                    onDividerSeen = { consumeDivider() },
                    unreadFromOthersCount = unreadFromOthersCount,
                    targetMessageId = targetMessageId,
                    onTargetConsumed = onTargetMessageConsumed,
                    onFetchTargetById = { id -> vm.fetchMessageById(id) },
                    onInputOverlayVisibilityChange = { inputOverlayOpen = it },
                )
            } else {
                GroupScreenDesktop(
                    groupId = groupId,
                    groupName = groupName,
                    relayUrl = relayUrl ?: currentRelayUrl,
                    groupMetadata = currentGroupMetadata,
                    selectedChannel = selectedChannel,
                    onChannelSelect = { selectedChannel = it },
                    messages = messages,
                    chatItems = chatItems,
                    connectionStatus = connectionStatus,
                    connectionState = connectionState,
                    isJoined = isJoined,
                    isAdmin = isAdmin,
                    userMetadata = userMetadata,
                    reactions = allReactions,
                    pendingReactions = pendingReactions,
                    messageStatus = messageStatus,
                    onRetrySend = { vm.retrySend(it) },
                    onDismissFailed = { vm.dismissFailed(it) },
                    currentUserPubkey = currentUserPubkey,
                    messageInput = messageInput,
                    onSendMessage = { text ->
                        val imetaTags = pendingUploads.map { it.toImetaTag() }
                        var content = text
                        groupMentions.forEach { (name, group) ->
                            val relayPubkey = relayMetadata[group.relay]?.groupNaddrAuthor
                            val naddr = Nip19.encodeNaddr(group.id, group.relay, pubkeyHex = relayPubkey)
                            content = content.replace("%$name", "nostr:$naddr")
                        }
                        // Snapshot for restore-on-failure. The composer owns and clears
                        // its own field; here we clear the rest of the draft state and
                        // reset messageInput so a failure can push the text back through
                        // the restore channel (a transition from "" guarantees it fires).
                        val sentInput = text
                        val sentMentions = mentions
                        val sentGroupMentions = groupMentions
                        val sentReply = replyingToMessage
                        val sentUploads = pendingUploads
                        messageInput = ""
                        mentions = emptyMap()
                        groupMentions = emptyMap()
                        replyingToMessage = null
                        pendingUploads = emptyList()
                        // Sending acknowledges the "New messages" boundary: drop the divider.
                        consumeDivider()
                        vm.sendMessage(
                            content,
                            selectedChannel,
                            sentMentions,
                            sentReply?.id,
                            imetaTags,
                            onFailure = {
                                // Restore so it can be retried. The composer re-accepts
                                // the text only if the user hasn't started a new message.
                                messageInput = sentInput
                                mentions = sentMentions
                                groupMentions = sentGroupMentions
                                replyingToMessage = sentReply
                                pendingUploads = sentUploads
                            },
                        )
                    },
                    onJoinGroup = { inviteCode -> vm.joinGroup(inviteCode) },
                    onLeaveGroup = { showLeaveDialog = true },
                    onShowGroupInfo = { showGroupInfoModal = true },
                    onEditGroup = { showEditGroupModal = true },
                    onDeleteGroup = { showDeleteGroupDialog = true },
                    onManageMembers = { showMemberManagementModal = true },
                    showSubgroupControls = supportsSubgroups,
                    parentGroupName = parentGroupName,
                    onParentClick = {
                        val parentId = currentGroupMetadata?.parent
                        if (!parentId.isNullOrBlank()) {
                            onNavigateToGroup(parentId, parentGroupName, null, null)
                        }
                    },
                    subgroupCount = childrenByParent[groupId]?.size ?: 0,
                    groupMembers = groupMembers,
                    recentlyActiveMembers = recentlyActiveMembers,
                    mentions = mentions,
                    onMentionsChange = { mentions = it },
                    availableGroups = availableGroups,
                    groupMentions = groupMentions,
                    onGroupMentionsChange = { groupMentions = it },
                    replyingToMessage = replyingToMessage,
                    replyNonce = replyNonce,
                    onReplyClick = { message ->
                        replyingToMessage = message
                        replyNonce++
                    },
                    onDeleteMessage = { message -> messageToDelete = message },
                    onReactionBadgeClick = { messageId, emoji ->
                        val targetMessage = messages.find { it.id == messageId }
                        if (targetMessage != null) {
                            vm.sendReaction(messageId, targetMessage.pubkey, emoji)
                        }
                    },
                    onCancelReply = { replyingToMessage = null },
                    isMembersLoading = isMembersLoading,
                    isInitialLoading = isInitialLoading,
                    isLoadingMore = isLoadingMore,
                    hasMoreMessages = hasMoreMessages,
                    isLoadStalled = isLoadStalled,
                    onLoadMore = { vm.loadMoreMessages(selectedChannel) },
                    onRetryLoadMore = { vm.retryLoadMore(selectedChannel) },
                    joinedGroups = joinedGroups,
                    groups = groups,
                    onNavigateToGroup = onNavigateToGroup,
                    onUserClick = { pubkey -> selectedUserPubkey = pubkey },
                    isSending = isSending,
                    onMediaUploaded = { upload ->
                        if (pendingUploads.none { it.url == upload.url }) {
                            pendingUploads = pendingUploads + upload
                        }
                    },
                    showMemberSidebar = maxWidth >= 1024.dp && membersVisible,
                    onToggleMembers = {
                        if (maxWidth >= 1024.dp) {
                            membersVisible = !membersVisible
                        } else {
                            showMemberSheet = true
                        }
                    },
                    showMemberSheet = showMemberSheet,
                    onShowMemberSheet = { showMemberSheet = it },
                    isCurrentUserAdmin = isAdmin,
                    onRemoveMember = { member -> memberToRemove = member },
                    onAddMember = { pubkey, notify -> vm.addUser(pubkey, notifyViaDm = notify) },
                    friends = friends,
                    groupRelay = groupHostRelay(groupId, allGroupsByRelay, currentRelayUrl),
                    userGroupLists = userGroupLists,
                    onPrefetchTarget = { vm.prefetchUserGroupList(it) },
                    pendingJoinRequestCount = pendingRequests.size,
                    onJoinRequestsClick = { showJoinRequestsModal = true },
                    isPendingApproval = isPendingApproval,
                    pendingRequestedAtSeconds = pendingRequestedAtSeconds,
                    onCancelJoinRequest = {
                        vm.leaveGroup { onNavigateHome() }
                    },
                    onInviteCodesClick = { showInviteCodesModal = true },
                    isClosed = currentGroupMetadata?.isOpen == false,
                    isGroupRestricted = isGroupRestricted,
                    initialInviteCode = effectiveInviteCode,
                    onReachedBottom = handleReachedBottom,
                    onLeftBottom = handleLeftBottom,
                    onSeenUpTo = { ts -> vm.markAsReadUpTo(ts) },
                    onDividerSeen = { consumeDivider() },
                    unreadFromOthersCount = unreadFromOthersCount,
                    targetMessageId = targetMessageId,
                    onTargetConsumed = onTargetMessageConsumed,
                    onFetchTargetById = { id -> vm.fetchMessageById(id) },
                    onInputOverlayVisibilityChange = { inputOverlayOpen = it },
                )
            }
        }
    } // CompositionLocalProvider
}

/**
 * The pending-invite prompt (Compose counterpart of the web GroupInviteModal): "GROUP
 * INVITE" eyebrow + group avatar/name/relay, one line saying who invited (or a neutral
 * "You were added" when the actor is unknown or the relay itself), and Decline / Accept.
 * Backdrop/Esc runs [onDismiss] = decide later.
 */
@Composable
private fun GroupInviteDialog(
    groupName: String,
    groupPicture: String?,
    groupId: String,
    relayHost: String,
    actorLabel: String?,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(480.dp),
        containerColor = NostrordColors.Surface,
        shape = RoundedCornerShape(16.dp),
        title = {
            // Same eyebrow as the DM GroupInviteCard (muted, 11sp bold, tight tracking).
            Text(
                text = "GROUP INVITE",
                color = NostrordColors.TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            )
        },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OptimizedSmallAvatar(
                        imageUrl = groupPicture?.ifBlank { null },
                        identifier = groupId,
                        displayName = groupName,
                        size = 44.dp,
                        shape = RoundedCornerShape(10.dp),
                        isGroup = true,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = groupName,
                            color = NostrordColors.TextPrimary,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = relayHost,
                            color = NostrordColors.TextMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text =
                    if (actorLabel != null) {
                        "$actorLabel invited you to this group. Accept to add it to your groups, or decline to leave."
                    } else {
                        "You were added to this group. Accept to add it to your groups, or decline to leave."
                    },
                    color = NostrordColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                colors =
                ButtonDefaults.buttonColors(
                    containerColor = NostrordColors.Primary,
                    contentColor = Color.White,
                ),
                shape = RoundedCornerShape(8.dp),
            ) { Text("Accept", fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            Button(
                onClick = onDecline,
                colors =
                ButtonDefaults.buttonColors(
                    containerColor = NostrordColors.SurfaceVariant,
                    contentColor = NostrordColors.TextPrimary,
                ),
                shape = RoundedCornerShape(8.dp),
            ) { Text("Decline", fontWeight = FontWeight.SemiBold) }
        },
    )
}
