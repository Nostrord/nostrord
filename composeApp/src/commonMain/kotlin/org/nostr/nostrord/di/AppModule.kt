package org.nostr.nostrord.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.nostr.nostrord.auth.Account
import org.nostr.nostrord.auth.AccountManager
import org.nostr.nostrord.auth.AccountSessionFactory
import org.nostr.nostrord.auth.AccountStore
import org.nostr.nostrord.auth.ActiveAccountManager
import org.nostr.nostrord.auth.pickFirstSuccess
import org.nostr.nostrord.network.AuthManager
import org.nostr.nostrord.network.NostrRepository
import org.nostr.nostrord.network.managers.AdaptiveConfig
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.network.managers.ConnectionStats
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.network.managers.LiveCursorStore
import org.nostr.nostrord.network.managers.MetadataManager
import org.nostr.nostrord.network.managers.MuxSubscriptionTracker
import org.nostr.nostrord.network.managers.OutboxManager
import org.nostr.nostrord.network.managers.PendingEventManager
import org.nostr.nostrord.network.managers.RelayMetadataManager
import org.nostr.nostrord.network.managers.SessionManager
import org.nostr.nostrord.network.managers.UnreadManager
import org.nostr.nostrord.network.managers.ZapManager
import org.nostr.nostrord.network.outbox.EventDeduplicator
import org.nostr.nostrord.network.outbox.RelayListManager
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.nostr.Nip27
import org.nostr.nostrord.notifications.FocusTracker
import org.nostr.nostrord.notifications.NotificationEntry
import org.nostr.nostrord.notifications.NotificationHistoryStore
import org.nostr.nostrord.notifications.NotificationPermission
import org.nostr.nostrord.notifications.NotificationRequest
import org.nostr.nostrord.notifications.NotificationService
import org.nostr.nostrord.notifications.NotificationType
import org.nostr.nostrord.notifications.playNotificationSound
import org.nostr.nostrord.settings.AppearanceSettings
import org.nostr.nostrord.settings.DmSettings
import org.nostr.nostrord.settings.MediaServerSettings
import org.nostr.nostrord.settings.MediaSettings
import org.nostr.nostrord.settings.NotificationSettings
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.cache.CacheStore
import org.nostr.nostrord.storage.cache.createCacheStore
import org.nostr.nostrord.ui.text.flattenMarkdownForPreview
import org.nostr.nostrord.utils.epochSeconds
import org.nostr.nostrord.utils.normalizeRelayUrl

/**
 * Simple dependency injection container.
 * Provides singleton instances of all managers and the repository.
 */
object AppModule {
    // Coroutine scope for the entire app
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * App-lifetime scope for fire-and-forget suspend actions triggered from the UI
     * (follow, send, ...). They must outlive the Composable/screen that triggered them,
     * so navigating away quickly never cancels (and loses) the work. Native counterpart
     * of the web bridge's `launchApp`.
     */
    fun launchApp(block: suspend CoroutineScope.() -> Unit): Job = appScope.launch(block = block)

    // Global event deduplicator — single instance shared across all managers.
    // Runs TTL eviction once per hour so long sessions don't accumulate stale entries.
    val eventDeduplicator: EventDeduplicator by lazy {
        EventDeduplicator().also { dedup ->
            appScope.launch {
                while (true) {
                    delay(60 * 60 * 1000L) // 1 hour
                    dedup.evictExpired()
                }
            }
        }
    }

    val connStats: ConnectionStats by lazy { ConnectionStats() }

    val accountStore: AccountStore by lazy { AccountStore() }

    // Per-group composer drafts (in memory, session-scoped). Lets the chat input
    // restore unsent text + mentions when the user leaves a group and comes back.
    val messageDraftStore: org.nostr.nostrord.ui.MessageDraftStore by lazy { org.nostr.nostrord.ui.MessageDraftStore() }

    // One-shot transient messages surfaced to the user (snackbar). Used for
    // events the user did not explicitly trigger, e.g. a bunker session that
    // got revoked while the user was sitting on another screen.
    private val _systemMessages = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    val systemMessages: kotlinx.coroutines.flow.SharedFlow<String> = _systemMessages

    /** Surface a one-shot transient confirmation/notice to the user (snackbar). */
    fun postSystemMessage(message: String) {
        _systemMessages.tryEmit(message)
    }

    // Set by the friends sidebar's "Follow people" empty-state action to re-open the
    // onboarding wizard for an account that already finished it but follows nobody.
    // AppViewModel folds this into its onboarding gate (overriding a prior Skip) and
    // clears it when the user leaves the wizard. It lives here, not on AppViewModel,
    // because the sidebar is too deep to reach the top-level AppViewModel (and on web
    // each useViewModel call site builds its own instance).
    private val _onboardingRequested = kotlinx.coroutines.flow.MutableStateFlow(false)
    val onboardingRequested: kotlinx.coroutines.flow.StateFlow<Boolean> = _onboardingRequested

    /** Re-open the onboarding wizard from inside the app (friends sidebar). */
    fun requestOnboarding() {
        _onboardingRequested.value = true
    }

    /** Clear the re-open request once the user leaves the wizard. */
    fun clearOnboardingRequest() {
        _onboardingRequested.value = false
    }

    val authManager: AuthManager by lazy {
        AuthManager(accountStore).also { am ->
            am.onSessionInvalidated = { invalidatedPubkey ->
                handleSessionInvalidated(invalidatedPubkey)
            }
        }
    }

    val accountSessionFactory: AccountSessionFactory by lazy {
        AccountSessionFactory(appScope)
    }

    val accountManager: AccountManager by lazy {
        // appScope (long-lived) so a switchAccountAsync() survives the caller
        // Composable being dismissed mid-swap — a rememberCoroutineScope would
        // cancel reloadForActiveAccount in the middle and leave the new
        // account's MY GROUPS empty.
        AccountManager(accountStore, authManager, accountSessionFactory, appScope)
    }

    /**
     * Bunker permission revoked / NIP-07 session lost / etc. Try to keep the
     * user inside the app by switching to another signed-in account.
     *
     * If a fallback account activates, the invalidated account stays in the
     * AccountStore with credentials intact so the user can re-pair later.
     * If NO fallback works (single-account install, or every other account
     * also broken), perform a full teardown: close NIP-29 sockets, cancel
     * in-flight coroutines, clear ActiveAccountManager, wipe the dead
     * account from the store. Without this the app sits in a zombie state
     * with isLoggedIn=false but live WebSockets still answering AUTH
     * challenges and heartbeat-driven sign attempts on a disposed signer,
     * which froze the browser tab.
     */
    private suspend fun handleSessionInvalidated(invalidatedPubkey: String?) {
        if (invalidatedPubkey.isNullOrBlank()) {
            _systemMessages.emit("Session ended. Please sign in.")
            fullTeardown()
            return
        }
        val invalidatedLabel = accountStore.get(invalidatedPubkey)?.label
            ?: "previous account"
        val candidates = accountStore.accounts.value
            .filter { it.pubkey != invalidatedPubkey }
            .sortedByDescending { it.addedAt }

        val winner = pickFirstSuccess(candidates) { candidate ->
            accountManager.switchAccount(candidate.id).isSuccess
        }
        if (winner != null) {
            _systemMessages.emit(
                "$invalidatedLabel disconnected. Switched to ${winner.label}.",
            )
            return
        }

        _systemMessages.emit("Couldn't reconnect to $invalidatedLabel.")
        fullTeardown()
    }

    /**
     * Close all relay sockets, cancel in-flight coroutines, clear per-account
     * caches and the ActiveAccountManager session, and wipe the dead account
     * from the store. Routes the UI to the login screen via the isLoggedIn /
     * isBunkerVerifying flags that nostrRepository.logout() resets.
     */
    private suspend fun fullTeardown() {
        try {
            nostrRepository.logout()
        } catch (_: Throwable) {}
        try {
            applyActiveAccountChange(null)
        } catch (_: Throwable) {}
    }

    // Lazy initialization of dependencies
    val relayListManager: RelayListManager by lazy {
        RelayListManager(
            bootstrapRelays = RelayListManager.DEFAULT_BOOTSTRAP_RELAYS,
            connectionManager = connectionManager,
        )
    }

    val connectionManager: ConnectionManager by lazy {
        ConnectionManager(scope = appScope, connStats = connStats, adaptiveConfig = adaptiveConfig)
    }

    val sessionManager: SessionManager by lazy {
        SessionManager(
            authManager = authManager,
        )
    }

    val outboxManager: OutboxManager by lazy {
        OutboxManager(
            connectionManager = connectionManager,
            relayListManager = relayListManager,
            scope = appScope,
        )
    }

    val pendingEventManager: PendingEventManager by lazy {
        PendingEventManager(
            connectionManager = connectionManager,
            scope = appScope,
        )
    }

    val liveCursorStore: LiveCursorStore by lazy {
        LiveCursorStore()
    }

    // Bulk message/event history cache. SQLDelight-backed on native, IndexedDB-backed on web
    // (web still on the in-memory fallback until that backend lands). No consumer reads it yet,
    // so wiring the real backend here is inert until the disk-first read paths are added.
    val cacheStore: CacheStore by lazy { createCacheStore() }

    val muxTracker: MuxSubscriptionTracker by lazy { MuxSubscriptionTracker() }

    val adaptiveConfig: AdaptiveConfig by lazy {
        AdaptiveConfig(scope = appScope)
    }

    val groupManager: GroupManager by lazy {
        GroupManager(
            connectionManager = connectionManager,
            scope = appScope,
            pendingEventManager = pendingEventManager,
            liveCursorStore = liveCursorStore,
            connStats = connStats,
            muxTracker = muxTracker,
            adaptiveConfig = adaptiveConfig,
            cacheStore = cacheStore,
            onNewMessagesFlushed = { groupId, newMessages ->
                unreadManager.onMessagesFlushed(groupId, newMessages)
            },
            onThreadEventReceived = { groupId, event ->
                unreadManager.onThreadEventReceived(groupId, event)
            },
        ).also { gm ->
            // Feed + popup for "an admin added you to a group" (kind:9000, live or catch-up).
            // Fires when the add lands as a PENDING invite; the group itself is only adopted
            // when the user accepts it from the group screen.
            appScope.launch {
                gm.externalAddPending.collect { add -> onExternalGroupAdd(add) }
            }
        }
    }

    /**
     * Notify an externally granted membership. Only the kind:9000 path carries an actor;
     * 39002-inferred invites (the user opened the group link and is looking at it) stay
     * silent. Catch-up adds (offline at put time) enter the feed but skip sound/popup via
     * [isRealtime], like every other notification type.
     */
    // Feed-entry age cap for external adds, matching the put-user watch's own 7-day
    // catch-up lookback. The no-since 39002 membership sweep has no such bound: on a
    // first connect it surfaces relay-side memberships from months ago, and their
    // enrichment 9000s carry the original timestamps — real pending invites, but
    // "added you 213d ago" is history, not news.
    private val GROUP_ADD_FEED_MAX_AGE_S = 7L * 24 * 3600

    private suspend fun onExternalGroupAdd(add: GroupManager.ExternalGroupAdd) {
        val actor = add.actorPubkey ?: return
        if (actor == sessionManager.getPublicKey()) return
        if (epochSeconds() - add.createdAtSeconds > GROUP_ADD_FEED_MAX_AGE_S) return
        // A relay-authored put-user (creator echo / relay backfill) is not a person adding
        // you: skip the "<relay key> added you" feed entry. The pending invite itself still
        // surfaces in the group screen prompt. Both key forms, like the VM's label lookup —
        // the metadata map is keyed by whatever URL the NIP-11 fetch received.
        val relayMeta = relayMetadataManager.relayMetadata.value
        val relayKey = (relayMeta[add.relayUrl] ?: relayMeta[add.relayUrl.normalizeRelayUrl()])
            ?.pubkey
            ?.takeIf { it.isNotBlank() }
        if (actor == relayKey) return
        // Give the repo's preview fetch a moment to land the kind:39000 so the entry
        // snapshots a real group name instead of a truncated id.
        kotlinx.coroutines.withTimeoutOrNull(3_000) {
            groupManager.groupsByRelay.first { byRelay ->
                byRelay.values.any { list -> list.any { it.id == add.groupId } }
            }
        }
        // Settled while we waited (create/join bookkeeping claimed its own echo, or the
        // user already decided): a notification for it would be noise.
        if (add.groupId !in groupManager.pendingGroupInvites.value) return
        val groupName = groupDisplayName(add.groupId)
        notificationHistoryStore.add(
            NotificationEntry(
                id = add.eventId ?: "gadd_${add.groupId}",
                type = NotificationType.GROUP_ADD,
                groupId = add.groupId,
                relayUrl = add.relayUrl,
                actorPubkey = actor,
                createdAt = add.createdAtSeconds,
                preview = "",
                groupName = groupName,
                relayName = relayDisplayName(add.relayUrl),
            ),
        )
        if (displayLabelFor(actor, prefixAt = false) == null) {
            appScope.launch { nostrRepository.requestUserMetadata(setOf(actor)) }
        }
        val realtime = isRealtime(add.createdAtSeconds)
        if (realtime && notificationSettings.soundEnabled.value) {
            playNotificationSound()
        }
        if (realtime &&
            notificationSettings.systemNotificationsEnabled.value &&
            notificationService.isSupported() &&
            notificationService.permission.value == NotificationPermission.Granted
        ) {
            val actorName = displayLabelFor(actor, prefixAt = false) ?: (actor.take(8) + "…")
            notificationService.notify(
                NotificationRequest(
                    relayUrl = add.relayUrl,
                    groupId = add.groupId,
                    title = groupName,
                    body = "$actorName added you to this group",
                ),
            )
        }
    }

    val metadataManager: MetadataManager by lazy {
        MetadataManager(
            connectionManager = connectionManager,
            outboxManager = outboxManager,
            scope = appScope,
            cacheStore = cacheStore,
            accountProvider = { sessionManager.getPublicKey() },
        )
    }

    val zapManager: ZapManager by lazy {
        ZapManager(
            metadataManager = metadataManager,
            connectionManager = connectionManager,
            scope = appScope,
        )
    }

    val focusTracker: FocusTracker by lazy { FocusTracker() }

    val notificationService: NotificationService by lazy { NotificationService() }

    val notificationHistoryStore: NotificationHistoryStore by lazy { NotificationHistoryStore() }

    // Unix-seconds timestamp of the most recent account activation. Events
    // with `createdAt < switchInstantSeconds` are catch-up: they predate the
    // user's current session of this account, so they enter the in-app feed
    // but do NOT play sound or fire OS popups. Set on every activation in
    // [applyActiveAccountChange].
    @kotlin.concurrent.Volatile
    private var switchInstantSeconds: Long = 0L

    private fun isRealtime(eventCreatedAt: Long): Boolean = eventCreatedAt >= switchInstantSeconds

    val unreadManager: UnreadManager by lazy {
        UnreadManager(
            // groupManager.isGroupJoined() is scoped to the active primary relay —
            // wrong for our purpose: we need cross-relay membership so notifications
            // fire for joined groups on background relays too. Scan all relay buckets.
            isJoined = { groupId ->
                groupManager.joinedGroupsByRelay.value.values.any { groupId in it }
            },
            isRestricted = { groupId -> groupManager.restrictedGroups.value.containsKey(groupId) },
            isAppFocused = { focusTracker.isAppFocused.value },
            findMessageAuthor = { messageId ->
                groupManager.findMessageByIdAcrossGroups(messageId)?.second?.pubkey
            },
            // Deferred to call time: by the first message flush the repository exists,
            // so this lazy access never cycles with unreadManager's own construction.
            isMutedAuthor = { pubkey -> pubkey in nostrRepository.mutedPubkeys.value },
            shouldNotify = { groupId, isDirect ->
                notificationSettings.shouldNotify(
                    notificationSettings.effectiveLevelFor(groupId),
                    isDirect,
                )
            },
            onUnreadIncrement = { groupId, message, _ ->
                val selfPubkey = sessionManager.getPublicKey()
                if (selfPubkey == null || message.pubkey != selfPubkey) {
                    val relayUrl = groupManager.getLatestMessageRelayForGroup(groupId)
                        ?: groupManager.getRelayForGroup(groupId) ?: ""
                    val preview = notificationPreview(message.content)
                    val groupName = groupDisplayName(groupId)
                    val relayName = relayDisplayName(relayUrl)
                    notificationHistoryStore.add(
                        NotificationEntry(
                            id = message.id,
                            type = NotificationType.MESSAGE,
                            groupId = groupId,
                            relayUrl = relayUrl,
                            actorPubkey = message.pubkey,
                            createdAt = message.createdAt,
                            preview = preview,
                            messageId = message.id,
                            groupName = groupName,
                            relayName = relayName,
                        ),
                    )
                    val realtime = isRealtime(message.createdAt)
                    // Sound — gated by the user-facing toggle in Settings → Notifications,
                    // and suppressed for catch-up events (older than the current activation)
                    // so a switch-in doesn't trigger a burst of sounds.
                    // Platform actuals no-op on unsupported targets (iOS for now).
                    if (realtime && notificationSettings.soundEnabled.value) {
                        playNotificationSound()
                    }
                    // Desktop popup — web-only; gated on platform support, granted permission,
                    // the user's toggle, and realtime so catch-up events don't pop.
                    if (realtime &&
                        notificationSettings.systemNotificationsEnabled.value &&
                        notificationService.isSupported() &&
                        notificationService.permission.value == NotificationPermission.Granted
                    ) {
                        val authorName = displayLabelFor(message.pubkey, prefixAt = false)
                            ?: (message.pubkey.take(8) + "…")
                        notificationService.notify(
                            NotificationRequest(
                                relayUrl = relayUrl,
                                groupId = groupId,
                                title = groupName,
                                body = "$authorName: $preview",
                                messageId = message.id,
                            ),
                        )
                    }
                }
            },
            onReplyNotify = { groupId, message ->
                val relayUrl = groupManager.getLatestMessageRelayForGroup(groupId)
                    ?: groupManager.getRelayForGroup(groupId) ?: ""
                val preview = notificationPreview(message.content)
                val groupName = groupDisplayName(groupId)
                val relayName = relayDisplayName(relayUrl)
                notificationHistoryStore.add(
                    NotificationEntry(
                        id = message.id,
                        type = NotificationType.REPLY,
                        groupId = groupId,
                        relayUrl = relayUrl,
                        actorPubkey = message.pubkey,
                        createdAt = message.createdAt,
                        preview = preview,
                        messageId = message.id,
                        groupName = groupName,
                        relayName = relayName,
                    ),
                )
                val realtime = isRealtime(message.createdAt)
                if (realtime && notificationSettings.soundEnabled.value) {
                    playNotificationSound()
                }
                if (realtime &&
                    notificationSettings.systemNotificationsEnabled.value &&
                    notificationService.isSupported() &&
                    notificationService.permission.value == NotificationPermission.Granted
                ) {
                    val authorName = displayLabelFor(message.pubkey, prefixAt = false)
                        ?: (message.pubkey.take(8) + "…")
                    notificationService.notify(
                        NotificationRequest(
                            relayUrl = relayUrl,
                            groupId = groupId,
                            title = groupName,
                            body = "$authorName replied to your message: $preview",
                            messageId = message.id,
                        ),
                    )
                }
            },
            onThreadReplyNotify = { groupId, reply ->
                val relayUrl = reply.relayUrl
                    ?: groupManager.getRelayForGroup(groupId) ?: ""
                val preview = notificationPreview(reply.content)
                val groupName = groupDisplayName(groupId)
                val relayName = relayDisplayName(relayUrl)
                // The E tag is the thread root; without it the entry would open the chat, where a
                // kind:1111 does not exist. Falls back to the reply itself so the pane still opens.
                val rootId = reply.tags.firstOrNull { it.size >= 2 && it[0] == "E" }?.get(1) ?: reply.id
                notificationHistoryStore.add(
                    NotificationEntry(
                        id = reply.id,
                        type = NotificationType.REPLY,
                        groupId = groupId,
                        relayUrl = relayUrl,
                        actorPubkey = reply.pubkey,
                        createdAt = reply.createdAt,
                        preview = preview,
                        messageId = reply.id,
                        threadRootId = rootId,
                        groupName = groupName,
                        relayName = relayName,
                    ),
                )
                val realtime = isRealtime(reply.createdAt)
                if (realtime && notificationSettings.soundEnabled.value) {
                    playNotificationSound()
                }
                if (realtime &&
                    notificationSettings.systemNotificationsEnabled.value &&
                    notificationService.isSupported() &&
                    notificationService.permission.value == NotificationPermission.Granted
                ) {
                    val authorName = displayLabelFor(reply.pubkey, prefixAt = false)
                        ?: (reply.pubkey.take(8) + "…")
                    notificationService.notify(
                        NotificationRequest(
                            relayUrl = relayUrl,
                            groupId = groupId,
                            title = groupName,
                            body = "$authorName replied in a thread: $preview",
                            messageId = reply.id,
                        ),
                    )
                }
            },
            onMentionNotify = { groupId, message ->
                val relayUrl = groupManager.getLatestMessageRelayForGroup(groupId)
                    ?: groupManager.getRelayForGroup(groupId) ?: ""
                val preview = notificationPreview(message.content)
                val groupName = groupDisplayName(groupId)
                val relayName = relayDisplayName(relayUrl)
                notificationHistoryStore.add(
                    NotificationEntry(
                        id = message.id,
                        type = NotificationType.MENTION,
                        groupId = groupId,
                        relayUrl = relayUrl,
                        actorPubkey = message.pubkey,
                        createdAt = message.createdAt,
                        preview = preview,
                        messageId = message.id,
                        groupName = groupName,
                        relayName = relayName,
                    ),
                )
                val realtime = isRealtime(message.createdAt)
                if (realtime && notificationSettings.soundEnabled.value) {
                    playNotificationSound()
                }
                if (realtime &&
                    notificationSettings.systemNotificationsEnabled.value &&
                    notificationService.isSupported() &&
                    notificationService.permission.value == NotificationPermission.Granted
                ) {
                    val authorName = displayLabelFor(message.pubkey, prefixAt = false)
                        ?: (message.pubkey.take(8) + "…")
                    notificationService.notify(
                        NotificationRequest(
                            relayUrl = relayUrl,
                            groupId = groupId,
                            title = groupName,
                            body = "$authorName mentioned you: $preview",
                            messageId = message.id,
                        ),
                    )
                }
            },
            onReactionNotify = { groupId, reaction ->
                val selfPubkey = sessionManager.getPublicKey()
                if (selfPubkey == null || reaction.pubkey != selfPubkey) {
                    val relayUrl = groupManager.getLatestMessageRelayForGroup(groupId)
                        ?: groupManager.getRelayForGroup(groupId) ?: ""
                    val emoji = reaction.emoji.ifBlank { "+" }
                    val groupName = groupDisplayName(groupId)
                    val relayName = relayDisplayName(relayUrl)
                    notificationHistoryStore.add(
                        NotificationEntry(
                            id = reaction.id,
                            type = NotificationType.REACTION,
                            groupId = groupId,
                            relayUrl = relayUrl,
                            actorPubkey = reaction.pubkey,
                            createdAt = reaction.createdAt,
                            preview = emoji,
                            messageId = reaction.targetEventId,
                            // A reaction to a thread root/reply must open the threads pane; the
                            // target does not exist in the chat. The reaction's own E tag is
                            // authoritative and works even when the target was never loaded here;
                            // the local scan covers reactors that omit it.
                            threadRootId = reaction.threadRootId
                                ?: groupManager.threadRootForEvent(reaction.targetEventId),
                            emoji = emoji,
                            groupName = groupName,
                            relayName = relayName,
                        ),
                    )
                    val realtime = isRealtime(reaction.createdAt)
                    if (realtime && notificationSettings.soundEnabled.value) {
                        playNotificationSound()
                    }
                    if (realtime &&
                        notificationSettings.systemNotificationsEnabled.value &&
                        notificationService.isSupported() &&
                        notificationService.permission.value == NotificationPermission.Granted
                    ) {
                        val authorName = displayLabelFor(reaction.pubkey, prefixAt = false)
                            ?: (reaction.pubkey.take(8) + "…")
                        notificationService.notify(
                            NotificationRequest(
                                relayUrl = relayUrl,
                                groupId = groupId,
                                title = groupName,
                                body = "$authorName reacted $emoji to your message",
                                messageId = reaction.targetEventId,
                            ),
                        )
                    }
                }
            },
            // Drop a group's notifications from the feed the moment its unread
            // badge is cleared, so opening/reading a chat clears both (issue #67).
            onGroupRead = { groupId -> notificationHistoryStore.markReadForGroup(groupId) },
            scope = appScope,
        )
    }

    val relayMetadataManager: RelayMetadataManager by lazy {
        RelayMetadataManager(scope = appScope)
    }

    val notificationSettings: NotificationSettings by lazy { NotificationSettings() }

    val mediaSettings: MediaSettings by lazy { MediaSettings() }

    val mediaServerSettings: MediaServerSettings by lazy { MediaServerSettings() }

    val appearanceSettings: AppearanceSettings by lazy { AppearanceSettings() }

    val dmSettings: DmSettings by lazy { DmSettings() }

    /** Holds an open AV room across screen changes; see [org.nostr.nostrord.ui.screens.avspace.AvSpaceHost]. */
    val avSpaceHost: org.nostr.nostrord.ui.screens.avspace.AvSpaceHost by lazy {
        org.nostr.nostrord.ui.screens.avspace.AvSpaceHost(nostrRepository)
    }

    val nostrRepository: NostrRepository by lazy {
        NostrRepository(
            connectionManager = connectionManager,
            sessionManager = sessionManager,
            groupManager = groupManager,
            metadataManager = metadataManager,
            outboxManager = outboxManager,
            zapManager = zapManager,
            unreadManager = unreadManager,
            pendingEventManager = pendingEventManager,
            relayMetadataManager = relayMetadataManager,
            liveCursorStore = liveCursorStore,
            connStats = connStats,
            notificationHistoryStore = notificationHistoryStore,
            notificationSettings = notificationSettings,
            cacheStore = cacheStore,
            scope = appScope,
        )
    }

    /**
     * Get the singleton NostrRepository instance.
     * This provides backward compatibility with code that uses NostrRepository directly.
     */
    fun getRepository(): NostrRepository = nostrRepository

    /**
     * Build and activate an [AccountSession] for the currently active account.
     *
     * Must be called after every successful login and after every cold-start
     * session restore — i.e. anywhere AuthManager has just loaded credentials
     * for an account. The session reuses AuthManager's KeyPair / Nip46Client
     * so there is exactly one credential instance per active account.
     *
     * Idempotent: if no active account exists in [accountStore] or AuthManager
     * has not loaded credentials yet, the existing session is left unchanged.
     */
    suspend fun activateSessionForActiveAccount() {
        // Re-allow pool connections that a prior logout gated off (no-op if still
        // logged in). Runs before any login-time relay/outbox/DM work.
        connectionManager.resumeConnections()
        val account = accountStore.active ?: return
        if (ActiveAccountManager.session.value?.accountId?.value == account.id) return
        val session = accountSessionFactory.build(account, authManager) ?: return
        ActiveAccountManager.activate(session)
    }

    /**
     * Reset every per-account in-memory cache and rebind it to [account].
     *
     * Called by switchAccount (Phase 4) after the AuthManager has loaded the
     * new account's credentials. Pass null to fully tear down on logout.
     *
     * This does NOT touch connection sockets or shared caches (metadata,
     * relay metadata, dedup, live cursors). Re-subscribing pubkey-filtered
     * REQs is the caller's responsibility.
     */
    suspend fun applyActiveAccountChange(account: Account?) {
        // Record the activation instant before any state is cleared. Events
        // with createdAt < this value are treated as catch-up (feed only,
        // no sound/popup). Set to 0 on logout so realtime gating is moot.
        switchInstantSeconds = if (account != null) epochSeconds() else 0L
        // Cancel any OS popups still in flight from the previous account so a
        // notification scheduled for A doesn't surface after B is active.
        try {
            notificationService.cancelAllPending()
        } catch (_: Throwable) {}
        // Close ALL of the old account's live subscriptions on every open socket BEFORE
        // clearing state and installing the new account. The mux chat/meta and dm_inbox REQs
        // are group/kind-filtered, not pubkey-filtered, so while they stay open the socket
        // (still AUTH'd as the old account) keeps pushing the old account's kind:9 / 39000 /
        // gift-wrap events, which land in the new account's freshly-cleared, now-new-account-
        // bound state — the old account's groups/messages then render under the new account.
        // Sockets stay connected; the new account re-subscribes via reloadForActiveAccount.
        // (Supersedes the group-list-only close: that sub id is also in openSubscriptions.)
        try {
            connectionManager.closeAllSubscriptionsOnAllClients()
        } catch (_: Throwable) {}
        groupManager.clear()
        unreadManager.clear()
        notificationSettings.clear()
        notificationHistoryStore.clear()
        // OutboxManager owns the per-account kind:10009 relay list and the
        // user's NIP-65 relay list. Without clearing here, the previous
        // account's relays linger in the rail until a new kind:10009 arrives
        // (and for accounts that never published one, they linger forever).
        outboxManager.clear()
        // Pending events are per-account (signed by the previous account's
        // signer). Replaying them against the new account is wrong.
        pendingEventManager.clear()
        // The "current relay" pointer is per-account in storage but
        // ConnectionManager only reads it on loadSavedRelay(). Clear here so
        // the rail's "current" slot doesn't keep showing the previous
        // account's relay before reloadForActiveAccount runs.
        connectionManager.clearCurrentRelay()

        if (account != null) {
            groupManager.setCurrentPubkey(account.pubkey)
            pendingEventManager.setCurrentPubkey(account.pubkey)
            unreadManager.initialize(account.pubkey)
            notificationSettings.initialize(account.pubkey)
            notificationHistoryStore.initialize(account.pubkey)
            // Restore the new account's saved relay (no-op if they don't have
            // one yet — a freshly added account starts with a blank rail).
            connectionManager.loadSavedRelay()
        } else {
            // Logout path: clear the active session so signing is impossible.
            ActiveAccountManager.clear()
        }
    }

    /**
     * Look up a human-readable label for [pubkey] from the metadata cache.
     * Prefers the NIP-01 `name` (short handle), falls back to `display_name`.
     * Returns null if no metadata is cached, so callers can decide a fallback
     * (e.g. truncated pubkey) and trigger an async fetch.
     */
    /**
     * Resolves a group's display name across every relay's cache. The
     * notification callbacks fire for groups on background relays too, so
     * `groupManager.groups` (active relay only) misses them and falls back to
     * the truncated id. Joined-group metadata is also restored from
     * [SecureStorage] at startup, so cached entries survive cold launches.
     */
    private fun groupDisplayName(groupId: String): String {
        val name = groupManager.groupsByRelay.value.values
            .firstNotNullOfOrNull { list ->
                list.firstOrNull { it.id == groupId }?.name?.takeIf { it.isNotBlank() }
            }
        return name ?: groupId.take(8)
    }

    /**
     * NIP-11 display name for [relayUrl] from the metadata cache. Returns null
     * when no entry exists so callers can decide between snapshot omission and
     * a URL-derived label.
     */
    private fun relayDisplayName(relayUrl: String): String? {
        if (relayUrl.isBlank()) return null
        return relayMetadataManager.relayMetadata.value[relayUrl]?.name?.takeIf { it.isNotBlank() }
    }

    private fun displayLabelFor(pubkey: String, prefixAt: Boolean): String? {
        val meta = metadataManager.userMetadata.value[pubkey] ?: return null
        val name = meta.name?.takeIf { it.isNotBlank() }
            ?: meta.displayName?.takeIf { it.isNotBlank() }
            ?: return null
        return if (prefixAt) "@$name" else name
    }

    /**
     * Replace `nostr:npub1…` / `nostr:nprofile1…` (and their bare bech32 forms)
     * inside [content] with `@<name>` resolved from the metadata cache. When a
     * referenced pubkey isn't cached, fall back to a short bech32 (`@npub1abc…`)
     * and kick off an async fetch so the next notification can resolve it.
     *
     * Other entity types (note/nevent/naddr) are left untouched — turning event
     * references into a readable summary needs the event itself, which isn't
     * available here.
     */
    /**
     * The message text as a notification shows it: mentions resolved to names and
     * markdown flattened to plain text. Feeds both the in-app feed and the OS
     * notification body, neither of which renders markers - and the OS body is why
     * spoilers are masked rather than unwrapped.
     */
    private fun notificationPreview(content: String): String = flattenMarkdownForPreview(resolveMentionsForNotification(content)).take(120)

    private fun resolveMentionsForNotification(content: String): String {
        val matches = Nip27.findReferenceMatches(content)
        if (matches.isEmpty()) return content
        val sb = StringBuilder()
        var cursor = 0
        val toFetch = mutableSetOf<String>()
        for ((range, ref) in matches) {
            val pubkey = when (val e = ref.entity) {
                is Nip19.Entity.Npub -> e.pubkey
                is Nip19.Entity.Nprofile -> e.pubkey
                else -> null
            } ?: continue
            sb.append(content, cursor, range.first)
            val label = displayLabelFor(pubkey, prefixAt = false)
            if (label != null) {
                sb.append('@').append(label)
            } else {
                toFetch += pubkey
                // Hex fallback (matches Nip19.summary, SystemEventItem, and the
                // author-name fallback in the notification callbacks). Mixing
                // bech32 here was the only place in the app that showed npub
                // for unknown pubkeys, breaking the visual convention.
                sb.append('@').append(pubkey.take(8)).append('…')
            }
            cursor = range.last + 1
        }
        sb.append(content, cursor, content.length)
        if (toFetch.isNotEmpty()) {
            appScope.launch { nostrRepository.requestUserMetadata(toFetch) }
        }
        return sb.toString()
    }
}
