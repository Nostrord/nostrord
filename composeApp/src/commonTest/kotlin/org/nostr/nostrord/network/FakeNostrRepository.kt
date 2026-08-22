package org.nostr.nostrord.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.nostr.nostrord.auth.Account
import org.nostr.nostrord.network.RoleDefinition
import org.nostr.nostrord.network.livekit.LiveKitCredentials
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.network.managers.DmArchiveManager
import org.nostr.nostrord.network.managers.DmConversation
import org.nostr.nostrord.network.managers.DmEncryptionManager
import org.nostr.nostrord.network.managers.DmFileManager
import org.nostr.nostrord.network.managers.DmMessage
import org.nostr.nostrord.network.managers.DmPairingManager
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.network.managers.PendingGroupInvite
import org.nostr.nostrord.network.managers.ZapManager
import org.nostr.nostrord.network.outbox.Nip65Relay
import org.nostr.nostrord.nostr.DmOutgoingFile
import org.nostr.nostrord.nostr.Nip11RelayInfo
import org.nostr.nostrord.nostr.Nip17File
import org.nostr.nostrord.nostr.Nip46Client
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.groupKey

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
    val _unreadByGroupKey = MutableStateFlow<Map<String, Int>>(emptyMap())
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
    var loginWithAmberAction: suspend (String, String?) -> Result<Unit> = { _, _ ->
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
    val _privateGroupEntries = MutableStateFlow<Set<Pair<String, String>>>(emptySet())
    override val privateGroupEntries: StateFlow<Set<Pair<String, String>>> = _privateGroupEntries
    val _privateListSectionOpaque = MutableStateFlow(false)
    override val privateListSectionOpaque: StateFlow<Boolean> = _privateListSectionOpaque

    /** Set to fail the next [setGroupListedPrivately], as a signer refusing to encrypt would. */
    var privateToggleFails: Boolean = false
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
    val _pendingApprovalSince = MutableStateFlow<Map<String, Long>>(emptyMap())
    override val pendingApprovalSince: StateFlow<Map<String, Long>> = _pendingApprovalSince
    override val groupAdmins: StateFlow<Map<String, List<String>>> = _groupAdmins
    override val userMetadata: StateFlow<Map<String, UserMetadata>> = _userMetadata
    override val cachedEvents: StateFlow<Map<String, CachedEvent>> = _cachedEvents
    override val unreadByGroupKey: StateFlow<Map<String, Int>> = _unreadByGroupKey
    override val dmConversations: StateFlow<List<DmConversation>> = MutableStateFlow(emptyList())
    val dmMessagesByPeerFlow = MutableStateFlow<Map<String, List<DmMessage>>>(emptyMap())
    override val dmMessagesByPeer: StateFlow<Map<String, List<DmMessage>>> = dmMessagesByPeerFlow
    override val dmUnreadByPeer: StateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
    override val totalDmUnread: StateFlow<Int> = MutableStateFlow(0)
    val lastDmPeerFlow = MutableStateFlow<String?>(null)
    override val lastDmPeer: StateFlow<String?> = lastDmPeerFlow

    override fun rememberDmPeer(pubkey: String) {
        lastDmPeerFlow.value = pubkey
    }

    val myDmRelaysFlow = MutableStateFlow<List<String>>(emptyList())
    override val myDmRelays: StateFlow<List<String>> = myDmRelaysFlow
    val dmRelaysByPubkeyFlow = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    override val dmRelaysByPubkey: StateFlow<Map<String, List<String>>> = dmRelaysByPubkeyFlow
    override val dmMessageStatus: StateFlow<Map<String, GroupManager.MessageStatus>> = MutableStateFlow(emptyMap())
    val dmSyncingFlow = MutableStateFlow(false)
    override val dmSyncing: StateFlow<Boolean> = dmSyncingFlow
    val dmReactionsFlow = MutableStateFlow<Map<String, Map<String, GroupManager.ReactionInfo>>>(emptyMap())
    override val dmReactions: StateFlow<Map<String, Map<String, GroupManager.ReactionInfo>>> = dmReactionsFlow

    /** Files sent through [sendDmFile], as (recipient, mimeType, size). */
    val sentDmFiles = mutableListOf<Triple<String, String, Int>>()

    override suspend fun sendDmFile(
        recipientPubkey: String,
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        width: Int?,
        height: Int?,
    ): Result<Unit> {
        sentDmFiles += Triple(recipientPubkey, mimeType, bytes.size)
        return Result.Success(Unit)
    }

    override suspend fun uploadDmFile(
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        width: Int?,
        height: Int?,
    ): Result<DmOutgoingFile> = Result.Success(
        DmOutgoingFile(
            url = "https://fake/$filename",
            mimeType = mimeType,
            keyHex = "a".repeat(64),
            nonceHex = "b".repeat(24),
            originalHashHex = "c".repeat(64),
            size = bytes.size.toLong(),
            width = width,
            height = height,
        ),
    )

    /** Uploads sent through [sendDmUploadedFile], as (recipient, url). */
    val sentDmUploads = mutableListOf<Pair<String, String>>()

    override suspend fun sendDmUploadedFile(
        recipientPubkey: String,
        file: DmOutgoingFile,
    ): Result<Unit> {
        sentDmUploads += recipientPubkey to file.url
        return Result.Success(Unit)
    }

    /** Reactions sent through [sendDmReaction], as (messageId, emoji). */
    val sentDmReactions = mutableListOf<Pair<String, String>>()

    override suspend fun sendDmReaction(
        recipientPubkey: String,
        messageId: String,
        emoji: String,
        emojiUrl: String?,
    ): Result<Unit> {
        sentDmReactions += messageId to emoji
        return Result.Success(Unit)
    }

    val dmFileStatesFlow = MutableStateFlow<Map<String, DmFileManager.FileState>>(emptyMap())
    override val dmFileStates: StateFlow<Map<String, DmFileManager.FileState>> = dmFileStatesFlow

    /** Rumor ids the screen asked to load, in call order. */
    val loadedDmFiles = mutableListOf<String>()

    override fun loadDmFile(rumorId: String, file: Nip17File) {
        loadedDmFiles += rumorId
    }

    override fun retryDmFile(rumorId: String, file: Nip17File) {
        loadedDmFiles += rumorId
    }

    override fun requestPeerDmRelays(pubkey: String) {}

    val dmEncryptionStateFlow = MutableStateFlow<DmEncryptionManager.State>(DmEncryptionManager.State.Unavailable)
    override val dmEncryptionState: StateFlow<DmEncryptionManager.State> = dmEncryptionStateFlow
    var enableDmEncryptionResult: Result<Unit> = Result.Success(Unit)
    var importDmEncryptionKeyResult: Boolean = true
    var exportedDmEncryptionKey: String? = null

    override suspend fun refreshDmEncryptionState() {
        calls += "refreshDmEncryptionState"
    }

    override suspend fun enableDmEncryption(): Result<Unit> = enableDmEncryptionResult

    override suspend fun disableDmEncryption(): Result<Unit> = Result.Success(Unit)

    var rotateDmEncryptionKeyResult: Result<Unit> = Result.Success(Unit)

    override suspend fun rotateDmEncryptionKey(): Result<Unit> {
        calls += "rotateDmEncryptionKey"
        return rotateDmEncryptionKeyResult
    }

    var exportedDmHistory: String = ""
    var importDmHistoryResult: Result<DmImportSummary> = Result.Success(DmImportSummary(0, 0, 0))
    var importedDmHistoryText: String? = null

    override suspend fun exportDmHistory(): String {
        calls += "exportDmHistory"
        return exportedDmHistory
    }

    override suspend fun importDmHistory(text: String): Result<DmImportSummary> {
        calls += "importDmHistory"
        importedDmHistoryText = text
        return importDmHistoryResult
    }

    var resetDmEncryptionKeyResult: Result<Unit> = Result.Success(Unit)

    override suspend fun resetDmEncryptionKey(): Result<Unit> {
        calls += "resetDmEncryptionKey"
        return resetDmEncryptionKeyResult
    }

    override fun importDmEncryptionKey(privateKeyHex: String): Boolean = importDmEncryptionKeyResult

    override fun exportDmEncryptionKey(): String? = exportedDmEncryptionKey

    val dmArchiveProgressFlow = MutableStateFlow(DmArchiveManager.Progress())
    override val dmArchiveProgress: StateFlow<DmArchiveManager.Progress> = dmArchiveProgressFlow
    var archivableCount: Int = 0
    var archiveDmHistoryResult: Result<Unit> = Result.Success(Unit)

    override suspend fun countDmArchivableMessages(): Int = archivableCount

    override suspend fun archiveDmHistory(): Result<Unit> {
        calls += "archiveDmHistory"
        return archiveDmHistoryResult
    }

    override fun cancelDmArchive() {
        calls += "cancelDmArchive"
    }

    val dmPairingStateFlow = MutableStateFlow<DmPairingManager.State>(DmPairingManager.State.Idle)
    override val dmPairingState: StateFlow<DmPairingManager.State> = dmPairingStateFlow
    var requestDmEncryptionKeyResult: Result<Unit> = Result.Success(Unit)
    var approveDmPairingResult: Result<Unit> = Result.Success(Unit)

    override suspend fun requestDmEncryptionKey(): Result<Unit> {
        calls += "requestDmEncryptionKey"
        return requestDmEncryptionKeyResult
    }

    override suspend fun approveDmPairing(throwawayPubkey: String): Result<Unit> {
        calls += "approveDmPairing:$throwawayPubkey"
        return approveDmPairingResult
    }

    override fun declineDmPairing(throwawayPubkey: String) {
        calls += "declineDmPairing:$throwawayPubkey"
    }

    override fun declineAllDmPairing() {
        calls += "declineAllDmPairing"
    }

    override fun dismissDmPairing() {
        calls += "dismissDmPairing"
    }
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

    override suspend fun loginWithAmber(
        pubkey: String,
        signerPackage: String?,
    ): Result<Unit> {
        calls += "loginWithAmber"
        return loginWithAmberAction(pubkey, signerPackage)
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
        listPrivately: Boolean,
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
        listPrivately: Boolean,
    ): Result<String> = Result.Success(customGroupId ?: "fake-subgroup-id")

    override suspend fun joinGroup(
        groupId: String,
        inviteCode: String?,
        listPrivately: Boolean,
        relayUrl: String?,
    ): Result<Unit> {
        calls += "joinGroup:$groupId:$listPrivately"
        joinRelays += groupId to relayUrl
        return Result.Success(Unit)
    }

    /** (groupId, relayUrl) of every joinGroup call, so tests can assert the routing relay. */
    val joinRelays = mutableListOf<Pair<String, String?>>()

    override suspend fun requestGroupThreads(groupId: String, relayUrl: String?): Boolean {
        calls += "requestGroupThreads:$groupId"
        threadRequestRelays += groupId to relayUrl
        return true
    }

    /** (groupId, relayUrl) of every requestGroupThreads call, so tests can assert the route relay. */
    val threadRequestRelays = mutableListOf<Pair<String, String?>>()

    override fun closeThreadSubscriptions(groupId: String) {
        calls += "closeThreadSubscriptions:$groupId"
    }

    override suspend fun fetchThread(groupId: String, rootId: String) {
        calls += "fetchThread:$groupId:$rootId"
    }

    override suspend fun createThread(
        groupId: String,
        title: String,
        content: String,
        mentions: Map<String, String>,
    ): Result<String> {
        calls += "createThread:$groupId:$title:${mentions.keys.sorted().joinToString(",")}"
        // A real 32-byte hex id so callers can bech32-encode it (nevent).
        return Result.Success("ab".repeat(32))
    }

    override suspend fun sendThreadReply(
        groupId: String,
        root: NostrGroupClient.NostrMessage,
        parent: NostrGroupClient.NostrMessage,
        content: String,
        mentions: Map<String, String>,
    ): Result<Unit> {
        calls += "sendThreadReply:$groupId:${root.id}:${parent.id}:${mentions.keys.sorted().joinToString(",")}"
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

    override suspend fun setGroupListedPrivately(
        groupId: String,
        relayUrl: String,
        listedPrivately: Boolean,
    ): Result<Unit> {
        calls += "setGroupListedPrivately:$groupId:$relayUrl:$listedPrivately"
        if (privateToggleFails) return Result.Error(org.nostr.nostrord.utils.AppError.Unknown("refused", null))
        val entry = relayUrl to groupId
        _privateGroupEntries.value =
            if (listedPrivately) _privateGroupEntries.value + entry else _privateGroupEntries.value - entry
        return Result.Success(Unit)
    }

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
        hasLiveKit: Boolean?,
    ): Result<Unit> {
        editedLiveKit = hasLiveKit
        return Result.Success(Unit)
    }

    /** Last `hasLiveKit` an edit was asked to apply; null means "keep whatever the group has". */
    var editedLiveKit: Boolean? = null

    override suspend fun deleteGroup(groupId: String): Result<Unit> = Result.Success(Unit)

    override suspend fun reorderChildren(
        groupId: String,
        orderedIds: List<String>,
    ): Result<Unit> = Result.Success(Unit)

    override suspend fun reorderGroups(order: List<Pair<String, String>>): Result<Unit> {
        groupOrderFlow.value = order
        return Result.Success(Unit)
    }

    override fun isGroupJoined(groupId: String): Boolean = joinedGroups.value.contains(groupId)

    override suspend fun requestGroupMessages(
        groupId: String,
        channel: String?,
    ) {}

    override suspend fun requestGroupMembers(groupId: String) {}

    override suspend fun requestGroupAdmins(groupId: String) {}

    val _liveKitParticipants = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    override val liveKitParticipants: StateFlow<Map<String, List<String>>> = _liveKitParticipants

    /** Set by tests to drive the AV space join path without a relay. */
    var liveKitCredentials: Result<LiveKitCredentials> =
        Result.Error(AppError.Network.Disconnected("no relay"))
    var avSupported: Boolean = false
    val requestedLiveKitParticipants = mutableListOf<String>()
    var liveKitCredentialRequests = 0

    override suspend fun requestLiveKitParticipants(groupId: String) {
        requestedLiveKitParticipants += groupId
    }

    override suspend fun relaySupportsAv(groupId: String): Boolean = avSupported

    override suspend fun fetchLiveKitCredentials(groupId: String): Result<LiveKitCredentials> {
        liveKitCredentialRequests++
        return liveKitCredentials
    }

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

    /** Reply targets passed to [sendDm], in call order (null when the message starts a thread). */
    val sentDmReplyTargets = mutableListOf<String?>()

    override suspend fun sendDm(recipientPubkey: String, content: String, replyToId: String?): Result<Unit> {
        sentDmReplyTargets += replyToId
        return sendDmAction(recipientPubkey, content)
    }

    var retryDmAction: (String) -> Unit = { }

    override fun retryDm(rumorId: String) = retryDmAction(rumorId)

    var dismissDmAction: (String) -> Unit = { }

    override fun dismissDm(rumorId: String) = dismissDmAction(rumorId)

    override suspend fun markDmRead(peerPubkey: String) {}

    override suspend fun publishDmRelayList(relays: List<String>): Result<Unit> {
        myDmRelaysFlow.value = relays
        return Result.Success(Unit)
    }

    var retrySendAction: (String) -> Unit = {}

    override fun retrySend(eventId: String) = retrySendAction(eventId)

    var dismissFailedAction: (String, String) -> Unit = { _, _ -> }

    override fun dismissFailed(groupId: String, eventId: String) = dismissFailedAction(groupId, eventId)

    var deleteMessageResult: Result<Unit> = Result.Success(Unit)

    override suspend fun deleteMessage(
        groupId: String,
        messageId: String,
    ): Result<Unit> {
        calls += "deleteMessage:$groupId:$messageId"
        return deleteMessageResult
    }

    override fun getMessagesForGroup(groupId: String): List<NostrGroupClient.NostrMessage> = messages.value[groupId] ?: emptyList()

    var relayHints = mutableMapOf<String, String>()

    override fun setGroupRelayHint(
        groupId: String,
        relayUrl: String,
    ) {
        relayHints[groupId] = relayUrl
    }

    override fun markGroupAsRead(relayUrl: String, groupId: String) {}

    override fun markGroupAsReadUpTo(relayUrl: String, groupId: String, timestamp: Long) {}

    override fun getUnreadCount(relayUrl: String, groupId: String): Int = unreadByGroupKey.value[groupKey(relayUrl, groupId)] ?: 0

    override fun getLastReadTimestamp(relayUrl: String, groupId: String): Long? = null

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

    var reportUserResult: Result<Unit> = Result.Success(Unit)

    override suspend fun reportUser(
        pubkey: String,
        type: org.nostr.nostrord.nostr.Nip56.ReportType,
        note: String,
        eventId: String?,
    ): Result<Unit> {
        calls += "reportUser:$pubkey:${type.value}:$note:$eventId"
        return reportUserResult
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
        groupId: String?,
    ) {
        calls += "requestEventById:$eventId:${groupId ?: "-"}"
    }

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

    override fun setActiveGroup(relayUrl: String?, groupId: String?) {}

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
    override val leftGroups: StateFlow<Map<String, Set<String>>> = MutableStateFlow(emptyMap())

    val pendingGroupInvitesFlow = MutableStateFlow<Map<String, PendingGroupInvite>>(emptyMap())
    override val pendingGroupInvites: StateFlow<Map<String, PendingGroupInvite>> = pendingGroupInvitesFlow
    val acceptedInvites = mutableListOf<String>()

    override suspend fun acceptGroupInvite(groupId: String) {
        acceptedInvites += groupId
        pendingGroupInvitesFlow.update { it - groupId }
    }

    val addedToList = mutableListOf<Pair<String, String?>>()

    override suspend fun addGroupToMyList(groupId: String, relayUrl: String?) {
        addedToList += groupId to relayUrl
        val relay = relayUrl ?: return
        _joinedGroupsByRelay.update { it + (relay to (it[relay].orEmpty() + groupId)) }
    }

    var sendReactionResult: Result<Unit> = Result.Success(Unit)

    override suspend fun sendReaction(
        groupId: String,
        targetEventId: String,
        targetPubkey: String,
        emoji: String,
        threadRootId: String?,
    ): Result<Unit> {
        calls += "sendReaction:$groupId:$targetEventId:$emoji"
        return sendReactionResult
    }

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

    val groupOrderFlow = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    override val groupOrder: StateFlow<List<Pair<String, String>>> = groupOrderFlow

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
