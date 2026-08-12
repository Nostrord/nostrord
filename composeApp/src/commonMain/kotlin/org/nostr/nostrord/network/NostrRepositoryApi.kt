package org.nostr.nostrord.network

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import org.nostr.nostrord.auth.Account
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
import org.nostr.nostrord.nostr.Nip11RelayInfo
import org.nostr.nostrord.nostr.Nip17File
import org.nostr.nostrord.nostr.Nip46Client
import org.nostr.nostrord.utils.Result

/**
 * Public contract for NostrRepository.
 * Allows ViewModels to be tested with a fake implementation.
 */
/** One entry of a user's public kind:10009 group list. */
@Serializable
data class UserGroupRef(
    val relayUrl: String,
    val groupId: String,
)

interface NostrRepositoryApi {
    // --- Auth state ---
    val isInitialized: StateFlow<Boolean>
    val isLoggedIn: StateFlow<Boolean>

    /**
     * Hex pubkey of the active account, or null when logged out. Changes on every
     * account switch, so screens can re-arm per-account loading state (skeletons)
     * instead of flashing the previous account's data or an empty/onboarding state.
     */
    val activePubkey: StateFlow<String?>
    val isBunkerVerifying: StateFlow<Boolean>
    val isBunkerConnected: StateFlow<Boolean>
    val bunkerState: StateFlow<BunkerState>
    val authUrl: StateFlow<String?>

    // --- Connection state ---
    val currentRelayUrl: StateFlow<String>
    val connectionState: StateFlow<ConnectionManager.ConnectionState>
    val isDiscoveringRelays: StateFlow<Boolean>

    /** Non-null when a deep link opened a relay not in the user's saved list. */
    val pendingDeepLinkRelay: StateFlow<String?>

    // --- Group state ---
    val groups: StateFlow<List<GroupMetadata>>
    val groupsByRelay: StateFlow<Map<String, List<GroupMetadata>>>
    val messages: StateFlow<Map<String, List<NostrGroupClient.NostrMessage>>>

    /** Per-message delivery status for the local user's own messages (optimistic send). */
    val messageStatus: StateFlow<Map<String, GroupManager.MessageStatus>>

    // --- Forum threads (NIP-29 kind:11 root + NIP-22 kind:1111 replies) ---
    /** Thread roots (kind:11) per group; the threads-pane list source. */
    val threadRoots: StateFlow<Map<String, List<NostrGroupClient.NostrMessage>>>

    /** Thread replies (kind:1111) per group; grouped by their root `E` tag in the ViewModel. */
    val threadReplies: StateFlow<Map<String, List<NostrGroupClient.NostrMessage>>>

    /** Group ids whose thread-roots subscription has reached EOSE (so an empty list is real). */
    val threadsLoaded: StateFlow<Set<String>>
    val joinedGroups: StateFlow<Set<String>>
    val joinedGroupsByRelay: StateFlow<Map<String, Set<String>>>

    /**
     * (relayUrl, groupId) the user keeps in the encrypted section of their kind:10009 instead of
     * its public tags. Private groups still work exactly the same; they are just not advertised.
     */
    val privateGroupEntries: StateFlow<Set<Pair<String, String>>>

    /**
     * The kind:10009 has an encrypted section this client cannot read (another app's encryption,
     * or a signer that won't decrypt). Its entries are preserved untouched on every publish.
     */
    val privateListSectionOpaque: StateFlow<Boolean>
    val loadingRelays: StateFlow<Set<String>>

    /** Relays (in LAZY mode) whose full group list has been fetched this session. */
    val fullGroupListFetchedRelays: StateFlow<Set<String>>

    /** Relays that finished serving their group list (EOSE); gates the "group no longer available" UI. */
    val completeGroupLoadRelays: StateFlow<Set<String>>

    /** Relays that returned CLOSED "restricted" — access permanently denied. */
    val restrictedRelays: StateFlow<Map<String, String>>
    val isLoadingMore: StateFlow<Map<String, Boolean>>
    val hasMoreMessages: StateFlow<Map<String, Boolean>>

    /**
     * Per-group GroupLoadingState. Lets the UI tell apart "Idle / InitialLoading
     * (haven't gotten EOSE yet)" from "Exhausted (relay confirmed empty)", which
     * the boolean [hasMoreMessages] conflates — both are `hasMore = false`. The
     * web's "No messages yet" empty state needs the distinction so it doesn't
     * flash before the relay has actually spoken (issue: empty state showing on
     * group open before any kind:9 has streamed).
     */
    val groupStates: StateFlow<Map<String, org.nostr.nostrord.network.managers.GroupLoadingState>>

    /**
     * Groups whose initial history read is waiting on NIP-42 AUTH (private group on a relay
     * that challenges in response to the read). The UI shows skeletons for these instead of a
     * premature "No messages yet", until an authenticated read settles.
     */
    val groupsAwaitingAuthRead: StateFlow<Set<String>>

    /**
     * Force-reset the loading state of [groupId] to Idle. Used to recover from
     * controllers stuck in InitialLoading because their underlying socket died
     * (account swap, connection reset) but the natural onConnectionLost path
     * didn't fire (e.g. explicit focusedClient.disconnect() during a reconnect()
     * doesn't always trigger the WebSocket close callback in time).
     */
    suspend fun resetGroupLoadingState(groupId: String)
    val reactions: StateFlow<Map<String, Map<String, GroupManager.ReactionInfo>>>

    /** NIP-57 zap totals keyed by zapped event id. */
    val zaps: StateFlow<Map<String, ZapManager.ZapInfo>>
    val groupMembers: StateFlow<Map<String, List<String>>>

    /**
     * Per-relay mirror of the member/admin/role lists (relay -> groupId -> list). The flat
     * maps above collapse by bare id (last relay wins on a same-id collision); relay-scoped
     * readers prefer these.
     */
    val groupMembersByRelay: StateFlow<Map<String, Map<String, List<String>>>>

    /** groupKey(relay, groupId) -> epoch seconds of our outstanding kind:9021 awaiting approval. */
    val pendingApprovalSince: StateFlow<Map<String, Long>>
    val groupAdmins: StateFlow<Map<String, List<String>>>
    val groupAdminsByRelay: StateFlow<Map<String, Map<String, List<String>>>>
    val groupRoles: StateFlow<Map<String, List<RoleDefinition>>>
    val groupRolesByRelay: StateFlow<Map<String, Map<String, List<RoleDefinition>>>>

    /** NIP-29 AV spaces: groupId -> pubkeys currently in the relay's LiveKit room (kind 39004). */
    val liveKitParticipants: StateFlow<Map<String, List<String>>>
    val loadingMembers: StateFlow<Set<String>>

    /** Groups whose subscriptions were CLOSED with "restricted" — private group, non-member. */
    val restrictedGroups: StateFlow<Map<String, String>>

    /**
     * Groups the user explicitly LEFT (durable, survives restart), keyed by the relay they were
     * left on; membership reads NONE for these. Relay-scoped because the same group id names
     * independent groups on different relays.
     */
    val leftGroups: StateFlow<Map<String, Set<String>>>

    /**
     * External adds (an admin's kind:9000) awaiting the user's accept/decline, keyed by
     * groupId. Accept via [acceptGroupInvite]; decline via [leaveGroup] (kind:9022 + durable
     * left marker, which also drops the pending entry).
     */
    val pendingGroupInvites: StateFlow<Map<String, PendingGroupInvite>>

    /** Adopt a pending external add into the joined set and republish kind:10009. */
    suspend fun acceptGroupInvite(groupId: String)

    /**
     * Put a group [relayUrl] already lists us in into our own kind:10009, with no invite card
     * involved. Relay-side membership and the list are separate states; this is the manual way
     * to reconcile them for a group we can read and post in but that no list knows about.
     */
    suspend fun addGroupToMyList(groupId: String, relayUrl: String?)

    // --- Metadata state ---
    val userMetadata: StateFlow<Map<String, UserMetadata>>
    val cachedEvents: StateFlow<Map<String, CachedEvent>>

    /** Unread chat counts keyed by [org.nostr.nostrord.utils.groupKey], never by bare group id. */
    val unreadByGroupKey: StateFlow<Map<String, Int>>

    // --- Direct messages (NIP-17) ---
    /** Conversations (most-recent first), derived from decrypted NIP-17 messages. */
    val dmConversations: StateFlow<List<DmConversation>>

    /** Decrypted DM messages keyed by peer pubkey. */
    val dmMessagesByPeer: StateFlow<Map<String, List<DmMessage>>>

    /** Unread DM count per peer (incoming messages newer than the read high-water). */
    val dmUnreadByPeer: StateFlow<Map<String, Int>>

    /** Total unread DMs across all conversations, for the nav badge. */
    val totalDmUnread: StateFlow<Int>

    /**
     * Peer of the DM conversation last opened this session, or null for none. The DM nav entry
     * reopens it, so leaving a conversation for a group and coming back lands on the thread
     * rather than the list. Session-scoped: an account switch clears it.
     */
    val lastDmPeer: StateFlow<String?>

    /** Record [pubkey] as the open DM conversation (see [lastDmPeer]). */
    fun rememberDmPeer(pubkey: String)

    /** Our own effective NIP-17 DM relays (kind:10050, or the defaults until one is published). */
    val myDmRelays: StateFlow<List<String>>

    /** Published kind:10050 DM relay lists by author pubkey, as they are fetched. */
    val dmRelaysByPubkey: StateFlow<Map<String, List<String>>>

    /** Send status of our own DM messages, keyed by rumor id (Sending until a relay OKs). */
    val dmMessageStatus: StateFlow<Map<String, GroupManager.MessageStatus>>

    /**
     * Send a file as an encrypted kind:15 message: the bytes are encrypted before upload, so the
     * media server holds ciphertext and only the recipient can read the file.
     */
    suspend fun sendDmFile(
        recipientPubkey: String,
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        width: Int? = null,
        height: Int? = null,
    ): Result<Unit>

    /** React to a DM message with [emoji] (NIP-25 rumor); [emojiUrl] for a custom emoji. */
    suspend fun sendDmReaction(
        recipientPubkey: String,
        messageId: String,
        emoji: String,
        emojiUrl: String? = null,
    ): Result<Unit>

    /**
     * The inbox is still catching up: a DM relay has not EOSEd, or a delivered gift wrap is still
     * waiting to be decrypted. Older messages can still land above what is already on screen.
     */
    val dmSyncing: StateFlow<Boolean>

    /** Reactions on DM messages, keyed by the message they target, then by emoji. */
    val dmReactions: StateFlow<Map<String, Map<String, GroupManager.ReactionInfo>>>

    /** Download + decryption state of kind:15 attachments, keyed by rumor id. */
    val dmFileStates: StateFlow<Map<String, DmFileManager.FileState>>

    /** Start reading a kind:15 attachment (idempotent; safe to call on every render). */
    fun loadDmFile(rumorId: String, file: Nip17File)

    /** Re-attempt a failed attachment load. */
    fun retryDmFile(rumorId: String, file: Nip17File)

    /** Fetch a peer's kind:10050 so [dmRelaysByPubkey] gains its entry (fire-and-forget). */
    fun requestPeerDmRelays(pubkey: String)

    /** Whether this account holds and advertises a NIP-4e encryption key (see DmEncryptionManager). */
    val dmEncryptionState: StateFlow<DmEncryptionManager.State>

    /**
     * Re-read this account's own kind:10044 and reconcile [dmEncryptionState] against it, so the
     * screen shows what the relays actually announce instead of what this device last did.
     */
    suspend fun refreshDmEncryptionState()

    /** Announce a NIP-4e encryption key so inbound DMs decrypt without the signer. */
    suspend fun enableDmEncryption(): Result<Unit>

    /** Stop advertising the encryption key. The key is kept, or its history becomes unreadable. */
    suspend fun disableDmEncryption(): Result<Unit>

    /** Advertise a fresh encryption key. The previous one stays held so its history keeps opening. */
    suspend fun rotateDmEncryptionKey(): Result<Unit>

    /**
     * Take over from a key announced by a device we no longer have: announce a fresh one we hold.
     * Everything addressed to the lost key stops being readable, which is why this is separate
     * from [rotateDmEncryptionKey] and only reachable from `AnnouncedElsewhere`.
     */
    suspend fun resetDmEncryptionKey(): Result<Unit>

    /** Hold the encryption key exported from another device. False when it is not the announced one. */
    fun importDmEncryptionKey(privateKeyHex: String): Boolean

    /** The current encryption private key, for moving it to another device. */
    fun exportDmEncryptionKey(): String?

    /**
     * The whole DM history as a backup file body (JSONL, one decrypted rumor per line). Empty when
     * there is nothing stored. The file is NOT encrypted; callers must say so before writing it.
     */
    suspend fun exportDmHistory(): String

    /** Restore rumors from a backup file body. See [DmImportSummary] for the partial-restore case. */
    suspend fun importDmHistory(text: String): Result<DmImportSummary>

    /** Progress of the self-archive republish (see DmArchiveManager). */
    val dmArchiveProgress: StateFlow<DmArchiveManager.Progress>

    /** How many stored messages still need an archive copy; drives the confirmation copy. */
    suspend fun countDmArchivableMessages(): Int

    /** Republish decrypted history to ourselves addressed to the encryption key. Resumable. */
    suspend fun archiveDmHistory(): Result<Unit>

    /** Stop an archive run in progress; already-published copies stay. */
    fun cancelDmArchive()

    /** NIP-4e device pairing: request the key, or answer another device asking for it. */
    val dmPairingState: StateFlow<DmPairingManager.State>

    /** Ask another device of this account for the encryption key (kind:4454). */
    suspend fun requestDmEncryptionKey(): Result<Unit>

    /** Send our encryption key to the device waiting on [throwawayPubkey] (kind:4455). */
    suspend fun approveDmPairing(throwawayPubkey: String): Result<Unit>

    /** Refuse one pending request; the asking device keeps waiting. */
    fun declineDmPairing(throwawayPubkey: String)

    /** Refuse every pending request at once. */
    fun declineAllDmPairing()

    /** Clear a finished or failed pairing back to idle. */
    fun dismissDmPairing()

    /** Send a NIP-17 direct message to [recipientPubkey]. */
    /** [replyToId] marks this message as a reply to another one in the same conversation. */
    suspend fun sendDm(recipientPubkey: String, content: String, replyToId: String? = null): Result<Unit>

    /** Send a DM again after every relay refused it (its bubble shows Not delivered). */
    fun retryDm(rumorId: String)

    /** Drop a refused DM from its conversation and from the send queue. */
    fun dismissDm(rumorId: String)

    /** Mark a DM conversation read up to its newest message (clears its unread badge). */
    suspend fun markDmRead(peerPubkey: String)

    /** Publish our NIP-17 DM relay list (kind:10050) so others know where to reach us. */
    suspend fun publishDmRelayList(relays: List<String>): Result<Unit>

    /**
     * High-water mark per group: `created_at` (seconds) of the newest message
     * the client has processed. Persisted across sessions via `UnreadManager`.
     * Used by the sidebar to surface groups with recent activity.
     */
    val latestMessageTimestamps: StateFlow<Map<String, Long>>
    val totalUnread: StateFlow<Int>
    val unreadByRelay: StateFlow<Map<String, Int>>
    val userRelayList: StateFlow<List<Nip65Relay>>
    val relayMetadata: StateFlow<Map<String, Nip11RelayInfo>>

    /**
     * Relay URLs (normalized) we could not reach: the WebSocket connect failed, or
     * the NIP-11 HTTP fetch exhausted its retries with no working socket. Discovery
     * surfaces (From friends, Recommended) hide groups hosted on these.
     */
    val unreachableRelays: StateFlow<Set<String>>

    /** Relay URLs present as explicit "r" tags in the user's kind:10009 event. */
    val kind10009Relays: StateFlow<Set<String>>

    /** Relay URLs from "group" tags that have no "r" tag — implicit, never persisted. */
    val groupTagRelays: StateFlow<Set<String>>

    /**
     * Rail order: (relayUrl, groupId) in the tag order of the account's kind:10009, the
     * order the user controls by dragging. Purely a sort key: an entry here whose group is
     * not joined is ignored, never a source of membership.
     */
    val groupOrder: StateFlow<List<Pair<String, String>>>

    /**
     * Public NIP-29 group lists (kind:10009) of OTHER users, keyed by pubkey, as
     * (relayUrl, groupId) refs. Filled by [requestUserGroupList]; the active
     * account's own list keeps flowing through the joined-groups state instead.
     */
    val userGroupLists: StateFlow<Map<String, List<UserGroupRef>>>

    /**
     * The active account's own following set (pubkeys) from its NIP-02 kind:3
     * contact list. Empty until [requestContactList] (or a follow/unfollow) fills it.
     */
    val following: StateFlow<Set<String>>

    /**
     * True once the kind:3 contact list has actually loaded (arrived from a relay,
     * was published by us, or the fetch resolved as "no list"). Lets the UI tell
     * "still loading" apart from "follows nobody" so an empty [following] is not
     * mistaken for a not-yet-loaded one.
     */
    val contactListLoaded: StateFlow<Boolean>

    /**
     * The active account's publicly muted pubkeys from its NIP-51 kind:10000 mute list.
     * Hydrated from the per-account cache at login and kept in sync with the relays.
     * Messages, DM conversations, and notifications from these authors are hidden.
     */
    val mutedPubkeys: StateFlow<Set<String>>

    // --- Initialization ---
    fun forceInitialized()

    suspend fun initialize()

    // --- Auth operations ---
    fun clearAuthUrl()

    fun getPublicKey(): String?

    fun getPrivateKey(): String?

    fun isUsingBunker(): Boolean

    fun isBunkerReady(): Boolean

    suspend fun ensureBunkerConnected(): Boolean

    fun forgetBunkerConnection()

    /**
     * Login with a raw private key. [ncryptsec] marks the account
     * password-protected (NIP-49): only the encrypted key is persisted and the
     * next session restore surfaces [pendingUnlockAccount] instead of logging in.
     */
    suspend fun loginSuspend(
        privKey: String,
        pubKey: String,
        isNewIdentity: Boolean = false,
        ncryptsec: String? = null,
    ): Result<Unit>

    /** Account whose NIP-49 password is needed to finish session restore (unlock gate). */
    val pendingUnlockAccount: StateFlow<Account?>

    /** Dismiss the unlock gate (the user can still log in another way). */
    fun clearPendingUnlock()

    suspend fun loginWithNip07(pubkey: String): Result<Unit>

    /** Login via a NIP-55 Android signer app (Amber); pubkey + package come from its get_public_key UI. */
    suspend fun loginWithAmber(
        pubkey: String,
        signerPackage: String?,
    ): Result<Unit>

    suspend fun loginWithBunker(bunkerUrl: String): Result<String>

    /** Default relays seeding the nostrconnect:// QR login (user-overridable). */
    val defaultNostrConnectRelays: List<String>

    suspend fun createNostrConnectSession(relays: List<String> = defaultNostrConnectRelays): Pair<String, Nip46Client>

    suspend fun completeNostrConnectLogin(
        client: Nip46Client,
        relays: List<String> = listOf("wss://relay.damus.io", "wss://nos.lol"),
    ): String

    suspend fun logout()

    /**
     * After AccountManager.switchAccount has swapped credentials and reset
     * in-memory caches, re-hydrate joined-group state from per-account storage
     * for the new pubkey and re-issue subscriptions on the active relay.
     */
    suspend fun reloadForActiveAccount()

    // --- Connection operations ---
    suspend fun connect()

    suspend fun reconnect(): Boolean

    /** Fire-and-forget reconnect — safe to call from non-suspend contexts. */
    fun triggerReconnect()

    suspend fun switchRelay(newRelayUrl: String)

    suspend fun removeRelay(url: String)

    suspend fun disconnect()

    // --- Per-relay fetch mode ---

    /** Set whether a relay uses lazy fetch mode (only joined-group metadata on connect). */
    fun setGroupFetchLazy(
        relayUrl: String,
        lazy: Boolean,
    )

    fun isGroupFetchLazy(relayUrl: String): Boolean

    /** Fetch the full group list for a relay — used when the user expands OTHER GROUPS on a lazy relay. */
    suspend fun requestFullGroupListForRelay(relayUrl: String)

    /** Add a relay to the user's saved list and publish kind:10009. */
    suspend fun addRelay(url: String)

    /** Dismiss the deep link relay prompt without saving. */
    fun dismissDeepLinkRelay()

    // --- Lifecycle ---

    /** Called when the app returns to the foreground. Re-establishes connections and refreshes subscriptions. */
    fun onForeground()

    /** Called when the app moves to the background. Persists live cursors to storage. */
    fun onBackground()

    /** Called when the app process is about to be destroyed. Persists all state and disconnects. */
    fun onDestroy()

    /**
     * Notify the repository which group the user is currently viewing.
     * The relay that hosts [groupId] is promoted to [RelayReconnectScheduler.Priority.ACTIVE]
     * so reconnect attempts for it use faster backoff. Pass null when leaving the group screen.
     */
    fun setActiveGroup(
        relayUrl: String?,
        groupId: String?,
    )

    // --- Group operations ---
    suspend fun createGroup(
        name: String,
        about: String?,
        relayUrl: String,
        isPrivate: Boolean,
        isClosed: Boolean,
        isRestricted: Boolean = false,
        isHidden: Boolean = false,
        picture: String? = null,
        customGroupId: String? = null,
    ): Result<String>

    /**
     * Create a group and immediately declare [parentGroupId] as its parent
     * (chained kind:9007 + kind:9002).
     */
    suspend fun createSubgroup(
        parentGroupId: String,
        name: String,
        about: String?,
        relayUrl: String,
        isPrivate: Boolean,
        isClosed: Boolean,
        isRestricted: Boolean = false,
        isHidden: Boolean = false,
        picture: String? = null,
        customGroupId: String? = null,
    ): Result<String>

    /**
     * Join [groupId]. [listPrivately] puts it straight into the encrypted section of the
     * kind:10009, so the group is never advertised in the clear, not even by the join publish.
     */
    suspend fun joinGroup(
        groupId: String,
        inviteCode: String? = null,
        listPrivately: Boolean = false,
        /** Relay hosting the group being joined. Null routes by hint / focused relay, which
         *  picks the wrong twin when the same id exists on two relays. */
        relayUrl: String? = null,
    ): Result<Unit>

    /**
     * Optimistically flip [groupId] on [relayUrl] to joined in memory so the UI reacts
     * immediately (mirrors the follow button); [joinGroup] then confirms and persists it.
     * Returns false when it was already joined, so the caller skips [revertOptimisticJoin].
     */
    fun markOptimisticJoin(relayUrl: String, groupId: String): Boolean

    /** Undo a [markOptimisticJoin] when the join ultimately fails. */
    fun revertOptimisticJoin(relayUrl: String, groupId: String)

    suspend fun leaveGroup(
        groupId: String,
        reason: String? = null,
    ): Result<Unit>

    /**
     * Move a joined group between the public tags and the encrypted section of the kind:10009,
     * republishing the list. Errors leave the previous state in place, so the toggle never claims
     * a change no relay has.
     */
    suspend fun setGroupListedPrivately(
        groupId: String,
        relayUrl: String,
        listedPrivately: Boolean,
    ): Result<Unit>

    /**
     * Locally remove a joined group that no longer has a `kind:39000` on the
     * relay (deleted while offline, or never existed anymore). Does NOT send
     * `kind:9022` — the group is gone, so there's no relay-side state to leave.
     * Republishes `kind:10009` so other devices drop the stale pin too.
     */
    suspend fun forgetGroup(
        groupId: String,
        relayUrl: String,
    ): Result<Unit>

    /** Joined groups on a relay that have no corresponding `kind:39000` metadata. */
    val orphanedJoinedByRelay: StateFlow<Map<String, Set<String>>>

    /**
     * Edit a group in one kind:9002 event (full-state replace: the current
     * parent and child list are re-declared automatically). [parentOp] is
     * optional — omit it to keep the current parent.
     */
    suspend fun editGroup(
        groupId: String,
        name: String,
        about: String?,
        isPrivate: Boolean,
        isClosed: Boolean,
        isRestricted: Boolean = false,
        isHidden: Boolean = false,
        picture: String? = null,
        parentOp: GroupManager.ParentOp? = null,
        /** NIP-29 `livekit`: null keeps the current setting, which a rename must not clear. */
        hasLiveKit: Boolean? = null,
    ): Result<Unit>

    suspend fun deleteGroup(groupId: String): Result<Unit>

    /**
     * Reorder the group's channels (kind:9002 full-state edit; order = `child` tag
     * position). [orderedIds] must be a permutation of the current child list.
     */
    suspend fun reorderChildren(
        groupId: String,
        orderedIds: List<String>,
    ): Result<Unit>

    /**
     * Reorder the rail by republishing kind:10009 with the `group` tags in [order].
     * Membership is untouched: entries not currently joined are dropped and joined groups
     * missing from [order] keep their slot at the end, so a reorder can never add a group
     * to the published list.
     */
    suspend fun reorderGroups(order: List<Pair<String, String>>): Result<Unit>

    fun isGroupJoined(groupId: String): Boolean

    suspend fun requestGroupMessages(
        groupId: String,
        channel: String? = null,
    )

    suspend fun requestGroupMembers(groupId: String)

    suspend fun requestGroupAdmins(groupId: String)

    /** Request live AV participants (kind 39004) for a group whose metadata carries `livekit`. */
    suspend fun requestLiveKitParticipants(groupId: String)

    /**
     * Whether the relay hosting [groupId] advertises NIP-29 LiveKit rooms (204 at
     * `/.well-known/nip29/livekit`). The spec's use for this is offering the AV option when
     * creating or editing a group, which is where the `livekit` tag gets set.
     */
    suspend fun relaySupportsAv(groupId: String): Boolean

    /**
     * Mint LiveKit join credentials for [groupId] from its relay, authenticated with NIP-98.
     * The relay enforces group access control when issuing the token.
     */
    suspend fun fetchLiveKitCredentials(groupId: String): Result<LiveKitCredentials>

    /**
     * Request pending join requests (kind 9021 + 9022) for a group. Admin-only;
     * supplements the standard chat REQ, which buries old 9021s under recent chat.
     */
    suspend fun requestPendingJoinRequests(groupId: String)

    /**
     * Fire-and-forget NIP-11 fetch for [relayUrl]. Used by the AddRelay modal
     * to populate icons + names for relays the user hasn't connected to yet.
     * Idempotent: succeeded/in-flight URLs are skipped inside the manager.
     */
    fun fetchRelayMetadata(relayUrl: String)

    suspend fun refreshGroupMetadata(groupId: String)

    /** Observable parent→children map derived from `parent` tags in kind:39000. */
    val childrenByParent: StateFlow<Map<String, Set<String>>>

    /** Connect to a relay in the background and fetch kind 39000 metadata for a group preview. */
    suspend fun fetchGroupPreview(
        groupId: String,
        relayUrl: String,
    )

    /**
     * Batched, deduplicated metadata fetch for discovered groups: [relayToGroups]
     * maps each relay to the set of group ids to fetch there. Connects to each
     * relay at most once (pooled) and sends one kind:39000 REQ per relay, so a
     * friends-groups list spanning many relays does not open redundant connections.
     */
    suspend fun fetchGroupPreviews(relayToGroups: Map<String, Set<String>>)

    /**
     * Batched member-list (kind 39002) fetch for discovered groups, same shape and
     * per-relay batching as [fetchGroupPreviews]: one REQ per relay instead of one per
     * group, so a discovery tab listing dozens of a relay's public groups doesn't open
     * dozens of individual member-count subscriptions.
     */
    suspend fun fetchGroupsMembers(relayToGroups: Map<String, Set<String>>)

    /**
     * Suspends until [relayUrl]'s NIP-42 AUTH has settled: returns quickly on relays that
     * don't gate reads (short challenge grace), waits out the signer budget on relays that
     * do. Used by checks that treat "the relay didn't serve X" as proof of absence — before
     * AUTH an auth-gating relay withholds private-group data, so absence proves nothing.
     */
    suspend fun awaitRelayAuthSettled(relayUrl: String)

    suspend fun loadMoreMessages(
        groupId: String,
        channel: String? = null,
    ): Boolean

    /**
     * Explicit retry for a group whose pagination is [GroupLoadingState.Stalled]
     * (repeated zero-event scroll-back timeouts). One attempt from the stalled
     * frontier; no-op (false) in any other state.
     */
    suspend fun retryStalledLoad(
        groupId: String,
        channel: String? = null,
    ): Boolean

    suspend fun fetchGroupMessageById(
        groupId: String,
        messageId: String,
    )

    suspend fun sendMessage(
        groupId: String,
        content: String,
        channel: String? = null,
        mentions: Map<String, String> = emptyMap(),
        replyToMessageId: String? = null,
        extraTags: List<List<String>> = emptyList(),
    ): Result<Unit>

    /** Re-send a previously failed own message (optimistic send) by its event id. */
    fun retrySend(eventId: String)

    /** Drop a failed own message from the chat. */
    fun dismissFailed(groupId: String, eventId: String)

    /**
     * Open the threads-pane subscriptions for a group (kind:11 roots + batched kind:1111 replies).
     *
     * [relayUrl] is the relay the pane's route names. Passing it lets the disk cache hydrate on a
     * cold open, before the group listings that [getRelayForGroup] would need have arrived.
     */
    suspend fun requestGroupThreads(groupId: String, relayUrl: String? = null): Boolean

    /** CLOSE the threads-pane subscriptions for a group (on leaving the pane). Fire-and-forget. */
    fun closeThreadSubscriptions(groupId: String)

    /** Backfill a single thread by id (deep link): fetch the kind:11 root and its replies. */
    suspend fun fetchThread(groupId: String, rootId: String)

    /** Create a forum thread (kind:11 root). [title] becomes a NIP-14 subject tag when non-blank. */
    /**
     * Publish a kind:11 thread root. Success carries the root's event id (stable NIP-01 id).
     * [mentions] maps a typed `@displayName` to its pubkey (resolved to `nostr:npub` + a `p` tag).
     */
    suspend fun createThread(
        groupId: String,
        title: String,
        content: String,
        mentions: Map<String, String> = emptyMap(),
    ): Result<String>

    /**
     * Publish a NIP-22 reply (kind:1111). [root] is the kind:11 thread root; [parent] is the item
     * being replied to (pass [root] itself for a top-level reply). [mentions] maps a typed
     * `@displayName` to its pubkey (resolved to `nostr:npub` + a `p` tag).
     */
    suspend fun sendThreadReply(
        groupId: String,
        root: NostrGroupClient.NostrMessage,
        parent: NostrGroupClient.NostrMessage,
        content: String,
        mentions: Map<String, String> = emptyMap(),
    ): Result<Unit>

    /**
     * Add a user to the group (kind:9000, admin only). With [notifyViaDm] a NIP-17 DM
     * carrying the group naddr is sent to the added user after the relay accepts, the
     * only signal that reaches someone who doesn't connect to this NIP-29 relay. Keep
     * it false for role changes and join-request approvals.
     */
    suspend fun addUser(
        groupId: String,
        targetPubkey: String,
        roles: List<String> = emptyList(),
        notifyViaDm: Boolean = false,
    ): Result<Unit>

    suspend fun removeUser(
        groupId: String,
        targetPubkey: String,
    ): Result<Unit>

    suspend fun rejectJoinRequest(
        groupId: String,
        joinRequestEventId: String,
    ): Result<Unit>

    suspend fun createInviteCode(groupId: String): Result<String>

    suspend fun revokeInviteCode(
        groupId: String,
        eventId: String,
    ): Result<Unit>

    suspend fun deleteMessage(
        groupId: String,
        messageId: String,
    ): Result<Unit>

    suspend fun sendReaction(
        groupId: String,
        targetEventId: String,
        targetPubkey: String,
        emoji: String,
        /** Thread root when reacting inside a thread; null for chat reactions. */
        threadRootId: String? = null,
    ): Result<Unit>

    /**
     * Resolve [recipientPubkey]'s LNURL-pay endpoint and fetch a bolt11 invoice for a
     * NIP-57 zap of [amountSats]. The returned invoice must be paid with an external
     * wallet. [eventId] is the zapped message, or null for a profile zap.
     */
    suspend fun requestZapInvoice(
        recipientPubkey: String,
        amountSats: Long,
        comment: String,
        eventId: String?,
    ): Result<ZapManager.ZapInvoice>

    /**
     * Suspend until a zap receipt settling [bolt11] is observed (returns true), or a timeout
     * elapses (returns false). Polls the relays named in the zap request while waiting.
     */
    suspend fun watchZapPayment(
        bolt11: String,
        recipientPubkey: String,
        eventId: String?,
    ): Boolean

    fun getMessagesForGroup(groupId: String): List<NostrGroupClient.NostrMessage>

    /**
     * Pin [groupId]'s relay routing to [relayUrl] (the relay the open route carried).
     * The same id can name independent groups on two relays; without the hint the
     * repo resolves the relay by scanning and can pick the wrong one.
     */
    fun setGroupRelayHint(
        groupId: String,
        relayUrl: String,
    )

    fun markGroupAsRead(
        relayUrl: String,
        groupId: String,
    )

    /** Advance the last-read timestamp for partial-read tracking. See UnreadManager.markAsReadUpTo. */
    fun markGroupAsReadUpTo(
        relayUrl: String,
        groupId: String,
        timestamp: Long,
    )

    fun getUnreadCount(
        relayUrl: String,
        groupId: String,
    ): Int

    fun getLastReadTimestamp(
        relayUrl: String,
        groupId: String,
    ): Long?

    // --- Metadata operations ---
    // [forceStale] re-fetches entries already cached but older than the staleness window
    // (e.g. when the user explicitly opens a profile and wants the latest name/avatar).
    suspend fun requestUserMetadata(pubkeys: Set<String>, forceStale: Boolean = false)

    /** Fetch [pubkey]'s public NIP-29 group list (kind:10009) into [userGroupLists]. */
    suspend fun requestUserGroupList(pubkey: String)

    /**
     * Batched version of [requestUserGroupList] for several authors at once: groups
     * [pubkeys] by their resolved outbox relays and sends one REQ per relay instead of
     * one REQ per author. Use this (not a [requestUserGroupList] loop) whenever fetching
     * more than a handful of authors — e.g. every followed user on login — since relays
     * commonly overlap across a friends list and a per-author loop can open dozens of
     * concurrent subscriptions against the same relay.
     */
    suspend fun fetchUserGroupLists(pubkeys: Set<String>)

    /** Fetch the active account's own kind:3 contact list into [following]. */
    suspend fun requestContactList()

    /** Add [pubkey] to the active account's kind:3 contact list and publish it. */
    suspend fun followUser(pubkey: String): Result<Unit>

    /** Remove [pubkey] from the active account's kind:3 contact list and publish it. */
    suspend fun unfollowUser(pubkey: String): Result<Unit>

    /**
     * Add every pubkey in [pubkeys] not already followed to the active account's kind:3
     * contact list and publish it once (a single event, not one per pubkey).
     */
    suspend fun followUsers(pubkeys: Set<String>): Result<Unit>

    /**
     * Add [pubkey] to the active account's kind:10000 mute list and publish it. New mutes
     * go to the private (NIP-44 self-encrypted) section; falls back to a public `p` tag
     * only while an existing private section can't be decrypted.
     */
    suspend fun muteUser(pubkey: String): Result<Unit>

    /** Remove [pubkey] from the active account's kind:10000 mute list and publish it. */
    suspend fun unmuteUser(pubkey: String): Result<Unit>

    /**
     * Publish a NIP-56 kind:1984 report flagging [pubkey], optionally pinned to their
     * event [eventId], as [type] with an optional free-text [note]. Routed like the
     * other non-group publishes (outbox write + bootstrap relays, never NIP-29 relays):
     * NIP-56 reports live on the reporter's relays for clients and tools to consume.
     */
    suspend fun reportUser(
        pubkey: String,
        type: org.nostr.nostrord.nostr.Nip56.ReportType,
        note: String = "",
        eventId: String? = null,
    ): Result<Unit>

    suspend fun updateProfileMetadata(
        displayName: String? = null,
        name: String? = null,
        about: String? = null,
        picture: String? = null,
        banner: String? = null,
        nip05: String? = null,
        lud16: String? = null,
        website: String? = null,
    ): Result<Unit>

    suspend fun publishRelayList(relays: List<Nip65Relay>): Result<Unit>

    // --- Event operations ---
    suspend fun requestEventById(
        eventId: String,
        relayHints: List<String> = emptyList(),
        author: String? = null,
        /** Group the reference was seen in, so the REQ can carry `#h` for relays that demand it. */
        groupId: String? = null,
    )

    suspend fun requestAddressableEvent(
        kind: Int,
        pubkey: String,
        identifier: String,
        relays: List<String> = emptyList(),
    )

    suspend fun requestQuotedEvent(eventId: String)

    suspend fun requestRelayLists(pubkeys: Set<String>)

    fun getRelayListForPubkey(pubkey: String): List<Nip65Relay>?

    fun selectOutboxRelays(
        authors: List<String> = emptyList(),
        taggedPubkeys: List<String> = emptyList(),
        explicitRelays: List<String> = emptyList(),
    ): List<String>
}

/**
 * Outcome of restoring a backup file. [skipped] counts lines that were not a usable rumor and
 * [duplicates] ones already in this account's history, so a restore that filed less than the file
 * held can say why instead of looking like a silent loss.
 */
data class DmImportSummary(
    val imported: Int,
    val duplicates: Int,
    val skipped: Int,
)
