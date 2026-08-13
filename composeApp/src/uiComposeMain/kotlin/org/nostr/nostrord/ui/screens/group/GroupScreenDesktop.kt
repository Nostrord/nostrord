package org.nostr.nostrord.ui.screens.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.NostrGroupClient.NostrMessage
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.ui.components.chat.AvOnlyComposerNote
import org.nostr.nostrord.ui.components.chat.LiveSpaceSection
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

/**
 * Desktop group screen layout.
 *
 * Two-column layout (the groups rail is provided by the parent AppFrame):
 * ┌───────────────────────┬──────────────┐
 * │       Messages        │    Members   │
 * │                       │    Sidebar   │
 * │       (flex)          │    (240dp)   │
 * └───────────────────────┴──────────────┘
 *
 * Note: the 72dp groups rail is handled by the parent AppFrame,
 * not this component.
 */
@Composable
fun GroupScreenDesktop(
    groupId: String,
    groupName: String?,
    relayUrl: String = "",
    groupMetadata: GroupMetadata?,
    selectedChannel: String,
    onChannelSelect: (String) -> Unit,
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
    showMemberSidebar: Boolean = true,
    onToggleMembers: () -> Unit = {},
    showMemberSheet: Boolean = false,
    onShowMemberSheet: (Boolean) -> Unit = {},
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
    val search = rememberChatSearchState(
        groupId = groupId,
        messages = messages,
        userMetadata = userMetadata,
        hasMoreMessages = hasMoreMessages,
        isLoadingMore = isLoadingMore,
        onLoadMore = onLoadMore,
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier =
                Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .background(NostrordColors.Background),
            ) {
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
                    trailingIcon = {
                        // Members toggle (prototype): highlighted while the column is visible.
                        IconButton(
                            onClick = onToggleMembers,
                            modifier = Modifier.size(30.dp),
                            // Active fill via the IconButton container so it matches the hover
                            // state-layer circle exactly (a manual .background fills the full box,
                            // which reads larger than the hover).
                            colors =
                            if (showMemberSidebar) {
                                IconButtonDefaults.iconButtonColors(containerColor = NostrordColors.SurfaceVariant)
                            } else {
                                IconButtonDefaults.iconButtonColors()
                            },
                        ) {
                            Icon(
                                Icons.Default.People,
                                contentDescription = "Members",
                                tint = if (showMemberSidebar) NostrordColors.TextPrimary else NostrordColors.TextSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                )

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
                        hostRelayUrl = relayUrl,
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
                        // Already empty / null when search is inactive (query is "" → no matches), so no
                        // searchActive guard is needed here (parity with web).
                        searchHitIds = search.hitIds,
                        currentSearchHitId = search.currentHitId,
                        searchScrollNonce = search.scrollNonce,
                        searchActive = search.active,
                        searchBar = search.bar,
                    )
                }

                // AV-only group: the relay accepts no text events, so there is nothing to compose.
                if (groupMetadata?.isAvOnly == true) {
                    AvOnlyComposerNote()
                } else {
                    MessageInput(
                        isPendingApproval = isPendingApproval,
                        pendingRequestedAtSeconds = pendingRequestedAtSeconds,
                        onCancelJoinRequest = onCancelJoinRequest,
                        isJoined = isJoined,
                        isGroupClosed = isClosed,
                        selectedChannel = selectedChannel,
                        groupId = groupId,
                        hostRelayUrl = relayUrl,
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

            if (showMemberSidebar) {
                MemberSidebar(
                    members = groupMembers,
                    recentlyActiveMembers = recentlyActiveMembers,
                    isLoading = isMembersLoading,
                    isPendingApproval = isPendingApproval,
                    isGroupRestricted = isGroupRestricted,
                    isPublic = groupMetadata?.isPublic == true,
                    onMemberClick = { member -> onUserClick(member.pubkey) },
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
        }

        // When the persistent column is hidden (medium width), the members panel slides in
        // from the right instead of up from the bottom (matches the web .member-sidebar drawer).
        MemberDrawerOverlay(
            visible = showMemberSheet && !showMemberSidebar,
            onDismiss = { onShowMemberSheet(false) },
        ) {
            MemberSidebar(
                members = groupMembers,
                recentlyActiveMembers = recentlyActiveMembers,
                isLoading = isMembersLoading,
                isPendingApproval = isPendingApproval,
                isGroupRestricted = isGroupRestricted,
                isPublic = groupMetadata?.isPublic == true,
                onMemberClick = { member ->
                    onShowMemberSheet(false)
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
    }
}
