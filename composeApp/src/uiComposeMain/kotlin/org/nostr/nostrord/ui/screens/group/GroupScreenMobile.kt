package org.nostr.nostrord.ui.screens.group

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.NostrGroupClient.NostrMessage
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.ui.components.chat.LiveSpaceSection
import org.nostr.nostrord.ui.components.chat.LocalAnimatedImageHidden
import org.nostr.nostrord.ui.components.layout.FrameMenuButton
import org.nostr.nostrord.ui.components.sidebars.MemberDrawerOverlay
import org.nostr.nostrord.ui.components.sidebars.MemberSidebar
import org.nostr.nostrord.ui.screens.group.components.GroupHeader
import org.nostr.nostrord.ui.screens.group.components.MessageInput
import org.nostr.nostrord.ui.screens.group.components.MessagesList
import org.nostr.nostrord.ui.screens.group.components.rememberChatSearchState
import org.nostr.nostrord.ui.screens.group.model.ChatItem
import org.nostr.nostrord.ui.screens.group.model.GroupInfo
import org.nostr.nostrord.ui.screens.group.model.MemberInfo
import org.nostr.nostrord.ui.theme.NostrordColors
import kotlin.math.abs

/**
 * Mobile group screen with gesture navigation.
 *
 * Gesture support:
 * - Swipe left from right edge: Open member sheet
 * - Tap people icon: Open member sheet
 *
 * Layout follows mobile-first design:
 * - Full-screen message view
 * - Bottom-anchored input (thumb-reachable)
 * - Slide-in member panel
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreenMobile(
    groupId: String,
    groupName: String?,
    relayUrl: String = "",
    groupMetadata: GroupMetadata?,
    selectedChannel: String,
    onChannelSelect: (String) -> Unit,
    onOpenDrawer: () -> Unit = {},
    messages: List<NostrGroupClient.NostrMessage>,
    chatItems: List<ChatItem>,
    connectionStatus: String,
    connectionState: ConnectionManager.ConnectionState,
    isJoined: Boolean,
    /** Relay-side member with no entry in our own kind:10009: offer to add it. */
    canAddToList: Boolean = false,
    onAddToList: () -> Unit = {},
    isAdmin: Boolean = false,
    userMetadata: Map<String, UserMetadata>,
    reactions: Map<String, Map<String, GroupManager.ReactionInfo>> = emptyMap(),
    pendingReactions: Set<String> = emptySet(),
    messageStatus: Map<String, GroupManager.MessageStatus> = emptyMap(),
    onRetrySend: (eventId: String) -> Unit = {},
    onDismissFailed: (eventId: String) -> Unit = {},
    currentUserPubkey: String? = null,
    messageInput: String,
    onSendMessage: (String) -> Unit,
    onJoinGroup: (inviteCode: String?) -> Unit,
    onLeaveGroup: () -> Unit,
    onShowGroupInfo: () -> Unit = {},
    onEditGroup: () -> Unit = {},
    onDeleteGroup: () -> Unit = {},
    onManageMembers: () -> Unit = {},
    showSubgroupControls: Boolean = true,
    parentGroupName: String? = null,
    onParentClick: () -> Unit = {},
    subgroupCount: Int = 0,
    groupMembers: List<MemberInfo> = emptyList(),
    recentlyActiveMembers: Set<String> = emptySet(),
    mentions: Map<String, String> = emptyMap(),
    onMentionsChange: (Map<String, String>) -> Unit = {},
    availableGroups: List<GroupInfo> = emptyList(),
    groupMentions: Map<String, GroupInfo> = emptyMap(),
    onGroupMentionsChange: (Map<String, GroupInfo>) -> Unit = {},
    replyingToMessage: NostrMessage? = null,
    replyNonce: Int = 0,
    onReplyClick: (NostrMessage) -> Unit = {},
    onDeleteMessage: (NostrMessage) -> Unit = {},
    onReactionBadgeClick: (messageId: String, emoji: String) -> Unit = { _, _ -> },
    onCancelReply: () -> Unit = {},
    onReachedBottom: () -> Unit = {},
    onLeftBottom: () -> Unit = {},
    onSeenUpTo: (Long) -> Unit = {},
    onDividerSeen: () -> Unit = {},
    unreadFromOthersCount: Int = 0,
    isMembersLoading: Boolean = false,
    isInitialLoading: Boolean = false,
    isLoadingMore: Boolean = false,
    hasMoreMessages: Boolean = true,
    isLoadStalled: Boolean = false,
    onLoadMore: () -> Unit = {},
    onRetryLoadMore: () -> Unit = {},
    joinedGroups: Set<String> = emptySet(),
    groups: List<GroupMetadata> = emptyList(),
    onNavigateToGroup: (groupId: String, groupName: String?, relayUrl: String?, messageId: String?) -> Unit = { _, _, _, _ -> },
    onUserClick: (String) -> Unit = {},
    isSending: Boolean = false,
    onMediaUploaded: (org.nostr.nostrord.network.upload.UploadResult) -> Unit = {},
    isCurrentUserAdmin: Boolean = false,
    onRemoveMember: (MemberInfo) -> Unit = {},
    onAddMember: (pubkey: String, notifyViaDm: Boolean) -> Unit = { _, _ -> },
    friends: List<FriendCandidate> = emptyList(),
    groupRelay: String? = null,
    userGroupLists: Map<String, List<org.nostr.nostrord.network.UserGroupRef>> = emptyMap(),
    onPrefetchTarget: (String) -> Unit = {},
    pendingJoinRequestCount: Int = 0,
    onJoinRequestsClick: () -> Unit = {},
    isPendingApproval: Boolean = false,
    pendingRequestedAtSeconds: Long? = null,
    onCancelJoinRequest: () -> Unit = {},
    onInviteCodesClick: () -> Unit = {},
    isClosed: Boolean = false,
    isGroupRestricted: Boolean = false,
    initialInviteCode: String? = null,
    targetMessageId: String? = null,
    onTargetConsumed: () -> Unit = {},
    onFetchTargetById: (String) -> Unit = {},
    onInputOverlayVisibilityChange: (Boolean) -> Unit = {},
) {
    var showMemberSheet by remember { mutableStateOf(false) }

    val search = rememberChatSearchState(
        groupId = groupId,
        messages = messages,
        userMetadata = userMetadata,
        hasMoreMessages = hasMoreMessages,
        isLoadingMore = isLoadingMore,
        onLoadMore = onLoadMore,
    )

    val parentHidden = LocalAnimatedImageHidden.current
    CompositionLocalProvider(LocalAnimatedImageHidden provides (parentHidden || showMemberSheet)) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    // Shared new-design header (same as desktop GroupHeader); the hamburger
                    // navigationIcon opens the left drawer and the trailing People button opens
                    // the member sheet.
                    GroupHeader(
                        groupName = if (isGroupRestricted && groupName == null) "Private Group" else groupName,
                        groupMetadata = groupMetadata,
                        relayUrl = relayUrl,
                        groupId = groupId,
                        isJoined = isJoined,
                        canAddToList = canAddToList,
                        onAddToListClick = onAddToList,
                        isAdmin = isAdmin,
                        onJoinClick = onJoinGroup,
                        onLeaveClick = onLeaveGroup,
                        onTitleClick = onShowGroupInfo,
                        onEditClick = onEditGroup,
                        onDeleteClick = onDeleteGroup,
                        onManageMembersClick = onManageMembers,
                        showSubgroupControls = showSubgroupControls,
                        parentGroupName = parentGroupName,
                        onParentClick = onParentClick,
                        childCount = subgroupCount,
                        onInviteCodesClick = onInviteCodesClick,
                        isClosed = isClosed,
                        initialInviteCode = initialInviteCode,
                        pendingJoinRequestCount = pendingJoinRequestCount,
                        onJoinRequestsClick = onJoinRequestsClick,
                        onSearchClick = search.onToggle,
                        searchActive = search.active,
                        connectionState = connectionState,
                        compact = true,
                        navigationIcon = {
                            FrameMenuButton(onClick = onOpenDrawer)
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = { showMemberSheet = true },
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(
                                    Icons.Default.People,
                                    contentDescription = "Members",
                                    tint = NostrordColors.TextSecondary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                    )
                },
                containerColor = NostrordColors.Background,
            ) { paddingValues ->
                Box(
                    modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .pointerInput(Unit) {
                            // Right-edge leftward swipe opens the member sheet. Custom gesture
                            // (not detectHorizontalDragGestures) so it only claims/consumes drags
                            // that start at the right edge — left-edge drags stay free for the
                            // nav-drawer swipe handled in App.kt (issue #77). Kept as a Box modifier,
                            // not an overlay, so it never blocks the scroll-to-bottom button.
                            val rightZonePx = size.width * 0.15f
                            awaitPointerEventScope {
                                while (true) {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    if (down.position.x < size.width - rightZonePx) continue
                                    var totalX = 0f
                                    var totalY = 0f
                                    var triggered = false
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (triggered) {
                                            change.consume()
                                        } else {
                                            totalX += change.positionChange().x
                                            totalY += change.positionChange().y
                                            if (totalX < -80f && -totalX > abs(totalY)) {
                                                triggered = true
                                                change.consume()
                                                showMemberSheet = true
                                            }
                                        }
                                        if (!change.pressed) break
                                    }
                                }
                            }
                        },
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Live NIP-29 AV space (kind:39004); self-hiding when there is no room.
                        LiveSpaceSection(groupId = groupId)

                        Box(
                            modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) {
                            MessagesList(
                                groupId = groupId,
                                chatItems = chatItems,
                                messages = messages,
                                userMetadata = userMetadata,
                                reactions = reactions,
                                pendingReactions = pendingReactions,
                                messageStatus = messageStatus,
                                onRetrySend = onRetrySend,
                                onDismissFailed = onDismissFailed,
                                currentUserPubkey = currentUserPubkey,
                                isJoined = isJoined,
                                isReplying = replyingToMessage != null,
                                replyTargetId = replyingToMessage?.id,
                                isInitialLoading = isInitialLoading,
                                isPendingApproval = isPendingApproval,
                                isGroupRestricted = isGroupRestricted,
                                isLoadingMore = isLoadingMore,
                                hasMoreMessages = hasMoreMessages,
                                isLoadStalled = isLoadStalled,
                                onLoadMore = onLoadMore,
                                onRetryLoadMore = onRetryLoadMore,
                                onUsernameClick = onUserClick,
                                onReplyClick = onReplyClick,
                                onDeleteMessage = onDeleteMessage,
                                onReactionBadgeClick = onReactionBadgeClick,
                                onNavigateToGroup = onNavigateToGroup,
                                onReachedBottom = onReachedBottom,
                                onLeftBottom = onLeftBottom,
                                onSeenUpTo = onSeenUpTo,
                                onDividerSeen = onDividerSeen,
                                unreadFromOthersCount = unreadFromOthersCount,
                                targetMessageId = targetMessageId,
                                onTargetConsumed = onTargetConsumed,
                                onFetchTargetById = onFetchTargetById,
                                swipeToReplyEnabled = true,
                                // Already empty / null when search is inactive (query is "" → no matches),
                                // so no searchActive guard is needed here (parity with web).
                                searchHitIds = search.hitIds,
                                currentSearchHitId = search.currentHitId,
                                searchScrollNonce = search.scrollNonce,
                                searchActive = search.active,
                                searchBar = search.bar,
                            )
                        }

                        MessageInput(
                            isPendingApproval = isPendingApproval,
                            pendingRequestedAtSeconds = pendingRequestedAtSeconds,
                            onCancelJoinRequest = onCancelJoinRequest,
                            isJoined = isJoined,
                            isGroupClosed = isClosed,
                            selectedChannel = selectedChannel,
                            groupId = groupId,
                            groupName = groupName,
                            messageInput = messageInput,
                            onSendMessage = onSendMessage,
                            onJoinGroup = onJoinGroup,
                            groupMembers = groupMembers,
                            mentions = mentions,
                            onMentionsChange = onMentionsChange,
                            availableGroups = availableGroups,
                            groupMentions = groupMentions,
                            onGroupMentionsChange = onGroupMentionsChange,
                            replyingToMessage = replyingToMessage,
                            replyNonce = replyNonce,
                            replyingToMetadata = replyingToMessage?.let { userMetadata[it.pubkey] },
                            userMetadata = userMetadata,
                            onCancelReply = onCancelReply,
                            isSending = isSending,
                            onMediaUploaded = onMediaUploaded,
                            onOverlayVisibilityChange = onInputOverlayVisibilityChange,
                        )
                    }
                }
            }
            // Members slide-over from the right (web .member-sidebar drawer): the same
            // MemberSidebar as desktop, kept at its default 240dp width.
            MemberDrawerOverlay(
                visible = showMemberSheet,
                onDismiss = { showMemberSheet = false },
            ) {
                MemberSidebar(
                    members = groupMembers,
                    recentlyActiveMembers = recentlyActiveMembers,
                    isLoading = isMembersLoading,
                    isPendingApproval = isPendingApproval,
                    isGroupRestricted = isGroupRestricted,
                    isPublic = groupMetadata?.isPublic == true,
                    onMemberClick = { member ->
                        showMemberSheet = false
                        onUserClick(member.pubkey)
                    },
                    isCurrentUserAdmin = isCurrentUserAdmin,
                    currentUserPubkey = currentUserPubkey,
                    onRemoveMember = onRemoveMember,
                    onAddMember = onAddMember,
                    friends = friends,
                    groupRelay = groupRelay,
                    userGroupLists = userGroupLists,
                    onPrefetchTarget = onPrefetchTarget,
                )
            }
        } // Box
    } // CompositionLocalProvider
}
