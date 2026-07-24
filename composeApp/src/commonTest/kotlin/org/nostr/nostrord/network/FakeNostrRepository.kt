package org.nostr.nostrord.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.nostr.nostrord.auth.Account
import org.nostr.nostrord.network.RoleDefinition
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.network.managers.DmConversation
import org.nostr.nostrord.network.managers.DmMessage
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.network.managers.PendingGroupInvite
import org.nostr.nostrord.network.managers.ZapManager
import org.nostr.nostrord.network.outbox.Nip65Relay
import org.nostr.nostrord.nostr.Nip11RelayInfo
import org.nostr.nostrord.nostr.Nip46Client
import org.nostr.nostrord.utils.Result

/**
 * In-memory fake of [NostrRepositoryApi] for ViewModel unit tests.
 *
 * Defaults every state to a sensible empty/false value.
 * Individual tests can override the `Mutable*` fields or provide lambdas to control behavior.
 */
class FakeNostrRepository : NostrRepositoryApi {
    // -------------------------------------------------------------------------
    // Mutable state exposed for test setup
    // -------------------------------------------------------------------------

    val _isInitialized = MutableStateFlow(false)
    val _isLoggedIn = MutableStateFlow(false)
    val _isBunkerVerifying = MutableStateFlow(false)
    val _isBunkerConnected = MutableStateFlow(false)
    val _authUrl = MutableStateFlow<String?>(null)
    val _currentRelayUrl = MutableStateFlow("wss://relay.example.com")
    val _connectionState = MutableStateFlow<ConnectionManager.ConnectionState>(ConnectionManager.ConnectionState.Disconnected)
    val _groups = MutableStateFlow<List<GroupMetadata>>(emptyList())
    val _messages = MutableStateFlow<Map<String, List<NostrGroupClient.NostrMessage>>>(emptyMap())
    val _joinedGroups = MutableStateFlow<Set<String>>(emptySet())
    val _isLoadingMore = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val _hasMoreMessages = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val _reactions = MutableStateFlow<Map<String, Map<String, GroupManager.ReactionInfo>>>(emptyMap())
    val _groupMembers = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val _groupAdmins = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val _userMetadata = MutableStateFlow<Map<String, UserMetadata>>(emptyMap())
    val _cachedEvents = MutableStateFlow<Map<String, CachedEvent>>(emptyMap())
    val _unreadCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val _userRelayList = MutableStateFlow<List<Nip65Relay>>(emptyList())
    val _relayMetadata = MutableStateFlow<Map<String, Nip11RelayInfo>>(emptyMap())
    val _unreachableRelays = MutableStateFlow<Set<String>>(emptySet())
    val _loadingMembers = MutableStateFlow<Set<String>>(emptySet())

    // Configurable behaviour
    var initializeAction: suspend () -> Unit = { _isInitialized.value = true }
    var loginSuspendAction: suspend (String, String) -> Result<Unit> = { _, _ ->
        _isLoggedIn.value = true
        Result.Success(Unit)
    }
    var loginWithNip07Action: suspend (String) -> Result<Unit> = {
        _isLoggedIn.value = true
        Result.Success(Unit)
    }
    var loginWithBunkerAction: suspend (String) -> Result<String> = { Result.Success("pubkey") }
    var leaveGroupAction: suspend (String, String?) -> Result<Unit> = { _, _ -> Result.Success(Unit) }
    var sendMessageAction: suspend (String, String, String?, Map<String, String>, String?) -> Result<Unit> =
        { _, _, _, _, _ -> Result.Success(Unit) }
    var updateProfileMetadataAction: suspend (String?, String?, String?, String?, String?, String?, String?, String?) -> Result<Unit> =
        { _, _, _, _, _, _, _, _ -> Result.Success(Unit) }
    var fakePublicKey: String? = null
    var fakePrivateKey: String? = null

    // Call log — tests can assert which methods were called
    val calls = mutableListOf<String>()

    // -------------------------------------------------------------------------
    // NostrRepositoryApi
    // -------------------------------------------------------------------------

    override val isInitialized: StateFlow<Boolean> = _isInitialized
    override val isLoggedIn: StateFlow<Boolean> = _isLoggedIn
    val _activePubkey = MutableStateFlow<String?>(null)
    override val activePubkey: StateFlow<String?> = _activePubkey
    override val isBunkerVerifying: StateFlow<Boolean> = _isBunkerVerifying
    override val isBunkerConnected: StateFlow<Boolean> = _isBunkerConnected
    override val bunkerState: StateFlow<BunkerState> = MutableStateFlow(BunkerState.Inactive)
    override val authUrl: StateFlow<String?> = _authUrl
    override val currentRelayUrl: StateFlow<String> = _currentRelayUrl
    override val connectionState: StateFlow<ConnectionManager.ConnectionState> = _connectionState
    override val groups: StateFlow<List<GroupMetadata>> = _groups
    val _groupsByRelay = MutableStateFlow<Map<String, List<GroupMetadata>>>(emptyMap())
    override val groupsByRelay: StateFlow<Map<String, List<GroupMetadata>>> = _groupsByRelay
    override val messages: StateFlow<Map<String, List<NostrGroupClient.NostrMessage>>> = _messages
    val _messageStatus = MutableStateFlow<Map<String, GroupManager.MessageStatus>>(emptyMap())
    override val messageStatus: StateFlow<Map<String, GroupManager.MessageStatus>> = _messageStatus
    val _threadRoots = MutableStateFlow<Map<String, List<NostrGroupClient.NostrMessage>>>(emptyMap())
    override val threadRoots: StateFlow<Map<String, List<NostrGroupClient.NostrMessage>>> = _threadRoots
    val _threadReplies = MutableStateFlow<Map<String, List<NostrGroupClient.NostrMessage>>>(emptyMap())
    override val threadReplies: StateFlow<Map<String, List<NostrGroupClient.NostrMessage>>> = _threadReplies
    val _threadsLoaded = MutableStateFlow<Set<String>>(emptySet())
    override val threadsLoaded: StateFlow<Set<String>> = _threadsLoaded
    val _joinedGroupsByRelay = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    override val joinedGroups: StateFlow<Set<String>> = _joinedGroups
    override val joinedGroupsByRelay: StateFlow<Map<String, Set<String>>> = _joinedGroupsByRelay
    override val isLoadingMore: StateFlow<Map<String, Boolean>> = _isLoadingMore
    override val hasMoreMessages: StateFlow<Map<String, Boolean>> = _hasMoreMessages
    val _groupStates =
        MutableStateFlow<Map<String, org.nostr.nostrord.network.managers.GroupLoadingState>>(emptyMap())
    override val groupStates: StateFlow<Map<String, org.nostr.nostrord.network.managers.GroupLoadingState>> =
        _groupStates
    override val groupsAwaitingAuthRead: StateFlow<Set<String>> = MutableStateFlow(emptySet())

    override suspend fun resetGroupLoadingState(groupId: String) {}
    override val reactions: StateFlow<Map<String, Map<String, GroupManager.ReactionInfo>>> = _reactions
    override val groupMembers: StateFlow<Map<String, List<String>>> = _groupMembers
    val _groupMembersByRelay = MutableStateFlow<Map<String, Map<String, List<String>>>>(emptyMap())
    override val groupMembersByRelay: StateFlow<Map<String, Map<String, List<String>>>> = _groupMembersByRelay
    val _groupAdminsByRelay = MutableStateFlow<Map<String, Map<String, List<String>>>>(emptyMap())
    override val groupAdminsByRelay: StateFlow<Map<String, Map<String, List<String>>>> = _groupAdminsByRelay
    override val groupRolesByRelay: StateFlow<Map<String, Map<String, List<RoleDefinition>>>> = MutableStateFlow(emptyMap())
    override val pendingApprovalSince: StateFlow<Map<String, Long>> = MutableStateFlow(emptyMap())
    override val groupAdmins: StateFlow<Map<String, List<String>>> = _groupAdmins
    override val userMetadata: StateFlow<Map<String, UserMetadata>> = _userMetadata
    override val cachedEvents: StateFlow<Map<String, CachedEvent>> = _cachedEvents
    override val unreadCounts: StateFlow<Map<String, Int>> = _unreadCounts
    override val dmConversations: StateFlow<List<DmConversation>> = MutableStateFlow(emptyList())
    override val dmMessagesByPeer: StateFlow<Map<String, List<DmMessage>>> = MutableStateFlow(emptyMap())
    override val dmUnreadByPeer: StateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
    override val totalDmUnread: StateFlow<Int> = MutableStateFlow(0)
    val myDmRelaysFlow = MutableStateFlow<List<String>>(emptyList())
    override val myDmRelays: StateFlow<List<String>> = myDmRelaysFlow
    val dmRelaysByPubkeyFlow = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    override val dmRelaysByPubkey: StateFlow<Map<String, List<String>>> = dmRelaysByPubkeyFlow
    override val dmMessageStatus: StateFlow<Map<String, GroupManager.MessageStatus>> = MutableStateFlow(emptyMap())
    override fun requestPeerDmRelays(pubkey: String) {}
    override val latestMessageTimestamps: StateFlow<Map<String, Long>> = MutableStateFlow(emptyMap())
    override val totalUnread: StateFlow<Int> = MutableStateFlow(0)
    override val unreadByRelay: StateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
    override val userRelayList: StateFlow<List<Nip65Relay>> = _userRelayList
    override val relayMetadata: StateFlow<Map<String, Nip11RelayInfo>> = _relayMetadata
    override val unreachableRelays: StateFlow<Set<String>> = _unreachableRelays
    override val loadingMembers: StateFlow<Set<String>> = _loadingMembers

    override fun forceInitialized() {
        _isInitialized.value = true
    }

    override suspend fun initialize() {
        calls += "initialize"
        initializeAction()
    }

    override fun clearAuthUrl() {
        _authUrl.value = null
    }

    override fun getPublicKey(): String? = fakePublicKey

    override fun getPrivateKey(): String? = fakePrivateKey

    override fun isUsingBunker(): Boolean = false

    override fun isBunkerReady(): Boolean = false

    override suspend fun ensureBunkerConnected(): Boolean = true

    override fun forgetBunkerConnection() {}

    override suspend fun loginSuspend(
        privKey: String,
        pubKey: String,
        isNewIdentity: Boolean,
        ncryptsec: String?,
    ): Result<Unit> {
        calls += "loginSuspend"
        return loginSuspendAction(privKey, pubKey)
    }

    val _pendingUnlockAccount = MutableStateFlow<Account?>(null)
    override val pendingUnlockAccount: StateFlow<Account?> = _pendingUnlockAccount

    override fun clearPendingUnlock() {
        _pendingUnlockAccount.value = null
    }

    override suspend fun loginWithNip07(pubkey: String): Result<Unit> {
        calls += "loginWithNip07"
        return loginWithNip07Action(pubkey)
    }

    override suspend fun loginWithBunker(bunkerUrl: String): Result<String> {
        calls += "loginWithBunker"
        return loginWithBunkerAction(bunkerUrl)
    }

    override val defaultNostrConnectRelays: List<String> = listOf("wss://relay.nsec.app")

    override suspend fun createNostrConnectSession(relays: List<String>): Pair<String, Nip46Client> = error("createNostrConnectSession not implemented in fake")

    override suspend fun completeNostrConnectLogin(
        client: Nip46Client,
        relays: List<String>,
    ): String = error("completeNostrConnectLogin not implemented in fake")

    override suspend fun logout() {
        calls += "logout"
        _isLoggedIn.value = false
    }

    override suspend fun reloadForActiveAccount() {
        calls += "reloadForActiveAccount"
    }

    var removeRelayAction: suspend (String) -> Unit = { url ->
        _joinedGroupsByRelay.update { it - url }
    }

    override suspend fun connect() {}

    override suspend fun reconnect(): Boolean = true

    override fun triggerReconnect() {}

    override suspend fun switchRelay(newRelayUrl: String) {
        _currentRelayUrl.value = newRelayUrl
    }

    override suspend fun removeRelay(url: String) {
        calls += "removeRelay:$url"
        removeRelayAction(url)
    }

    override suspend fun disconnect() {}

    override suspend fun createGroup(
        name: String,
        about: String?,
        relayUrl: String,
        isPrivate: Boolean,
        isClosed: Boolean,
        isRestricted: Boolean,
        isHidden: Boolean,
        picture: String?,
        customGroupId: String?,
    ): Result<String> = Result.Success(customGroupId ?: "fake-group-id")

    override suspend fun createSubgroup(
        parentGroupId: String,
        name: String,
        about: String?,
        relayUrl: String,
        isPrivate: Boolean,
        isClosed: Boolean,
        isRestricted: Boolean,
        isHidden: Boolean,
        picture: String?,
        customGroupId: String?,
    ): Result<String> = Result.Success(customGroupId ?: "fake-subgroup-id")

    override suspend fun joinGroup(
        groupId: String,
        inviteCode: String?,
    ): Result<Unit> = Result.Success(Unit)

    override suspend fun requestGroupThreads(groupId: String): Boolean {
        calls += "requestGroupThreads:$groupId"
        return true
    }

    override fun closeThreadSubscriptions(groupId: String) {
        calls += "closeThreadSubscriptions:$groupId"
    }

    override suspend fun fetchThread(groupId: String, rootId: String) {
        calls += "fetchThread:$groupId:$rootId"
    }

    override suspend fun createThread(groupId: String, title: String, content: String): Result<Unit> {
        calls += "createThread:$groupId:$title"
        return Result.Success(Unit)
    }

    override suspend fun sendThreadReply(
        groupId: String,
        root: NostrGroupClient.NostrMessage,
        parent: NostrGroupClient.NostrMessage,
        content: String,
    ): Result<Unit> {
        calls += "sendThreadReply:$groupId:${root.id}:${parent.id}"
        return Result.Success(Unit)
    }

    override fun markOptimisticJoin(relayUrl: String, groupId: String): Boolean {
        if (groupId in (_joinedGroupsByRelay.value[relayUrl] ?: emptySet())) return false
        _joinedGroupsByRelay.update { current ->
            current + (relayUrl to ((current[relayUrl] ?: emptySet()) + groupId))
        }
        return true
    }

    override fun revertOptimisticJoin(relayUrl: String, groupId: String) {
        _joinedGroupsByRelay.update { current ->
            current + (relayUrl to ((current[relayUrl] ?: emptySet()) - groupId))
        }
    }

    override suspend fun leaveGroup(
        groupId: String,
        reason: String?,
    ): Result<Unit> = leaveGroupAction(groupId, reason)

    override suspend fun forgetGroup(
        groupId: String,
        relayUrl: String,
    ): Result<Unit> {
        calls += "forgetGroup:$groupId:$relayUrl"
        _joinedGroups.value = _joinedGroups.value - groupId
        _joinedGroupsByRelay.update { current ->
            val updated: Set<String> = (current[relayUrl] ?: emptySet()) - groupId
            current + (relayUrl to updated)
        }
        return Result.Success(Unit)
    }

    override val orphanedJoinedByRelay: StateFlow<Map<String, Set<String>>> =
        MutableStateFlow(emptyMap())

    override suspend fun editGroup(
        groupId: String,
        name: String,
        about: String?,
        isPrivate: Boolean,
        isClosed: Boolean,
        isRestricted: Boolean,
        isHidden: Boolean,
        picture: String?,
        parentOp: org.nostr.nostrord.network.managers.GroupManager.ParentOp?,
    ): Result<Unit> = Result.Success(Unit)

    override suspend fun deleteGroup(groupId: String): Result<Unit> = Result.Success(Unit)

    override suspend fun reorderChildren(
        groupId: String,
        orderedIds: List<String>,
    ): Result<Unit> = Result.Success(Unit)

    override fun isGroupJoined(groupId: String): Boolean = joinedGroups.value.contains(groupId)

    override suspend fun requestGroupMessages(
        groupId: String,
        channel: String?,
    ) {}

    override suspend fun requestGroupMembers(groupId: String) {}

    override suspend fun requestGroupAdmins(groupId: String) {}

    override suspend fun requestPendingJoinRequests(groupId: String) {}

    override fun fetchRelayMetadata(relayUrl: String) {}

    override suspend fun refreshGroupMetadata(groupId: String) {}

    override val childrenByParent: StateFlow<Map<String, Set<String>>> = MutableStateFlow(emptyMap())

    override suspend fun loadMoreMessages(
        groupId: String,
        channel: String?,
    ): Boolean = false

    override suspend fun retryStalledLoad(
        groupId: String,
        channel: String?,
    ): Boolean = false

    override suspend fun sendMessage(
        groupId: String,
        content: String,
        channel: String?,
        mentions: Map<String, String>,
        replyToMessageId: String?,
        extraTags: List<List<String>>,
    ): Result<Unit> = sendMessageAction(groupId, content, channel, mentions, replyToMessageId)

    var sendDmAction: (String, String) -> Result<Unit> = { _, _ -> Result.Success(Unit) }

    override suspend fun sendDm(recipientPubkey: String, content: String): Result<Unit> = sendDmAction(recipientPubkey, content)

    override suspend fun markDmRead(peerPubkey: String) {}

    override suspend fun publishDmRelayList(relays: List<String>): Result<Unit> {
        myDmRelaysFlow.value = relays
        return Result.Success(Unit)
    }

    var retrySendAction: (String) -> Unit = {}

    override fun retrySend(eventId: String) = retrySendAction(eventId)

    var dismissFailedAction: (String, String) -> Unit = { _, _ -> }

    override fun dismissFailed(groupId: String, eventId: String) = dismissFailedAction(groupId, eventId)

    override suspend fun deleteMessage(
        groupId: String,
        messageId: String,
    ): Result<Unit> = Result.Success(Unit)

    override fun getMessagesForGroup(groupId: String): List<NostrGroupClient.NostrMessage> = messages.value[groupId] ?: emptyList()

    var relayHints = mutableMapOf<String, String>()

    override fun setGroupRelayHint(
        groupId: String,
        relayUrl: String,
    ) {
        relayHints[groupId] = relayUrl
    }

    override fun markGroupAsRead(groupId: String) {}

    override fun markGroupAsReadUpTo(groupId: String, timestamp: Long) {}

    override fun getUnreadCount(groupId: String): Int = unreadCounts.value[groupId] ?: 0

    override fun getLastReadTimestamp(groupId: String): Long? = null

    override suspend fun requestUserMetadata(pubkeys: Set<String>, forceStale: Boolean) {}

    val _userGroupLists = MutableStateFlow<Map<String, List<UserGroupRef>>>(emptyMap())
    override val userGroupLists: StateFlow<Map<String, List<UserGroupRef>>> = _userGroupLists

    override suspend fun requestUserGroupList(pubkey: String) {}

    var fetchUserGroupListsCalls = mutableListOf<Set<String>>()

    override suspend fun fetchUserGroupLists(pubkeys: Set<String>) {
        fetchUserGroupListsCalls.add(pubkeys)
    }

    val _following = MutableStateFlow<Set<String>>(emptySet())
    override val following: StateFlow<Set<String>> = _following

    val _contactListLoaded = MutableStateFlow(true)
    override val contactListLoaded: StateFlow<Boolean> = _contactListLoaded

    /** Times [requestContactList] was called, so a test can assert the screen re-fetches
     *  the contact list on cold start and again on every account switch. */
    var requestContactListCount = 0
        private set

    override suspend fun requestContactList() {
        requestContactListCount++
    }

    override suspend fun followUser(pubkey: String): Result<Unit> {
        _following.value = _following.value + pubkey
        return Result.Success(Unit)
    }

    override suspend fun unfollowUser(pubkey: String): Result<Unit> {
        _following.value = _following.value - pubkey
        return Result.Success(Unit)
    }

    override suspend fun followUsers(pubkeys: Set<String>): Result<Unit> {
        _following.value = _following.value + pubkeys.filter { it.isNotBlank() }
        return Result.Success(Unit)
    }

    val _mutedPubkeys = MutableStateFlow<Set<String>>(emptySet())
    override val mutedPubkeys: StateFlow<Set<String>> = _mutedPubkeys

    override suspend fun muteUser(pubkey: String): Result<Unit> {
        calls += "muteUser:$pubkey"
        if (pubkey.isNotBlank() && pubkey != fakePublicKey) {
            _mutedPubkeys.value = _mutedPubkeys.value + pubkey
        }
        return Result.Success(Unit)
    }

    override suspend fun unmuteUser(pubkey: String): Result<Unit> {
        calls += "unmuteUser:$pubkey"
        _mutedPubkeys.value = _mutedPubkeys.value - pubkey
        return Result.Success(Unit)
    }

    override suspend fun updateProfileMetadata(
        displayName: String?,
        name: String?,
        about: String?,
        picture: String?,
        banner: String?,
        nip05: String?,
        lud16: String?,
        website: String?,
    ): Result<Unit> = updateProfileMetadataAction(displayName, name, about, picture, banner, nip05, lud16, website)

    override suspend fun requestEventById(
        eventId: String,
        relayHints: List<String>,
        author: String?,
    ) {}

    override suspend fun requestAddressableEvent(
        kind: Int,
        pubkey: String,
        identifier: String,
        relays: List<String>,
    ) {}

    override suspend fun requestQuotedEvent(eventId: String) {}

    override suspend fun requestRelayLists(pubkeys: Set<String>) {}

    override fun getRelayListForPubkey(pubkey: String): List<Nip65Relay>? = null

    override fun selectOutboxRelays(
        authors: List<String>,
        taggedPubkeys: List<String>,
        explicitRelays: List<String>,
    ): List<String> = emptyList()

    override suspend fun addRelay(url: String) {}

    override fun dismissDeepLinkRelay() {}

    override fun onForeground() {}

    override fun onBackground() {}

    override fun onDestroy() {}

    override fun setActiveGroup(groupId: String?) {}

    var addUserResult: Result<Unit> = Result.Success(Unit)
    var addUserCalls = mutableListOf<Triple<String, String, List<String>>>()

    /** When set, addUser suspends until completed — lets tests observe in-flight state. */
    var addUserGate: CompletableDeferred<Unit>? = null

    override suspend fun addUser(
        groupId: String,
        targetPubkey: String,
        roles: List<String>,
        notifyViaDm: Boolean,
    ): Result<Unit> {
        addUserCalls.add(Triple(groupId, targetPubkey, roles))
        addUserGate?.await()
        return addUserResult
    }

    var removeUserResult: Result<Unit> = Result.Success(Unit)

    override suspend fun removeUser(
        groupId: String,
        targetPubkey: String,
    ): Result<Unit> = removeUserResult

    override suspend fun rejectJoinRequest(
        groupId: String,
        joinRequestEventId: String,
    ): Result<Unit> = Result.Success(Unit)

    override suspend fun createInviteCode(groupId: String): Result<String> = Result.Success("fake-invite")

    override suspend fun revokeInviteCode(
        groupId: String,
        eventId: String,
    ): Result<Unit> = Result.Success(Unit)

    override val groupRoles: StateFlow<Map<String, List<RoleDefinition>>> = MutableStateFlow(emptyMap())
    override val restrictedGroups: StateFlow<Map<String, String>> = MutableStateFlow(emptyMap())
    override val leftGroups: StateFlow<Set<String>> = MutableStateFlow(emptySet())

    val pendingGroupInvitesFlow = MutableStateFlow<Map<String, PendingGroupInvite>>(emptyMap())
    override val pendingGroupInvites: StateFlow<Map<String, PendingGroupInvite>> = pendingGroupInvitesFlow
    val acceptedInvites = mutableListOf<String>()

    override suspend fun acceptGroupInvite(groupId: String) {
        acceptedInvites += groupId
        pendingGroupInvitesFlow.update { it - groupId }
    }

    override suspend fun sendReaction(
        groupId: String,
        targetEventId: String,
        targetPubkey: String,
        emoji: String,
    ): Result<Unit> = Result.Success(Unit)

    override val zaps: StateFlow<Map<String, ZapManager.ZapInfo>> = MutableStateFlow(emptyMap())

    override suspend fun requestZapInvoice(
        recipientPubkey: String,
        amountSats: Long,
        comment: String,
        eventId: String?,
    ): Result<ZapManager.ZapInvoice> = Result.Success(
        ZapManager.ZapInvoice(
            bolt11 = "lnbc10n1fake",
            amountMsats = amountSats * 1_000L,
            recipientPubkey = recipientPubkey,
            eventId = eventId,
            comment = comment,
        ),
    )

    override suspend fun watchZapPayment(
        bolt11: String,
        recipientPubkey: String,
        eventId: String?,
    ): Boolean = false

    override suspend fun publishRelayList(relays: List<Nip65Relay>): Result<Unit> = Result.Success(Unit)

    override val isDiscoveringRelays: StateFlow<Boolean> = MutableStateFlow(false)
    override val pendingDeepLinkRelay: StateFlow<String?> = MutableStateFlow(null)
    override val loadingRelays: StateFlow<Set<String>> = MutableStateFlow(emptySet())
    override val restrictedRelays: StateFlow<Map<String, String>> = MutableStateFlow(emptyMap())
    override val kind10009Relays: StateFlow<Set<String>> = MutableStateFlow(emptySet())
    override val groupTagRelays: StateFlow<Set<String>> = MutableStateFlow(emptySet())

    override suspend fun fetchGroupPreview(
        groupId: String,
        relayUrl: String,
    ) {}

    var fetchGroupPreviewsCalls = mutableListOf<Map<String, Set<String>>>()

    override suspend fun fetchGroupPreviews(relayToGroups: Map<String, Set<String>>) {
        fetchGroupPreviewsCalls.add(relayToGroups)
    }

    var fetchGroupsMembersCalls = mutableListOf<Map<String, Set<String>>>()

    override suspend fun fetchGroupsMembers(relayToGroups: Map<String, Set<String>>) {
        fetchGroupsMembersCalls.add(relayToGroups)
    }

    override suspend fun awaitRelayAuthSettled(relayUrl: String) {}

    override val fullGroupListFetchedRelays: StateFlow<Set<String>> = MutableStateFlow(emptySet())
    override val completeGroupLoadRelays: StateFlow<Set<String>> = MutableStateFlow(emptySet())

    override fun setGroupFetchLazy(
        relayUrl: String,
        lazy: Boolean,
    ) {}

    override fun isGroupFetchLazy(relayUrl: String): Boolean = false

    override suspend fun requestFullGroupListForRelay(relayUrl: String) {}

    override suspend fun fetchGroupMessageById(
        groupId: String,
        messageId: String,
    ) {}
}
