package org.nostr.nostrord.network.managers

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import org.nostr.nostrord.network.GroupAdmins
import org.nostr.nostrord.network.GroupMembers
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.GroupRoles
import org.nostr.nostrord.network.LiveKitParticipants
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.RoleDefinition
import org.nostr.nostrord.network.groupMetadataListSerializer
import org.nostr.nostrord.network.outbox.EventDeduplicator
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.addLeftGroupForRelay
import org.nostr.nostrord.storage.addRestrictedGroupForRelay
import org.nostr.nostrord.storage.cache.CacheStore
import org.nostr.nostrord.storage.cache.CachedMsg
import org.nostr.nostrord.storage.cache.InMemoryCacheStore
import org.nostr.nostrord.storage.clearGroupMembershipFor
import org.nostr.nostrord.storage.clearMessageBlobMigratedFor
import org.nostr.nostrord.storage.getDeleteWatchCursor
import org.nostr.nostrord.storage.getLeftGroupsForRelay
import org.nostr.nostrord.storage.getPendingGroupInvitesFor
import org.nostr.nostrord.storage.getPutUserCursorForRelay
import org.nostr.nostrord.storage.getRestrictedGroupsForRelay
import org.nostr.nostrord.storage.isFullGroupListCacheFresh
import org.nostr.nostrord.storage.isGroupListCacheFresh
import org.nostr.nostrord.storage.isMessageBlobMigratedFor
import org.nostr.nostrord.storage.loadDroppedGroupIds
import org.nostr.nostrord.storage.loadGroupMembershipFor
import org.nostr.nostrord.storage.removeLeftGroupForRelay
import org.nostr.nostrord.storage.removeRestrictedGroupForRelay
import org.nostr.nostrord.storage.saveDeleteWatchCursor
import org.nostr.nostrord.storage.saveDroppedGroupIds
import org.nostr.nostrord.storage.saveFullGroupListEoseTimestamp
import org.nostr.nostrord.storage.saveGroupListEoseTimestamp
import org.nostr.nostrord.storage.saveGroupMembershipFor
import org.nostr.nostrord.storage.savePendingGroupInvitesFor
import org.nostr.nostrord.storage.savePutUserCursorForRelay
import org.nostr.nostrord.storage.setMessageBlobMigratedFor
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.epochMillis
import org.nostr.nostrord.utils.epochSeconds
import org.nostr.nostrord.utils.groupKey
import org.nostr.nostrord.utils.groupKeyId
import org.nostr.nostrord.utils.groupKeyRelay
import org.nostr.nostrord.utils.isJoinedOn
import org.nostr.nostrord.utils.normalizeRelayUrl
import kotlin.coroutines.cancellation.CancellationException

/**
 * Manages group operations: join, leave, messages, and group metadata.
 * Handles NIP-29 group protocol operations.
 *
 * Uses a formal state machine (GroupLoadingController) for reliable pagination:
 * - Per-group locks prevent blocking unrelated groups
 * - Explicit state transitions prevent race conditions
 * - Immutable cursors prevent pagination corruption
 */
class GroupManager(
    private val connectionManager: ConnectionManager,
    private val scope: CoroutineScope,
    private val pendingEventManager: PendingEventManager? = null,
    private val liveCursorStore: LiveCursorStore = LiveCursorStore(),
    private val connStats: ConnectionStats? = null,
    private val muxTracker: MuxSubscriptionTracker = MuxSubscriptionTracker(),
    private val adaptiveConfig: AdaptiveConfig? = null,
    private val cacheStore: CacheStore = InMemoryCacheStore(),
    private val onNewMessagesFlushed: ((groupId: String, newMessages: List<NostrGroupClient.NostrMessage>) -> Unit)? = null,
    // Forum thread events (kind:11 / 1111) as they land from a relay. Separate from
    // [onNewMessagesFlushed]: threads live in their own stores and never enter the chat
    // batch, so notifications would otherwise never see a thread reply.
    private val onThreadEventReceived: ((groupId: String, event: NostrGroupClient.NostrMessage) -> Unit)? = null,
    /** Mirrors a thread event into the generic by-id event cache the quote cards read. */
    private val onThreadEventCached: ((event: NostrGroupClient.NostrMessage) -> Unit)? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val eventDeduplicator = EventDeduplicator()

    // These two flows MUST be declared before the init blocks below: the coroutines those
    // blocks launch capture them, and a coroutine can run before the constructor finishes.
    // Kotlin assigns fields in declaration order — on Kotlin/Native a not-yet-assigned
    // field read from another thread is a segfault (no null-check on non-null fields).
    private val _messageStatus = MutableStateFlow<Map<String, MessageStatus>>(emptyMap())
    val messageStatus: StateFlow<Map<String, MessageStatus>> = _messageStatus.asStateFlow()

    // Per-relay joined groups cache — the single source of truth for membership.
    private val _joinedGroupsByRelay = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val joinedGroupsByRelay: StateFlow<Map<String, Set<String>>> = _joinedGroupsByRelay.asStateFlow()

    init {
        // The retry queue is the second half of optimistic send: when a queued
        // message is finally accepted (or permanently fails) on reconnect, reflect
        // that into the per-message status so the on-screen indicator resolves.
        pendingEventManager?.onEventDelivered = { eventId -> markDelivered(eventId) }
        pendingEventManager?.onEventPermanentlyFailed = { eventId, groupId, eventJson, reason ->
            markFailed(eventId, groupId, eventJson, reason)
        }
        // Queued retries must hit the relay that hosts their group: a NIP-29 relay
        // rejects a kind:9 for a group it doesn't host ("group doesn't exist").
        pendingEventManager?.resolveClient = { groupId ->
            // Fail closed on auth-gating relays too: an unauthed publish gets OK-false
            // "auth-required", which used to fail the event permanently. Returning null
            // keeps it Queued for the sweep / AUTH-completion flush; relays that never
            // challenged publish immediately.
            val authBudgetMs = if (org.nostr.nostrord.auth.ActiveAccountManager.session.value?.signer?.isRemote == true) {
                12_000L
            } else {
                2_000L
            }
            clientForGroup(groupId)?.takeIf { !it.requiresAuth() || it.awaitAuthSigned(authBudgetMs) }
        }
        // Any event sitting in the retry queue is an undelivered own message. Marking it
        // Sending here covers events restored from the persisted queue after a restart,
        // whose cached bubble would otherwise look delivered.
        pendingEventManager?.let { pending ->
            scope.launch {
                pending.pendingEvents.collect { events ->
                    if (events.isEmpty()) return@collect
                    _messageStatus.update { statuses ->
                        val missing = events.filter { it.eventId !in statuses }
                        if (missing.isEmpty()) {
                            statuses
                        } else {
                            statuses + missing.associate { it.eventId to MessageStatus.Sending }
                        }
                    }
                }
            }
        }
    }

    /**
     * Collects incoming messages per group, then applies them to [_messages]
     * in a single sorted batch — reducing N StateFlow emissions to 1 during burst loads.
     * Window is dynamic: reads from [AdaptiveConfig.bufferWindowMs] at each enqueue.
     */
    private val eventOrderingBuffer = EventOrderingBuffer(
        scope = scope,
        windowProvider = { adaptiveConfig?.bufferWindowMs ?: EventOrderingBuffer.WINDOW_MS },
    ) { groupId, messages ->
        flushBatchToState(groupId, messages)
    }

    // Persistent per-group message ID index — avoids rebuilding a HashSet on every flush.
    // Updated incrementally in flushBatchToState and loadMessagesFromStorage.
    // Cleared on relay switch / logout.
    private val messageIdIndex = mutableMapOf<String, MutableSet<String>>()

    // Group-message recency, least-recent first (re-inserted on touch). Drives whole-group
    // eviction past [MAX_GROUPS_IN_MEMORY] so in-memory history stays bounded over a long
    // multi-group session. Per-group message-count capping is deferred to the disk-backed
    // history store (plan phase 4): trimming a live list here would desync messageIdIndex
    // and stop a re-fetched older message from re-rendering.
    private val groupMessageRecency = LinkedHashSet<String>()

    private val _groups = MutableStateFlow<List<GroupMetadata>>(emptyList())
    val groups: StateFlow<List<GroupMetadata>> = _groups.asStateFlow()

    // Per-relay group cache — persists across relay switches.
    private val _groupsByRelay = MutableStateFlow<Map<String, List<GroupMetadata>>>(emptyMap())
    val groupsByRelay: StateFlow<Map<String, List<GroupMetadata>>> = _groupsByRelay.asStateFlow()

    // Tracks which relay is currently active and which relays have fully loaded
    // (i.e. received EOSE for the "group-list" subscription).
    private val _currentRelayUrl = MutableStateFlow<String?>(null)
    private var currentRelayUrl: String?
        get() = _currentRelayUrl.value
        set(value) {
            _currentRelayUrl.value = value
        }
    private val _completeGroupLoadRelays = MutableStateFlow<Set<String>>(emptySet())

    /** Relays that finished serving their group list (EOSE this session); gates the "group no
     * longer available" check so a not-yet-loaded relay doesn't read as deleted. */
    val completeGroupLoadRelays: StateFlow<Set<String>> = _completeGroupLoadRelays.asStateFlow()

    /**
     * Joined groups on a relay that have no corresponding `kind:39000` after the
     * relay finished serving its group list (EOSE received). These are stale pins
     * from `kind:10009` — the group was deleted while offline, or the relay
     * dropped it. UI surfaces them so the user can explicitly forget them.
     */
    private val _orphanedJoinedByRelay = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val orphanedJoinedByRelay: StateFlow<Map<String, Set<String>>> = _orphanedJoinedByRelay.asStateFlow()

    // The group currently being viewed by the user.
    // Mux chat/reactions subscriptions are scoped to this group only.
    private var _activeGroupId: String? = null
    val activeGroupId: String? get() = _activeGroupId

    // Groups that have been opened (clicked) by the user during this session.
    // Chat/reactions mux covers these groups so they keep receiving live messages.
    // Only cleared on full disconnect/logout, NOT on relay switch.
    // Backed by StateFlow for thread-safe reads/writes from any dispatcher.
    private val _openedGroupIds = MutableStateFlow<Set<String>>(emptySet())

    // NIP-29 subgroups — topology derived from `parent` tags in kind:39000.
    // Tree is built client-side: groups without a parent tag are roots, the
    // rest are grouped under the `d` referenced by their `parent` tag. The
    // relay keeps parent/child bilateral and validated, so a declared link
    // is trusted as-is.
    private val _childrenByParent = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val childrenByParent: StateFlow<Map<String, Set<String>>> = _childrenByParent.asStateFlow()

    /**
     * Groups the user locally deleted. We ignore incoming kind:39000 for these ids so a
     * re-emitted metadata event (relay replaying, or a relay that doesn't actually honor
     * 9008) doesn't resurrect the group into "Other Groups".
     */
    // Groups deleted or forgotten on this device, keyed by [droppedKey] (relay + group). Relay-scoped
    // for the same reason the left markers are: a bare id blocks the kind:10009 merge for the same-id
    // group on EVERY relay, and unlike a left marker nothing self-heals it.
    private val deletedGroupIds = mutableSetOf<String>()

    // Debounce mux refresh: coalesces rapid calls (auth + EOSE + CLOSED) into one.
    private val muxRefreshJobs = mutableMapOf<String, Job>()

    // Request cooldown: prevents duplicate in-flight requests within a short window.
    // Key = "type:groupId", value = epochMillis when last requested.
    private val recentRequests = mutableMapOf<String, Long>()

    /**
     * Returns true if this request type for this group hasn't been issued recently.
     * Prevents duplicate REQs when multiple triggers fire within [cooldownMs].
     */
    private fun shouldRequest(groupId: String, type: String, cooldownMs: Long? = null): Boolean {
        val key = "$type:$groupId"
        val now = epochMillis()
        val effectiveCooldown = cooldownMs
            ?: adaptiveConfig?.requestCooldownMs
            ?: REQUEST_COOLDOWN_MS
        val last = recentRequests[key] ?: 0L
        if (now - last < effectiveCooldown) {
            connStats?.onRequestAvoided(getRelayForGroup(groupId) ?: "unknown")
            return false
        }
        recentRequests[key] = now
        return true
    }

    // Relays for which requestGroups() was sent but EOSE hasn't arrived yet.
    // Used by the UI to show skeleton loaders while groups are being fetched.
    private val _loadingRelays = MutableStateFlow<Set<String>>(emptySet())
    val loadingRelays: StateFlow<Set<String>> = _loadingRelays.asStateFlow()

    // Safety-net timers so the sidebar skeleton can never spin forever. Every
    // clear of [_loadingRelays] is otherwise event-driven (group-list EOSE,
    // CLOSED, or a connection Error/Reconnecting). A relay that connects, accepts
    // the REQ, then never sends EOSE nor CLOSED — common after a slow/flaky
    // NIP-46 bunker AUTH where the 2s awaitAuthOrTimeout races the signer — would
    // leave the relay stuck in _loadingRelays indefinitely (#88). Each
    // markRelayLoading arms a per-relay watchdog; markRelayLoaded / EOSE cancel it.
    /**
     * Guards [loadingWatchdogs]. Its entries are written from relay-pipeline threads (arm on REQ,
     * remove on EOSE), from each watchdog's own finally, and read wholesale by [clear] on account
     * switch. A plain map iterated while another thread removes throws out of the iterator and
     * kills the coroutine, so every access goes through this lock.
     */
    private val loadingWatchdogsLock = SynchronizedObject()
    private val loadingWatchdogs = mutableMapOf<String, Job>()
    private val loadingWatchdogTimeoutMs = 30_000L

    // Relays for which requestGroups() (unfiltered, no #d tag) was sent this session but
    // EOSE hasn't arrived yet, mapped to the epoch-seconds the REQ was sent. Lets
    // handleEoseSuspend() distinguish a full fetch from a lazy (joined-only) fetch when
    // the same sub ID is reused. The timestamp makes the dedup self-expiring: a REQ that
    // never gets EOSE/CLOSED stops blocking retries after [pendingFullFetchStaleSeconds]
    // without the loading watchdog having to erase the marker (which would mis-classify a
    // genuinely slow EOSE that arrives later as a partial fetch).
    private val pendingFullFetchRelays = mutableMapOf<String, Long>()
    private val pendingFullFetchStaleSeconds = 30L

    // Group IDs seen during an in-flight full fetch; cleared on EOSE to prune stale groups.
    private val pendingFetchSeenGroups = mutableMapOf<String, MutableSet<String>>()

    // Expected-vs-seen kind:39000 ids for the in-flight mux metadata REQ, per normalized
    // relay. Consumed once at that REQ's EOSE by [reconcileMuxMetadataEose].
    private val muxMetaExpectedIds = mutableMapOf<String, Set<String>>()
    private val muxMetaSeenIds = mutableMapOf<String, MutableSet<String>>()

    fun markPendingFullFetch(relayUrl: String) {
        val normalized = relayUrl.normalizeRelayUrl()
        pendingFullFetchRelays[normalized] = epochSeconds()
        pendingFetchSeenGroups[normalized] = mutableSetOf()
    }

    /**
     * Remove [relayUrl] from [pendingFullFetchRelays] without marking the full list as fetched.
     * Call this when a partial (joined-only) REQ is sent after a full REQ was already marked
     * pending, to prevent the partial EOSE from incorrectly setting fullGroupListFetchedRelays.
     */
    fun cancelPendingFullFetch(relayUrl: String) {
        pendingFullFetchRelays.remove(relayUrl.normalizeRelayUrl())
    }

    /**
     * Returns true if [relayUrl] has a pending full-fetch REQ that hasn't received EOSE yet.
     * Used by [NostrRepository.requestFullGroupListForRelay] to skip a duplicate REQ when
     * [requestGroupsForRelay] already scheduled a full fetch on connect.
     */
    fun hasPendingFullFetch(relayUrl: String): Boolean {
        val sentAt = pendingFullFetchRelays[relayUrl.normalizeRelayUrl()] ?: return false
        // A marker older than the stale window means the REQ got neither EOSE nor
        // CLOSED; stop treating it as in-flight so a re-open of OTHER GROUPS can retry.
        return epochSeconds() - sentAt < pendingFullFetchStaleSeconds
    }

    // Relays whose FULL group list (unfiltered requestGroups()) EOSE was received this session.
    // Updated by handleEoseSuspend when the relay is in pendingFullFetchRelays.
    private val _fullGroupListFetchedRelays = MutableStateFlow<Set<String>>(emptySet())
    val fullGroupListFetchedRelays: StateFlow<Set<String>> = _fullGroupListFetchedRelays.asStateFlow()

    fun hasFullGroupListBeenFetched(relayUrl: String): Boolean {
        val normalized = relayUrl.normalizeRelayUrl()
        if (normalized in _fullGroupListFetchedRelays.value) return true
        return SecureStorage.isFullGroupListCacheFresh(normalized, epochSeconds())
    }

    /**
     * Discard the IN-MEMORY record that the FULL group list was fetched for
     * [relayUrl] this session. Called once per relay when it completes NIP-42
     * AUTH: an auth-required relay answers the pre-AUTH unauthenticated
     * group-list REQ with an EMPTY EOSE, which handleEoseSuspend records as
     * "fully fetched", falsely satisfying the dedup in resubscribeAfterAuth and
     * suppressing the real post-AUTH fetch — leaving OTHER GROUPS empty even when
     * expanded (observed on hzrd149's relay). Deliberately does NOT touch the
     * persisted freshness timestamp: that survives across sessions and clearing
     * it on every auth would defeat the cross-session cache.
     */
    fun invalidateFullGroupListFetch(relayUrl: String) {
        _fullGroupListFetchedRelays.update { it - relayUrl.normalizeRelayUrl() }
    }

    fun markRelayLoading(relayUrl: String) {
        val normalized = relayUrl.normalizeRelayUrl()
        _loadingRelays.update { it + normalized }
        // (Re)arm the watchdog: if no EOSE/CLOSED/error clears this relay within
        // the grace period, force-clear ONLY the skeleton flag so it stops
        // spinning. The pending-full-fetch dedup is released separately by the
        // self-expiring timestamp in hasPendingFullFetch — the watchdog must not
        // erase pendingFullFetchRelays, or a genuinely slow EOSE arriving after the
        // timeout would be mis-classified as a partial fetch (isFull=false), skip
        // pruning, and never mark the relay fully fetched.
        val previous = synchronized(loadingWatchdogsLock) { loadingWatchdogs.remove(normalized) }
        previous?.cancel()
        // Lazy so the job is in the map before its body (and its finally) can run; started eagerly
        // it could self-remove before the entry exists and leave a dead job behind.
        val watchdog =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    delay(loadingWatchdogTimeoutMs)
                    _loadingRelays.update { it - normalized }
                } finally {
                    // Remove self only if still the current watchdog for this relay, so a
                    // watchdog re-armed by a concurrent markRelayLoading isn't evicted
                    // (which would leave it uncancellable and able to clear a later fetch).
                    val self = coroutineContext[Job]
                    synchronized(loadingWatchdogsLock) {
                        if (loadingWatchdogs[normalized] === self) loadingWatchdogs.remove(normalized)
                    }
                }
            }
        synchronized(loadingWatchdogsLock) { loadingWatchdogs[normalized] = watchdog }
        watchdog.start()
    }

    fun markRelayLoaded(relayUrl: String) {
        val normalized = relayUrl.normalizeRelayUrl()
        _loadingRelays.update { it - normalized }
        synchronized(loadingWatchdogsLock) { loadingWatchdogs.remove(normalized) }?.cancel()
    }

    /**
     * Suspends until the relay's `group-list` EOSE has arrived (i.e. it appears
     * in [_completeGroupLoadRelays]), or until [timeoutMs] elapses. Returns true
     * if EOSE was observed, false if the relay timed out without emitting one.
     *
     * Used by callers that need to act on the populated group cache after
     * `requestGroups()` — preferred over a fixed `delay(N)` so fast relays
     * proceed immediately and slow relays still get bounded latency.
     */
    suspend fun awaitGroupListEose(relayUrl: String, timeoutMs: Long = 5_000): Boolean {
        val normalized = relayUrl.normalizeRelayUrl()
        if (normalized in _completeGroupLoadRelays.value) return true
        return withTimeoutOrNull(timeoutMs) {
            _completeGroupLoadRelays.first { normalized in it }
            true
        } ?: false
    }

    private val _messages = MutableStateFlow<Map<String, List<NostrGroupClient.NostrMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<NostrGroupClient.NostrMessage>>> = _messages.asStateFlow()

    // Forum threads live in their own stores, keyed by group id, so kind:11 roots and their
    // NIP-22 kind:1111 replies never enter the chat timeline (_messages) or its pagination.
    // Replies are kept per-group and grouped by their root `E` tag in the ViewModel.
    private val _threadRoots = MutableStateFlow<Map<String, List<NostrGroupClient.NostrMessage>>>(emptyMap())
    val threadRoots: StateFlow<Map<String, List<NostrGroupClient.NostrMessage>>> = _threadRoots.asStateFlow()

    private val _threadReplies = MutableStateFlow<Map<String, List<NostrGroupClient.NostrMessage>>>(emptyMap())
    val threadReplies: StateFlow<Map<String, List<NostrGroupClient.NostrMessage>>> = _threadReplies.asStateFlow()

    // Group ids whose thread-roots subscription has received its EOSE (stored events done), so the
    // ViewModel can show "No threads yet" only once loading is genuinely finished, not on a guess.
    private val _threadsLoaded = MutableStateFlow<Set<String>>(emptySet())
    val threadsLoaded: StateFlow<Set<String>> = _threadsLoaded.asStateFlow()

    /** Mark a group's thread-roots subscription as having reached EOSE. Called from the EOSE handler. */
    fun markThreadsLoaded(groupId: String) {
        _threadsLoaded.update { it + groupId }
    }

    /**
     * Delivery status of the local user's own messages (optimistic send).
     * Sending = the message is on screen and awaiting relay confirmation (or
     * queued for auto-retry); Delivered = the relay confirmed a send made this
     * session (renders a check); Failed = the relay rejected it, retries were
     * exhausted, or signing failed. Messages with no entry (history, other
     * sessions) render without an indicator. Keyed by event id.
     */
    sealed interface MessageStatus {
        data object Sending : MessageStatus

        data object Delivered : MessageStatus

        data class Failed(
            val reason: String,
            val groupId: String,
            // The signed JSON when a relay rejected an already-signed event; null when
            // signing itself failed, in which case [unsignedEvent] is set so retry
            // re-signs before delivering. Exactly one of the two is non-null.
            val eventJson: String?,
            val unsignedEvent: Event? = null,
        ) : MessageStatus
    }

    // Active-relay view — derived so it can never drift from _joinedGroupsByRelay.
    val joinedGroups: StateFlow<Set<String>> = combine(
        _joinedGroupsByRelay,
        _currentRelayUrl,
    ) { map, relay ->
        relay?.normalizeRelayUrl()?.let { map[it] } ?: emptySet()
    }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    // Synchronous active-relay view for internal use (avoids stateIn dispatch lag).
    private val activeJoinedGroups: Set<String>
        get() = currentRelayUrl?.normalizeRelayUrl()?.let { _joinedGroupsByRelay.value[it] } ?: emptySet()

    // Orphan derivation: joined pins with no kind:39000 on a relay that finished its
    // group list. This init MUST sit below the declarations of every flow the combine
    // reads (_groupsByRelay, _joinedGroupsByRelay, _completeGroupLoadRelays): the
    // launched coroutine can run while the constructor is still assigning later
    // fields, and a not-yet-assigned StateFlow reaches combine as null (cold-boot
    // NPE in CombineKt, crashed 2.0.1 on open).
    init {
        scope.launch {
            kotlinx.coroutines.flow.combine(
                _groupsByRelay,
                _joinedGroupsByRelay,
                _completeGroupLoadRelays,
            ) { groupsMap, joinedMap, doneRelays ->
                doneRelays.associateWith { relay ->
                    val known = groupsMap[relay].orEmpty().map { it.id }.toSet()
                    val joined = joinedMap[relay].orEmpty()
                    joined - known
                }.filterValues { it.isNotEmpty() }
            }.collect { _orphanedJoinedByRelay.value = it }
        }
    }

    // ==========================================================================
    // STATE MACHINE: Per-group loading controller with formal state transitions
    // ==========================================================================
    private val loadingRegistry = GroupLoadingRegistry(scope, PAGE_SIZE, LOADING_TIMEOUT_MS)

    // Track active group states for UI binding
    private val _groupStates = MutableStateFlow<Map<String, GroupLoadingState>>(emptyMap())
    val groupStates: StateFlow<Map<String, GroupLoadingState>> = _groupStates.asStateFlow()

    // Groups whose initial read is waiting on NIP-42 AUTH (held by holdInitialLoadForReauth).
    // The UI treats these as still-loading so it shows skeletons across the whole pre-AUTH
    // dance (pre-AUTH CLOSE -> AUTH -> resubscribeAfterAuth reset/replay), which briefly
    // bounces the controller through Idle, instead of flashing "No messages yet". Cleared
    // the moment the controller reaches a settled state (an authenticated read completed).
    private val _groupsAwaitingAuthRead = MutableStateFlow<Set<String>>(emptySet())
    val groupsAwaitingAuthRead: StateFlow<Set<String>> = _groupsAwaitingAuthRead.asStateFlow()

    // Legacy API compatibility: derive isLoadingMore from state machine
    private val _isLoadingMore = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val isLoadingMore: StateFlow<Map<String, Boolean>> = _isLoadingMore.asStateFlow()

    // Legacy API compatibility: derive hasMoreMessages from state machine
    private val _hasMoreMessages = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val hasMoreMessages: StateFlow<Map<String, Boolean>> = _hasMoreMessages.asStateFlow()

    /**
     * Info about reactions for a specific emoji on a message.
     */
    data class ReactionInfo(
        val emojiUrl: String?, // URL for custom emoji (NIP-30), null for standard emojis
        val reactors: List<String>, // List of pubkeys who reacted with this emoji
    )

    // Global emoji shortcode → URL cache, populated from every emoji tag we see.
    // Used as fallback when a kind-7 reaction event omits the emoji tag.
    // LRU-bounded to 2000 entries to prevent unbounded memory growth.
    // Uses KMP-compatible LruCache (not java.util.LinkedHashMap).
    private val emojiUrlCache = org.nostr.nostrord.utils.LruCache<String, String>(2000)

    // Reactions: messageId -> (emoji -> ReactionInfo)
    private val _reactions = MutableStateFlow<Map<String, Map<String, ReactionInfo>>>(emptyMap())
    val reactions: StateFlow<Map<String, Map<String, ReactionInfo>>> = _reactions.asStateFlow()

    // Reaction debounce: coalesces rapid reaction arrivals into a single StateFlow emission.
    // During burst loads (initial EOSE with many reactions), this reduces N emissions to ~1.
    // Pending changes accumulate in _pendingReactions and are flushed to _reactions after
    // REACTION_DEBOUNCE_MS of inactivity.
    private val _pendingReactions = mutableMapOf<String, Map<String, ReactionInfo>>()
    private var reactionFlushJob: Job? = null

    /** Flush all pending reaction changes to the StateFlow immediately. */
    private fun flushPendingReactions() {
        if (_pendingReactions.isEmpty()) return
        val pending = _pendingReactions.toMap()
        _pendingReactions.clear()
        _reactions.value = _reactions.value + pending
    }

    // Group members from kind 39002: groupId -> list of member pubkeys
    private val _groupMembers = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val groupMembers: StateFlow<Map<String, List<String>>> = _groupMembers.asStateFlow()

    // Groups whose member list request has been sent but no kind:39002 response yet.
    // Cleared when handleGroupMembers stores the response, or after a timeout.
    private val _loadingMembers = MutableStateFlow<Set<String>>(emptySet())
    val loadingMembers: StateFlow<Set<String>> = _loadingMembers.asStateFlow()

    // Group admins from kind 39001: groupId -> list of admin pubkeys
    private val _groupAdmins = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val groupAdmins: StateFlow<Map<String, List<String>>> = _groupAdmins.asStateFlow()

    // Group roles from kind 39003: groupId -> list of role definitions
    private val _groupRoles = MutableStateFlow<Map<String, List<RoleDefinition>>>(emptyMap())
    val groupRoles: StateFlow<Map<String, List<RoleDefinition>>> = _groupRoles.asStateFlow()

    // Live AV participants from kind 39004: groupId -> participant pubkeys. Relay-owned live
    // state, so it is never persisted: a snapshot restored on cold start would show people in
    // a room they left hours ago.
    private val _liveKitParticipants = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val liveKitParticipants: StateFlow<Map<String, List<String>>> = _liveKitParticipants.asStateFlow()

    // Timestamp guards: reject stale kind:39001/39002 events from slower relays.
    private val memberEventTimestamps = mutableMapOf<String, Long>()
    private val adminEventTimestamps = mutableMapOf<String, Long>()
    private val roleEventTimestamps = mutableMapOf<String, Long>()
    private val liveKitEventTimestamps = mutableMapOf<String, Long>()

    // Per-relay mirror of the kind:39001/2/3 lists (relay -> groupId -> list). The flat
    // maps above stay the compat view where the last relay to publish wins on an id
    // collision; relay-scoped readers (GroupViewModel with a host relay, home cards)
    // prefer these so same-id groups on two relays don't overwrite each other's lists.
    private val _groupMembersByRelay = MutableStateFlow<Map<String, Map<String, List<String>>>>(emptyMap())
    val groupMembersByRelay: StateFlow<Map<String, Map<String, List<String>>>> = _groupMembersByRelay.asStateFlow()
    private val _groupAdminsByRelay = MutableStateFlow<Map<String, Map<String, List<String>>>>(emptyMap())
    val groupAdminsByRelay: StateFlow<Map<String, Map<String, List<String>>>> = _groupAdminsByRelay.asStateFlow()
    private val _groupRolesByRelay = MutableStateFlow<Map<String, Map<String, List<RoleDefinition>>>>(emptyMap())
    val groupRolesByRelay: StateFlow<Map<String, Map<String, List<RoleDefinition>>>> = _groupRolesByRelay.asStateFlow()

    // Per-(relay, group) staleness guards for the mirrors: the flat guards are bare-id,
    // so one relay's newer event must not get the other relay's list rejected as stale.
    private val scopedMemberEventTimestamps = mutableMapOf<String, Long>()
    private val scopedAdminEventTimestamps = mutableMapOf<String, Long>()
    private val scopedRoleEventTimestamps = mutableMapOf<String, Long>()

    /** Write one relay's kind:39001/2/3 list into its per-relay mirror, under its own guard. */
    private fun <T> updateRelayScopedList(
        flow: MutableStateFlow<Map<String, Map<String, T>>>,
        guards: MutableMap<String, Long>,
        relayUrl: String?,
        groupId: String,
        createdAt: Long,
        value: T,
    ) {
        val relay = relayUrl?.normalizeRelayUrl() ?: return
        val key = "$relay|$groupId"
        val existing = guards[key] ?: 0L
        if (createdAt > 0L && createdAt < existing) return
        if (createdAt > 0L) guards[key] = createdAt
        flow.update { byRelay ->
            val forRelay = byRelay[relay].orEmpty()
            if (forRelay[groupId] == value) byRelay else byRelay + (relay to (forRelay + (groupId to value)))
        }
    }

    // Groups whose subscriptions were closed with "restricted" by the relay, keyed by
    // [groupKey] -> reason. For private groups the relay denies access to non-members
    // (including metadata); the UI shows a "Private Group" placeholder with invite input.
    // Access is granted per relay, so a denial here never gates the same id elsewhere.
    private val _restrictedGroups = MutableStateFlow<Map<String, String>>(emptyMap())
    val restrictedGroups: StateFlow<Map<String, String>> = _restrictedGroups.asStateFlow()

    // Durable "I explicitly left this group" intent (kind:9022 sent), persisted per-account/per-relay
    // with a 30-day TTL and restored on cold start. This is the authoritative override the membership
    // derivation checks BEFORE the relay's kind:39002, so a left group reads as not-a-member ("Request
    // to Join") even when the relay keeps listing us (some relays do after a 9022). A rejoin clears it.
    //
    // Keyed by relay, THEN group id. The same id names independent groups on different relays
    // ("nostrord" exists on several), and the storage slot behind this is already per-relay, so a
    // bare-id marker made leaving one of them read as leaving all of them — with no way back, since
    // the self-heal then cleared the marker from the wrong relay's slot and the restore re-flattened
    // it on every cold start.
    private val _leftGroups = MutableStateFlow<Map<String, Map<String, Long>>>(emptyMap())
    val leftGroups: StateFlow<Map<String, Set<String>>> =
        _leftGroups
            .map { byRelay -> byRelay.mapValues { (_, marks) -> marks.keys } }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    // Lets clientForGroup reconnect a group's own relay on demand. Wired by NostrRepository.
    var messageHandler: ((String, NostrGroupClient) -> Unit)? = null

    // Gate for the approval-recovery path: only fires when the user actually
    // sent kind:9021, never for historical events on already-joined groups.
    // Exposed as a StateFlow so the membership derivation reads "I have an outstanding join request"
    // from this LOCAL truth (set on our 9021, cleared on our 9022/leave/approval) instead of the
    // kind:9021 echo in the message feed, which leaveGroup clears and a re-fetch races, leaving a
    // left group stuck on "Request pending" until a reload.
    /** [groupKey] -> seconds of our outstanding kind:9021 on that relay. */
    private val _pendingApprovalSince = MutableStateFlow<Map<String, Long>>(emptyMap())
    val pendingApprovalSince: StateFlow<Map<String, Long>> = _pendingApprovalSince.asStateFlow()

    /** Drops the pending marker for [groupId] on [relayUrl], or on every relay when it is unknown. */
    private fun clearPendingApproval(relayUrl: String?, groupId: String) {
        _pendingApprovalSince.update { pending ->
            if (relayUrl != null) {
                pending - groupKey(relayUrl, groupId)
            } else {
                pending.filterKeys { groupKeyId(it) != groupId }
            }
        }
    }

    // Drops the relay's kind:9022 echo and updated kind:39002 that arrive in
    // the milliseconds after a leave, otherwise they re-populate the lists we
    // just cleared and the UI shows zombie members + a "you left" message.
    // Keyed by [recentLeaveKey] (relay + group), like the durable marker above.
    private val recentlyLeftAt = mutableMapOf<String, Long>()

    /**
     * True when the group was deleted or left on THIS device. Stale kind:10009
     * merges skip these so a lagging relay cannot resurrect them.
     */
    fun isLocallyDropped(groupId: String, relayUrl: String? = null): Boolean = isDropped(relayUrl, groupId) || isRecentlyLeft(relayUrl, groupId)

    /**
     * Key of a dropped-group entry. Relay first, so the relay part can never contain the
     * separator and splitting on the FIRST '|' is unambiguous even for a group id that has one.
     */
    private fun droppedKey(relayUrl: String, groupId: String): String = "${relayUrl.normalizeRelayUrl()}|$groupId"

    /** Whether [groupId] was deleted/forgotten on [relayUrl]; a null relay scans every relay. */
    private fun isDropped(relayUrl: String?, groupId: String): Boolean = if (relayUrl != null) {
        droppedKey(relayUrl, groupId) in deletedGroupIds
    } else {
        deletedGroupIds.any { it.substringAfter('|', "") == groupId }
    }
    private val LEFT_GROUP_GRACE_MS = 5_000L

    // Relay-of-origin per groupId, recorded from the WebSocket that delivered
    // each event. Used by notifications to route clicks to the correct relay
    // when getRelayForGroup would be ambiguous.
    private val _latestMessageRelayByGroup = MutableStateFlow<Map<String, String>>(emptyMap())
    fun getLatestMessageRelayForGroup(groupId: String): String? = _latestMessageRelayByGroup.value[groupId]

    // Relay the open group screen navigated to (route-carried, normalized). The same id
    // can name two independent groups on two relays; the hint pins the opened group's
    // reads/sends to the relay the user actually chose instead of the first relay the
    // by-id scan finds. Last-opened wins while both are loaded (per-group stores are
    // still keyed by bare id).
    private val groupRelayHints = MutableStateFlow<Map<String, String>>(emptyMap())

    fun setGroupRelayHint(groupId: String, relayUrl: String) {
        val normalized = relayUrl.normalizeRelayUrl()
        groupRelayHints.update { if (it[groupId] == normalized) it else it + (groupId to normalized) }
    }

    private fun recentLeaveKey(relayUrl: String, groupId: String): String = "${relayUrl.normalizeRelayUrl()}\u0000$groupId"

    /**
     * Leave grace window for [groupId] on [relayUrl]. A null relay means the event carried no
     * relay of origin, and falls back to any relay's window: this guard only suppresses echoes
     * of our own leave for 5s, so a cross-relay false positive there is bounded and self-clearing.
     */
    private fun isRecentlyLeft(relayUrl: String?, groupId: String): Boolean {
        val keys =
            if (relayUrl != null) {
                listOf(recentLeaveKey(relayUrl, groupId))
            } else {
                recentlyLeftAt.keys.filter { it.endsWith("\u0000$groupId") }.toList()
            }
        var recent = false
        for (key in keys) {
            val at = recentlyLeftAt[key] ?: continue
            if (epochMillis() - at < LEFT_GROUP_GRACE_MS) recent = true else recentlyLeftAt.remove(key)
        }
        return recent
    }

    /** Durable leave timestamp for [groupId] on [relayUrl]; null relay scans every relay. */
    private fun leftAtOn(relayUrl: String?, groupId: String): Long? {
        val relay = relayUrl?.normalizeRelayUrl()
            ?: return _leftGroups.value.values.firstNotNullOfOrNull { it[groupId] }
        return _leftGroups.value[relay]?.get(groupId)
    }

    private fun markLeft(relayUrl: String, groupId: String, atSeconds: Long) {
        val relay = relayUrl.normalizeRelayUrl()
        _leftGroups.update { it + (relay to (it[relay].orEmpty() + (groupId to atSeconds))) }
    }

    /**
     * A kind:9021 rejection that means "you are already in this group". Relays word it freely
     * ("duplicate: already a member", "invalid: user already in group"), so match on the shape.
     * Treated as a successful join so the local markers get reconciled with the relay's truth.
     */
    private fun isAlreadyMemberRejection(reason: String?): Boolean {
        val text = reason?.lowercase() ?: return false
        if ("already" !in text) return false
        return "member" in text || "in group" in text || "in the group" in text || "joined" in text
    }

    private fun clearLeft(relayUrl: String, groupId: String) {
        val relay = relayUrl.normalizeRelayUrl()
        _leftGroups.update { current ->
            val marks = current[relay] ?: return@update current
            if (groupId !in marks) return@update current
            current + (relay to (marks - groupId))
        }
    }

    // When a group was joined this session. Auto-forget skips groups joined within
    // RECENT_JOIN_GRACE_MS so a just-joined group whose kind:39000 is still in flight is
    // never mistaken for a deleted orphan.
    private val recentlyJoinedAt = mutableMapOf<String, Long>()
    private val RECENT_JOIN_GRACE_MS = 60_000L

    fun markRecentlyJoined(groupId: String) {
        recentlyJoinedAt[groupId] = epochMillis()
    }

    private fun isRecentlyJoined(groupId: String): Boolean {
        val at = recentlyJoinedAt[groupId] ?: return false
        return epochMillis() - at <= RECENT_JOIN_GRACE_MS
    }

    /**
     * Orphaned joined pins safe to auto-forget: groups still missing kind:39000 after their
     * relay finished serving its group list (so the relay was asked and does not have them —
     * deleted, or filed under the wrong relay). Two guards keep this from removing real
     * groups: skip any group joined within [RECENT_JOIN_GRACE_MS] (metadata may still be
     * arriving), and skip any relay where EVERY joined group is an orphan — a relay glitch or
     * transient data loss must never wipe the user's groups; those stay as manual-forget
     * orphans. Returns relay -> forgettable group ids.
     */
    fun autoForgettableOrphans(): Map<String, Set<String>> {
        val orphans = _orphanedJoinedByRelay.value
        if (orphans.isEmpty()) return emptyMap()
        val now = epochMillis()
        val joined = _joinedGroupsByRelay.value
        return orphans
            .mapNotNull { (relay, orphanIds) ->
                val joinedOnRelay = joined[relay].orEmpty()
                // Glitch guard: a healthy relay served metadata for at least one joined group.
                if (joinedOnRelay.isEmpty() || orphanIds.size >= joinedOnRelay.size) {
                    return@mapNotNull null
                }
                val safe =
                    orphanIds.filterTo(mutableSetOf()) { id ->
                        val at = recentlyJoinedAt[id]
                        at == null || now - at > RECENT_JOIN_GRACE_MS
                    }
                if (safe.isEmpty()) null else relay to (safe as Set<String>)
            }.toMap()
    }

    /**
     * Mark a group as restricted (relay denied access).
     * Called when a CLOSED "restricted" message arrives for a group subscription.
     *
     * Kicks off a debounced mux refresh so mux_meta / mux_del / mux_chat are
     * re-sent with this group excluded from the #d/#h batch — otherwise the
     * relay would keep CLOSE-ing the whole mux whenever this group is in it,
     * starving the other joined groups of metadata/delete updates.
     */
    fun markGroupRestricted(relayUrl: String, groupId: String, reason: String) {
        val host = relayUrl.normalizeRelayUrl()
        val key = groupKey(host, groupId)
        val wasAlreadyRestricted = key in _restrictedGroups.value
        _restrictedGroups.update { it + (key to reason) }
        if (!wasAlreadyRestricted) {
            currentPubkey?.let { pk ->
                try {
                    SecureStorage.addRestrictedGroupForRelay(pk, host, groupId, reason, epochSeconds())
                } catch (_: Exception) {}
            }
            refreshMuxDebounced(host)
        }
    }

    /**
     * Clear restricted status for a group (e.g. after successful join).
     *
     * A null [relayUrl] clears the marker on every relay serving [groupId]; use it only where
     * the origin is genuinely unknown, since access is per relay.
     */
    fun clearGroupRestricted(relayUrl: String?, groupId: String) {
        val relays =
            if (relayUrl != null) {
                listOf(relayUrl.normalizeRelayUrl())
            } else {
                _restrictedGroups.value.keys.filter { groupKeyId(it) == groupId }.map { groupKeyRelay(it) }
            }
        val keys = relays.map { groupKey(it, groupId) }.filter { it in _restrictedGroups.value }
        if (keys.isEmpty()) return
        _restrictedGroups.update { it - keys.toSet() }
        currentPubkey?.let { pk ->
            keys.forEach { key ->
                try {
                    SecureStorage.removeRestrictedGroupForRelay(pk, groupKeyRelay(key), groupId)
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Load persisted restricted groups for [relayUrls] into [_restrictedGroups].
     * Called on startup before the first connect, so batched REQs exclude known
     * restricted IDs from the start. Entries older than 7 days are auto-expired
     * by [SecureStorage.getRestrictedGroupsForRelay].
     */
    fun loadRestrictedGroupsFromStorage(pubKey: String, relayUrls: List<String>) {
        if (relayUrls.isEmpty()) return
        val now = epochSeconds()
        val loaded = mutableMapOf<String, String>()
        for (url in relayUrls) {
            try {
                SecureStorage.getRestrictedGroupsForRelay(pubKey, url, now)
                    .forEach { (gid, reason) -> loaded[groupKey(url, gid)] = reason }
            } catch (_: Exception) {}
        }
        if (loaded.isNotEmpty()) {
            _restrictedGroups.update { it + loaded }
        }
    }

    companion object {
        const val PAGE_SIZE = 50
        const val LOADING_TIMEOUT_MS = 10_000L // 10 seconds timeout for loading

        // Forum threads: page size for kind:11 roots and the cap on the batched kind:1111
        // reply feed used to derive per-thread counts/previews on the list. A group with more
        // than THREAD_REPLY_BATCH replies across all threads would under-count on the list (the
        // detail view still filters whatever is loaded); fine for an MVP, revisit if it bites.
        const val THREAD_PAGE_SIZE = 50
        const val THREAD_REPLY_BATCH = 500

        // Cap the disk-first cache read so a hung IndexedDB cursor on web can never dead-lock
        // pagination — fall through to the relay if the cache does not answer in time.
        const val CACHE_PAGE_TIMEOUT_MS = 2_000L

        // Budget for awaiting NIP-42 AUTH before the initial group read. Sized above a
        // remote (NIP-46) signer's round-trip so a private group on a bunker authenticates
        // before the first REQ instead of racing it (a pre-AUTH REQ is CLOSED "auth-required"
        // and the empty result flashes a false "No messages yet"). Public relays skip it.
        const val INITIAL_READ_AUTH_TIMEOUT_MS = 12_000L
        const val MAX_PERSISTED_MESSAGES = 100 // Limit messages per group for storage

        // Most-recently-used groups whose messages stay hydrated in memory. Groups beyond
        // this are evicted whole (list + dedup index + reactions, persisted first) to bound
        // long-session growth across many opened groups. The active group is never evicted.
        const val MAX_GROUPS_IN_MEMORY = 30

        // How many cached messages to render instantly when a group is opened, before the
        // live subscription refreshes the tail.
        const val CACHE_HYDRATE_LIMIT = 300

        // Threads hydration budget: one roots page + the reply batch the live subs would fetch.
        const val THREAD_CACHE_HYDRATE_LIMIT = THREAD_PAGE_SIZE + THREAD_REPLY_BATCH

        // Quiet window before the periodic refresh force-re-arms a relay's mux subs
        // (see refreshLiveSubscriptions). Two timer ticks: long enough that a healthy
        // quiet relay re-proves itself at a lazy cadence, short enough that a deaf
        // feed heals without user action.
        const val MUX_STALE_REARM_MS = 600_000L

        // Quiet window before OPENING a group re-arms its relay's mux, much tighter
        // than MUX_STALE_REARM_MS because the user is actively looking at the screen.
        // A quiet-but-alive relay answers the re-sent REQ with one EOSE (which resets
        // the activity mark, so group-hopping costs at most one round trip per window);
        // a frame-silent socket additionally gets a liveness probe.
        const val MUX_OPEN_REARM_MS = 120_000L

        // Disk budget for the persistent history cache (messages + events), per account.
        // Evicted oldest-first once exceeded; the eviction is debounced behind writes.
        const val CACHE_BYTE_BUDGET = 75L * 1024 * 1024
        const val CACHE_EVICTION_DEBOUNCE_MS = 30_000L
        const val MEMBER_LOAD_TIMEOUT_MS = 8_000L // Safety timeout for member loading state
        const val REQUEST_COOLDOWN_MS = 2_000L // Prevents duplicate REQs within this window
        const val REACTION_DEBOUNCE_MS = 50L // Coalesces burst reaction arrivals
        const val GROUP_SNAPSHOT_EXTRA_CAP = 100 // Recently-seen non-joined groups kept per relay
    }

    private var currentPubkey: String? = null

    fun setCurrentPubkey(pubkey: String?) {
        currentPubkey = pubkey
    }

    // Catch-up `since` for the first mux refresh after an account switch.
    // Lets the now-active account fetch events that happened while it was
    // inactive. Expires automatically after [CATCH_UP_TTL_S] so it never
    // re-applies on a later reconnect of the same session.
    @kotlin.concurrent.Volatile
    private var catchUpSinceSeconds: Long? = null

    @kotlin.concurrent.Volatile
    private var catchUpSetAtSeconds: Long = 0L
    private val CATCH_UP_TTL_S = 60L

    /**
     * Set a one-shot catch-up `since` to apply on the next mux refresh(es).
     * Pass null to clear. Used by [reloadForActiveAccount] right after an
     * account switch so notification-bearing events that happened while this
     * account was inactive still arrive.
     */
    fun setCatchUpSince(unixSeconds: Long?) {
        catchUpSinceSeconds = unixSeconds
        catchUpSetAtSeconds = if (unixSeconds != null) epochSeconds() else 0L
    }

    private fun activeCatchUpSince(): Long? {
        val s = catchUpSinceSeconds ?: return null
        if (epochSeconds() - catchUpSetAtSeconds > CATCH_UP_TTL_S) {
            catchUpSinceSeconds = null
            return null
        }
        return s
    }

    // Track which groups have observation jobs to prevent memory leaks
    private val observedGroups = mutableSetOf<String>()
    private val observedGroupsMutex = Mutex()

    /**
     * Relay hosting [groupId], from the open-group hint or the per-relay caches.
     *
     * Returns null when the id is AMBIGUOUS - served by more than one relay with no hint to
     * break the tie. A group id is only unique within a relay, so guessing here is what routed
     * sends, invites and previews to a stranger's same-id group; callers that fall back to the
     * connected relay are at least on a relay the user chose. Anything that knows its relay
     * should pass it instead of asking.
     *
     * Uses immutable snapshots, so no lock is needed.
     */
    fun getRelayForGroup(groupId: String): String? {
        groupRelayHints.value[groupId]?.let { return it }
        val serving = LinkedHashSet<String>()
        _groupsByRelay.value.forEach { (relay, groups) ->
            if (groups.any { it.id == groupId }) serving.add(relay.normalizeRelayUrl())
        }
        // Private groups may not appear in the kind:39000 listing but are tracked in
        // _joinedGroupsByRelay (from kind:10009).
        _joinedGroupsByRelay.value.forEach { (relay, groupIds) ->
            if (groupId in groupIds) serving.add(relay.normalizeRelayUrl())
        }
        return serving.singleOrNull()
    }

    /**
     * Returns the WebSocket client for the relay that hosts [groupId].
     *
     * A NIP-29 relay rejects a kind:9 ("blocked: group doesn't exist") if it does not
     * host the group in the `h` tag, so once the group's relay is known this never falls
     * back to the focused. A non-focused pool relay that was evicted is reconnected on
     * demand; a relay that is down (or whose socket is dead) returns null so the send
     * fails honestly instead of being misrouted.
     */
    private suspend fun clientForGroup(groupId: String): NostrGroupClient? {
        val relayUrl = getRelayForGroup(groupId)
            ?: return connectionManager.getFocusedClient()
        val client = connectionManager.getClientForRelay(relayUrl)
        return when {
            client != null && client.isConnected() -> client
            relayUrl.normalizeRelayUrl() == connectionManager.currentRelayUrl.value.normalizeRelayUrl() -> null
            client != null -> null
            else -> messageHandler?.let { connectionManager.getOrConnectRelay(relayUrl, it) }
        }
    }

    /** Returns all cached groups for the given relay URL. */
    fun getGroupsForRelay(relayUrl: String): List<GroupMetadata> = _groupsByRelay.value[relayUrl] ?: emptyList()

    /**
     * Reverse-lookup: find a group ID from its first-8-chars prefix.
     * Used to identify which group a `live_<prefix>` or `msg_<prefix>_...` subscription belongs to.
     */
    fun getGroupIdByPrefix(prefix: String): String? {
        val allGroups = (_groups.value + _groupsByRelay.value.values.flatten()).distinctBy { it.id }
        return allGroups.firstOrNull { it.id.take(8) == prefix }?.id
    }

    /**
     * Re-send live subscriptions for all currently-loaded groups.
     *
     * Called every ~5 minutes to prevent relays from dropping idle subscriptions.
     * Also called from resubscribeAllGroups / resubscribePoolRelay implicitly because
     * requestGroupMessages already calls sendLiveSubscription on initial load.
     */
    /**
     * Returns the set of group IDs that should be covered by the mux subscription for [relayUrl].
     *
     * Union of: joined groups on that relay + groups with loaded messages on that relay.
     * This is the canonical input to [sendMuxSubscriptions].
     */
    fun getGroupIdsForMux(relayUrl: String): List<String> {
        val normalized = relayUrl.normalizeRelayUrl()
        val joined = _joinedGroupsByRelay.value[normalized] ?: emptySet()
        val loaded = _messages.value.keys.filter { groupId -> getRelayForGroup(groupId)?.normalizeRelayUrl() == normalized }
        val opened = _openedGroupIds.value.filter { groupId ->
            val relay = getRelayForGroup(groupId)?.normalizeRelayUrl()
            relay == normalized || (relay == null && normalized == currentRelayUrl)
        }
        // A group we durably left, AND are no longer joined to, must not re-enter the shared mux via
        // the loaded/opened terms (reopening it to see the locked panel would otherwise re-subscribe
        // its live chat). A still-joined group is NEVER excluded — a stale left marker on a rejoined
        // group must not cut off its live messages. Only THIS relay's markers count: the same id
        // left on another relay is a different group and must keep streaming here.
        val excluded = _leftGroups.value[normalized]?.keys.orEmpty() - joined
        return (joined + loaded + opened).distinct().filter { it !in excluded }
    }

    /**
     * Set the group currently being viewed by the user.
     * If this group hasn't been opened before, triggers on-demand subscriptions
     * (messages, members, admins, metadata). Refreshes the mux so chat/reactions
     * include this group.
     */
    fun setActiveGroupId(groupId: String?) {
        _activeGroupId = groupId
        // Instant render from the persistent history cache. The fast lane below joins this
        // job before computing the forward-fill frontier, so an evicted group's cached
        // tail counts as "newest we hold".
        val hydrateJob = if (groupId != null) {
            touchGroupRecency(groupId)
            evictGroupMessagesBeyondCap()
            scope.launch { hydrateMessagesFromCache(groupId) }
        } else {
            null
        }
        // Fallback to currentRelayUrl when the group isn't tracked yet (e.g. user
        // navigated to a private group URL without being a member).
        val relayUrl = if (groupId != null) (getRelayForGroup(groupId) ?: currentRelayUrl) else currentRelayUrl

        // On-demand: first time opening this group, fetch its data.
        val isNew = if (groupId != null) {
            val current = _openedGroupIds.value
            if (groupId !in current) {
                _openedGroupIds.value = current + groupId
                true
            } else {
                false
            }
        } else {
            false
        }

        if (groupId == null) {
            if (relayUrl != null) scope.launch { refreshMuxSubscriptionsForRelay(relayUrl) }
            return
        }

        scope.launch {
            val url = relayUrl ?: return@launch
            // Quick handshake check — 50ms × 20 = 1s max (down from 500ms × 6 = 3s).
            var client = connectionManager.getClientForRelay(url)
            if (client != null && !client.isConnected()) {
                repeat(20) {
                    delay(50)
                    if (client!!.isConnected()) return@repeat
                }
            }
            client = connectionManager.getClientForRelay(url)

            // A socket can sit "Connected" while deaf (zombie after a radio nap, or a
            // relay that silently dropped its subs): the tracker then no-ops the mux
            // refresh below and the entry REQs go into the void. The user is looking
            // at this group right now, so don't leave recovery to the 10-minute
            // staleness net: re-arm a mux-quiet relay immediately, and probe a
            // frame-silent socket (a zombie is marked dead, driving reconnect +
            // resubscribe + pending flush).
            val nowMs = epochMillis()
            if (muxTracker.isStale(url, nowMs, MUX_OPEN_REARM_MS)) {
                muxTracker.clearRelay(url)
                val probeClient = client
                if (probeClient != null && (probeClient.inboundSilenceMs(nowMs) ?: 0L) > MUX_OPEN_REARM_MS) {
                    scope.launch { RelayProbeGuard.probe(probeClient) }
                }
            }

            // Fire mux refresh in parallel — covers ongoing delivery for ALL groups.
            val muxJob = scope.launch { refreshMuxSubscriptionsForRelay(url) }

            if (client != null && client.isConnected()) {
                // Wait for NIP-42 AUTH before the direct REQs. Closed/private
                // groups answer with CLOSED "auth-required" if these race ahead,
                // and the 39002 never arrives, leaving the screen stuck on
                // "Awaiting admin approval" for an already-approved member.
                client.awaitAuthOrTimeout()
                // Direct fast-lane REQs for the ACTIVE group. Re-issued on EVERY
                // switch, not just the first open: a first open that raced AUTH,
                // timed out an EOSE, or landed before the socket connected would
                // otherwise never re-pull this group's metadata/members/admins, so
                // the screen stays partly empty until something else happens to
                // refresh it. shouldRequest() still debounces rapid re-entry, and
                // duplicates are handled by the event deduplicator.
                client.requestGroupMetadata(groupId) // Metadata (essential for private groups)
                requestGroupMembers(groupId) // Fast member list
                requestGroupAdmins(groupId) // Fast admin list
                // Load message history on first open AND whenever the loader is sitting in a
                // non-loaded state. A cross-relay switch runs clearForRelaySwitch (resets every
                // controller to Idle); the bulk re-subscribe reloads the other groups but skips
                // the active one, so without this the active group stays Idle (hasMore=false) and
                // can never paginate until restart. startInitialLoad is a no-op when the group is
                // already HasMore/Paginating, so a normal re-entry still costs nothing.
                val loadState = loadingRegistry.getController(groupId).state.value
                val needsLoad = isNew ||
                    loadState is GroupLoadingState.Idle ||
                    loadState is GroupLoadingState.Error
                if (needsLoad) {
                    requestGroupMessages(groupId)
                } else {
                    // Re-entry to an already-loaded group: the cached/in-memory tail
                    // rendered instantly, but anything that arrived while the live sub
                    // was silently dead (idle-drop without CLOSED, pre-AUTH empty EOSE,
                    // tracker-deduped refresh) stays invisible until a later AUTH or
                    // reconnect happens to re-arm it. One targeted forward REQ closes
                    // that window in a single round-trip; the deduplicator absorbs the
                    // overlap with what we already hold.
                    hydrateJob?.join()
                    val newest = _messages.value[groupId]?.lastOrNull()?.createdAt
                    if (newest != null &&
                        groupKey(url, groupId) !in _restrictedGroups.value &&
                        shouldRequest(groupId, "entry_gapfill", cooldownMs = 20_000L)
                    ) {
                        try {
                            client.requestGroupsMessagesSince(
                                listOf(groupId),
                                newest - LiveCursorStore.RECONNECT_OVERLAP_S,
                            )
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }
                }
            }

            muxJob.join()
        }
    }

    /**
     * Get the set of groups opened by the user this session.
     */
    fun getOpenedGroupIds(): Set<String> = _openedGroupIds.value

    /**
     * Send (or refresh) the relay-level multiplexed subscriptions for [relayUrl].
     *
     * Metadata mux covers ALL joined groups on this relay (lightweight, addressable).
     * Chat + reactions mux covers only the active group (on-demand).
     *
     * Uses [LiveCursorStore.getMinSince] to pick the oldest per-group cursor — guarantees
     * no group misses events during the offline window.
     * No-ops if there are no groups for that relay or the client is not connected.
     */
    suspend fun refreshMuxSubscriptionsForRelay(relayUrl: String) {
        refreshMuxSubscriptionsForRelayImpl(relayUrl)
    }

    /**
     * Debounced version: coalesces rapid calls within 300ms into a single mux refresh.
     * Use this from callers that may fire in quick succession (auth, EOSE, CLOSED handlers).
     */
    fun refreshMuxDebounced(relayUrl: String) {
        muxRefreshJobs[relayUrl]?.cancel()
        muxRefreshJobs[relayUrl] = scope.launch {
            delay(300)
            refreshMuxSubscriptionsForRelayImpl(relayUrl)
            muxRefreshJobs.remove(relayUrl)
        }
    }

    private suspend fun refreshMuxSubscriptionsForRelayImpl(relayUrl: String) {
        // Drop restricted groups from every batched filter — including a single
        // restricted #d/#h value makes the relay CLOSE the entire subscription,
        // silencing metadata/delete updates for the groups we DO belong to.
        val restricted = _restrictedGroups.value
        val allGroupIds = getGroupIdsForMux(relayUrl).filter { groupKey(relayUrl, it) !in restricted }
        // The metadata + delete watches also cover every descendant channel of the mux
        // groups: the sidebar renders the whole subgroup subtree, so channel renames and
        // deletions must sync even for channels the user never joined or opened.
        val metadataIds = expandWithDescendants(allGroupIds).filter { groupKey(relayUrl, it) !in restricted }
        // The put-user watch must be armed even with zero groups on this relay —
        // that is exactly the state in which an external add can only arrive via #p.
        val selfPubkey = currentPubkey
        if (allGroupIds.isEmpty() && selfPubkey == null) return
        val client = connectionManager.getClientForRelay(relayUrl) ?: return
        if (!client.isConnected()) return

        // Live chat covers EVERY group in the mux set, focused relay included. kind:9
        // flows only over mux_chat, so narrowing the focused relay to session-opened
        // groups (the old on-demand cost model, with a 60s post-boot catch-up window)
        // silenced notifications/unread for joined-but-unopened groups entirely: their
        // messages simply never arrived until the group was opened, then flooded in via
        // the initial REQ. The since cursors bound the replay cost, and a quiet group
        // costs nothing live.
        val catchUp = activeCatchUpSince()
        val chatGroupIds = allGroupIds

        val baseChatSince = liveCursorStore.getMinSince(relayUrl, chatGroupIds)
        // After a switch-in, regress `since` (one-shot, TTL-bounded) so the
        // mux replays anything the now-active account missed while inactive.
        // `MuxSubscriptionTracker.needsRefresh` already treats a regressed
        // `since` as a refresh trigger, so the CLOSE+REQ cycle fires here.
        val chatSince = if (catchUp == null) baseChatSince else minOf(baseChatSince, catchUp)

        val putUserSince = selfPubkey?.let { putUserWatchSince(it, relayUrl) } ?: 0L
        val desired = MuxSubscriptionTracker.MuxState(
            metadataGroupIds = metadataIds.toSet(),
            chatGroupIds = chatGroupIds.toSet(),
            chatSinceSeconds = chatSince,
            putUserPubkey = selfPubkey,
            putUserSinceSeconds = putUserSince,
        )

        if (!muxTracker.needsRefresh(relayUrl, desired)) {
            connStats?.onSubscriptionAvoided(relayUrl)
            return
        }

        // Delete-watch cursor: replay kind:9008s missed since the last arm (offline gap).
        // Replays are idempotent (deletedGroupIds), so the overlap with live delivery is fine.
        val normalized = relayUrl.normalizeRelayUrl()
        val nowSeconds = epochMillis() / 1000
        val deleteSince = SecureStorage.getDeleteWatchCursor(normalized)

        try {
            client.sendMuxSubscriptions(metadataIds, chatGroupIds, chatSince, selfPubkey, putUserSince, deleteSince)
            muxTracker.update(relayUrl, desired, epochMillis())
            // Arm the EOSE reconciliation window: ids REQed now vs 39000s that answer.
            muxMetaExpectedIds[normalized] = metadataIds.toSet()
            muxMetaSeenIds[normalized] = mutableSetOf()
            connStats?.onSubscriptionSent(relayUrl)
            try {
                SecureStorage.saveDeleteWatchCursor(normalized, nowSeconds)
            } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    /** [ids] plus every subgroup descendant (walk over childrenByParent), order-stable. */
    private fun expandWithDescendants(ids: List<String>): List<String> {
        val children = _childrenByParent.value
        if (children.isEmpty()) return ids
        val out = LinkedHashSet(ids)
        val queue = ArrayDeque(ids)
        while (queue.isNotEmpty()) {
            for (child in children[queue.removeFirst()].orEmpty()) {
                if (out.add(child)) queue.add(child)
            }
        }
        return out.toList()
    }

    // First-arm lookback for the put-user watch: catch adds from up to a week offline
    // without replaying the relay's whole put-user history.
    private val PUT_USER_INITIAL_LOOKBACK_S = 7L * 24 * 3600

    /**
     * `since` for [relayUrl]'s put-user watch: the persisted cursor, initialized to
     * now minus [PUT_USER_INITIAL_LOOKBACK_S] on first use. Inclusive replay of the
     * last processed event is fine — adoption is idempotent and skips joined groups.
     */
    private fun putUserWatchSince(pubkey: String, relayUrl: String): Long {
        val key = relayUrl.normalizeRelayUrl()
        val stored = try {
            SecureStorage.getPutUserCursorForRelay(pubkey, key)
        } catch (_: Exception) {
            0L
        }
        if (stored > 0L) return stored
        val initial = epochSeconds() - PUT_USER_INITIAL_LOOKBACK_S
        try {
            SecureStorage.savePutUserCursorForRelay(pubkey, key, initial)
        } catch (_: Exception) {}
        return initial
    }

    /** Advance the put-user cursor so processed adds are not replayed on the next REQ. */
    private fun advancePutUserCursor(pubkey: String, relayUrl: String, createdAtSeconds: Long) {
        if (createdAtSeconds <= 0L) return
        val key = relayUrl.normalizeRelayUrl()
        try {
            if (createdAtSeconds > SecureStorage.getPutUserCursorForRelay(pubkey, key)) {
                SecureStorage.savePutUserCursorForRelay(pubkey, key, createdAtSeconds)
            }
        } catch (_: Exception) {}
    }

    /**
     * Refresh mux subscriptions for all relays that currently have joined or loaded groups.
     * Replaces the old per-group `live_` refresh loop.
     * Called from the 5-minute periodic timer in NostrRepository.
     */
    suspend fun refreshLiveSubscriptions() {
        val relayUrls = (
            _joinedGroupsByRelay.value.keys +
                _messages.value.keys.mapNotNull { getRelayForGroup(it) }
            ).distinct()
        val now = epochMillis()
        for (relayUrl in relayUrls) {
            try {
                // A silently dead sub (idle reap without CLOSED, REQ eaten pre-AUTH)
                // leaves the tracker state identical to the desired one, so this
                // periodic refresh would no-op forever while the feed is deaf. If
                // nothing has been heard on the relay's mux subs for the stale window,
                // drop the tracker entry so the refresh below re-sends; the resulting
                // EOSE re-proves liveness (a quiet relay costs one CLOSE+REQ+EOSE per
                // window, an eaten REQ keeps being nudged).
                if (muxTracker.isStale(relayUrl, now, MUX_STALE_REARM_MS)) {
                    muxTracker.clearRelay(relayUrl)
                    // A zombie socket eats the re-sent REQ silently and update()
                    // re-arms the staleness clock, deferring detection forever. When
                    // the socket itself has been frame-silent for the whole window,
                    // verify it: the guard marks it dead once the probes go
                    // unanswered, and onConnectionLost drives reconnect +
                    // resubscribe + pending flush.
                    val client = connectionManager.getClientForRelay(relayUrl)
                    if (client != null && (client.inboundSilenceMs(now) ?: 0L) > MUX_STALE_REARM_MS) {
                        scope.launch { RelayProbeGuard.probe(client) }
                    }
                }
                refreshMuxSubscriptionsForRelay(relayUrl)
            } catch (_: Exception) {}
        }
    }

    /**
     * Returns joined group IDs for [relayUrl] that are NOT in the _groupsByRelay cache.
     * These are typically private groups whose kind 39000 was not returned by the
     * relay's general listing (because the relay hides private groups from non-members
     * or the listing arrived before AUTH completed).
     */
    fun getUncachedJoinedGroups(relayUrl: String): List<String> {
        val normalized = relayUrl.normalizeRelayUrl()
        val joined = _joinedGroupsByRelay.value[normalized] ?: emptySet()
        // Pending external adds sit outside the joined set but are already readable
        // relay-side (we are a member). Without them here the post-AUTH private-group
        // heal never fetches their withheld 39000/39002 and the invite's group screen
        // stays on skeletons / "no longer available" when opened from the notification.
        val pending = _pendingGroupInvites.value.values
            .filter { it.relayUrl.normalizeRelayUrl() == normalized }
            .map { it.groupId }
        val cached = _groupsByRelay.value[normalized]?.map { it.id }?.toSet() ?: emptySet()
        return (joined + pending - cached).toList()
    }

    /**
     * Request metadata, members and admins for joined groups that are missing from
     * the group cache. This is essential for private groups: the relay omits them
     * from the general kind 39000 listing, but returns them on targeted #d requests
     * once the client has authenticated via NIP-42.
     */
    // Per-group cooldown so the several post-AUTH callers (connect, switchRelay,
    // ensureJoinedRelaysConnected, resubscribeAfterAuth) do not each re-fire the same
    // private group's #d REQs in the window before its kind:39000 lands. Without it a
    // cold boot fanned out duplicate per-group REQs that could exhaust a relay's
    // subscription budget and get the chat/pagination REQs CLOSED "too many subscriptions".
    private val privateGroupFetchAt = mutableMapOf<String, Long>()
    private val privateGroupFetchMutex = Mutex()
    private val PRIVATE_GROUP_FETCH_COOLDOWN_MS = 10_000L

    suspend fun requestPrivateGroupData(relayUrl: String) {
        val uncached = getUncachedJoinedGroups(relayUrl)
        if (uncached.isEmpty()) return
        // Claim the cooldown BEFORE the only suspension point below (getClientForRelay awaits
        // an in-flight connect). Checking the cooldown map AFTER that suspend let every caller
        // parked on the same in-flight connect wake up together, all see a stale (unclaimed)
        // cooldown, and all fire the same batched REQ at once — a relay-visible request storm
        // that got the client rate-limited. Claiming first under a mutex means only the caller
        // that wins the race gets a non-empty toFetch; the rest see it already claimed and bail.
        val now = epochMillis()
        val toFetch = privateGroupFetchMutex.withLock {
            uncached
                .filter { now - (privateGroupFetchAt[it] ?: 0L) > PRIVATE_GROUP_FETCH_COOLDOWN_MS }
                .also { claimed -> claimed.forEach { privateGroupFetchAt[it] = now } }
        }
        if (toFetch.isEmpty()) return
        val client = connectionManager.getClientForRelay(relayUrl) ?: return
        if (!client.isConnected()) return
        try {
            // One batched #d REQ for ALL missing metadata, not one per group.
            client.requestGroupsMetadata(toFetch)
        } catch (_: Exception) {}
        val relayKey = relayUrl.normalizeRelayUrl()
        for (groupId in toFetch) {
            try {
                // Members/admins may already be live (mux_meta or an earlier fetch); only
                // ask for what we still lack so we don't double the kind:39002/39001 REQs.
                // Presence must be checked on THIS relay's mirror, not the flat bare-id map:
                // a same-id group on another relay (or its cached snapshot) satisfies the
                // flat check and suppresses the fetch, leaking that relay's admin badge here.
                if (_groupMembersByRelay.value[relayKey]?.containsKey(groupId) != true) client.requestGroupMembers(groupId)
                if (_groupAdminsByRelay.value[relayKey]?.containsKey(groupId) != true) client.requestGroupAdmins(groupId)
            } catch (_: Exception) {}
        }
    }

    /**
     * Request metadata for the active group if it has no metadata in the cache.
     * Covers the case where a user navigates directly to a group URL (e.g. via
     * invite link) before the relay has returned the group in its general listing.
     * The relay will respond to a targeted kind:39000 REQ with #d filter even for
     * groups that are private or not in the general listing.
     */
    suspend fun requestActiveGroupMetadataIfMissing(relayUrl: String) {
        val groupId = _activeGroupId ?: return
        // Already have metadata — nothing to do.
        val hasMeta = _groups.value.any { it.id == groupId }
        if (hasMeta) return
        val client = connectionManager.getClientForRelay(relayUrl) ?: return
        if (!client.isConnected()) return
        try {
            client.requestGroupMetadata(groupId)
            client.requestGroupMembers(groupId)
            client.requestGroupAdmins(groupId)
        } catch (_: Exception) {}
    }

    /**
     * Timing-robust safety net for kind:39000. The mux_meta batch and the one-shot meta_batch_
     * REQ are all-or-nothing: if the relay drops or CLOSEs them in the AUTH-vs-REQ window, EVERY
     * joined group loses its metadata at once (members still arrive on their independent per-group
     * members_ REQ). The slow JVM WebSocket engine loses that race far more than the browser
     * engine, so on desktop cold boot many groups render as a truncated id + "No description"
     * until a restart. This fires an INDEPENDENT per-group kind:39000 REQ for each joined group
     * STILL missing metadata, so recovery never depends on winning the batch race. Paced (one
     * short-lived, EOSE-closed sub open at a time) to stay well under strict relay sub caps
     * (nos.social 50), and NOT gated by the [requestPrivateGroupData] cooldown — it is the net
     * that cooldown otherwise breaks. Re-checks the missing set each step so it stops as soon as
     * another path (the mux) fills them in.
     */
    suspend fun requestMissingGroupMetadata(relayUrl: String) {
        val client = connectionManager.getClientForRelay(relayUrl) ?: return
        if (!client.isConnected()) return
        var missing = getUncachedJoinedGroups(relayUrl)
        while (missing.isNotEmpty()) {
            val groupId = missing.first()
            try {
                client.requestGroupMetadata(groupId)
            } catch (_: Exception) {}
            delay(60)
            if (!client.isConnected()) return
            val next = getUncachedJoinedGroups(relayUrl)
            // Guard against a group whose 39000 the relay never serves: drop it from the
            // work set even if still uncached so the loop always terminates.
            missing = next.filter { it != groupId }
        }
    }

    /**
     * Load the active group's message history after a relay switch.
     *
     * [clearForRelaySwitch] resets every controller to Idle; the bulk re-subscribe reloads the
     * other groups but not the active one, and setActiveGroupId's own load races the clear and
     * gets wiped (it runs before clearForRelaySwitch). Re-firing here, after the new relay's
     * group-list EOSE (so after the clear), guarantees the active group leaves Idle and can
     * paginate. No-op if the group already loaded — requestGroupMessages only acts from Idle/Error.
     */
    suspend fun requestActiveGroupMessagesIfNeeded(relayUrl: String) {
        val groupId = _activeGroupId ?: return
        if (getRelayForGroup(groupId)?.normalizeRelayUrl() != relayUrl.normalizeRelayUrl()) return
        val state = loadingRegistry.getController(groupId).state.value
        if (state !is GroupLoadingState.Idle && state !is GroupLoadingState.Error) return
        requestGroupMessages(groupId)
    }

    /**
     * Load joined groups from storage
     */
    fun loadJoinedGroupsFromStorage(pubKey: String, relayUrl: String) {
        currentPubkey = pubKey
        // Restore the persisted dropped-group guard: a relay that missed our delete still serves the
        // old kind:10009, and the additive merge would resurrect the group on restart without this.
        deletedGroupIds.addAll(SecureStorage.loadDroppedGroupIds(pubKey))
        val groups = SecureStorage.getJoinedGroupsForRelay(pubKey, relayUrl)
        // Additive: a join that landed before this async restore must not be wiped
        // by the (older) persisted set.
        _joinedGroupsByRelay.update { current ->
            val key = relayUrl.normalizeRelayUrl()
            current + (key to (current[key].orEmpty() + groups))
        }
        loadPendingInvitesFromStorage(pubKey)
    }

    /**
     * Load joined groups for all given relays into the per-relay cache without
     * only touching the per-relay cache.
     */
    fun loadAllJoinedGroupsFromStorage(pubKey: String, relayUrls: List<String>) {
        val now = epochSeconds()
        // Restore the durable left markers into the flow (called at every restore path) so a left group
        // reads NONE after a restart. We do NOT filter the joined set against them: leaveGroup already
        // removes the group from the joined slot, and filtering here risked excluding a rejoined group
        // whose marker hadn't been cleared yet, breaking its live messages.
        // Each relay's slot stays under its own key: flattening them into one id-keyed map made a
        // leave on one relay suppress the same-id group on every other relay, and it came back on
        // every cold start because the restore re-flattened it.
        val leftAll = mutableMapOf<String, Map<String, Long>>()
        for (url in relayUrls) {
            val marks = SecureStorage.getLeftGroupsForRelay(pubKey, url, now)
            if (marks.isNotEmpty()) leftAll[url.normalizeRelayUrl()] = marks
        }
        val updates = relayUrls.associate { url ->
            url.normalizeRelayUrl() to SecureStorage.getJoinedGroupsForRelay(pubKey, url)
        }.filter { (_, groups) -> groups.isNotEmpty() }
        if (leftAll.isNotEmpty()) {
            _leftGroups.update { current ->
                current + leftAll.mapValues { (relay, marks) -> current[relay].orEmpty() + marks }
            }
        }
        if (updates.isNotEmpty()) {
            // Additive, like loadJoinedGroupsFromStorage: never wipe a fresher join.
            _joinedGroupsByRelay.update { current ->
                current + updates.mapValues { (relay, groups) -> current[relay].orEmpty() + groups }
            }
        }
        loadPendingInvitesFromStorage(pubKey)
    }

    /** Persist [deletedGroupIds] so the dropped-group guard survives a restart (see its callers). */
    private fun persistDroppedGroups(pubKey: String? = currentPubkey) {
        val pk = pubKey?.takeIf { it.isNotBlank() } ?: return
        SecureStorage.saveDroppedGroupIds(pk, deletedGroupIds.toSet())
    }

    /**
     * Pre-populate _groupsByRelay with empty entries so relays appear in the
     * rail before their first connection this session.
     */
    fun prePopulateRelayList(relayUrls: List<String>) {
        _groupsByRelay.update { current ->
            val additions = relayUrls.map { it.normalizeRelayUrl() }
                .filter { !current.containsKey(it) }
                .associateWith { emptyList<GroupMetadata>() }
            current + additions
        }
    }

    fun pruneRelaysNotIn(authoritativeRelays: Set<String>) {
        val normalizedAuth = authoritativeRelays.map { it.normalizeRelayUrl() }.toSet()
        _groupsByRelay.update { current ->
            current.filterKeys { it.normalizeRelayUrl() in normalizedAuth }
        }
    }

    fun setJoinedGroups(groups: Set<String>) {
        val url = currentRelayUrl?.normalizeRelayUrl() ?: return
        _joinedGroupsByRelay.update { existing ->
            val current = existing[url] ?: emptySet()
            existing + (url to (current + groups))
        }
    }

    fun updateAllRelayJoinedGroups(relayGroups: Map<String, Set<String>>) {
        if (relayGroups.isEmpty()) return
        _joinedGroupsByRelay.update { it + relayGroups }
    }

    /**
     * Join a group
     */
    suspend fun joinGroup(
        groupId: String,
        pubKey: String,
        currentRelayUrl: String,
        signEvent: suspend (Event) -> Event,
        publishJoinedGroups: suspend () -> Unit,
        inviteCode: String? = null,
    ): Result<Unit> {
        val groupRelayUrl = getRelayForGroup(groupId) ?: currentRelayUrl
        // The 9021 must reach the group's OWN relay — a foreign fallback (the focused
        // client) can OK a group it doesn't host, minting a phantom local joined+pending
        // state the real relay never sees (same class as the #136 send-routing bug).
        // Leaving a relay's last group drops it from the pool, so reconnect on demand.
        val currentClient = connectionManager.getClientForRelay(groupRelayUrl)
            ?: messageHandler?.let { handler -> connectionManager.getOrConnectRelay(groupRelayUrl, handler) }
            ?: return Result.Error(AppError.Network.Disconnected(groupRelayUrl))

        return try {
            val tags = mutableListOf(listOf("h", groupId))
            val effectiveCode = inviteCode?.takeIf { it.isNotBlank() }
            if (effectiveCode != null) {
                tags.add(listOf("code", effectiveCode))
            }
            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 9021,
                tags = tags,
                content = "/join",
            )

            val signedEvent = signEvent(event)
            val eventId = signedEvent.id
                ?: return Result.Error(AppError.Group.JoinFailed(groupId, Exception("Event ID not generated")))

            val message = buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()

            // Private/closed relays gate the join-request publish behind NIP-42 AUTH. Firing the
            // 9021 on an unauthenticated socket gets it dropped "auth-required" and the admin never
            // sees the pending request. awaitAuthSigned (not the requiresAuth-gated wait) also covers
            // the fresh-connect race where the relay has not issued its challenge yet by tap time:
            // it waits for the challenge to arrive, then for the signer. Returns fast on open/public
            // relays that never challenge.
            currentClient.awaitAuthSigned(signBudgetMs = INITIAL_READ_AUTH_TIMEOUT_MS)

            // Await the OK so a relay rejection (e.g. a closed group that only admits via invite
            // code) surfaces with its reason instead of failing silently, and so we only record the
            // optimistic membership when the request was actually accepted.
            when (val publish = currentClient.sendAndAwaitOk(message, eventId)) {
                is org.nostr.nostrord.network.PublishResult.Success -> Unit
                is org.nostr.nostrord.network.PublishResult.Rejected ->
                    // "Already a member" is the relay agreeing with the join, not refusing it. Failing
                    // here left the local left/dropped markers set forever: they only clear on success,
                    // so a user whose markers disagreed with the relay could never rejoin.
                    if (!isAlreadyMemberRejection(publish.reason)) {
                        return Result.Error(AppError.Group.JoinFailed(groupId, Exception(publish.reason)))
                    }
                is org.nostr.nostrord.network.PublishResult.Timeout ->
                    return Result.Error(AppError.Group.JoinFailed(groupId, Exception("The relay did not confirm the join request.")))
                is org.nostr.nostrord.network.PublishResult.Error ->
                    return Result.Error(AppError.Group.JoinFailed(groupId, publish.exception))
            }

            // The relay accepted the join: only now clear the leave/delete markers. Clearing
            // them before the OK (the old order) silently dropped the durable left protection
            // when the relay rejected or timed out the request. Still BEFORE the joined-set
            // write below, so the additive kind:10009 merge can't drop the fresh join.
            deletedGroupIds.remove(droppedKey(groupRelayUrl, groupId))
            persistDroppedGroups()
            recentlyLeftAt.remove(recentLeaveKey(groupRelayUrl, groupId))
            clearLeft(groupRelayUrl, groupId)
            try {
                SecureStorage.removeLeftGroupForRelay(pubKey, groupRelayUrl.normalizeRelayUrl(), groupId)
            } catch (_: Exception) {}

            // Normalized key, like every other _joinedGroupsByRelay writer: a raw URL
            // variant ("wss://x/" vs "wss://x") would start a parallel entry whose set
            // lacks the relay's other groups, and the next kind:10009 publish/echo
            // would read as the list being replaced instead of incremented.
            // Merged with the persisted slot because the in-memory map can be partial
            // early in a session (the storage restore is async): writing memory-only
            // here would clobber the slot, and on restart the slot is the only truth
            // (our own kind:10009 echo is dropped by the persisted timestamp guard).
            val normalizedGroupRelay = groupRelayUrl.normalizeRelayUrl()
            val stored = SecureStorage.getJoinedGroupsForRelay(pubKey, normalizedGroupRelay)
            val relayGroups = (_joinedGroupsByRelay.value[normalizedGroupRelay] ?: emptySet()) + stored
            val updated = relayGroups + groupId
            SecureStorage.saveJoinedGroupsForRelay(pubKey, normalizedGroupRelay, updated)
            _joinedGroupsByRelay.update { it + (normalizedGroupRelay to updated) }
            markRecentlyJoined(groupId)
            // An explicit join settles any pending external add for the same group.
            discardPendingInvite(groupId)

            publishJoinedGroups()

            clearGroupRestricted(normalizedGroupRelay, groupId)
            // Seconds, to match GroupMembershipState.requestedAtSeconds (the "Requested ..." label).
            // Keyed per relay: a request to this relay says nothing about the same-id group
            // elsewhere, which otherwise opened already showing "Request pending".
            _pendingApprovalSince.update { it + (groupKey(normalizedGroupRelay, groupId) to epochSeconds()) }
            refreshMuxSubscriptionsForRelay(groupRelayUrl)

            kotlinx.coroutines.delay(500)

            // Re-request all group data now that we're a member
            // (private groups block these until membership is confirmed)
            currentClient.requestGroupMetadata(groupId)
            currentClient.requestGroupMembers(groupId)
            currentClient.requestGroupAdmins(groupId)
            currentClient.requestGroupMessages(groupId)

            Result.Success(Unit)
        } catch (e: Throwable) {
            Result.Error(AppError.Group.JoinFailed(groupId, e))
        }
    }

    /**
     * Flip [groupId] to joined on [relayUrl] in memory immediately so the UI reacts
     * without waiting on a relay switch + signer + send (mirrors the optimistic follow
     * button). The real [joinGroup] confirms and persists it; [revertOptimisticJoin]
     * rolls it back on failure. Returns false when it was already joined, so the caller
     * knows not to revert a pre-existing membership. Memory-only: persistence stays with
     * the confirmed join so a failed attempt never lands in SecureStorage.
     */
    fun markOptimisticJoin(relayUrl: String, groupId: String): Boolean {
        val key = relayUrl.normalizeRelayUrl()
        if (groupId in (_joinedGroupsByRelay.value[key] ?: emptySet())) return false
        _joinedGroupsByRelay.update { current ->
            current + (key to ((current[key] ?: emptySet()) + groupId))
        }
        markRecentlyJoined(groupId)
        return true
    }

    /** Undo a [markOptimisticJoin] when the join ultimately fails. */
    fun revertOptimisticJoin(relayUrl: String, groupId: String) {
        val key = relayUrl.normalizeRelayUrl()
        _joinedGroupsByRelay.update { current ->
            current + (key to ((current[key] ?: emptySet()) - groupId))
        }
    }

    /**
     * Append the NIP-29 access flags to a kind:9002 tag list. Flags are presence-only:
     * each is added only when on; absence is the permissive default (public / open /
     * anyone-writes / discoverable). There are no public / open / un-restricted tags, so a
     * complete flag declaration is exactly the set of on-flags, which is also how the OFF
     * state is communicated to the relay.
     */
    private fun addAccessFlags(
        tags: MutableList<List<String>>,
        isPrivate: Boolean,
        isClosed: Boolean,
        isRestricted: Boolean,
        isHidden: Boolean,
    ) {
        if (isPrivate) tags.add(listOf("private"))
        if (isClosed) tags.add(listOf("closed"))
        if (isRestricted) tags.add(listOf("restricted"))
        if (isHidden) tags.add(listOf("hidden"))
    }

    /**
     * Create a new NIP-29 group
     */
    suspend fun createGroup(
        name: String,
        about: String?,
        picture: String? = null,
        isPrivate: Boolean,
        isClosed: Boolean,
        isRestricted: Boolean = false,
        isHidden: Boolean = false,
        customGroupId: String? = null,
        parentGroupId: String? = null,
        pubKey: String,
        currentRelayUrl: String,
        signEvent: suspend (Event) -> Event,
        publishJoinedGroups: suspend () -> Unit,
    ): Result<String> {
        val currentClient = connectionManager.getFocusedClient()
            ?: return Result.Error(AppError.Network.Disconnected(currentRelayUrl))

        return try {
            // Use custom ID if provided, otherwise generate a random one
            val suggestedId = if (!customGroupId.isNullOrBlank()) {
                customGroupId.trim().lowercase()
            } else {
                org.nostr.nostrord.utils.ReadableId.generate()
            }

            // kind 9007: create-group — sign and build the full message first so
            // awaitGroupCreated can send it and track the OK response.
            // NIP-29 relays expect ["h", groupId] (the group tag), not ["d", groupId].
            val createEvent = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 9007,
                tags = listOf(listOf("h", suggestedId)),
                content = "",
            )
            val signedCreate = signEvent(createEvent)
            val create9007Json = buildJsonArray {
                add("EVENT")
                add(signedCreate.toJsonObject())
            }.toString()

            // Send 9007, await relay OK, then subscribe for kind:39000 to get the
            // Pre-claim the id: the relay's put-user echo (relay-authored, so the self-actor
            // guard misses it) races the OK and must not become a pending invite.
            markRecentlyJoined(suggestedId)
            // relay-confirmed group ID (relay may have used a different ID than suggested)
            val confirmedGroupId = currentClient.awaitGroupCreated(
                create9007EventJson = create9007Json,
                create9007EventId = signedCreate.id ?: suggestedId,
                suggestedGroupId = suggestedId,
            )

            // kind 9002: edit-metadata — sets name, about, picture, and access in one event.
            val metaTags = mutableListOf(
                listOf("h", confirmedGroupId),
                listOf("name", name),
            )
            // NIP-29 access flags are presence-only: absence is the permissive default
            // (public / open / anyone-writes / discoverable). There are no public / open /
            // un-restricted counterpart tags, so we emit each flag only when on and omit it
            // otherwise — that omission is what declares the OFF state to the relay.
            addAccessFlags(metaTags, isPrivate, isClosed, isRestricted, isHidden)
            if (!about.isNullOrBlank()) metaTags.add(listOf("about", about))
            if (!picture.isNullOrBlank()) metaTags.add(listOf("picture", picture))
            // Subgroup creation rides the same event: relays reject a kind:9002 whose only
            // payload is a parent tag ("missing metadata tags"), so the parent link must be
            // declared alongside name/flags instead of in a follow-up topology event.
            if (!parentGroupId.isNullOrBlank()) metaTags.add(listOf("parent", parentGroupId))
            val signedMeta = signEvent(
                Event(
                    pubkey = pubKey,
                    createdAt = epochMillis() / 1000,
                    kind = 9002,
                    tags = metaTags,
                    content = "",
                ),
            )
            currentClient.send(
                buildJsonArray {
                    add("EVENT")
                    add(signedMeta.toJsonObject())
                }.toString(),
            )

            // Normalized key + merged with the persisted slot, for the same reasons as
            // joinGroup: a raw URL variant forks a parallel relay entry, and a partial
            // in-memory map would clobber the slot that restarts rely on.
            val normalizedCreateRelay = currentRelayUrl.normalizeRelayUrl()
            val storedAtCreate = SecureStorage.getJoinedGroupsForRelay(pubKey, normalizedCreateRelay)
            val relayGroups = (_joinedGroupsByRelay.value[normalizedCreateRelay] ?: emptySet()) + storedAtCreate
            val updatedAfterCreate = relayGroups + confirmedGroupId
            SecureStorage.saveJoinedGroupsForRelay(pubKey, normalizedCreateRelay, updatedAfterCreate)
            _joinedGroupsByRelay.update { it + (normalizedCreateRelay to updatedAfterCreate) }
            // The relay's creation-time 39002 (listing us) races this bookkeeping on the
            // #p sweep and registers a pending "invite" for our own group; settle it so
            // the creator never sees the accept/decline prompt.
            discardPendingInvite(confirmedGroupId)
            publishJoinedGroups()
            currentClient.requestGroupMessages(confirmedGroupId)
            // Pull the kind:39000 produced by the edit-metadata above so name and parent land
            // locally right away (childrenByParent needs the parent tag for the rail/Subgroups).
            currentClient.requestGroupMetadata(confirmedGroupId)

            Result.Success(confirmedGroupId)
        } catch (e: Throwable) {
            Result.Error(AppError.Group.CreateFailed(e))
        }
    }

    /** Local kind:39000 snapshot for [groupId], from the focused list or any relay's cache. */
    private fun localMetadataFor(groupId: String): GroupMetadata? = _groups.value.find { it.id == groupId }
        ?: _groupsByRelay.value.values.firstNotNullOfOrNull { groups -> groups.find { it.id == groupId } }

    /**
     * Edit a group's metadata, its place in the hierarchy, and the list of
     * accepted children — all in a single kind:9002 event (admin only).
     *
     * A kind:9002 replaces the whole group metadata (NIP-29), so this always
     * emits the complete state: name, access flags, about/picture, the parent
     * and the full current child list. Omitting the parent tag is what promotes
     * a subgroup to root, and the relay rejects an edit that doesn't enumerate
     * every current child.
     *
     * - [parentOp]: null keeps the current parent (re-declared from the local
     *   kind:39000 cache). `SetTo(id)` links this group under `id`; `Detach`
     *   omits the tag, promoting the group to root.
     */
    suspend fun editGroup(
        groupId: String,
        name: String,
        about: String?,
        picture: String? = null,
        isPrivate: Boolean,
        isClosed: Boolean,
        isRestricted: Boolean = false,
        isHidden: Boolean = false,
        pubKey: String,
        currentRelayUrl: String,
        signEvent: suspend (Event) -> Event,
        parentOp: ParentOp? = null,
        hasLiveKit: Boolean? = null,
    ): Result<Unit> {
        val groupRelayUrl = getRelayForGroup(groupId) ?: currentRelayUrl
        val currentClient = connectionManager.getClientForRelay(groupRelayUrl)
            ?: connectionManager.getFocusedClient()
            ?: return Result.Error(AppError.Network.Disconnected(groupRelayUrl))

        return try {
            val metaTags = mutableListOf(
                listOf("h", groupId),
                listOf("name", name),
            )
            // Presence-only NIP-29 flags (see createGroup): emit only the ones that are on.
            addAccessFlags(metaTags, isPrivate, isClosed, isRestricted, isHidden)
            if (!about.isNullOrBlank()) metaTags.add(listOf("about", about))
            if (!picture.isNullOrBlank()) metaTags.add(listOf("picture", picture))

            val current = localMetadataFor(groupId)
            when (parentOp) {
                is ParentOp.SetTo -> metaTags.add(listOf("parent", parentOp.id))
                ParentOp.Detach -> Unit // omitting the parent tag is what promotes to root
                null -> current?.parent?.let { metaTags.add(listOf("parent", it)) }
            }

            // The relay rejects an edit that doesn't enumerate every current child, so
            // always re-declare the full list in its current order.
            current?.children.orEmpty().forEach { metaTags.add(listOf("child", it)) }

            // A kind:9002 carrying a name plus any flag is a full-state replace: the relay
            // wipes livekit and supported_kinds before applying the event. Both must be
            // re-declared or a plain rename would silently tear down the group's AV room.
            // [hasLiveKit] null keeps the current setting; false emits the explicit off tag.
            when (hasLiveKit ?: current?.hasLiveKit) {
                true -> metaTags.add(listOf("livekit"))
                false -> metaTags.add(listOf("no-livekit"))
                null -> Unit
            }
            current?.supportedKinds?.let { kinds ->
                metaTags.add(listOf("supported_kinds") + kinds.map { it.toString() })
            }

            val signedMeta = signEvent(
                Event(
                    pubkey = pubKey,
                    createdAt = epochMillis() / 1000,
                    kind = 9002,
                    tags = metaTags,
                    content = "",
                ),
            )
            val eventJson = buildJsonArray {
                add("EVENT")
                add(signedMeta.toJsonObject())
            }.toString()
            val eventId = signedMeta.id
                ?: return Result.Error(AppError.Group.EditFailed(groupId, Exception("Event ID not generated")))

            when (val result = currentClient.sendAndAwaitOk(eventJson, eventId)) {
                is org.nostr.nostrord.network.PublishResult.Success -> {
                    // Pull the just-edited kind:39000 so name/picture/parent/children land locally
                    // immediately (groupsByRelay + childrenByParent), instead of waiting for a
                    // refresh that no longer happens now that we only fetch known groups.
                    currentClient.requestGroupMetadata(groupId)
                    refreshMuxSubscriptionsForRelay(groupRelayUrl)
                    Result.Success(Unit)
                }
                is org.nostr.nostrord.network.PublishResult.Rejected ->
                    Result.Error(AppError.Group.EditFailed(groupId, Exception(result.reason)))
                is org.nostr.nostrord.network.PublishResult.Timeout ->
                    Result.Error(AppError.Group.EditFailed(groupId, Exception("Relay did not respond in time")))
                is org.nostr.nostrord.network.PublishResult.Error ->
                    Result.Error(AppError.Group.EditFailed(groupId, result.exception))
            }
        } catch (e: Throwable) {
            Result.Error(AppError.Group.EditFailed(groupId, e))
        }
    }

    sealed class ParentOp {
        data class SetTo(val id: String) : ParentOp()

        /** Republish metadata without a parent (promotes subgroup back to root). */
        object Detach : ParentOp()
    }

    /**
     * Publish a kind:9002 that reorders the group's channel list (admin only).
     * Sibling order is the position of the `child` tags, so the event re-declares the
     * full current metadata with the children in the new order. [orderedIds] must be a
     * permutation of the current list — the relay rejects a list that drops or invents
     * children, so mismatches (e.g. a stale local snapshot) fail fast here instead.
     */
    suspend fun reorderChildren(
        groupId: String,
        orderedIds: List<String>,
        pubKey: String,
        currentRelayUrl: String,
        signEvent: suspend (Event) -> Event,
    ): Result<Unit> {
        val meta = localMetadataFor(groupId)
            ?: return Result.Error(AppError.Group.EditFailed(groupId, Exception("Group metadata not loaded")))
        if (orderedIds.size != meta.children.size || orderedIds.toSet() != meta.children.toSet()) {
            return Result.Error(AppError.Group.EditFailed(groupId, Exception("Reorder must keep the same channels")))
        }
        val groupRelayUrl = getRelayForGroup(groupId) ?: currentRelayUrl
        val currentClient = connectionManager.getClientForRelay(groupRelayUrl)
            ?: connectionManager.getFocusedClient()
            ?: return Result.Error(AppError.Network.Disconnected(groupRelayUrl))

        return try {
            val tags = mutableListOf(
                listOf("h", groupId),
                listOf("name", meta.name?.takeIf { it.isNotBlank() } ?: groupId),
            )
            addAccessFlags(
                tags,
                isPrivate = !meta.isPublic,
                isClosed = !meta.isOpen,
                isRestricted = meta.isRestricted,
                isHidden = meta.isHidden,
            )
            meta.about?.takeIf { it.isNotBlank() }?.let { tags.add(listOf("about", it)) }
            meta.picture?.takeIf { it.isNotBlank() }?.let { tags.add(listOf("picture", it)) }
            meta.parent?.let { tags.add(listOf("parent", it)) }
            orderedIds.forEach { tags.add(listOf("child", it)) }

            val signed = signEvent(
                Event(
                    pubkey = pubKey,
                    createdAt = epochMillis() / 1000,
                    kind = 9002,
                    tags = tags,
                    content = "",
                ),
            )
            val eventJson = buildJsonArray {
                add("EVENT")
                add(signed.toJsonObject())
            }.toString()
            val eventId = signed.id
                ?: return Result.Error(AppError.Group.EditFailed(groupId, Exception("Event ID not generated")))
            when (val res = currentClient.sendAndAwaitOk(eventJson, eventId)) {
                is org.nostr.nostrord.network.PublishResult.Success -> {
                    // Pull the reordered kind:39000 so the channel list re-sorts locally.
                    currentClient.requestGroupMetadata(groupId)
                    Result.Success(Unit)
                }
                is org.nostr.nostrord.network.PublishResult.Rejected ->
                    Result.Error(AppError.Group.EditFailed(groupId, Exception(res.reason)))
                is org.nostr.nostrord.network.PublishResult.Timeout ->
                    Result.Error(AppError.Group.EditFailed(groupId, Exception("Relay did not respond in time")))
                is org.nostr.nostrord.network.PublishResult.Error ->
                    Result.Error(AppError.Group.EditFailed(groupId, res.exception))
            }
        } catch (e: Throwable) {
            Result.Error(AppError.Group.EditFailed(groupId, e))
        }
    }

    /**
     * Delete a group (admin only). Sends kind:9008 (delete-group).
     * Per NIP-29: when a parent is deleted, its children become roots —
     * the relay re-emits their kind:39000 without the parent tag.
     */
    suspend fun deleteGroup(
        groupId: String,
        pubKey: String,
        currentRelayUrl: String,
        signEvent: suspend (Event) -> Event,
        publishJoinedGroups: suspend () -> Unit,
    ): Result<Unit> {
        val groupRelayUrl = getRelayForGroup(groupId) ?: currentRelayUrl
        val currentClient = connectionManager.getClientForRelay(groupRelayUrl)
            ?: connectionManager.getFocusedClient()
            ?: return Result.Error(AppError.Network.Disconnected(groupRelayUrl))

        return try {
            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 9008,
                tags = listOf(listOf("h", groupId)),
                content = "",
            )
            val signedEvent = signEvent(event)
            val eventId = signedEvent.id
                ?: return Result.Error(AppError.Group.DeleteFailed(groupId, Exception("Event ID not generated")))
            val message = buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()

            // Background the 9008 so delete doesn't block on the relay OK (a half-open socket burns
            // the 10s timeout). The local cleanup below drops the group now; the send retries once.
            scope.launch {
                val pub = currentClient.sendAndAwaitOk(message, eventId)
                if (pub is org.nostr.nostrord.network.PublishResult.Timeout && connectionManager.reconnect()) {
                    val freshClient = connectionManager.getClientForRelay(groupRelayUrl)
                        ?: connectionManager.getFocusedClient()
                    freshClient?.sendAndAwaitOk(message, eventId)
                }
            }

            val idsToRemove = setOf(groupId)
            val normalizedGroupRelay = groupRelayUrl.normalizeRelayUrl()
            // Merge memory with the persisted slot (the map can be partial early in a
            // session), then remove. The slot key must be the normalized URL: storage
            // slots hash the raw string, so a raw variant would write a parallel slot
            // and the canonical one would resurrect the group on restart.
            val storedBeforeLeave = SecureStorage.getJoinedGroupsForRelay(pubKey, normalizedGroupRelay)
            val relayGroupsBefore = (_joinedGroupsByRelay.value[normalizedGroupRelay] ?: emptySet()) + storedBeforeLeave
            val updatedAfterLeave = relayGroupsBefore - idsToRemove
            SecureStorage.saveJoinedGroupsForRelay(pubKey, normalizedGroupRelay, updatedAfterLeave)
            _joinedGroupsByRelay.update { it + (normalizedGroupRelay to updatedAfterLeave) }
            publishJoinedGroups()

            _groups.value = _groups.value.filter { it.id !in idsToRemove }
            _groupsByRelay.update { current ->
                val updated = (current[groupRelayUrl] ?: emptyList()).filter { it.id !in idsToRemove }
                current + (groupRelayUrl to updated)
            }
            persistJoinedGroupMetadataSnapshot(groupRelayUrl)

            _messages.update { it - idsToRemove }
            _isLoadingMore.update { it - idsToRemove }
            _hasMoreMessages.update { it - idsToRemove }
            _groupStates.update { it - idsToRemove }
            _groupsAwaitingAuthRead.update { it - idsToRemove }
            _groupAdmins.update { it - idsToRemove }
            _groupMembers.update { it - idsToRemove }
            _liveKitParticipants.update { it - idsToRemove }
            idsToRemove.forEach { loadingRegistry.remove(it) }
            deletedGroupIds.addAll(idsToRemove.map { droppedKey(normalizedGroupRelay, it) })
            persistDroppedGroups(pubKey)
            recomputeSubgroupTopology()

            Result.Success(Unit)
        } catch (e: Throwable) {
            Result.Error(AppError.Group.DeleteFailed(groupId, e))
        }
    }

    /**
     * Apply a deletion broadcast (kind:9008) coming from the relay for a group the
     * current user didn't initiate. Idempotent; no-op if the group is already gone.
     *
     * Returns true when local state actually changed so callers (e.g. NostrRepository)
     * can republish the user's joined list for cross-device consistency.
     */
    suspend fun handleRemoteDeleteGroup(groupId: String, relayUrl: String, pubKey: String?): Boolean {
        // Once a deletion has been processed, don't re-process it. The joinGroup()
        // method clears the id from deletedGroupIds, so a future deletion after
        // re-join will still be handled correctly.
        if (isDropped(relayUrl, groupId)) return false

        val idsToRemove = setOf(groupId)
        // Intentionally do NOT remove from _joinedGroupsByRelay / kind:10009 — orphaned groups
        // surface in the sidebar so the user can review and explicitly forget them.
        // Per NIP-29: when a parent is deleted, its children become roots —
        // the relay re-emits their kind:39000 without the parent tag.

        _groups.value = _groups.value.filter { it.id !in idsToRemove }
        _groupsByRelay.update { current ->
            val updated = (current[relayUrl] ?: emptyList()).filter { it.id !in idsToRemove }
            current + (relayUrl to updated)
        }
        // The group is intentionally kept in _joinedGroupsByRelay (shows as orphan in sidebar).
        // Rebuild the snapshot so next startup sees it as an orphan (in joined but not in metadata).
        persistJoinedGroupMetadataSnapshot(relayUrl)

        _messages.update { it - idsToRemove }
        _isLoadingMore.update { it - idsToRemove }
        _hasMoreMessages.update { it - idsToRemove }
        _groupStates.update { it - idsToRemove }
        _groupsAwaitingAuthRead.update { it - idsToRemove }
        _groupAdmins.update { it - idsToRemove }
        _groupMembers.update { it - idsToRemove }
        idsToRemove.forEach { loadingRegistry.remove(it) }
        deletedGroupIds.addAll(idsToRemove.map { droppedKey(relayUrl, it) })
        persistDroppedGroups(pubKey)
        recomputeSubgroupTopology()
        return false
    }

    /**
     * Explicitly forget an orphaned pin: drop the id from the joined set and
     * persist, so the next publishJoinedGroupsList() writes a kind:10009
     * without it. Used by the sidebar's "forget orphan" trash action.
     */
    fun forgetJoinedPin(groupId: String, relayUrl: String, pubKey: String?): Boolean {
        val normalized = relayUrl.normalizeRelayUrl()
        val currentPerRelay = _joinedGroupsByRelay.value[normalized] ?: emptySet()
        val activeKey = currentRelayUrl?.normalizeRelayUrl()
        val activeGroups = activeKey?.let { _joinedGroupsByRelay.value[it] } ?: emptySet()
        val alsoInActive = activeKey != null && activeKey != normalized && groupId in activeGroups
        if (groupId !in currentPerRelay && !alsoInActive) return false

        val updatedPerRelay = currentPerRelay - groupId
        _joinedGroupsByRelay.update { current ->
            val next = current + (normalized to updatedPerRelay)
            if (alsoInActive) next + (activeKey!! to (activeGroups - groupId)) else next
        }
        if (pubKey != null) {
            try {
                SecureStorage.saveJoinedGroupsForRelay(pubKey, normalized, updatedPerRelay)
            } catch (_: Exception) {}
        }
        deletedGroupIds.add(droppedKey(normalized, groupId))
        persistDroppedGroups(pubKey)
        return true
    }

    /**
     * Add a user to a group (admin only). Sends kind:9000 (put-user).
     * Optionally assigns roles (e.g. "admin", "moderator").
     */
    suspend fun addUser(
        groupId: String,
        targetPubkey: String,
        roles: List<String> = emptyList(),
        pubKey: String,
        currentRelayUrl: String,
        signEvent: suspend (Event) -> Event,
    ): Result<Unit> {
        val groupRelayUrl = getRelayForGroup(groupId) ?: currentRelayUrl
        val currentClient = connectionManager.getClientForRelay(groupRelayUrl)
            ?: connectionManager.getFocusedClient()
            ?: return Result.Error(AppError.Network.Disconnected(groupRelayUrl))

        return try {
            val pTag = mutableListOf("p", targetPubkey).apply { addAll(roles) }
            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 9000,
                tags = listOf(listOf("h", groupId), pTag),
                content = "",
            )
            val signedEvent = signEvent(event)
            val eventId = signedEvent.id
                ?: return Result.Error(AppError.Group.SendFailed(groupId, Exception("Event ID not generated")))
            val message = buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()
            when (val result = currentClient.sendAndAwaitOk(message, eventId)) {
                is org.nostr.nostrord.network.PublishResult.Success -> Result.Success(Unit)
                is org.nostr.nostrord.network.PublishResult.Rejected ->
                    Result.Error(AppError.Group.SendFailed(groupId, Exception(result.reason)))
                is org.nostr.nostrord.network.PublishResult.Timeout ->
                    Result.Error(AppError.Group.SendTimeout(groupId))
                is org.nostr.nostrord.network.PublishResult.Error ->
                    Result.Error(AppError.Group.SendFailed(groupId, result.exception))
            }
        } catch (e: Throwable) {
            Result.Error(AppError.Group.SendFailed(groupId, e))
        }
    }

    /**
     * Remove a user from a group (admin only). Sends kind:9001 (remove-user).
     */
    suspend fun removeUser(
        groupId: String,
        targetPubkey: String,
        pubKey: String,
        currentRelayUrl: String,
        signEvent: suspend (Event) -> Event,
    ): Result<Unit> {
        val groupRelayUrl = getRelayForGroup(groupId) ?: currentRelayUrl
        val currentClient = connectionManager.getClientForRelay(groupRelayUrl)
            ?: connectionManager.getFocusedClient()
            ?: return Result.Error(AppError.Network.Disconnected(groupRelayUrl))

        return try {
            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 9001,
                tags = listOf(
                    listOf("h", groupId),
                    listOf("p", targetPubkey),
                ),
                content = "",
            )
            val signedEvent = signEvent(event)
            val eventId = signedEvent.id
                ?: return Result.Error(AppError.Group.SendFailed(groupId, Exception("Event ID not generated")))
            val message = buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()
            when (val result = currentClient.sendAndAwaitOk(message, eventId)) {
                is org.nostr.nostrord.network.PublishResult.Success -> Result.Success(Unit)
                is org.nostr.nostrord.network.PublishResult.Rejected ->
                    Result.Error(AppError.Group.SendFailed(groupId, Exception(result.reason)))
                is org.nostr.nostrord.network.PublishResult.Timeout ->
                    Result.Error(AppError.Group.SendTimeout(groupId))
                is org.nostr.nostrord.network.PublishResult.Error ->
                    Result.Error(AppError.Group.SendFailed(groupId, result.exception))
            }
        } catch (e: Throwable) {
            Result.Error(AppError.Group.SendFailed(groupId, e))
        }
    }

    /**
     * Reject a join request (admin only). Sends kind:9005 (delete-event) targeting the 9021 event.
     */
    suspend fun rejectJoinRequest(
        groupId: String,
        joinRequestEventId: String,
        pubKey: String,
        currentRelayUrl: String,
        signEvent: suspend (Event) -> Event,
    ): Result<Unit> {
        val groupRelayUrl = getRelayForGroup(groupId) ?: currentRelayUrl
        val currentClient = connectionManager.getClientForRelay(groupRelayUrl)
            ?: connectionManager.getFocusedClient()
            ?: return Result.Error(AppError.Network.Disconnected(groupRelayUrl))

        return try {
            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 9005,
                tags = listOf(
                    listOf("h", groupId),
                    listOf("e", joinRequestEventId),
                ),
                content = "",
            )
            val signedEvent = signEvent(event)
            val eventId = signedEvent.id
                ?: return Result.Error(AppError.Group.SendFailed(groupId, Exception("Event ID not generated")))
            val message = buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()
            when (val result = currentClient.sendAndAwaitOk(message, eventId)) {
                is org.nostr.nostrord.network.PublishResult.Success -> Result.Success(Unit)
                is org.nostr.nostrord.network.PublishResult.Rejected ->
                    Result.Error(AppError.Group.SendFailed(groupId, Exception(result.reason)))
                is org.nostr.nostrord.network.PublishResult.Timeout ->
                    Result.Error(AppError.Group.SendTimeout(groupId))
                is org.nostr.nostrord.network.PublishResult.Error ->
                    Result.Error(AppError.Group.SendFailed(groupId, result.exception))
            }
        } catch (e: Throwable) {
            Result.Error(AppError.Group.SendFailed(groupId, e))
        }
    }

    /**
     * Create an invite code for a group (admin only). Sends kind:9009.
     * Returns the generated code on success.
     */
    suspend fun createInviteCode(
        groupId: String,
        pubKey: String,
        currentRelayUrl: String,
        signEvent: suspend (Event) -> Event,
    ): Result<String> {
        val groupRelayUrl = getRelayForGroup(groupId) ?: currentRelayUrl
        val currentClient = connectionManager.getClientForRelay(groupRelayUrl)
            ?: connectionManager.getFocusedClient()
            ?: return Result.Error(AppError.Network.Disconnected(groupRelayUrl))

        return try {
            val code = generateInviteCode()
            val tags = listOf(
                listOf("h", groupId),
                listOf("code", code),
            )
            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 9009,
                tags = tags,
                content = "",
            )
            val signedEvent = signEvent(event)
            val eventId = signedEvent.id
                ?: return Result.Error(AppError.Group.SendFailed(groupId, Exception("Event ID not generated")))
            val message = buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()
            when (val result = currentClient.sendAndAwaitOk(message, eventId)) {
                is org.nostr.nostrord.network.PublishResult.Success -> Result.Success(code)
                is org.nostr.nostrord.network.PublishResult.Rejected ->
                    Result.Error(AppError.Group.SendFailed(groupId, Exception(result.reason)))
                is org.nostr.nostrord.network.PublishResult.Timeout ->
                    Result.Error(AppError.Group.SendTimeout(groupId))
                is org.nostr.nostrord.network.PublishResult.Error ->
                    Result.Error(AppError.Group.SendFailed(groupId, result.exception))
            }
        } catch (e: Throwable) {
            Result.Error(AppError.Group.SendFailed(groupId, e))
        }
    }

    /**
     * Revoke an invite code (admin only). Sends kind:9005 targeting the 9009 event by ID.
     */
    suspend fun revokeInviteCode(
        groupId: String,
        eventId: String,
        pubKey: String,
        currentRelayUrl: String,
        signEvent: suspend (Event) -> Event,
    ): Result<Unit> {
        val groupRelayUrl = getRelayForGroup(groupId) ?: currentRelayUrl
        val currentClient = connectionManager.getClientForRelay(groupRelayUrl)
            ?: connectionManager.getFocusedClient()
            ?: return Result.Error(AppError.Network.Disconnected(groupRelayUrl))

        return try {
            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 9005,
                tags = listOf(
                    listOf("h", groupId),
                    listOf("e", eventId),
                ),
                content = "",
            )
            val signedEvent = signEvent(event)
            val eventId = signedEvent.id
                ?: return Result.Error(AppError.Group.SendFailed(groupId, Exception("Event ID not generated")))
            val message = buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()
            when (val result = currentClient.sendAndAwaitOk(message, eventId)) {
                is org.nostr.nostrord.network.PublishResult.Success -> Result.Success(Unit)
                is org.nostr.nostrord.network.PublishResult.Rejected ->
                    Result.Error(AppError.Group.SendFailed(groupId, Exception(result.reason)))
                is org.nostr.nostrord.network.PublishResult.Timeout ->
                    Result.Error(AppError.Group.SendTimeout(groupId))
                is org.nostr.nostrord.network.PublishResult.Error ->
                    Result.Error(AppError.Group.SendFailed(groupId, result.exception))
            }
        } catch (e: Throwable) {
            Result.Error(AppError.Group.SendFailed(groupId, e))
        }
    }

    private fun generateInviteCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
        return (1..8).map { chars.random() }.joinToString("")
    }

    /**
     * Leave a group
     */
    suspend fun leaveGroup(
        groupId: String,
        pubKey: String,
        currentRelayUrl: String,
        reason: String? = null,
        signEvent: suspend (Event) -> Event,
        publishJoinedGroups: suspend () -> Unit,
    ): Result<Unit> {
        // Normalize so the joined-set/storage keys match those written by joinGroup and the
        // kind:10009 handler. getRelayForGroup already returns a normalized key, but the
        // currentRelayUrl fallback may not, and a non-normalized key would leave the group
        // in the canonical (normalized) slot and resurrect it on the next publish.
        val groupRelayUrl = (getRelayForGroup(groupId) ?: currentRelayUrl).normalizeRelayUrl()

        return try {
            // Best-effort 9022 to the group relay. A dead or unreachable relay must NOT
            // block removal from the user's own kind:10009 list, so a missing client or a
            // failed send is swallowed and we still clean the list below. The kind:10009
            // republish goes to the outbox relays, which are independent of this relay.
            val currentClient = connectionManager.getClientForRelay(groupRelayUrl)
                ?: connectionManager.getFocusedClient()
            if (currentClient != null) {
                try {
                    val event = Event(
                        pubkey = pubKey,
                        createdAt = epochMillis() / 1000,
                        kind = 9022,
                        tags = listOf(listOf("h", groupId)),
                        content = reason.orEmpty(),
                    )
                    val signedEvent = signEvent(event)
                    val message = buildJsonArray {
                        add("EVENT")
                        add(signedEvent.toJsonObject())
                    }.toString()
                    // Private/closed relays gate the leave-request behind NIP-42 AUTH. Firing the
                    // 9022 on an unauthenticated socket gets it dropped "auth-required", so the relay
                    // never removes the user and re-fetched members keep listing them (the group looks
                    // joined again on reopen). Wait for AUTH first, like the join path. No-op on
                    // open/public relays. Fire-and-forget after that so the local cleanup below is
                    // never delayed by an OK that some relays don't send for a 9022.
                    if (currentClient.requiresAuth() && !currentClient.hasAuthSucceeded()) {
                        currentClient.awaitAuthOrTimeout(INITIAL_READ_AUTH_TIMEOUT_MS)
                    }
                    currentClient.send(message)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Relay send/sign failed; the kind:10009 cleanup below still applies.
                }
            }

            // Merge with the persisted slot like joinGroup and acceptInvite: the in-memory map can be
            // partial early in a session (the storage restore is async), and a memory-only write would
            // drop this relay's OTHER groups from the slot — and then from the kind:10009 we publish
            // right below, so the loss propagates to every client.
            val storedGroups = try {
                SecureStorage.getJoinedGroupsForRelay(pubKey, groupRelayUrl)
            } catch (_: Exception) {
                emptySet()
            }
            val relayGroups = (_joinedGroupsByRelay.value[groupRelayUrl] ?: emptySet()) + storedGroups
            val updatedAfterLeave = relayGroups - groupId
            SecureStorage.saveJoinedGroupsForRelay(pubKey, groupRelayUrl, updatedAfterLeave)
            _joinedGroupsByRelay.update { it + (groupRelayUrl to updatedAfterLeave) }
            persistJoinedGroupMetadataSnapshot(groupRelayUrl)

            publishJoinedGroups()

            recentlyLeftAt[recentLeaveKey(groupRelayUrl, groupId)] = epochMillis()
            // Leaving also settles a pending external add (this IS the decline path).
            discardPendingInvite(groupId)
            // Durable leave intent: outlives recentlyLeftAt (5s) and a restart, so the membership
            // derivation keeps reporting NONE even when the relay still lists us in kind:39002.
            markLeft(groupRelayUrl, groupId, epochSeconds())
            try {
                SecureStorage.addLeftGroupForRelay(pubKey, groupRelayUrl, groupId, epochSeconds())
            } catch (_: Exception) {}

            _messages.update { it - groupId }
            _isLoadingMore.update { it - groupId }
            _hasMoreMessages.update { it - groupId }
            _groupStates.update { it - groupId }
            _groupsAwaitingAuthRead.update { it - groupId }
            _groupMembers.update { it - groupId }
            _groupAdmins.update { it - groupId }
            _groupRoles.update { it - groupId }
            _loadingMembers.update { it - groupId }
            memberEventTimestamps.remove(groupId)
            adminEventTimestamps.remove(groupId)
            roleEventTimestamps.remove(groupId)
            clearPendingApproval(groupRelayUrl, groupId)
            messageIdIndex.remove(groupId)
            _latestMessageRelayByGroup.update { it - groupId }
            observedGroupsMutex.withLock { observedGroups.remove(groupId) }
            loadingRegistry.remove(groupId)
            _openedGroupIds.update { it - groupId }
            // When the relay gates this group's reads behind NIP-42 AUTH, leaving makes us a
            // non-member and the relay WILL re-CLOSE our reads "restricted" a round-trip later.
            // Leaving is an explicit reset of intent — a future rejoin should
            // get a fresh access attempt instead of being silently excluded.
            clearGroupRestricted(null, groupId)

            // Overwrite the persisted membership cache (now that the in-memory maps no longer carry
            // this group) so a restart does not re-hydrate self into the left group's member list —
            // which would read back as MEMBER (no Join button, cached chat) on a private group whose
            // 39002 the relay no longer serves us.
            persistGroupMembershipSnapshot()

            // Drop the group from the live mux so the relay stops pushing
            // events for it the moment we send the leave.
            refreshMuxSubscriptionsForRelay(groupRelayUrl)

            Result.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.Error(AppError.Group.LeaveFailed(groupId, e))
        }
    }

    /**
     * Check if a group is joined
     */
    fun isGroupJoined(groupId: String): Boolean = groupId in activeJoinedGroups

    /**
     * Request group messages (initial load).
     * Uses the state machine for reliable loading with proper state transitions.
     */
    suspend fun requestGroupMessages(groupId: String, channel: String? = null): Boolean {
        val currentClient = clientForGroup(groupId) ?: return false

        // Get controller and attempt to start initial load. Enter InitialLoading immediately
        // (so the UI shows skeletons) but DEFER the load timeout: the AUTH wait below can take
        // several seconds on a remote signer and must not consume the timeout budget.
        val controller = loadingRegistry.getController(groupId)
        val subscriptionId = controller.startInitialLoad(armTimeout = false) ?: return false

        // Register subscription for O(1) lookup
        loadingRegistry.registerSubscription(subscriptionId, controller)

        // Update legacy flags for UI compatibility
        updateLegacyFlags(groupId, controller.state.value)

        // Observe state changes to update legacy flags (only once per group)
        observeStateChanges(groupId, controller)

        // Private/closed groups gate reads behind NIP-42 AUTH. Firing the initial REQ before
        // AUTH lands gets CLOSED "auth-required" and zero events, which the UI mistakes for an
        // empty group (the "No messages yet" flicker on bunker login). Wait for AUTH first,
        // with a budget sized for a remote (NIP-46) signer. The controller stays in
        // InitialLoading during the wait, so the UI keeps showing skeletons. No-op once AUTH
        // has succeeded; skipped on public relays that never challenge. Mirrors loadMoreMessages.
        if (currentClient.requiresAuth() && !currentClient.hasAuthSucceeded()) {
            currentClient.awaitAuthOrTimeout(INITIAL_READ_AUTH_TIMEOUT_MS)
        }

        // If messages have already been delivered by mux (possibly during the AUTH wait),
        // request only events older than the oldest one we have to avoid deduplication
        // causing messageCount=0 → Exhausted.
        val existingMessages = _messages.value[groupId]
        val until = existingMessages?.minOfOrNull { it.createdAt }?.let { oldest ->
            if (oldest < Long.MAX_VALUE) oldest - 1L else null
        }

        return try {
            currentClient.requestGroupMessages(
                groupId = groupId,
                channel = channel,
                until = until,
                limit = PAGE_SIZE,
                subscriptionId = subscriptionId,
            )
            // The authenticated REQ is now on the wire — start the load timeout.
            controller.armInitialTimeout(subscriptionId)
            true
        } catch (e: Throwable) {
            loadingRegistry.unregisterSubscription(subscriptionId)
            controller.handleSendFailure(subscriptionId)
            updateLegacyFlags(groupId, controller.state.value)
            false
        }
    }

    /**
     * Observe state changes and update legacy flags.
     * Only creates one observation job per group to prevent memory leaks.
     */
    private suspend fun observeStateChanges(groupId: String, controller: GroupLoadingController) {
        val shouldObserve = observedGroupsMutex.withLock {
            if (groupId in observedGroups) {
                false
            } else {
                observedGroups.add(groupId)
                true
            }
        }

        if (!shouldObserve) return

        scope.launch {
            controller.state.collect { state ->
                updateLegacyFlags(groupId, state)
                _groupStates.update { it + (groupId to state) }
                // A settled state means an authenticated read completed (pre-AUTH reads are
                // held, not settled), so the group is no longer awaiting AUTH. Idle/InitialLoading
                // keep the flag so the resubscribeAfterAuth reset window stays "loading".
                if (state is GroupLoadingState.HasMore ||
                    state is GroupLoadingState.Exhausted ||
                    state is GroupLoadingState.Error
                ) {
                    _groupsAwaitingAuthRead.update { it - groupId }
                }
            }
        }
    }

    /**
     * Update legacy boolean flags from state machine state.
     * Maintains backward compatibility with existing UI code.
     */
    private fun updateLegacyFlags(groupId: String, state: GroupLoadingState) {
        _isLoadingMore.update { it + (groupId to state.isLoading) }
        _hasMoreMessages.update { it + (groupId to state.hasMore) }
    }

    /**
     * Load older messages for pagination (infinite scroll).
     * Uses the state machine with per-group locks - doesn't block other groups.
     */
    suspend fun loadMoreMessages(groupId: String, channel: String? = null): Boolean {
        // Disk-first: serve an older page from the persistent cache before any relay round-trip
        // (and without touching the pagination state machine). Only when local history is
        // exhausted do we fall through to the relay. Works offline too — no client required.
        // Guarded by a timeout: a hung IndexedDB read on web must NEVER dead-lock pagination —
        // the web load latch only releases once this returns, so a hang froze scroll-back
        // entirely (the group settled on HasMore but the latch stayed stuck after one page).
        val servedFromCache = withTimeoutOrNull(CACHE_PAGE_TIMEOUT_MS) { loadOlderFromCache(groupId) } ?: false
        if (servedFromCache) return true

        val currentClient = clientForGroup(groupId) ?: return false

        // Get controller and attempt to start pagination
        val controller = loadingRegistry.getController(groupId)
        val (subscriptionId, cursor) = controller.startPagination() ?: run {
            // Slow relays (e.g. groups.0xchat.com) sometimes deliver messages but no EOSE
            // within the 10s window, leaving the controller in Error(PARTIAL_TIMEOUT) with
            // an advanced cursor. Auto-resume via retry() so user scroll keeps pagination
            // moving instead of deadlocking until a manual retry. retry() honors maxRetries,
            // so a permanently broken relay still stops after a bounded number of attempts.
            val st = controller.state.value
            val canResume = st is GroupLoadingState.Error &&
                st.reason == GroupLoadingState.ErrorReason.PARTIAL_TIMEOUT &&
                st.cursor != null
            if (!canResume) return false
            val retrySubId = controller.retry() ?: return false
            val retryCursor = (controller.state.value as? GroupLoadingState.Retrying)?.cursor
                ?: return false
            Pair(retrySubId, retryCursor)
        }

        // Register subscription for O(1) lookup
        loadingRegistry.registerSubscription(subscriptionId, controller)

        // Update legacy flags
        updateLegacyFlags(groupId, controller.state.value)

        // Continue the relay scan from the oldest message actually in memory, never from a
        // cursor that lags behind it. Two ways the cursor falls behind the in-memory boundary:
        // disk-first pagination prepends older cached pages without advancing the controller,
        // and a mux delivery before the pagination sub leaves the tracker at Long.MAX_VALUE.
        // In both cases firing the REQ at the stale cursor re-requests an already-covered window
        // (every event dedups away), so the loader reads it as "no more" and stops paginating
        // early. Clamping to oldest-in-memory minus one makes page 2 pick up below what we hold.
        val oldestInMemory = _messages.value[groupId]
            ?.filter { it.createdAt > 1_000_000_000L } // guard against epoch-0 outliers
            ?.minOfOrNull { it.createdAt }
        val effectiveUntil = if (oldestInMemory != null) {
            minOf(cursor.untilTimestamp, oldestInMemory - 1L)
        } else {
            cursor.untilTimestamp
        }

        // Private/closed groups gate reads behind NIP-42 AUTH. After a reconnect the
        // socket's auth deferred resets; firing the pagination REQ before re-AUTH lands
        // gets CLOSED "auth-required" and returns zero events, which the UI mistakes for
        // "no older history" and stops paginating early. Wait for re-AUTH first. This is
        // a no-op once AUTH has already succeeded on this socket, and is skipped entirely
        // on relays that never issue a challenge (public groups), so it costs nothing in
        // the common case.
        if (currentClient.requiresAuth() && !currentClient.hasAuthSucceeded()) {
            currentClient.awaitAuthOrTimeout()
        }

        return try {
            currentClient.requestGroupMessages(
                groupId = groupId,
                channel = channel,
                until = effectiveUntil,
                limit = PAGE_SIZE,
                subscriptionId = subscriptionId,
            )
            true
        } catch (e: Throwable) {
            loadingRegistry.unregisterSubscription(subscriptionId)
            controller.handleSendFailure(subscriptionId)
            updateLegacyFlags(groupId, controller.state.value)
            false
        }
    }

    /**
     * Explicit user retry for a group whose pagination stalled (repeated zero-event
     * timeouts → [GroupLoadingState.Stalled]). Flips back to HasMore at the stalled
     * frontier and fires one attempt. Scroll-position triggers stay gated on hasMore,
     * so only the UI's retry affordance reaches this.
     */
    suspend fun retryStalledLoad(groupId: String, channel: String? = null): Boolean {
        val controller = loadingRegistry.getController(groupId)
        if (!controller.retryStalled()) return false
        updateLegacyFlags(groupId, controller.state.value)
        return loadMoreMessages(groupId, channel)
    }

    suspend fun fetchGroupMessageById(groupId: String, messageId: String) {
        clientForGroup(groupId)?.requestGroupMessageById(groupId, messageId)
    }

    /**
     * A msg_ subscription's initial load returned/CLOSED while the relay still needs NIP-42
     * AUTH. Hold the load in InitialLoading (skeletons) instead of settling it to a false
     * empty result, because resubscribeAfterAuth replays the read once AUTH completes. This
     * removes the "open a private group from the homepage" flicker, where the first read
     * races the relay's auth-required CLOSE. Returns true if the load was held (caller skips
     * the normal EOSE settle). Unregisters the dead sub so the registry doesn't accumulate.
     */
    suspend fun holdInitialLoadForReauth(subscriptionId: String): Boolean {
        val controller = loadingRegistry.findBySubscription(subscriptionId) ?: return false
        val held = controller.holdInitialLoadForReauth(subscriptionId)
        if (held) {
            _groupsAwaitingAuthRead.update { it + controller.id }
            loadingRegistry.unregisterSubscription(subscriptionId)
        }
        return held
    }

    /**
     * Called when EOSE is received for a subscription.
     * Delegates to the state machine for proper state transitions.
     * CRITICAL: This must be called from a coroutine context to ensure
     * proper ordering with message tracking.
     */
    /**
     * Handle an EOSE from [sourceRelayUrl]'s socket. Passing the source relay
     * explicitly (rather than reverse-mapping from the sub ID) avoids
     * mis-attributing a late EOSE from a torn-down relay to whichever relay is
     * currently focused — previously such a misattribution could mark the
     * current relay as fully fetched and prune its groups even though it had
     * never received its own EOSE.
     */
    suspend fun handleEoseSuspend(subscriptionId: String, sourceRelayUrl: String): Boolean {
        if (subscriptionId == "group-list" || subscriptionId.startsWith("group-list-")) {
            // Reject obviously-stale frames: a relay-specific sub ID must
            // match the source socket. Legacy "group-list" (no hash) is
            // single-relay and trusted as-is.
            if (subscriptionId.startsWith("group-list-") &&
                subscriptionId != "group-list-${sourceRelayUrl.hashCode().toUInt()}"
            ) {
                return true
            }
            val normalizedRelay = sourceRelayUrl.normalizeRelayUrl()
            _completeGroupLoadRelays.update { it + normalizedRelay }
            _loadingRelays.update { it - normalizedRelay }
            synchronized(loadingWatchdogsLock) { loadingWatchdogs.remove(normalizedRelay) }?.cancel()
            val now = epochSeconds()
            val isFull = pendingFullFetchRelays.remove(normalizedRelay) != null
            if (isFull) {
                // Save the dedicated full-list timestamp so hasFullGroupListBeenFetched()
                // returns true after an app restart.
                _fullGroupListFetchedRelays.update { it + normalizedRelay }
                try {
                    SecureStorage.saveFullGroupListEoseTimestamp(normalizedRelay, now)
                } catch (_: Exception) {}
                // Prune stale groups: keep only those seen in this fetch, plus joined
                // groups (which become orphans the user can manually forget).
                val seenIds = pendingFetchSeenGroups.remove(normalizedRelay) ?: emptySet()
                val joinedIds = _joinedGroupsByRelay.value[normalizedRelay] ?: emptySet()
                _groupsByRelay.update { current ->
                    val pruned = (current[normalizedRelay] ?: emptyList())
                        .filter { it.id in seenIds || it.id in joinedIds }
                    current + (normalizedRelay to pruned)
                }
            }
            try {
                SecureStorage.saveGroupListEoseTimestamp(normalizedRelay, currentPubkey, now)
            } catch (_: Exception) {}
            refreshMuxSubscriptionsForRelay(normalizedRelay)
            return true
        }
        awaitTrackingForSubscription(subscriptionId)
        return loadingRegistry.handleEose(subscriptionId, sourceRelayUrl)
    }

    // Incremented synchronously (before scope.launch) so EOSE always sees the full count.
    private val pendingTrackCounts = MutableStateFlow<Map<String, Int>>(emptyMap())

    fun trackMessageForSubscription(subscriptionId: String, timestamp: Long, eventId: String) {
        pendingTrackCounts.update { map ->
            map + (subscriptionId to ((map[subscriptionId] ?: 0) + 1))
        }
        scope.launch {
            try {
                loadingRegistry.trackMessage(subscriptionId, timestamp, eventId)
            } finally {
                pendingTrackCounts.update { map ->
                    val n = map[subscriptionId] ?: return@update map
                    if (n <= 1) map - subscriptionId else map + (subscriptionId to (n - 1))
                }
            }
        }
    }

    private suspend fun awaitTrackingForSubscription(subscriptionId: String) {
        pendingTrackCounts.first { (it[subscriptionId] ?: 0) == 0 }
    }

    /**
     * Check if a subscription is managed by the loading registry.
     */
    suspend fun isPaginationSubscription(subscriptionId: String): Boolean = loadingRegistry.findBySubscription(subscriptionId) != null

    /**
     * Retry loading after an error.
     */
    suspend fun retryLoading(groupId: String, channel: String? = null): Boolean {
        val currentClient = clientForGroup(groupId) ?: return false
        val controller = loadingRegistry.getController(groupId)

        val subscriptionId = controller.retry() ?: return false

        // Register subscription for O(1) lookup
        loadingRegistry.registerSubscription(subscriptionId, controller)

        updateLegacyFlags(groupId, controller.state.value)

        val state = controller.state.value
        val cursor = when (state) {
            is GroupLoadingState.Retrying -> state.cursor
            else -> null
        }

        return try {
            currentClient.requestGroupMessages(
                groupId = groupId,
                channel = channel,
                until = cursor?.untilTimestamp,
                limit = PAGE_SIZE,
                subscriptionId = subscriptionId,
            )
            true
        } catch (e: Throwable) {
            loadingRegistry.unregisterSubscription(subscriptionId)
            controller.handleSendFailure(subscriptionId)
            updateLegacyFlags(groupId, controller.state.value)
            false
        }
    }

    /**
     * Get the current loading state for a group.
     */
    suspend fun getLoadingState(groupId: String): GroupLoadingState = loadingRegistry.getController(groupId).state.value

    /**
     * Handle connection lost - notify all active loaders.
     */
    suspend fun handleConnectionLost() {
        loadingRegistry.handleDisconnectAll()
        muxTracker.clearAll()
        recentRequests.clear()
    }

    /**
     * Handle connection lost for a specific set of groups (e.g. a pool relay dropped).
     * Resets their loading states to Idle so re-subscription can proceed after reconnect.
     * Also clears the mux tracker so reconnect re-sends fresh subscriptions.
     */
    suspend fun handleConnectionLostForGroups(groupIds: List<String>) {
        loadingRegistry.handleDisconnectForGroups(groupIds)
        // Clear subscription tracker for affected relays so reconnect re-sends mux.
        groupIds.mapNotNull { getRelayForGroup(it) }.distinct().forEach {
            muxTracker.clearRelay(it)
        }
    }

    /**
     * Reset loading states WITHOUT clearing the mux tracker.
     * Use this when re-subscribing after auth — the caller is responsible for
     * clearing the tracker separately via [clearMuxTrackerForRelay] if needed.
     */
    suspend fun resetLoadingForGroups(groupIds: List<String>) {
        loadingRegistry.handleDisconnectForGroups(groupIds)
    }

    /**
     * Like [resetLoadingForGroups] but PRESERVES pagination cursors for groups that
     * are mid-pagination (see [GroupLoadingController.handleReconnect]). Does NOT touch
     * the mux tracker (the caller clears it separately). Use on a re-AUTH re-subscribe so
     * a periodic AUTH challenge doesn't reset an actively-scrolled group to Idle, which
     * would re-fire the initial load with `until = oldest - 1`, jump to the floor, inject
     * ancient events at the top and mark the group Exhausted.
     */
    suspend fun resetLoadingForGroupsPreservingCursor(groupIds: List<String>) {
        loadingRegistry.handleReconnectForGroups(groupIds)
    }

    /**
     * Re-subscribe opened groups after a reconnect while PRESERVING pagination
     * progress (cursor). Clears the mux tracker so the live feed is re-sent, but
     * keeps how far back the user has scrolled so the next page resumes from the
     * same frontier instead of jumping to the oldest message and going Exhausted.
     */
    suspend fun handleReconnectForGroups(groupIds: List<String>) {
        loadingRegistry.handleReconnectForGroups(groupIds)
        groupIds.mapNotNull { getRelayForGroup(it) }.distinct().forEach {
            muxTracker.clearRelay(it)
        }
    }

    /**
     * Clear the mux subscription tracker for a specific relay so the next
     * [refreshMuxSubscriptionsForRelay] call always re-sends subscriptions.
     * Use after AUTH challenges where the relay may have dropped active subs.
     */
    fun clearMuxTrackerForRelay(relayUrl: String) {
        muxTracker.clearRelay(relayUrl)
    }

    /**
     * Proof of life on one of [relayUrl]'s mux subs (EVENT or EOSE). Feeds the
     * staleness check that lets [refreshLiveSubscriptions] re-arm a silently dead sub.
     */
    fun noteMuxActivity(relayUrl: String) {
        muxTracker.noteActivity(relayUrl, epochMillis())
    }

    /**
     * Bounded forward-fill after a dead live-sub window (AUTH raced, mux CLOSED, relay
     * race). A message missed live is otherwise gone for the session: later messages
     * advance the LiveCursorStore cursor PAST it, so mux replay (since = cursor) never
     * revisits the gap, and msg_ pagination only fetches older-than-cache. Re-request a
     * small recent window per loaded group — since = that group's newest in-memory
     * message minus the reconnect overlap — and let the deduplicator drop what we
     * already have. Only groups with in-memory messages participate; empty groups are
     * covered by the initial-load path.
     */
    suspend fun backfillRecentGapsForRelay(relayUrl: String) {
        val client = connectionManager.getClientForRelay(relayUrl) ?: return
        if (!client.isConnected()) return
        val restricted = _restrictedGroups.value
        val loaded = getGroupIdsForMux(relayUrl)
            .filter { groupKey(relayUrl, it) !in restricted }
            .mapNotNull { gid -> _messages.value[gid]?.lastOrNull()?.createdAt?.let { gid to it } }
        if (loaded.isEmpty()) return
        val since = loaded.minOf { it.second } - LiveCursorStore.RECONNECT_OVERLAP_S
        try {
            client.requestGroupsMessagesSince(loaded.map { it.first }, since)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /**
     * Send a message to a group.
     * Uses sendAndAwaitOk() to properly wait for relay confirmation.
     */
    suspend fun sendMessage(
        groupId: String,
        content: String,
        pubKey: String,
        channel: String? = null,
        mentions: Map<String, String> = emptyMap(),
        replyToMessageId: String? = null,
        extraTags: List<List<String>> = emptyList(),
        signEvent: suspend (Event) -> Event,
    ): Result<Unit> = try {
        val tags = mutableListOf(listOf("h", groupId))
        if (channel != null && channel != "general") {
            tags.add(listOf("channel", channel))
        }

        // Add reply tag if replying to a message (NIP-29: use "q" tag)
        if (replyToMessageId != null) {
            tags.add(listOf("q", replyToMessageId))
        }

        val (processedContent, mentionTags) = MentionTags.apply(content, mentions, tags)
        tags.addAll(mentionTags)

        // p-tag the author of the message being replied to (NIP-10/22). Without
        // it the recipient is only classified as "replied to" when they happen
        // to have the parent message cached locally, so replies silently miss
        // the per-group "mentions & replies only" notification filter (#70).
        if (replyToMessageId != null) {
            val parentAuthor = findMessageByIdAcrossGroups(replyToMessageId)?.second?.pubkey
            if (parentAuthor != null &&
                parentAuthor != pubKey &&
                tags.none { it.size >= 2 && it[0] == "p" && it[1] == parentAuthor }
            ) {
                tags.add(listOf("p", parentAuthor))
            }
        }

        // Add extra tags (e.g. NIP-68 imeta tags from media uploads), dedup by content
        extraTags.forEach { tag -> if (tag !in tags) tags.add(tag) }

        val event = Event(
            pubkey = pubKey,
            createdAt = epochMillis() / 1000,
            kind = 9,
            tags = tags,
            content = processedContent,
        )

        // Stable NIP-01 id, computable before signing, so the optimistic bubble
        // carries the final id and the relay echo dedups against it.
        val eventId = event.calculateId()

        // Optimistic insert before signing: the message shows immediately with a
        // Sending status. Signing and delivery run on the manager scope so a group
        // switch or screen exit does not cancel them, and a signing failure (e.g. a
        // bunker timeout) resolves to a Failed status with retry, never a modal.
        insertOwnMessage(
            groupId,
            NostrGroupClient.NostrMessage(
                id = eventId,
                pubkey = event.pubkey,
                content = event.content,
                createdAt = event.createdAt,
                kind = event.kind,
                tags = event.tags,
                // Stamp the send target so the optimistic bubble stays scoped to the
                // group's own relay (same-id groups on other relays must not show it).
                relayUrl = getRelayForGroup(groupId)?.normalizeRelayUrl(),
            ),
        )
        _messageStatus.update { it + (eventId to MessageStatus.Sending) }

        scope.launch { signAndDeliver(groupId, event, eventId, signEvent) }
        Result.Success(Unit)
    } catch (e: Throwable) {
        // Pre-build failure before the optimistic insert: nothing is on screen, so
        // surface a real error (the composer keeps the draft).
        Result.Error(AppError.Group.SendFailed(groupId, e))
    }

    /**
     * Insert one of the local user's own messages into [_messages] immediately,
     * reusing [messageIdIndex] so the relay's later echo of the same id is deduped.
     *
     * NOT cached here: the message status lives only in memory, so a cached
     * optimistic message the relay never accepted would come back after restart
     * with no pending icon — a ghost only this device can see. [markDelivered]
     * caches it once the relay confirms.
     */
    private fun insertOwnMessage(groupId: String, message: NostrGroupClient.NostrMessage) {
        _messages.update { currentMap ->
            val current = currentMap[groupId] ?: emptyList()
            val index = messageIdIndex.getOrPut(groupId) { current.mapTo(mutableSetOf()) { it.id } }
            if (!index.add(message.id)) return@update currentMap
            currentMap + (groupId to (current + message).sortedBy { it.createdAt })
        }
        touchGroupRecency(groupId)
    }

    /**
     * Sign an optimistic message off the UI scope, then deliver it. A signing failure
     * (e.g. a bunker timeout or refusal) marks the message Failed carrying the unsigned
     * event so retry can re-sign; success hands the signed JSON to deliverMessage().
     */
    private suspend fun signAndDeliver(
        groupId: String,
        event: Event,
        eventId: String,
        signEvent: suspend (Event) -> Event,
    ) {
        val message = try {
            val signed = signEvent(event)
            buildJsonArray {
                add("EVENT")
                add(signed.toJsonObject())
            }.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            markFailed(eventId, groupId, eventJson = null, reason = e.message ?: "Could not sign message", unsignedEvent = event)
            return
        }
        deliverMessage(groupId, message, eventId)
    }

    /**
     * Publish an optimistic message and resolve its status. On timeout/network
     * error the message is queued for auto-retry and stays in [MessageStatus.Sending];
     * only a relay rejection marks it [MessageStatus.Failed].
     */
    private suspend fun deliverMessage(groupId: String, eventJson: String, eventId: String) {
        val client = clientForGroup(groupId)
        if (client == null) {
            // No socket yet: queue so it retries on reconnect; keep it Sending.
            pendingEventManager?.queueEvent(eventJson, eventId, groupId)
            return
        }
        when (val result = client.sendAndAwaitOk(eventJson, eventId)) {
            is org.nostr.nostrord.network.PublishResult.Success -> markDelivered(eventId)
            is org.nostr.nostrord.network.PublishResult.Rejected ->
                markFailed(eventId, groupId, eventJson, result.reason)
            is org.nostr.nostrord.network.PublishResult.Timeout ->
                pendingEventManager?.queueEvent(eventJson, eventId, groupId)
            is org.nostr.nostrord.network.PublishResult.Error ->
                pendingEventManager?.queueEvent(eventJson, eventId, groupId)
        }
    }

    private fun markDelivered(eventId: String) {
        // Only tracked sends flip to Delivered; an id already dismissed (or from a
        // previous session) stays absent so history never grows a stray check.
        _messageStatus.update { statuses ->
            if (eventId in statuses) statuses + (eventId to MessageStatus.Delivered) else statuses
        }
        // First relay confirmation is what makes the message durable. The relay's
        // echo is deduped by messageIdIndex and never reaches the batch cache path,
        // so this is the only place an own message enters the cache.
        for ((groupId, msgs) in _messages.value) {
            val msg = msgs.lastOrNull { it.id == eventId } ?: continue
            cacheMessages(groupId, listOf(msg))
            break
        }
    }

    private fun markFailed(eventId: String, groupId: String, eventJson: String?, reason: String, unsignedEvent: Event? = null) {
        _messageStatus.update { it + (eventId to MessageStatus.Failed(reason, groupId, eventJson, unsignedEvent)) }
    }

    /**
     * Re-send a previously failed own message: re-delivers the stored signed JSON, or
     * re-signs the unsigned event first when the original failure happened while signing.
     */
    fun retrySend(eventId: String, signEvent: suspend (Event) -> Event) {
        val failed = _messageStatus.value[eventId] as? MessageStatus.Failed ?: return
        _messageStatus.update { it + (eventId to MessageStatus.Sending) }
        scope.launch {
            val json = failed.eventJson
            when {
                json != null -> deliverMessage(failed.groupId, json, eventId)
                failed.unsignedEvent != null -> signAndDeliver(failed.groupId, failed.unsignedEvent, eventId, signEvent)
            }
        }
    }

    /** Drop a failed own message from the chat (user dismissed it). */
    fun dismissFailed(groupId: String, eventId: String) {
        _messageStatus.update { it - eventId }
        _messages.update { currentMap ->
            val current = currentMap[groupId] ?: return@update currentMap
            val filtered = current.filterNot { it.id == eventId }
            if (filtered.size == current.size) currentMap else currentMap + (groupId to filtered)
        }
        messageIdIndex[groupId]?.remove(eventId)
    }

    // ---- Forum threads (NIP-29 kind:11 root + NIP-22 kind:1111 replies) ----

    private fun threadRootsSubId(groupId: String) = "threads_$groupId"

    private fun threadRepliesSubId(groupId: String) = "threadrepl_$groupId"

    /**
     * Ids the current threads REQ delivered, per group. Its EOSE compares them against what we
     * hold to tell "the relay no longer serves this" from "we never asked": a thread deleted on
     * another device is simply absent from the answer, and the kind:5/9005 that removed it is
     * never replayed to this one, so the disk cache would rehydrate it forever.
     *
     * A StateFlow (not a plain map) because the ingest path writes it off the relay socket while
     * the pane's REQ resets it: `update` is atomic, a shared MutableMap would race.
     */
    private val threadSubSeenIds = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /** Start a fresh answer window for [groupId]; called every time the threads REQ is issued. */
    private fun resetThreadSubSeen(groupId: String) {
        threadSubSeenIds.update { it + (groupId to emptySet()) }
    }

    /** Record an id the relay served for [groupId]'s threads REQ (before dedup drops replays). */
    private fun noteThreadSubSeen(groupId: String, eventId: String) {
        threadSubSeenIds.update { current ->
            val seen = current[groupId] ?: return@update current
            if (eventId in seen) current else current + (groupId to (seen + eventId))
        }
    }

    /**
     * Drop stored thread events the relay's answer did not include: they were deleted elsewhere.
     *
     * Bounded by the answer's own window. The REQ is limit-capped, so anything older than the
     * oldest event served is simply outside what the relay was asked for and must be kept -
     * without that guard, a group with more threads than [THREAD_PAGE_SIZE] would lose its
     * history on every EOSE. Entries from another relay and un-echoed optimistic sends (no
     * relayUrl) are never touched. Removals are evicted from the disk cache too, otherwise the
     * next cold open rehydrates exactly what was just reconciled away.
     */
    private fun reconcileThreadsAtEose(groupId: String, relayUrl: String, kind: Int) {
        val seen = threadSubSeenIds.value[groupId] ?: return
        val relay = relayUrl.normalizeRelayUrl()
        val store = if (kind == 11) _threadRoots else _threadReplies
        val removed = mutableSetOf<String>()
        store.update { current ->
            val list = current[groupId] ?: return@update current
            val stale = staleThreadEventIds(list.filter { it.kind == kind && it.relayUrl?.normalizeRelayUrl() == relay }, seen)
            if (stale.isEmpty()) return@update current
            removed += stale
            current + (groupId to list.filterNot { it.id in stale })
        }
        if (removed.isEmpty()) return
        val account = currentPubkey ?: return
        scope.launch {
            try {
                cacheStore.deleteByIds(account, removed)
            } catch (_: Exception) {
            }
        }
    }

    /** Reconcile roots against the relay's answer; called on the threads_ sub's EOSE. */
    fun reconcileThreadRootsAtEose(groupId: String, relayUrl: String) = reconcileThreadsAtEose(groupId, relayUrl, kind = 11)

    /** Reconcile replies against the relay's answer; called on the threadrepl_ sub's EOSE. */
    fun reconcileThreadRepliesAtEose(groupId: String, relayUrl: String) = reconcileThreadsAtEose(groupId, relayUrl, kind = 1111)

    /** Route a parsed thread event into its store (kind:11 roots, kind:1111 replies), deduped. */
    private fun applyThreadEvent(groupId: String, message: NostrGroupClient.NostrMessage, persist: Boolean = true) {
        val store = if (message.kind == 11) _threadRoots else _threadReplies
        var inserted = false
        store.update { current ->
            val list = current[groupId] ?: emptyList()
            if (list.any { it.id == message.id }) return@update current
            inserted = true
            current + (groupId to (list + message).sortedBy { it.createdAt })
        }
        // Notify only on a real insert of a relay-delivered event: a disk hydration is not news,
        // and an un-echoed optimistic send is our own.
        if (inserted && persist && message.relayUrl != null) onThreadEventReceived?.invoke(groupId, message)
        // A quoted kind:11 (the "Share to chat" announcement) resolves through the by-id event
        // cache, which the thread stores never reach: without this the card pays a network
        // round-trip for an event already in memory, and reads as removed when the relay
        // withholds it. Skipped on disk hydration, whose events the mirror already persisted.
        if (inserted && persist) onThreadEventCached?.invoke(message)
        // Write-through OUTSIDE the in-memory dedup: the relay echo of an optimistic
        // (relay-less) insert is deduped above, yet it is the first relay-attributed copy
        // and the one that must reach the disk cache. Upserts are idempotent.
        if (persist) cacheThreadEvent(groupId, message)
    }

    /**
     * Disk-cache slot for a group's forum threads (kind:11 + 1111 together, split by kind on
     * load) on one relay. The trailing segment keeps it disjoint from the chat history slot
     * ("relay|groupId"); '|' cannot appear in a relay URL or group id.
     */
    private fun threadCacheSlot(
        relayUrl: String,
        groupId: String,
    ): String = "${relayUrl.normalizeRelayUrl()}|$groupId|threads"

    /**
     * Write a thread event through to the persistent cache (fire-and-forget). Relay-less
     * events (the optimistic insert before the relay echo) are skipped: hydration is
     * relay-scoped and a send that never echoes must not rehydrate as a ghost.
     */
    /**
     * Persist a reaction that targets a thread event into that thread's cache slot, so reopening
     * the pane shows its chips from disk instead of a bare card until the relay answers. Chat
     * reactions are not cached (their slot is the chat history and nothing rehydrates them).
     *
     * The tags are rebuilt from the parsed reaction rather than kept raw: only e/p/h/E/emoji
     * matter to [toNostrReaction], and this keeps the row the same shape a thread event has.
     */
    private fun cacheThreadReaction(reaction: NostrGroupClient.NostrReaction, relayUrl: String) {
        val account = currentPubkey ?: return
        val groupId = reaction.groupId ?: return
        val rootId = reaction.threadRootId ?: threadRootForEvent(reaction.targetEventId) ?: return
        val slot = threadCacheSlot(relayUrl, groupId)
        val tags = threadReactionCacheTags(reaction, groupId, rootId)
        scope.launch {
            try {
                cacheStore.upsertMessages(
                    account,
                    slot,
                    listOf(
                        CachedMsg(
                            id = reaction.id,
                            groupId = slot,
                            pubkey = reaction.pubkey,
                            createdAt = reaction.createdAt,
                            kind = 7,
                            content = reaction.emoji,
                            tagsJson = json.encodeToString(tagsSerializer, tags),
                        ),
                    ),
                )
            } catch (_: Exception) {
            }
        }
        scheduleCacheEviction()
    }

    /** Rebuild a reaction from its cached row; null when the row is not a usable kind:7. */
    private fun CachedMsg.toNostrReaction(): NostrGroupClient.NostrReaction? {
        if (kind != 7) return null
        val tags = try {
            json.decodeFromString(tagsSerializer, tagsJson)
        } catch (_: Exception) {
            return null
        }
        return reactionFromCacheRow(id, pubkey, createdAt, content, tags)
    }

    private fun cacheThreadEvent(groupId: String, message: NostrGroupClient.NostrMessage) {
        val account = currentPubkey ?: return
        val relay = message.relayUrl ?: return
        scope.launch {
            try {
                val slot = threadCacheSlot(relay, groupId)
                cacheStore.upsertMessages(account, slot, listOf(message.toCachedMsg(slot)))
            } catch (_: Exception) {
            }
        }
        scheduleCacheEviction()
    }

    // Groups whose threads pane already hydrated from disk this session: keeps the VM's
    // requestGroupThreads retry loop (and pane re-opens) from re-reading the store.
    private val hydratedThreadGroups = mutableSetOf<String>()

    /**
     * Render a previously-seen threads pane instantly from the persistent cache, before any
     * relay round-trip (stale-while-revalidate, the threads analogue of
     * [hydrateMessagesFromCache]). The live subscriptions then refresh and dedupe by id;
     * deletions were already evicted from the cache when their kind 5/9005 arrived.
     */
    private suspend fun hydrateThreadsFromCache(groupId: String) {
        val account = currentPubkey ?: return
        val relay = getRelayForGroup(groupId) ?: return
        if (!hydratedThreadGroups.add(groupId)) return
        val cached =
            try {
                cacheStore.loadLatest(account, threadCacheSlot(relay, groupId), THREAD_CACHE_HYDRATE_LIMIT)
            } catch (_: Exception) {
                return
            }
        for (row in cached) {
            // The slot holds the thread's reactions alongside its events; a kind:7 must rebuild
            // a reaction, not be filed as a reply.
            if (row.kind == 7) {
                row.toNostrReaction()?.let { handleReaction(it) }
            } else {
                applyThreadEvent(groupId, row.toNostrMessage().withOriginRelay(relay), persist = false)
            }
        }
    }

    // Groups with an open threads pane, so they can be resubscribed after NIP-42 AUTH: a private
    // group's pre-AUTH thread REQ comes back CLOSED "auth-required" and otherwise never retries.
    private val openThreadGroups = mutableSetOf<String>()

    /**
     * Open the threads pane for a group: subscribe to kind:11 roots plus the batched kind:1111
     * reply feed (for per-thread counts/previews). Both subscriptions stay open for live updates
     * until [closeThreadSubscriptions]. Waits for AUTH on a private group, like the chat read; if
     * AUTH lands later, [resubscribeOpenThreadsAfterAuth] re-fires these.
     */
    suspend fun requestGroupThreads(groupId: String): Boolean {
        // Show the known threads instantly from disk (stale-while-revalidate) - before the
        // client check, so a cold or offline open still renders and the VM's retry loop
        // (which re-enters here until the client is ready) hits the hydration guard.
        hydrateThreadsFromCache(groupId)
        val client = clientForGroup(groupId) ?: return false
        openThreadGroups.add(groupId)
        if (client.requiresAuth() && !client.hasAuthSucceeded()) {
            client.awaitAuthOrTimeout(INITIAL_READ_AUTH_TIMEOUT_MS)
        }
        return try {
            resetThreadSubSeen(groupId)
            client.requestGroupThreadRoots(
                groupId,
                limit = THREAD_PAGE_SIZE,
                subscriptionId = threadRootsSubId(groupId),
            )
            client.requestThreadReplies(
                groupId,
                rootId = null,
                limit = THREAD_REPLY_BATCH,
                subscriptionId = threadRepliesSubId(groupId),
            )
            true
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Re-fire thread subscriptions for any open threads pane on [relayUrl] after NIP-42 AUTH
     * succeeds. A private group's pre-AUTH thread REQ is CLOSED "auth-required"; this is the
     * threads analogue of the chat mux's resubscribeAfterAuth.
     */
    suspend fun resubscribeOpenThreadsAfterAuth(relayUrl: String) {
        val normalized = relayUrl.normalizeRelayUrl()
        for (groupId in openThreadGroups.toList()) {
            if (getRelayForGroup(groupId)?.normalizeRelayUrl() != normalized) continue
            val client = clientForGroup(groupId) ?: continue
            runCatching {
                resetThreadSubSeen(groupId)
                client.requestGroupThreadRoots(groupId, limit = THREAD_PAGE_SIZE, subscriptionId = threadRootsSubId(groupId))
                client.requestThreadReplies(groupId, rootId = null, limit = THREAD_REPLY_BATCH, subscriptionId = threadRepliesSubId(groupId))
            }
        }
    }

    /**
     * CLOSE the threads-pane subscriptions for a group (on leaving the pane). Fire-and-forget on
     * the manager scope so a ViewModel can call it from onCleared without a live caller scope.
     */
    fun closeThreadSubscriptions(groupId: String) {
        openThreadGroups.remove(groupId)
        scope.launch {
            // A responsive relayout (a desktop window crossing the 768dp mobile breakpoint, or any
            // fast dispose/recreate) tears down then immediately rebuilds the threads pane. The new
            // instance re-adds the group via requestGroupThreads; wait a beat and skip the close if
            // it was re-opened, so we never CLOSE the subscription the new instance just opened
            // (which left the list permanently empty / stuck loading on mobile).
            delay(500)
            if (groupId in openThreadGroups) return@launch
            val client = clientForGroup(groupId) ?: return@launch
            runCatching {
                client.closeSubscription(threadRootsSubId(groupId))
                client.closeSubscription(threadRepliesSubId(groupId))
            }
        }
    }

    /**
     * Backfill one thread for a deep link (#/g/.../threads/<rootId>): the roots subscription only
     * returns the latest [THREAD_PAGE_SIZE], so an older root (and its replies) may never arrive
     * from it, leaving the detail view stuck on "Loading thread...". Fetch the kind:11 root by id
     * (routes into [_threadRoots]) and a focused #E reply page so the detail resolves.
     */
    suspend fun fetchThread(groupId: String, rootId: String) {
        // Retry so a cold-start deep link (the group client is still connecting when the screen
        // mounts) still lands the fetch instead of silently no-op'ing on a null client.
        repeat(8) {
            val client = clientForGroup(groupId)
            if (client != null) {
                runCatching {
                    client.requestGroupMessageById(groupId, rootId)
                    client.requestThreadReplies(groupId, rootId = rootId, subscriptionId = "threadfocus_$rootId")
                    // The root arrives via an id fetch (no post-EOSE kind:7 backfill like the
                    // thread subs), so fetch its reactions directly.
                    client.requestReactionsForMessages(listOf(rootId))
                }
                return
            }
            delay(500)
        }
    }

    /**
     * Create a forum thread (kind:11 root). Optimistically inserts into the thread store and
     * publishes on the manager scope so a pane switch does not cancel the send; the relay echo
     * is deduped by id. [title] becomes a NIP-14 `subject` tag (omitted when blank).
     */
    suspend fun createThread(
        groupId: String,
        title: String,
        content: String,
        pubKey: String,
        mentions: Map<String, String> = emptyMap(),
        signEvent: suspend (Event) -> Event,
    ): Result<String> = try {
        val tags = ThreadTags.root(groupId, getRelayForGroup(groupId), title)
        val (body, mentionTags) = MentionTags.apply(content, mentions, tags)
        publishThreadEvent(groupId, kind = 11, content = body, tags = tags + mentionTags, pubKey = pubKey, signEvent = signEvent)
    } catch (e: Throwable) {
        Result.Error(AppError.Group.SendFailed(groupId, e))
    }

    /**
     * Publish a NIP-22 reply (kind:1111) to a thread. [root] is always the kind:11 thread root;
     * [parent] is the item being replied to (the root for a top-level reply, or another reply
     * for a nested one). Uppercase E/K/P carry the root scope (kept identical across the whole
     * thread so nested replies stay attached to the original root); lowercase e/k/p the parent.
     */
    suspend fun sendThreadReply(
        groupId: String,
        root: NostrGroupClient.NostrMessage,
        parent: NostrGroupClient.NostrMessage,
        content: String,
        pubKey: String,
        mentions: Map<String, String> = emptyMap(),
        signEvent: suspend (Event) -> Event,
    ): Result<Unit> = try {
        val tags = ThreadTags.reply(groupId, getRelayForGroup(groupId), root, parent)
        // The reply already p-tags the parent author, so mentioning them adds no second tag.
        val (body, mentionTags) = MentionTags.apply(content, mentions, tags)
        when (val result = publishThreadEvent(groupId, kind = 1111, content = body, tags = tags + mentionTags, pubKey = pubKey, signEvent = signEvent)) {
            is Result.Success -> Result.Success(Unit)
            is Result.Error -> Result.Error(result.error)
        }
    } catch (e: Throwable) {
        Result.Error(AppError.Group.SendFailed(groupId, e))
    }

    /** Shared sign + optimistic-insert + deliver pipeline for thread events (kind 11 / 1111). */
    /** Returns the published event's id (computed before signing; the NIP-01 id is stable). */
    private suspend fun publishThreadEvent(
        groupId: String,
        kind: Int,
        content: String,
        tags: List<List<String>>,
        pubKey: String,
        signEvent: suspend (Event) -> Event,
    ): Result<String> {
        val event = Event(
            pubkey = pubKey,
            createdAt = epochMillis() / 1000,
            kind = kind,
            tags = tags,
            content = content,
        )
        // Insert the optimistic thread item before signing (stable NIP-01 id), then
        // sign + deliver on the manager scope, so a bunker sign timeout resolves to a
        // Failed status with retry instead of a modal, matching the chat send path.
        val eventId = event.calculateId()
        applyThreadEvent(
            groupId,
            NostrGroupClient.NostrMessage(
                id = eventId,
                pubkey = event.pubkey,
                content = event.content,
                createdAt = event.createdAt,
                kind = event.kind,
                tags = event.tags,
            ),
        )
        _messageStatus.update { it + (eventId to MessageStatus.Sending) }
        scope.launch { signAndDeliver(groupId, event, eventId, signEvent) }
        return Result.Success(eventId)
    }

    /**
     * Send a reaction to a message (NIP-25: kind 7 reaction event).
     * Uses optimistic update: shows the reaction immediately, rolls back on rejection.
     */
    suspend fun sendReaction(
        groupId: String,
        targetEventId: String,
        targetPubkey: String,
        emoji: String,
        pubKey: String,
        signEvent: suspend (Event) -> Event,
        // Set when reacting inside a thread: carries the root so the target's readers can open
        // the thread without having the reacted-to event loaded.
        threadRootId: String? = null,
    ): Result<Unit> {
        val currentClient = clientForGroup(groupId)
            ?: return Result.Error(AppError.Network.Disconnected(""))

        return try {
            val groupRelayUrl = getRelayForGroup(groupId) ?: ""
            val tags = buildList {
                add(listOf("h", groupId, groupRelayUrl))
                add(listOf("e", targetEventId, "", "", targetPubkey))
                add(listOf("p", targetPubkey))
                if (threadRootId != null) add(listOf("E", threadRootId))
                // Include emoji tag for custom emojis (NIP-30) so other clients can resolve the URL
                val shortcode = emoji.trim(':')
                if (emoji.startsWith(":") && emoji.endsWith(":") && shortcode.isNotEmpty()) {
                    emojiUrlCache.get(shortcode)?.let { url ->
                        add(listOf("emoji", shortcode, url))
                    }
                }
            }

            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 7,
                tags = tags,
                content = emoji,
            )

            val signedEvent = signEvent(event)
            val eventJson = signedEvent.toJsonObject()
            val message = buildJsonArray {
                add("EVENT")
                add(eventJson)
            }.toString()

            val eventId = signedEvent.id
                ?: return Result.Error(AppError.Group.SendFailed(groupId, Exception("Event ID not generated")))

            // Optimistic update: show reaction in UI immediately (no debounce)
            handleReaction(
                NostrGroupClient.NostrReaction(
                    id = eventId,
                    pubkey = pubKey,
                    emoji = emoji,
                    emojiUrl = null,
                    targetEventId = targetEventId,
                    createdAt = event.createdAt,
                ),
                immediate = true,
            )

            // Send to group relay
            val publishResult = currentClient.sendAndAwaitOk(message, eventId)

            when (publishResult) {
                is org.nostr.nostrord.network.PublishResult.Success -> {
                    Result.Success(Unit)
                }
                is org.nostr.nostrord.network.PublishResult.Rejected -> {
                    // Rollback optimistic update
                    removeReaction(targetEventId, emoji, pubKey)
                    Result.Error(AppError.Group.SendFailed(groupId, Exception(publishResult.reason)))
                }
                is org.nostr.nostrord.network.PublishResult.Timeout -> {
                    Result.Error(AppError.Group.SendFailed(groupId, Exception("timeout")))
                }
                is org.nostr.nostrord.network.PublishResult.Error -> {
                    // Rollback optimistic update
                    removeReaction(targetEventId, emoji, pubKey)
                    Result.Error(AppError.Group.SendFailed(groupId, publishResult.exception))
                }
            }
        } catch (e: Throwable) {
            Result.Error(AppError.Group.SendFailed(groupId, e))
        }
    }

    /**
     * Delete a message from a group.
     *
     * The deletion kind is chosen by who is deleting whose message:
     * - Authors deleting their own message use kind 5 (NIP-09 standard deletion), which stays
     *   honored outside this relay by anything that mirrored the event.
     * - Admins deleting another member's message use kind 9005 (NIP-29
     *   delete-event moderation action). NIP-29 relays only honor a kind 5 from
     *   the author; removing someone else's message requires the admin kind 9005.
     * - A kind 5 an admin sends is retried as 9005 when the relay refuses the kind outright
     *   ("kind 5 not allowed"): some NIP-29 relays accept only the moderation route, and an
     *   admin must not be the one member who cannot delete their own message there.
     *
     * Sends the deletion event to the relay; the relay broadcasts it and
     * handleDeletion() removes it from local state when received back.
     */
    suspend fun deleteMessage(
        groupId: String,
        messageId: String,
        pubKey: String,
        signEvent: suspend (Event) -> Event,
    ): Result<Unit> {
        val currentClient = clientForGroup(groupId)
            ?: return Result.Error(AppError.Network.Disconnected(""))
        // Thread roots/replies live in their own stores, so check those too or deleting a thread
        // would misread ownership (author null -> wrong deletion kind).
        val messageAuthor = (
            _messages.value[groupId].orEmpty() +
                _threadRoots.value[groupId].orEmpty() +
                _threadReplies.value[groupId].orEmpty()
            ).firstOrNull { it.id == messageId }?.pubkey
        val isAdmin = isGroupAdmin(groupId, pubKey)
        val deletionKind = deletionKindFor(isOwnMessage = messageAuthor == pubKey, isAdmin = isAdmin)

        suspend fun publish(kind: Int): org.nostr.nostrord.network.PublishResult {
            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = kind,
                tags = listOf(
                    listOf("h", groupId),
                    listOf("e", messageId),
                ),
                content = "",
            )
            val signedEvent = signEvent(event)
            val eventId = signedEvent.id
                ?: return org.nostr.nostrord.network.PublishResult.Error("", Exception("Event ID not generated"))
            val message = buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()
            return currentClient.sendAndAwaitOk(message, eventId)
        }

        return try {
            val first = publish(deletionKind)
            // Only a rejection escalates: a timeout is a connectivity problem, and resending
            // would race a delete the relay may still be processing.
            val result =
                if (first is org.nostr.nostrord.network.PublishResult.Rejected && deletionKind == 5 && isAdmin) {
                    publish(ADMIN_DELETE_EVENT_KIND)
                } else {
                    first
                }
            when (result) {
                is org.nostr.nostrord.network.PublishResult.Success -> Result.Success(Unit)
                is org.nostr.nostrord.network.PublishResult.Rejected ->
                    Result.Error(AppError.Group.SendFailed(groupId, Exception(result.reason)))
                is org.nostr.nostrord.network.PublishResult.Timeout ->
                    Result.Error(AppError.Group.SendTimeout(groupId))
                is org.nostr.nostrord.network.PublishResult.Error ->
                    Result.Error(AppError.Group.SendFailed(groupId, result.exception))
            }
        } catch (e: Throwable) {
            Result.Error(AppError.Group.SendFailed(groupId, e))
        }
    }

    /**
     * Rebuild and persist the group metadata snapshot for [relayUrl]: every joined group
     * (the rail / My groups) plus a capped set of the most recently seen non-joined groups,
     * so discovery cards on relays you're already on also render their name/avatar instantly
     * on the next launch. Bounded by [GROUP_SNAPSHOT_EXTRA_CAP] to keep the blob small.
     * No-op when [currentPubkey] is not set (unauthenticated state).
     */
    private fun persistJoinedGroupMetadataSnapshot(relayUrl: String) {
        val pubKey = currentPubkey ?: return
        val normalized = relayUrl.normalizeRelayUrl()
        val joinedIds = _joinedGroupsByRelay.value[normalized] ?: emptySet()
        val all = _groupsByRelay.value[normalized] ?: emptyList()
        val joined = all.filter { it.id in joinedIds }
        val others = all.filter { it.id !in joinedIds }.takeLast(GROUP_SNAPSHOT_EXTRA_CAP)
        val snapshot = joined + others
        try {
            SecureStorage.saveJoinedGroupMetadata(pubKey, normalized, json.encodeToString(groupMetadataListSerializer, snapshot))
        } catch (_: Exception) {}
    }

    /**
     * Handle incoming group metadata.
     * Updates the live flow for the current relay AND persists the joined-group snapshot
     * so returning to this relay later is instant without a network re-fetch.
     */
    fun handleGroupMetadata(metadata: GroupMetadata, relayUrl: String) {
        if (isDropped(relayUrl, metadata.id)) return
        val normalized = relayUrl.normalizeRelayUrl()
        pendingFetchSeenGroups[normalized]?.add(metadata.id)
        muxMetaSeenIds[normalized]?.add(metadata.id)
        val previousChildren = _groupsByRelay.value[normalized].orEmpty().firstOrNull { it.id == metadata.id }?.children.orEmpty()
        // ALL relays contribute to the unified group list regardless of which relay
        // is currently active. _groupsByRelay handles per-relay filtering for the UI.
        val wasNew = _groups.value.none { it.id == metadata.id }
        // Replace existing entry (update) or append if new — distinctBy keeps the first,
        // so we must map-replace instead to propagate edits in realtime.
        _groups.value = if (wasNew) {
            _groups.value + metadata
        } else {
            _groups.value.map { if (it.id == metadata.id) metadata else it }
        }
        _groupsByRelay.update { current ->
            val relayGroups = current[normalized] ?: emptyList()
            val isNewForRelay = relayGroups.none { it.id == metadata.id }
            val updated = if (isNewForRelay) {
                relayGroups + metadata
            } else {
                relayGroups.map { if (it.id == metadata.id) metadata else it }
            }
            current + (normalized to updated)
        }
        persistJoinedGroupMetadataSnapshot(relayUrl)

        // Bilateral child tags are relay-maintained: a fresh parent 39000 that dropped a
        // child it previously declared is the live deletion/move signal (not every relay
        // stores kind:9008 for the delete watch to replay). Prune the dropped child's
        // cached metadata; a moved (not deleted) child re-adds itself via its own
        // re-emitted 39000. Diffing against OUR previous cache keeps relays that never
        // emit child tags out of this path entirely.
        val droppedChildren = previousChildren
            .filter { it != metadata.id && it !in metadata.children }
            .filterTo(mutableSetOf()) { id ->
                _groupsByRelay.value[normalized].orEmpty().any { it.id == id && it.parent == metadata.id }
            }
        pruneRelayGroups(normalized, droppedChildren)

        // Recompute the full tree because a kind:39000 update can carry new
        // `child`/`parent` tags that move groups other than `metadata` itself.
        recomputeSubgroupTopology()
    }

    /**
     * Drop [ids]' cached metadata for [normalizedRelay]: they no longer exist there.
     * Unlike [handleRemoteDeleteGroup] this touches neither deletedGroupIds nor the
     * joined pins, so a false prune (AUTH-withheld metadata, subgroup move) self-heals
     * when the group's kind:39000 arrives again.
     */
    private fun pruneRelayGroups(normalizedRelay: String, ids: Set<String>) {
        if (ids.isEmpty()) return
        _groups.value = _groups.value.filter { it.id !in ids }
        _groupsByRelay.update { current ->
            val updated = (current[normalizedRelay] ?: emptyList()).filter { it.id !in ids }
            current + (normalizedRelay to updated)
        }
        persistJoinedGroupMetadataSnapshot(normalizedRelay)
        recomputeSubgroupTopology()
    }

    /**
     * Mux metadata REQ EOSE: every id REQed via `#d` that got no kind:39000 back no
     * longer exists on the relay — the durable deletion signal for relays that don't
     * store kind:9008. Public-only: an AUTH-gated relay may withhold private metadata,
     * which must not read as deletion. One-shot per arm.
     */
    fun reconcileMuxMetadataEose(relayUrl: String) {
        val normalized = relayUrl.normalizeRelayUrl()
        val expected = muxMetaExpectedIds.remove(normalized) ?: return
        val seen = muxMetaSeenIds.remove(normalized).orEmpty()
        val cached = _groupsByRelay.value[normalized].orEmpty().associateBy { it.id }
        val gone = expected.filterTo(mutableSetOf()) { it !in seen && cached[it]?.isPublic == true }
        pruneRelayGroups(normalized, gone)
    }

    /**
     * Recompute the parent→children map from the current `_groups` list. The relay
     * validates parent links (bilateral, admin-authored), so a `parent` tag on a
     * known group is trusted as-is; a child whose parent metadata we don't have
     * yet renders as a root until it arrives.
     */
    private fun recomputeSubgroupTopology() {
        val groups = _groups.value
        val byId = groups.associateBy { it.id }

        val nextChildren = mutableMapOf<String, MutableSet<String>>()
        for (child in groups) {
            val parentId = child.parent ?: continue
            if (parentId !in byId) continue
            nextChildren.getOrPut(parentId) { mutableSetOf() }.add(child.id)
        }
        _childrenByParent.value = nextChildren.mapValues { (_, s) -> s.toSet() }
    }

    /**
     * Restore the groups list from the per-relay cache.
     * Called on relay switch to show previously loaded groups instantly.
     */
    fun restoreGroupsForRelay(relayUrl: String) {
        val normalized = relayUrl.normalizeRelayUrl()
        currentRelayUrl = normalized
        // Merge cached groups for the new relay into the unified live list.
        // State is additive: previously loaded groups from other relays are kept.
        val cached = _groupsByRelay.value[normalized] ?: emptyList()
        if (cached.isNotEmpty()) {
            _groups.value = (_groups.value + cached).distinctBy { it.id }
            recomputeSubgroupTopology()
        }
    }

    /**
     * Restore the group metadata snapshot for fast startup display. Reads the pubkey-scoped
     * cache written by [persistJoinedGroupMetadataSnapshot] — the joined groups plus a capped
     * set of recently-seen others, a small, bounded dataset.
     * Does NOT restore [_fullGroupListFetchedRelays]: this snapshot is not a full list fetch,
     * so OTHER GROUPS still triggers a network fetch when opened.
     */
    fun restoreJoinedGroupMetadataFromStorage(pubkey: String, relayUrls: List<String>) {
        val now = epochSeconds()
        _groupsByRelay.update { current ->
            val updates = relayUrls.mapNotNull { url ->
                val normalized = url.normalizeRelayUrl()
                val jsonStr = SecureStorage.getJoinedGroupMetadata(pubkey, normalized) ?: return@mapNotNull null
                try {
                    val groups = json.decodeFromString(groupMetadataListSerializer, jsonStr)
                    if (groups.isNotEmpty()) normalized to groups else null
                } catch (_: Exception) {
                    null
                }
            }.toMap()
            current + updates
        }
        // Restore general cache freshness flags so hasCachedGroupsForRelay() skips the
        // network fetch when the joined-group snapshot is still within the TTL window.
        val normalizedUrls = relayUrls.map { it.normalizeRelayUrl() }
        val freshRelays = normalizedUrls.filter { normalized ->
            SecureStorage.isGroupListCacheFresh(normalized, currentPubkey, now) &&
                _groupsByRelay.value[normalized]?.isNotEmpty() == true
        }.toSet()
        if (freshRelays.isNotEmpty()) {
            _completeGroupLoadRelays.update { it + freshRelays }
        }
        recomputeSubgroupTopology()
    }

    /**
     * One group's members/admins/roles plus each list's source-event timestamp. The
     * timestamps are persisted so the in-memory staleness guards survive a restart: a
     * re-delivered same-or-older event after reconnect stays a no-op, and only a genuinely
     * newer event replaces the cached list.
     */
    @Serializable
    private data class MembershipSnapshot(
        val members: List<String> = emptyList(),
        val admins: List<String> = emptyList(),
        val roles: List<RoleDefinition> = emptyList(),
        val memberTs: Long = 0L,
        val adminTs: Long = 0L,
        val roleTs: Long = 0L,
    )

    // v2: relay -> groupId -> snapshot. The old bare-groupId format was relay-ambiguous: on
    // restore it hydrated only the flat maps, so a same-id group on another relay leaked its
    // admin/member lists (a wrong ADMIN badge) until live 39001/39002s replaced them.
    private val membershipSnapshotSerializer =
        MapSerializer(String.serializer(), MapSerializer(String.serializer(), MembershipSnapshot.serializer()))

    /**
     * Persist members/admins/roles for every known (relay, group) pair as one per-account blob.
     * Cheap and infrequent: only the rare kind:39001/39002/39003 handlers call it, and only when
     * a list actually changed. No-op when [currentPubkey] is unset (unauthenticated state).
     * Lists that arrived without relay attribution exist only in the flat maps and are not
     * persisted; they rebuild from the next live event.
     */
    private fun persistGroupMembershipSnapshot() {
        val pubKey = currentPubkey ?: return
        val membersByRelay = _groupMembersByRelay.value
        val adminsByRelay = _groupAdminsByRelay.value
        val rolesByRelay = _groupRolesByRelay.value
        val relays = membersByRelay.keys + adminsByRelay.keys + rolesByRelay.keys
        if (relays.isEmpty()) return
        val snapshot =
            relays.associateWith { relay ->
                val ids = membersByRelay[relay].orEmpty().keys +
                    adminsByRelay[relay].orEmpty().keys +
                    rolesByRelay[relay].orEmpty().keys
                ids.associateWith { id ->
                    MembershipSnapshot(
                        members = membersByRelay[relay]?.get(id) ?: emptyList(),
                        admins = adminsByRelay[relay]?.get(id) ?: emptyList(),
                        roles = rolesByRelay[relay]?.get(id) ?: emptyList(),
                        memberTs = scopedMemberEventTimestamps["$relay|$id"] ?: 0L,
                        adminTs = scopedAdminEventTimestamps["$relay|$id"] ?: 0L,
                        roleTs = scopedRoleEventTimestamps["$relay|$id"] ?: 0L,
                    )
                }
            }
        try {
            SecureStorage.saveGroupMembershipFor(pubKey, json.encodeToString(membershipSnapshotSerializer, snapshot))
        } catch (_: Exception) {
        }
    }

    /**
     * Hydrate members/admins/roles from the per-account cache before sockets open, so opening a
     * previously-seen group shows its member list with no spinner. Live data already in memory
     * wins over the cache (existing keys are kept), and the seeded timestamps only advance the
     * staleness guards, never roll them back. A blob in the old bare-groupId format fails to
     * decode and is discarded (one cold start rebuilds it) — hydrating it flat is exactly what
     * leaked another relay's admin badge onto a same-id group.
     */
    fun restoreGroupMembershipFromStorage(pubkey: String) {
        val raw = SecureStorage.loadGroupMembershipFor(pubkey) ?: return
        val snapshot =
            try {
                json.decodeFromString(membershipSnapshotSerializer, raw)
            } catch (_: Exception) {
                return
            }
        if (snapshot.isEmpty()) return
        val cachedMembers = mutableMapOf<String, MutableMap<String, List<String>>>()
        val cachedAdmins = mutableMapOf<String, MutableMap<String, List<String>>>()
        val cachedRoles = mutableMapOf<String, MutableMap<String, List<RoleDefinition>>>()
        snapshot.forEach { (relay, groups) ->
            groups.forEach { (id, snap) ->
                val key = "$relay|$id"
                if (snap.members.isNotEmpty()) {
                    cachedMembers.getOrPut(relay) { mutableMapOf() }[id] = snap.members
                    scopedMemberEventTimestamps[key] = maxOf(scopedMemberEventTimestamps[key] ?: 0L, snap.memberTs)
                    memberEventTimestamps[id] = maxOf(memberEventTimestamps[id] ?: 0L, snap.memberTs)
                }
                if (snap.admins.isNotEmpty()) {
                    cachedAdmins.getOrPut(relay) { mutableMapOf() }[id] = snap.admins
                    scopedAdminEventTimestamps[key] = maxOf(scopedAdminEventTimestamps[key] ?: 0L, snap.adminTs)
                    adminEventTimestamps[id] = maxOf(adminEventTimestamps[id] ?: 0L, snap.adminTs)
                }
                if (snap.roles.isNotEmpty()) {
                    cachedRoles.getOrPut(relay) { mutableMapOf() }[id] = snap.roles
                    scopedRoleEventTimestamps[key] = maxOf(scopedRoleEventTimestamps[key] ?: 0L, snap.roleTs)
                    roleEventTimestamps[id] = maxOf(roleEventTimestamps[id] ?: 0L, snap.roleTs)
                }
            }
        }
        // `cached + live` per relay so any list already updated this session keeps its live value.
        fun <T> merge(
            live: Map<String, Map<String, T>>,
            cached: Map<String, out Map<String, T>>,
        ): Map<String, Map<String, T>> = (live.keys + cached.keys).associateWith { relay ->
            cached[relay].orEmpty() + live[relay].orEmpty()
        }
        if (cachedMembers.isNotEmpty()) _groupMembersByRelay.update { merge(it, cachedMembers) }
        if (cachedAdmins.isNotEmpty()) _groupAdminsByRelay.update { merge(it, cachedAdmins) }
        if (cachedRoles.isNotEmpty()) _groupRolesByRelay.update { merge(it, cachedRoles) }
        // Flat compat maps (used when no host relay scopes the read): same cached + live merge,
        // folded across relays. The relay-scoped mirrors above are what the scoped UIs read.
        fun <T> flatten(byRelay: Map<String, out Map<String, T>>): Map<String, T> = byRelay.values.fold(emptyMap()) { acc, m -> acc + m }
        if (cachedMembers.isNotEmpty()) _groupMembers.update { flatten(cachedMembers) + it }
        if (cachedAdmins.isNotEmpty()) _groupAdmins.update { flatten(cachedAdmins) + it }
        if (cachedRoles.isNotEmpty()) _groupRoles.update { flatten(cachedRoles) + it }
    }

    /**
     * Prepend one page of older messages from the persistent cache, ahead of the current oldest
     * in-memory message. Returns true when it added something (the relay is then skipped for this
     * scroll), false when local history is exhausted so the caller paginates the relay. Reads
     * through the same dedup index as the live path; the relay's `until` cursor naturally
     * continues from the new, older in-memory boundary once the disk runs dry.
     */
    private suspend fun loadOlderFromCache(groupId: String): Boolean {
        val account = currentPubkey ?: return false
        val relay = getRelayForGroup(groupId) ?: return false
        val oldestInMemory = _messages.value[groupId]?.minOfOrNull { it.createdAt } ?: return false
        val olderPage =
            try {
                cacheStore.loadBefore(account, cacheSlot(relay, groupId), oldestInMemory, PAGE_SIZE)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // The web IndexedDB store can fail with a raw JS error (a TypeError), which is NOT
                // a Kotlin Exception. Catching only Exception let it escape loadMoreMessages and
                // kill the pagination coroutine, freezing scroll-back. Treat any cache failure as
                // "nothing cached" and fall through to the relay.
                return false
            }
        if (olderPage.isEmpty()) return false
        val restored = olderPage.map { it.toNostrMessage().withOriginRelay(relay) }
        var added = false
        _messages.update { current ->
            val existing = current[groupId] ?: emptyList()
            val index = messageIdIndex.getOrPut(groupId) { existing.mapTo(mutableSetOf()) { it.id } }
            val fresh = restored.filter { index.add(it.id) }
            if (fresh.isEmpty()) return@update current
            added = true
            current + (groupId to (existing + fresh).sortedBy { it.createdAt })
        }
        return added
    }

    /**
     * Returns true if we have a non-empty cached group list for the given relay.
     */
    fun hasCachedGroupsForRelay(relayUrl: String): Boolean {
        val normalized = relayUrl.normalizeRelayUrl()
        if (_groupsByRelay.value[normalized]?.isNotEmpty() != true) return false
        // Session flag set (EOSE received this session) — always valid.
        if (normalized in _completeGroupLoadRelays.value) return true
        // No session flag: check persisted timestamp so a fresh app restart can skip requestGroups().
        return SecureStorage.isGroupListCacheFresh(normalized, currentPubkey, epochSeconds())
    }

    /**
     * Handle incoming group members (kind 39002)
     * Returns list of member pubkeys that need metadata fetching
     */
    fun handleGroupMembers(members: GroupMembers, createdAt: Long = 0L, relayUrl: String? = null): List<String> {
        if (isRecentlyLeft(relayUrl, members.groupId)) {
            _loadingMembers.value = _loadingMembers.value - members.groupId
            return emptyList()
        }
        // Before the flat guard: a same-id group on another relay may carry a newer
        // timestamp there, which must not starve THIS relay's mirror.
        updateRelayScopedList(_groupMembersByRelay, scopedMemberEventTimestamps, relayUrl, members.groupId, createdAt, members.members)
        val existing = memberEventTimestamps[members.groupId] ?: 0L
        if (createdAt > 0L && createdAt < existing) {
            // Stale event from a slower relay — skip state update.
            connStats?.onStateConflict(members.groupId)
            _loadingMembers.value = _loadingMembers.value - members.groupId
            return _groupMembers.value[members.groupId] ?: emptyList()
        }
        if (createdAt > 0L) memberEventTimestamps[members.groupId] = createdAt
        // Skip StateFlow update if the member list is identical — avoids unnecessary recomposition.
        val currentMembers = _groupMembers.value[members.groupId]
        if (currentMembers != members.members) {
            _groupMembers.value = _groupMembers.value + (members.groupId to members.members)
            persistGroupMembershipSnapshot()
        }
        _loadingMembers.value = _loadingMembers.value - members.groupId

        val self = currentPubkey
        val selfNowMember = self != null && self in members.members
        val selfWasAbsent = currentMembers?.contains(self) != true
        // Relay this listing belongs to: every membership decision below is scoped to it,
        // because group ids repeat across relays.
        val hostRelay = (relayUrl ?: getRelayForGroup(members.groupId) ?: currentRelayUrl)?.normalizeRelayUrl()

        // The relay listing us in 39002 is the ground-truth approval signal. If we were gated
        // (locally pending OR persisted/CLOSED as restricted) and have just transitioned into the
        // member list, we gained read access this moment: clear the gate AND re-arm the live
        // subscription. Keyed on the restricted marker too, not only _pendingApprovalSince, because
        // some join paths (invite link / deep link) never set the pending marker, yet the relay
        // still CLOSED our pre-approval mux as "restricted" — without the re-arm the admin's later
        // messages never reach us until an app restart.
        if (selfNowMember &&
            selfWasAbsent &&
            (
                (hostRelay != null && groupKey(hostRelay, members.groupId) in _pendingApprovalSince.value) ||
                    (relayUrl != null && groupKey(relayUrl, members.groupId) in _restrictedGroups.value)
                )
        ) {
            onApprovalDetected(relayUrl, members.groupId)
        } else if (selfNowMember && relayUrl != null && groupKey(relayUrl, members.groupId) in _restrictedGroups.value) {
            // Confirmed membership with no live transition (e.g. a stale 7-day restricted marker
            // restored from SecureStorage on cold start): just drop the "Private group / invite
            // code" placeholder. The mux is built fresh this session, so no re-arm is needed.
            clearGroupRestricted(relayUrl, members.groupId)
        }

        // Self-heal a stale durable left marker: the relay lists us as a member AND we are joined
        // (our own kind:10009), so we genuinely rejoined — clear the marker so the group stops
        // reading NONE and rejoins the mux. Gated on `joined`: the B2 case (we left, the relay still
        // lists us) leaves us NOT joined, so the marker stays and the group keeps reading "left".
        if (self != null &&
            self in members.members &&
            hostRelay != null &&
            leftAtOn(hostRelay, members.groupId) != null &&
            isJoinedOn(hostRelay, members.groupId)
        ) {
            clearLeft(hostRelay, members.groupId)
            currentPubkey?.let { pk ->
                try {
                    SecureStorage.removeLeftGroupForRelay(pk, hostRelay, members.groupId)
                } catch (_: Exception) {}
            }
        }

        // An admin added us via kind:9000 (no kind:9021 of ours): the relay's 39002 is the
        // ground truth of relay-side membership, but locally the group only becomes a
        // PENDING invite — nothing enters the joined set / our kind:10009 until the user
        // accepts it (declining leaves via kind:9022).
        if (selfNowMember && self != null) {
            registerExternalAdd(members.groupId, relayUrl, createdAtSeconds = createdAt)
        } else if (self != null && !selfNowMember) {
            // No longer listed (admin removed us before we decided): the invite is moot.
            // Only this relay's invite: a same-id invite from another relay is a different group.
            if (_pendingGroupInvites.value[members.groupId]?.relayUrl == hostRelay) discardPendingInvite(members.groupId)
        }

        return members.members
    }

    /**
     * A membership granted externally (an admin's kind:9000), adopted into the joined set.
     * [actorPubkey]/[eventId] are null when the adoption was inferred from a kind:39002
     * listing instead of the put-user event itself.
     */
    data class ExternalGroupAdd(
        val groupId: String,
        val relayUrl: String,
        val actorPubkey: String?,
        val eventId: String?,
        val createdAtSeconds: Long,
    )

    /**
     * Emits on every adopted external membership (i.e. on [acceptPendingInvite]).
     * NostrRepository republishes kind:10009 on it so the group survives restarts.
     */
    private val _externalMembershipAdopted = MutableSharedFlow<ExternalGroupAdd>(extraBufferCapacity = 16)
    val externalMembershipAdopted: SharedFlow<ExternalGroupAdd> = _externalMembershipAdopted.asSharedFlow()

    /**
     * External adds (an admin's kind:9000 / a 39002 listing us) awaiting the user's
     * accept/decline, keyed by groupId. Nothing enters the joined set or the kind:10009
     * list until [acceptPendingInvite] — otherwise any group admin could make this client
     * publish a kind:10009 naming their group without the user's consent. Declining goes
     * through leaveGroup, whose durable left marker stops the relay's recurring 39002
     * from re-registering the invite.
     */
    private val _pendingGroupInvites = MutableStateFlow<Map<String, PendingGroupInvite>>(emptyMap())
    val pendingGroupInvites: StateFlow<Map<String, PendingGroupInvite>> = _pendingGroupInvites.asStateFlow()

    /**
     * Emits when an external add first lands in the pending set (or a silent 39002-inferred
     * entry learns its actor from the put-user event). AppModule turns it into a user
     * notification; NostrRepository fetches the group's kind:39000 so the prompt has a name.
     * Buffer sized for a catch-up burst: AppModule's collector waits up to 3s per event for
     * the group name, and tryEmit silently drops what doesn't fit.
     */
    private val _externalAddPending = MutableSharedFlow<ExternalGroupAdd>(extraBufferCapacity = 64)
    val externalAddPending: SharedFlow<ExternalGroupAdd> = _externalAddPending.asSharedFlow()

    init {
        // This init block sits AFTER _pendingGroupInvites' declaration on purpose: init
        // blocks run in declaration order, and the collector reads the field from another
        // thread — launched any earlier it can observe the not-yet-assigned field mid-
        // construction (an uncaught NPE that poisoned unrelated runTests).
        //
        // A group that becomes joined by ANY path settles its pending invite. The write
        // sites can't all know about invites — the key case is our own kind:10009 from
        // another device (create/accept there) landing AFTER the relay's put-user echo
        // registered an "invite" here; without this the creator is prompted to accept
        // their own group.
        scope.launch {
            _joinedGroupsByRelay.collect {
                if (_pendingGroupInvites.value.isEmpty()) return@collect
                // Settled only on the invite's OWN relay: the same id joined elsewhere is a
                // different group, and treating it as settled discarded the invite unanswered.
                _pendingGroupInvites.value.values
                    .filter { isJoinedOn(it.relayUrl, it.groupId) }
                    .forEach { discardPendingInvite(it.groupId) }
            }
        }
    }

    /** True when [groupId] is in the joined set of [relayUrl] specifically (ids repeat across relays). */
    private fun isJoinedOn(
        relayUrl: String,
        groupId: String,
    ): Boolean = _joinedGroupsByRelay.value.isJoinedOn(relayUrl, groupId)

    private fun persistPendingInvites() {
        val pk = currentPubkey ?: return
        try {
            SecureStorage.savePendingGroupInvitesFor(pk, _pendingGroupInvites.value.values.toList())
        } catch (_: Exception) {}
    }

    /** Restore pending invites for [pubKey], dropping any resolved while we were away. */
    fun loadPendingInvitesFromStorage(pubKey: String) {
        val stored = try {
            SecureStorage.getPendingGroupInvitesFor(pubKey)
        } catch (_: Exception) {
            emptyList()
        }
        if (stored.isEmpty()) return
        val valid = stored.filter {
            !isJoinedOn(it.relayUrl, it.groupId) &&
                leftAtOn(it.relayUrl, it.groupId) == null &&
                !isDropped(it.relayUrl, it.groupId)
        }
        if (valid.isEmpty()) return
        // Keep in-memory entries: a live registration this session is fresher than the slot.
        _pendingGroupInvites.update { current -> valid.associateBy { it.groupId } + current }
    }

    /**
     * Record a membership granted by an admin's kind:9000 (put-user) or inferred from a
     * kind:39002 listing as a pending invite. The durable left marker wins over 39002
     * listings: a relay that keeps listing us after our kind:9022 must not resurrect the
     * invite (B2). A put-user NEWER than our leave is different — it is a deliberate
     * re-add, and with adds consent-gated behind the prompt it re-opens the invite.
     */
    private fun registerExternalAdd(
        groupId: String,
        relayUrl: String?,
        actorPubkey: String? = null,
        eventId: String? = null,
        createdAtSeconds: Long = 0L,
    ) {
        // Our own put-user echoed back (some relays emit one for the group creator, and we
        // are the actor when we add ourselves) is not an external add.
        if (actorPubkey != null && actorPubkey == currentPubkey) return
        if (isRecentlyLeft(relayUrl, groupId) || isDropped(relayUrl, groupId)) return
        val relay = (relayUrl ?: getRelayForGroup(groupId) ?: currentRelayUrl ?: return).normalizeRelayUrl()
        // Per relay: the same id joined elsewhere is a different group. Matching across all
        // relays swallowed the invite, so a group we are a relay-side member of never got a
        // prompt, never entered the joined set, and never reached kind:10009 (no rail entry).
        if (isJoinedOn(relay, groupId)) return
        // A create/join we initiated is still in flight (id pre-claimed before the send):
        // the relay's put-user echo for it is our own doing, not an external add.
        if (isRecentlyJoined(groupId)) return
        val leftAt = leftAtOn(relay, groupId)
        if (leftAt != null) {
            // Only an actual put-user event dated after the leave re-opens; 39002 listings
            // carry no causality (the relay may simply have never processed our 9022).
            if (actorPubkey == null || createdAtSeconds <= leftAt) return
            clearLeft(relay, groupId)
            currentPubkey?.let { pk ->
                try {
                    SecureStorage.removeLeftGroupForRelay(pk, relay, groupId)
                } catch (_: Exception) {}
            }
        }
        // Scoped to this relay: an entry held for the same id on another relay is a different
        // group's invite and must be replaced by this one, not refreshed in place.
        val existing = _pendingGroupInvites.value[groupId]?.takeIf { it.relayUrl == relay }
        if (existing != null) {
            if (actorPubkey == null) return
            // The same put-user replayed by the inclusive since-cursor: nothing new.
            if (eventId != null && eventId == existing.eventId) return
            val isNewerAdd = createdAtSeconds > existing.createdAtSeconds
            // Two cases refresh the entry and re-notify: a 39002-inferred entry learning its
            // actor from the put-user event (the silent entry never announced anyone), and a
            // NEWER put-user for an invite we already hold — a remove-then-re-add whose
            // removal we may never have seen (the watch carries no kind:9001, and a removal's
            // 39002 stops matching #p=self). Anything older is a historical replay. A role
            // edit of a still-pending invitee is also a put-user and re-notifies too: it is
            // indistinguishable from a re-add in this stream, and a reminder while the user
            // hasn't decided is acceptable.
            if (existing.actorPubkey != null && !isNewerAdd) return
            val refreshed = existing.copy(
                actorPubkey = actorPubkey,
                eventId = eventId ?: existing.eventId,
                createdAtSeconds = createdAtSeconds.takeIf { it > 0L } ?: existing.createdAtSeconds,
            )
            _pendingGroupInvites.update { it + (groupId to refreshed) }
            persistPendingInvites()
            _externalAddPending.tryEmit(
                ExternalGroupAdd(groupId, relay, actorPubkey, refreshed.eventId, refreshed.createdAtSeconds),
            )
            return
        }
        val invite = PendingGroupInvite(
            groupId = groupId,
            relayUrl = relay,
            actorPubkey = actorPubkey,
            eventId = eventId,
            createdAtSeconds = createdAtSeconds.takeIf { it > 0L } ?: epochSeconds(),
        )
        _pendingGroupInvites.update { it + (groupId to invite) }
        persistPendingInvites()
        // Pre-add the relay may have CLOSED our reads "restricted"; we are a member now, so
        // the preview (opening the group from the notification / DM card) must get a fresh
        // read attempt while the decision is pending.
        clearGroupRestricted(relay, groupId)
        _externalAddPending.tryEmit(
            ExternalGroupAdd(groupId, relay, actorPubkey, eventId, invite.createdAtSeconds),
        )
        // 39002-inferred invites carry no actor. Fetch the put-user event itself (#p + #h
        // passes the h/e/a query rule that blocks the bare-#p watch on 0xchat) so the
        // invite can name who added us; its arrival upgrades the entry and notifies.
        if (actorPubkey == null) {
            val self = currentPubkey ?: return
            scope.launch {
                try {
                    connectionManager.getClientForRelay(relay)?.requestSelfPutUser(groupId, self)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
            }
        }
    }

    /** Accept a pending external add: adopt it into the joined set + kind:10009. */
    fun acceptPendingInvite(groupId: String) {
        val self = currentPubkey ?: return
        val invite = _pendingGroupInvites.value[groupId] ?: return
        // The entry is dropped by the adoption itself, once the membership is really in the
        // joined set. Discarding first spent the user's consent on a no-op whenever a guard
        // then blocked the adoption: the card was gone and the group never reached kind:10009.
        adoptExternalMembership(
            groupId = groupId,
            pubkey = self,
            relayUrl = invite.relayUrl,
            actorPubkey = invite.actorPubkey,
            eventId = invite.eventId,
            createdAtSeconds = invite.createdAtSeconds,
        )
    }

    /**
     * Put a group the relay already lists us in (kind:39002) into our own kind:10009, without
     * an invite card. The relay side and the list are separate states, and any missed prompt
     * (or one answered while a guard blocked the adoption) leaves a group we can read and post
     * in but that no list, and therefore no rail, knows about. This is the manual way in.
     */
    fun addRelaySideMembershipToList(groupId: String, relayUrl: String?) {
        val self = currentPubkey ?: return
        adoptExternalMembership(groupId = groupId, pubkey = self, relayUrl = relayUrl)
    }

    /** Drop a pending invite without adopting (decline path, or the add became moot). */
    fun discardPendingInvite(groupId: String) {
        if (groupId !in _pendingGroupInvites.value) return
        _pendingGroupInvites.update { it - groupId }
        persistPendingInvites()
    }

    /**
     * Adopt a membership the relay already grants us into the joined set: the
     * [acceptPendingInvite] and [addRelaySideMembershipToList] paths. Both are a deliberate
     * user action, so a durable left marker (B2: the relay keeps listing us after our
     * kind:9022) is CLEARED here rather than obeyed — obeying it turned an explicit rejoin
     * into a silent no-op. Unattended adoption still can't happen: nothing calls this
     * without the user asking for it.
     */
    private fun adoptExternalMembership(
        groupId: String,
        pubkey: String,
        relayUrl: String?,
        actorPubkey: String? = null,
        eventId: String? = null,
        createdAtSeconds: Long = 0L,
    ) {
        val relay = (relayUrl ?: getRelayForGroup(groupId) ?: currentRelayUrl ?: return).normalizeRelayUrl()
        // Already-joined check is per relay: group ids are relay-scoped, so a short one
        // ("nostrord") commonly exists on several. Matching across all of them silently
        // dropped the accept for every relay after the first.
        if (isJoinedOn(relay, groupId)) {
            discardPendingInvite(groupId)
            return
        }
        scope.launch {
            // Re-check inside the launch: two 39002s (or a 39002 + a live 9000) can both pass
            // the synchronous guard and only the first may adopt.
            if (isJoinedOn(relay, groupId)) {
                discardPendingInvite(groupId)
                return@launch
            }
            deletedGroupIds.remove(droppedKey(relay, groupId))
            persistDroppedGroups(pubkey)
            recentlyLeftAt.remove(recentLeaveKey(relay, groupId))
            clearLeft(relay, groupId)
            try {
                SecureStorage.removeLeftGroupForRelay(pubkey, relay, groupId)
            } catch (_: Exception) {}
            // Merge with the persisted slot like joinGroup: the in-memory map can be partial
            // early in a session and a memory-only write would clobber the slot.
            val stored = try {
                SecureStorage.getJoinedGroupsForRelay(pubkey, relay)
            } catch (_: Exception) {
                emptySet()
            }
            val updated = (_joinedGroupsByRelay.value[relay] ?: emptySet()) + stored + groupId
            try {
                SecureStorage.saveJoinedGroupsForRelay(pubkey, relay, updated)
            } catch (_: Exception) {}
            _joinedGroupsByRelay.update { it + (relay to updated) }
            // Consent is spent only now that the membership is really in the joined set.
            discardPendingInvite(groupId)
            // Grace against the orphan auto-forget while this group's kind:39000 is in flight.
            markRecentlyJoined(groupId)
            clearGroupRestricted(relay, groupId)
            _externalMembershipAdopted.tryEmit(
                ExternalGroupAdd(
                    groupId = groupId,
                    relayUrl = relay,
                    actorPubkey = actorPubkey,
                    eventId = eventId,
                    createdAtSeconds = createdAtSeconds.takeIf { it > 0L } ?: epochSeconds(),
                ),
            )
            try {
                refreshMuxSubscriptionsForRelay(relay)
            } catch (_: Exception) {}
        }
    }

    // Pre-approval CLOSED("restricted") drove the loader to Exhausted. Reset it (so
    // startInitialLoad is no longer a no-op) and re-run the initial history load through
    // the controller now that we have read access: refreshMux only re-subscribes the live
    // tail, so without this the group stays on "No messages yet" until someone posts. We go
    // through requestGroupMessages (the state-machine path), NOT a raw _messages fetch /
    // dedup eviction, which is what broke pagination before.
    private fun onApprovalDetected(relayUrl: String?, groupId: String) {
        clearPendingApproval(relayUrl ?: getRelayForGroup(groupId), groupId)
        clearGroupRestricted(relayUrl, groupId)
        scope.launch {
            try {
                loadingRegistry.getController(groupId).reset()
            } catch (_: Exception) {}
            val relayUrl = getRelayForGroup(groupId)
            if (relayUrl != null) {
                try {
                    // Pre-approval the relay CLOSED our batched mux subs with "restricted"; a relay
                    // CLOSE does not update muxTracker, so it still believes the live chat sub is
                    // active and a same-state refresh would be skipped (needsRefresh == false),
                    // leaving the tail dead until an app restart. Invalidate so the re-subscribe
                    // actually fires now that we have read access.
                    muxTracker.clearRelay(relayUrl)
                    refreshMuxSubscriptionsForRelay(relayUrl)
                } catch (_: Exception) {}
            }
            try {
                requestGroupMessages(groupId)
            } catch (_: Exception) {}
        }
    }

    /**
     * Request group members (kind 39002).
     * Marks the group as loading so the UI can show skeletons.
     * Auto-clears after [MEMBER_LOAD_TIMEOUT_MS] if no response arrives.
     */
    suspend fun requestGroupMembers(groupId: String): Boolean {
        if (!shouldRequest(groupId, "members")) return true // recently requested
        val currentClient = clientForGroup(groupId) ?: return false
        _loadingMembers.value = _loadingMembers.value + groupId
        currentClient.requestGroupMembers(groupId)
        // Safety timeout: clear loading flag if relay never responds.
        scope.launch {
            delay(MEMBER_LOAD_TIMEOUT_MS)
            _loadingMembers.value = _loadingMembers.value - groupId
        }
        return true
    }

    /**
     * Handle incoming group admins (kind 39001)
     */
    fun handleGroupAdmins(admins: GroupAdmins, createdAt: Long = 0L, relayUrl: String? = null) {
        if (isRecentlyLeft(relayUrl, admins.groupId)) return
        updateRelayScopedList(_groupAdminsByRelay, scopedAdminEventTimestamps, relayUrl, admins.groupId, createdAt, admins.admins)
        val existing = adminEventTimestamps[admins.groupId] ?: 0L
        if (createdAt > 0L && createdAt < existing) {
            connStats?.onStateConflict(admins.groupId)
            return
        }
        if (createdAt > 0L) adminEventTimestamps[admins.groupId] = createdAt
        // Skip StateFlow update if the admin list is identical.
        val currentAdmins = _groupAdmins.value[admins.groupId]
        if (currentAdmins != admins.admins) {
            _groupAdmins.value = _groupAdmins.value + (admins.groupId to admins.admins)
            persistGroupMembershipSnapshot()
            // An admin removal can flip an attestation-based `confirmed` relation back
            // to `unverified` (spec: evaluated against current kind:39001).
            recomputeSubgroupTopology()
        }
    }

    /**
     * Request group admins (kind 39001)
     */
    suspend fun requestGroupAdmins(groupId: String): Boolean {
        if (!shouldRequest(groupId, "admins")) return true // recently requested
        val currentClient = clientForGroup(groupId) ?: return false
        currentClient.requestGroupAdmins(groupId)
        return true
    }

    /**
     * Request pending join requests (kind 9021 + 9022) for a group.
     * Use when an admin opens a closed group — the standard chat REQ caps at 50
     * events and buries 9021s under recent chat. Debounced by [shouldRequest].
     */
    suspend fun requestPendingJoinRequests(groupId: String): Boolean {
        if (!shouldRequest(groupId, "joinreq")) return true
        val currentClient = clientForGroup(groupId) ?: return false
        currentClient.requestPendingJoinRequests(groupId)
        return true
    }

    /**
     * Handle incoming group roles (kind 39003)
     */
    fun handleGroupRoles(roles: GroupRoles, createdAt: Long = 0L, relayUrl: String? = null) {
        if (isRecentlyLeft(relayUrl, roles.groupId)) return
        updateRelayScopedList(_groupRolesByRelay, scopedRoleEventTimestamps, relayUrl, roles.groupId, createdAt, roles.roles)
        val existing = roleEventTimestamps[roles.groupId] ?: 0L
        if (createdAt > 0L && createdAt < existing) {
            connStats?.onStateConflict(roles.groupId)
            return
        }
        if (createdAt > 0L) roleEventTimestamps[roles.groupId] = createdAt
        val currentRoles = _groupRoles.value[roles.groupId]
        if (currentRoles != roles.roles) {
            _groupRoles.value = _groupRoles.value + (roles.groupId to roles.roles)
            persistGroupMembershipSnapshot()
        }
    }

    /**
     * Apply a kind 39004 (live AV participants) event.
     *
     * An empty participant list is meaningful — it is how the relay says the room emptied out —
     * so it is stored like any other update rather than skipped.
     */
    fun handleLiveKitParticipants(participants: LiveKitParticipants, createdAt: Long = 0L) {
        if (isRecentlyLeft(null, participants.groupId)) return
        val existing = liveKitEventTimestamps[participants.groupId] ?: 0L
        if (createdAt > 0L && createdAt < existing) return
        if (createdAt > 0L) liveKitEventTimestamps[participants.groupId] = createdAt
        if (_liveKitParticipants.value[participants.groupId] != participants.participants) {
            _liveKitParticipants.value = _liveKitParticipants.value + (participants.groupId to participants.participants)
        }
    }

    /**
     * Request live AV participants (kind 39004) for a group whose metadata carries `livekit`.
     */
    suspend fun requestLiveKitParticipants(groupId: String): Boolean {
        val currentClient = clientForGroup(groupId) ?: return false
        currentClient.requestLiveKitParticipants(groupId)
        return true
    }

    /**
     * Request group roles (kind 39003)
     */
    suspend fun requestGroupRoles(groupId: String): Boolean {
        if (!shouldRequest(groupId, "roles")) return true
        val currentClient = clientForGroup(groupId) ?: return false
        currentClient.requestGroupRoles(groupId)
        return true
    }

    /**
     * Check if a pubkey is an admin of a group
     */
    fun isGroupAdmin(groupId: String, pubkey: String): Boolean = pubkey in (_groupAdmins.value[groupId] ?: emptyList())

    /**
     * Get members for a specific group
     */
    fun getMembersForGroup(groupId: String): List<String> = _groupMembers.value[groupId] ?: emptyList()

    /**
     * Apply immediate member list changes from kind:9000 (add-user) and kind:9001 (remove-user)
     * admin events. This prevents ghost members and stale UI between the admin event and
     * the next kind:39002 full member list refresh.
     *
     * Only applies changes from events newer than the last authoritative kind:39002 member
     * list to avoid re-adding users that were removed (historical replays after AUTH).
     */
    private fun applyMemberChangeIfAdmin(
        message: NostrGroupClient.NostrMessage,
        groupId: String,
        relayUrl: String? = null,
    ) {
        val targetPubkey = message.tags.firstOrNull { it.firstOrNull() == "p" }?.getOrNull(1)
            ?: return
        // Advance the put-user watch cursor for EVERY kind:9000 targeting us, even when the
        // staleness guard below skips it — replays must stop arriving on the next REQ.
        if (message.kind == 9000 && targetPubkey == currentPubkey && relayUrl != null) {
            advancePutUserCursor(targetPubkey, relayUrl, message.createdAt)
        }
        // A put-user targeting US registers a pending invite BEFORE the staleness guard:
        // the 39002 that the add produced usually carries a created_at >= the 9000's (0xchat
        // even pins it at group creation), so gating on it would eat the actor enrichment
        // fetched after a 39002-inferred invite. registerExternalAdd has its own replay
        // guards (joined / left / already-pending).
        if (message.kind == 9000 && targetPubkey == currentPubkey) {
            registerExternalAdd(
                groupId,
                relayUrl,
                actorPubkey = message.pubkey,
                eventId = message.id,
                createdAtSeconds = message.createdAt,
            )
        }
        // A remove targeting US settles the pending invite — also before the staleness
        // guard, since the removal's own 39002 usually outdates the kind:9001. Only a
        // remove NEWER than the invite counts: a historical 9001 replay must not kill an
        // invite created by a later re-add.
        if (message.kind == 9001 && targetPubkey == currentPubkey) {
            val invite = _pendingGroupInvites.value[groupId]
            if (invite != null && message.createdAt > invite.createdAtSeconds) {
                discardPendingInvite(groupId)
            }
        }
        // Mirror the add/remove into the per-relay record under its own scoped guard,
        // before the flat guard below (which a same-id group elsewhere can inflate).
        if (relayUrl != null && (message.kind == 9000 || message.kind == 9001)) {
            val relay = relayUrl.normalizeRelayUrl()
            val scopedTs = scopedMemberEventTimestamps["$relay|$groupId"] ?: 0L
            if (message.createdAt > scopedTs) {
                _groupMembersByRelay.update { byRelay ->
                    val forRelay = byRelay[relay] ?: return@update byRelay
                    val members = forRelay[groupId] ?: return@update byRelay
                    val updated = when {
                        message.kind == 9000 && targetPubkey !in members -> members + targetPubkey
                        message.kind == 9001 && targetPubkey in members -> members - targetPubkey
                        else -> return@update byRelay
                    }
                    byRelay + (relay to (forRelay + (groupId to updated)))
                }
            }
        }

        // Skip historical events older than the authoritative member list.
        val memberListTimestamp = memberEventTimestamps[groupId] ?: 0L
        if (message.createdAt <= memberListTimestamp) return

        when (message.kind) {
            9000 -> { // add-user
                _groupMembers.update { current ->
                    val members = current[groupId] ?: return@update current
                    if (targetPubkey in members) {
                        current
                    } else {
                        current + (groupId to members + targetPubkey)
                    }
                }
            }
            9001 -> { // remove-user
                // Removed before we decided on a pending external add: the invite is moot.
                if (targetPubkey == currentPubkey) discardPendingInvite(groupId)
                _groupMembers.update { current ->
                    val members = current[groupId] ?: return@update current
                    if (targetPubkey !in members) {
                        current
                    } else {
                        current + (groupId to members - targetPubkey)
                    }
                }
            }
        }
    }

    // Valid message kinds for group events (NIP-29 and related)
    private val validMessageKinds = setOf(
        9, // Chat messages (NIP-29)
        9000, // Group admin: add user
        9001, // Group admin: remove user
        9002, // Group admin: edit metadata (NIP-29)
        9009, // Group admin: create invite (NIP-29)
        9021, // Join request
        9022, // Leave request
        9321, // Zap request (NIP-57)
    )

    // Deletion kinds that remove other events
    private val deletionKinds = setOf(
        5, // Deletion request (NIP-09)
        9003, // Group admin: delete event (NIP-29)
        9005, // Group admin: delete event (NIP-29 moderation)
    )

    // Forum threads: kind:11 root + NIP-22 kind:1111 replies. Deliberately NOT in
    // validMessageKinds — handleMessage routes them to the thread stores (applyThreadEvent)
    // before the chat-only bookkeeping, so they never touch _messages or the chat cursor.
    private val threadKinds = setOf(11, 1111)

    /**
     * Handle incoming message.
     * Returns the pubkey of the message sender if metadata should be fetched.
     * Also tracks message for pagination cursor calculation.
     */
    fun handleMessage(
        message: NostrGroupClient.NostrMessage,
        rawMsg: String,
        subscriptionId: String? = null,
        relayUrl: String? = null,
    ): String? {
        // Any event on a mux sub is proof the relay's live feed is alive.
        if (relayUrl != null && subscriptionId?.startsWith("mux_") == true) {
            noteMuxActivity(relayUrl)
        }

        // Handle deletion events separately — but still track for pagination cursor
        if (message.kind in deletionKinds) {
            if (subscriptionId != null) {
                trackMessageForSubscription(subscriptionId, message.createdAt, message.id)
            }
            handleDeletion(message, rawMsg)
            return null
        }

        // Forum threads (kind:11) and their NIP-22 replies (kind:1111) go to a separate store,
        // ahead of the chat-only bookkeeping below (live cursor, member changes, ordering
        // buffer). Returning the author pubkey still backfills their profile metadata.
        if (message.kind in threadKinds) {
            if (message.id.isBlank()) return null
            val groupId = extractGroupIdFromMessage(rawMsg) ?: return null
            if (isRecentlyLeft(relayUrl, groupId)) return null
            // Ahead of the dedup: a resubscribe replays the same roots, and those replays are
            // exactly what proves the relay still serves them. Deduping first would leave the
            // answer window looking empty and the EOSE would reconcile live threads away.
            noteThreadSubSeen(groupId, message.id)
            if (!eventDeduplicator.tryAddSync(message.id)) return null
            applyThreadEvent(groupId, message.withOriginRelay(relayUrl))
            return message.pubkey
        }

        // Only process valid message kinds
        if (message.kind !in validMessageKinds) {
            return null
        }

        val messageId = message.id
        if (messageId.isBlank()) return null

        // Track before dedup: events overlapping with mux_chat's `since` window
        // arrive on both subs and would otherwise be deducted from the msg_ sub's
        // page count, flipping a full page to Exhausted.
        if (subscriptionId != null) {
            trackMessageForSubscription(subscriptionId, message.createdAt, message.id)
        }

        if (!eventDeduplicator.tryAddSync(messageId)) return null

        val groupId = extractGroupIdFromMessage(rawMsg) ?: return null

        if (isRecentlyLeft(relayUrl, groupId)) return null

        if (relayUrl != null) {
            _latestMessageRelayByGroup.update { it + (groupId to relayUrl) }
        }

        // Populate global emoji cache from message emoji tags and backfill
        // any existing reactions that are missing their image URL.
        var newEmojis = false
        for (tag in message.tags) {
            if (tag.size >= 3 && tag[0] == "emoji" && !emojiUrlCache.containsKey(tag[1])) {
                emojiUrlCache.put(tag[1], tag[2])
                newEmojis = true
            }
        }
        if (newEmojis) backfillReactionEmojiUrls()

        // Update live cursor so reconnects resume from the right timestamp.
        // Fire-and-forget: cursor updates are non-critical and should not block message processing.
        if (relayUrl != null && message.createdAt > 0L) {
            scope.launch { liveCursorStore.update(relayUrl, groupId, message.createdAt) }
        }

        // Inline member list updates from admin events — provides immediate UI feedback
        // without waiting for a new kind:39002 event from the relay.
        applyMemberChangeIfAdmin(message, groupId, relayUrl)

        // Enqueue to the ordering buffer; the buffer flushes after 300 ms of inactivity
        // for this group, applying the entire batch in one sorted StateFlow update.
        eventOrderingBuffer.enqueue(groupId, message.withOriginRelay(relayUrl))

        return message.pubkey
    }

    /**
     * Stamp the delivering relay onto a message (normalized), so readers can scope the
     * shared bare-id buckets: the same id on two relays is two independent groups.
     * An already-stamped or relay-less message passes through unchanged.
     */
    private fun NostrGroupClient.NostrMessage.withOriginRelay(relayUrl: String?): NostrGroupClient.NostrMessage = if (relayUrl == null || this.relayUrl != null) this else copy(relayUrl = relayUrl.normalizeRelayUrl())

    /**
     * Disk-cache slot for a group's history on one relay. Relay-scoped so same-id groups
     * on two relays never mix on disk; '|' cannot appear in a relay URL or group id.
     * A null relay falls back to the legacy bare-id slot.
     */
    private fun cacheSlot(
        relayUrl: String?,
        groupId: String,
    ): String = relayUrl?.let { "${it.normalizeRelayUrl()}|$groupId" } ?: groupId

    /**
     * Apply a pre-sorted batch of messages for [groupId] to [_messages] in one atomic update.
     * Called by [EventOrderingBuffer] after its debounce window expires.
     */
    private fun flushBatchToState(groupId: String, messages: List<NostrGroupClient.NostrMessage>) {
        // Guard: currentPubkey is nulled in clear() before a switch. Any flush
        // that arrives after clear() but before setCurrentPubkey() is discarded
        // here to prevent cross-account message contamination.
        if (currentPubkey == null) return

        // A relay echoing back one of our own optimistic messages proves it was accepted
        // even when the OK was lost: resolve the on-screen status and drop the queued
        // retry before it double-sends.
        val ownStatuses = _messageStatus.value
        if (ownStatuses.isNotEmpty()) {
            for (msg in messages) {
                // Skip already-Delivered entries so relay re-deliveries of the same
                // event don't re-fire the resolution work on every batch.
                val status = ownStatuses[msg.id]
                if (status != null && status !is MessageStatus.Delivered) {
                    markDelivered(msg.id)
                    pendingEventManager?.let { pending -> scope.launch { pending.removeByEventId(msg.id) } }
                }
            }
        }

        // Record burst for adaptive tuning
        adaptiveConfig?.recordEventBurst(messages.size)

        var capturedNew: List<NostrGroupClient.NostrMessage>? = null
        _messages.update { currentMap ->
            val current = currentMap[groupId] ?: emptyList()
            // Persistent index: O(1) per lookup, no rebuild.
            val index = messageIdIndex.getOrPut(groupId) {
                current.mapTo(mutableSetOf()) { it.id }
            }
            // index.add() returns true if new → filters and indexes in one pass.
            val newMessages = messages.filter { index.add(it.id) }
            if (newMessages.isEmpty()) return@update currentMap
            capturedNew = newMessages

            // Sort optimization: if all new messages are newer than the last existing
            // message, just append the sorted batch (the common live-message case).
            // Avoids re-sorting the full list on every flush.
            val lastExistingTs = current.lastOrNull()?.createdAt ?: 0L
            val allNewer = newMessages.all { it.createdAt >= lastExistingTs }
            val merged = if (allNewer) {
                current + newMessages.sortedBy { it.createdAt }
            } else {
                (current + newMessages).sortedBy { it.createdAt }
            }
            currentMap + (groupId to merged)
        }

        capturedNew?.let { onNewMessagesFlushed?.invoke(groupId, it) }
        capturedNew?.let { cacheMessages(groupId, it) }
        touchGroupRecency(groupId)
        evictGroupMessagesBeyondCap()
    }

    /** Mark [groupId] most-recently-used for whole-group eviction ordering. */
    private fun touchGroupRecency(groupId: String) {
        groupMessageRecency.remove(groupId)
        groupMessageRecency.add(groupId)
    }

    private val tagsSerializer = ListSerializer(ListSerializer(String.serializer()))

    private fun NostrGroupClient.NostrMessage.toCachedMsg(groupId: String): CachedMsg = CachedMsg(
        id = id,
        groupId = groupId,
        pubkey = pubkey,
        createdAt = createdAt,
        kind = kind,
        content = content,
        tagsJson = json.encodeToString(tagsSerializer, tags),
    )

    private fun CachedMsg.toNostrMessage(): NostrGroupClient.NostrMessage = NostrGroupClient.NostrMessage(
        id = id,
        pubkey = pubkey,
        content = content,
        createdAt = createdAt,
        kind = kind,
        tags = try {
            json.decodeFromString(tagsSerializer, tagsJson)
        } catch (_: Exception) {
            emptyList()
        },
    )

    /** Write new messages through to the persistent history cache (fire-and-forget). */
    private fun cacheMessages(
        groupId: String,
        messages: List<NostrGroupClient.NostrMessage>,
    ) {
        val account = currentPubkey ?: return
        if (messages.isEmpty()) return
        scope.launch {
            try {
                // One slot per origin relay: same-id groups on two relays keep separate
                // disk histories (a mux flush batch can mix relays for one bare id).
                messages.groupBy { it.relayUrl }.forEach { (relay, batch) ->
                    val slot = cacheSlot(relay, groupId)
                    cacheStore.upsertMessages(account, slot, batch.map { it.toCachedMsg(slot) })
                }
            } catch (_: Exception) {
            }
        }
        scheduleCacheEviction()
    }

    /**
     * One-time seeding of the persistent cache from the legacy per-group message blobs
     * (the last-100 snapshot in [SecureStorage]). Without this, a previously-seen group would
     * hydrate empty right after upgrade until the write-through repopulates it; with it, the
     * already-saved history shows on the first open. Idempotent (guarded by a per-account flag)
     * and scoped to the joined groups (already restored at cold start), since the KV blobs
     * aren't enumerable.
     */
    fun migrateMessageBlobsToCache(pubkey: String) {
        if (pubkey.isBlank()) return
        scope.launch {
            if (SecureStorage.isMessageBlobMigratedFor(pubkey)) return@launch
            val groupIds = _joinedGroupsByRelay.value.values.flatten().toSet()
            if (groupIds.isEmpty()) return@launch
            for (groupId in groupIds) {
                val blob = SecureStorage.getMessagesForGroup(pubkey, groupId) ?: continue
                val messages = parseMessagesBlob(blob)
                if (messages.isEmpty()) continue
                try {
                    cacheStore.upsertMessages(pubkey, groupId, messages.map { it.toCachedMsg(groupId) })
                } catch (_: Exception) {
                }
            }
            SecureStorage.setMessageBlobMigratedFor(pubkey)
        }
    }

    private var cacheEvictionJob: Job? = null

    /** Trim the persistent cache to [CACHE_BYTE_BUDGET], debounced so a write burst evicts once. */
    private fun scheduleCacheEviction() {
        val account = currentPubkey ?: return
        cacheEvictionJob?.cancel()
        cacheEvictionJob =
            scope.launch {
                delay(CACHE_EVICTION_DEBOUNCE_MS)
                try {
                    cacheStore.evictToByteBudget(account, CACHE_BYTE_BUDGET)
                } catch (_: Exception) {
                }
            }
    }

    /**
     * Render a previously-seen group instantly from the persistent cache on open, before any
     * relay round-trip. Merges the cached page into [_messages] via the same dedup index as the
     * live path, so the subsequent network refresh (stale-while-revalidate) never double-inserts.
     */
    private suspend fun hydrateMessagesFromCache(groupId: String) {
        val account = currentPubkey ?: return
        // The group's relay picks the slot; restored messages are stamped with it so the
        // relay-scoped screens accept them. Pre-slot legacy rows (bare id) are skipped:
        // they can't be attributed to a relay, and the live REQ re-fills and re-caches.
        val relay = getRelayForGroup(groupId) ?: return
        val cached =
            try {
                cacheStore.loadLatest(account, cacheSlot(relay, groupId), CACHE_HYDRATE_LIMIT)
            } catch (_: Exception) {
                return
            }
        if (cached.isEmpty()) return
        val restored = cached.map { it.toNostrMessage().withOriginRelay(relay) }
        _messages.update { current ->
            val existing = current[groupId] ?: emptyList()
            val index = messageIdIndex.getOrPut(groupId) { existing.mapTo(mutableSetOf()) { it.id } }
            val fresh = restored.filter { index.add(it.id) }
            if (fresh.isEmpty()) return@update current
            current + (groupId to (existing + fresh).sortedBy { it.createdAt })
        }
        touchGroupRecency(groupId)
    }

    /**
     * Evict the least-recently-used groups' in-memory messages once more than
     * [MAX_GROUPS_IN_MEMORY] groups are loaded, never the active one. Each evicted group is
     * persisted first so reopening hydrates instantly from disk, then its list, dedup index
     * and reactions are dropped together (keeping list and index consistent — a partial trim
     * would not). The live subscription refills the tail on reopen.
     */
    private fun evictGroupMessagesBeyondCap() {
        val present = _messages.value
        val overflow = present.size - MAX_GROUPS_IN_MEMORY
        if (overflow <= 0) return
        val active = _activeGroupId
        val toEvict =
            groupMessageRecency
                .asSequence()
                .filter { it != active && present.containsKey(it) }
                .take(overflow)
                .toSet()
        if (toEvict.isEmpty()) return
        toEvict.forEach { saveMessagesToStorage(it) }
        _messages.update { it - toEvict }
        toEvict.forEach { groupId ->
            val droppedIds = messageIdIndex.remove(groupId)
            if (!droppedIds.isNullOrEmpty()) {
                _reactions.update { it - droppedIds }
            }
            groupMessageRecency.remove(groupId)
        }
    }

    /**
     * Handle deletion events (kind 5 and 9005).
     * Removes the referenced events from the message list.
     */
    private fun handleDeletion(deletion: NostrGroupClient.NostrMessage, rawMsg: String) {
        val groupId = extractGroupIdFromMessage(rawMsg) ?: return

        // Extract event IDs to delete from "e" tags
        val eventIdsToDelete = deletion.tags
            .filter { it.size >= 2 && it[0] == "e" }
            .map { it[1] }
            .toSet()

        if (eventIdsToDelete.isEmpty()) return

        // Remove deleted messages from the list
        _messages.update { currentMap ->
            val currentMessages = currentMap[groupId] ?: return@update currentMap
            val filteredMessages = currentMessages.filterNot { it.id in eventIdsToDelete }
            if (filteredMessages.size == currentMessages.size) {
                currentMap // No changes
            } else {
                currentMap + (groupId to filteredMessages)
            }
        }

        // Threads live in their own stores; remove deleted roots/replies there too so a deleted
        // thread disappears from the forum list (its own author's delete and admin deletes both
        // arrive here).
        _threadRoots.update { current ->
            val list = current[groupId] ?: return@update current
            val filtered = list.filterNot { it.id in eventIdsToDelete }
            if (filtered.size == list.size) current else current + (groupId to filtered)
        }
        _threadReplies.update { current ->
            val list = current[groupId] ?: return@update current
            val filtered = list.filterNot { it.id in eventIdsToDelete }
            if (filtered.size == list.size) current else current + (groupId to filtered)
        }

        // Also remove deleted reactions — flush pending first so deletions apply to full state.
        flushPendingReactions()
        _reactions.update { currentReactions ->
            var updated = currentReactions
            eventIdsToDelete.forEach { eventId ->
                if (eventId in updated) {
                    updated = updated - eventId
                }
            }
            updated
        }

        // Evict from the persistent cache too. Without this, a deleted event (e.g. a revoked
        // kind:9009 invite code) rehydrates from cache on the next cold open — the relay no longer
        // serves it and the 9005 delete is never cached, so nothing re-removes it. Fire-and-forget.
        val account = currentPubkey
        if (account != null) {
            scope.launch {
                try {
                    cacheStore.deleteByIds(account, eventIdsToDelete)
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Get messages for a specific group
     */
    fun getMessagesForGroup(groupId: String): List<NostrGroupClient.NostrMessage> = _messages.value[groupId] ?: emptyList()

    /**
     * The thread root to open for [eventId] when it is a forum event: the root itself for a
     * kind:11, its uppercase-E scope for a kind:1111. Null for chat events, which open the chat.
     * Lets a notification about a thread event deep-link into the threads pane.
     */
    fun threadRootForEvent(eventId: String): String? {
        _threadRoots.value.values.forEach { roots ->
            if (roots.any { it.id == eventId }) return eventId
        }
        _threadReplies.value.values.forEach { replies ->
            val reply = replies.firstOrNull { it.id == eventId }
            if (reply != null) return reply.tags.firstOrNull { it.size >= 2 && it[0] == "E" }?.get(1)
        }
        return null
    }

    fun findMessageByIdAcrossGroups(messageId: String): Pair<String, NostrGroupClient.NostrMessage>? {
        for ((groupId, msgs) in _messages.value) {
            val msg = msgs.firstOrNull { it.id == messageId } ?: continue
            return groupId to msg
        }
        // Thread roots and replies live outside _messages; without them a reaction to a thread
        // event whose sender omitted the NIP-25 `p` tag resolves to no author and never counts
        // as an interaction with us.
        for (store in listOf(_threadRoots.value, _threadReplies.value)) {
            for ((groupId, events) in store) {
                val event = events.firstOrNull { it.id == messageId } ?: continue
                return groupId to event
            }
        }
        return null
    }

    /**
     * Returns the [NostrGroupClient.NostrMessage.createdAt] of the newest message in the
     * in-memory list for [groupId], or null if the group has no messages loaded yet.
     * Used by gap detection to check whether events near the cursor arrived after reconnect.
     */
    fun getLatestMessageTimestamp(groupId: String): Long? = _messages.value[groupId]?.maxOfOrNull { it.createdAt }

    /**
     * Handle incoming reaction (kind 7).
     *
     * @param immediate When true, the StateFlow emits synchronously (used for
     *                  optimistic UI updates from the user's own reaction).
     *                  When false (default), the emission is debounced to coalesce
     *                  burst loads from relays into a single UI update.
     * @return the pubkey of the reactor if metadata should be fetched
     */
    fun handleReaction(
        reaction: NostrGroupClient.NostrReaction,
        immediate: Boolean = false,
        // Origin relay, for the disk cache slot. Null skips persistence (optimistic local
        // reactions, which the relay echo re-caches with its relay attributed).
        relayUrl: String? = null,
    ): String? {
        val messageId = reaction.targetEventId
        if (messageId.isBlank()) return null

        // Deduplicate reactions by id
        if (!eventDeduplicator.tryAddSync(reaction.id)) {
            return null
        }

        if (relayUrl != null) cacheThreadReaction(reaction, relayUrl)

        // Check pending (staged) reactions first, then committed state.
        val currentReactions = _pendingReactions[messageId]
            ?: _reactions.value[messageId]
            ?: emptyMap()
        val emoji = reaction.emoji
        val reactorPubkey = reaction.pubkey

        // Get current reaction info for this emoji
        val currentInfo = currentReactions[emoji]
        val currentReactors = currentInfo?.reactors ?: emptyList()

        if (reactorPubkey in currentReactors) return null // Already reacted with this emoji

        val shortcode = emoji.trim(':')
        val resolvedUrl = reaction.emojiUrl
            ?: currentInfo?.emojiUrl
            ?: emojiUrlCache.get(shortcode)
            ?: findEmojiUrlInMessages(shortcode)

        val isNewCacheEntry = resolvedUrl != null && !emojiUrlCache.containsKey(shortcode)
        if (resolvedUrl != null) {
            emojiUrlCache.put(shortcode, resolvedUrl)
        }

        val updatedInfo = ReactionInfo(
            emojiUrl = resolvedUrl,
            reactors = currentReactors + reactorPubkey,
        )
        val updatedEmojiMap = currentReactions + (emoji to updatedInfo)

        if (immediate) {
            // Optimistic update from the user — flush any pending batch first,
            // then apply this reaction and emit to UI synchronously.
            reactionFlushJob?.cancel()
            reactionFlushJob = null
            flushPendingReactions()
            _reactions.value = _reactions.value + (messageId to updatedEmojiMap)
        } else {
            // Relay burst — stage the change and schedule a debounced flush.
            // Subsequent handleReaction calls within the window read from both
            // _reactions.value and _pendingReactions so they see the full state.
            _pendingReactions[messageId] = updatedEmojiMap
            reactionFlushJob?.cancel()
            reactionFlushJob = scope.launch {
                delay(REACTION_DEBOUNCE_MS)
                flushPendingReactions()
            }
        }

        if (isNewCacheEntry) backfillReactionEmojiUrls()

        return reactorPubkey
    }

    private fun findEmojiUrlInMessages(shortcode: String): String? {
        for ((_, messages) in _messages.value) {
            for (msg in messages) {
                for (tag in msg.tags) {
                    if (tag.size >= 3 && tag[0] == "emoji" && tag[1] == shortcode) {
                        emojiUrlCache.put(shortcode, tag[2])
                        return tag[2]
                    }
                }
            }
        }
        return null
    }

    private fun backfillReactionEmojiUrls() {
        flushPendingReactions()
        val current = _reactions.value
        var changed = false
        val updated = current.mapValues { (_, emojiMap) ->
            emojiMap.mapValues { (emoji, info) ->
                if (info.emojiUrl == null) {
                    val cached = emojiUrlCache.get(emoji.trim(':'))
                    if (cached != null) {
                        changed = true
                        info.copy(emojiUrl = cached)
                    } else {
                        info
                    }
                } else {
                    info
                }
            }
        }
        if (changed) _reactions.value = updated
    }

    /**
     * Remove a reaction from local state (used for rollback on relay rejection).
     */
    private fun removeReaction(messageId: String, emoji: String, pubkey: String) {
        // Flush any pending reactions so we operate on the full state.
        flushPendingReactions()
        val currentReactions = _reactions.value[messageId] ?: return
        val info = currentReactions[emoji] ?: return
        val updatedReactors = info.reactors.filter { it != pubkey }
        val updatedEmojiMap = if (updatedReactors.isEmpty()) {
            currentReactions - emoji
        } else {
            currentReactions + (emoji to info.copy(reactors = updatedReactors))
        }
        _reactions.value = if (updatedEmojiMap.isEmpty()) {
            _reactions.value - messageId
        } else {
            _reactions.value + (messageId to updatedEmojiMap)
        }
    }

    /**
     * Extract group ID from a raw message
     */
    private fun extractGroupIdFromMessage(msg: String): String? {
        return try {
            val arr = json.parseToJsonElement(msg).jsonArray
            if (arr.size < 3) return null
            val event = arr[2].jsonObject
            val tags = event["tags"]?.jsonArray ?: return null

            tags.firstOrNull { tag ->
                val tagArray = tag.jsonArray
                tagArray.size >= 2 && tagArray[0].jsonPrimitive.content == "h"
            }?.jsonArray?.get(1)?.jsonPrimitive?.content
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Clear session state only — messages, joined groups, loading state, reactions.
     * Does NOT clear the per-relay group metadata cache so switching back to a relay
     * that was previously visited shows groups immediately without a network re-fetch.
     *
     * Use this on relay switch. Use [clear] only on logout.
     */
    suspend fun clearForRelaySwitch() {
        currentRelayUrl = null
        _groups.value = emptyList()
        observedGroups.clear()

        // Invalidate group-list cache so the next relay fetches all kind:39000
        // instead of relying on stale data from a previous visit.
        _completeGroupLoadRelays.value = emptySet()

        // Without clearing this, an in-flight full fetch on the previous relay
        // leaves that relay flagged "pending"; if its late EOSE then arrives
        // the relay would be marked complete with an empty seen-set, pruning
        // its OTHER GROUPS to just the joined entries. Cleared here (alongside
        // [clear] which covers logout) so every relay switch resets the bookkeeping.
        pendingFullFetchRelays.clear()
        pendingFetchSeenGroups.clear()
        muxMetaExpectedIds.clear()
        muxMetaSeenIds.clear()

        // MUST be synchronous (not scope.launch). If this is async, requestGroupMessages
        // called immediately after switchRelay sees stale HasMore/Exhausted state from the
        // previous relay and startInitialLoad() returns null — group silently never loads.
        loadingRegistry.clear()
        eventDeduplicator.clear()
        messageIdIndex.clear()
    }

    /**
     * Remove a single relay entry from the in-memory cache so the rail updates immediately.
     */
    fun removeRelayEntry(url: String) {
        val normalized = url.normalizeRelayUrl()
        _completeGroupLoadRelays.update { it - normalized }
        _fullGroupListFetchedRelays.update { it - normalized }
        _groupsByRelay.update { current -> current - normalized }
        _joinedGroupsByRelay.update { current -> current - normalized }
    }

    /**
     * Clear all state including the per-relay group metadata cache.
     * Use on logout or full account reset.
     */
    suspend fun clear() {
        // Null out currentPubkey BEFORE flushing so any in-flight flush that
        // fires during or after the clear is discarded by flushBatchToState's
        // null guard. This prevents stale messages from a previous account
        // writing into _messages after a switch.
        currentPubkey = null
        eventOrderingBuffer.flushAll()
        _completeGroupLoadRelays.value = emptySet()
        _fullGroupListFetchedRelays.value = emptySet()
        _loadingRelays.value = emptySet()
        // Snapshot under the lock before cancelling: a watchdog already past its delay removes
        // itself from its finally, and iterating the live map while it does throws.
        val watchdogs =
            synchronized(loadingWatchdogsLock) {
                val snapshot = loadingWatchdogs.values.toList()
                loadingWatchdogs.clear()
                snapshot
            }
        watchdogs.forEach { it.cancel() }
        // Without clearing this, an in-flight full fetch from the previous account
        // leaves the relay flagged as "pending", and the new account's expand of
        // OTHER GROUPS is silently skipped by requestFullGroupListForRelay's guard.
        pendingFullFetchRelays.clear()
        pendingFetchSeenGroups.clear()
        muxMetaExpectedIds.clear()
        muxMetaSeenIds.clear()
        _groupsByRelay.value = emptyMap()
        _openedGroupIds.value = emptySet()
        // Messages from the previous account must NOT survive into the new
        // session. flushBatchToState rebuilds messageIdIndex from _messages,
        // so leaving an event id behind makes the next account's catch-up
        // REQ silently drop it — newMessages stays empty, onNewMessagesFlushed
        // never fires, and UnreadManager never sees the event. Cleared here
        // (not in clearForRelaySwitch) because relay switch must preserve the
        // user's message history for the active account.
        _messages.value = emptyMap()
        _threadRoots.value = emptyMap()
        _threadReplies.value = emptyMap()
        hydratedThreadGroups.clear()
        _threadsLoaded.value = emptySet()
        openThreadGroups.clear()
        groupMessageRecency.clear()
        _messageStatus.value = emptyMap()
        _latestMessageRelayByGroup.value = emptyMap()
        groupRelayHints.value = emptyMap()
        // Joined-group sets and restricted-group markers are pubkey-scoped.
        // Leaving them in place lets isJoined() / isRestricted() report the
        // PREVIOUS account's data while the new account's flow is still
        // settling. Concretely: a kind:9 buffered in the ordering buffer (or
        // arriving on a still-open socket) flushes a few hundred ms after the
        // switch, UnreadManager.isJoined() returns true on the stale set, and
        // the notification lands in the new account's history.
        _joinedGroupsByRelay.value = emptyMap()
        _restrictedGroups.value = emptyMap()
        _leftGroups.value = emptyMap()
        // Pubkey-scoped like the joined set; the next account reloads its own from storage.
        _pendingGroupInvites.value = emptyMap()
        // Per-group state from the previous account must be cleared too.
        // Without this, an account switch leaves stale kind:39002/39001/39003
        // caches that don't include the new account's pubkey, so opening a
        // closed group falls straight into the "Awaiting admin approval" branch
        // in GroupScreen. memberEventTimestamps must also be reset, otherwise a
        // fresh 39002 with the same createdAt is treated as stale and the cache
        // never updates.
        _groupMembers.value = emptyMap()
        _groupAdmins.value = emptyMap()
        _groupRoles.value = emptyMap()
        _loadingMembers.value = emptySet()
        memberEventTimestamps.clear()
        adminEventTimestamps.clear()
        roleEventTimestamps.clear()
        _groupMembersByRelay.value = emptyMap()
        _groupAdminsByRelay.value = emptyMap()
        _groupRolesByRelay.value = emptyMap()
        scopedMemberEventTimestamps.clear()
        scopedAdminEventTimestamps.clear()
        scopedRoleEventTimestamps.clear()
        _pendingApprovalSince.value = emptyMap()
        recentlyLeftAt.clear()
        // Pubkey-scoped: the next account reloads its own set from storage in loadJoinedGroupsFromStorage.
        deletedGroupIds.clear()
        // The mux tracker remembers what was last sent per relay. Without
        // clearing it on identity swap, refreshMuxSubscriptionsForRelay can
        // see "no change" and skip the REQ. Private-group 39002 then never
        // arrives on the new identity's session and the chat is stuck on
        // "Awaiting admin approval" until the user restarts the app.
        muxTracker.clearAll()
        // Same idea for the 2s request cooldown: a recent REQ from the
        // previous account would otherwise block an equivalent REQ from
        // the new account during the swap window.
        recentRequests.clear()
        // Incrementally-mutated per-group caches and topology maps: only ever updated via
        // update{}/recompute, so they were never part of the teardown and survived a swap
        // (B briefly reading A's reaction/loading/subgroup state, plus leaked debounce jobs
        // on the singleton scope). Reset them here so clear() is a true whole-account reset.
        _reactions.value = emptyMap()
        _pendingReactions.clear()
        reactionFlushJob?.cancel()
        reactionFlushJob = null
        _groupStates.value = emptyMap()
        _isLoadingMore.value = emptyMap()
        _hasMoreMessages.value = emptyMap()
        _groupsAwaitingAuthRead.value = emptySet()
        recentlyJoinedAt.clear()
        privateGroupFetchAt.clear()
        pendingTrackCounts.value = emptyMap()
        muxRefreshJobs.values.forEach { it.cancel() }
        muxRefreshJobs.clear()
        // Subgroup topology: recomputeSubgroupTopology reassigns on B's first ingest, but
        // until then B would render A's parent->children grouping. Reset so it starts empty.
        _childrenByParent.value = emptyMap()
        _activeGroupId = null
        // Reset every per-group loading controller. Without this, controllers
        // left mid-load (InitialLoading / Paginating / HasMore) at logout
        // refuse startInitialLoad() on re-login — it only accepts Idle/Error
        // — so requestGroupMessages returns false silently and the chat
        // stays on "No messages yet" until the process restarts. Affects
        // bunker logins disproportionately because their AUTH path is slow
        // enough to leave more groups mid-load when the logout cancellation
        // fires.
        loadingRegistry.clear()
        clearForRelaySwitch()
    }

    /**
     * Clear joined groups for an account
     */
    fun clearJoinedGroupsForAccount(pubKey: String) {
        SecureStorage.clearAllJoinedGroupsForAccount(pubKey)
        SecureStorage.saveDroppedGroupIds(pubKey, emptySet())
        SecureStorage.clearAllJoinedGroupMetadataForAccount(pubKey)
        SecureStorage.clearGroupMembershipFor(pubKey)
        // Wipe the persistent history cache and let a re-added account re-seed from its blobs.
        SecureStorage.clearMessageBlobMigratedFor(pubKey)
        scope.launch {
            try {
                cacheStore.clearAccount(pubKey)
            } catch (_: Exception) {
            }
        }
        _joinedGroupsByRelay.value = emptyMap()
        currentPubkey = null
    }

    // ==========================================================================
    // MESSAGE PERSISTENCE
    // ==========================================================================

    /**
     * Load persisted messages for a group from storage.
     * Called when entering a group to show cached messages while fetching new ones.
     */
    /** Parse a persisted message blob (JSON array) into messages; empty on any parse error. */
    private fun parseMessagesBlob(messagesJson: String): List<NostrGroupClient.NostrMessage> = try {
        json.parseToJsonElement(messagesJson).jsonArray.mapNotNull { element ->
            try {
                val obj = element.jsonObject
                NostrGroupClient.NostrMessage(
                    id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    pubkey = obj["pubkey"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    content = obj["content"]?.jsonPrimitive?.content ?: "",
                    createdAt = obj["createdAt"]?.jsonPrimitive?.long ?: return@mapNotNull null,
                    kind = obj["kind"]?.jsonPrimitive?.int ?: 9,
                    tags = obj["tags"]?.jsonArray?.map { tagArray ->
                        tagArray.jsonArray.map { it.jsonPrimitive.content }
                    } ?: emptyList(),
                )
            } catch (e: Throwable) {
                null
            }
        }
    } catch (e: Throwable) {
        emptyList()
    }

    fun loadMessagesFromStorage(groupId: String) {
        val pubkey = currentPubkey ?: return
        val messagesJson = SecureStorage.getMessagesForGroup(pubkey, groupId) ?: return

        try {
            val messages = parseMessagesBlob(messagesJson)

            if (messages.isNotEmpty()) {
                // Add to deduplicator and persistent message index
                messages.forEach { msg -> eventDeduplicator.tryAddSync(msg.id) }
                val index = messageIdIndex.getOrPut(groupId) { mutableSetOf() }
                _messages.update { current ->
                    val existing = current[groupId] ?: emptyList()
                    // Seed index from existing + new, dedup via index
                    existing.forEach { index.add(it.id) }
                    val newMsgs = messages.filter { index.add(it.id) }
                    if (newMsgs.isEmpty() && existing.isNotEmpty()) return@update current
                    val merged = (existing + newMsgs).sortedBy { it.createdAt }
                    current + (groupId to merged)
                }
                touchGroupRecency(groupId)
            }
        } catch (e: Throwable) {
            // Ignore parsing errors - storage may be corrupted
        }
    }

    /**
     * Persist messages for a group to storage.
     * Called periodically and when leaving a group.
     */
    fun saveMessagesToStorage(groupId: String) {
        val pubkey = currentPubkey ?: return
        val messages = _messages.value[groupId] ?: return
        if (messages.isEmpty()) return

        try {
            // Keep only the most recent messages
            val toSave = messages.takeLast(MAX_PERSISTED_MESSAGES)
            val messagesJson = buildJsonArray {
                toSave.forEach { msg ->
                    add(
                        buildJsonObject {
                            put("id", msg.id)
                            put("pubkey", msg.pubkey)
                            put("content", msg.content)
                            put("createdAt", msg.createdAt)
                            put("kind", msg.kind)
                            put(
                                "tags",
                                buildJsonArray {
                                    msg.tags.forEach { tag ->
                                        add(
                                            buildJsonArray {
                                                tag.forEach { add(it) }
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            }.toString()

            SecureStorage.saveMessagesForGroup(pubkey, groupId, messagesJson)
        } catch (e: Throwable) {
            // Ignore save errors
        }
    }

    /**
     * Save all messages for all groups.
     * Called on app backgrounding/closing.
     */
    fun saveAllMessagesToStorage() {
        _messages.value.keys.forEach { groupId ->
            saveMessagesToStorage(groupId)
        }
    }

    /**
     * Clear persisted messages for a group.
     */
    fun clearMessagesFromStorage(groupId: String) {
        val pubkey = currentPubkey ?: return
        SecureStorage.clearMessagesForGroup(pubkey, groupId)
    }
}

/**
 * Which of [stored] a limit-capped REQ answer ([seen]) proves are gone, i.e. deleted on another
 * device: this one never receives that kind:5/9005, so absence from the answer is the only signal.
 *
 * Only the answer's own window counts. The REQ is limit-capped, so events older than the oldest
 * one served were never asked for and stay - otherwise a group with more threads than the page
 * size would lose its history on every EOSE. An empty answer is authoritative (callers reconcile
 * only a settled, authenticated sub), so it clears the whole window.
 *
 * [stored] must already be narrowed to one kind on the answering relay; un-echoed optimistic
 * sends (no relay) never reach here.
 */
internal fun staleThreadEventIds(
    stored: List<NostrGroupClient.NostrMessage>,
    seen: Set<String>,
): Set<String> {
    if (stored.isEmpty()) return emptySet()
    val windowStart = stored.filter { it.id in seen }.minOfOrNull { it.createdAt } ?: Long.MIN_VALUE
    return stored.filter { it.id !in seen && it.createdAt >= windowStart }.map { it.id }.toSet()
}

/**
 * Tags a cached thread reaction keeps. Only what [reactionFromCacheRow] needs to rebuild it:
 * the target (`e`), its author (`p`), the group (`h`), the thread root (`E`) and the custom
 * emoji URL. Written instead of the raw event tags so the row shape stays stable.
 */
internal fun threadReactionCacheTags(
    reaction: NostrGroupClient.NostrReaction,
    groupId: String,
    rootId: String,
): List<List<String>> = buildList {
    add(listOf("e", reaction.targetEventId))
    reaction.targetAuthorPubkey?.let { add(listOf("p", it)) }
    add(listOf("h", groupId))
    add(listOf("E", rootId))
    reaction.emojiUrl?.let { add(listOf("emoji", reaction.emoji.trim(':'), it)) }
}

/** Inverse of [threadReactionCacheTags]; null when the row carries no target to react to. */
internal fun reactionFromCacheRow(
    id: String,
    pubkey: String,
    createdAt: Long,
    emoji: String,
    tags: List<List<String>>,
): NostrGroupClient.NostrReaction? {
    val target = tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1) ?: return null
    return NostrGroupClient.NostrReaction(
        id = id,
        pubkey = pubkey,
        emoji = emoji,
        emojiUrl = tags.firstOrNull { it.size >= 3 && it[0] == "emoji" }?.get(2),
        targetEventId = target,
        createdAt = createdAt,
        targetAuthorPubkey = tags.firstOrNull { it.size >= 2 && it[0] == "p" }?.get(1),
        groupId = tags.firstOrNull { it.size >= 2 && it[0] == "h" }?.get(1),
        threadRootId = tags.firstOrNull { it.size >= 2 && it[0] == "E" }?.get(1),
    )
}

/** NIP-29 delete-event: the admin moderation action that removes any member's event. */
internal const val ADMIN_DELETE_EVENT_KIND = 9005

/**
 * Pick the deletion event kind for a group message.
 *
 * - Kind 5 (NIP-09) is the standard deletion an author issues for their own message.
 * - Kind 9005 is the NIP-29 delete-event moderation action; NIP-29 relays only let an
 *   admin remove another member's message through it, never through kind 5.
 *
 * An admin deleting their own message stays on kind 5 (they are the author, and the NIP-09
 * deletion also travels outside this relay). [GroupManager.deleteMessage] falls back to 9005
 * if the relay rejects kind 5.
 */
internal fun deletionKindFor(isOwnMessage: Boolean, isAdmin: Boolean): Int = if (!isOwnMessage && isAdmin) ADMIN_DELETE_EVENT_KIND else 5
