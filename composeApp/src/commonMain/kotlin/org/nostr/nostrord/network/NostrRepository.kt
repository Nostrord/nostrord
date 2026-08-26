package org.nostr.nostrord.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.nostr.nostrord.auth.Account
import org.nostr.nostrord.auth.ActiveAccountManager
import org.nostr.nostrord.auth.NostrSigner
import org.nostr.nostrord.auth.SelfDecryptCache
import org.nostr.nostrord.auth.parseSignedEventJson
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.livekit.LiveKitCredentials
import org.nostr.nostrord.network.livekit.LiveKitTokenClient
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.network.managers.ConnectionStats
import org.nostr.nostrord.network.managers.DmArchiveManager
import org.nostr.nostrord.network.managers.DmConversation
import org.nostr.nostrord.network.managers.DmEncryptionManager
import org.nostr.nostrord.network.managers.DmFileManager
import org.nostr.nostrord.network.managers.DmHistoryFile
import org.nostr.nostrord.network.managers.DmManager
import org.nostr.nostrord.network.managers.DmMessage
import org.nostr.nostrord.network.managers.DmPairingManager
import org.nostr.nostrord.network.managers.DmPublishOutcome
import org.nostr.nostrord.network.managers.DmSendQueue
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.network.managers.LegacySealVerifier
import org.nostr.nostrord.network.managers.LiveCursorStore
import org.nostr.nostrord.network.managers.MentionCandidate
import org.nostr.nostrord.network.managers.MentionTags
import org.nostr.nostrord.network.managers.MetadataManager
import org.nostr.nostrord.network.managers.OutboxManager
import org.nostr.nostrord.network.managers.PendingDmWrap
import org.nostr.nostrord.network.managers.PendingGroupInvite
import org.nostr.nostrord.network.managers.RelayMetadataManager
import org.nostr.nostrord.network.managers.RelayProbeGuard
import org.nostr.nostrord.network.managers.RelayReconnectScheduler
import org.nostr.nostrord.network.managers.SessionManager
import org.nostr.nostrord.network.managers.UnreadManager
import org.nostr.nostrord.network.managers.ZapManager
import org.nostr.nostrord.network.outbox.Nip65Relay
import org.nostr.nostrord.network.upload.decodeImageDimensions
import org.nostr.nostrord.network.upload.uploadEncryptedBlob
import org.nostr.nostrord.nostr.Crypto
import org.nostr.nostrord.nostr.DmMessageOrder
import org.nostr.nostrord.nostr.DmOutgoingFile
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.nostr.Nip11RelayInfo
import org.nostr.nostrord.nostr.Nip17
import org.nostr.nostrord.nostr.Nip17File
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.nostr.Nip44
import org.nostr.nostrord.nostr.Nip4e
import org.nostr.nostrord.nostr.Nip78
import org.nostr.nostrord.nostr.toHexString
import org.nostr.nostrord.settings.NotificationLevel
import org.nostr.nostrord.startup.StartupResolver
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.cache.DM_CACHE_KIND
import org.nostr.nostrord.storage.cache.DM_CACHE_KINDS
import org.nostr.nostrord.storage.cache.DM_FILE_CACHE_KIND
import org.nostr.nostrord.storage.clearCurrentRelayUrlFor
import org.nostr.nostrord.storage.clearDmMessages
import org.nostr.nostrord.storage.getLastActiveAt
import org.nostr.nostrord.storage.isDmCacheMigratedFor
import org.nostr.nostrord.storage.isGroupFetchLazy
import org.nostr.nostrord.storage.isKind10009RepublishPendingFor
import org.nostr.nostrord.storage.loadDmArchivedRumorIdsFor
import org.nostr.nostrord.storage.loadDmEncKeys
import org.nostr.nostrord.storage.loadDmLastRead
import org.nostr.nostrord.storage.loadDmMessages
import org.nostr.nostrord.storage.loadDmPairingProcessedFor
import org.nostr.nostrord.storage.loadDmProcessedWrapIds
import org.nostr.nostrord.storage.loadDmReactions
import org.nostr.nostrord.storage.loadDmSeenRelays
import org.nostr.nostrord.storage.loadDmSendQueue
import org.nostr.nostrord.storage.loadDmSyncCursor
import org.nostr.nostrord.storage.loadDmWrapRumor
import org.nostr.nostrord.storage.loadFollowingCacheFor
import org.nostr.nostrord.storage.loadKind10000TimestampFor
import org.nostr.nostrord.storage.loadKind30078TimestampFor
import org.nostr.nostrord.storage.loadMuteListSnapshotFor
import org.nostr.nostrord.storage.loadRelayListFor
import org.nostr.nostrord.storage.saveCurrentRelayUrlFor
import org.nostr.nostrord.storage.saveDmArchivedRumorIdsFor
import org.nostr.nostrord.storage.saveDmEncKeys
import org.nostr.nostrord.storage.saveDmLastRead
import org.nostr.nostrord.storage.saveDmPairingProcessedFor
import org.nostr.nostrord.storage.saveDmProcessedWrapIds
import org.nostr.nostrord.storage.saveDmReactions
import org.nostr.nostrord.storage.saveDmSeenRelays
import org.nostr.nostrord.storage.saveDmSendQueue
import org.nostr.nostrord.storage.saveDmSyncCursor
import org.nostr.nostrord.storage.saveDmWrapRumor
import org.nostr.nostrord.storage.saveFollowingCacheFor
import org.nostr.nostrord.storage.saveGroupFetchLazy
import org.nostr.nostrord.storage.saveKind10000TimestampFor
import org.nostr.nostrord.storage.saveKind30078TimestampFor
import org.nostr.nostrord.storage.saveMuteListSnapshotFor
import org.nostr.nostrord.storage.saveRelayListFor
import org.nostr.nostrord.storage.setDmCacheMigratedFor
import org.nostr.nostrord.ui.screens.dm.eventJson
import org.nostr.nostrord.utils.AesGcm
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.epochSeconds
import org.nostr.nostrord.utils.groupKey
import org.nostr.nostrord.utils.normalizeRelayUrl
import org.nostr.nostrord.utils.urlDecode
import kotlin.concurrent.Volatile

/**
 * Curator whose public kind:10009 seeds the Recommended discovery tab. Mirrored here so the
 * group-list fetch can include the curator's groups in its targeted (known-only) #d request,
 * matching what the Recommended tab needs (HomePageViewModel uses the same pubkey).
 */
private const val DISCOVERY_CURATOR_PUBKEY = "b2cdcb37d32533145c00c4f43d5e1e1deb7c67bceea7ef63f526ca4cab891633"

// NIP-59 backdates gift-wrap timestamps up to 2 days into the past, so the DM-inbox `since`
// must reach back this far from the sync cursor or recent wraps would be missed on resync.
private const val GIFT_WRAP_BACKDATE_SECONDS = 2L * 24 * 60 * 60

// Min frame silence before a foreground return probes a socket. A frame in the
// last minute proves the socket is alive; below this, probing is pure overhead.
private const val FOREGROUND_PROBE_MIN_SILENCE_MS = 60_000L

// Min gap between two foreground socket sweeps. Visibility changes come in bursts (tab
// switching, app switching); each sweep can kill and rebuild sockets, and every rebuilt
// socket costs a NIP-42 signature.
private const val FOREGROUND_SWEEP_MIN_INTERVAL_MS = 60_000L

/**
 * Coalescing window for kind:30078 notification-prefs publishes: a burst of toggles is
 * one event. Short on purpose — with a NIP-07 or bunker signer this is the delay before
 * the signing prompt appears, and anything longer reads as a popup out of nowhere.
 */
private const val NOTIF_PREFS_PUBLISH_DEBOUNCE_MS = 600L

/**
 * How long a replaceable list waits after its first copy before its private section is
 * decrypted. Relays hold different versions, and only the newest is worth reading: with a
 * signer that asks the user, every other version in the burst is a dialog answered for
 * nothing. Matches the settle window kind:10009 discovery already uses.
 */
private const val REPLACEABLE_SETTLE_MS = 1_500L

/** Cap on one self-decrypt, for signers that answer on their own (local key, bunker). */
private const val PRIVATE_DECRYPT_TIMEOUT_MS = 20_000L

/** Floor between focus-driven kind:30078 re-fetches, so window flipping can't spam relays. */
private const val NOTIF_PREFS_FETCH_MIN_INTERVAL_S = 3L

/** How far back the live kind:30078 filter reaches, to absorb clock skew between devices. */
private const val NOTIF_PREFS_LIVE_SLACK_S = 300L

/**
 * Backstop poll for kind:30078 while the app is focused. The subscription's `since`
 * filter is what should deliver a change within a second; this only covers a relay that
 * drops the subscription, or a desktop window that reports no focus change because the
 * user clicked over to a browser sitting beside it.
 */
private const val NOTIF_PREFS_TICK_MS = 30_000L

/**
 * Repository for Nostr operations.
 * Coordinates between specialized managers for different concerns.
 *
 * Dependencies are injected via constructor for testability.
 * Use [NostrRepository.instance] for the default singleton.
 */
class NostrRepository(
    private val connectionManager: ConnectionManager,
    private val sessionManager: SessionManager,
    private val groupManager: GroupManager,
    private val metadataManager: MetadataManager,
    private val outboxManager: OutboxManager,
    private val zapManager: ZapManager,
    private val unreadManager: UnreadManager,
    private val pendingEventManager: org.nostr.nostrord.network.managers.PendingEventManager? = null,
    private val relayMetadataManager: RelayMetadataManager? = null,
    private val liveCursorStore: LiveCursorStore? = null,
    private val connStats: ConnectionStats = ConnectionStats(),
    private val notificationHistoryStore: org.nostr.nostrord.notifications.NotificationHistoryStore? = null,
    private val notificationSettings: org.nostr.nostrord.settings.NotificationSettings? = null,
    // DM history is persisted here (IndexedDB on web, SQLDelight on native) instead of the
    // size-limited SecureStorage KV. Defaults to in-memory so tests construct without a backend.
    private val cacheStore: org.nostr.nostrord.storage.cache.CacheStore = org.nostr.nostrord.storage.cache.InMemoryCacheStore(),
    private val scope: CoroutineScope,
) : NostrRepositoryApi {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A gap is suspected when the newest in-memory message is older than the cursor by at least
     * this many seconds. 120 s gives relays reasonable delivery time before we consider the
     * difference a real hole rather than normal clock skew.
     */
    private val GAP_THRESHOLD_S = 120L

    /**
     * Minimum seconds between gap-detection passes for the same relay.
     * Prevents the CLOSE→REQ mux cycle from triggering three successive gap fills.
     */
    private val GAP_DETECTION_COOLDOWN_S = 30L
    private val lastGapDetectionAt = mutableMapOf<String, Long>()

    /** How long the zap modal waits for a payment confirmation, and the receipt poll cadence. */
    private val ZAP_PAYMENT_WATCH_MS = 90_000L
    private val ZAP_PAYMENT_POLL_MS = 3_000L

    /**
     * Epoch-seconds of the last requestGroups() call per relay.
     * Prevents resubscribeAfterAuth from sending a duplicate group-list REQ when
     * connect() already sent one within the last 10 seconds.
     */
    private val lastRequestGroupsAt = mutableMapOf<String, Long>()

    // Per "relayUrl|groupId" cooldown for fetchGroupPreviews. Its caller (HomePageViewModel)
    // deliberately re-emits relayToGroups on every following/userGroupLists/joinedGroupsByRelay/
    // groupsByRelay change so a group whose metadata hasn't landed yet gets retried instead of
    // abandoned — but those four flows update independently during login, so several distinct
    // (and therefore not distinctUntilChanged-suppressed) emissions can land within milliseconds
    // of each other while a group is still missing, each spawning its own launch that fires the
    // same batched REQ. Mirrors GroupManager.requestPrivateGroupData's cooldown/mutex fix.
    private val groupPreviewFetchAt = mutableMapOf<String, Long>()
    private val groupPreviewFetchMutex = Mutex()

    // Backoff state for re-arming mux subs a relay CLOSEs with "restricted" (see the
    // CLOSED handler). Attempts reset on the next successful mux_chat EOSE.
    private val muxRestrictedRetryJobs = mutableMapOf<String, Job>()
    private val muxRestrictedRetryAttempts = mutableMapOf<String, Int>()

    // Caps how many NIP-17 DM decryptions hit the signer at once. A cold load streams
    // every unread kind:1059 (dozens, sometimes 80+), and with a remote signer (NIP-46
    // bunker) each decrypt is a serialized round-trip. Launching them all flooded the
    // signer queue so the group relays' NIP-42 AUTH signs were starved — the relay then
    // closed the unauthenticated socket and private groups never loaded (the more DMs,
    // the worse; absent on main, which has no NIP-17). Bounding the burst leaves the
    // signer free to answer AUTH promptly while DMs decrypt steadily in the background.
    // Bound concurrent gift-wrap decryption. A larger fan-out amortizes the signer's per-request
    // latency (it answers nip44_decrypt in bursts), so the inbox drains faster, while still capping
    // the load so a remote bunker isn't flooded. Paired with the in-flight dedup (one coroutine per
    // wrap) so re-streams never multiply the in-flight count past this bound. Local / NIP-07 signers
    // decrypt in-process and don't care about it.
    private val dmDecryptSemaphore = Semaphore(6)

    // A bunker decrypt PUBLISHES two kind:24133 requests to the bunker relay (wrap then seal), and
    // every publish is paced + rate-limit-backed-off inside Nip46Client (Nip46PublishPacer). This
    // gate only ADMITS backlog wraps slowly enough (two publishes' worth of client pacing per wrap)
    // that the client-side publish queue stays shallow: the admission wait happens BEFORE the 90s
    // decrypt timeout starts counting, and interactive signs (reactions, uploads, NIP-42 AUTH) find
    // a near-empty queue. Crucially the gate is released BEFORE the await, not held across it: the
    // signer answers nip44_decrypt in bursts, so we keep many requests in-flight for one burst to
    // satisfy at once (serializing the await instead collapses throughput and every wrap hits the
    // 90s timeout). Local / NIP-07 decrypt in-process (no publish), skip the gate, and keep the
    // full semaphore concurrency above.
    private val bunkerPublishGate = Mutex()
    private val BUNKER_WRAP_ADMIT_INTERVAL_MS = 2 * org.nostr.nostrord.nostr.Nip46PublishPacer.MIN_INTERVAL_MS

    // Cap one wrap's decryption. The signer RPC's own timeout is 120s; an intermittently
    // unresponsive bunker would otherwise pin a decrypt permit for that long per stuck wrap. But too
    // short a cap kills decrypts the signer answers in its NEXT burst (it replies in bursts with
    // quiet gaps), wasting the work and stalling progress. 90s rides out a quiet gap while still
    // freeing the permit for the periodic retry when the signer is genuinely gone.
    private val DM_DECRYPT_TIMEOUT_MS = 90_000L

    // How long to wait for a peer's kind:10044 after asking for it, when authenticating a NIP-4e
    // legacy seal. Short: on a miss the wrap stays unhandled and the pipeline retries it later.
    private val DM_ENC_KEY_FETCH_WAIT_MS = 3_000L

    // Publish the legacy (encryption-key-signed) copy alongside the modern one, mirroring Jumble's
    // migration. It is the only shape Coop and pre-migration builds read, it is signed locally so
    // it costs no signer time, and the modern copy stays the delivery-tracked primary. Flip off
    // once the deployed clients have dropped their legacy readers.
    private val NIP4E_DUAL_SEND = true

    // Per-relay budget for a background event publish (connect + send). Bounds a dead socket so it
    // can't leak the publish job; delivery is best-effort and swallows failures anyway.
    private val PUBLISH_RELAY_TIMEOUT_MS = 8_000L

    // How long a wrap refused with "auth-required" waits for the NIP-42 signature before it is sent
    // again. Generous because a bunker or a signer app makes that a round-trip, not a formality, and
    // the wait runs in the send queue's own scope: nothing on screen is blocked by it.
    private val PUBLISH_AUTH_SIGN_BUDGET_MS = 20_000L

    // Budget for the relays to answer with this account's own kind:10044 before enabling acts on
    // local state. Short: it is paid on every Settings open and on a first enable.
    private val OWN_ANNOUNCEMENT_WAIT_MS = 2_500L

    // Budget for a peer's kind:10050 before the first message of a conversation goes out. Paid once
    // per peer, and only when we hold no list for them; the send proceeds to the defaults after it.
    private val PEER_DM_RELAY_WAIT_MS = 3_000L

    // How far back the pairing subscription looks. Long enough to cover a request published while
    // this app was closed (its client is still sitting on the pairing screen), short enough that a
    // request the user walked away from days ago never re-prompts. Kept under
    // DmPairingManager.PROCESSED_RETENTION_SECONDS so a decision always outlives what the relay
    // will still serve.
    private val PAIRING_LOOKBACK_SECONDS = 6L * 60 * 60

    // Give up on a wrap after this many failed decrypt attempts (this session). High enough that
    // congestion cycles (the relay dropping the signer's responses -> 90s timeouts) don't park the
    // inbox for the whole session; still stops the retry loop from hammering wraps the signer
    // never answers, or genuinely malformed ones.
    private val DM_MAX_DECRYPT_ATTEMPTS = 10

    // Parked (given-up) wraps get a fresh run on this cadence, doubling up to the cap. A signer
    // that is still down must not have every backlog wrap's ten attempts re-burned every pass,
    // but a session that never unparks leaves those DMs missing until the app is restarted.
    private val DM_GIVEN_UP_RETRY_MIN_MS = 2 * 60_000L
    private val DM_GIVEN_UP_RETRY_MAX_MS = 30 * 60_000L

    // How long the full-sync latch waits for a subscribed DM relay to EOSE before treating it as
    // settled. Generous: it only has to outlast a slow backlog delivery, and latching early on a
    // relay still streaming would advance the cursor past undelivered wraps.
    private val DM_INBOX_EOSE_TIMEOUT_MS = 90_000L

    // Keeps the decrypt backlog off the signer for the first seconds of a session so
    // login-critical signs (NIP-42 AUTH, handshake acks - including another device's login
    // against the same signer) find its queue empty. Set in startDmInbox.
    private val DM_BACKLOG_BOOT_HOLD_MS = 10_000L

    @kotlin.concurrent.Volatile
    private var dmBacklogHoldUntilMs = 0L

    // Newest-first cap on a fresh device's backlog decrypt. Relays stream the REQ newest-first,
    // so the first N wraps admitted are (approximately, gift-wrap created_at is randomized) the
    // most recent conversation: they decrypt at the normal paced rate and fill the visible inbox
    // fast. Everything older takes a spaced trickle slot and parks OUTSIDE the admission gate,
    // so live wraps (arriving after every DM relay EOSEd) and the eager batch never queue behind
    // the deep history. 2 signer round trips per wrap times N devices is the load that trips the
    // bunker relay's rate limit; the cap bounds what a fresh login costs.
    private val DM_EAGER_DECRYPT_CAP = 50
    private val DM_TRICKLE_INTERVAL_MS = 5_000L
    private val dmTrickleMutex = Mutex()
    private var dmEagerDecryptCount = 0
    private var dmTrickleNextSlotMs = 0L

    // Durable per-wrap dedup so a re-streamed backlog skips wraps already decrypted (no repeat
    // bunker round-trip). Loaded per account in startDmInbox; grows as wraps are handled.
    // Copy-on-write + @Volatile: the relay pipeline thread reads it while the (serialized) decrypt
    // coroutine appends, so snapshots stay safe without locking.
    @kotlin.concurrent.Volatile
    private var dmProcessedWrapIds: Set<String> = emptySet()

    // This-session bookkeeping for latching the full-sync flag only once we are genuinely caught
    // up: every gift-wrap id the inbox delivered, and whether the inbox sub has EOSEd.
    @kotlin.concurrent.Volatile
    private var dmReceivedWrapIds: Set<String> = emptySet()

    // Gift-wrap ids with a decrypt coroutine currently in flight. A re-streamed inbox (relay change,
    // reconnect, periodic retry) re-delivers the same wraps; without this guard each delivery spawns
    // ANOTHER decrypt coroutine for the same wrap, and the duplicates hog the decrypt semaphore so
    // only a handful of wraps ever make progress. One coroutine per wrap at a time.
    @kotlin.concurrent.Volatile
    private var dmInFlightWrapIds: Set<String> = emptySet()

    // Per-wrap decrypt failure count, and wraps given up THIS session after too many failures. Kept
    // in memory only (not persisted): a wrap abandoned because the signer was momentarily
    // unresponsive is retried fresh next session, while within a session giving up stops the retry
    // loop from re-streaming it forever (which otherwise spams EOSE and trips the relay's
    // publish rate limit, "you are noting too much").
    @kotlin.concurrent.Volatile
    private var dmFailCounts: Map<String, Int> = emptyMap()

    @kotlin.concurrent.Volatile
    private var dmGivenUpWrapIds: Set<String> = emptySet()

    // created_at of the oldest wrap given up this session, 0 when none. The full-sync latch rewinds
    // the sync cursor to it, so the next session's incremental REQ still covers the wraps this one
    // could not decrypt instead of skipping past them forever.
    @kotlin.concurrent.Volatile
    private var dmGivenUpOldestAt: Long = 0L

    // Which DM relays have EOSEd the inbox sub this session. The full-sync latch waits for ALL
    // subscribed DM relays, so a relay that EOSEs early with an empty/partial set can't latch
    // "synced" before the slower relays deliver the rest of the backlog.
    @kotlin.concurrent.Volatile
    private var dmInboxEosedRelays: Set<String> = emptySet()

    // Deadline for the wait above. A relay that accepts the REQ and never EOSEs (or silently drops
    // the sub) would otherwise pin `since = 0` forever, so every launch re-streams the whole
    // backlog. Past the deadline the silent relays count as settled, provided at least one relay
    // did answer.
    @kotlin.concurrent.Volatile
    private var dmInboxEoseDeadlineMs: Long = 0L
    private var dmProcessedSaveJob: kotlinx.coroutines.Job? = null
    private var dmSeenRelaysSaveJob: kotlinx.coroutines.Job? = null

    // Upper bound on how long a bunker account holds its DM gift-wrap backlog
    // while the active relay signs its NIP-42 AUTH. awaitAuthOrTimeout returns
    // the instant AUTH completes, so this only caps the wait for relays that
    // turn out to need no AUTH (public). See the gift-wrap handler.
    private val DM_INGEST_AUTH_GRACE_MS = 10_000L

    // After an interactive bunker login, let finishLoginInit's own connect settle
    // before the idempotent signer-ready recovery runs, so the two don't reconnect
    // the focused at once. The recovery only acts if AUTH did not take.
    private val BUNKER_LOGIN_RECOVERY_DELAY_MS = 2_500L

    // NIP-42 AUTH sign budget for the PRIVATE-group data fetch only. A local key signs
    // instantly; a remote (bunker / NIP-07) signer is a round-trip that can take several
    // seconds, so the gate that holds the private-group #d REQ until AUTH lands must wait
    // long enough or it races the sign and comes back CLOSED "auth-required". The PUBLIC
    // group list is NOT gated on this — it uses the short awaitAuthOrTimeout so public
    // groups load fast even while a slow bunker AUTH is still signing.
    private val LOCAL_SIGNER_AUTH_BUDGET_MS = 2_000L
    private val REMOTE_SIGNER_AUTH_BUDGET_MS = 12_000L

    // Grace before auto-forgetting an orphan pin. Must exceed the remote AUTH budget plus a
    // little, so a private group whose kind:39000 only arrives post-AUTH (after the public
    // group-list EOSE flagged it as an orphan) has time to resolve and is not wrongly dropped.
    private val ORPHAN_FORGET_SETTLE_MS = 15_000L

    private fun signerAuthBudgetMs(): Long = if (ActiveAccountManager.session.value?.signer?.isRemote == true) {
        REMOTE_SIGNER_AUTH_BUDGET_MS
    } else {
        LOCAL_SIGNER_AUTH_BUDGET_MS
    }

    /**
     * Relays for which a post-AUTH group-list fetch has already been issued this
     * session. resubscribeAfterAuth invalidates the (possibly pre-AUTH, empty-EOSE)
     * full-list marker only on the FIRST auth completion per relay; thereafter the
     * in-session marker is trustworthy, so subsequent re-AUTH challenges fall back
     * to the normal 10s dedup instead of force-refetching the full list every time.
     */
    private val authedGroupListFetchedRelays = mutableSetOf<String>()

    /**
     * Relays for which the UI requested the full OTHER GROUPS list while the
     * corresponding client wasn't yet focused/connected/AUTHed. Drained by
     * [drainFullFetchRequest] from connect()/switchRelay()/resubscribeAfterAuth
     * once the client is ready, so the user-triggered click is honoured
     * without a polling wait.
     */
    private val pendingFullFetchRequests = mutableSetOf<String>()
    private val pendingFullFetchMutex = Mutex()

    /**
     * Relay URL of the group currently open on screen — used to promote reconnect priority.
     * Null when no group is focused (Home screen, settings, etc.).
     */
    private var activeRelayUrl: String? = null

    // Debounced metadata refresh — when multiple mux subs are CLOSED at once
    // (idle drop), only one refreshVisibleUserMetadata() fires.
    private var metadataRefreshJob: kotlinx.coroutines.Job? = null

    // Tracks the intended relay; stale concurrent switchRelay() calls bail out when they see a mismatch.
    private val _targetSwitchRelayUrl = MutableStateFlow<String?>(null)

    // Per-relay bounded message pipelines — prevents unbounded coroutine creation under burst load.
    // Map: relayUrl -> (client, pipeline). The client reference is used to detect reconnects:
    // when a new NostrGroupClient is created for the same URL, the old pipeline is closed and a
    // new one is created, ensuring handleUnifiedMessage always sees the live client.
    private val relayPipelines = mutableMapOf<String, Pair<NostrGroupClient, RelayEventPipeline>>()

    /**
     * Routes an incoming WebSocket frame through the per-relay pipeline.
     * Creates the pipeline on first message; replaces it if the client has changed (reconnect).
     */
    private fun enqueueToRelayPipeline(msg: String, client: NostrGroupClient) {
        val url = client.getRelayUrl()
        val entry = relayPipelines[url]
        val pipeline = if (entry != null && entry.first === client) {
            entry.second
        } else {
            entry?.second?.close()
            RelayEventPipeline(url, scope) { m -> handleUnifiedMessage(m, client) }
                .also { relayPipelines[url] = client to it }
        }
        pipeline.enqueue(msg)
    }

    // Relays whose live subscriptions died with their socket and must be re-armed on the
    // NEXT successful connect, whoever lands it. Marked in onPoolRelayLost, consumed in
    // onRelayConnected (see initialize()). This is what makes the resubscribe independent
    // of the connect race: any background fetch calling getOrConnectRelay during the
    // scheduler's backoff revives the socket first, and without this set the scheduler
    // would then see "already connected" and never re-arm the group's mux/chat REQs —
    // messages silently stop arriving live until an app restart.
    private val relaysNeedingResubscribe = mutableSetOf<String>()

    // Centralised reconnect scheduler for every previously-connected relay, focused
    // included (see ConnectionManager.onPoolRelayLost wiring in initialize()). Only
    // relays in connectedPoolRelays are scheduled for reconnection. Resubscribing is NOT
    // done here: getOrConnectRelay fires onRelayConnected for whichever caller lands the
    // socket (this scheduler included), and that hook re-arms the subs exactly once.
    // On connectionWorkScope (not the shared app scope): clearAll() on logout cancels
    // pending retries without touching unrelated app-wide collectors.
    private val relayReconnectScheduler = RelayReconnectScheduler(
        scope = connectionManager.connectionWorkScope,
        isRelayActive = { relayUrl -> relayUrl in connectedPoolRelays },
        doReconnect = { relayUrl ->
            connectionManager.getOrConnectRelay(relayUrl) { msg, c ->
                enqueueToRelayPipeline(msg, c)
            } != null
        },
    )

    private val _isInitialized = MutableStateFlow(false)
    override val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    // Expose connection state
    override val currentRelayUrl: StateFlow<String> = connectionManager.currentRelayUrl
    override val connectionState: StateFlow<ConnectionManager.ConnectionState> = connectionManager.connectionState
    private val _isDiscoveringRelays = MutableStateFlow(false)
    override val isDiscoveringRelays: StateFlow<Boolean> = _isDiscoveringRelays.asStateFlow()
    private val _pendingDeepLinkRelay = MutableStateFlow<String?>(null)
    override val pendingDeepLinkRelay: StateFlow<String?> = _pendingDeepLinkRelay.asStateFlow()

    // Expose group state
    override val groups: StateFlow<List<GroupMetadata>> = groupManager.groups
    override val groupsByRelay: StateFlow<Map<String, List<GroupMetadata>>> = groupManager.groupsByRelay
    override val messages: StateFlow<Map<String, List<NostrGroupClient.NostrMessage>>> = groupManager.messages
    override val messageStatus: StateFlow<Map<String, GroupManager.MessageStatus>> = groupManager.messageStatus
    override val threadRoots: StateFlow<Map<String, List<NostrGroupClient.NostrMessage>>> = groupManager.threadRoots
    override val threadReplies: StateFlow<Map<String, List<NostrGroupClient.NostrMessage>>> = groupManager.threadReplies
    override val threadsLoaded: StateFlow<Set<String>> = groupManager.threadsLoaded
    override val joinedGroups: StateFlow<Set<String>> = groupManager.joinedGroups
    override val joinedGroupsByRelay: StateFlow<Map<String, Set<String>>> = groupManager.joinedGroupsByRelay
    override val privateGroupEntries: StateFlow<Set<Pair<String, String>>> = outboxManager.privateGroupEntries
    override val privateListSectionOpaque: StateFlow<Boolean> = outboxManager.privateSectionOpaque
    override val loadingRelays: StateFlow<Set<String>> = groupManager.loadingRelays
    private val _restrictedRelays = MutableStateFlow<Map<String, String>>(emptyMap())
    override val restrictedRelays: StateFlow<Map<String, String>> = _restrictedRelays.asStateFlow()
    override val isLoadingMore: StateFlow<Map<String, Boolean>> = groupManager.isLoadingMore
    override val hasMoreMessages: StateFlow<Map<String, Boolean>> = groupManager.hasMoreMessages
    override val groupStates: StateFlow<Map<String, org.nostr.nostrord.network.managers.GroupLoadingState>> = groupManager.groupStates
    override val groupsAwaitingAuthRead: StateFlow<Set<String>> = groupManager.groupsAwaitingAuthRead

    override suspend fun resetGroupLoadingState(groupId: String) {
        groupManager.resetLoadingForGroups(listOf(groupId))
    }
    override val reactions: StateFlow<Map<String, Map<String, GroupManager.ReactionInfo>>> = groupManager.reactions

    // NIP-57 zap totals per zapped event id.
    override val zaps: StateFlow<Map<String, ZapManager.ZapInfo>> = zapManager.zaps
    override val groupMembers: StateFlow<Map<String, List<String>>> = groupManager.groupMembers
    override val groupMembersByRelay: StateFlow<Map<String, Map<String, List<String>>>> = groupManager.groupMembersByRelay
    override val pendingApprovalSince: StateFlow<Map<String, Long>> = groupManager.pendingApprovalSince
    override val groupAdmins: StateFlow<Map<String, List<String>>> = groupManager.groupAdmins
    override val groupAdminsByRelay: StateFlow<Map<String, Map<String, List<String>>>> = groupManager.groupAdminsByRelay
    override val groupRoles: StateFlow<Map<String, List<RoleDefinition>>> = groupManager.groupRoles
    override val groupRolesByRelay: StateFlow<Map<String, Map<String, List<RoleDefinition>>>> = groupManager.groupRolesByRelay
    override val loadingMembers: StateFlow<Set<String>> = groupManager.loadingMembers
    override val restrictedGroups: StateFlow<Map<String, String>> = groupManager.restrictedGroups
    override val leftGroups: StateFlow<Map<String, Set<String>>> = groupManager.leftGroups
    override val pendingGroupInvites: StateFlow<Map<String, PendingGroupInvite>> = groupManager.pendingGroupInvites

    override suspend fun acceptGroupInvite(groupId: String) {
        groupManager.acceptPendingInvite(groupId)
    }

    override suspend fun addGroupToMyList(groupId: String, relayUrl: String?) {
        groupManager.addRelaySideMembershipToList(groupId, relayUrl)
    }

    // Expose auth state
    override val isLoggedIn: StateFlow<Boolean> = sessionManager.isLoggedIn

    // Active account's pubkey, derived from the session swap so it changes on every
    // account switch. Screens key their per-account loading state off this.
    override val activePubkey: StateFlow<String?> =
        ActiveAccountManager.session
            .map { it?.pubkey }
            .stateIn(scope, SharingStarted.Eagerly, ActiveAccountManager.currentPubkey)
    override val isBunkerConnected: StateFlow<Boolean> = sessionManager.isBunkerConnected
    override val isBunkerVerifying: StateFlow<Boolean> = sessionManager.isBunkerVerifying
    override val bunkerState: StateFlow<BunkerState> = sessionManager.bunkerState
    override val authUrl: StateFlow<String?> = sessionManager.authUrl
    override val pendingUnlockAccount: StateFlow<Account?> = sessionManager.pendingUnlock

    override fun clearPendingUnlock() = sessionManager.clearPendingUnlock()

    // Expose metadata state
    override val userMetadata: StateFlow<Map<String, UserMetadata>> = metadataManager.userMetadata
    override val cachedEvents: StateFlow<Map<String, CachedEvent>> = metadataManager.cachedEvents

    // Expose NIP-65 state
    override val userRelayList: StateFlow<List<Nip65Relay>> = outboxManager.userRelayList

    // Expose unread state
    override val unreadByGroupKey: StateFlow<Map<String, Int>> = unreadManager.unreadByGroupKey
    override val latestMessageTimestamps: StateFlow<Map<String, Long>> = unreadManager.latestMessageTimestamps

    // Filtered to relays the UI can actually show (rail's source list:
    // kind:10009 ∪ group-tag relays ∪ current). Without this, joined groups
    // on relays the user can't navigate to would silently inflate the title
    // counter ("(2) Nostrord" with no visible badge anywhere).
    override val unreadByRelay: StateFlow<Map<String, Int>> = combine(
        groupManager.joinedGroupsByRelay,
        unreadManager.unreadByGroupKey,
        outboxManager.kind10009Relays,
        outboxManager.groupTagRelays,
        connectionManager.currentRelayUrl,
    ) { joined, counts, kind10009, groupTags, current ->
        val visible = (kind10009 + groupTags + setOf(current))
            .filter { it.isNotBlank() }
            .map { it.normalizeRelayUrl() }
            .toSet()
        joined
            .filterKeys { it in visible }
            .mapValues { (relay, ids) -> ids.sumOf { counts[groupKey(relay, it)] ?: 0 } }
            .filterValues { it > 0 }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    override val totalUnread: StateFlow<Int> = unreadByRelay
        .map { it.values.sum() }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    // Expose NIP-11 relay metadata
    private val _relayMetadataManager = relayMetadataManager ?: RelayMetadataManager(scope)
    override val relayMetadata: StateFlow<Map<String, Nip11RelayInfo>> = _relayMetadataManager.relayMetadata

    // Relays we could not reach (normalized URLs): the WebSocket connect failed, or
    // the NIP-11 HTTP fetch exhausted its retries, AND no socket is currently up. A
    // relay whose socket connected is always reachable even if its NIP-11 is missing
    // (many NIP-29 relays do not serve a NIP-11 document). Discovery surfaces hide
    // groups hosted on these.
    override val unreachableRelays: StateFlow<Set<String>> =
        combine(
            connectionManager.relayReachability,
            _relayMetadataManager.failedRelays,
        ) { reachability, nip11Failed ->
            val socketOk = reachability.filterValues { it }.keys
            val socketFailed = reachability.filterValues { !it }.keys.toSet()
            val nip11Bad = nip11Failed.map { it.normalizeRelayUrl() }.toSet()
            (socketFailed + (nip11Bad - socketOk)).toSet()
        }.stateIn(scope, SharingStarted.Eagerly, emptySet())
    override val kind10009Relays: StateFlow<Set<String>> = outboxManager.kind10009Relays
    override val groupTagRelays: StateFlow<Set<String>> = outboxManager.groupTagRelays
    override val groupOrder: StateFlow<List<Pair<String, String>>> = outboxManager.groupOrder

    // Public kind:10009 group lists of OTHER users (profile pages). Newest event
    // wins per pubkey; the active account's own list never lands here.
    private val _userGroupLists = MutableStateFlow<Map<String, List<UserGroupRef>>>(emptyMap())
    override val userGroupLists: StateFlow<Map<String, List<UserGroupRef>>> = _userGroupLists.asStateFlow()

    private val userGroupListsSerializer =
        MapSerializer(String.serializer(), ListSerializer(UserGroupRef.serializer()))
    private var userGroupListsPersistJob: Job? = null

    /** Most recently-seen pubkeys to keep when snapshotting the kind:10009 cache to disk. */
    private val userGroupListsCacheCap = 500

    /** Hydrate other users' kind:10009 lists from disk so discovery tabs render from cache. */
    private fun restoreUserGroupListsFromCache() {
        try {
            val cached = SecureStorage.getUserGroupListsCache()
            if (cached.isNullOrBlank()) return
            val map = json.decodeFromString(userGroupListsSerializer, cached)
            if (map.isNotEmpty()) _userGroupLists.value = map
        } catch (_: Exception) {
            // Corrupted cache — start fresh.
        }
    }

    /** Debounced snapshot of the (recency-capped) kind:10009 cache to the global on-disk store. */
    private fun scheduleUserGroupListsPersist() {
        userGroupListsPersistJob?.cancel()
        userGroupListsPersistJob =
            scope.launch {
                delay(5_000)
                val snapshot = _userGroupLists.value
                val capped =
                    if (snapshot.size <= userGroupListsCacheCap) {
                        snapshot
                    } else {
                        snapshot.entries.toList().takeLast(userGroupListsCacheCap).associate { it.toPair() }
                    }
                if (capped.isEmpty()) return@launch
                try {
                    SecureStorage.saveUserGroupListsCache(json.encodeToString(userGroupListsSerializer, capped))
                } catch (_: Exception) {
                }
            }
    }
    private val userGroupListCreatedAt = mutableMapOf<String, Long>()

    // The active account's own NIP-02 contact list (kind:3). [following] is the set
    // of "p"-tagged pubkeys; the raw tags + content are kept so follow/unfollow
    // re-publish on top of the latest known list without dropping relay hints,
    // petnames, or the (legacy) relay-JSON content.
    private val _following = MutableStateFlow<Set<String>>(emptySet())
    override val following: StateFlow<Set<String>> = _following.asStateFlow()

    // True once the active account's kind:3 has actually loaded (arrived from a relay,
    // been published by us, or the fetch resolved as "no list"). Lets the UI tell
    // "still loading" apart from "follows nobody", so an empty [following] after the
    // user unfollows everyone is shown as empty instead of falling back to a stale cache.
    private val _contactListLoaded = MutableStateFlow(false)
    override val contactListLoaded: StateFlow<Boolean> = _contactListLoaded.asStateFlow()
    private var contactListCreatedAt = 0L
    private var contactListContent = ""
    private var contactListTags: List<List<String>> = emptyList()
    private var contactListRequested = false
    private val contactListMutex = Mutex()

    // Retries the kind:3 REQ until a relay actually accepts it. On a cold-start login
    // (especially bunker, whose connect is signer-gated and slow) the screen's one-shot
    // requestContactList() can fire before any relay is up, send nothing, and leave the
    // friends sidebar on "follows nobody" until the app is reopened. This loop self-heals
    // that without a restart. Cancelled on account switch by resetContactListState.
    private var contactListRetryJob: Job? = null

    // Debounced kind:3 publisher. [following] holds the user's desired set (flipped
    // optimistically on each tap); rapid taps coalesce into a single publish.
    private var pendingContactListPublish: Job? = null
    private var hasUnpublishedContactChanges = false
    private val contactListPublishDebounceMs = 700L

    private fun followsFrom(tags: List<List<String>>): Set<String> = tags
        .filter { it.firstOrNull() == "p" }
        .mapNotNull { it.getOrNull(1)?.takeIf { pk -> pk.isNotBlank() } }
        .toSet()

    private fun handleKind3Event(event: JsonObject) {
        // Only the active account's own list drives [following]; other users' kind:3
        // events (some relays gossip them) are ignored here.
        val pubKey = sessionManager.getPublicKey() ?: return
        val eventPubkey = event["pubkey"]?.jsonPrimitive?.contentOrNull ?: return
        if (eventPubkey != pubKey) return
        val createdAt = event["created_at"]?.jsonPrimitive?.longOrNull ?: 0L
        if (createdAt < contactListCreatedAt) return
        val tags =
            event["tags"]?.jsonArray.orEmpty().map { tag ->
                tag.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
            }
        contactListCreatedAt = createdAt
        contactListContent = event["content"]?.jsonPrimitive?.contentOrNull ?: ""
        contactListTags = tags
        contactListRequested = true
        _contactListLoaded.value = true
        // Keep the optimistic [following] when there are local taps not yet published,
        // so a relay echo arriving mid-follow doesn't clobber a just-tapped follow.
        if (!hasUnpublishedContactChanges) {
            _following.value = followsFrom(tags)
            // Persist so the next cold boot can seed the follow set (and the DM Follows/Others
            // split) instantly, regardless of which screen wrote it last.
            SecureStorage.saveFollowingCacheFor(pubKey, _following.value.toList())
        }
    }

    // ===== NIP-51 mute list (kind:10000) =====
    // Mirrors the kind:3 handling above: optimistic local sets + debounced publish,
    // per-account persistence, staleness-guarded ingest. New mutes go to the PRIVATE
    // section (NIP-44 self-encrypted `content`); public `p` tags written by other clients
    // are honored for filtering and kept public. Invariant: a `content` we could not
    // decrypt is republished verbatim, never rebuilt, so another client's private data
    // cannot be destroyed from here.

    private val _mutedPubkeys = MutableStateFlow<Set<String>>(emptySet())
    override val mutedPubkeys: StateFlow<Set<String>> = _mutedPubkeys.asStateFlow()
    private var publicMuted: Set<String> = emptySet()
    private var privateMuted: Set<String> = emptySet()
    private var muteListCreatedAt = 0L
    private var muteListContent = ""

    // Shared by every self-encrypted list (kind:10000, kind:10009, kind:30078): one decrypt
    // per distinct ciphertext, however many relays deliver a copy of the same event.
    private val selfDecryptCache = SelfDecryptCache()

    private var muteListPublicTags: List<List<String>> = emptyList()

    // Decrypted private-section tags, valid only for the ciphertext they came from:
    // when muteListContent no longer matches, the section is treated as unreadable.
    private var muteListPrivateTags: List<List<String>> = emptyList()
    private var muteListPrivateDecryptedFrom = ""
    private var muteListRequested = false

    // Newest private section seen, and the job reading it. Same reason as the kind:30078
    // pair: the decrypted-from marker only moves after a decrypt, so it cannot be the
    // freshness floor while a burst of relay copies is arriving.
    private var muteListPendingPrivate: String? = null
    private var muteListPrivateApplyJob: Job? = null
    private var pendingMuteListPublish: Job? = null
    private var hasUnpublishedMuteChanges = false
    private val muteListMutex = Mutex()

    /** Full last-known list, persisted so a publish after restart never rebuilds from a partial base. */
    @Serializable
    private data class MuteListSnapshot(
        val createdAt: Long,
        val publicTags: List<List<String>>,
        val content: String,
        val privateTags: List<List<String>>,
        val privateDecryptedFrom: String,
    )

    /** True when the private section is safe to rebuild (absent, or we decrypted this exact ciphertext). */
    private fun muteListPrivateWritable() = muteListContent.isBlank() || muteListContent == muteListPrivateDecryptedFrom

    private fun applyMutedSetsAndPersist(pubkey: String) {
        _mutedPubkeys.value = publicMuted + privateMuted
        try {
            SecureStorage.saveMuteListSnapshotFor(
                pubkey,
                Json.encodeToString(
                    MuteListSnapshot(
                        createdAt = muteListCreatedAt,
                        publicTags = muteListPublicTags,
                        content = muteListContent,
                        privateTags = muteListPrivateTags,
                        privateDecryptedFrom = muteListPrivateDecryptedFrom,
                    ),
                ),
            )
        } catch (_: Exception) {
        }
        SecureStorage.saveKind10000TimestampFor(pubkey, muteListCreatedAt)
    }

    private fun handleKind10000Event(event: JsonObject) {
        // Only the active account's own list drives [mutedPubkeys].
        val pubKey = sessionManager.getPublicKey() ?: return
        val eventPubkey = event["pubkey"]?.jsonPrimitive?.contentOrNull ?: return
        if (eventPubkey != pubKey) return
        val createdAt = event["created_at"]?.jsonPrimitive?.longOrNull ?: 0L
        // muteListCreatedAt is floored by the persisted timestamp (hydrateMuteListFromCache),
        // so a lagging relay can't resurrect mutes removed in a previous session.
        if (createdAt < muteListCreatedAt) return
        val tags =
            event["tags"]?.jsonArray.orEmpty().map { tag ->
                tag.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
            }
        val content = event["content"]?.jsonPrimitive?.contentOrNull ?: ""
        muteListCreatedAt = createdAt
        muteListPublicTags = tags
        val contentChanged = content != muteListContent
        muteListContent = content
        if (content.isBlank()) {
            muteListPrivateTags = emptyList()
            muteListPrivateDecryptedFrom = ""
        }
        muteListRequested = true
        // Keep the optimistic sets while local taps are unpublished, so a relay echo
        // arriving mid-mute doesn't clobber a just-tapped mute.
        if (hasUnpublishedMuteChanges) return
        publicMuted = org.nostr.nostrord.nostr.Nip51.mutedPubkeysFrom(tags)
        if (content.isBlank()) {
            privateMuted = emptySet()
            applyMutedSetsAndPersist(pubKey)
            return
        }
        applyMutedSetsAndPersist(pubKey)
        if (!contentChanged && content == muteListPrivateDecryptedFrom) return
        muteListPendingPrivate = content
        scheduleMuteListPrivateApply(pubKey)
    }

    /**
     * Decrypts and applies the private mute section off the ingest path (a bunker signer is a
     * remote round-trip), once the burst of relay copies has settled.
     *
     * Single-flight on purpose: a newer version landing mid-decrypt is picked up by the loop
     * rather than starting a second one, so a prompting signer never shows two dialogs for
     * one list. A ciphertext that is NIP-04-era, foreign, or already refused reads as null:
     * the section stays opaque and the writable() guard keeps it verbatim on publish.
     */
    private fun scheduleMuteListPrivateApply(pubKey: String) {
        if (muteListPrivateApplyJob?.isActive == true) return
        muteListPrivateApplyJob = scope.launch {
            // Slower relays get to land their newer version before anything is decrypted.
            delay(REPLACEABLE_SETTLE_MS)
            while (true) {
                val content = muteListPendingPrivate ?: return@launch
                if (content == muteListPrivateDecryptedFrom) return@launch
                val plaintext = decryptOwnSection(pubKey, content) ?: return@launch
                val privateTags = org.nostr.nostrord.nostr.Nip51.decodeTags(plaintext) ?: return@launch
                if (sessionManager.getPublicKey() != pubKey) return@launch
                // Superseded while decrypting (newer event, local tap): only the version the
                // list currently carries may be applied.
                if (hasUnpublishedMuteChanges) return@launch
                if (muteListContent == content) {
                    muteListPrivateTags = privateTags
                    muteListPrivateDecryptedFrom = content
                    privateMuted = org.nostr.nostrord.nostr.Nip51.mutedPubkeysFrom(privateTags)
                    applyMutedSetsAndPersist(pubKey)
                }
                if (muteListPendingPrivate == content) return@launch
            }
        }
    }

    // ===== NIP-78 notification preferences (kind:30078) =====
    // Per-group notification levels follow the account across devices. The payload is
    // NIP-44 self-encrypted: it is a list of NIP-29 group ids, which in plaintext would
    // hand any relay the account's group membership.

    private var notifPrefsCreatedAt = 0L

    // Newest version seen, whether or not it has been decrypted yet, and its ciphertext.
    // The applied timestamp above cannot serve as the freshness floor during a burst: it
    // only rises after a decrypt, which with a prompting signer is the user's response time.
    private var notifPrefsNewestSeenAt = 0L
    private var notifPrefsPendingContent: String? = null
    private var notifPrefsApplyJob: Job? = null

    /**
     * The override map as last agreed with the network: what this device published, or
     * what it last accepted from another one. A change is only worth publishing when it
     * differs from this.
     *
     * This is a content comparison rather than an "applying remote" flag on purpose.
     * Writing an ingested payload into the settings makes [NotificationSettings.muteState]
     * emit, and the watcher below observes that emission from another coroutine — after
     * the ingest has already returned. Any flag raised around the write is back down by
     * then, so the echo would be republished, prompting the signer on a device that only
     * received the change and bouncing the event back to its origin.
     *
     * Null means "unknown", which makes the next change publish.
     */
    private var notifPrefsSynced: Map<String, NotificationLevel>? = null

    /** True while a publish is in flight, so an arriving remote event doesn't race it. */
    private var notifPrefsPublishing = false

    private var notifPrefsPublishJob: Job? = null
    private var notifPrefsWatchJob: Job? = null
    private var notifPrefsFocusJob: Job? = null
    private var notifPrefsTickJob: Job? = null

    /**
     * Starts mirroring the account's per-group notification levels to kind:30078.
     * Re-armed per account: the previous account's watcher is cancelled first, so a
     * switch can never publish account A's prefs under account B.
     */
    private fun startNotificationPrefsSync(pubkey: String) {
        val settings = notificationSettings ?: return
        notifPrefsWatchJob?.cancel()
        notifPrefsPublishJob?.cancel()
        notifPrefsApplyJob?.cancel()
        notifPrefsPendingContent = null
        notifPrefsPublishing = false
        notifPrefsCreatedAt = SecureStorage.loadKind30078TimestampFor(pubkey, Nip78.D_NOTIFICATIONS)
        notifPrefsNewestSeenAt = notifPrefsCreatedAt
        // A change only survives once it is published, so whatever is on disk is by
        // definition the last state that reached the network.
        notifPrefsSynced = settings.muteState.value.overrides
        notifPrefsWatchJob = scope.launch {
            // drop(1): the current value is what initialize() just loaded from disk, not a change.
            settings.muteState.drop(1).collect { state ->
                if (state.overrides == notifPrefsSynced) return@collect
                schedulePublishNotificationPrefs()
            }
        }
        notifPrefsFocusJob?.cancel()
        notifPrefsFocusJob = scope.launch {
            // Pull on every focus gain, so switching to this device shows what another one
            // changed. drop(1) skips the tracker's current value; the initial fetch below
            // covers startup. Web hooks real window focus/blur, which is what makes two
            // apps side by side converge on a click; desktop only reports lifecycle stops,
            // hence the tick below.
            AppModule.focusTracker.isAppFocused.drop(1).collect { focused ->
                if (focused) refreshNotificationPrefs()
            }
        }
        notifPrefsTickJob?.cancel()
        notifPrefsTickJob = scope.launch {
            refreshNotificationPrefs()
            while (true) {
                delay(NOTIF_PREFS_TICK_MS)
                // Only while the user is here: a backgrounded app converges on its next
                // focus instead of polling relays it isn't showing.
                if (AppModule.focusTracker.isAppFocused.value) refreshNotificationPrefs()
            }
        }
    }

    // Throttle for the focus-driven kind:30078 re-fetch (seconds).
    private var lastNotifPrefsFetchAt = 0L

    /**
     * Re-request the account's kind:30078 from the general-purpose relays.
     *
     * A standing REQ is not enough on its own: the filter carries a `limit`, and whether
     * a relay keeps pushing new matches afterwards is not something to depend on. This
     * runs whenever the window regains focus, which is the moment the user looks at this
     * device after changing something on another one — including two apps open side by
     * side, where the browser's visibilitychange never fires.
     */
    private suspend fun refreshNotificationPrefs() {
        val pubKey = sessionManager.getPublicKey() ?: return
        val now = epochSeconds()
        if (now - lastNotifPrefsFetchAt < NOTIF_PREFS_FETCH_MIN_INTERVAL_S) return
        lastNotifPrefsFetchAt = now
        val nip29Relays = outboxManager.kind10009Relays.value + connectionManager.currentRelayUrl.value
        val targets = (outboxManager.getWriteRelays() + outboxManager.bootstrapRelays)
            .distinct()
            .filter { it !in nip29Relays }
        targets.forEach { relayUrl ->
            runCatching {
                val client = connectionManager.getClientForRelay(relayUrl)?.takeIf { it.isConnected() }
                    ?: connectionManager.getOrConnectRelay(relayUrl, metadataMessageHandler)
                // Slack on the live window: the publishing device's clock may sit slightly
                // behind ours, and an event stamped a few seconds "ago" must not fall
                // outside the subscription that is supposed to catch it.
                client?.takeIf { it.isConnected() }?.requestNotificationPrefs(pubKey, now - NOTIF_PREFS_LIVE_SLACK_S)
            }
        }
    }

    private fun stopNotificationPrefsSync() {
        notifPrefsWatchJob?.cancel()
        notifPrefsWatchJob = null
        notifPrefsFocusJob?.cancel()
        notifPrefsFocusJob = null
        notifPrefsTickJob?.cancel()
        notifPrefsTickJob = null
        notifPrefsPublishJob?.cancel()
        notifPrefsPublishJob = null
        notifPrefsPublishing = false
        notifPrefsCreatedAt = 0L
        notifPrefsSynced = null
    }

    /**
     * Puts the levels back to the last state that reached the network, after a change
     * failed to publish. [notifPrefsSynced] already holds that state, so the watcher
     * sees the restored value as agreed and does not try to publish the undo.
     */
    private fun revertNotificationPrefs(reason: String) {
        val settings = notificationSettings ?: return
        val synced = notifPrefsSynced ?: return
        if (settings.muteState.value.overrides == synced) return
        settings.applyRemoteGroupLevels(synced)
        AppModule.postSystemMessage(reason)
    }

    /**
     * Coalesces a burst of level changes into one event, so toggling three groups in a
     * row is one signature prompt and one replaceable event rather than three racing at
     * the relay. Kept short: with a NIP-07 or bunker signer this delay is the gap between
     * the click and the extension's prompt, and a long one reads as an unprompted popup.
     */
    private fun schedulePublishNotificationPrefs() {
        notifPrefsPublishJob?.cancel()
        notifPrefsPublishJob = scope.launch {
            delay(NOTIF_PREFS_PUBLISH_DEBOUNCE_MS)
            publishNotificationPrefs()
        }
    }

    /**
     * A level change only stands once its kind:30078 is signed and accepted by a relay;
     * anything else puts the levels back. That is the user's chosen contract: declining
     * the signing prompt must not leave the app acting on a change it was told not to
     * make. The cost is that muting with no signer or no reachable relay fails too,
     * because NIP-07 surfaces a refusal and an error the same way.
     */
    private suspend fun publishNotificationPrefs() {
        val settings = notificationSettings ?: return
        val pubKey = sessionManager.getPublicKey()
            ?: return revertNotificationPrefs("Mute undone: not signed in.")
        val state = settings.muteState.value
        val signer = ActiveAccountManager.session.value?.signer
            ?: return revertNotificationPrefs("Mute undone: no signer available to sync it.")
        notifPrefsPublishing = true
        try {
            val content = signer.nip44Encrypt(
                pubKey,
                Nip78.encodeNotifications(state.defaultLevel, state.overrides),
            )
            val event = Event(
                pubkey = pubKey,
                createdAt = epochSeconds(),
                kind = Nip78.KIND_APP_DATA,
                tags = listOf(listOf("d", Nip78.D_NOTIFICATIONS)),
                content = content,
            )
            val signedEvent = sessionManager.signEvent(event)
            val eventId = signedEvent.id
                ?: return revertNotificationPrefs("Mute undone: the event could not be signed.")
            val message = buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()
            if (publishToGeneralPurposeRelays(message, eventId) is Result.Success) {
                notifPrefsCreatedAt = signedEvent.createdAt
                SecureStorage.saveKind30078TimestampFor(pubKey, Nip78.D_NOTIFICATIONS, signedEvent.createdAt)
                // Exactly the snapshot that went out, so our own relay echo is a no-op.
                notifPrefsSynced = state.overrides
            } else {
                revertNotificationPrefs("Mute undone: no relay accepted the change.")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Declined prompt, or a signer that can't NIP-44. Indistinguishable here, and
            // both mean the change was never agreed to.
            revertNotificationPrefs("Mute undone: the signing request was not approved.")
        } finally {
            notifPrefsPublishing = false
        }
    }

    /**
     * Ingests the account's own kind:30078 notification prefs. Last write wins on
     * created_at and replaces the whole map, which is what a replaceable event means:
     * a union-merge would resurrect a group unmuted on another device.
     */
    private fun handleKind30078Event(event: JsonObject) {
        val settings = notificationSettings ?: return
        val pubKey = sessionManager.getPublicKey() ?: return
        if (event["pubkey"]?.jsonPrimitive?.contentOrNull != pubKey) return
        val tags = event["tags"]?.jsonArray.orEmpty().map { tag ->
            tag.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        }
        val dTag = tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)
        if (dTag != Nip78.D_NOTIFICATIONS) return
        // A publish is mid-flight (the user may be staring at a signing prompt): let it
        // settle rather than applying a payload it is about to supersede or be reverted to.
        if (notifPrefsPublishing) return
        val createdAt = event["created_at"]?.jsonPrimitive?.longOrNull ?: 0L
        // <=, not <: a same-second replay carries no new information, and applying it
        // would fight a local change made in that same second.
        if (createdAt <= notifPrefsCreatedAt) return
        val content = event["content"]?.jsonPrimitive?.contentOrNull ?: return
        if (content.isBlank()) return
        // Recorded synchronously, before any decrypt: relays hold different versions of a
        // replaceable event, and the applied-timestamp below only rises once a decrypt has
        // finished. Without this floor every version in the burst reaches the signer, and
        // all but the newest are discarded after the user answered for them.
        if (createdAt <= notifPrefsNewestSeenAt) return
        notifPrefsNewestSeenAt = createdAt
        notifPrefsPendingContent = content
        scheduleNotifPrefsApply(pubKey, settings)
    }

    /**
     * Decrypts and applies the newest kind:30078 seen, once the burst has settled.
     *
     * Single-flight on purpose: a version landing while a decrypt is in flight is picked up
     * by the loop below rather than starting a second one. Cancelling the in-flight decrypt
     * instead would abandon a signer dialog the user is looking at and raise another.
     */
    private fun scheduleNotifPrefsApply(
        pubKey: String,
        settings: org.nostr.nostrord.settings.NotificationSettings,
    ) {
        if (notifPrefsApplyJob?.isActive == true) return
        notifPrefsApplyJob = scope.launch {
            // Slower relays get to land their newer version before anything is decrypted.
            delay(REPLACEABLE_SETTLE_MS)
            while (true) {
                val createdAt = notifPrefsNewestSeenAt
                val content = notifPrefsPendingContent ?: return@launch
                if (createdAt <= notifPrefsCreatedAt) return@launch
                if (notifPrefsPublishing) return@launch
                val plaintext = decryptOwnSection(pubKey, content)
                if (plaintext == null) {
                    // Unread (signer refused, or was not reachable yet). Drop the floor so a
                    // later delivery may try again; the decrypt cache is what keeps that retry
                    // from reaching the signer while the refusal is still fresh.
                    if (notifPrefsNewestSeenAt == createdAt) {
                        notifPrefsNewestSeenAt = notifPrefsCreatedAt
                        return@launch
                    }
                    continue // a newer version landed meanwhile: read that one instead
                }
                val decoded = Nip78.decodeNotifications(plaintext) ?: return@launch
                // Superseded while decrypting (newer event, local change, account switch).
                if (createdAt <= notifPrefsCreatedAt) return@launch
                if (notifPrefsPublishing) return@launch
                if (sessionManager.getPublicKey() != pubKey) return@launch
                notifPrefsCreatedAt = createdAt
                SecureStorage.saveKind30078TimestampFor(pubKey, Nip78.D_NOTIFICATIONS, createdAt)
                // Recorded BEFORE the write: applying it makes muteState emit, and the watcher
                // must already see this payload as the agreed state or it republishes it.
                notifPrefsSynced = decoded.groupLevels
                settings.applyRemoteGroupLevels(decoded.groupLevels)
                // A newer version landed while this one was being read: take it now.
                if (notifPrefsNewestSeenAt <= createdAt) return@launch
            }
        }
    }

    /**
     * Publishes a signed replaceable event to the account's general-purpose relays.
     *
     * Same routing as kind:3: write + bootstrap relays, never the NIP-29 relays (they
     * don't serve replaceable lists back). Succeeds if any one relay accepts.
     */
    private suspend fun publishToGeneralPurposeRelays(
        message: String,
        eventId: String,
    ): Result<Unit> {
        val nip29Relays = outboxManager.kind10009Relays.value + connectionManager.currentRelayUrl.value
        val targets = (outboxManager.getWriteRelays() + outboxManager.bootstrapRelays)
            .distinct()
            .filter { it !in nip29Relays }
        val clients = targets.mapNotNull { relayUrl ->
            connectionManager.getClientForRelay(relayUrl)?.takeIf { it.isConnected() }
                ?: connectionManager.getOrConnectRelay(relayUrl, metadataMessageHandler)?.takeIf { it.isConnected() }
        }
        if (clients.isEmpty()) {
            return Result.Error(AppError.Network.Disconnected(connectionManager.currentRelayUrl.value))
        }
        val results = clients.map { client ->
            scope.async { client.sendAndAwaitOkOrError(message, eventId) }
        }.awaitAll()
        if (results.none { it is PublishResult.Success }) {
            return Result.Error(AppError.Network.PublishRejected(results.summarizeFailures()))
        }
        return Result.Success(Unit)
    }

    /** Seed the mute state from the per-account snapshot so filtering works before the network answers. */
    private fun hydrateMuteListFromCache(pubkey: String) {
        muteListCreatedAt = SecureStorage.loadKind10000TimestampFor(pubkey)
        val snapshot =
            SecureStorage.loadMuteListSnapshotFor(pubkey)?.let {
                try {
                    Json.decodeFromString<MuteListSnapshot>(it)
                } catch (_: Exception) {
                    null
                }
            }
        if (snapshot == null) {
            _mutedPubkeys.value = emptySet()
            return
        }
        muteListCreatedAt = maxOf(muteListCreatedAt, snapshot.createdAt)
        muteListPublicTags = snapshot.publicTags
        muteListContent = snapshot.content
        muteListPrivateTags = snapshot.privateTags
        muteListPrivateDecryptedFrom = snapshot.privateDecryptedFrom
        publicMuted = org.nostr.nostrord.nostr.Nip51.mutedPubkeysFrom(snapshot.publicTags)
        privateMuted = org.nostr.nostrord.nostr.Nip51.mutedPubkeysFrom(snapshot.privateTags)
        _mutedPubkeys.value = publicMuted + privateMuted
    }

    override fun forceInitialized() {
        _isInitialized.value = true
    }

    override suspend fun initialize() {
        // Wire up connection lifecycle callbacks once — avoids overwriting them on every connect().
        connectionManager.onConnectionDropped = {
            scope.launch { groupManager.handleConnectionLost() }
        }
        connectionManager.onPoolRelayLost = { relayUrl ->
            // A relay that just dropped while focused is, by definition, one the user
            // cares about reconnecting — mark it active the same way a pool connect does
            // (see the getOrConnectRelay call sites below) so isRelayActive keeps retrying
            // it in the slow phase even after focus moves elsewhere.
            val isFocused = relayUrl == connectionManager.currentRelayUrl.value
            if (isFocused) connectedPoolRelays.add(relayUrl)
            // Whatever live subs this socket carried are gone with it — re-arm them on the
            // next successful connect, whichever caller lands it (see onRelayConnected).
            relaysNeedingResubscribe.add(relayUrl)
            // Only reconnect pool relays that were actively connected during this session
            // (had been focused at some point). Lazy pool relays are not reconnected.
            if (relayUrl in connectedPoolRelays) {
                val priority = if (isFocused) {
                    RelayReconnectScheduler.Priority.ACTIVE
                } else {
                    RelayReconnectScheduler.Priority.BACKGROUND
                }
                relayReconnectScheduler.schedule(
                    relayUrl,
                    priority = priority,
                    onAttempt = { attempt ->
                        connectionManager.reportReconnectAttempt(
                            relayUrl,
                            attempt,
                            RelayReconnectScheduler.MAX_FAST_ATTEMPTS,
                        )
                    },
                )
            }
        }
        connectionManager.onRelayConnected = { relayUrl, client ->
            // Consume-once: getOrConnectRelay's singleflight guarantees one landing per
            // URL at a time, so this fires at most once per drop.
            if (relaysNeedingResubscribe.remove(relayUrl)) {
                scope.launch {
                    // Same focused/pool split ConnectionManager itself uses: the focused
                    // relay's reconnect must also restore connectionState and run the full
                    // onReconnected pipeline (notifyReconnected does both).
                    if (relayUrl == connectionManager.currentRelayUrl.value) {
                        connectionManager.notifyReconnected(client)
                    } else {
                        resubscribePoolRelay(relayUrl, client)
                    }
                }
            }
            // Every landed socket (cold boot included — boot connects never fire
            // onReconnected) gives queued offline sends a chance to flush. Cheap no-op
            // when the queue is empty; per-event routing skips groups whose relay is
            // still down, so early boot passes don't burn retries.
            pendingEventManager?.onConnectionRestored()
            dmSendQueue.onConnectionRestored()
        }
        connectionManager.onReconnected = { client ->
            resubscribeAllGroups(client)
            pendingEventManager?.onConnectionRestored()
            dmSendQueue.onConnectionRestored()
            reconnectDroppedNip29PoolRelays()
            resubscribeDmInbox()
            scope.launch { refreshVisibleUserMetadata() }
        }

        // Clear the sidebar skeleton for a relay that becomes unreachable. EOSE never
        // arrives for a failed connection, so without this the loading flag would
        // pulse indefinitely while the offline management screen is already showing.
        scope.launch {
            connectionManager.connectionState.collect { state ->
                if (state is ConnectionManager.ConnectionState.Error ||
                    state is ConnectionManager.ConnectionState.Reconnecting
                ) {
                    val relay = connectionManager.currentRelayUrl.value
                    if (relay.isNotBlank()) {
                        groupManager.markRelayLoaded(relay)
                    }
                }
            }
        }

        // The set of groups we KNOW on the focused relay grows over time: friends'/curator
        // kind:10009 lists arrive after connect, and joining/creating a group (incl. a subgroup)
        // adds to joinedGroupsByRelay. When it grows, send a fresh targeted #d fetch so those
        // groups' metadata loads (name/picture/parent) — we never pull the full directory.
        scope.launch {
            // groupsByRelay is in the combine so a 39000 arriving with `child` tags
            // re-expands the known set and fetches the channels' own metadata.
            combine(_following, _userGroupLists, groupManager.joinedGroupsByRelay, groupManager.groupsByRelay) { _, _, _, _ -> Unit }.collect {
                val relay = connectionManager.currentRelayUrl.value
                if (relay.isBlank()) return@collect
                val client = connectionManager.getFocusedClient() ?: return@collect
                val ids = knownGroupIdsForRelay(relay).toSet()
                if (ids.isNotEmpty() && ids != sentKnownGroupFetch[relay.normalizeRelayUrl()]) {
                    groupManager.markRelayLoading(relay)
                    sendKnownGroupsFetch(client, relay)
                }
            }
        }

        // Open the NIP-17 DM inbox while logged in AND the DM feature is enabled (cold-boot
        // restore or a fresh login). Flipping the master toggle off tears the inbox down live —
        // stopDmInbox() closes the kind:1059 subscription so no more wraps are fetched or decrypted;
        // flipping it back on re-subscribes. startDmInbox()/stopDmInbox() both dedup on
        // dmInboxStarted, so redundant emissions are no-ops.
        scope.launch {
            combine(sessionManager.isLoggedIn, AppModule.dmSettings.dmEnabled) { loggedIn, enabled ->
                loggedIn && enabled
            }.distinctUntilChanged().collect { active ->
                if (active) startDmInbox() else stopDmInbox()
            }
        }

        // NIP-4e keys follow the session, not the DM inbox: whether the account signs remotely is
        // only knowable once its signer is installed, which can land after the inbox opens.
        scope.launch {
            ActiveAccountManager.session.collect { session ->
                dmPairingManager.clear()
                pendingPairingRequestId = null
                if (session == null) {
                    dmEncryptionManager.clear()
                    dmPairingManager.onProcessedChanged = null
                } else {
                    dmEncryptionManager.loadFor(session.pubkey, session.signer.isRemote)
                    val pubkey = session.pubkey
                    dmPairingManager.hydrateProcessed(SecureStorage.loadDmPairingProcessedFor(pubkey))
                    dmPairingManager.onProcessedChanged = { SecureStorage.saveDmPairingProcessedFor(pubkey, it) }
                }
            }
        }

        // Auto-forget confirmed orphan pins. A joined group still missing its kind:39000
        // after the hosting relay finished its group list (EOSE) was deleted, or was filed
        // under the wrong relay — it can never resolve, shows as a broken "No description"
        // card, and (being in storage) gets re-published into kind:10009 every time. Drop it
        // from the joined list + storage and republish so it stays gone. The guards
        // (recently-joined grace, relay-glitch protection) live in autoForgettableOrphans;
        // forgetJoinedPin updates joined, which re-runs this collector until it converges.
        scope.launch {
            groupManager.orphanedJoinedByRelay.collect {
                if (groupManager.autoForgettableOrphans().isEmpty()) return@collect
                // Settle before acting: a private group's kind:39000 arrives via
                // requestPrivateGroupData only AFTER AUTH, which can land after the public
                // group-list EOSE that flags it as an orphan. Wait, then RE-CHECK, so a
                // group that resolved during the window is never forgotten.
                delay(ORPHAN_FORGET_SETTLE_MS)
                val pubKey = sessionManager.getPublicKey() ?: return@collect
                val toForget = groupManager.autoForgettableOrphans()
                if (toForget.isEmpty()) return@collect
                var changed = false
                toForget.forEach { (relay, ids) ->
                    ids.forEach { id ->
                        if (groupManager.forgetJoinedPin(id, relay, pubKey)) changed = true
                    }
                }
                if (changed) publishJoinedGroupsList()
            }
        }

        // Membership granted by an admin's kind:9000 and ACCEPTED by the user: GroupManager
        // adopted it into the joined set; mirror it into our kind:10009 so it survives
        // restarts and reaches the account's other devices.
        scope.launch {
            groupManager.externalMembershipAdopted.collect {
                publishJoinedGroupsList()
            }
        }

        // A pending external add is in no group list yet; pull its kind:39000 so the
        // invite notification and the accept/decline prompt show the group's real name.
        scope.launch {
            groupManager.externalAddPending.collect { add ->
                try {
                    fetchGroupPreview(add.groupId, add.relayUrl)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {}
            }
        }

        // A kind:10009 publish no relay accepted (offline leave, zombie socket, or the
        // NIP-29-focused fallback rejecting it) retries on every (re)connect while the
        // pending flag holds — in memory for this session, persisted across restarts.
        // Always publishes the CURRENT list, never the lost snapshot (replaceable event).
        scope.launch {
            connectionManager.connectionState.collect { st ->
                if (st !is ConnectionManager.ConnectionState.Connected) return@collect
                val pk = sessionManager.getPublicKey() ?: return@collect
                // Reconnects drop the standing own-kind:10009 sub with the socket; re-arm it
                // so lists published by another device keep applying live.
                outboxManager.armKind10009LiveSub(pk)
                val pending = outboxManager.kind10009NeedsRepublish.value ||
                    try {
                        SecureStorage.isKind10009RepublishPendingFor(pk)
                    } catch (_: Exception) {
                        false
                    }
                if (!pending) return@collect
                // Let AUTH and the reconnect subscription burst settle first.
                delay(2_000)
                try {
                    publishJoinedGroupsList()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {}
            }
        }

        // Bunker (NIP-46) signer-ready recovery. On session restore / account-add
        // the account is marked logged-in optimistically while the remote signer
        // connects asynchronously (issue #85). connect() + NIP-42 AUTH for the
        // group relays run before the signer can sign the AUTH event, so their
        // group/mux REQs come back CLOSED "auth-required" and nothing retries —
        // groups stuck on "No messages yet" / "Members 0" until app restart.
        // When the signer transitions to ready, reconnect the group relays: fresh
        // sockets -> fresh AUTH (now signable) -> resubscribe (messages+members).
        // Interactive loginWithBunker connects the signer synchronously (verifying
        // never goes true), so this never fires spuriously for a fresh login.
        scope.launch {
            var wasReady = sessionManager.isBunkerConnected.value &&
                !sessionManager.isBunkerVerifying.value &&
                sessionManager.isBunkerReady()
            combine(
                sessionManager.isBunkerConnected,
                sessionManager.isBunkerVerifying,
            ) { connected, verifying ->
                connected && !verifying && sessionManager.isBunkerReady()
            }.collect { ready ->
                if (ready && !wasReady) recoverBunkerGroupRelays()
                wasReady = ready
            }
        }

        metadataManager.messageHandler = { msg, client -> enqueueToRelayPipeline(msg, client) }
        groupManager.messageHandler = { msg, client -> enqueueToRelayPipeline(msg, client) }

        connectionManager.startNetworkMonitor()

        // Deep link relay from URL query params (web) — merge into relay list
        val deepLinkRelay = StartupResolver.deepLinkRelayUrl

        // Populate IndexedDB-backed caches (relay_metadata, joined_group_meta) before any reads.
        // No-op on Android/JVM where storage is synchronous.
        SecureStorage.preloadMetadata()

        // One-shot legacy → multi-account migration. Idempotent; no-op once any
        // account exists. Runs before restoreSession() so the AccountStore is
        // ready when Phase 2 wires AuthManager to read from it.
        AppModule.accountStore.migrateFromLegacyIfNeeded()

        // Now that the IDB cache is populated, prime the relay-metadata StateFlow so the sidebar
        // shows icons/names immediately instead of waiting for NIP-11 HTTP fetches.
        _relayMetadataManager.restoreFromCache()
        // Same for kind:0 profiles: hydrate names/avatars from the global on-disk store so they
        // show instantly on cold start instead of waiting for the network.
        metadataManager.restoreFromCache()
        // And friends'/curator's kind:10009 so the From friends / Recommended tabs render from
        // cache before the network answers; loadFriendsGroups/loadRecommended revalidate.
        restoreUserGroupListsFromCache()

        val restored = sessionManager.restoreSession()
        if (restored) {
            // Activate an AccountSession so all signing routes through the
            // isolated NostrSigner from this point forward.
            AppModule.activateSessionForActiveAccount()
            // Now that the session is active, load the per-account current
            // relay pointer. Doing this earlier (before restoreSession) would
            // read with a null pubkey and force currentRelayUrl to blank,
            // making the boot flow pick a different focused relay than the
            // one the user was on.
            connectionManager.loadSavedRelay()
            val pubkey = sessionManager.getPublicKey()
            val activeRelay = connectionManager.currentRelayUrl.value

            // Seed the kind:10009 relay set from the active account's persisted
            // relay list so the sidebar pre-fills before the network fetch. Must
            // run after restoreSession so we have a pubkey — a blank pubkey
            // would leak the previous account's relays into a fresh login.
            outboxManager.seedFromCache(pubkey.orEmpty())

            // Load saved relay list and pre-populate the rail before connecting
            val savedRelays = SecureStorage.loadRelayListFor(pubkey.orEmpty())

            // No NIP-29 relays saved locally — fetch kind:10009 from bootstrap
            // relays so "r" tags can restore the user's relay list automatically.
            // Once relays are discovered, connect to the first one as focused.
            if (activeRelay.isBlank() && savedRelays.isEmpty() && deepLinkRelay == null) {
                if (pubkey != null) {
                    unreadManager.initialize(pubkey)
                    notificationSettings?.initialize(pubkey)
                    startNotificationPrefsSync(pubkey)
                    notificationHistoryStore?.initialize(pubkey)
                    hydrateMuteListFromCache(pubkey)
                    initializeOutboxModel()
                    scope.launch {
                        outboxManager.loadJoinedGroupsFromNostr(pubkey) { msg, c ->
                            enqueueToRelayPipeline(msg, c)
                        }
                        // After kind:10009 is fetched, check if relays were restored.
                        // If so, connect to the first one so the app doesn't stay in empty state.
                        val restoredRelays = SecureStorage.loadRelayListFor(pubkey)
                        if (restoredRelays.isNotEmpty()) {
                            val focusedRelay = restoredRelays.first()
                            // Persist and set as active so the UI picks it up immediately.
                            SecureStorage.saveCurrentRelayUrlFor(pubkey, focusedRelay)
                            connectionManager.loadSavedRelay()

                            groupManager.prePopulateRelayList(restoredRelays)
                            _relayMetadataManager.fetchAll(restoredRelays)
                            liveCursorStore?.loadAll(restoredRelays)
                            groupManager.loadJoinedGroupsFromStorage(pubkey, focusedRelay)
                            groupManager.loadAllJoinedGroupsFromStorage(pubkey, restoredRelays)
                            groupManager.restoreJoinedGroupMetadataFromStorage(pubkey, restoredRelays)
                            groupManager.restoreGroupMembershipFromStorage(pubkey)
                            outboxManager.restoreGroupOrder(pubkey)
                            groupManager.migrateMessageBlobsToCache(pubkey)
                            // Arm catch-up right before connect (fresh TTL) so the
                            // first mux refresh replays the closed-app backlog.
                            applyCatchUpSinceFor(pubkey)
                            connect(focusedRelay)
                            scope.launch { ensureJoinedRelaysConnected(focusedRelay) }
                        }
                    }
                    requestUserMetadata(setOf(pubkey))
                }
                _isInitialized.value = true
                return
            }

            // Merge deep link relay into the visible list (not persisted until user confirms)
            val baseRelays = if (activeRelay.isBlank()) savedRelays else (listOf(activeRelay) + savedRelays)
            val allRelays = (baseRelays + listOfNotNull(deepLinkRelay)).distinct()
            // Deep link relay becomes focused; otherwise use the first saved relay
            val focusedRelay = deepLinkRelay ?: allRelays.first()
            if (deepLinkRelay != null) {
                // Only set as current relay for this session — don't save to relay list
                if (pubkey != null) {
                    SecureStorage.saveCurrentRelayUrlFor(pubkey, focusedRelay)
                }
                connectionManager.loadSavedRelay()
                // Signal UI to offer adding this relay if it's not already saved
                if (deepLinkRelay !in baseRelays) {
                    _pendingDeepLinkRelay.value = deepLinkRelay
                }
            }
            liveCursorStore?.loadAll(allRelays)
            groupManager.prePopulateRelayList(allRelays)
            _relayMetadataManager.fetchAll(allRelays)

            if (pubkey != null) {
                groupManager.loadJoinedGroupsFromStorage(pubkey, focusedRelay)
                groupManager.loadAllJoinedGroupsFromStorage(pubkey, allRelays)
                groupManager.restoreJoinedGroupMetadataFromStorage(pubkey, allRelays)
                groupManager.restoreGroupMembershipFromStorage(pubkey)
                outboxManager.restoreGroupOrder(pubkey)
                groupManager.migrateMessageBlobsToCache(pubkey)
                unreadManager.initialize(pubkey)
                notificationSettings?.initialize(pubkey)
                startNotificationPrefsSync(pubkey)
                notificationHistoryStore?.initialize(pubkey)
                hydrateMuteListFromCache(pubkey)
                // Arm catch-up so the first mux refresh after connect replays
                // messages that arrived while the app was closed (notifications
                // + unread). Must run after notificationHistoryStore.initialize
                // so the newest-notification data point is loaded.
                applyCatchUpSinceFor(pubkey)
            }
            initializeOutboxModel()

            // Local data loaded — show UI while connect() runs in the background
            _isInitialized.value = true

            connect(focusedRelay)
            scope.launch { ensureJoinedRelaysConnected(focusedRelay) }
            if (pubkey != null) {
                requestUserMetadata(setOf(pubkey))
            }

            // Pool relays are known but NOT connected — they connect lazily when the
            // user switches to them via switchRelay(). Pre-population above already
            // makes them visible in the relay rail.
        } else {
            _isInitialized.value = true
        }

        // Periodically refresh live group subscriptions so relays that drop idle subs
        // (e.g. pyramid.fiatjaf.com) don't permanently stall message delivery.
        // The CLOSED handler already re-opens live subs on explicit relay closure; this
        // periodic refresh is the safety net for silent drops with no CLOSED message.
        scope.launch {
            while (true) {
                delay(5 * 60 * 1000L) // 5 minutes
                // Not gated on the FOCUSED relay's state: that gate starved the pool
                // relays' staleness re-arm whenever the focused relay sat in
                // Reconnecting/Error. Each relay is individually guarded inside
                // (no client / not connected → skip), so this is safe when offline.
                // The zombie probe runs here too: a half-open TCP (sleep/resume,
                // network flap) never fires onConnectionLost, and desktop has no
                // network-change or lifecycle trigger to catch it, so without this
                // the app sits "Connected" and deaf until the 10-min mux-stale path.
                probeIdleSockets()
                groupManager.refreshLiveSubscriptions()
                liveCursorStore?.persistAll()
            }
        }

        // Low-frequency revalidation of the metadata on screen (open-group authors/members
        // and the followed sidebar) so a long-running session picks up renamed profiles and
        // new avatars without needing a reconnect. Only stale entries are refetched.
        scope.launch {
            while (true) {
                delay(MetadataManager.STALE_THRESHOLD_MS)
                if (connectionManager.connectionState.value is ConnectionManager.ConnectionState.Connected) {
                    refreshVisibleUserMetadata()
                }
            }
        }
    }

    /**
     * Connect a pool relay in the background and track it as actively connected.
     * Only called for relays that were previously focused (demoted to pool) and need reconnection.
     */
    private suspend fun connectToRelayBackground(relayUrl: String) {
        if (connectionManager.getFocusedClient()?.getRelayUrl() == relayUrl) return
        try {
            connectionManager.getOrConnectRelay(relayUrl) { msg, c ->
                enqueueToRelayPipeline(msg, c)
            } ?: return
            connectedPoolRelays.add(relayUrl)
        } catch (_: Exception) {}
    }

    // True only while finishLoginInit is wiring up a newly logged-in account.
    // The bunker-ready collector reacts to _bunkerState turning Connected, which
    // for the synchronous loginWithBunker path happens mid-swap, before the new
    // account's relays exist; reconnecting then raced the swap's own reconnect
    // and left the focused unauthenticated (the add-account-needs-restart bug).
    @Volatile
    private var bunkerLoginInProgress = false

    /**
     * Drive the active relay's NIP-42 AUTH once the bunker signer is reachable,
     * so private groups that came back CLOSED "auth-required" while the signer
     * was still connecting load without an app restart.
     *
     * Idempotent: skips the reconnect when the focused already AUTH'd this
     * session (a slow bunker can finish signing before the verifying flag flips,
     * and reconnecting would throw away ~3.5 s of setup + AUTH); ensureJoined is
     * always safe to re-run. No-ops while a login swap is still in flight.
     */
    private suspend fun recoverBunkerGroupRelays() {
        if (bunkerLoginInProgress) return
        try {
            val focused = connectionManager.getFocusedClient()
            val focusedHealthy = focused != null &&
                focused.isConnected() &&
                focused.hasAuthSucceeded()
            if (!focusedHealthy) reconnect()
            ensureJoinedRelaysConnected(
                connectionManager.currentRelayUrl.value.takeIf { it.isNotBlank() },
            )
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

    /**
     * Open a WebSocket + send a mux chat REQ for every relay where the user has
     * joined groups, except [skipPrimary] (already connected). Idempotent; safe
     * to call from startup, on relay switch, or after a join.
     *
     * Without this, only the focused relay delivers live kind:9 — joined groups
     * on other relays go silent. See plan: cosmic-beaming-eclipse.md.
     */
    private suspend fun ensureJoinedRelaysConnected(skipPrimary: String?) {
        val joinedRelays = groupManager.joinedGroupsByRelay.value.keys.toList()
        val skipNormalized = skipPrimary?.normalizeRelayUrl()
        val restrictedNormalized = _restrictedRelays.value.keys
            .map { it.normalizeRelayUrl() }
            .toSet()
        // Connect relays that host auth-gated (private / unknown-metadata) joined groups
        // first. AUTH is one serialized round-trip per relay on a NIP-46 bunker, and those
        // relays are the only ones that need it to reveal their kind:39000/39002 — spending
        // the scarce signer on them before public relays makes private groups load first.
        val joinedByRelay = groupManager.joinedGroupsByRelay.value
        val metaByRelay = groupManager.groupsByRelay.value
        fun authPendingCount(relay: String): Int {
            val joined = joinedByRelay[relay].orEmpty()
            val publicIds = metaByRelay[relay].orEmpty().filter { it.isPublic }.map { it.id }.toSet()
            return joined.count { it !in publicIds }
        }
        val targets = joinedRelays
            .map { it.normalizeRelayUrl() }
            .distinct()
            .filter { it.isNotBlank() && it != skipNormalized && it !in restrictedNormalized }
            .sortedByDescending { authPendingCount(it) }

        for (relayUrl in targets) {
            // Skip if already connected — connectToRelayBackground is idempotent
            // but we'd still pay the awaitAuthOrTimeout round-trip needlessly.
            val existing = connectionManager.getClientForRelay(relayUrl)
            if (existing != null && existing.isConnected() && relayUrl in connectedPoolRelays) {
                groupManager.refreshMuxSubscriptionsForRelay(relayUrl)
                scheduleMissingMetadataSweep(relayUrl)
                delay(100)
                continue
            }
            connectToRelayBackground(relayUrl)
            val client = connectionManager.getClientForRelay(relayUrl) ?: run {
                delay(100)
                continue
            }
            try {
                // Short grace only: a slow bunker sign must NOT block this sequential loop.
                // Private-group data on this relay is covered by resubscribeAfterAuth (on a
                // fresh AUTH challenge) and the mux refresh below; firing it here too flooded
                // the relay with duplicate per-group REQs.
                if (client.isConnected()) client.awaitAuthOrTimeout()
            } catch (_: Throwable) {}
            groupManager.refreshMuxSubscriptionsForRelay(relayUrl)
            // Per-group kind:39000 safety net for THIS secondary relay: the mux/meta batch above
            // is all-or-nothing, so a public relay that loses the AUTH-vs-REQ race would strand
            // every joined group on a truncated id + "No description" (auth-gated relays are
            // covered by resubscribeAfterAuth on their fresh challenge). Idempotent + delayed, so
            // it's a no-op once the batch lands.
            scheduleMissingMetadataSweep(relayUrl)
            delay(100)
        }
    }

    override fun clearAuthUrl() {
        sessionManager.clearAuthUrl()
    }

    override suspend fun loginWithBunker(bunkerUrl: String): Result<String> = try {
        val previousPubkey = sessionManager.getPublicKey()
        if (connectionManager.currentRelayUrl.value.isBlank()) {
            _isDiscoveringRelays.value = true
        }
        // Suppress the reactive bunker-ready recovery while the swap wires up the
        // new account: loginWithBunker flips _bunkerState to Connected mid-swap,
        // which would otherwise fire a reconnect against the old account's relays.
        bunkerLoginInProgress = true
        val userPubkey = try {
            val pk = sessionManager.loginWithBunker(bunkerUrl)
            finishLoginInit(previousPubkey, pk)
            pk
        } finally {
            bunkerLoginInProgress = false
        }
        // Now the new account's relays are wired up and the signer is connected,
        // drive a correctly-timed, idempotent AUTH recovery so a first bunker add
        // never needs an app restart to load private groups.
        scope.launch {
            delay(BUNKER_LOGIN_RECOVERY_DELAY_MS)
            recoverBunkerGroupRelays()
        }
        Result.Success(userPubkey)
    } catch (e: Exception) {
        Result.Error(AppError.Auth.BunkerError(e.message ?: "Bunker connection failed", e))
    }

    override val defaultNostrConnectRelays: List<String> = sessionManager.defaultNostrConnectRelays

    override suspend fun createNostrConnectSession(relays: List<String>): Pair<String, org.nostr.nostrord.nostr.Nip46Client> = sessionManager.createNostrConnectSession(relays)

    override suspend fun completeNostrConnectLogin(
        client: org.nostr.nostrord.nostr.Nip46Client,
        relays: List<String>,
    ): String {
        val previousPubkey = sessionManager.getPublicKey()
        if (connectionManager.currentRelayUrl.value.isBlank()) {
            _isDiscoveringRelays.value = true
        }
        bunkerLoginInProgress = true
        val userPubkey = try {
            val pk = sessionManager.completeNostrConnectLogin(client, relays)
            finishLoginInit(previousPubkey, pk)
            pk
        } finally {
            bunkerLoginInProgress = false
        }
        scope.launch {
            delay(BUNKER_LOGIN_RECOVERY_DELAY_MS)
            recoverBunkerGroupRelays()
        }
        return userPubkey
    }

    override suspend fun loginSuspend(privKey: String, pubKey: String, isNewIdentity: Boolean, ncryptsec: String?): Result<Unit> = try {
        val previousPubkey = sessionManager.getPublicKey()
        // A freshly generated identity has nothing on the network yet — no
        // kind:10002, no kind:10009 — so don't bother flagging the relay
        // discovery spinner. The user will land on OnboardingScreen and pick
        // their first relay manually.
        if (!isNewIdentity && connectionManager.currentRelayUrl.value.isBlank()) {
            _isDiscoveringRelays.value = true
        }
        sessionManager.loginWithPrivateKey(privKey, pubKey, ncryptsec)
        finishLoginInit(previousPubkey, pubKey, isNewIdentity = isNewIdentity)
        Result.Success(Unit)
    } catch (e: Exception) {
        Result.Error(AppError.Unknown(e.message ?: "Login failed", e))
    }

    override suspend fun loginWithNip07(pubkey: String): Result<Unit> = try {
        val previousPubkey = sessionManager.getPublicKey()
        if (connectionManager.currentRelayUrl.value.isBlank()) {
            _isDiscoveringRelays.value = true
        }
        sessionManager.loginWithNip07(pubkey)
        finishLoginInit(previousPubkey, pubkey)
        Result.Success(Unit)
    } catch (e: Exception) {
        Result.Error(AppError.Unknown(e.message ?: "Login failed", e))
    }

    override suspend fun loginWithAmber(
        pubkey: String,
        signerPackage: String?,
    ): Result<Unit> = try {
        val previousPubkey = sessionManager.getPublicKey()
        if (connectionManager.currentRelayUrl.value.isBlank()) {
            _isDiscoveringRelays.value = true
        }
        sessionManager.loginWithAmber(pubkey, signerPackage)
        finishLoginInit(previousPubkey, pubkey)
        Result.Success(Unit)
    } catch (e: Exception) {
        Result.Error(AppError.Unknown(e.message ?: "Login failed", e))
    }

    /**
     * Shared post-login setup. Handles two cases:
     *
     * - Cold start ([previousPubkey] is null): just (re)initialise per-account
     *   stores for [newPubkey] and kick off the initial relay connect.
     * - Warm swap (already authenticated as a different pubkey): clear the
     *   previous identity's in-memory caches via [AppModule.applyActiveAccountChange],
     *   then re-hydrate joined-group state for [newPubkey] from storage and
     *   force a reconnect so pubkey-filtered REQs reissue.
     */
    private suspend fun finishLoginInit(
        previousPubkey: String?,
        newPubkey: String,
        isNewIdentity: Boolean = false,
    ) {
        val isWarmSwap = previousPubkey != null && previousPubkey != newPubkey
        // Activate an AccountSession for the logged-in account so all signing
        // routes through the isolated NostrSigner and the session scope is
        // properly bounded. Must happen before any event is published.
        AppModule.activateSessionForActiveAccount()
        if (isWarmSwap) {
            AppModule.applyActiveAccountChange(AppModule.accountStore.active)
            initializeOutboxModel()
            sessionManager.setLoggedIn(true)
            reloadForActiveAccount()
        } else {
            // Re-bind GroupManager to the new identity. logout() called
            // groupManager.clear() which nulls currentPubkey, and the cold-start
            // path otherwise only resets it as a side-effect of
            // loadJoinedGroupsFromStorage — which is not invoked here because
            // the boot flow (initialize) is the one that calls it on cold start,
            // not a login that happens later in the process. Without this,
            // flushBatchToState drops every incoming kind:9 because of its
            // `currentPubkey == null` guard, leaving every group stuck on
            // "No messages yet" until the user restarts the app.
            groupManager.setCurrentPubkey(newPubkey)
            // Repopulate _currentRelayUrl from the per-account persisted slot.
            // logout() called connectionManager.clearCurrentRelay() which blanked
            // the StateFlow, so without this `connect()` below would dispatch
            // to connect("") and return immediately. Worse: when the kind:10009
            // arrives over the outbox bootstrap, onRelaysRestored fires
            // autoConnectFirstRelay(newRelays), whose `isBlank()` guard passes
            // and which OVERWRITES the persisted slot with relays.first() —
            // silently moving the user off the relay they were on (e.g. from
            // groups.fiatjaf.com to whatever happens to be first in their
            // kind:10009 r-tag list). The cold-boot path (initialize) and the
            // warm-swap path (applyActiveAccountChange) both call loadSavedRelay
            // for the same reason; the cold-start re-login path was the only
            // one missing it.
            connectionManager.loadSavedRelay()
            // Seed the relay rail (kind:10009 set) from the per-account cache so
            // ALL of the user's relays show immediately, exactly as initialize()
            // does on cold boot. Without this, the rail showed only the current
            // relay after re-login until the slow network kind:10009 fetch landed
            // (or the user restarted the app).
            outboxManager.seedFromCache(newPubkey)
            // Re-hydrate joined-group state from local storage, exactly as the
            // cold-boot path (initialize) does at lines 377-380. Previously this
            // re-login branch relied solely on the kind:10009 outbox fetch to
            // repopulate the group list; when that network fetch was slow or the
            // bunker signer was unreachable (#85), nothing loaded the locally
            // persisted groups as a fallback. The result (#88): every group was
            // stuck on "No messages yet" and the relay's groups were never
            // subscribed, intermittently, depending on outbox-fetch timing.
            val activeRelay = connectionManager.currentRelayUrl.value
            val savedRelays = SecureStorage.loadRelayListFor(newPubkey)
            val allRelays = (listOfNotNull(activeRelay.ifBlank { null }) + savedRelays).distinct()
            val focusedRelay = activeRelay.ifBlank { allRelays.firstOrNull().orEmpty() }
            if (focusedRelay.isNotBlank()) {
                groupManager.prePopulateRelayList(allRelays)
                // Mirror the rest of initialize()'s sibling bootstrap, not just the
                // joined-group loaders: without loadAll the first mux subscription
                // has no persisted `since` cursor, and without fetchAll the relay
                // rail shows stale NIP-11 icons/names after re-login.
                liveCursorStore?.loadAll(allRelays)
                _relayMetadataManager.fetchAll(allRelays)
                groupManager.loadJoinedGroupsFromStorage(newPubkey, focusedRelay)
                groupManager.loadAllJoinedGroupsFromStorage(newPubkey, allRelays)
                groupManager.restoreJoinedGroupMetadataFromStorage(newPubkey, allRelays)
                groupManager.restoreGroupMembershipFromStorage(newPubkey)
                outboxManager.restoreGroupOrder(newPubkey)
                groupManager.migrateMessageBlobsToCache(newPubkey)
            }
            unreadManager.initialize(newPubkey)
            notificationSettings?.initialize(newPubkey)
            startNotificationPrefsSync(newPubkey)
            notificationHistoryStore?.initialize(newPubkey)
            hydrateMuteListFromCache(newPubkey)
            // Arm catch-up so the first mux refresh after connect() replays the
            // backlog accumulated while this identity was logged out. A fresh
            // identity has no backlog, so skip it there. Must run after
            // notificationHistoryStore.initialize so the data point is loaded.
            if (!isNewIdentity) applyCatchUpSinceFor(newPubkey)
            // Skip the outbox bootstrap for a freshly generated identity:
            // nothing has been published yet, so kind:10002 / kind:10009 fetches
            // would only delay landing the user on the onboarding screen.
            // The bootstrap connections will form on demand once the user
            // adds their first relay.
            if (!isNewIdentity) initializeOutboxModel()
            sessionManager.setLoggedIn(true)
            scope.launch { connect() }
            // Open sockets for joined groups that live on secondary (non-focused)
            // relays too. connect() only handles the focused; without this those
            // groups stay on "No messages yet" until the user manually switches to
            // their relay — the same #88 symptom, confined to non-focused relays.
            if (focusedRelay.isNotBlank()) {
                scope.launch { ensureJoinedRelaysConnected(focusedRelay) }
            }
            // Fetch the contact list (kind:3) on cold-start login too. A logout -> re-login in a
            // live process does NOT reconstruct the screen ViewModel (it's long-lived), and re-
            // logging the SAME account never changes activePubkey (it maps to the pubkey, which is
            // unchanged, so the StateFlow dedups and the VM's drop(1) re-arm never fires) — so
            // nothing else requested it and the friends list stayed on "follows nobody" until a
            // restart. The warm-swap path already requests it via reloadForActiveAccount; this is its
            // cold-start counterpart. Idempotent (mutex + contactListRequested guard) and the
            // self-healing retry covers relays still connecting.
            scope.launch { requestContactList() }
        }
        scope.launch { requestUserMetadata(setOf(newPubkey)) }
    }

    override suspend fun logout() {
        // Do NOT cancel appScope's children here: logout runs on a coroutine that
        // is itself an appScope child (AccountManager.removeAccountAsync), so a
        // scope-wide cancelChildren() would abort logout midway, leaving
        // _isLoggedIn true and the sockets/legacy slots uncleared (the app then
        // could not leave the last account and a restart re-migrated it). Per-
        // account in-flight work lives on AccountSession.scope and is cancelled by
        // ActiveAccountManager.clear() via applyActiveAccountChange(null).

        // Preserve persisted per-account state (relay list, joined groups,
        // current relay, last viewed group). Re-login with the same pubkey
        // should restore the previous setup from local storage. Wiping here
        // forced a cold-start that came back empty or, worse, raced an
        // in-flight kind:10009 from another account.
        _isDiscoveringRelays.value = false
        outboxManager.clear()
        groupManager.clear()
        unreadManager.clear()
        notificationSettings?.clear()
        stopNotificationPrefsSync()
        notificationHistoryStore?.clear()
        liveCursorStore?.clear()
        relayPipelines.values.forEach { (_, pipeline) -> pipeline.close() }
        relayPipelines.clear()
        connectionManager.clearCurrentRelay()

        try {
            connectionManager.clearAll()
        } catch (_: Exception) {}
        connectedPoolRelays.clear()

        resetSessionScopedCaches()
        resetContactListState()

        sessionManager.logout()
    }

    /**
     * Reset every session-scoped, relay-keyed in-memory cache. These are NOT persisted
     * state; they only make sense within one account's session. The full-logout path and
     * the warm-swap path (reloadForActiveAccount) must both run this so neither leaks the
     * outgoing account's dedup/restriction state into the incoming account. Leaving any of
     * these populated across a switch makes resubscribeAfterAuth / switchRelay / the mux
     * refresh skip work for the new account: a relay A marked restricted stays hidden for B,
     * an authed group-list already fetched under A is not re-fetched for B, and B's groups
     * stay dark until an app restart. Single source of truth so the two paths cannot drift.
     */
    private suspend fun resetSessionScopedCaches() {
        // A live AV room belongs to the account that joined it: it must not survive the swap,
        // and the microphone least of all.
        AppModule.avSpaceHost.release()
        lastRequestGroupsAt.clear()
        relaysNeedingResubscribe.clear()
        // Probe evidence is about the relay, but the sockets it judged belong to the account being
        // torn down; the next one gets a fresh read.
        RelayProbeGuard.reset()
        muxRestrictedRetryJobs.values.forEach { it.cancel() }
        muxRestrictedRetryJobs.clear()
        muxRestrictedRetryAttempts.clear()
        groupPreviewFetchMutex.withLock { groupPreviewFetchAt.clear() }
        authedGroupListFetchedRelays.clear()
        lastGapDetectionAt.clear()
        pendingFullFetchMutex.withLock { pendingFullFetchRequests.clear() }
        _closedGroupSubscriptions.value = emptySet()
        activeRelayUrl = null
        // Per-relay AUTH-required / restricted markers from the previous account would
        // otherwise short-circuit switchRelay (early return at the restriction check) and
        // make ensureJoinedRelaysConnected skip those relays, so a relay that just had a
        // transient AUTH timeout stays permanently "restricted" for the new account.
        _restrictedRelays.value = emptyMap()
        // Relay-keyed, not account-keyed: left intact it holds the previous account's
        // fetched ids and the growth collector skips re-fetching kind:39000 for the new one.
        sentKnownGroupFetch.clear()
        // Decrypted DM attachments are the previous account's private files.
        dmFileManager.clear()
    }

    // ===== Direct messages (NIP-17 over NIP-59 gift wraps) =====

    private val dmManager = DmManager(scope, mutedPubkeys)

    private val dmFileManager = DmFileManager(scope)

    private val dmEncryptionManager = DmEncryptionManager()

    private val dmArchiveManager = DmArchiveManager()

    private val dmPairingManager = DmPairingManager()

    /** Our own outstanding kind:4454, deleted once a device answers it. */
    private var pendingPairingRequestId: String? = null

    override val dmPairingState: StateFlow<DmPairingManager.State> = dmPairingManager.state

    override val dmArchiveProgress: StateFlow<DmArchiveManager.Progress> = dmArchiveManager.progress

    override val dmEncryptionState: StateFlow<DmEncryptionManager.State> = dmEncryptionManager.state

    /**
     * Decryptors for inbound wraps, held NIP-4e keys first: they fail in microseconds on a bad
     * HMAC, so trying them costs nothing when the wrap is not ours, and it removes the signer from
     * the read path entirely when it is.
     */
    private fun dmDecryptors(signer: NostrSigner): List<Nip17.Nip44Decryptor> = dmEncryptionManager.heldKeys().map { kp ->
        Nip17.Nip44Decryptor { peer, ciphertext -> Nip44.decrypt(ciphertext, kp.privateKeyHex, peer) }
    } + Nip17.Nip44Decryptor { peer, ciphertext -> signer.nip44Decrypt(peer, ciphertext) }

    private val legacySealVerifier =
        LegacySealVerifier(
            fetchWaitMs = DM_ENC_KEY_FETCH_WAIT_MS,
            announcedKeyFor = { author -> dmManager.encryptionKeyFor(author) },
            requestAnnouncement = { author -> fetchDmRelays(author) },
        )

    private suspend fun verifyLegacyDmSender(authorPubkey: String, sealPubkey: String): Boolean = legacySealVerifier.verify(authorPubkey, sealPubkey)

    /** Conversations (most-recent first), derived from decrypted NIP-17 messages. */
    override val dmConversations: StateFlow<List<DmConversation>> get() = dmManager.conversations

    /** Decrypted DM messages keyed by peer pubkey. */
    override val dmMessagesByPeer: StateFlow<Map<String, List<DmMessage>>> get() = dmManager.messagesByPeer

    /** Unread DM count per peer (incoming messages newer than the read high-water). */
    override val dmUnreadByPeer: StateFlow<Map<String, Int>> get() = dmManager.unreadByPeer

    /** Total unread DMs across all conversations, for the nav badge. */
    override val totalDmUnread: StateFlow<Int> get() = dmManager.totalUnread

    override val lastDmPeer: StateFlow<String?> get() = dmManager.lastPeer

    override fun rememberDmPeer(pubkey: String) = dmManager.rememberLastPeer(pubkey)

    // Our own effective DM relays (kind:10050, or the defaults until we publish one). Drives the
    // Settings editor; kept in sync on inbox open, on our own kind:10050, and on publish.
    private val _myDmRelays = MutableStateFlow<List<String>>(emptyList())
    override val myDmRelays: StateFlow<List<String>> = _myDmRelays.asStateFlow()

    override val dmRelaysByPubkey: StateFlow<Map<String, List<String>>> = dmManager.dmRelaysByPubkey

    override val dmMessageStatus: StateFlow<Map<String, GroupManager.MessageStatus>> = dmManager.messageStatus

    override val dmFileStates: StateFlow<Map<String, DmFileManager.FileState>> = dmFileManager.states

    override val dmReactions: StateFlow<Map<String, Map<String, GroupManager.ReactionInfo>>> = dmManager.reactions

    override fun loadDmFile(rumorId: String, file: Nip17File) = dmFileManager.load(rumorId, file)

    override fun retryDmFile(rumorId: String, file: Nip17File) = dmFileManager.retry(rumorId, file)

    override fun requestPeerDmRelays(pubkey: String) {
        scope.launch { fetchDmRelays(pubkey) }
    }

    /**
     * Resolve [pubkey]'s DM relay list if we do not hold one yet, waiting a bounded moment for the
     * answer. Nothing else fetches a peer's kind:10050 before a send: the list only arrived as a
     * side effect of receiving from them, so the first message of a conversation was addressed to
     * the defaults and never reached anyone who reads elsewhere.
     */
    private suspend fun awaitPeerDmRelays(pubkey: String) {
        if (dmManager.dmRelaysFor(pubkey).isNotEmpty()) return
        fetchDmRelays(pubkey)
        withTimeoutOrNull(PEER_DM_RELAY_WAIT_MS) {
            dmManager.dmRelaysByPubkey.first { it[pubkey]?.isNotEmpty() == true }
        }
    }

    // Fallback DM relays for users (and us) without a published kind:10050.
    private val defaultDmRelays =
        listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", "wss://auth.nostr1.com")

    // The kind:10002 an account gets when it has none. Read+write general-purpose relays, so the
    // advertised list is one other clients can actually fetch from and publish to.
    private val defaultOutboxRelays =
        listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net")

    private var dmInboxStarted = false
    private var dmPersistenceWired = false
    private val dmPersistenceJobs = mutableListOf<kotlinx.coroutines.Job>()

    /** Mark a DM conversation read up to its newest message (clears its unread badge). */
    override suspend fun markDmRead(peerPubkey: String) {
        dmManager.markRead(peerPubkey)
    }

    private fun dmRelaysFor(pubkey: String): List<String> = dmManager.dmRelaysFor(pubkey).map { it.normalizeRelayUrl() }.ifEmpty { defaultDmRelays }

    // Union of a user's resolved DM relays with the defaults. The inbox subscribes and the wrap
    // publish targets this, so devices always overlap regardless of when each resolves the kind:10050
    // (a fresh login subscribes to defaults first; a peer may publish to defaults before their list
    // loads). Keeping the defaults in the set closes those cross-device gaps.
    private fun dmRelaysWithDefaults(pubkey: String): List<String> = (dmRelaysFor(pubkey) + defaultDmRelays).distinct()

    // Order stamping plus the on-screen bubble, held only for that. A rumor's created_at and
    // `ms` come off a counter, so two sends racing here would land swapped in the conversation.
    private val dmStampMutex = Mutex()

    // One publish at a time. Every message costs two seals through the signer, which a bunker
    // answers one by one anyway, and the durable queue then holds them in the order they were
    // typed. Bubbles do not wait on this: they are already up (see [stampDm]).
    private val dmPublishMutex = Mutex()

    /**
     * Send a NIP-17 direct message: build the rumor, seal + gift-wrap it for the recipient and a
     * self-copy for us, and publish each to its side's DM relays. The seal's NIP-44 encrypt and
     * signature run through the active signer, so local, bunker, and NIP-07 accounts all work.
     */
    override suspend fun sendDm(recipientPubkey: String, content: String, replyToId: String?): Result<Unit> {
        val signer =
            ActiveAccountManager.session.value?.signer
                ?: return Result.Error(AppError.Auth.NotAuthenticated)
        val myPub = sessionManager.getPublicKey() ?: return Result.Error(AppError.Auth.NotAuthenticated)
        val rumor = dmStampMutex.withLock { stampDm(chatRumor(myPub, recipientPubkey, content, replyToId), recipientPubkey, myPub) }
        return dmPublishMutex.withLock { publishStampedDm(rumor, recipientPubkey, myPub, signer) }
    }

    override fun submitDm(
        recipientPubkey: String,
        files: List<DmOutgoingFile>,
        text: String,
        replyToId: String?,
        onResult: (Result<Unit>) -> Unit,
    ) {
        if (files.isEmpty() && text.isEmpty()) return
        // The session's scope, not the composer's: the round-trip lasts as long as the signer
        // takes, and a message still waiting its turn must survive the conversation being closed.
        scope.launch {
            val signer = ActiveAccountManager.session.value?.signer
            val myPub = sessionManager.getPublicKey()
            if (signer == null || myPub == null) {
                onResult(Result.Error(AppError.Auth.NotAuthenticated))
                return@launch
            }
            // Every message of the submit is on screen before anything is signed, each with its
            // own Sending clock, so the composer is free the moment Enter is pressed.
            val rumors =
                dmStampMutex.withLock {
                    buildList {
                        files.forEach { add(fileRumor(myPub, recipientPubkey, it)) }
                        if (text.isNotEmpty()) add(chatRumor(myPub, recipientPubkey, text, replyToId))
                    }.map { stampDm(it, recipientPubkey, myPub) }
                }
            var failure: AppError? = null
            dmPublishMutex.withLock {
                for ((index, rumor) in rumors.withIndex()) {
                    val result = publishStampedDm(rumor, recipientPubkey, myPub, signer)
                    if (result is Result.Error) {
                        failure = result.error
                        // The rest of the submit never reached the signer, so their bubbles have
                        // nothing behind them either.
                        rumors.drop(index + 1).forEach { discardUnsentDm(it) }
                        break
                    }
                }
            }
            onResult(failure?.let { Result.Error(it) } ?: Result.Success(Unit))
        }
    }

    // A reply is an `e` tag on the rumor, with no relay hint: the rumor's id is computed before
    // any relay lookup, and the reply never leaves the conversation anyway.
    private fun chatRumor(myPub: String, recipientPubkey: String, content: String, replyToId: String?): Event {
        val replyTags = replyToId?.let { listOf(listOf("e", it)) } ?: emptyList()
        val (createdAt, ms) = DmMessageOrder.next()
        return Nip17.buildRumor(myPub, recipientPubkey, content, createdAt, DmMessageOrder.withOrderTag(replyTags, ms))
    }

    // An uploaded attachment as its own kind:15: the url is the content and the tags carry the
    // key, the nonce and `ox`, the shape other NIP-17 clients read.
    private fun fileRumor(myPub: String, recipientPubkey: String, file: DmOutgoingFile): Event {
        val tags =
            buildList {
                add(listOf(Nip17File.TAG_FILE_TYPE, file.mimeType))
                add(listOf("encryption-algorithm", Nip17File.ALGORITHM_AES_GCM))
                add(listOf(Nip17File.TAG_DECRYPTION_KEY, file.keyHex))
                add(listOf("decryption-nonce", file.nonceHex))
                // Hash of the plaintext: what the reader checks the decrypted bytes against.
                add(listOf("ox", file.originalHashHex))
                add(listOf("size", file.size.toString()))
                val w = file.width
                val h = file.height
                if (w != null && h != null) add(listOf("dim", "${w}x$h"))
            }
        val (createdAt, ms) = DmMessageOrder.next()
        return Nip17.buildRumor(myPub, recipientPubkey, file.url, createdAt, DmMessageOrder.withOrderTag(tags, ms), kind = Nip17.KIND_FILE)
    }

    /** Put [rumor] in the thread with a Sending clock before a single byte is signed. */
    private fun stampDm(rumor: Event, recipientPubkey: String, myPub: String): Event {
        dmManager.addOptimistic(rumor, recipientPubkey, myPub)
        return rumor
    }

    /**
     * Address, seal and queue a rumor whose bubble is already up. Resolving where the recipient
     * reads and the two seals are the slow half of a send, which is exactly why the bubble does
     * not wait for them.
     */
    private suspend fun publishStampedDm(rumor: Event, recipientPubkey: String, myPub: String, signer: NostrSigner): Result<Unit> {
        // Where the recipient actually reads. Without their kind:10050 the wrap goes to the app
        // defaults only, which for a first contact means it is published where they never look and
        // is lost silently. Their kind:10044 rides the same REQ, so addressing improves too.
        awaitPeerDmRelays(recipientPubkey)
        val result = publishDmRumor(rumor, recipientPubkey, myPub, signer)
        if (result is Result.Error) discardUnsentDm(rumor)
        return result
    }

    /**
     * Take a message back off the thread when its build failed. Nothing reached the send queue, so
     * the bubble would sit on Sending for good and its Retry would have no wrap to resend; the
     * composer offers the draft again instead.
     */
    private fun discardUnsentDm(rumor: Event) {
        rumor.id?.let { dismissDm(it) }
    }

    /**
     * Encrypt and upload a DM attachment, returning what the composer keeps until Enter. The
     * media server only ever holds ciphertext: key and nonce travel inside the sealed kind:15.
     * [width] and [height] let the reader reserve the right slot before the bytes land.
     */
    override suspend fun uploadDmFile(
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        width: Int?,
        height: Int?,
    ): Result<DmOutgoingFile> {
        if (bytes.isEmpty()) return Result.Error(AppError.Unknown("The file is empty"))
        // A private key is 32 bytes off the platform CSPRNG, which is exactly what the cipher
        // wants; drawing from it keeps randomness on one primitive instead of a fifth expect/actual.
        val key = Crypto.generatePrivateKey()
        val nonce = Crypto.generatePrivateKey().copyOf(AesGcm.NONCE_SIZE)
        val encrypted =
            AesGcm.encrypt(key, nonce, bytes)
                ?: return Result.Error(AppError.Unknown("Could not encrypt the file"))
        val uploaded = uploadEncryptedBlob(encrypted, filename)
        val upload = uploaded.getOrNull() ?: return Result.Error(uploaded.errorOrNull() ?: AppError.Unknown("Upload failed"))
        // Without a `dim` the reader cannot reserve the image's box and the thread jumps when the
        // bytes land. The server cannot supply one (it only sees ciphertext), so decode it here.
        val (dimWidth, dimHeight) =
            if (width != null && height != null) {
                width to height
            } else {
                decodeImageDimensions(bytes, mimeType) ?: (null to null)
            }
        return Result.Success(
            DmOutgoingFile(
                url = upload.url,
                mimeType = mimeType,
                keyHex = key.toHexString(),
                nonceHex = nonce.toHexString(),
                originalHashHex = Crypto.sha256(bytes).toHexString(),
                size = bytes.size.toLong(),
                width = dimWidth?.takeIf { it > 0 },
                height = dimHeight?.takeIf { it > 0 },
            ),
        )
    }

    /**
     * Send an uploaded attachment as its own NIP-17 kind:15. Each file is its own rumor; any text
     * goes in a separate kind:14.
     */
    override suspend fun sendDmUploadedFile(
        recipientPubkey: String,
        file: DmOutgoingFile,
    ): Result<Unit> {
        val signer =
            ActiveAccountManager.session.value?.signer
                ?: return Result.Error(AppError.Auth.NotAuthenticated)
        val myPub = sessionManager.getPublicKey() ?: return Result.Error(AppError.Auth.NotAuthenticated)
        val rumor = dmStampMutex.withLock { stampDm(fileRumor(myPub, recipientPubkey, file), recipientPubkey, myPub) }
        return dmPublishMutex.withLock { publishStampedDm(rumor, recipientPubkey, myPub, signer) }
    }

    override suspend fun sendDmFile(
        recipientPubkey: String,
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        width: Int?,
        height: Int?,
    ): Result<Unit> = when (val uploaded = uploadDmFile(bytes, filename, mimeType, width, height)) {
        is Result.Error -> Result.Error(uploaded.error)
        is Result.Success -> sendDmUploadedFile(recipientPubkey, uploaded.data)
    }

    /**
     * React to a DM with [emoji] (NIP-25 inside the gift wrap). [emojiUrl] carries a custom emoji's
     * image (NIP-30). The reaction is not a message, so it never enters the thread or the unread
     * count; it shows under the bubble it targets.
     */
    override suspend fun sendDmReaction(
        recipientPubkey: String,
        messageId: String,
        emoji: String,
        emojiUrl: String?,
    ): Result<Unit> {
        val signer =
            ActiveAccountManager.session.value?.signer
                ?: return Result.Error(AppError.Auth.NotAuthenticated)
        val myPub = sessionManager.getPublicKey() ?: return Result.Error(AppError.Auth.NotAuthenticated)
        awaitPeerDmRelays(recipientPubkey)
        val tags =
            buildList {
                add(listOf("e", messageId, dmRelaysFor(recipientPubkey).firstOrNull() ?: ""))
                if (emojiUrl != null) add(listOf("emoji", emoji.trim(':'), emojiUrl))
            }
        val rumor = Nip17.buildRumor(myPub, recipientPubkey, emoji, extraTags = tags, kind = Nip17.KIND_REACTION)
        dmManager.addOptimisticReaction(rumor, recipientPubkey, myPub)
        return publishDmRumor(rumor, recipientPubkey, myPub, signer)
    }

    /**
     * Seal and gift-wrap [rumor] for the recipient plus a self-copy, and publish each to its side's
     * DM relays. Shared by text, file messages and reactions: only the rumor differs, so the NIP-4e
     * addressing, the send queue and the delivery tracking are written once. Whatever the rumor
     * shows on screen is already there: a message bubble from [stampDm], a reaction from the
     * reaction map.
     */
    private suspend fun publishDmRumor(
        rumor: Event,
        recipientPubkey: String,
        myPub: String,
        signer: NostrSigner,
    ): Result<Unit> {
        return try {
            // NIP-4e: a recipient who announced an encryption key gets both NIP-44 layers addressed
            // to it, tagged with the key they must ECDH the seal against. Until we hold an
            // encryption key of our own that key is our identity pubkey, which is what makes this
            // readable by adopters without us publishing any state yet.
            val recipientEncKey = dmManager.encryptionKeyFor(recipientPubkey)
            // Our own encryption key, when we hold one AND it is the announced one. It makes both
            // seals a local NIP-44 op, so a send costs two signatures instead of two signatures
            // plus two remote encrypts.
            val myEncKey =
                (dmEncryptionManager.state.value as? DmEncryptionManager.State.Active)
                    ?.let { dmEncryptionManager.heldKeys().firstOrNull() }
            val recipientWrap =
                if (recipientEncKey != null) {
                    Nip17.wrap(
                        rumor,
                        recipientPubkey,
                        signer,
                        encryptTo = recipientEncKey,
                        // Until we hold a key of our own, our encryption key IS our identity key,
                        // which is what makes this readable and verified without announcing state.
                        senderEncTag = myEncKey?.publicKeyHex ?: myPub,
                        encryptWith = myEncKey?.privateKeyHex,
                    )
                } else {
                    Nip17.wrap(rumor, recipientPubkey, signer)
                }
            // Self-copy: addressed to our encryption key once we hold one, so our other devices
            // read it without the signer too; identity-addressed otherwise, exactly as before.
            val selfWrap =
                if (myEncKey != null) {
                    Nip17.wrap(
                        rumor,
                        myPub,
                        signer,
                        encryptTo = myEncKey.publicKeyHex,
                        senderEncTag = myEncKey.publicKeyHex,
                        encryptWith = myEncKey.privateKeyHex,
                    )
                } else {
                    Nip17.wrap(rumor, myPub, signer)
                }
            val rumorId = rumor.id ?: return Result.Error(AppError.Unknown("Failed to build the message"))
            // Enqueue both wraps (recipient + self-copy) in the persisted send queue, then publish.
            // The message shows as Sending and flips to Delivered when a relay OKs the recipient's
            // wrap. The queue survives an app restart and never gives up, so a wrap that never
            // reached a relay keeps retrying instead of being stranded local-only.
            //
            // The status is re-asserted here for the rumors that carry no bubble: markDelivered
            // only resolves ids it already knows, so a wrap accepted before the id was staked out
            // would leave the message on Sending for good.
            dmManager.setSending(rumorId)
            val now = rumor.createdAt
            dmSendQueue.enqueue(
                myPub,
                listOf(
                    PendingDmWrap(rumorId, recipientWrap.id ?: "", recipientWrap.toJsonObject().toString(), dmRelaysWithDefaults(recipientPubkey), now),
                    PendingDmWrap(rumorId, selfWrap.id ?: "", selfWrap.toJsonObject().toString(), dmRelaysWithDefaults(myPub), now, toSelf = true),
                ),
            )
            if (NIP4E_DUAL_SEND && myEncKey != null && recipientEncKey != null) {
                // Best-effort copies in the encryption-key-signed shape, the only one deployed
                // readers that predate the identity-signed seal accept. Both are signed locally,
                // and the modern wraps above remain the delivery-tracked primaries, so a failure
                // here never affects the message's state.
                scope.launch {
                    try {
                        publishEventToRelays(
                            dmRelaysWithDefaults(recipientPubkey),
                            Nip17.giftWrap(
                                Nip17.legacySeal(rumor, myEncKey.privateKeyHex, recipientEncKey),
                                recipientPubkey,
                                encryptTo = recipientEncKey,
                            ),
                        )
                        publishEventToRelays(
                            dmRelaysWithDefaults(myPub),
                            Nip17.giftWrap(
                                Nip17.legacySeal(rumor, myEncKey.privateKeyHex, myEncKey.publicKeyHex),
                                myPub,
                                encryptTo = myEncKey.publicKeyHex,
                            ),
                        )
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                    }
                }
            }
            Result.Success(Unit)
        } catch (e: NostrSigner.SigningException) {
            Result.Error(AppError.Unknown("Your signer could not encrypt this message (NIP-44). It may not support direct messages."))
        } catch (e: Throwable) {
            Result.Error(AppError.Unknown(e.message ?: "Failed to send the message"))
        }
    }

    /**
     * Announce a NIP-4e encryption key so senders address it and inbound DMs decrypt in-process.
     * Reuses the held key when there is one, so re-enabling does not strand history behind a key
     * nobody is told about any more.
     */
    override suspend fun enableDmEncryption(): Result<Unit> {
        val signer =
            ActiveAccountManager.session.value?.signer
                ?: return Result.Error(AppError.Auth.NotAuthenticated)
        val myPub = sessionManager.getPublicKey() ?: return Result.Error(AppError.Auth.NotAuthenticated)
        // Ask the relays what this account announces BEFORE minting anything. Local state on a
        // device that just logged in says Disabled until the fetch lands, and enabling on that
        // stale read overwrites the other device's kind:10044 — leaving the two devices holding
        // different keys, each believing it is the current one.
        refreshDmEncryptionState()
        if (dmEncryptionManager.state.value is DmEncryptionManager.State.AnnouncedElsewhere) {
            return Result.Error(
                AppError.Unknown("This account announces a key that is not stored on this device. Import it, or replace it below."),
            )
        }
        // A key from an earlier session is re-announced as-is; otherwise this mints one.
        val held = dmEncryptionManager.currentEncPubkeyOrNull()
            ?: return announceFreshEncryptionKey(signer, myPub)
        return publishEncryptionAnnouncement(signer, myPub, held)
    }

    /**
     * Re-read this account's own kind:10044 from the relays and let the manager reconcile against
     * it. Bounded: the fetch has no EOSE plumbing here, so a first-ever enable (nothing to find)
     * costs this wait once rather than risking a decision made on a stale local read.
     */
    override suspend fun refreshDmEncryptionState() {
        val myPub = sessionManager.getPublicKey() ?: return
        fetchDmRelays(myPub)
        // A fixed pause, not a wait for a particular state: waiting for AnnouncedElsewhere made a
        // momentary wrong answer the thing that ended the wait.
        delay(OWN_ANNOUNCEMENT_WAIT_MS)
    }

    /**
     * Replace the advertised key with a fresh one. The previous key stays held and keeps being
     * tried on receive, both for its own history and for contacts who have not re-read our
     * announcement yet and are still addressing it.
     */
    override suspend fun rotateDmEncryptionKey(): Result<Unit> {
        val signer =
            ActiveAccountManager.session.value?.signer
                ?: return Result.Error(AppError.Auth.NotAuthenticated)
        val myPub = sessionManager.getPublicKey() ?: return Result.Error(AppError.Auth.NotAuthenticated)
        if (dmEncryptionManager.state.value !is DmEncryptionManager.State.Active) {
            return Result.Error(AppError.Unknown("Turn on the fast decryption key first."))
        }
        return announceFreshEncryptionKey(signer, myPub)
    }

    /**
     * Escape hatch for a key announced by a device we no longer have. Without it the account is
     * stuck: senders address a key nobody here holds, so inbound DMs cannot be opened at all, and
     * enable/rotate both refuse while another device owns the announcement.
     */
    override suspend fun resetDmEncryptionKey(): Result<Unit> {
        val signer =
            ActiveAccountManager.session.value?.signer
                ?: return Result.Error(AppError.Auth.NotAuthenticated)
        val myPub = sessionManager.getPublicKey() ?: return Result.Error(AppError.Auth.NotAuthenticated)
        if (dmEncryptionManager.state.value !is DmEncryptionManager.State.AnnouncedElsewhere) {
            return Result.Error(AppError.Unknown("This device already holds the announced key."))
        }
        return announceFreshEncryptionKey(signer, myPub)
    }

    /**
     * Publish a brand-new key, and hold it whatever the publish reports.
     *
     * An unconfirmed publish is not a failed one: a relay that stores the event and never answers
     * OK leaves the account advertising a key this device would otherwise have thrown away, and
     * the announcement coming back then reads as another device's — the account locks itself out
     * of its own key. Holding it costs nothing if the publish really was lost.
     *
     * Which held key is current is then the relays' call, not ours: [refreshDmEncryptionState]
     * re-reads the account's own kind:10044 and promotes whichever key it names.
     */
    private suspend fun announceFreshEncryptionKey(signer: NostrSigner, myPub: String): Result<Unit> {
        val fresh = KeyPair.generate()
        // Persist the key BEFORE any network call. It is the only thing here that cannot be
        // recovered: an unannounced key on the device is inert, while an announcement whose key was
        // lost (a cancelled publish, a closed screen, a killed process) costs the account every
        // message sent to it, permanently.
        dmEncryptionManager.adoptKey(fresh)
        return publishEncryptionAnnouncement(signer, myPub, fresh.publicKeyHex)
    }

    /**
     * Stop advertising our encryption key. The key itself is kept: messages already addressed to
     * it can only ever be opened with it, so deleting it would destroy that history.
     */
    override suspend fun disableDmEncryption(): Result<Unit> {
        val signer =
            ActiveAccountManager.session.value?.signer
                ?: return Result.Error(AppError.Auth.NotAuthenticated)
        val myPub = sessionManager.getPublicKey() ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return publishEncryptionAnnouncement(signer, myPub, null)
    }

    /**
     * Sign and publish the account's kind:10044. The signed event is recorded as our announcement
     * immediately, before the network call: the relay echoes it back within a second on the
     * standing REQ, and an announcement we have not recorded yet reads as a key someone else owns.
     *
     * The publish result decides only what the screen says about confirmation. Whether the key is
     * announced is what the newest kind:10044 says, and by then we have already seen it.
     */
    private suspend fun publishEncryptionAnnouncement(signer: NostrSigner, myPub: String, encPubkey: String?): Result<Unit> = try {
        val createdAt = epochSeconds()
        val signed = signer.signEvent(Nip4e.buildAnnouncement(myPub, encPubkey, createdAt))
        dmEncryptionManager.ingestAnnouncement(encPubkey, createdAt, fromRelay = false)
        // Same relay set as the DM relay list: senders look for both on our outbox, and our
        // own DM relays are where another device of ours will read it.
        val targets = (outboxManager.getWriteRelays() + outboxManager.bootstrapRelays + dmRelaysWithDefaults(myPub)).distinct()
        if (publishWrapJsonAwaitOk(targets, signed.toJsonObject().toString(), signed.id.orEmpty()) is DmPublishOutcome.Accepted) {
            dmEncryptionManager.ingestAnnouncement(encPubkey, createdAt, fromRelay = true)
            Result.Success(Unit)
        } else {
            Result.Error(AppError.Unknown("No relay confirmed the announcement yet. The key is saved on this device."))
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.Error(AppError.Unknown(e.message ?: "Failed to publish the encryption key announcement"))
    }

    /**
     * Rumors still missing an archive copy. Messages newer than the announcement are already
     * addressed to the encryption key by the sender (inbound) or by our own send path (outbound).
     */
    private suspend fun pendingArchiveRumors(myPub: String): List<Event> {
        val cached =
            try {
                loadCachedDms(myPub)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                return emptyList()
            }
        val rumors =
            cached.mapNotNull { row ->
                Nip17.parseRumor(row.toDmMessage(myPub).eventJson())
            }
        return dmArchiveManager.pending(rumors, dmEncryptionManager.announcedAt())
    }

    override suspend fun countDmArchivableMessages(): Int {
        val myPub = sessionManager.getPublicKey() ?: return 0
        if (dmEncryptionManager.state.value !is DmEncryptionManager.State.Active) return 0
        return pendingArchiveRumors(myPub).size
    }

    /**
     * Republish decrypted history to ourselves, addressed to our encryption key, so a new device
     * holding that key loads it without the signer. One signature per message, paced through the
     * same gate as sends, and resumable: only relay-accepted copies advance the progress set.
     */
    override suspend fun archiveDmHistory(): Result<Unit> {
        val signer =
            ActiveAccountManager.session.value?.signer
                ?: return Result.Error(AppError.Auth.NotAuthenticated)
        val myPub = sessionManager.getPublicKey() ?: return Result.Error(AppError.Auth.NotAuthenticated)
        val encKey =
            (dmEncryptionManager.state.value as? DmEncryptionManager.State.Active)
                ?.let { dmEncryptionManager.heldKeys().firstOrNull() }
                ?: return Result.Error(AppError.Unknown("Turn on the fast decryption key first."))

        // Runs on the repository scope, not the caller's: archiving takes minutes and must survive
        // the user leaving Settings. Progress and failures are reported through dmArchiveProgress.
        scope.launch {
            dmArchiveManager.run(
                rumors = pendingArchiveRumors(myPub),
                buildWrap = { rumor ->
                    // One shape for both halves. Our own messages come out as ordinary NIP-17
                    // self-wraps (seal.pubkey == rumor.pubkey); a peer's rumor makes the seal ours over
                    // their rumor, which only our own unwrap accepts (see Nip17.unwrap) and which every
                    // other client drops silently.
                    bunkerPublishGate.withLock { delay(BUNKER_WRAP_ADMIT_INTERVAL_MS) }
                    Nip17.wrap(
                        rumor,
                        myPub,
                        signer,
                        encryptTo = encKey.publicKeyHex,
                        senderEncTag = encKey.publicKeyHex,
                        encryptWith = encKey.privateKeyHex,
                    )
                },
                // A new device's dm_inbox REQ looks exactly here.
                publish = { wrap ->
                    publishWrapJsonAwaitOk(dmRelaysWithDefaults(myPub), wrap.toJsonObject().toString(), wrap.id ?: "") is DmPublishOutcome.Accepted
                },
                persistProgress = { ids -> SecureStorage.saveDmArchivedRumorIdsFor(myPub, ids) },
            )
        }
        return Result.Success(Unit)
    }

    override suspend fun exportDmHistory(): String {
        val myPub = sessionManager.getPublicKey() ?: return ""
        val cached =
            try {
                loadCachedDms(myPub)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                return ""
            }
        return DmHistoryFile.render(cached.mapNotNull { it.toDmMessage(myPub).rumorJson })
    }

    override suspend fun importDmHistory(text: String): Result<DmImportSummary> {
        val myPub = sessionManager.getPublicKey() ?: return Result.Error(AppError.Auth.NotAuthenticated)
        val parsed = DmHistoryFile.parse(text)
        if (parsed.rumors.isEmpty()) {
            return Result.Error(AppError.Unknown("No messages this account can restore were found in that file."))
        }
        // Filing goes through DmManager, so the persistence collector writes the restored rumors to
        // the cache exactly like relay-delivered ones. Nothing is published.
        var imported = 0
        parsed.rumors.forEach { if (dmManager.importRumor(it, myPub)) imported++ }
        return Result.Success(
            DmImportSummary(
                imported = imported,
                duplicates = parsed.rumors.size - imported,
                skipped = parsed.skipped,
            ),
        )
    }

    override fun cancelDmArchive() {
        dmArchiveManager.cancel()
    }

    /**
     * Ask another device of this account for the encryption key (NIP-4e kind:4454). The reply is
     * encrypted to a throwaway key only this device holds, so nothing usable is exposed by the
     * request sitting on a relay.
     */
    override suspend fun requestDmEncryptionKey(): Result<Unit> {
        val signer =
            ActiveAccountManager.session.value?.signer
                ?: return Result.Error(AppError.Auth.NotAuthenticated)
        val myPub = sessionManager.getPublicKey() ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return try {
            // The previous attempt's request is dead the moment a new throwaway key exists: only
            // this device can open its answer, and it no longer holds that key. Left on the relay
            // it prompts every holding device again, once per launch, forever.
            val superseded = pendingPairingRequestId
            val throwaway = dmPairingManager.beginRequest()
            val signed = signer.signEvent(Nip4e.buildClientKeyRequest(myPub, throwaway, epochSeconds()))
            pendingPairingRequestId = signed.id
            superseded?.let { scope.launch { publishDeletion(myPub, signer, listOf(it)) } }
            // Our own request must never prompt us: the throwaway-key guard is in-memory only, so
            // after a restart the relay replays this event back to us like any other.
            signed.id?.let { dmPairingManager.markProcessed(it) }
            publishEventToRelays(pairingRelays(myPub), signed)
            Result.Success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            dmPairingManager.failed(e.message ?: "Could not publish the pairing request")
            Result.Error(AppError.Unknown(e.message ?: "Could not publish the pairing request"))
        }
    }

    /** Send our encryption key to the device that asked for it (NIP-4e kind:4455). */
    override suspend fun approveDmPairing(throwawayPubkey: String): Result<Unit> {
        val incoming =
            dmPairingManager.pendingRequest(throwawayPubkey)
                ?: return Result.Error(AppError.Unknown("There is no pairing request to approve."))
        val signer =
            ActiveAccountManager.session.value?.signer
                ?: return Result.Error(AppError.Auth.NotAuthenticated)
        val myPub = sessionManager.getPublicKey() ?: return Result.Error(AppError.Auth.NotAuthenticated)
        val encKey =
            dmEncryptionManager.heldKeys().firstOrNull()
                ?: return Result.Error(AppError.Unknown("This device does not hold an encryption key."))
        return try {
            val throwaway = KeyPair.generate()
            val encrypted = Nip44.encrypt(encKey.privateKeyHex, throwaway.privateKeyHex, incoming.throwawayPubkey)
            val signed =
                signer.signEvent(
                    Nip4e.buildKeyShare(myPub, throwaway.publicKeyHex, incoming.throwawayPubkey, encrypted, epochSeconds()),
                )
            publishEventToRelays(pairingRelays(myPub), signed)
            dmPairingManager.resolveIncoming(incoming.throwawayPubkey)
            // No deletion from this side: the requester deletes the share (and its own request)
            // once it holds the key. Deleting here races the delivery — relays honour the kind:5
            // before the waiting device has read the share, so the pairing never completes.
            Result.Success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.Error(AppError.Unknown(e.message ?: "Could not send the encryption key"))
        }
    }

    override fun declineDmPairing(throwawayPubkey: String) {
        val declined = dmPairingManager.pendingRequest(throwawayPubkey)
        dmPairingManager.resolveIncoming(throwawayPubkey)
        declined?.let { withdrawRequests(listOf(it.eventId)) }
    }

    override fun declineAllDmPairing() {
        val pending = (dmPairingManager.state.value as? DmPairingManager.State.IncomingRequests)?.requests.orEmpty()
        dmPairingManager.resolveAllIncoming()
        withdrawRequests(pending.map { it.eventId })
    }

    /**
     * Delete declined kind:4454s. Both devices are the same identity, so this deletion is the
     * account withdrawing its own request: it tells the waiting device it was turned down, and it
     * stops the request prompting every other device of the account on their next launch.
     */
    private fun withdrawRequests(eventIds: List<String>) {
        if (eventIds.isEmpty()) return
        val signer = ActiveAccountManager.session.value?.signer ?: return
        val myPub = sessionManager.getPublicKey() ?: return
        scope.launch { publishDeletion(myPub, signer, eventIds) }
    }

    override fun dismissDmPairing() {
        dmPairingManager.reset()
    }

    /** A kind:4455 answering our own outstanding request: decrypt, validate, hold. */
    private fun handleKeyShare(event: Event, myPub: String) {
        val recipient = Nip4e.keyShareRecipientFrom(event) ?: return
        // Every device of the account sees the answer. One still prompting for that same request
        // drops it here: the key is already on its way, and a second share would put another copy
        // of the ciphertext on the relays for a request nobody is waiting on.
        dmPairingManager.resolveIncoming(recipient)
        val ours = dmPairingManager.requestThrowawayKey() ?: return
        if (recipient != ours.publicKeyHex) return
        val senderThrowaway = Nip4e.throwawayPubkeyFrom(event) ?: return
        val keyHex =
            runCatching { Nip44.decrypt(event.content, ours.privateKeyHex, senderThrowaway) }.getOrNull()
                ?: return dmPairingManager.failed("The key from the other device could not be read.")
        // importKey rejects anything that is not the key this account announced, so a forged or
        // stale share cannot leave us holding something senders are not addressing.
        if (!dmEncryptionManager.importKey(keyHex)) {
            dmPairingManager.failed("The other device sent a key that does not match this account's announcement.")
            return
        }
        dmPairingManager.succeeded()
        val signer = ActiveAccountManager.session.value?.signer ?: return
        val ids = listOfNotNull(event.id, pendingPairingRequestId)
        pendingPairingRequestId = null
        if (ids.isNotEmpty()) scope.launch { publishDeletion(myPub, signer, ids) }
    }

    /** NIP-09 delete for our own pairing events; best effort, relays may keep them anyway. */
    private suspend fun publishDeletion(myPub: String, signer: NostrSigner, eventIds: List<String>) {
        try {
            val deletion =
                Event(
                    pubkey = myPub,
                    createdAt = epochSeconds(),
                    kind = 5,
                    tags = eventIds.map { listOf("e", it) },
                    content = "",
                )
            publishEventToRelays(pairingRelays(myPub), signer.signEvent(deletion))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }

    /**
     * Watch our own pairing events. Both kinds are authored by our identity and are rare, so this
     * stays a small windowed subscription rather than a history read.
     *
     * The window reaches back [PAIRING_LOOKBACK_SECONDS]: the other client publishes its request
     * once and then just waits on its pairing screen, so a `since`-now filter would hide every
     * request made before this app happened to subscribe — which is the normal order (the user
     * opens the requesting client first, then comes here to approve).
     */
    // Fire-and-forget per relay: connecting is serial-blocking otherwise, and one cold or dead
    // relay in the list costs a full connect timeout that the caller has no reason to wait on.
    private suspend fun sendPairingReq(myPub: String) {
        val filter =
            buildJsonObject {
                putJsonArray("kinds") {
                    add(Nip4e.KIND_CLIENT_KEY)
                    add(Nip4e.KIND_KEY_SHARE)
                    // Deletions too: a declined request is withdrawn by a kind:5 from this same
                    // identity, which is what tells the waiting device it was turned down.
                    add(5)
                }
                putJsonArray("authors") { add(myPub) }
                put("since", epochSeconds() - PAIRING_LOOKBACK_SECONDS)
            }
        val req =
            buildJsonArray {
                add("REQ")
                add("nip4e_pair")
                add(filter)
            }.toString()
        // Fan out concurrently: a cold pool pays a connect per relay, and serially that is one
        // handshake (or dead-relay timeout) after another.
        coroutineScope {
            pairingRelays(myPub).forEach { url ->
                launch {
                    val client =
                        connectionManager.getClientForRelay(url)
                            ?: connectionManager.getOrConnectRelay(url) { m, c -> enqueueToRelayPipeline(m, c) }
                    try {
                        client?.send(req)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    private fun pairingRelays(myPub: String): List<String> = (outboxManager.getWriteRelays() + outboxManager.bootstrapRelays + dmRelaysWithDefaults(myPub)).distinct()

    /** Hold a key exported from another device. Rejected unless it matches the announced one. */
    override fun importDmEncryptionKey(privateKeyHex: String): Boolean = dmEncryptionManager.importKey(privateKeyHex)

    override fun exportDmEncryptionKey(): String? = dmEncryptionManager.exportKey()

    private suspend fun publishEventToRelays(relays: List<String>, event: Event) {
        val json =
            buildJsonArray {
                add("EVENT")
                add(event.toJsonObject())
            }.toString()
        // Fan out concurrently: the target set is write + bootstrap + DM relays, and a cold pool
        // pays a connect per relay. Serially that is one timeout after another, which the caller
        // sees as a UI action stuck for a minute.
        coroutineScope {
            relays.distinct().map { url ->
                async {
                    // Bound the connect + send per relay so a dead/half-open socket can't hang the
                    // caller (or leak the background publish job) indefinitely.
                    try {
                        withTimeoutOrNull(PUBLISH_RELAY_TIMEOUT_MS) {
                            val client =
                                connectionManager.getClientForRelay(url)
                                    ?: connectionManager.getOrConnectRelay(url) { m, c -> enqueueToRelayPipeline(m, c) }
                            client?.send(json)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * Publish one gift wrap to a relay, once through AUTH if the relay asks for it.
     *
     * A DM inbox relay that gates writes (auth.nostr1.com, and any strfry with `auth_required`)
     * answers the first EVENT with OK-false "auth-required" and issues its NIP-42 challenge in the
     * same breath. The wrap only lands if it is sent AGAIN once the challenge is answered, so the
     * publish waits out the signature here. Leaving it to the send queue's backoff instead loses:
     * every later attempt reopens the same race, so the message sits on the Sending clock while the
     * socket next to it is already authenticated.
     */
    private suspend fun sendWrapAwaitingAuth(client: NostrGroupClient, frame: String, wrapId: String): PublishResult {
        val first = client.sendAndAwaitOk(frame, wrapId, PUBLISH_RELAY_TIMEOUT_MS)
        val reason = (first as? PublishResult.Rejected)?.reason ?: return first
        if (classifyRejection(reason) != RelayRejection.Transient || !reason.trim().lowercase().startsWith("auth-required")) return first
        // A challenge the relay already sent resolves this immediately; the budget is spent only on
        // the signature itself.
        if (!client.awaitAuthSigned(PUBLISH_AUTH_SIGN_BUDGET_MS)) return first
        return client.sendAndAwaitOk(frame, wrapId, PUBLISH_RELAY_TIMEOUT_MS)
    }

    // Publish a pre-serialized gift wrap event and wait for a relay OK, so a DM has a real delivered
    // signal (not fire-and-forget). Accepted the moment the FIRST relay OKs it, naming that relay;
    // the other targets keep going and report through [onLateAccept] as they land, so the bubble
    // resolves at the speed of the fastest relay while "seen on" and the second tick still fill in
    // from the slow ones. Rejected only when every target answered OK-false for a reason retrying
    // cannot fix: one unreachable relay keeps the whole send retryable, since it might be the one
    // that would have taken it.
    private suspend fun publishWrapJsonAwaitOk(
        relays: List<String>,
        wrapJson: String,
        wrapId: String,
        onLateAccept: (List<String>) -> Unit = {},
    ): DmPublishOutcome {
        if (wrapId.isBlank()) return DmPublishOutcome.Retry
        val frame = """["EVENT",$wrapJson]"""
        val targets = relays.distinct()
        if (targets.isEmpty()) return DmPublishOutcome.Retry
        // Every target is tried, concurrently, because the peer reads from whichever of their DM
        // relays they happen to be on. The attempts run on the repository scope, not the caller's:
        // an early return below must not cancel the relays still working, and the buffered channel
        // lets each of them finish and report even if nobody is listening any more.
        val settled = Channel<Pair<String, PublishResult?>>(targets.size)
        targets.forEach { url ->
            scope.launch {
                val result =
                    try {
                        // Only the connect is bounded here: sendWrapAwaitingAuth carries its own
                        // budget, and a relay that gates writes needs more than a connect's worth
                        // of it.
                        val client =
                            withTimeoutOrNull(PUBLISH_RELAY_TIMEOUT_MS) {
                                connectionManager.getClientForRelay(url)
                                    ?: connectionManager.getOrConnectRelay(url) { m, c -> enqueueToRelayPipeline(m, c) }
                            }
                        client?.let { sendWrapAwaitingAuth(it, frame, wrapId) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        null
                    }
                settled.send(url to result)
            }
        }
        val results = mutableListOf<Pair<String, PublishResult?>>()
        repeat(targets.size) {
            val entry = settled.receive()
            results += entry
            if (!wrapAccepted(entry.second)) return@repeat
            val pending = targets.size - results.size
            if (pending > 0) {
                scope.launch {
                    repeat(pending) {
                        val (url, result) = settled.receive()
                        if (wrapAccepted(result)) onLateAccept(listOf(url))
                    }
                }
            }
            return DmPublishOutcome.Accepted(listOf(entry.first))
        }
        // A stated rejection is worth more than a timeout, which only says nobody answered, so a
        // send is final only when every target refused it outright.
        val refusals =
            results.mapNotNull { (url, result) ->
                (result as? PublishResult.Rejected)
                    ?.takeIf { classifyRejection(it.reason) == RelayRejection.Permanent }
                    ?.let { url to it.reason }
            }
        if (refusals.size != results.size) return DmPublishOutcome.Retry
        val (url, why) = refusals.first()
        return DmPublishOutcome.Rejected("${relayHost(url)} refused it: $why")
    }

    // "duplicate" counts as acceptance: the relay is telling us it already holds this wrap, which
    // is exactly what the retry wanted. Anything else keeps its OK-false meaning.
    private fun wrapAccepted(result: PublishResult?): Boolean = when (result) {
        is PublishResult.Success -> true
        is PublishResult.Rejected -> classifyRejection(result.reason) == RelayRejection.AlreadyStored
        else -> false
    }

    private fun relayHost(url: String): String = url.removePrefix("wss://").removePrefix("ws://").trimEnd('/')

    // Persisted DM send queue: undelivered wraps by account, mirrored to SecureStorage. Retries
    // never expire, so a wrap written offline lands on the next reconnect or app launch.
    private val dmSendQueue by lazy {
        DmSendQueue(
            scope = scope,
            publish = { relays, wrapJson, wrapId, onLateAccept -> publishWrapJsonAwaitOk(relays, wrapJson, wrapId, onLateAccept) },
            onDelivered = { rumorId, relays ->
                dmManager.markDelivered(rumorId)
                dmManager.recordSentTo(rumorId, relays)
            },
            onRejected = { rumorId, reason -> dmManager.markFailed(rumorId, reason) },
            onQueued = { rumorId -> dmManager.setSending(rumorId) },
            persist = { pubkey, entries -> SecureStorage.saveDmSendQueue(pubkey, entries) },
        )
    }

    override fun retryDm(rumorId: String) {
        dmSendQueue.retry(rumorId)
    }

    override fun dismissDm(rumorId: String) {
        dmSendQueue.dismiss(rumorId)
        dmManager.dismissFailed(rumorId)
        // Also drop the cached copy, or the dismissed message comes back on the next cold open.
        val myPub = sessionManager.getPublicKey() ?: return
        dmPersistedIds = dmPersistedIds - rumorId
        scope.launch { runCatching { cacheStore.deleteByIds(myPub, listOf(rumorId)) } }
    }

    // Resume undelivered wraps from disk on login so a send interrupted by an app close still lands.
    private suspend fun resumeDmSendQueue(myPub: String) {
        dmSendQueue.resume(myPub, SecureStorage.loadDmSendQueue(myPub))
    }

    /** Connect to our DM relays and subscribe to the kind:1059 inbox so DMs arrive in real time. */
    suspend fun startDmInbox() {
        if (!AppModule.dmSettings.dmEnabled.value) return
        if (dmInboxStarted) return
        val myPub = sessionManager.getPublicKey() ?: return
        dmInboxStarted = true

        dmArchiveManager.hydrate(SecureStorage.loadDmArchivedRumorIdsFor(myPub))

        // Seed the follow set from its persisted cache before the DM list partitions, so
        // conversations land in Follows/Others immediately instead of all falling in Others until
        // the kind:3 arrives from a relay (the visible "reorganizing" delay). The live kind:3
        // corrects it when it lands. Only seed when empty so it never clobbers a fresher list.
        if (_following.value.isEmpty()) {
            val cached = SecureStorage.loadFollowingCacheFor(myPub)
            if (cached.isNotEmpty()) _following.value = cached.toSet()
        }

        // Hydrate from the CacheStore before the inbox streams so old conversations render instantly
        // and already-seen gift wraps are never re-decrypted. Nothing that touches a relay may run
        // ahead of this: a cold pool pays a connect (or a timeout) per relay, and the conversations
        // are on disk the whole time.
        val hydratedCount = hydrateDmCache(myPub)
        wireDmPersistence(myPub)
        // Resume any wraps that never reached a relay before the app last closed.
        resumeDmSendQueue(myPub)
        // NIP-4e pairing listens for our own key-share events; it is not on the read path, so it
        // never gates the messages appearing.
        scope.launch { sendPairingReq(myPub) }

        refreshDmSyncing()
        dmBacklogHoldUntilMs = org.nostr.nostrord.utils.epochMillis() + DM_BACKLOG_BOOT_HOLD_MS
        dmTrickleMutex.withLock {
            dmEagerDecryptCount = 0
            dmTrickleNextSlotMs = 0L
        }

        // Resume per-wrap decrypt progress, and reset the this-session sync bookkeeping.
        dmProcessedWrapIds = SecureStorage.loadDmProcessedWrapIds(myPub)
        dmReceivedWrapIds = emptySet()
        dmInFlightWrapIds = emptySet()
        dmFailCounts = emptyMap()
        dmGivenUpWrapIds = emptySet()
        dmGivenUpOldestAt = 0L
        dmInboxEosedRelays = emptySet()

        // Consistency guard: decrypt progress claims processed wraps, but the message store
        // came back empty (wiped/lost store, or a failed cache read — hydrateDmCache swallows
        // it). Trusting the progress here is fatal: the full-sync flag keeps the REQ
        // incremental so the history is never re-requested, and any re-delivered wrap is
        // skipped by the dedup — the inbox looks synced and stays empty until the user
        // manually wipes storage. Drop the sync state so the REQ below streams from 0 and
        // rebuilds the two stores together.
        if (hydratedCount == 0 && dmProcessedWrapIds.isNotEmpty()) {
            dmProcessedWrapIds = emptySet()
            SecureStorage.saveDmProcessedWrapIds(myPub, emptySet())
            SecureStorage.saveBooleanPref(dmFullSyncKey(myPub), false)
        }

        // One-shot rescan: kind:15 file messages used to be decrypted, discarded and then marked
        // processed, so every attachment already received is invisible AND permanently skipped by
        // the dedup. Drop the progress once so the REQ streams from 0 and the backlog is decrypted
        // again, this time keeping the file messages.
        if (!SecureStorage.getBooleanPref(dmFileRescanKey(myPub), false)) {
            dmProcessedWrapIds = emptySet()
            SecureStorage.saveDmProcessedWrapIds(myPub, emptySet())
            SecureStorage.saveBooleanPref(dmFullSyncKey(myPub), false)
            SecureStorage.saveBooleanPref(dmFileRescanKey(myPub), true)
        }

        _myDmRelays.value = dmRelaysFor(myPub)
        fetchDmRelays(myPub)
        resendDmInboxReq(myPub)
        // The sync cursor + full-sync flag advance on the dm_inbox EOSE (handleUnifiedMessage),
        // not here: advancing unconditionally on open skipped the whole undecrypted backlog on a
        // device that had never synced it. The EOSE means the relay actually delivered everything.

        // Make ourselves reachable: publish a default kind:10050 only if the account truly has none.
        // Gate on the fetch landing an event (our pubkey appearing in dmRelaysByPubkey), not a fixed
        // delay - the old 4s fired before the fetch finished and overwrote existing lists with the
        // defaults. Registered as a dmPersistenceJob: stopDmInbox clears dmRelaysByPubkey and the
        // ingest guard drops the real 10050 while disabled, so an orphaned job would deterministically
        // time out and overwrite the user's published list with the defaults mid-disable.
        dmPersistenceJobs +=
            scope.launch {
                val found = withTimeoutOrNull(12_000) { dmManager.dmRelaysByPubkey.first { myPub in it } } != null
                if (found) {
                    _myDmRelays.value = dmRelaysFor(myPub)
                } else if (AppModule.dmSettings.dmEnabled.value && dmInboxStarted) {
                    publishDmRelayList(defaultDmRelays)
                }
            }

        // Retry undecrypted wraps as the (flaky) bunker signer recovers: a timed-out nip44_decrypt
        // leaves its wrap un-acked, so periodically re-stream the inbox window. The persisted dedup
        // skips wraps already decrypted, so each pass only re-attempts the ones still missing.
        // Registered as a dmPersistenceJob so it's cancelled on logout / account switch.
        dmPersistenceJobs +=
            scope.launch {
                // Parked wraps are excluded from `pending` below, so without this the pass that
                // parks the last one is the last pass that re-streams anything: the DMs stay
                // missing until the next launch. Unparking hands them back to the same resend.
                var givenUpRetryAtMs = 0L
                var givenUpBackoffMs = DM_GIVEN_UP_RETRY_MIN_MS
                while (true) {
                    delay(120_000)
                    val pub = sessionManager.getPublicKey() ?: break
                    val nowMs = org.nostr.nostrord.utils.epochMillis()
                    if (nowMs >= givenUpRetryAtMs && unparkGivenUpDmWraps()) {
                        givenUpRetryAtMs = nowMs + givenUpBackoffMs
                        givenUpBackoffMs = minOf(givenUpBackoffMs * 2, DM_GIVEN_UP_RETRY_MAX_MS)
                    }
                    val pending = dmReceivedWrapIds.count { it !in dmProcessedWrapIds && it !in dmGivenUpWrapIds }
                    if (pending > 0) {
                        resendDmInboxReq(pub)
                    } else {
                        // Nothing arriving means nothing calls the latch: re-check here so an
                        // expired EOSE deadline can still close the sync.
                        maybeLatchDmFullSync(pub)
                    }
                }
            }

        // A signer outage (revoked permission, offline bunker) burns every backlog wrap's
        // decrypt attempts into the given-up set, which parks those DMs until an app
        // restart. When the bunker comes back (banner Reconnect, auto-reconnect), give
        // them a fresh run: the persisted dedup keeps already-decrypted wraps cheap, so
        // re-streaming only re-attempts what's actually missing.
        dmPersistenceJobs +=
            scope.launch {
                sessionManager.bunkerState
                    .map { it is BunkerState.Connected }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { connected ->
                        if (!connected) return@collect
                        if (!unparkGivenUpDmWraps()) return@collect
                        sessionManager.getPublicKey()?.let { resendDmInboxReq(it) }
                    }
            }
    }

    /**
     * Clear the parked (given-up) wraps and their failure counts so the inbox resend re-attempts
     * them. False when there was nothing parked, so callers can skip the resend.
     */
    private fun unparkGivenUpDmWraps(): Boolean {
        if (dmGivenUpWrapIds.isEmpty() && dmFailCounts.isEmpty()) return false
        dmGivenUpWrapIds = emptySet()
        dmGivenUpOldestAt = 0L
        dmFailCounts = emptyMap()
        refreshDmSyncing()
        return true
    }

    /**
     * Tear the DM inbox down: close the kind:1059 subscription on every relay it was streaming from,
     * cancel the retry/persistence jobs, and drop all decrypted state. Called when the master DM
     * toggle is switched off (Settings → Direct Messages) so the app stops fetching and decrypting
     * DMs entirely. Idempotent — a no-op when the inbox was never started.
     */
    private fun stopDmInbox() {
        if (!dmInboxStarted) return
        dmInboxStarted = false
        _dmSyncing.value = false
        dmPersistenceWired = false
        dmPersistenceJobs.forEach { it.cancel() }
        dmPersistenceJobs.clear()
        val relays = dmInboxSubscribedRelays
        dmInboxSubscribedRelays = emptySet()
        relays.forEach { url ->
            scope.launch {
                try {
                    connectionManager.getClientForRelay(url)?.send("""["CLOSE","dm_inbox"]""")
                } catch (_: Throwable) {
                }
            }
        }
        dmManager.clear()
        dmArchiveManager.clear()
        _myDmRelays.value = emptyList()
        dmInboxEosedRelays = emptySet()
        // Drop the in-memory send queue; the persisted per-account copy stays on disk and is
        // reloaded by resumeDmSendQueue when this (or another) account's inbox next starts.
        dmSendQueue.clear()
    }

    // Ids already written to the CacheStore, so the persistence collector upserts only new DMs
    // instead of rewriting the whole history on every change. Seeded by hydrateDmCache.
    private var dmPersistedIds: Set<String> = emptySet()

    private fun DmMessage.toCachedMsg(): org.nostr.nostrord.storage.cache.CachedMsg = org.nostr.nostrord.storage.cache.CachedMsg(
        id = id,
        groupId = peerPubkey,
        pubkey = senderPubkey,
        createdAt = createdAt,
        // A file message keeps its own kind so the hydrated row still renders as an attachment
        // instead of falling back to its blob url as text.
        kind = if (kind == Nip17.KIND_FILE) DM_FILE_CACHE_KIND else DM_CACHE_KIND,
        content = content,
        // The rumor's real tags: with them the row IS the rumor, so toDmMessage can rebuild the
        // exact event JSON across restarts.
        tagsJson = rumorJson?.let { rj ->
            runCatching { Json.parseToJsonElement(rj).jsonObject["tags"]?.toString() }.getOrNull()
        } ?: "[]",
    )

    /** Every cached DM row for [myPub]: text and file messages live under different kinds. */
    private suspend fun loadCachedDms(myPub: String): List<org.nostr.nostrord.storage.cache.CachedMsg> = DM_CACHE_KINDS.flatMap { cacheStore.loadByKind(myPub, it) }

    private fun org.nostr.nostrord.storage.cache.CachedMsg.toDmMessage(myPubkey: String): DmMessage {
        // Rows written before file messages had their own cache kind all claim kind:14, so the
        // file-type tag is what identifies an attachment; without this an existing one hydrates
        // as text and renders its blob url. Matched on the raw json: hydration runs over the whole
        // history at startup, and parsing every row's tags to answer this costs more than it saves.
        val rumorKind =
            if (kind == DM_FILE_CACHE_KIND || tagsJson.contains("\"${Nip17File.TAG_FILE_TYPE}\"")) {
                Nip17.KIND_FILE
            } else {
                Nip17.KIND_CHAT
            }
        return DmMessage(
            id = id,
            peerPubkey = groupId,
            senderPubkey = pubkey,
            content = content,
            createdAt = createdAt,
            orderKey = DmMessageOrder.orderKey(createdAt, tagsJson),
            mine = pubkey == myPubkey,
            kind = rumorKind,
            // Assembled as text around the tags exactly as they were stored, rather than parsed
            // into a tree and re-serialized: only the content needs escaping.
            rumorJson =
            """{"id":"$id","pubkey":"$pubkey","created_at":$createdAt,"kind":$rumorKind,"tags":$tagsJson,"content":${JsonPrimitive(content)}}""",
        )
    }

    /**
     * Load DM history from the CacheStore (migrating the legacy SecureStorage blob the first time).
     * DMs are stored in the message cache as kind:14 keyed by peer pubkey, so one query rebuilds
     * every conversation without knowing the peers up front.
     */
    /** Returns how many cached DMs were hydrated, so the caller can sanity-check sync state. */
    private suspend fun hydrateDmCache(myPub: String): Int {
        if (!SecureStorage.isDmCacheMigratedFor(myPub)) {
            val legacy = SecureStorage.loadDmMessages(myPub)
            val migrated =
                try {
                    legacy.groupBy { it.peerPubkey }.forEach { (peer, msgs) ->
                        cacheStore.upsertMessages(myPub, peer, msgs.map { it.toCachedMsg() })
                    }
                    true
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
            // Only retire the legacy blob once it is safely in the cache; a failed migration retries
            // next launch instead of losing history.
            if (migrated) {
                SecureStorage.setDmCacheMigratedFor(myPub)
                SecureStorage.clearDmMessages(myPub)
            }
        }
        val cached =
            try {
                loadCachedDms(myPub)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        dmPersistedIds = cached.mapTo(HashSet()) { it.id }
        dmManager.hydrateReactions(SecureStorage.loadDmReactions(myPub))
        dmManager.hydrate(
            cached.map { it.toDmMessage(myPub) },
            SecureStorage.loadDmLastRead(myPub),
            SecureStorage.loadDmSeenRelays(myPub),
            SecureStorage.loadDmWrapRumor(myPub),
            SecureStorage.loadDmEncKeys(myPub),
        )
        return cached.size
    }

    /** Persist decrypted messages + read state whenever they change (one collector per session). */
    private fun wireDmPersistence(myPub: String) {
        if (dmPersistenceWired) return
        dmPersistenceWired = true
        dmPersistenceJobs +=
            scope.launch {
                dmManager.messagesByPeer.drop(1).collect { byPeer ->
                    // Upsert only messages not yet cached; never rewrite the whole history.
                    val fresh = byPeer.values.flatten().filter { it.id !in dmPersistedIds }
                    if (fresh.isNotEmpty()) {
                        try {
                            fresh.groupBy { it.peerPubkey }.forEach { (peer, msgs) ->
                                cacheStore.upsertMessages(myPub, peer, msgs.map { it.toCachedMsg() })
                            }
                            dmPersistedIds = dmPersistedIds + fresh.map { it.id }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // A transient cache write error (e.g. SQLITE_BUSY while group writes hit
                            // the same db) must NOT kill the collector: leave these ids unpersisted
                            // so the next emission retries them, instead of stranding DMs at the
                            // migrated count until restart.
                        }
                    }
                }
            }
        dmPersistenceJobs +=
            scope.launch {
                dmManager.lastReadByPeer.drop(1).collect { reads ->
                    SecureStorage.saveDmLastRead(myPub, reads)
                }
            }
        // Reactions are small and rarely change, so the whole set is rewritten rather than
        // diffed. They must be persisted at all: the inbox never re-decrypts a wrap it has
        // already processed, so a reaction only in memory is gone after a restart.
        dmPersistenceJobs +=
            scope.launch {
                dmManager.reactionRumorsByPeer.drop(1).collect { byPeer ->
                    SecureStorage.saveDmReactions(myPub, byPeer.values.flatten().sortedBy { it.createdAt })
                }
            }
        // Persist the "seen on" relay map (debounced) so View source keeps it across restarts.
        dmManager.onSeenRelaysChanged = { scheduleSaveDmMaps(myPub) }
        // Peer encryption keys ride the same debounce: both are small maps written on arrival.
        dmManager.onEncKeysChanged = { scheduleSaveDmMaps(myPub) }
    }

    private fun scheduleSaveDmMaps(myPub: String) {
        dmSeenRelaysSaveJob?.cancel()
        dmSeenRelaysSaveJob = scope.launch {
            delay(2_000)
            SecureStorage.saveDmSeenRelays(myPub, dmManager.seenRelaysSnapshot())
            SecureStorage.saveDmWrapRumor(myPub, dmManager.wrapToRumorSnapshot())
            SecureStorage.saveDmEncKeys(myPub, dmManager.encKeysSnapshot())
        }
    }

    /** (Re)subscribe to the kind:1059 inbox on all DM relays. Idempotent; also run on reconnect. */
    // v2: the v1 flag was latched on bare EOSE (before decryption finished), so it could be stuck
    // true with an incomplete backlog. v2 latches only when every delivered wrap is processed.
    private fun dmFullSyncKey(pubkey: String) = "dm_full_synced_v2_$pubkey"

    /** Marks the one-shot backlog rescan that recovers kind:15 file messages. */
    private fun dmFileRescanKey(pubkey: String) = "dm_file_rescan_$pubkey"

    // Persist decrypt progress, debounced so a streaming backlog doesn't hammer storage.
    private fun scheduleSaveProcessedWrapIds(myPub: String) {
        dmProcessedSaveJob?.cancel()
        dmProcessedSaveJob = scope.launch {
            delay(2_000)
            SecureStorage.saveDmProcessedWrapIds(myPub, dmProcessedWrapIds)
        }
    }

    // Latch the persisted "full sync done" flag + advance the cursor ONLY once the inbox has
    // EOSEd and every wrap it delivered this session is processed. Until then resendDmInboxReq
    // keeps `since = 0`, so a slow/interrupted bunker backfill resumes next session (skipping the
    // wraps already in dmProcessedWrapIds) instead of stranding the undecrypted ones.
    private fun maybeLatchDmFullSync(myPub: String) {
        if (SecureStorage.getBooleanPref(dmFullSyncKey(myPub), false)) return
        // Every subscribed DM relay must have EOSEd. Otherwise a fast relay that returns nothing (or
        // a subset) latches "synced" while another relay still holds the bulk of the backlog, which
        // freezes `since` at now so the next launch's incremental REQ excludes the older wraps and
        // they are never re-requested (the inbox stalls partway through across restarts).
        val subscribed = dmInboxSubscribedRelays
        if (subscribed.isEmpty()) return
        // Past the deadline the silent relays count as settled, but at least one relay must have
        // EOSEd: latching off a pool where nothing answered would call an unsynced inbox complete.
        val deadlinePassed = dmInboxEoseDeadlineMs > 0L && org.nostr.nostrord.utils.epochMillis() > dmInboxEoseDeadlineMs
        if (!dmInboxEosedRelays.containsAll(subscribed) && !(deadlinePassed && dmInboxEosedRelays.isNotEmpty())) return
        // Wraps given up this session count as settled too, otherwise one permanently undecryptable
        // wrap pins `since = 0` and every launch re-streams the entire backlog. They are not lost:
        // the cursor below rewinds to the oldest of them so the next REQ still covers them.
        if (dmReceivedWrapIds.any { it !in dmProcessedWrapIds && it !in dmGivenUpWrapIds }) return
        SecureStorage.saveDmProcessedWrapIds(myPub, dmProcessedWrapIds)
        SecureStorage.saveBooleanPref(dmFullSyncKey(myPub), true)
        val now = epochSeconds()
        val cursor = if (dmGivenUpOldestAt > 0L) minOf(dmGivenUpOldestAt, now) else now
        SecureStorage.saveDmSyncCursor(myPub, cursor)
    }

    // DM relays the inbox sub is currently subscribed on, so a re-derived (but unchanged)
    // relay list doesn't re-issue the REQ and re-stream the whole backlog at the bunker.
    private var dmInboxSubscribedRelays: Set<String> = emptySet()

    private val _dmSyncing = MutableStateFlow(false)

    override val dmSyncing: StateFlow<Boolean> = _dmSyncing.asStateFlow()

    /**
     * Recompute whether the inbox is still catching up: a relay has not EOSEd yet, or a delivered
     * wrap is still waiting to be decrypted. Called wherever that bookkeeping moves.
     *
     * The UI shows this instead of blocking the composer. "Everything has arrived" is not something
     * a Nostr client can assert: NIP-59 backdates a gift wrap by up to two days, so a relay can
     * hand over an old message at any moment. Saying so is honest; refusing to send would not be.
     */
    private fun refreshDmSyncing() {
        val subscribed = dmInboxSubscribedRelays
        val awaitingEose = subscribed.isEmpty() || !dmInboxEosedRelays.containsAll(subscribed)
        val awaitingDecrypt = dmReceivedWrapIds.any { it !in dmProcessedWrapIds && it !in dmGivenUpWrapIds }
        _dmSyncing.value = dmInboxStarted && (awaitingEose || awaitingDecrypt)
    }

    private suspend fun resendDmInboxReq(myPub: String) {
        // Sync the whole inbox until it has EOSEd with every delivered wrap decrypted (dmFullSyncKey),
        // then go incremental from the saved cursor. Streaming everything is safe: the in-flight
        // dedup plus the bounded decrypt semaphore cap the load on the signer regardless of how many
        // wraps arrive, so the relay window doesn't need to be limited.
        val fullSynced = SecureStorage.getBooleanPref(dmFullSyncKey(myPub), default = false)
        val since = if (!fullSynced) 0L else SecureStorage.loadDmSyncCursor(myPub)
        val filter =
            buildJsonObject {
                putJsonArray("kinds") { add(Nip17.KIND_GIFT_WRAP) }
                putJsonArray("#p") { add(myPub) }
                if (since > 0L) put("since", since - GIFT_WRAP_BACKDATE_SECONDS)
            }
        val req =
            buildJsonArray {
                add("REQ")
                add("dm_inbox")
                add(filter)
            }.toString()
        val urls = dmRelaysWithDefaults(myPub).distinct()
        val urlSet = urls.toSet()
        // Arm (or re-arm) the EOSE deadline only when a relay joins the sub: a plain resend of the
        // same set must not keep pushing the deadline out, or the 120s retry loop below would defer
        // the latch forever on a relay that never EOSEs.
        if (!dmInboxSubscribedRelays.containsAll(urlSet)) {
            dmInboxEoseDeadlineMs = org.nostr.nostrord.utils.epochMillis() + DM_INBOX_EOSE_TIMEOUT_MS
        }
        dmInboxSubscribedRelays = urlSet
        // Keep DM relays alive: register them with the reconnect scheduler so a dropped DM
        // socket is revived (and re-subscribed via resubscribePoolRelay) rather than going
        // silent until the next app start. They host no NIP-29 groups, so group-resubscribe
        // on them is a harmless no-op.
        connectedPoolRelays.addAll(urls)
        // One coroutine per relay: a relay that is down costs its own connect timeout instead of
        // delaying the REQ to every relay behind it in the list.
        urls.forEach { url ->
            scope.launch {
                val client =
                    connectionManager.getClientForRelay(url)
                        ?: connectionManager.getOrConnectRelay(url) { m, c -> enqueueToRelayPipeline(m, c) }
                try {
                    client?.send(req)
                } catch (_: Throwable) {
                }
            }
        }
    }

    /** Re-arm the DM inbox after a relay reconnect so real-time receive survives drops. */
    private fun resubscribeDmInbox() {
        if (!dmInboxStarted) return
        val myPub = sessionManager.getPublicKey() ?: return
        scope.launch { resendDmInboxReq(myPub) }
    }

    /**
     * Publish our NIP-17 DM relay list (kind:10050) so other clients know where to send our DMs.
     * Replaceable event with a `relay` tag per URL. Published to general relays + the DM relays.
     */
    override suspend fun publishDmRelayList(relays: List<String>): Result<Unit> {
        val myPub = sessionManager.getPublicKey() ?: return Result.Error(AppError.Auth.NotAuthenticated)
        val clean = relays.map { it.normalizeRelayUrl() }.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) return Result.Error(AppError.Unknown("No DM relays to publish"))
        return try {
            val event =
                Event(
                    pubkey = myPub,
                    createdAt = org.nostr.nostrord.utils.epochSeconds(),
                    kind = Nip17.KIND_DM_RELAYS,
                    tags = clean.map { listOf("relay", it) },
                    content = "",
                )
            val signed = sessionManager.signEvent(event)
            dmManager.ingestDmRelays(signed)
            _myDmRelays.value = clean
            val targets = (clean + outboxManager.getWriteRelays() + outboxManager.bootstrapRelays).distinct()
            publishEventToRelays(targets, signed)
            Result.Success(Unit)
        } catch (e: NostrSigner.SigningException) {
            Result.Error(AppError.Unknown("Your signer rejected publishing the DM relay list."))
        } catch (e: Throwable) {
            Result.Error(AppError.Unknown(e.message ?: "Failed to publish DM relays"))
        }
    }

    /**
     * One-shot fetch of [pubkey]'s kind:10050 DM relay list and kind:10044 NIP-4e encryption key.
     * Queries the user's NIP-65 write relays and bootstrap relays (where the replaceable lists are
     * published, see publishDmRelayList) plus the defaults, so an existing list is actually found
     * instead of falsely read as absent. Both replaceables ride one REQ: they are published to the
     * same relay set and both are needed before the first send to this peer.
     */
    private suspend fun fetchDmRelays(pubkey: String) {
        val filter =
            buildJsonObject {
                putJsonArray("kinds") {
                    add(Nip17.KIND_DM_RELAYS)
                    add(Nip4e.KIND_ENCRYPTION_KEY)
                }
                putJsonArray("authors") { add(pubkey) }
            }
        val req =
            buildJsonArray {
                add("REQ")
                add("dmrelays_${pubkey.take(8)}")
                add(filter)
            }.toString()
        val fetchFrom = (defaultDmRelays + outboxManager.getWriteRelays() + outboxManager.bootstrapRelays).distinct()
        fetchFrom.forEach { url ->
            val client =
                connectionManager.getClientForRelay(url)
                    ?: connectionManager.getOrConnectRelay(url) { m, c -> enqueueToRelayPipeline(m, c) }
            try {
                client?.send(req)
            } catch (_: Throwable) {
            }
        }
    }

    /** Drops the previous account's kind:3 state so [following] never leaks across accounts. */
    private fun resetContactListState() {
        contactListCreatedAt = 0L
        contactListContent = ""
        contactListTags = emptyList()
        contactListRequested = false
        // Stop any background retry from the previous account; the new account re-arms it.
        contactListRetryJob?.cancel()
        contactListRetryJob = null
        // Cancel any in-flight debounced publish and clear the loaded flag, so the new
        // account starts "not loaded" (UI shows its cache, not a false "follows nobody")
        // and a pending publish never targets the wrong account.
        pendingContactListPublish?.cancel()
        pendingContactListPublish = null
        hasUnpublishedContactChanges = false
        _contactListLoaded.value = false
        _following.value = emptySet()
        // The mute list is per-account too; same lifecycle as the contact list.
        pendingMuteListPublish?.cancel()
        pendingMuteListPublish = null
        hasUnpublishedMuteChanges = false
        muteListCreatedAt = 0L
        muteListContent = ""
        muteListPublicTags = emptyList()
        muteListPrivateTags = emptyList()
        muteListPrivateDecryptedFrom = ""
        muteListRequested = false
        muteListPrivateApplyJob?.cancel()
        muteListPrivateApplyJob = null
        muteListPendingPrivate = null
        notifPrefsApplyJob?.cancel()
        notifPrefsApplyJob = null
        notifPrefsPendingContent = null
        notifPrefsNewestSeenAt = 0L
        selfDecryptCache.clear()
        publicMuted = emptySet()
        privateMuted = emptySet()
        _mutedPubkeys.value = emptySet()
        dmPersistenceJobs.forEach { it.cancel() }
        dmPersistenceJobs.clear()
        dmManager.clear()
        // Not dmEncryptionManager: the session collector owns it, and clearing here would race a
        // warm account switch that has already installed the new signer.
        dmArchiveManager.clear()
        _myDmRelays.value = emptyList()
        dmInboxStarted = false
        dmPersistenceWired = false
    }

    override fun forgetBunkerConnection() {
        sessionManager.forgetBunkerConnection()
    }

    /**
     * Arm a one-shot catch-up `since` for the upcoming mux refresh so the first
     * subscription after connect replays everything that arrived for [pubkey]
     * while it was inactive (account switched away, or the app was closed).
     *
     * The window starts at the earliest of the account's last-active heartbeat
     * and the newest notification it already has, minus a small overlap. When
     * the account has never been active before (no data point exists) the
     * default per-group windowing in LiveCursorStore is left in charge.
     *
     * Used on both warm account swaps ([reloadForActiveAccount]) and cold start
     * ([initialize] / cold-start [finishLoginInit]) — without it, a real
     * close+reopen would only surface realtime events, never the missed backlog.
     */
    private fun applyCatchUpSinceFor(pubkey: String) {
        val lastActiveAt = SecureStorage.getLastActiveAt(pubkey)
        val newestNotif = notificationHistoryStore?.entries?.value?.firstOrNull()?.createdAt ?: 0L
        if (lastActiveAt > 0L || newestNotif > 0L) {
            val now = epochSeconds()
            val candidate = listOf(lastActiveAt, newestNotif).filter { it > 0L }.min()
            val capped = maxOf(
                candidate - LiveCursorStore.RECONNECT_OVERLAP_S,
                now - LiveCursorStore.MAX_SINCE_AGE_S,
            )
            groupManager.setCatchUpSince(capped)
            // Also pin UnreadManager's "first seen" fallback to the same value
            // so events arriving via catch-up qualify (otherwise they're
            // filtered as createdAt < firstSeenAt(now) for groups the active
            // account has never opened).
            unreadManager.setCatchUpAnchor(capped)
        } else {
            groupManager.setCatchUpSince(null)
            unreadManager.setCatchUpAnchor(null)
        }
    }

    override suspend fun reloadForActiveAccount() {
        val pubkey = sessionManager.getPublicKey() ?: return
        // The contact list is per-account; drop the prior account's before the swap.
        resetContactListState()
        // Re-seed the incoming account's mutes so filtering holds through the swap;
        // the network refresh rides the requestContactList() below.
        hydrateMuteListFromCache(pubkey)
        // Re-arm on the incoming account. A warm swap initializes notificationSettings
        // from AppModule, not from here, so without this the watcher stays bound to the
        // previous pubkey and files B's changes under A.
        startNotificationPrefsSync(pubkey)
        // Same session-cache teardown the full-logout path runs, so a warm swap does not
        // leak account A's restricted-relay / dedup state into account B (which otherwise
        // left B's groups dark until an app restart). Runs BEFORE B's re-derive below.
        resetSessionScopedCaches()
        val activeRelay = connectionManager.currentRelayUrl.value
        val savedRelays = SecureStorage.loadRelayListFor(pubkey)

        applyCatchUpSinceFor(pubkey)

        // Pre-fill the rail for the new account before the kind:10009 fetch
        // returns; without this the sidebar would show empty until the network
        // resolves, even for accounts that already have a persisted relay list.
        outboxManager.seedFromCache(pubkey)

        // Mirror initialize()/finishLoginInit's full relay+group bootstrap so a
        // warm account swap establishes the SAME state as a cold start. The swap
        // previously loaded joined groups but skipped prePopulateRelayList / the
        // cursor + NIP-11 metadata bootstrap, and elected no focused when the new
        // account had a blank current-relay slot — leaving it on empty groups /
        // a one-relay rail / stale icons until the app was restarted.
        val allRelays = (listOfNotNull(activeRelay.ifBlank { null }) + savedRelays).distinct()
        val focusedRelay = activeRelay.ifBlank { allRelays.firstOrNull().orEmpty() }
        if (focusedRelay.isNotBlank()) {
            groupManager.prePopulateRelayList(allRelays)
            liveCursorStore?.loadAll(allRelays)
            _relayMetadataManager.fetchAll(allRelays)
            groupManager.loadJoinedGroupsFromStorage(pubkey, focusedRelay)
            groupManager.loadAllJoinedGroupsFromStorage(pubkey, allRelays)
            groupManager.restoreJoinedGroupMetadataFromStorage(pubkey, allRelays)
            groupManager.restoreGroupMembershipFromStorage(pubkey)
            outboxManager.restoreGroupOrder(pubkey)
            groupManager.migrateMessageBlobsToCache(pubkey)
            groupManager.loadRestrictedGroupsFromStorage(pubkey, allRelays)
            // Point GroupManager's current-relay flow at the new focused so the
            // derived joinedGroups (My Groups — sidebar AND homescreen) reflects
            // this account immediately. applyActiveAccountChange.clear() nulled it,
            // and the warm-swap path only re-sets it if reconnect() actually runs
            // (non-blank relay + a live message handler) via resubscribeAllGroups
            // — so without this, My Groups stayed empty after an account switch.
            groupManager.restoreGroupsForRelay(focusedRelay)
            // The account had no persisted current relay — elect one so the
            // focused actually connects (and its cache-hit mux is set up) instead
            // of relying on a reconnect that no-ops against a blank current relay.
            if (activeRelay.isBlank()) {
                SecureStorage.saveCurrentRelayUrlFor(pubkey, focusedRelay)
                connectionManager.loadSavedRelay()
            }
        }

        initializeOutboxModel()
        scope.launch {
            outboxManager.loadJoinedGroupsFromNostr(pubkey) { msg, c ->
                enqueueToRelayPipeline(msg, c)
            }
        }

        // Force re-subscribe on the active relay so pubkey-filtered REQs
        // reissue with the new identity instead of carrying stale ones.
        // Background relays still hold sockets authed as the previous account,
        // so the catch-up mux REQ would either be filtered or silently dropped
        // until the next AUTH challenge; clear their mux tracker and reconnect
        // them so the new identity's AUTH runs before subs go out (matches
        // [ensureJoinedRelaysConnected] in the boot path).
        if (focusedRelay.isNotBlank()) {
            triggerReconnect()
        }
        scope.launch {
            // Force a fresh socket per secondary joined relay so it re-runs NIP-42 AUTH as
            // the NEW account. Warm swaps leave these sockets open and AUTH'd as the PREVIOUS
            // account, and ensureJoinedRelaysConnected short-circuits any relay that is still
            // connected — so no fresh challenge fires and an auth-gated relay withholds the new
            // account's group metadata AND membership (total blackout, e.g. chat.wisp.talk)
            // until an app restart. NIP-42 is reactive: only a fresh socket triggers the
            // challenge the signer answers with the new identity. Drop from connectedPoolRelays
            // first so the pool-lost reconnect scheduler doesn't race the reopen below. Cost
            // matches a cold boot (one AUTH per relay), and the focused is reconnected above.
            try {
                val focusedNorm = focusedRelay.normalizeRelayUrl()
                for (relayUrl in groupManager.joinedGroupsByRelay.value.keys.toList()) {
                    val norm = relayUrl.normalizeRelayUrl()
                    if (norm.isBlank() || norm == focusedNorm) continue
                    connectedPoolRelays.remove(norm)
                    connectionManager.disconnectRelay(norm)
                }
            } catch (_: Exception) {}
            // The loop above only covers the INCOMING account's joined relays, which is empty for
            // a brand-new account — leaving the outgoing account's sockets open and still AUTH'd
            // as it. Opening a private group only that account belongs to then reads it in full,
            // because the relay answers under the socket's identity. Sweep by AUTH identity so
            // every leftover socket is dropped regardless of whose relay list it is on.
            try {
                connectionManager.disconnectSocketsAuthedAsOther(pubkey)
                    .forEach { connectedPoolRelays.remove(it) }
            } catch (_: Exception) {}
            try {
                ensureJoinedRelaysConnected(focusedRelay.takeIf { it.isNotBlank() })
            } catch (_: Exception) {}
            // Safety net for the active relay and any joined relay not covered
            // above: applies the catch-up `since` set earlier so events missed
            // while inactive arrive across every relay.
            try {
                groupManager.refreshLiveSubscriptions()
            } catch (_: Exception) {}
            // Re-fetch kind:39000 metadata across EVERY joined relay. A warm swap reuses
            // already-open secondary-relay sockets, so no fresh AUTH fires and
            // resubscribeAfterAuth (the only reconnect path that runs requestPrivateGroupData)
            // never re-fetches their groups' metadata — leaving the new account's group cards
            // on "No description" + a fallback avatar until a restart forces a cold boot. Mirror
            // connect()'s post-EOSE fetch here so the new account's metadata fills in immediately.
            try {
                for (relayUrl in groupManager.joinedGroupsByRelay.value.keys.toList()) {
                    val client = connectionManager.getClientForRelay(relayUrl) ?: continue
                    if (!client.isConnected()) continue
                    requestGroupsForRelay(client, relayUrl)
                    groupManager.requestPrivateGroupData(relayUrl)
                    groupManager.requestActiveGroupMetadataIfMissing(relayUrl)
                    scheduleMissingMetadataSweep(relayUrl)
                }
            } catch (_: Exception) {}
            // Re-fetch the new account's kind:3 so [following] (and the friends list)
            // repopulate: resetContactListState() cleared the prior account's, and a warm
            // swap does not reconstruct the screen ViewModel that requests it on cold boot.
            try {
                requestContactList()
            } catch (_: Exception) {}
            // Re-open the DM inbox for the new account. resetContactListState() cleared the
            // DM state and set dmInboxStarted=false, but the isLoggedIn collector that runs
            // startDmInbox() on cold boot never re-fires on a warm swap (it stays logged in),
            // so without this the new account shows no DMs until the app restarts.
            try {
                startDmInbox()
            } catch (_: Exception) {}
        }
    }

    private fun initializeOutboxModel() {
        val pubKey = sessionManager.getPublicKey() ?: return
        outboxManager.initialize(
            pubKey = pubKey,
            messageHandler = { msg, client -> enqueueToRelayPipeline(msg, client) },
            onDiscoveryComplete = {
                _isDiscoveringRelays.value = false
                // Track bootstrap relays for reconnection so metadata fetches
                // keep working if a bootstrap relay drops mid-session.
                connectedPoolRelays.addAll(outboxManager.bootstrapRelays)
                scope.launch { publishDefaultRelayListIfAbsent(pubKey) }
            },
        )
    }

    /**
     * Give an account with no kind:10002 a default one. Discovery has settled by the time this
     * runs, so an empty list means the account really published none — and an account the outbox
     * model cannot place is one whose events other clients look for in the wrong places, including
     * the kind:10044 and kind:10050 that make DMs reachable.
     *
     * General-purpose relays only: the bootstrap set includes a NIP-65 index that does not accept
     * ordinary events, and an AUTH-gated relay, neither of which belongs in someone's advertised
     * write list.
     */
    private suspend fun publishDefaultRelayListIfAbsent(pubKey: String) {
        if (pubKey in defaultRelayListPublishedFor) return
        if (outboxManager.userRelayList.value.isNotEmpty()) return
        // The account may have been switched away from while discovery ran; publishing then would
        // sign for whoever is active now.
        if (sessionManager.getPublicKey() != pubKey) return
        defaultRelayListPublishedFor += pubKey
        val defaults = defaultOutboxRelays.map { Nip65Relay(url = it, read = true, write = true) }
        publishRelayList(defaults)
    }

    // Accounts already given a default kind:10002 this session, so a re-run of discovery does not
    // republish (and does not overwrite a list the user edited in the meantime).
    private val defaultRelayListPublishedFor = mutableSetOf<String>()

    override suspend fun connect() {
        connect(connectionManager.currentRelayUrl.value)
    }

    /**
     * Manually trigger reconnection to the relay.
     * Use this when auto-reconnection fails or user wants to retry.
     */
    override fun triggerReconnect() {
        scope.launch { reconnect() }
    }

    override suspend fun reconnect(): Boolean {
        // Explicitly notify GroupManager that all in-flight loading is dead.
        // connectionManager.reconnect() calls focusedClient.disconnect() which
        // closes the old socket — but the `onConnectionLost` callback that
        // would normally cascade into GroupManager.handleConnectionLost() may
        // not fire in time (explicit disconnect doesn't always trigger the
        // close-event handler synchronously). Without this reset, controllers
        // left in InitialLoading from REQs sent on the dying socket reject
        // resubscribeAllGroups' new REQ calls (startInitialLoad only accepts
        // Idle/Error), so the new session never gets fresh data — chat sits
        // on skeletons until the controller's own ~10s timeout fires.
        groupManager.handleConnectionLost()
        val connected = connectionManager.reconnect()
        if (connected) {
            val client = connectionManager.getFocusedClient()
            if (client != null) {
                resubscribeAllGroups(client)
                pendingEventManager?.onConnectionRestored()
            }
            dmSendQueue.onConnectionRestored()
            reconnectDroppedNip29PoolRelays()
        }
        return connected
    }

    /**
     * Called when the app returns to the foreground.
     * - If the focused relay is disconnected or errored: triggers a full reconnect.
     * - If already connected: refreshes all live mux subscriptions and pool relays.
     */
    override fun onForeground() {
        scope.launch {
            val state = connectionManager.connectionState.value
            when (state) {
                // Only force reconnect if fully disconnected (no auto-reconnect running).
                // Error state means auto-reconnect exhausted Phase 1 — force a fresh attempt.
                is ConnectionManager.ConnectionState.Disconnected,
                is ConnectionManager.ConnectionState.Error,
                -> reconnect()

                // Auto-reconnect or initial connect in progress — don't interrupt the
                // FOCUSED relay, but the pool sockets still deserve the zombie check:
                // they can be deaf from the same background period, and skipping them
                // here left their recovery to the periodic staleness net.
                is ConnectionManager.ConnectionState.Reconnecting,
                is ConnectionManager.ConnectionState.Connecting,
                -> if (claimForegroundSweep()) {
                    probeIdleSockets()
                    groupManager.refreshLiveSubscriptions()
                    reconnectDroppedNip29PoolRelays()
                }

                // Already connected — verify the sockets actually survived the
                // background period (Doze/radio sleep kills TCP without a close
                // frame; "Connected" can be a zombie), then refresh subs.
                is ConnectionManager.ConnectionState.Connected -> if (claimForegroundSweep()) {
                    probeIdleSockets()
                    groupManager.refreshLiveSubscriptions()
                    reconnectDroppedNip29PoolRelays()
                }
            }
            refreshUserListsOnForeground()
        }
    }

    private var lastForegroundSweepAtMs = 0L

    /**
     * True at most once per [FOREGROUND_SWEEP_MIN_INTERVAL_MS], for the socket sweep that
     * follows a return to the foreground.
     *
     * "Foreground" fires on every visibility change: a browser tab switch, an app switch, a
     * screen unlock. Sweeping on each one probes sockets that have simply been quiet, and a
     * probe that misses its answer tears the socket down - so a user alt-tabbing produces a
     * stream of reconnects, each of which costs the signer a fresh NIP-42 signature. A socket
     * that really died during a 30 s absence is caught by the next sweep or by the 5-minute
     * staleness net.
     */
    private fun claimForegroundSweep(): Boolean {
        val now = org.nostr.nostrord.utils.epochMillis()
        if (now - lastForegroundSweepAtMs < FOREGROUND_SWEEP_MIN_INTERVAL_MS) return false
        lastForegroundSweepAtMs = now
        return true
    }

    // Throttle for the foreground kind:3/kind:10000 re-fetch (seconds).
    private var lastUserListsRefreshAt = 0L
    private val userListsRefreshMinIntervalS = 60L

    /**
     * Re-fetch the account's kind:3 + kind:10000 when the app returns to the foreground,
     * so follows/mutes changed on another device while this one was backgrounded apply
     * without a restart. The live REQ covers changes while the socket is up; this covers
     * the suspended window. createdAt guards keep an older copy from regressing anything,
     * and the unpublished-changes guards protect local taps, so re-requesting is safe.
     */
    private suspend fun refreshUserListsOnForeground() {
        if (sessionManager.getPublicKey() == null) return
        val now = org.nostr.nostrord.utils.epochSeconds()
        if (now - lastUserListsRefreshAt < userListsRefreshMinIntervalS) return
        lastUserListsRefreshAt = now
        contactListMutex.withLock {
            contactListRequested = false
            muteListRequested = false
            requestContactListLocked()
        }
    }

    /**
     * Probe every connected relay socket that has been frame-silent for a while.
     * A zombie is marked dead by RelayProbeGuard once the misses add up, and the
     * resulting onConnectionLost drives reconnect + resubscribe + pending-event
     * flush. Probes run concurrently so one dead socket doesn't delay the others.
     */
    private suspend fun probeIdleSockets() {
        val clients = (
            connectionManager.getAllConnectedClients() +
                listOfNotNull(connectionManager.getFocusedClient())
            ).distinct()
        for (client in clients) {
            val silence = client.inboundSilenceMs() ?: continue
            if (silence < FOREGROUND_PROBE_MIN_SILENCE_MS) continue
            scope.launch { RelayProbeGuard.probe(client) }
        }
    }

    /**
     * Called when the app moves to the background.
     * Persists live cursors to storage so they survive process death.
     */
    override fun onBackground() {
        scope.launch {
            liveCursorStore?.persistAll()
        }
    }

    /**
     * Called when the app process is about to be destroyed (Android onDestroy / process exit).
     * Persists all live cursors and disconnects gracefully.
     */
    override fun onDestroy() {
        scope.launch {
            liveCursorStore?.persistAll()
            groupManager.saveAllMessagesToStorage()
            connectionManager.disconnectFocused()
        }
    }

    /**
     * Notify the repository which group the user is currently viewing.
     * The relay hosting [groupId] is promoted to [RelayReconnectScheduler.Priority.ACTIVE]
     * so reconnect attempts for it use faster (500 ms base) backoff.
     * Pass null when the user leaves the group screen to revert to BACKGROUND priority.
     */
    override fun setActiveGroup(
        relayUrl: String?,
        groupId: String?,
    ) {
        activeRelayUrl = relayUrl?.normalizeRelayUrl()
            ?: groupId?.let { groupManager.getRelayForGroup(it) }
        // Update mux subscriptions to scope chat/reactions to the active group only.
        groupManager.setActiveGroupId(groupId)
        // Suppress unread-counter bumps for the group currently on screen.
        unreadManager.setActiveGroup(activeRelayUrl, groupId)
    }

    /**
     * Gap detection: after EOSE for the [mux_chat_] subscription, check each group on
     * [relayUrl] for evidence of a gap between the cursor and what was actually delivered.
     *
     * A gap is suspected when the newest in-memory message for a group is more than
     * [GAP_THRESHOLD_S] seconds older than what the cursor expected to have received.
     * In that case a targeted history fetch is scheduled to fill the hole.
     */
    private suspend fun detectAndFillGaps(relayUrl: String) {
        val now = epochSeconds()
        val lastAt = lastGapDetectionAt[relayUrl] ?: 0L
        if (now - lastAt < GAP_DETECTION_COOLDOWN_S) return
        lastGapDetectionAt[relayUrl] = now

        val groupIds = groupManager.getGroupIdsForMux(relayUrl)
        if (groupIds.isEmpty()) return

        for (groupId in groupIds) {
            val cursorSince = liveCursorStore?.getSince(relayUrl, groupId) ?: continue
            // getSince already subtracts RECONNECT_OVERLAP_S; add it back to get the actual
            // timestamp of the last event we KNOW we received.
            val lastKnown = cursorSince + LiveCursorStore.RECONNECT_OVERLAP_S
            val latestInMemory = groupManager.getLatestMessageTimestamp(groupId)

            val gapDetected = when {
                latestInMemory == null -> false // no messages loaded yet — normal cold start
                latestInMemory < lastKnown - GAP_THRESHOLD_S -> true // memory is stale
                else -> false
            }

            if (gapDetected) {
                scope.launch {
                    try {
                        groupManager.requestGroupMessages(groupId)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * Group ids we know on [relayUrl] WITHOUT listing the relay's full directory: our own joined
     * groups plus the groups in the kind:10009 lists of people we follow (and the discovery
     * curator). Restricted groups are excluded — the relay CLOSEs the whole batch if any single
     * id is denied.
     */
    private fun knownGroupIdsForRelay(relayUrl: String): List<String> {
        val normalized = relayUrl.normalizeRelayUrl()
        val restricted = groupManager.restrictedGroups.value
        val ids = LinkedHashSet<String>()
        groupManager.joinedGroupsByRelay.value[normalized].orEmpty().forEach { ids.add(it) }
        val lists = _userGroupLists.value
        (_following.value + DISCOVERY_CURATOR_PUBKEY).forEach { pk ->
            lists[pk].orEmpty().forEach { ref ->
                if (ref.relayUrl.normalizeRelayUrl() == normalized) ids.add(ref.groupId)
            }
        }
        // Follow the hierarchy: a known root's kind:39000 lists its channels in `child`
        // tags, but joining the root doesn't join them — without this expansion their
        // metadata is never fetched and the sidebar channel list stays empty. Fetched
        // children re-enter here via the groupsByRelay collector, so deeper trees
        // converge one level per round.
        val metaById = groupManager.groupsByRelay.value[normalized].orEmpty().associateBy { it.id }
        ids.toList().forEach { id -> metaById[id]?.children?.forEach { ids.add(it) } }
        return ids.filter { groupKey(normalized, it) !in restricted }
    }

    // Group ids already requested (targeted #d) per normalized relay this session, so the
    // friends/curator re-fetch only fires when the known set actually grows.
    private val sentKnownGroupFetch = mutableMapOf<String, Set<String>>()

    /**
     * Fetch kind:39000 for ONLY the groups we know on [relayUrl] (see [knownGroupIdsForRelay]),
     * via a targeted #d REQ — the relay's full group directory is never downloaded. When nothing
     * is known yet, marks the relay loaded and wires the mux directly (no EOSE would arrive).
     */
    private suspend fun sendKnownGroupsFetch(client: NostrGroupClient, relayUrl: String) {
        groupManager.cancelPendingFullFetch(relayUrl)
        val ids = knownGroupIdsForRelay(relayUrl)
        if (ids.isEmpty()) {
            groupManager.markRelayLoaded(relayUrl)
            groupManager.refreshMuxSubscriptionsForRelay(relayUrl)
            return
        }
        sentKnownGroupFetch[relayUrl.normalizeRelayUrl()] = ids.toSet()
        client.requestGroupsForIds(ids)
    }

    /**
     * Send the group-list REQ for a relay. Always targeted to the groups we know (joined +
     * friends' / curator lists); we deliberately never pull the relay's full group directory.
     */
    private suspend fun requestGroupsForRelay(client: NostrGroupClient, relayUrl: String) {
        sendKnownGroupsFetch(client, relayUrl)
    }

    /**
     * Schedule the timing-robust kind:39000 safety net a few seconds after a relay is ready.
     * The batched mux/meta REQ that normally carries group metadata is all-or-nothing and the
     * JVM WebSocket engine loses the AUTH-vs-REQ race far more than the browser engine, so on
     * desktop cold boot many groups stay on a truncated id + "No description" until a restart.
     * The delay lets the batch/mux land first; [requestMissingGroupMetadata] then per-group
     * fetches only what is still missing, so this is a no-op on the happy path (web, and desktop
     * when the race is won).
     */
    private fun scheduleMissingMetadataSweep(relayUrl: String) {
        scope.launch {
            delay(3_000)
            try {
                groupManager.requestMissingGroupMetadata(relayUrl)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
    }

    private suspend fun connect(relayUrl: String) {
        if (relayUrl.isBlank()) return
        _relayMetadataManager.fetch(relayUrl)

        // Mark loading before the connection attempt so the skeleton shows during
        // the initial Connecting state on cold start. The state-flow observer in
        // initialize() clears this if the connection fails (Error/Reconnecting).
        groupManager.markRelayLoading(relayUrl)

        val connected = connectionManager.connectFocused(relayUrl) { msg, client ->
            enqueueToRelayPipeline(msg, client)
        }

        if (connected) {
            val client = connectionManager.getFocusedClient()
            if (client != null) {
                // Public group list: only a short AUTH grace, so public groups load fast.
                // Most NIP-29 relays serve public kind:39000 without AUTH; firing the list
                // here (pre-AUTH on a slow bunker) loads them immediately, and the few groups
                // gated behind AUTH recover via resubscribeAfterAuth + the private fetch below.
                // Waiting the full bunker sign budget here would stall PUBLIC groups too.
                val authHandled = client.awaitAuthOrTimeout()
                groupManager.restoreGroupsForRelay(relayUrl)
                if (!groupManager.hasCachedGroupsForRelay(relayUrl)) {
                    // If auth was handled, resubscribeAfterAuth already called
                    // requestGroups(); only request if auth was not needed.
                    if (!authHandled) {
                        lastRequestGroupsAt[relayUrl] = epochSeconds()
                        groupManager.markRelayLoading(relayUrl)
                        requestGroupsForRelay(client, relayUrl)
                    }
                } else if (
                    SecureStorage.isGroupFetchLazy(relayUrl) &&
                    relayUrl.normalizeRelayUrl() !in groupManager.fullGroupListFetchedRelays.value &&
                    SecureStorage.getBooleanPref("sidebar_other_expanded_$relayUrl", default = true)
                ) {
                    // Cache is fresh (partial, joined-only) but OTHER GROUPS is open and the full
                    // list was never fetched THIS SESSION. Trigger a full fetch now so the sidebar
                    // populates automatically on connect — don't make the user click the homescreen
                    // OTHER GROUPS tab. We check the in-memory set, not hasFullGroupListBeenFetched:
                    // a stale persisted timestamp (or a previous session's full fetch) would
                    // otherwise satisfy that guard while the cache holds only joined groups,
                    // leaving OTHER GROUPS empty until a manual fetch.
                    groupManager.markRelayLoading(relayUrl)
                    sendKnownGroupsFetch(client, relayUrl)
                } else {
                    // Cache hit — no EOSE will arrive, so clear the early loading mark.
                    groupManager.markRelayLoaded(relayUrl)
                    // No group-list REQ was sent, so the EOSE-driven mux setup
                    // (handleEoseSuspend -> refreshMuxSubscriptionsForRelay) never
                    // fires. Set up the live chat + metadata mux now, otherwise the
                    // focused relay's groups show "No messages yet" and "Members 0"
                    // until a re-AUTH or the 5-min periodic refresh. This path is hit
                    // whenever login hydrates joined groups from storage (#88), which
                    // makes the relay a cache hit and skips the group-list fetch.
                    groupManager.refreshMuxSubscriptionsForRelay(relayUrl)
                }
                drainFullFetchRequest(client, relayUrl)
                // After the public group-list EOSE, request metadata for private groups that
                // are in the joined list but not in the cache (omitted from the public
                // listing), and for the active group if navigated via URL. One launch only —
                // firing these from several paths flooded the relay with duplicate subs.
                scope.launch {
                    groupManager.awaitGroupListEose(relayUrl)
                    groupManager.requestPrivateGroupData(relayUrl)
                    groupManager.requestActiveGroupMetadataIfMissing(relayUrl)
                }
                scheduleMissingMetadataSweep(relayUrl)
            }
        }
    }

    private suspend fun autoConnectFirstRelay(relays: List<String>) {
        if (relays.isEmpty()) return
        if (connectionManager.getFocusedClient() != null) return
        if (connectionManager.currentRelayUrl.value.isNotBlank()) return

        val focusedRelay = relays.first()
        _isDiscoveringRelays.value = false
        val pubkey = sessionManager.getPublicKey()
        if (pubkey != null) {
            SecureStorage.saveCurrentRelayUrlFor(pubkey, focusedRelay)
        }
        connectionManager.loadSavedRelay()
        liveCursorStore?.loadAll(relays)
        if (pubkey != null) {
            groupManager.loadJoinedGroupsFromStorage(pubkey, focusedRelay)
            groupManager.loadAllJoinedGroupsFromStorage(pubkey, relays)
            groupManager.restoreJoinedGroupMetadataFromStorage(pubkey, relays)
            groupManager.restoreGroupMembershipFromStorage(pubkey)
            outboxManager.restoreGroupOrder(pubkey)
            groupManager.migrateMessageBlobsToCache(pubkey)
            groupManager.loadRestrictedGroupsFromStorage(pubkey, relays)
        }
        connect(focusedRelay)
        // This path is the ONLY bootstrap for a login with no local cache: the relay list
        // arrives with the kind:10009, so the login branch skipped its own
        // ensureJoinedRelaysConnected (focusedRelay was still blank) and initialize()'s two
        // call sites never ran either. Without this the whole session holds a single socket,
        // so every joined group on another relay has no kind:39000 and no chat until the app
        // is restarted. joinedGroupsByRelay is already populated here: the parser fires
        // onRelayGroupsUpdated before onRelaysRestored.
        scope.launch { ensureJoinedRelaysConnected(focusedRelay) }
    }

    override suspend fun switchRelay(newRelayUrl: String) {
        _targetSwitchRelayUrl.value = newRelayUrl

        // Skip if already on this relay — avoids unnecessary disconnect/reconnect/AUTH cycle.
        // Also skip if a connect to the same relay is in flight: deep-link cold-start
        // fires repo.switchRelay() from AppShell's useEffectOnce after initialize()
        // has already kicked off connect(focusedRelay) but before focusedClient is set.
        // Without this guard, switchRelay nulls the in-flight focusedClient and opens
        // a duplicate socket on the same URL — observed as a doomed second WebSocket
        // attempt to groups.0xchat.com (handshake fails, ~1.7 s lost to backoff).
        val sameRelay = newRelayUrl == connectionManager.currentRelayUrl.value
        if (sameRelay) {
            val state = connectionManager.connectionState.value
            val healthy = connectionManager.getFocusedClient()?.isConnected() == true
            val connectInFlight = state is ConnectionManager.ConnectionState.Connecting ||
                state is ConnectionManager.ConnectionState.Reconnecting
            if (healthy || connectInFlight) return
        }

        val normalized = newRelayUrl.normalizeRelayUrl()

        // If this relay previously returned "restricted", show the cached
        // error immediately instead of reconnecting and skeleton-loading.
        val restriction = _restrictedRelays.value[normalized]
        if (restriction != null) {
            connectionManager.setFocusedRelay(newRelayUrl) { msg, client ->
                enqueueToRelayPipeline(msg, client)
            }
            connectionManager.setError(newRelayUrl, restriction)
            return
        }

        _relayMetadataManager.fetch(newRelayUrl)

        // Mark the new relay as loading BEFORE clearing groups so the UI
        // shows skeleton loaders immediately instead of a flash of empty state.
        groupManager.markRelayLoading(newRelayUrl)

        // Clears messages/state but NOT the group metadata cache (_groupsByRelay).
        groupManager.clearForRelaySwitch()

        if (_targetSwitchRelayUrl.value != newRelayUrl) return

        connectionManager.setFocusedRelay(newRelayUrl) { msg, client ->
            enqueueToRelayPipeline(msg, client)
        }

        if (_targetSwitchRelayUrl.value != newRelayUrl) return

        val pubKey = sessionManager.getPublicKey() ?: ""

        groupManager.restoreGroupsForRelay(newRelayUrl)

        val outboxCached = outboxManager.getJoinedGroupsForRelay(newRelayUrl)
        val inMemoryCached = groupManager.joinedGroupsByRelay.value[normalized] ?: emptySet()
        val cachedJoined = outboxCached + inMemoryCached
        if (cachedJoined.isNotEmpty()) {
            groupManager.setJoinedGroups(cachedJoined)
        } else {
            // Off-Main: this reads the persisted joined-group set (EncryptedSharedPreferences
            // decrypt + JSON parse) — blocking it on the caller's thread stalled the rail switch.
            withContext(Dispatchers.Default) {
                groupManager.loadJoinedGroupsFromStorage(pubKey, newRelayUrl)
            }
        }

        val client = connectionManager.getFocusedClient()
        if (client != null) {
            // Short AUTH grace so the public group list loads fast.
            val authHandled = client.awaitAuthOrTimeout()
            if (_targetSwitchRelayUrl.value != newRelayUrl) return
            // Skip re-fetch if cached; re-fetching races against restored state.
            if (!groupManager.hasCachedGroupsForRelay(newRelayUrl)) {
                // Always request groups here. Even if AUTH was already handled
                // (authHandled == true), resubscribeAfterAuth only calls
                // requestGroups() for the focused client — if this client was
                // promoted from the pool (e.g. connected by a link preview),
                // AUTH happened while it was a pool client and requestGroups()
                // was never sent.
                requestGroupsForRelay(client, newRelayUrl)
            } else if (
                SecureStorage.isGroupFetchLazy(newRelayUrl) &&
                newRelayUrl.normalizeRelayUrl() !in groupManager.fullGroupListFetchedRelays.value &&
                SecureStorage.getBooleanPref("sidebar_other_expanded_$newRelayUrl", default = true)
            ) {
                // Cache holds only the joined-group subset (from a previous lazy
                // fetch) but OTHER GROUPS is open and this session hasn't
                // received a full EOSE — fire the full fetch so the sidebar
                // populates instead of flashing "no other groups". Mirrors the
                // equivalent branch in connect(). We check the in-memory session
                // set rather than hasFullGroupListBeenFetched so a stale persisted
                // timestamp can't suppress the auto-fetch on switching to a relay.
                groupManager.markRelayLoading(newRelayUrl)
                sendKnownGroupsFetch(client, newRelayUrl)
            } else {
                // Cache was restored — no EOSE will arrive, so unmark loading now.
                groupManager.markRelayLoaded(newRelayUrl)
                // As in connect(): a cache hit sends no group-list REQ, so the
                // EOSE-driven mux setup won't fire. Refresh the chat + metadata mux
                // for the new focused now so messages/members load instead of
                // showing "No messages yet" / "Members 0".
                groupManager.refreshMuxSubscriptionsForRelay(newRelayUrl)
            }
            drainFullFetchRequest(client, newRelayUrl)
            // After the group-list EOSE, request metadata for private groups not in the
            // cache, and for the active group if navigated via URL. One launch only.
            scope.launch {
                groupManager.awaitGroupListEose(newRelayUrl)
                groupManager.requestPrivateGroupData(newRelayUrl)
                groupManager.requestActiveGroupMetadataIfMissing(newRelayUrl)
                scheduleMissingMetadataSweep(newRelayUrl)
                // The active group's controller was reset to Idle by clearForRelaySwitch and the
                // bulk re-subscribe skips it; reload it here so it can paginate (race fix).
                groupManager.requestActiveGroupMessagesIfNeeded(newRelayUrl)
            }
        } else if (groupManager.hasCachedGroupsForRelay(newRelayUrl)) {
            // No connection yet but cache was restored — unmark loading.
            groupManager.markRelayLoaded(newRelayUrl)
        }

        outboxManager.resetKind10009State()

        if (!connectionManager.hasPoolConnections()) {
            initializeOutboxModel()
        }

        // Fetch only on first load; in-memory cache is source of truth after that.
        if (!outboxManager.hasJoinedGroupsData()) {
            scope.launch {
                outboxManager.loadJoinedGroupsFromNostr(pubKey) { msg, c -> handleRelayMessage(msg, c) }
            }
        }

        // Ensure every other joined relay also has a live mux chat sub.
        scope.launch { ensureJoinedRelaysConnected(newRelayUrl) }
    }

    override suspend fun removeRelay(url: String) {
        val normalized = url.normalizeRelayUrl()
        val existing = outboxManager.kind10009Relays.value.toList()
        val remaining = existing.filter { it != normalized }
        val pubKey = sessionManager.getPublicKey()

        // Apply removal locally first so the relay vanishes even when the relay is offline.
        // The kind:10009 publish is attempted afterwards; failure is non-fatal — the local
        // removal and the persisted timestamp guard in OutboxManager prevent resurrection.
        if (pubKey != null) {
            SecureStorage.saveRelayListFor(pubKey, remaining)
            SecureStorage.clearJoinedGroupsForRelay(pubKey, normalized)
        }
        groupManager.removeRelayEntry(normalized)
        outboxManager.removeRelayFromCache(normalized)

        val fallback = remaining.firstOrNull()
        if (fallback != null && fallback != connectionManager.currentRelayUrl.value.normalizeRelayUrl()) {
            switchRelay(fallback)
        } else if (fallback == null) {
            if (pubKey != null) SecureStorage.clearCurrentRelayUrlFor(pubKey)
            connectionManager.clearCurrentRelay()
        }
        connectionManager.disconnectRelay(normalized)
        connectedPoolRelays.remove(normalized)

        // Publish updated kind:10009 to remaining relays. Signer denial is the only error
        // that would matter here (user explicitly cancelled), but at this point the relay is
        // already gone locally, so we just fire-and-forget.
        if (pubKey != null) {
            publishJoinedGroupsListWith(pubKey, nip29Relays = remaining)
        }
    }

    override suspend fun disconnect() {
        connectionManager.disconnectFocused()
        groupManager.clear()
    }

    override fun setGroupFetchLazy(relayUrl: String, lazy: Boolean) {
        SecureStorage.saveGroupFetchLazy(relayUrl, lazy)
    }

    override fun isGroupFetchLazy(relayUrl: String): Boolean = SecureStorage.isGroupFetchLazy(relayUrl)

    override val fullGroupListFetchedRelays: StateFlow<Set<String>> =
        groupManager.fullGroupListFetchedRelays

    override val completeGroupLoadRelays: StateFlow<Set<String>> =
        groupManager.completeGroupLoadRelays

    override suspend fun requestFullGroupListForRelay(relayUrl: String) {
        // Only guard against in-flight duplicates — hasFullGroupListBeenFetched is intentionally
        // NOT checked here: a stale persisted timestamp can make it return true when the current
        // session has no data, silently blocking user-triggered fetches.
        if (groupManager.hasPendingFullFetch(relayUrl)) return

        val normalizedTarget = relayUrl.normalizeRelayUrl()

        // We must NOT fall back to the current focused: the sidebar triggers this
        // right after selectedRelayUrl changes, BEFORE switchRelay() has made the
        // new relay focused. Falling back would send requestGroups() on the old
        // focused's WebSocket, polluting that relay's _groupsByRelay cache with
        // unrelated kind:39000 events — surfacing as OTHER GROUPS auto-populating
        // on a collapsed relay the next time the user switches back to it.
        //
        // If the target client isn't ready yet (typically: still connecting / awaiting
        // AUTH during a relay switch), enqueue the request and let
        // [drainFullFetchRequest] fire it from the connect/switchRelay/resubscribeAfterAuth
        // post-AUTH path — avoiding the previous 30 s polling wait that was
        // particularly painful on Android.
        val client = connectionManager.getClientForRelay(normalizedTarget)
        val ready = client != null &&
            client.isConnected() &&
            client.getRelayUrl().normalizeRelayUrl() == normalizedTarget
        if (!ready) {
            pendingFullFetchMutex.withLock { pendingFullFetchRequests.add(normalizedTarget) }
            // Mark loading right away so the sidebar shows the spinner from the
            // moment the user clicks — instead of flashing "no other groups"
            // until the post-AUTH drain fires the REQ.
            groupManager.markRelayLoading(relayUrl)
            return
        }

        groupManager.markRelayLoading(relayUrl)
        sendKnownGroupsFetch(client!!, relayUrl)
    }

    /**
     * Fire any pending full-fetch request the UI enqueued for [relayUrl] while
     * [client] wasn't ready. Called from connect()/switchRelay()/resubscribeAfterAuth
     * after AUTH completes. No-op if no pending request, if the connect-path
     * already fired the REQ (hasPendingFullFetch), or if [client] mismatches.
     */
    private suspend fun drainFullFetchRequest(client: NostrGroupClient, relayUrl: String) {
        val normalized = relayUrl.normalizeRelayUrl()
        val requested = pendingFullFetchMutex.withLock {
            pendingFullFetchRequests.remove(normalized)
        }
        if (!requested) return
        if (groupManager.hasPendingFullFetch(relayUrl)) return
        if (!client.isConnected()) return
        if (client.getRelayUrl().normalizeRelayUrl() != normalized) return
        groupManager.markRelayLoading(relayUrl)
        sendKnownGroupsFetch(client, relayUrl)
    }

    override suspend fun addRelay(url: String) {
        val normalized = url.normalizeRelayUrl()
        val alreadyInKind10009 = normalized in outboxManager.kind10009Relays.value
        if (!alreadyInKind10009) {
            val existing = outboxManager.kind10009Relays.value.toList()
            val newList = (existing + normalized).distinct()
            val pubKey = sessionManager.getPublicKey()
            if (!pubKey.isNullOrEmpty()) {
                val result = publishJoinedGroupsListWith(pubKey, nip29Relays = newList)
                if (result is Result.Success) {
                    SecureStorage.saveRelayListFor(pubKey, newList)
                } else {
                    return // signer denied or publish failed — don't save
                }
            }
        }
        // Clear deep link prompt if this was the pending relay
        if (_pendingDeepLinkRelay.value == normalized) {
            _pendingDeepLinkRelay.value = null
        }
    }

    override fun dismissDeepLinkRelay() {
        _pendingDeepLinkRelay.value = null
    }

    // Auth delegation
    override fun getPublicKey(): String? = sessionManager.getPublicKey()
    override fun getPrivateKey(): String? = sessionManager.getPrivateKey()
    override fun isUsingBunker(): Boolean = sessionManager.isUsingBunker()
    override fun isBunkerReady(): Boolean = sessionManager.isBunkerReady()
    override suspend fun ensureBunkerConnected(): Boolean = sessionManager.ensureBunkerConnected()

    // Group operations
    override suspend fun joinGroup(groupId: String, inviteCode: String?, listPrivately: Boolean, relayUrl: String?): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        // The caller's relay decides: with the same id on two relays, the focused relay is a
        // coin flip and the 9021 lands on the group the user is not looking at.
        val joinRelay = relayUrl?.normalizeRelayUrl()
            ?: groupManager.getRelayForGroup(groupId)
            ?: connectionManager.currentRelayUrl.value
        // Before the join publishes the list, not after: a group marked private afterwards would
        // already have gone out in the clear once, and relays keep that version.
        if (listPrivately) outboxManager.setGroupPrivate(joinRelay, groupId, true)
        val result = groupManager.joinGroup(
            groupId = groupId,
            pubKey = pubKey,
            currentRelayUrl = joinRelay,
            signEvent = { sessionManager.signEvent(it) },
            publishJoinedGroups = { publishJoinedGroupsList() },
            inviteCode = inviteCode,
        )
        if (result is Result.Success) {
            // Joining a group may have introduced a new joined relay — ensure it's
            // connected with a chat sub so notifications fire even when the user is
            // browsing a different focused.
            scope.launch { ensureJoinedRelaysConnected(joinRelay) }
        } else if (listPrivately) {
            outboxManager.setGroupPrivate(joinRelay, groupId, false)
        }
        return result
    }

    override fun markOptimisticJoin(relayUrl: String, groupId: String): Boolean = groupManager.markOptimisticJoin(relayUrl, groupId)

    override fun revertOptimisticJoin(relayUrl: String, groupId: String) = groupManager.revertOptimisticJoin(relayUrl, groupId)

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
    ): Result<String> = createGroupInternal(
        name, about, relayUrl, isPrivate, isClosed, isRestricted, isHidden, picture, customGroupId,
        parentGroupId = null,
        listPrivately = listPrivately,
    )

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
    ): Result<String> = createGroupInternal(
        name, about, relayUrl, isPrivate, isClosed, isRestricted, isHidden, picture, customGroupId,
        // The parent link rides the creation kind:9002: a follow-up parent-only 9002 is
        // rejected by relays as a moderation action with no metadata tags.
        parentGroupId = parentGroupId,
        listPrivately = listPrivately,
    )

    private suspend fun createGroupInternal(
        name: String,
        about: String?,
        relayUrl: String,
        isPrivate: Boolean,
        isClosed: Boolean,
        isRestricted: Boolean,
        isHidden: Boolean,
        picture: String?,
        customGroupId: String?,
        parentGroupId: String?,
        listPrivately: Boolean,
    ): Result<String> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        // Also re-enter switchRelay when the URL already matches but the socket is dead:
        // a failed first connect leaves currentRelayUrl set with an empty pool, and skipping
        // here would fail every retry with Disconnected without ever reconnecting.
        if (relayUrl != connectionManager.currentRelayUrl.value ||
            connectionManager.getFocusedClient()?.isConnected() != true
        ) {
            switchRelay(relayUrl)
        }
        val result = groupManager.createGroup(
            name = name,
            about = about,
            picture = picture,
            isPrivate = isPrivate,
            isClosed = isClosed,
            isRestricted = isRestricted,
            isHidden = isHidden,
            customGroupId = customGroupId,
            parentGroupId = parentGroupId,
            pubKey = pubKey,
            currentRelayUrl = connectionManager.currentRelayUrl.value,
            signEvent = { sessionManager.signEvent(it) },
            publishJoinedGroups = { publishJoinedGroupsList() },
            // The relay confirms (and may replace) the group id inside createGroup, so the
            // private flag can only be set there, right before the list publish — a group
            // flagged private afterwards would already have gone out in the clear once.
            markListedPrivately = if (listPrivately) {
                { relay, groupId -> outboxManager.setGroupPrivate(relay, groupId, true) }
            } else {
                null
            },
        )
        if (result is Result.Success) {
            scope.launch { ensureJoinedRelaysConnected(connectionManager.currentRelayUrl.value) }
        }
        return result
    }

    override suspend fun leaveGroup(groupId: String, reason: String?): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.leaveGroup(
            groupId = groupId,
            pubKey = pubKey,
            currentRelayUrl = connectionManager.currentRelayUrl.value,
            reason = reason,
            signEvent = { sessionManager.signEvent(it) },
            publishJoinedGroups = { publishJoinedGroupsList() },
        )
    }

    override suspend fun setGroupListedPrivately(
        groupId: String,
        relayUrl: String,
        listedPrivately: Boolean,
    ): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        outboxManager.setGroupPrivate(relayUrl, groupId, listedPrivately)
        val result = publishJoinedGroupsListWith(pubKey)
        // A publish the signer refused (it will not encrypt the private section) leaves the
        // published list as it was, so the local side must go back too.
        if (result is Result.Error) outboxManager.setGroupPrivate(relayUrl, groupId, !listedPrivately)
        return result
    }

    override suspend fun forgetGroup(groupId: String, relayUrl: String): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
        val changed = groupManager.forgetJoinedPin(groupId, relayUrl, pubKey)
        if (changed) publishJoinedGroupsList()
        return Result.Success(Unit)
    }

    override val orphanedJoinedByRelay: StateFlow<Map<String, Set<String>>>
        get() = groupManager.orphanedJoinedByRelay

    override fun isGroupJoined(groupId: String): Boolean = groupManager.isGroupJoined(groupId)

    override suspend fun requestGroupMessages(groupId: String, channel: String?) {
        if (connectionManager.getFocusedClient() == null) {
            connect()
        }
        groupManager.requestGroupMessages(groupId, channel)
        // Also request group metadata (kind 39000), members (kind 39002) and admins (kind 39001)
        val relayUrl = groupManager.getRelayForGroup(groupId)
        val metaClient = if (relayUrl != null) connectionManager.getClientForRelay(relayUrl) else null
        (metaClient ?: connectionManager.getFocusedClient())?.requestGroupMetadata(groupId)
        groupManager.requestGroupMembers(groupId)
        groupManager.requestGroupAdmins(groupId)
        groupManager.requestGroupRoles(groupId)
    }

    /**
     * Request group members (kind 39002) for a specific group.
     */
    override suspend fun requestGroupMembers(groupId: String) {
        if (connectionManager.getFocusedClient() == null) {
            connect()
        }
        groupManager.requestGroupMembers(groupId)
    }

    /**
     * Request group admins (kind 39001) for a specific group.
     */
    override suspend fun requestGroupAdmins(groupId: String) {
        if (connectionManager.getFocusedClient() == null) {
            connect()
        }
        groupManager.requestGroupAdmins(groupId)
    }

    /**
     * Request pending join requests (kind 9021 + 9022) for a group. Admin-only
     * use case — the standard chat REQ misses old 9021s in active groups.
     */
    override suspend fun requestPendingJoinRequests(groupId: String) {
        if (connectionManager.getFocusedClient() == null) {
            connect()
        }
        groupManager.requestPendingJoinRequests(groupId)
    }

    /**
     * Fire-and-forget NIP-11 fetch for [relayUrl]. Powers the suggested-relay
     * cards in the AddRelay modal; deduplicated inside [RelayMetadataManager].
     */
    override fun fetchRelayMetadata(relayUrl: String) {
        _relayMetadataManager.fetch(relayUrl)
    }

    /**
     * Request group roles (kind 39003) for a specific group.
     */
    suspend fun requestGroupRoles(groupId: String) {
        if (connectionManager.getFocusedClient() == null) {
            connect()
        }
        groupManager.requestGroupRoles(groupId)
    }

    override val liveKitParticipants: StateFlow<Map<String, List<String>>> = groupManager.liveKitParticipants

    /** NIP-29 AV spaces. Signs the NIP-98 token request with the active account's signer. */
    private val liveKitTokenClient = LiveKitTokenClient { url, method -> buildNip98AuthHeader(url, method) }

    override suspend fun requestLiveKitParticipants(groupId: String) {
        if (connectionManager.getFocusedClient() == null) {
            connect()
        }
        groupManager.requestLiveKitParticipants(groupId)
    }

    override suspend fun relaySupportsAv(groupId: String): Boolean {
        val relayUrl = groupManager.getRelayForGroup(groupId) ?: return false
        return liveKitTokenClient.relaySupportsAv(relayUrl)
    }

    override suspend fun fetchLiveKitCredentials(groupId: String): Result<LiveKitCredentials> {
        val relayUrl = groupManager.getRelayForGroup(groupId)
            ?: return Result.Error(AppError.Network.Disconnected("unknown relay for group $groupId"))
        return liveKitTokenClient.fetchCredentials(relayUrl, groupId)
    }

    override val childrenByParent: StateFlow<Map<String, Set<String>>> = groupManager.childrenByParent

    override suspend fun refreshGroupMetadata(groupId: String) {
        val relayUrl = groupManager.getRelayForGroup(groupId)
        val client = (if (relayUrl != null) connectionManager.getClientForRelay(relayUrl) else null)
            ?: connectionManager.getFocusedClient()
            ?: return
        client.requestGroupMetadata(groupId)
        client.requestGroupAdmins(groupId)
    }

    override suspend fun fetchGroupPreview(groupId: String, relayUrl: String) {
        // Skip only when THIS relay's copy is already named. A same-id group on another relay is a
        // different group, and treating it as a hit left the requested one permanently nameless.
        val host = relayUrl.normalizeRelayUrl()
        val existing = groupManager.groupsByRelay.value
            .entries.firstOrNull { it.key.normalizeRelayUrl() == host }?.value
            ?.find { it.id == groupId }
        if (existing?.name != null) return

        try {
            val client = connectionManager.getOrConnectRelay(relayUrl) { msg, c ->
                enqueueToRelayPipeline(msg, c)
            } ?: return
            connectedPoolRelays.add(relayUrl)
            client.requestGroupMetadata(groupId)
            // Same AUTH-gated re-send as fetchGroupPreviews: a private group's kind:39000 is
            // withheld pre-AUTH (0xchat answers with an empty EOSE, not a CLOSED), so for
            // them the first REQ yields nothing and only the authenticated retry lands.
            if (client.awaitAuthSigned(signerAuthBudgetMs())) client.requestGroupMetadata(groupId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {}
    }

    override suspend fun fetchGroupPreviews(relayToGroups: Map<String, Set<String>>) {
        // Skip groups whose metadata we already have a name for.
        val known = groupManager.groupsByRelay.value.values.flatten()
            .filter { it.name != null }
            .map { it.id }
            .toSet()
        // Fan out per relay concurrently. A friend's groups often span many relays, and
        // connecting to them sequentially (each up to the connect timeout) made discovery
        // metadata trickle in slowly, worst on the onboarding "Find your group" step.
        // getOrConnectRelay is a pooled singleflight per URL, so concurrent callers never
        // open duplicate sockets; launching on [scope] returns from here immediately and
        // lets every relay's kind:39000 arrive in parallel via the pipeline.
        relayToGroups.forEach { (relayUrl, groupIds) ->
            if (relayUrl.isBlank()) return@forEach
            val missing = groupIds.filter { it !in known }
            if (missing.isEmpty()) return@forEach
            scope.launch {
                try {
                    // Claim the cooldown before connecting/sending, not after: the collector
                    // above can re-emit this same relay+group within milliseconds while the
                    // metadata is still in flight, and getOrConnectRelay below suspends, so
                    // checking the cooldown after it would let concurrent re-emissions all
                    // pass together (the same race GroupManager.requestPrivateGroupData had).
                    val now = epochSeconds()
                    val toFetch = groupPreviewFetchMutex.withLock {
                        missing
                            .filter { now - (groupPreviewFetchAt["$relayUrl|$it"] ?: 0L) > 10L }
                            .also { claimed -> claimed.forEach { groupPreviewFetchAt["$relayUrl|$it"] = now } }
                    }
                    if (toFetch.isEmpty()) return@launch
                    val client = connectionManager.getOrConnectRelay(relayUrl) { msg, c ->
                        enqueueToRelayPipeline(msg, c)
                    } ?: return@launch
                    connectedPoolRelays.add(relayUrl)
                    // Send immediately: relays that don't gate reads behind NIP-42 serve
                    // this with no added latency (the common case).
                    client.requestGroupsMetadata(toFetch)
                    // AUTH-gated relays (e.g. chat.wisp.talk, nos.lol) reject the pre-AUTH
                    // REQ above with CLOSED "auth-required", and resubscribeAfterAuth replays
                    // only group/mux subs, never one-shot discovery REQs. Re-send once AUTH is
                    // signed so the discovery card fills in its kind:39000 name/about instead of
                    // staying a bare-id placeholder until the group is opened. awaitAuthOrTimeout
                    // returns true only when a challenge was actually answered, so non-auth
                    // relays don't pay a redundant re-send.
                    if (client.awaitAuthSigned(signerAuthBudgetMs())) client.requestGroupsMetadata(toFetch)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
            }
        }
    }

    override suspend fun awaitRelayAuthSettled(relayUrl: String) {
        val client = connectionManager.getClientForRelay(relayUrl) ?: return
        client.awaitAuthSigned(signerAuthBudgetMs())
    }

    override suspend fun fetchGroupsMembers(relayToGroups: Map<String, Set<String>>) {
        relayToGroups.forEach { (relayUrl, groupIds) ->
            if (relayUrl.isBlank() || groupIds.isEmpty()) return@forEach
            scope.launch {
                try {
                    val client = connectionManager.getOrConnectRelay(relayUrl) { msg, c ->
                        enqueueToRelayPipeline(msg, c)
                    } ?: return@launch
                    connectedPoolRelays.add(relayUrl)
                    val ids = groupIds.toList()
                    client.requestGroupsMembers(ids)
                    // Same AUTH-gated re-send as fetchGroupPreviews: a private group's member
                    // count would otherwise stay blank until the group is opened.
                    if (client.awaitAuthSigned(signerAuthBudgetMs())) client.requestGroupsMembers(ids)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
            }
        }
    }

    override suspend fun editGroup(
        groupId: String,
        name: String,
        about: String?,
        isPrivate: Boolean,
        isClosed: Boolean,
        isRestricted: Boolean,
        isHidden: Boolean,
        picture: String?,
        parentOp: GroupManager.ParentOp?,
        hasLiveKit: Boolean?,
    ): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        val result = groupManager.editGroup(
            groupId = groupId,
            name = name,
            about = about,
            picture = picture,
            isPrivate = isPrivate,
            isClosed = isClosed,
            isRestricted = isRestricted,
            isHidden = isHidden,
            pubKey = pubKey,
            currentRelayUrl = connectionManager.currentRelayUrl.value,
            signEvent = { sessionManager.signEvent(it) },
            parentOp = parentOp,
            hasLiveKit = hasLiveKit,
        )
        if (result is Result.Success) refreshGroupMetadata(groupId)
        return result
    }

    override suspend fun deleteGroup(groupId: String): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.deleteGroup(
            groupId = groupId,
            pubKey = pubKey,
            currentRelayUrl = connectionManager.currentRelayUrl.value,
            signEvent = { sessionManager.signEvent(it) },
            publishJoinedGroups = { publishJoinedGroupsList() },
        )
    }

    override suspend fun reorderChildren(
        groupId: String,
        orderedIds: List<String>,
    ): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.reorderChildren(
            groupId = groupId,
            orderedIds = orderedIds,
            pubKey = pubKey,
            currentRelayUrl = connectionManager.currentRelayUrl.value,
            signEvent = { sessionManager.signEvent(it) },
        )
    }

    override suspend fun loadMoreMessages(groupId: String, channel: String?): Boolean = groupManager.loadMoreMessages(groupId, channel)

    override suspend fun retryStalledLoad(groupId: String, channel: String?): Boolean = groupManager.retryStalledLoad(groupId, channel)

    override suspend fun fetchGroupMessageById(groupId: String, messageId: String) {
        groupManager.fetchGroupMessageById(groupId, messageId)
    }

    /**
     * [mentions] plus the `@name`s typed out in [content] without picking a suggestion, matched
     * against the group's members so a hand-typed mention still resolves to `nostr:npub` + a `p`
     * tag. Members are the same pool the composer popup offers.
     */
    private fun withTypedMentions(groupId: String, content: String, mentions: Map<String, String>): Map<String, String> {
        val metadata = metadataManager.userMetadata.value
        val candidates = groupManager.getMembersForGroup(groupId).map { pubkey ->
            val meta = metadata[pubkey]
            MentionCandidate(pubkey, listOfNotNull(meta?.displayName, meta?.name))
        }
        return mentions + MentionTags.resolveTyped(content, mentions, candidates)
    }

    override suspend fun sendMessage(groupId: String, content: String, channel: String?, mentions: Map<String, String>, replyToMessageId: String?, extraTags: List<List<String>>): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.sendMessage(
            groupId = groupId,
            content = content,
            pubKey = pubKey,
            channel = channel,
            mentions = withTypedMentions(groupId, content, mentions),
            replyToMessageId = replyToMessageId,
            extraTags = extraTags,
            signEvent = { sessionManager.signEvent(it) },
        )
    }

    override fun retrySend(eventId: String) = groupManager.retrySend(eventId) { sessionManager.signEvent(it) }

    override fun dismissFailed(groupId: String, eventId: String) = groupManager.dismissFailed(groupId, eventId)

    override suspend fun requestGroupThreads(groupId: String, relayUrl: String?): Boolean = groupManager.requestGroupThreads(groupId, relayUrl)

    override fun closeThreadSubscriptions(groupId: String) = groupManager.closeThreadSubscriptions(groupId)

    override suspend fun fetchThread(groupId: String, rootId: String) = groupManager.fetchThread(groupId, rootId)

    override suspend fun createThread(
        groupId: String,
        title: String,
        content: String,
        mentions: Map<String, String>,
    ): Result<String> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.createThread(
            groupId = groupId,
            title = title,
            content = content,
            pubKey = pubKey,
            mentions = withTypedMentions(groupId, content, mentions),
            signEvent = { sessionManager.signEvent(it) },
        )
    }

    override suspend fun sendThreadReply(
        groupId: String,
        root: NostrGroupClient.NostrMessage,
        parent: NostrGroupClient.NostrMessage,
        content: String,
        mentions: Map<String, String>,
    ): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.sendThreadReply(
            groupId = groupId,
            root = root,
            parent = parent,
            content = content,
            pubKey = pubKey,
            mentions = withTypedMentions(groupId, content, mentions),
            signEvent = { sessionManager.signEvent(it) },
        )
    }

    override suspend fun addUser(
        groupId: String,
        targetPubkey: String,
        roles: List<String>,
        notifyViaDm: Boolean,
    ): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        val result = groupManager.addUser(
            groupId = groupId,
            targetPubkey = targetPubkey,
            roles = roles,
            pubKey = pubKey,
            currentRelayUrl = connectionManager.currentRelayUrl.value,
            signEvent = { sessionManager.signEvent(it) },
        )
        if (notifyViaDm && result is Result.Success && targetPubkey != pubKey) {
            // Resolve the relay on the ADD path, where the group is unambiguous: the invite naddr
            // must address the relay the user was actually added on, not a same-id group elsewhere.
            val inviteRelay = groupManager.getRelayForGroup(groupId) ?: connectionManager.currentRelayUrl.value
            // Fire-and-forget: the DM is a courtesy reach-out; its failure must not fail the add.
            scope.launch { sendGroupAddedDm(inviteRelay, groupId, targetPubkey) }
        }
        return result
    }

    /**
     * NIP-17 DM telling [targetPubkey] they were added to [groupId], carrying the group
     * naddr. It travels over the recipient's own DM relays, so it reaches users who don't
     * connect to the group's NIP-29 relay (the put-user #p watch can't). The naddr sits on
     * its own line so clients that card-preview group links render the invite card.
     */
    private suspend fun sendGroupAddedDm(relayUrl: String, groupId: String, targetPubkey: String) {
        try {
            val relayPubkey = (relayMetadata.value[relayUrl] ?: relayMetadata.value[relayUrl.normalizeRelayUrl()])
                ?.groupNaddrAuthor
            val naddr = Nip19.encodeNaddr(identifier = groupId, relay = relayUrl, kind = 39000, pubkeyHex = relayPubkey)
            // The group's own relay first: NIP-29 ids are relay-local, so an any-relay scan
            // could name a same-id group from another relay.
            val byRelay = groupManager.groupsByRelay.value
            val groupName = (byRelay[relayUrl.normalizeRelayUrl()] ?: byRelay[relayUrl])
                ?.firstOrNull { it.id == groupId }
                ?.name
                ?.takeIf { it.isNotBlank() }
                ?: byRelay.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.id == groupId }?.name?.takeIf { it.isNotBlank() } }
            val title = groupName?.let { "the group \"$it\"" } ?: "a group"
            sendDm(targetPubkey, "You've been added to $title.\nnostr:$naddr")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {}
    }

    override suspend fun removeUser(groupId: String, targetPubkey: String): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.removeUser(
            groupId = groupId,
            targetPubkey = targetPubkey,
            pubKey = pubKey,
            currentRelayUrl = connectionManager.currentRelayUrl.value,
            signEvent = { sessionManager.signEvent(it) },
        )
    }

    override suspend fun rejectJoinRequest(groupId: String, joinRequestEventId: String): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.rejectJoinRequest(
            groupId = groupId,
            joinRequestEventId = joinRequestEventId,
            pubKey = pubKey,
            currentRelayUrl = connectionManager.currentRelayUrl.value,
            signEvent = { sessionManager.signEvent(it) },
        )
    }

    override suspend fun createInviteCode(groupId: String): Result<String> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.createInviteCode(
            groupId = groupId,
            pubKey = pubKey,
            currentRelayUrl = connectionManager.currentRelayUrl.value,
            signEvent = { sessionManager.signEvent(it) },
        )
    }

    override suspend fun revokeInviteCode(groupId: String, eventId: String): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.revokeInviteCode(
            groupId = groupId,
            eventId = eventId,
            pubKey = pubKey,
            currentRelayUrl = connectionManager.currentRelayUrl.value,
            signEvent = { sessionManager.signEvent(it) },
        )
    }

    override suspend fun deleteMessage(groupId: String, messageId: String): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.deleteMessage(
            groupId = groupId,
            messageId = messageId,
            pubKey = pubKey,
            signEvent = { sessionManager.signEvent(it) },
        )
    }

    override suspend fun sendReaction(
        groupId: String,
        targetEventId: String,
        targetPubkey: String,
        emoji: String,
        threadRootId: String?,
    ): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.sendReaction(
            groupId = groupId,
            targetEventId = targetEventId,
            targetPubkey = targetPubkey,
            emoji = emoji,
            pubKey = pubKey,
            signEvent = { sessionManager.signEvent(it) },
            threadRootId = threadRootId,
        )
    }

    override suspend fun requestZapInvoice(
        recipientPubkey: String,
        amountSats: Long,
        comment: String,
        eventId: String?,
    ): Result<ZapManager.ZapInvoice> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return zapManager.requestInvoice(
            recipientPubkey = recipientPubkey,
            amountSats = amountSats,
            comment = comment,
            eventId = eventId,
            senderPubkey = pubKey,
            signEvent = { sessionManager.signEvent(it) },
        )
    }

    override suspend fun watchZapPayment(
        bolt11: String,
        recipientPubkey: String,
        eventId: String?,
    ): Boolean {
        // Poll the receipt relays for the matching kind:9735 while awaiting the flow.
        val matched = withTimeoutOrNull(ZAP_PAYMENT_WATCH_MS) {
            coroutineScope {
                val poller = launch {
                    while (isActive) {
                        pollZapReceipts(recipientPubkey, eventId)
                        delay(ZAP_PAYMENT_POLL_MS)
                    }
                }
                try {
                    zapManager.paidInvoices.first { it.equals(bolt11, ignoreCase = true) }
                } finally {
                    poller.cancel()
                }
            }
        }
        return matched != null
    }

    /** One poll cycle: re-request the zap receipt from connected + general relays. */
    private suspend fun pollZapReceipts(recipientPubkey: String, eventId: String?) {
        // Same relays the receipt was asked to be published to (see ZapManager.receiptRelays).
        zapManager.receiptRelays().forEach { url ->
            try {
                val client = connectionManager.getClientForRelay(url)
                    ?: connectionManager.getOrConnectRelay(url) { msg, c -> enqueueToRelayPipeline(msg, c) }
                if (client != null && client.isConnected()) {
                    if (eventId != null) {
                        client.requestZapReceipts(listOf(eventId))
                    } else {
                        client.requestZapReceiptsForRecipient(recipientPubkey)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
    }

    override fun getMessagesForGroup(groupId: String): List<NostrGroupClient.NostrMessage> = groupManager.getMessagesForGroup(groupId)

    override fun setGroupRelayHint(
        groupId: String,
        relayUrl: String,
    ) = groupManager.setGroupRelayHint(groupId, relayUrl)

    // Unread message operations
    override fun markGroupAsRead(relayUrl: String, groupId: String) {
        unreadManager.markAsRead(relayUrl, groupId)
    }

    override fun markGroupAsReadUpTo(relayUrl: String, groupId: String, timestamp: Long) {
        unreadManager.markAsReadUpTo(relayUrl, groupId, timestamp)
    }

    override fun getUnreadCount(relayUrl: String, groupId: String): Int = unreadManager.getUnreadCount(relayUrl, groupId)

    override fun getLastReadTimestamp(relayUrl: String, groupId: String): Long? = unreadManager.getLastReadTimestamp(relayUrl, groupId)

    // Metadata operations
    private val metadataMessageHandler: (String, NostrGroupClient) -> Unit = { msg, client ->
        handleRelayMessage(msg, client)
    }

    override suspend fun requestUserMetadata(pubkeys: Set<String>, forceStale: Boolean) {
        metadataManager.requestUserMetadata(pubkeys, metadataMessageHandler, forceStale)
    }

    override suspend fun requestUserGroupList(pubkey: String) {
        // Own list flows through the joined-groups state; nothing to fetch here.
        if (pubkey.isBlank() || pubkey == sessionManager.getPublicKey()) return
        // kind:10009 lives on general-purpose relays (author outbox + bootstrap).
        // Connect on demand: when a discovery tab opens, those may not be connected
        // yet, and a connected-only filter would silently send nothing.
        val targets = outboxManager.selectOutboxRelays(authors = listOf(pubkey))
        targets.forEach { relayUrl ->
            runCatching {
                val client = connectionManager.getClientForRelay(relayUrl)?.takeIf { it.isConnected() }
                    ?: connectionManager.getOrConnectRelay(relayUrl, metadataMessageHandler)
                client?.takeIf { it.isConnected() }?.requestUserGroupList(pubkey)
            }
        }
        // The focused NIP-29 relay often hosts members' lists too.
        val focused = connectionManager.getFocusedClient()
        if (focused != null && focused.isConnected()) {
            runCatching { focused.requestUserGroupList(pubkey) }
        }
    }

    override suspend fun fetchUserGroupLists(pubkeys: Set<String>) {
        val myPubkey = sessionManager.getPublicKey()
        val toFetch = pubkeys.filterNot { it.isBlank() || it == myPubkey }
        if (toFetch.isEmpty()) return
        // Same outbox resolution as requestUserGroupList, just done once for every author
        // instead of once per author — selectOutboxRelays already unions relays across
        // however many authors are passed in, so a friends list that mostly clusters on a
        // handful of popular relays (nos.lol, purplepag.es, ...) collapses to that same
        // handful of REQs instead of one REQ per person.
        val targets = outboxManager.selectOutboxRelays(authors = toFetch)
        targets.forEach { relayUrl ->
            scope.launch {
                runCatching {
                    val client = connectionManager.getClientForRelay(relayUrl)?.takeIf { it.isConnected() }
                        ?: connectionManager.getOrConnectRelay(relayUrl, metadataMessageHandler)
                    client?.takeIf { it.isConnected() }?.requestUserGroupLists(toFetch)
                }
            }
        }
        // The focused NIP-29 relay often hosts members' lists too.
        val focused = connectionManager.getFocusedClient()
        if (focused != null && focused.isConnected()) {
            scope.launch { runCatching { focused.requestUserGroupLists(toFetch) } }
        }
    }

    // Serialize check-and-set: the Home and Profile view models both fire this on
    // startup, and without the lock both pass the `contactListRequested` guard
    // before either latches it (the guard is set only after the suspending relay
    // send), doubling the kind:3 REQ to every relay.
    override suspend fun requestContactList() {
        contactListMutex.withLock { requestContactListLocked() }
        // If no relay was connected yet (cold-start / slow bunker connect), the REQ went
        // nowhere and contactListRequested stayed false. Keep retrying in the background
        // until a relay accepts it (or the list lands), so a slow connect self-heals
        // instead of stranding the friends sidebar on "follows nobody" until a reopen.
        //
        // Only while logged in: on logout the screen VM (long-lived) fires requestContactList()
        // once for the activePubkey -> null emission. With no pubkey that arms a retry that can
        // never send, and because scheduleContactListRetry() no-ops while one is already active,
        // it would BLOCK the real retry the subsequent re-login schedules — stranding the friends
        // list on "follows nobody" after a logout -> re-login in a live process (cold-start re-login
        // does not resetContactListState, so nothing cancels the dead retry). Guarding on a present
        // pubkey keeps the logged-out call inert, so re-login always arms a fresh full-budget retry.
        if (sessionManager.getPublicKey() != null && !contactListRequested && !contactListLoaded.value) {
            scheduleContactListRetry()
        }
        // contactListLoaded is flipped by the kind:3 ingestion handler the moment this
        // account's contact list lands (even an empty one), never on a timeout here. On a
        // warm account switch the general-purpose relays that serve kind:3 are still
        // reconnecting, so a blind flip would surface a false "follows nobody" and collapse
        // the friends sidebar for an account that does have follows. An account that
        // genuinely has no kind:3 is resolved by the screen's own loading cap instead, so
        // the skeleton never hangs on a contact list that will never arrive.
    }

    /** Body of [requestContactList]; caller must hold [contactListMutex]. */
    private suspend fun requestContactListLocked() {
        val pubKey = sessionManager.getPublicKey() ?: return
        if (contactListRequested) return

        // kind:3 lives on general-purpose relays, never the NIP-29 group relay
        // (NIP-29 relays don't serve kind:0/3), so exclude them and the focused.
        val nip29Relays = outboxManager.kind10009Relays.value + connectionManager.currentRelayUrl.value
        val targets = (outboxManager.getWriteRelays() + outboxManager.bootstrapRelays)
            .distinct()
            .filter { it !in nip29Relays }

        // Connect bootstrap relays on demand: at cold start they are not yet up, so
        // a connected-only filter would send nothing. We only latch the request once
        // at least one relay actually received the REQ — otherwise the one-shot VM
        // call would permanently suppress the fetch after a restart.
        var sent = 0
        targets.forEach { relayUrl ->
            runCatching {
                val client = connectionManager.getClientForRelay(relayUrl)?.takeIf { it.isConnected() }
                    ?: connectionManager.getOrConnectRelay(relayUrl, metadataMessageHandler)
                if (client != null && client.isConnected()) {
                    client.requestContactList(pubKey)
                    sent++
                }
            }
        }
        if (sent > 0) {
            contactListRequested = true
            // The same REQ carries the kind:10000 filter (see NostrGroupClient.requestContactList),
            // so the mute list is fetched, and retried, together with the contact list.
            muteListRequested = true
        }
    }

    /**
     * Background retry for [requestContactList] when the first attempt found no connected
     * relay. Runs on the app scope so it outlives the screen that triggered it; exits as
     * soon as the REQ lands (contactListRequested) or the list arrives (contactListLoaded).
     */
    private fun scheduleContactListRetry() {
        if (contactListRetryJob?.isActive == true) return
        contactListRetryJob =
            scope.launch {
                repeat(30) {
                    delay(1_000)
                    if (contactListRequested || contactListLoaded.value) return@launch
                    contactListMutex.withLock { requestContactListLocked() }
                }
            }
    }

    override suspend fun followUser(pubkey: String): Result<Unit> {
        if (pubkey.isNotBlank()) applyFollowingChange { it + pubkey }
        return Result.Success(Unit)
    }

    override suspend fun unfollowUser(pubkey: String): Result<Unit> {
        applyFollowingChange { it - pubkey }
        return Result.Success(Unit)
    }

    override suspend fun followUsers(pubkeys: Set<String>): Result<Unit> {
        val clean = pubkeys.filter { it.isNotBlank() }.toSet()
        if (clean.isNotEmpty()) applyFollowingChange { it + clean }
        return Result.Success(Unit)
    }

    /**
     * Flips [following] to the new desired set immediately (no relay round-trip, so
     * rapid sequential follow taps never block on each other) and schedules a single
     * debounced kind:3 publish. [following] is the source of truth for the user's
     * intent; the publish rebuilds the list from it.
     */
    private fun applyFollowingChange(transform: (Set<String>) -> Set<String>) {
        _following.value = transform(_following.value)
        hasUnpublishedContactChanges = true
        schedulePublishContactList()
    }

    /**
     * Coalesces rapid follow/unfollow taps into one publish: each tap cancels the
     * previous pending job and restarts the debounce, so N taps in a row produce a
     * single kind:3 instead of N racing events. Runs on the app scope so it outlives
     * the screen that triggered it (navigating away never loses a follow).
     */
    private fun schedulePublishContactList() {
        pendingContactListPublish?.cancel()
        pendingContactListPublish =
            scope.launch {
                delay(contactListPublishDebounceMs)
                publishContactList()
            }
    }

    /**
     * Builds, signs and publishes a fresh kind:3 from the current [following] set,
     * preserving non-"p" tags (relay hints, petnames) and content from the last known
     * list. Serialized by [contactListMutex]. On the first run it best-effort fetches
     * the existing list so we don't replace a list built on another client.
     */
    private suspend fun publishContactList(): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return contactListMutex.withLock {
            if (!contactListRequested) {
                requestContactListLocked() // already holding contactListMutex
                // Wait briefly for a relay to return the existing list before we
                // overwrite it; absence is treated as "no prior list" after this.
                withTimeoutOrNull(3_000L) { following.first { contactListCreatedAt > 0L } }
            }

            // Rebuild "p" tags from the desired set; keep any other tags + content.
            val newTags = contactListTags.filterNot { it.firstOrNull() == "p" } +
                _following.value.map { listOf("p", it) }
            try {
                val event = org.nostr.nostrord.nostr.Event(
                    pubkey = pubKey,
                    createdAt = org.nostr.nostrord.utils.epochSeconds(),
                    kind = 3,
                    tags = newTags,
                    content = contactListContent,
                )
                val signedEvent = sessionManager.signEvent(event)
                val eventId = signedEvent.id
                    ?: return@withLock Result.Error(AppError.Unknown("Event has no id after signing", null))
                val message = buildJsonArray {
                    add("EVENT")
                    add(signedEvent.toJsonObject())
                }.toString()

                // Publish kind:3 only to general-purpose relays (write + bootstrap),
                // never the NIP-29 group relay: it doesn't serve kind:3 back, so a
                // list written only there would read as empty on the next start.
                val nip29Relays = outboxManager.kind10009Relays.value + connectionManager.currentRelayUrl.value
                val targets = (outboxManager.getWriteRelays() + outboxManager.bootstrapRelays)
                    .distinct()
                    .filter { it !in nip29Relays }
                val clients = targets.mapNotNull { relayUrl ->
                    connectionManager.getClientForRelay(relayUrl)?.takeIf { it.isConnected() }
                        ?: connectionManager.getOrConnectRelay(relayUrl, metadataMessageHandler)?.takeIf { it.isConnected() }
                }
                if (clients.isEmpty()) {
                    return@withLock Result.Error(AppError.Network.Disconnected(connectionManager.currentRelayUrl.value))
                }
                val results = clients.map { client ->
                    scope.async { client.sendAndAwaitOkOrError(message, eventId) }
                }.awaitAll()
                if (results.none { it is PublishResult.Success }) {
                    return@withLock Result.Error(AppError.Network.PublishRejected(results.summarizeFailures()))
                }

                contactListCreatedAt = signedEvent.createdAt
                contactListContent = signedEvent.content
                contactListTags = newTags
                contactListRequested = true
                _contactListLoaded.value = true
                // The desired set is now on the relays; later relay echoes may adopt freely.
                hasUnpublishedContactChanges = false
                Result.Success(Unit)
            } catch (e: Exception) {
                Result.Error(AppError.Unknown(e.message ?: "Failed to update contact list", e))
            }
        }
    }

    override suspend fun muteUser(pubkey: String): Result<Unit> {
        // Muting yourself would hide your own messages everywhere; ignore it.
        if (pubkey.isBlank() || pubkey == sessionManager.getPublicKey()) return Result.Success(Unit)
        if (pubkey in _mutedPubkeys.value) return Result.Success(Unit)
        // Private by default (who you mute is nobody's business). Falls back to a public
        // `p` tag while the existing private section is unreadable: merging into a
        // ciphertext we can't decrypt would destroy another client's entries.
        if (muteListPrivateWritable()) {
            applyMuteChange(newPrivate = privateMuted + pubkey)
        } else {
            applyMuteChange(newPublic = publicMuted + pubkey)
        }
        return Result.Success(Unit)
    }

    override suspend fun unmuteUser(pubkey: String): Result<Unit> {
        if (pubkey !in _mutedPubkeys.value) return Result.Success(Unit)
        applyMuteChange(newPublic = publicMuted - pubkey, newPrivate = privateMuted - pubkey)
        return Result.Success(Unit)
    }

    /**
     * Flips [mutedPubkeys] immediately (filtering reacts without a relay round-trip),
     * persists the snapshot, and schedules a single debounced kind:10000 publish so rapid
     * taps (bulk unmute in Settings) coalesce into one event.
     */
    private fun applyMuteChange(
        newPublic: Set<String> = publicMuted,
        newPrivate: Set<String> = privateMuted,
    ) {
        if (newPublic == publicMuted && newPrivate == privateMuted) return
        publicMuted = newPublic
        privateMuted = newPrivate
        sessionManager.getPublicKey()?.let { applyMutedSetsAndPersist(it) }
        hasUnpublishedMuteChanges = true
        pendingMuteListPublish?.cancel()
        pendingMuteListPublish =
            scope.launch {
                delay(contactListPublishDebounceMs)
                publishMuteList()
            }
    }

    /**
     * Builds, signs and publishes a fresh kind:10000: public `p` tags from [publicMuted],
     * plus the private section (non-`p` private tags + [privateMuted]) re-encrypted with
     * NIP-44 to self. Non-`p` public tags are carried over and an unreadable `content` is
     * republished verbatim, so entries added by other clients aren't clobbered. On the
     * first run it best-effort fetches the existing list (it rides the kind:3 REQ) before
     * overwriting.
     */
    private suspend fun publishMuteList(): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return muteListMutex.withLock {
            if (!muteListRequested) {
                contactListMutex.withLock { requestContactListLocked() }
                withTimeoutOrNull(3_000L) { mutedPubkeys.first { muteListCreatedAt > 0L } }
            }

            val newPublicTags = org.nostr.nostrord.nostr.Nip51.rebuildMuteTags(muteListPublicTags, publicMuted)
            val privateWritable = muteListPrivateWritable()
            val newPrivateTags =
                if (privateWritable) {
                    org.nostr.nostrord.nostr.Nip51.rebuildMuteTags(muteListPrivateTags, privateMuted)
                } else {
                    muteListPrivateTags
                }
            try {
                val newContent =
                    when {
                        // Opaque private section: pass it through untouched.
                        !privateWritable -> muteListContent
                        newPrivateTags.isEmpty() -> ""
                        else -> {
                            val signer = ActiveAccountManager.session.value?.signer
                                ?: return@withLock Result.Error(AppError.Auth.NotAuthenticated)
                            signer.nip44Encrypt(pubKey, org.nostr.nostrord.nostr.Nip51.encodeTags(newPrivateTags))
                        }
                    }
                val event = org.nostr.nostrord.nostr.Event(
                    pubkey = pubKey,
                    createdAt = org.nostr.nostrord.utils.epochSeconds(),
                    kind = org.nostr.nostrord.nostr.Nip51.KIND_MUTE_LIST,
                    tags = newPublicTags,
                    content = newContent,
                )
                val signedEvent = sessionManager.signEvent(event)
                val eventId = signedEvent.id
                    ?: return@withLock Result.Error(AppError.Unknown("Event has no id after signing", null))
                val message = buildJsonArray {
                    add("EVENT")
                    add(signedEvent.toJsonObject())
                }.toString()

                val publish = publishToGeneralPurposeRelays(message, eventId)
                if (publish is Result.Error) return@withLock publish

                muteListCreatedAt = signedEvent.createdAt
                muteListPublicTags = newPublicTags
                muteListContent = signedEvent.content
                if (privateWritable) {
                    muteListPrivateTags = newPrivateTags
                    muteListPrivateDecryptedFrom = if (newPrivateTags.isEmpty()) "" else signedEvent.content
                }
                muteListRequested = true
                hasUnpublishedMuteChanges = false
                applyMutedSetsAndPersist(pubKey)
                Result.Success(Unit)
            } catch (e: Exception) {
                Result.Error(AppError.Unknown(e.message ?: "Failed to update mute list", e))
            }
        }
    }

    /**
     * NIP-56 report (kind:1984). Settles on the FIRST relay OK (a 1984 is append-only,
     * so one acceptance is enough — unlike the replaceable lists there is no stale-copy
     * hazard); remaining sends finish in the background. Connects run per-relay inside
     * the same race, so one dead outbox relay can't hold the modal's spinner.
     */
    override suspend fun reportUser(
        pubkey: String,
        type: org.nostr.nostrord.nostr.Nip56.ReportType,
        note: String,
        eventId: String?,
    ): Result<Unit> {
        val myPubkey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return try {
            val event = org.nostr.nostrord.nostr.Event(
                pubkey = myPubkey,
                createdAt = org.nostr.nostrord.utils.epochSeconds(),
                kind = org.nostr.nostrord.nostr.Nip56.KIND_REPORT,
                tags = org.nostr.nostrord.nostr.Nip56.reportTags(pubkey, eventId, type),
                content = note.trim(),
            )
            val signedEvent = sessionManager.signEvent(event)
            val signedId = signedEvent.id
                ?: return Result.Error(AppError.Unknown("Event has no id after signing", null))
            val message = buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()

            // Same routing as kind:3 / kind:10000: general-purpose relays only. Normalized
            // BEFORE distinct: the kind:10002 write list and the hardcoded bootstrap list spell
            // the same relay differently (trailing slash, case), and raw-string dedup let both
            // through to one pooled client = the same EVENT frame twice on one socket.
            val nip29Relays = (
                outboxManager.kind10009Relays.value +
                    connectionManager.currentRelayUrl.value
                ).map { it.normalizeRelayUrl() }.toSet()
            val targets = (outboxManager.getWriteRelays() + outboxManager.bootstrapRelays)
                .map { it.normalizeRelayUrl() }
                .distinct()
                .filter { it !in nip29Relays }
            if (targets.isEmpty()) {
                return Result.Error(AppError.Network.Disconnected(connectionManager.currentRelayUrl.value))
            }
            // Buffered to relay count so background stragglers never block on send
            // after this function has already returned on the first OK.
            val verdicts = kotlinx.coroutines.channels.Channel<PublishResult?>(capacity = targets.size)
            targets.forEach { relayUrl ->
                scope.launch {
                    val client = connectionManager.getClientForRelay(relayUrl)?.takeIf { it.isConnected() }
                        ?: connectionManager.getOrConnectRelay(relayUrl, metadataMessageHandler)?.takeIf { it.isConnected() }
                    verdicts.send(client?.sendAndAwaitOkOrError(message, signedId))
                }
            }
            val failures = mutableListOf<PublishResult>()
            repeat(targets.size) {
                when (val verdict = verdicts.receive()) {
                    null -> {} // relay unreachable
                    is PublishResult.Success -> return Result.Success(Unit)
                    else -> failures += verdict
                }
            }
            if (failures.isEmpty()) {
                return Result.Error(AppError.Network.Disconnected(connectionManager.currentRelayUrl.value))
            }
            Result.Error(AppError.Network.PublishRejected(failures.summarizeFailures()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(AppError.Unknown(e.message ?: "Failed to send report", e))
        }
    }

    private suspend fun refreshVisibleUserMetadata() {
        // Wait for resubscribeAllGroups REQs to deliver events before collecting pubkeys.
        // Without this delay, messages/members may still be empty from the previous session.
        delay(3_000)

        val openedGroups = groupManager.getOpenedGroupIds()
        val groupPubkeys = openedGroups.flatMap { groupId ->
            val messagePubkeys = groupManager.messages.value[groupId]
                ?.takeLast(50)?.map { it.pubkey } ?: emptyList()
            val memberPubkeys = groupManager.getMembersForGroup(groupId)
            messagePubkeys + memberPubkeys
        }
        // Also revalidate the followed users shown in the home sidebar so a friend's new
        // name/avatar appears without having to open a group. Only stale entries are
        // refetched (forceStale), and the request batches them into one REQ per relay.
        val pubkeys = (groupPubkeys + _following.value).toSet().filter { metadataManager.isStale(it) }.toSet()
        if (pubkeys.isNotEmpty()) {
            metadataManager.requestUserMetadata(pubkeys, metadataMessageHandler, forceStale = true)
        }
    }

    /**
     * Build a NIP-98 Authorization header value for HTTP requests.
     * Returns "Nostr <base64>", or null only when no account is signed in. A signer
     * failure (bunker timeout, extension denial) propagates so the caller can surface
     * the real cause instead of a false "not authenticated".
     */
    suspend fun buildNip98AuthHeader(url: String, method: String): String? {
        val pubKey = sessionManager.getPublicKey() ?: return null
        return org.nostr.nostrord.nostr.Nip98.buildAuthHeader(pubKey, url, method) { sessionManager.signEvent(it) }
    }

    /**
     * Build a Blossom (BUD-11) Authorization header value: a kind:24242 event bound to the
     * blob hash and the verb ("upload", "delete", …). Returns "Nostr <base64>", or null only
     * when no account is signed in; a signer failure propagates, as with NIP-98.
     */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    suspend fun buildBlossomAuthHeader(
        sha256Hex: String,
        verb: String,
    ): String? {
        val pubKey = sessionManager.getPublicKey() ?: return null
        val now = org.nostr.nostrord.utils.epochMillis() / 1000
        val event = org.nostr.nostrord.nostr.Event(
            pubkey = pubKey,
            createdAt = now,
            kind = 24242,
            tags = listOf(
                listOf("t", verb),
                listOf("x", sha256Hex),
                // Short-lived: the token authorizes exactly this blob, and a long window
                // would let a leaked header be replayed.
                listOf("expiration", (now + org.nostr.nostrord.network.upload.BLOSSOM_AUTH_TTL_SECONDS).toString()),
            ),
            content = "Upload Blob",
        )
        val signed = sessionManager.signEvent(event)
        val json = signed.toJsonObject().toString()
        val encoded = kotlin.io.encoding.Base64.encode(json.encodeToByteArray())
        return "Nostr $encoded"
    }

    /**
     * Update the current user's profile metadata (kind 0 event).
     */
    override suspend fun updateProfileMetadata(
        displayName: String?,
        name: String?,
        about: String?,
        picture: String?,
        banner: String?,
        nip05: String?,
        lud16: String?,
        website: String?,
    ): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)

        return try {
            // Start from the existing raw JSON so unknown fields are preserved.
            var existing = metadataManager.getMetadata(pubKey)

            // If cache is empty, try to fetch fresh metadata before saving.
            // This avoids losing unknown fields when the cache was evicted or not yet loaded.
            // If nothing comes back after the timeout, treat as a new user (no prior kind:0).
            if (existing == null) {
                requestUserMetadata(setOf(pubKey))
                existing = withTimeoutOrNull(5_000L) {
                    metadataManager.userMetadata.first { it.containsKey(pubKey) }
                }?.get(pubKey)
            }

            val base: Map<String, JsonElement> = existing?.rawContentJson?.let { raw ->
                try {
                    Json.parseToJsonElement(raw).jsonObject.toMap()
                } catch (_: Exception) {
                    null
                }
            } ?: emptyMap()

            val merged = buildJsonObject {
                base.forEach { (k, v) -> put(k, v) }
                displayName?.let { put("display_name", JsonPrimitive(it)) }
                name?.let { put("name", JsonPrimitive(it)) }
                about?.let { put("about", JsonPrimitive(it)) }
                picture?.let { put("picture", JsonPrimitive(it)) }
                banner?.let { put("banner", JsonPrimitive(it)) }
                nip05?.let { put("nip05", JsonPrimitive(it)) }
                lud16?.let { put("lud16", JsonPrimitive(it)) }
                website?.let { put("website", JsonPrimitive(it)) }
            }
            val content = merged.toString()

            val event = org.nostr.nostrord.nostr.Event(
                pubkey = pubKey,
                createdAt = org.nostr.nostrord.utils.epochSeconds(),
                kind = 0,
                tags = emptyList(),
                content = content,
            )

            // Sign the event
            val signedEvent = sessionManager.signEvent(event)

            // Build event message in correct format
            val message = buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()

            // Publish to write relays + bootstrap relays so other clients can discover
            // the updated profile (kind:0) via general-purpose relays.
            val eventId = signedEvent.id ?: return Result.Error(AppError.Unknown("Event has no id after signing", null))
            val targets = (outboxManager.getWriteRelays() + outboxManager.bootstrapRelays).distinct()
            val clients = targets.mapNotNull { relayUrl ->
                connectionManager.getClientForRelay(relayUrl)?.takeIf { it.isConnected() }
            }.ifEmpty {
                listOfNotNull(connectionManager.getFocusedClient())
            }
            if (clients.isEmpty()) {
                return Result.Error(AppError.Network.Disconnected(connectionManager.currentRelayUrl.value))
            }
            val results = clients.map { client ->
                scope.async { client.sendAndAwaitOkOrError(message, eventId) }
            }.awaitAll()
            if (results.none { it is PublishResult.Success }) {
                return Result.Error(AppError.Network.PublishRejected(results.summarizeFailures()))
            }

            val updatedMetadata = UserMetadata(
                pubkey = pubKey,
                name = name ?: existing?.name,
                displayName = displayName ?: existing?.displayName,
                picture = picture ?: existing?.picture,
                about = about ?: existing?.about,
                nip05 = nip05 ?: existing?.nip05,
                banner = existing?.banner,
                rawContentJson = content,
            )
            metadataManager.updateLocalMetadata(pubKey, updatedMetadata, signedEvent.createdAt)

            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(AppError.Unknown(e.message ?: "Failed to update profile", e))
        }
    }

    override suspend fun publishRelayList(relays: List<org.nostr.nostrord.network.outbox.Nip65Relay>): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return try {
            val event = org.nostr.nostrord.nostr.Event(
                pubkey = pubKey,
                createdAt = org.nostr.nostrord.utils.epochSeconds(),
                kind = 10002,
                tags = relays.map { it.toTag() },
                content = "",
            )
            val signedEvent = sessionManager.signEvent(event)
            val eventId = signedEvent.id ?: return Result.Error(AppError.Unknown("Event has no id after signing", null))
            val message = buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()

            // Publish to current write relays + bootstrap to ensure the new list is
            // discoverable even when switching relay sets entirely.
            val targets = (outboxManager.getWriteRelays() + outboxManager.bootstrapRelays).distinct()
            val clients = targets.mapNotNull { relayUrl ->
                connectionManager.getClientForRelay(relayUrl)?.takeIf { it.isConnected() }
            }.ifEmpty {
                listOfNotNull(connectionManager.getFocusedClient())
            }
            if (clients.isEmpty()) {
                return Result.Error(AppError.Network.Disconnected(connectionManager.currentRelayUrl.value))
            }
            val results = clients.map { client ->
                scope.async { client.sendAndAwaitOkOrError(message, eventId) }
            }.awaitAll()
            if (results.none { it is PublishResult.Success }) {
                return Result.Error(AppError.Network.PublishRejected(results.summarizeFailures()))
            }

            outboxManager.updateMyRelayList(pubKey, relays)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(AppError.Unknown(e.message ?: "Failed to publish relay list", e))
        }
    }

    override suspend fun requestEventById(eventId: String, relayHints: List<String>, author: String?, groupId: String?) {
        metadataManager.requestEventById(eventId, relayHints, author, groupId) { msg, client ->
            handleRelayMessage(msg, client)
        }
    }

    /**
     * Request an addressable event (naddr) by its coordinates.
     * Addressable events are identified by kind, pubkey, and identifier (d-tag).
     */
    override suspend fun requestAddressableEvent(
        kind: Int,
        pubkey: String,
        identifier: String,
        relays: List<String>,
    ) {
        metadataManager.requestAddressableEvent(kind, pubkey, identifier, relays) { msg, client ->
            handleRelayMessage(msg, client)
        }
    }

    /**
     * Request a quoted event from the focused relay.
     * Used for q tags in group messages where the quoted event is on the same relay.
     */
    override suspend fun requestQuotedEvent(eventId: String) {
        // Skip if already cached
        if (metadataManager.hasCachedEvent(eventId)) return

        val client = connectionManager.getFocusedClient() ?: return
        client.requestEventById(eventId)
    }

    // Outbox operations
    override suspend fun requestRelayLists(pubkeys: Set<String>) {
        outboxManager.requestRelayLists(pubkeys) { msg, client ->
            handleRelayMessage(msg, client)
        }
    }

    override fun getRelayListForPubkey(pubkey: String): List<Nip65Relay> = outboxManager.getCachedRelayList(pubkey)

    override fun selectOutboxRelays(
        authors: List<String>,
        taggedPubkeys: List<String>,
        explicitRelays: List<String>,
    ): List<String> = outboxManager.selectOutboxRelays(
        authors = authors,
        taggedPubkeys = taggedPubkeys,
        explicitRelays = explicitRelays,
        currentNip29Relay = connectionManager.currentRelayUrl.value,
    )

    private suspend fun publishJoinedGroupsList() {
        val pubKey = sessionManager.getPublicKey() ?: return
        publishJoinedGroupsListWith(pubKey)
    }

    /**
     * Publish kind:10009 with an optional custom relay list.
     * Returns the result so callers can check if the signer approved.
     */
    private suspend fun publishJoinedGroupsListWith(
        pubKey: String,
        nip29Relays: List<String> = outboxManager.kind10009Relays.value.toList(),
        orderOverride: List<Pair<String, String>>? = null,
    ): Result<Unit> {
        // The in-memory map can be partial early in a session (the storage restore and
        // the kind:10009 fetch are async). An event published from a partial map drops
        // the missing groups FOR GOOD: on restart the persisted timestamp guard ignores
        // our own event, leaving only the (then clobbered) storage slots. Merge memory
        // with the persisted per-relay slots so the published list is always a
        // superset; removals stay correct because leave/removeRelay update storage
        // before publishing.
        val memory = groupManager.joinedGroupsByRelay.value
        val storedRelays = SecureStorage.loadRelayListFor(pubKey).map { it.normalizeRelayUrl() }
        val relays = (storedRelays + memory.keys.map { it.normalizeRelayUrl() }).distinct()
        val perRelay =
            relays
                .associateWith { relay ->
                    SecureStorage.getJoinedGroupsForRelay(pubKey, relay) + memory[relay].orEmpty()
                }.filterValues { it.isNotEmpty() }
        return outboxManager.publishJoinedGroupsList(
            pubKey = pubKey,
            joinedGroupsByRelay = perRelay,
            nip29Relays = nip29Relays,
            signEvent = { sessionManager.signEvent(it) },
            messageHandler = { msg, client -> handleRelayMessage(msg, client) },
            orderOverride = orderOverride,
            encryptPrivate = { plaintext -> encryptOwnListSection(plaintext) },
        )
    }

    /**
     * Read the private section of one of our own NIP-51 lists (self-encrypted to our own key).
     * Null when the signer can't or won't: the caller then leaves the section untouched.
     */
    private suspend fun decryptOwnListSection(ciphertext: String): String? {
        val pubKey = sessionManager.getPublicKey() ?: return null
        return decryptOwnSection(pubKey, ciphertext)
    }

    /**
     * One decrypt per distinct ciphertext of our own, shared by every self-encrypted list
     * (kind:10000, kind:10009, kind:30078). Null when the signer cannot or will not read it.
     */
    private suspend fun decryptOwnSection(pubKey: String, ciphertext: String): String? = selfDecryptCache.decrypt(pubKey, ciphertext) {
        val signer = ActiveAccountManager.session.value?.signer ?: return@decrypt null
        // A signer that asks the user gets no deadline: only the user can answer, and a
        // deadline here would abandon the open dialog and later raise a second one.
        if (signer.promptsUser) {
            signer.nip44Decrypt(pubKey, ciphertext)
        } else {
            kotlinx.coroutines.withTimeoutOrNull(PRIVATE_DECRYPT_TIMEOUT_MS) {
                signer.nip44Decrypt(pubKey, ciphertext)
            }
        }
    }

    /** Write side of [decryptOwnListSection]. NIP-44 only: a list this client rewrites is upgraded. */
    private suspend fun encryptOwnListSection(plaintext: String): String? {
        val pubKey = sessionManager.getPublicKey() ?: return null
        val signer = ActiveAccountManager.session.value?.signer ?: return null
        return try {
            signer.nip44Encrypt(pubKey, plaintext)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun reorderGroups(order: List<Pair<String, String>>): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        // Membership is not derived from [order]: the publish emits only groups in the
        // joined superset, so an entry here that is not joined is a no-op, and a joined
        // group missing from it falls to the end. A reorder can never add a group.
        val normalized = order.map { (relay, id) -> relay.normalizeRelayUrl() to id }.distinct()
        return publishJoinedGroupsListWith(pubKey, orderOverride = normalized)
    }

    // Groups whose subscriptions the relay CLOSED (typically auth-required).
    // StateFlow + atomic `.update` is required: parallel AUTH handlers across
    // joined relays mutate this concurrently from Dispatchers.Default.
    private val _closedGroupSubscriptions = MutableStateFlow<Set<String>>(emptySet())

    // Message IDs collected per msg_/thread subscription, used to fetch reactions after EOSE.
    private val pendingReactionFetch = mutableMapOf<String, MutableList<String>>()

    /** Thread-pane subscriptions whose events get the same post-EOSE kind:7 backfill as msg_. */
    private fun isThreadSub(subId: String) = subId.startsWith("threads_") ||
        subId.startsWith("threadrepl_") ||
        subId.startsWith("threadfocus_")

    // Pool relay URLs that have been actively connected during this session (were focused at
    // some point or were reconnected). Only these are eligible for reconnection on drop.
    private val connectedPoolRelays = mutableSetOf<String>()

    // Per-relay debug counters: relayUrl -> event count since last connect
    private val relayEventCounts = mutableMapOf<String, Int>()

    /**
     * Unified message handler for all relay pool connections.
     *
     * Problem this solves: getOrConnectRelay() always returns the *existing* pool client
     * when a relay is already connected, ignoring the new onMessage callback. This means
     * whichever handler was used on the first connection wins permanently.
     *
     * If initializeOutboxModel() connected relay A with handleRelayMessage first, then
     * connectToRelayBackground() got that same client. requestGroups() was sent, but
     * kind 39000 responses went to handleRelayMessage which drops them — the relay
     * appeared "connected but silent" from the group perspective.
     *
     * Fix: both initializeOutboxModel and connectToRelayBackground now use this unified
     * handler so the first-connection handler always handles both NIP-29 and outbox events.
     */
    /**
     * Fetch NIP-57 zap receipts (kind 9735) for [messageIds] from general-purpose relays.
     * NIP-29 group relays don't serve zap receipts (they carry no `h` tag), so we query
     * connected outbox/bootstrap relays — connecting a couple if none are open. Responses
     * route through the relay pipeline → kind 9735 → ZapManager aggregation.
     */
    private fun fetchZapReceiptsFromGeneralRelays(messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        scope.launch {
            // Query the exact relays the zap request asked the receipt to be published to —
            // not the NIP-29 group relay, which rejects the (h-less) 9735.
            zapManager.receiptRelays().forEach { url ->
                try {
                    val client = connectionManager.getClientForRelay(url)
                        ?: connectionManager.getOrConnectRelay(url) { msg, c -> enqueueToRelayPipeline(msg, c) }
                    if (client != null && client.isConnected()) client.requestZapReceipts(messageIds)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
            }
        }
    }

    private fun handleUnifiedMessage(msg: String, client: NostrGroupClient) {
        // Parse once — every downstream handler reuses this JsonArray.
        val arr = try {
            json.parseToJsonElement(msg).jsonArray
        } catch (_: Exception) {
            return
        }

        val msgType = arr.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return

        // Outbox routing for EOSE
        if (msgType == "EOSE" && arr.size >= 2) {
            val subId = arr[1].jsonPrimitive.content
            val url = client.getRelayUrl()
            if (subId.startsWith("mux_chat_")) {
                relayEventCounts.remove(url)
                // Mux is live again — a later "restricted" CLOSED starts backing off fresh.
                muxRestrictedRetryAttempts.remove(url)
            }
            if (subId == "dm_inbox") {
                // This relay finished delivering its share of the backlog (decryption may still be
                // draining). Record WHICH relay EOSEd so maybeLatchDmFullSync only latches "synced"
                // once EVERY subscribed DM relay is done and every delivered wrap is processed; until
                // then resendDmInboxReq keeps `since = 0` and resumes next session instead of stranding.
                val myPub = sessionManager.getPublicKey()
                if (myPub != null) {
                    dmInboxEosedRelays = dmInboxEosedRelays + url.normalizeRelayUrl()
                    maybeLatchDmFullSync(myPub)
                    refreshDmSyncing()
                }
            }
            // Fall through — handleMessage also needs EOSE for group pagination
        }

        // Outbox routing for EVENT kinds 10009/10002
        if (msgType == "EVENT" && arr.size >= 3) {
            val event = arr[2].jsonObject
            val kind = event["kind"]?.jsonPrimitive?.int
            val url = client.getRelayUrl()
            relayEventCounts[url] = (relayEventCounts[url] ?: 0) + 1
            connStats.onEventReceived(url)
            if (kind == 10009) {
                val pubKey = sessionManager.getPublicKey() ?: ""
                val eventPubkey = event["pubkey"]?.jsonPrimitive?.content
                if (eventPubkey != null && eventPubkey != pubKey) {
                    // Another user's public group list (profile page fetch).
                    val createdAt = event["created_at"]?.jsonPrimitive?.longOrNull ?: 0L
                    if (createdAt >= (userGroupListCreatedAt[eventPubkey] ?: 0L)) {
                        userGroupListCreatedAt[eventPubkey] = createdAt
                        val refs =
                            event["tags"]?.jsonArray.orEmpty().mapNotNull { tag ->
                                val t = tag.jsonArray
                                if (t.getOrNull(0)?.jsonPrimitive?.content != "group") return@mapNotNull null
                                val gid = t.getOrNull(1)?.jsonPrimitive?.content ?: return@mapNotNull null
                                val relay =
                                    (t.getOrNull(2)?.jsonPrimitive?.content ?: "")
                                        .normalizeRelayUrl()
                                        .ifBlank { connectionManager.currentRelayUrl.value }
                                UserGroupRef(relayUrl = relay, groupId = gid)
                            }.distinct()
                        _userGroupLists.value = _userGroupLists.value + (eventPubkey to refs)
                        scheduleUserGroupListsPersist()
                    }
                    return
                }
                scope.launch {
                    outboxManager.handleKind10009Event(
                        event = event,
                        currentRelayUrl = connectionManager.currentRelayUrl.value,
                        pubKey = pubKey,
                        onGroupsUpdated = { groups -> groupManager.setJoinedGroups(groups) },
                        onRelaysRestored = { newRelays ->
                            groupManager.prePopulateRelayList(newRelays)
                            _relayMetadataManager.fetchAll(newRelays)
                            autoConnectFirstRelay(newRelays)
                        },
                        onRelayGroupsUpdated = { relayGroups ->
                            groupManager.updateAllRelayJoinedGroups(relayGroups)
                            // Prune relays from the rail that are not in the authoritative
                            // kind:10009 event — prevents stale relays from a previous
                            // session's SecureStorage from staying in _groupsByRelay.
                            val authoritativeRelays = outboxManager.kind10009Relays.value +
                                relayGroups.keys
                            if (authoritativeRelays.isNotEmpty()) {
                                groupManager.pruneRelaysNotIn(authoritativeRelays)
                            }
                        },
                        messageHandler = { m, c -> enqueueToRelayPipeline(m, c) },
                        isGroupDropped = { relay, gid -> groupManager.isLocallyDropped(gid, relay) },
                        decryptPrivate = { ciphertext -> decryptOwnListSection(ciphertext) },
                    )
                }
                return
            }
            if (kind == 10002) {
                outboxManager.handleKind10002Event(event, sessionManager.getPublicKey())
                return
            }
            if (kind == 3) {
                handleKind3Event(event)
                return
            }
            if (kind == org.nostr.nostrord.nostr.Nip51.KIND_MUTE_LIST) {
                handleKind10000Event(event)
                return
            }
            if (kind == Nip78.KIND_APP_DATA) {
                handleKind30078Event(event)
                return
            }
            // NIP-17 DM gift wrap addressed to us: decrypt with the active signer.
            if (kind == Nip17.KIND_GIFT_WRAP) {
                // Master DM toggle off: never unwrap/decrypt, even for a wrap that raced the CLOSE.
                if (!AppModule.dmSettings.dmEnabled.value) return
                val giftWrap = runCatching { parseSignedEventJson(event.toString()) }.getOrNull() ?: return
                val myPub = sessionManager.getPublicKey() ?: return
                val signer = ActiveAccountManager.session.value?.signer ?: return
                val wrapId = giftWrap.id
                if (wrapId != null) {
                    if (wrapId !in dmReceivedWrapIds) {
                        dmReceivedWrapIds = dmReceivedWrapIds + wrapId
                        refreshDmSyncing()
                    }
                    // Before any dedup skip: the same wrap sits on several DM relays, and every
                    // delivery counts for the message's "seen on" relay list.
                    dmManager.recordWrapRelay(wrapId, client.getRelayUrl().normalizeRelayUrl())
                    // Already decrypted, or given up this session: skip the (expensive) round-trip.
                    if (wrapId in dmProcessedWrapIds || wrapId in dmGivenUpWrapIds) {
                        maybeLatchDmFullSync(myPub)
                        refreshDmSyncing()
                        return
                    }
                    // Already being decrypted by an in-flight coroutine: don't spawn a duplicate.
                    if (wrapId in dmInFlightWrapIds) return
                    dmInFlightWrapIds = dmInFlightWrapIds + wrapId
                }
                scope.launch {
                    // Captured at arrival: wraps streaming in before every DM relay EOSEd are
                    // backlog; anything after is a live incoming DM.
                    val liveWrap = dmInboxSubscribedRelays.isNotEmpty() &&
                        dmInboxEosedRelays.containsAll(dmInboxSubscribedRelays)
                    // NIP-4e fast path: a wrap addressed to a key we hold decrypts in-process, so
                    // it skips the boot hold, the trickle and the bunker gate entirely. This is
                    // the payoff of announcing a key: an adopted-sender backlog drains at local
                    // speed while the signer stays idle. A miss here costs two failed HMACs and
                    // falls through to the paced signer path below, without counting as a failure.
                    if (dmEncryptionManager.heldKeys().isNotEmpty() &&
                        sessionManager.getPublicKey() == myPub &&
                        AppModule.dmSettings.dmEnabled.value
                    ) {
                        val localOnly =
                            dmEncryptionManager.heldKeys().map { kp ->
                                Nip17.Nip44Decryptor { peer, ciphertext -> Nip44.decrypt(ciphertext, kp.privateKeyHex, peer) }
                            }
                        val handledLocally =
                            try {
                                dmManager.ingestGiftWrap(giftWrap, myPub, localOnly, ::verifyLegacyDmSender)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Throwable) {
                                false
                            }
                        if (handledLocally) {
                            if (wrapId != null && wrapId !in dmProcessedWrapIds) {
                                dmProcessedWrapIds = dmProcessedWrapIds + wrapId
                                dmFailCounts = dmFailCounts - wrapId
                                scheduleSaveProcessedWrapIds(myPub)
                            }
                            if (wrapId != null) dmInFlightWrapIds = dmInFlightWrapIds - wrapId
                            maybeLatchDmFullSync(myPub)
                            refreshDmSyncing()
                            return@launch
                        }
                    }
                    try {
                        // A bunker signer pays a remote round-trip per NIP-44 decrypt, and a gift
                        // wrap needs two (wrap then seal). A cold start with a large DM backlog
                        // would flood the single signer connection and starve the kind:22242 AUTH
                        // sign that private NIP-29 relays need, leaving private groups stuck on
                        // "auth-required". Hold the backlog until the active relay's AUTH is signed
                        // (awaitAuthOrTimeout returns the moment AUTH completes, or after the grace
                        // when the relay needs no AUTH). Local/NIP-07 decrypt in-process and skip it.
                        if (signer is NostrSigner.Bunker) {
                            connectionManager.getFocusedClient()?.awaitAuthOrTimeout(DM_INGEST_AUTH_GRACE_MS)
                        }
                        // This wrap captured myPub/signer when it arrived; the AUTH grace and the gate
                        // below can take seconds (bunker round-trips), so the active account may have
                        // switched meanwhile. A previous account's wrap must never land in (and then get
                        // persisted under) the new account's inbox, so drop it once the active pubkey no
                        // longer matches.
                        val decrypt: suspend () -> Boolean = {
                            if (sessionManager.getPublicKey() != myPub) {
                                false
                            } else if (!AppModule.dmSettings.dmEnabled.value) {
                                false
                            } else {
                                kotlinx.coroutines.withTimeoutOrNull(DM_DECRYPT_TIMEOUT_MS) {
                                    dmManager.ingestGiftWrap(giftWrap, myPub, dmDecryptors(signer), ::verifyLegacyDmSender)
                                } ?: false
                            }
                        }
                        val handled =
                            if (signer is NostrSigner.Bunker) {
                                // Boot hold, then admit one wrap per two client-pacer slots, releasing
                                // the gate before the decrypt so many requests stay in-flight for the
                                // signer's burst.
                                val bootHold = dmBacklogHoldUntilMs - org.nostr.nostrord.utils.epochMillis()
                                if (bootHold > 0) delay(bootHold)
                                // Live wraps and the newest DM_EAGER_DECRYPT_CAP go straight to the
                                // gate; deeper history parks on a trickle slot (assigned FIFO, so
                                // approximately newest-first) outside it.
                                val trickleSlot = dmTrickleMutex.withLock {
                                    when {
                                        liveWrap -> null
                                        dmEagerDecryptCount < DM_EAGER_DECRYPT_CAP -> {
                                            dmEagerDecryptCount++
                                            null
                                        }
                                        else -> {
                                            dmTrickleNextSlotMs =
                                                maxOf(org.nostr.nostrord.utils.epochMillis(), dmTrickleNextSlotMs) + DM_TRICKLE_INTERVAL_MS
                                            dmTrickleNextSlotMs
                                        }
                                    }
                                }
                                if (trickleSlot != null) {
                                    val trickleWait = trickleSlot - org.nostr.nostrord.utils.epochMillis()
                                    if (trickleWait > 0) delay(trickleWait)
                                }
                                bunkerPublishGate.withLock { delay(BUNKER_WRAP_ADMIT_INTERVAL_MS) }
                                decrypt()
                            } else {
                                dmDecryptSemaphore.withPermit { decrypt() }
                            }
                        if (handled && wrapId != null && wrapId !in dmProcessedWrapIds) {
                            dmProcessedWrapIds = dmProcessedWrapIds + wrapId
                            dmFailCounts = dmFailCounts - wrapId
                            scheduleSaveProcessedWrapIds(myPub)
                            maybeLatchDmFullSync(myPub)
                            refreshDmSyncing()
                        } else if (!handled && wrapId != null) {
                            // Transient failure (signer timeout/unresponsive, or a malformed wrap).
                            // Count it; after the cap, give up for this session so the retry loop
                            // stops re-streaming it (relay rate limit / EOSE spam). Retried fresh
                            // next session, where the signer may be reachable again.
                            val fails = (dmFailCounts[wrapId] ?: 0) + 1
                            dmFailCounts = dmFailCounts + (wrapId to fails)
                            if (fails >= DM_MAX_DECRYPT_ATTEMPTS) {
                                dmGivenUpWrapIds = dmGivenUpWrapIds + wrapId
                                refreshDmSyncing()
                                val at = giftWrap.createdAt
                                if (dmGivenUpOldestAt == 0L || at < dmGivenUpOldestAt) dmGivenUpOldestAt = at
                                // Settling the last unprocessed wrap can complete the sync.
                                maybeLatchDmFullSync(myPub)
                            }
                        }
                    } finally {
                        if (wrapId != null) dmInFlightWrapIds = dmInFlightWrapIds - wrapId
                    }
                }
                return
            }
            // A user's NIP-17 DM relay list (kind:10050).
            if (kind == Nip17.KIND_DM_RELAYS) {
                if (!AppModule.dmSettings.dmEnabled.value) return
                runCatching { parseSignedEventJson(event.toString()) }.getOrNull()?.let {
                    dmManager.ingestDmRelays(it)
                    if (it.pubkey == sessionManager.getPublicKey()) {
                        _myDmRelays.value = dmRelaysFor(it.pubkey)
                        // Our own DM relay list resolved: the inbox was subscribed on the default
                        // relays at boot, so re-subscribe on the now-known relays or DMs sent only
                        // to them are never requested. Only when the set actually CHANGED, so the
                        // same kind:10050 arriving from several relays doesn't re-stream the whole
                        // gift-wrap backlog and flood the bunker signer.
                        val newRelays = dmRelaysWithDefaults(it.pubkey).distinct().toSet()
                        if (newRelays != dmInboxSubscribedRelays) {
                            scope.launch { resendDmInboxReq(it.pubkey) }
                            // The pairing sub went out before this list resolved, so it is watching
                            // the defaults only. Another device publishes its request to these
                            // relays; without re-firing here the request is never even asked for.
                            scope.launch { sendPairingReq(it.pubkey) }
                        }
                    }
                }
                return
            }
            // Our own kind:5 on the request we are waiting for: another device declined it. NIP-4e
            // has no rejection event, and the deletion is the account withdrawing its own request,
            // so this is the only signal that ends the wait instead of leaving it hanging.
            if (kind == 5 && pendingPairingRequestId != null) {
                val pending = pendingPairingRequestId
                val myPub = sessionManager.getPublicKey()
                runCatching { parseSignedEventJson(event.toString()) }.getOrNull()?.let {
                    if (it.pubkey == myPub && it.tags.any { tag -> tag.firstOrNull() == "e" && tag.getOrNull(1) == pending }) {
                        pendingPairingRequestId = null
                        dmPairingManager.failed("Your other device dismissed the request.")
                    }
                }
            }
            // NIP-4e device pairing, our own account only: a device asking for the encryption key
            // (4454) and a device answering with it (4455).
            if (kind == Nip4e.KIND_CLIENT_KEY || kind == Nip4e.KIND_KEY_SHARE) {
                if (!AppModule.dmSettings.dmEnabled.value) return
                val myPub = sessionManager.getPublicKey() ?: return
                runCatching { parseSignedEventJson(event.toString()) }.getOrNull()?.let {
                    if (it.pubkey != myPub) return
                    if (kind == Nip4e.KIND_CLIENT_KEY) {
                        // Only a device that holds the key can answer.
                        if (dmEncryptionManager.heldKeys().isNotEmpty()) dmPairingManager.onRequestSeen(it)
                    } else {
                        handleKeyShare(it, myPub)
                    }
                }
                return
            }
            // A user's NIP-4e encryption key announcement (kind:10044).
            if (kind == Nip4e.KIND_ENCRYPTION_KEY) {
                if (!AppModule.dmSettings.dmEnabled.value) return
                runCatching { parseSignedEventJson(event.toString()) }.getOrNull()?.let {
                    dmManager.ingestEncryptionKey(it)
                    // Our own announcement is the relay's truth about this account: another device
                    // may have rotated to a key we do not hold, or withdrawn ours.
                    if (it.pubkey == sessionManager.getPublicKey()) {
                        dmEncryptionManager.ingestAnnouncement(Nip4e.encryptionKeyFrom(it), it.createdAt, fromRelay = true)
                    }
                }
                return
            }
        }

        // Delegate all other events (39000, 39001, 39002, 9, 7, 5, 0, AUTH, OK, CLOSED…)
        handleMessage(msg, arr, msgType, client)
    }

    private fun handleMessage(msg: String, arr: JsonArray, msgType: String, client: NostrGroupClient) {
        when (msgType) {
            "AUTH" -> {
                val authChallenge = client.parseAuthChallenge(arr) ?: return
                // Remember this relay gates reads behind auth, so pagination can
                // await re-AUTH after a reconnect instead of racing ahead and
                // getting CLOSED "auth-required".
                client.markAuthChallengeSeen()
                scope.launch {
                    // Only run post-AUTH side effects when we actually signed and sent
                    // the response. handleAuthChallenge dedupes per relay; a chatty
                    // relay that resends AUTH frames in tight succession (observed on
                    // chat.wisp.talk) would otherwise re-fire notifyAuthCompleted and
                    // resubscribeAfterAuth for every duplicate frame, flooding the
                    // relay with redundant subscriptions.
                    if (sessionManager.handleAuthChallenge(client, authChallenge)) {
                        // Signal that AUTH is done so connect()/switchRelay() can
                        // proceed with requestGroups() after the relay accepted auth.
                        client.notifyAuthCompleted(sessionManager.getPublicKey())
                        resubscribeAfterAuth(client)
                        // Queued sends blocked on this relay's AUTH (resolveClientForGroup
                        // fails closed pre-AUTH) can flush now.
                        pendingEventManager?.onConnectionRestored()
                    }
                }
            }

            "OK" -> {
                val (eventId, success, message) = client.parseOkMessage(arr) ?: return
                scope.launch {
                    client.handleOkResponse(eventId, success, message)
                }
            }

            "EOSE" -> {
                if (arr.size < 2) return
                val subId = arr[1].jsonPrimitive.content
                // CRITICAL: EOSE handling must be async to allow pending message tracking to complete
                // yield() before EOSE so pending message-tracking coroutines (scope.launch
                // blocks queued by handleMessage) get scheduled first, reducing the race
                // window where messageCount is read before all tracking completes.
                val sourceRelayUrl = client.getRelayUrl()
                scope.launch {
                    yield()
                    // A msg_ initial load that EOSEs while the relay still needs AUTH (some
                    // relays empty-EOSE instead of CLOSED auth-required) is not a real empty
                    // result: hold it in InitialLoading so the UI keeps skeletons until
                    // resubscribeAfterAuth replays it post-AUTH, instead of flashing empty.
                    val authBlocked = client.requiresAuth() && !client.hasAuthSucceeded()
                    val held = authBlocked &&
                        subId.startsWith("msg_") &&
                        groupManager.holdInitialLoadForReauth(subId)
                    if (!held) {
                        groupManager.handleEoseSuspend(subId, sourceRelayUrl)
                    }
                    // Mux metadata EOSE: prune REQed ids that got no 39000 back (deleted
                    // groups). Skipped pre-AUTH, when the relay may still be withholding
                    // metadata it will serve post-AUTH.
                    if (!authBlocked && subId == client.muxMetaSubId()) {
                        groupManager.reconcileMuxMetadataEose(sourceRelayUrl)
                    }
                    // Wake any pending batchFetch waiting on EOSE for this metadata sub.
                    metadataManager.notifyMetadataEose(subId, sourceRelayUrl)
                    // EOSE on any mux sub is proof of life for the relay's live feed —
                    // the signal the periodic stale-re-arm keys on (quiet relays have
                    // no EVENTs, so the EOSE is what keeps them from reading as dead).
                    if (subId.startsWith("mux_")) {
                        groupManager.noteMuxActivity(client.getRelayUrl())
                    }
                    // After the mux chat subscription delivers its backlog, detect any gaps
                    // (groups whose cursor expected events that never arrived from this relay).
                    if (subId.startsWith("mux_chat_")) {
                        detectAndFillGaps(client.getRelayUrl())
                    }
                    // Fetch reactions for message IDs received in this msg_ or thread subscription
                    if (subId.startsWith("msg_") || isThreadSub(subId)) {
                        val messageIds = pendingReactionFetch.remove(subId)
                        if (!messageIds.isNullOrEmpty()) {
                            try {
                                val reactSubId = client.requestReactionsForMessages(messageIds)
                                // Auto-close the reactions sub after EOSE
                                if (reactSubId != null) {
                                    // Will be closed when its own EOSE arrives via reactions_ prefix
                                }
                            } catch (_: Exception) {}
                            // Zap receipts (kind 9735) carry no `h` tag, so they live on
                            // general relays, not the NIP-29 group relay — fetch them there.
                            // Chat only: the thread UI has no zap display.
                            if (subId.startsWith("msg_")) {
                                fetchZapReceiptsFromGeneralRelays(messageIds)
                            }
                        }
                    }
                    // The forum thread-roots sub (threads_<groupId>) reached EOSE: stored threads
                    // are in, so the list can settle (show "No threads yet" only now, not on a timer).
                    // The answer is also authoritative for what still exists, so anything we hold
                    // for this relay that it did not serve was deleted elsewhere - drop it. Skipped
                    // while AUTH is still pending, when an empty answer proves nothing.
                    if (subId.startsWith("threads_")) {
                        val threadGroupId = subId.removePrefix("threads_")
                        groupManager.markThreadsLoaded(threadGroupId)
                        if (!authBlocked) groupManager.reconcileThreadRootsAtEose(threadGroupId, sourceRelayUrl)
                    }
                    if (subId.startsWith("threadrepl_") && !authBlocked) {
                        groupManager.reconcileThreadRepliesAtEose(subId.removePrefix("threadrepl_"), sourceRelayUrl)
                    }
                    closeOneShotSubAfterEose(subId, client)
                }
            }

            "CLOSED" -> {
                if (arr.size < 2) return
                val subId = arr[1].jsonPrimitive.content
                val reason = if (arr.size >= 3) arr[2].jsonPrimitive.contentOrNull ?: "" else ""
                // communities.nos.social caps subscriptions at 50 and rejects the overflow
                // with "restricted: Subscription quota exceeded: 50/50" — a rate limit, NOT
                // NIP-29 access control. Treating it as a private-group restriction marked the
                // group private and PERSISTED it for 7 days, so the group rendered the "Private
                // group" placeholder for groups the user belongs to (the relay where this bites
                // is the one with the most joined groups, i.e. the user's busiest). Exclude
                // quota/rate-limit reasons; that sub just needs reopening when a slot frees.
                val isQuotaOrRateLimit = reason.contains("quota", ignoreCase = true) ||
                    reason.contains("rate-limit", ignoreCase = true) ||
                    reason.contains("rate limit", ignoreCase = true) ||
                    reason.contains("too many", ignoreCase = true)
                val isRestricted = reason.contains("restricted") && !isQuotaOrRateLimit
                val isAuthRequired = reason.contains("auth-required")

                // A batched discovery REQ (meta_batch_ / members_batch_) dies WHOLE when any
                // #d in it is a private group — relay29 validates the filter as one — so the
                // public groups batched alongside never get their 39000/39002 and render as
                // bare-id cards (profile page / friends grid). Split the batch into per-group
                // REQs once: the private offenders CLOSE individually (and pick up the
                // restricted mark below on their own subs) while the public ones resolve.
                // auth-required is NOT split: fetchGroupPreviews already replays the same
                // batch once AUTH signs, and a restricted CLOSED on that replay lands here.
                if (isRestricted && (subId.startsWith("meta_batch_") || subId.startsWith("members_batch_"))) {
                    val batchIds = client.takeBatchGroupIds(subId)
                    if (batchIds != null) {
                        val isMeta = subId.startsWith("meta_batch_")
                        scope.launch {
                            batchIds.forEach { id ->
                                try {
                                    if (isMeta) client.requestGroupMetadata(id) else client.requestGroupMembers(id)
                                } catch (e: Throwable) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                }
                            }
                        }
                    }
                    return
                }

                // "restricted" on the group-list subscription: distinguish between
                // (a) unfiltered requestGroups() failing — relay genuinely denies access,
                // and (b) #d-filtered requestGroupsForIds() failing because one of the
                // joined groups in the batch is restricted. Only (a) marks the whole
                // relay. (b) is silent — OTHER GROUPS is collapsed by design, so no
                // fallback unfiltered fetch (that would leak OTHER groups into the
                // homescreen). Per-group meta_/msg_ CLOSEDs from requestPrivateGroupData
                // and resubscribeAfterAuth identify the specific offender.
                if (isRestricted && subId.startsWith("group-list")) {
                    val relayUrl = client.getRelayUrl()
                    val wasFullFetch = groupManager.hasPendingFullFetch(relayUrl)
                    if (wasFullFetch) {
                        _restrictedRelays.value = _restrictedRelays.value + (relayUrl to reason)
                        groupManager.cancelPendingFullFetch(relayUrl)
                        groupManager.markRelayLoaded(relayUrl)
                        connectionManager.setError(relayUrl, reason)
                    } else {
                        groupManager.markRelayLoaded(relayUrl)
                    }
                    return
                }

                if (isAuthRequired) {
                    // Only record groups that belong to THIS relay so resubscribeAfterAuth
                    // doesn't send cross-relay metadata requests to the authed client.
                    val relayUrl = client.getRelayUrl()
                    val activeGroupIds = groupManager.getGroupIdsForMux(relayUrl)
                    if (activeGroupIds.isNotEmpty()) {
                        _closedGroupSubscriptions.update { it + activeGroupIds }
                    }
                    // If the relay denied the unfiltered group-list REQ with auth-required,
                    // clear the pending-full-fetch marker. Otherwise the flag stays set
                    // forever (EOSE never arrives), and any later attempt (including the
                    // user expanding OTHER GROUPS) is silently dropped by the dedup guard
                    // in requestFullGroupListForRelay, leaving the panel empty until restart.
                    if (subId.startsWith("group-list")) {
                        groupManager.cancelPendingFullFetch(relayUrl)
                        groupManager.markRelayLoaded(relayUrl)
                    }
                }

                // Track per-group "restricted" status so the UI can show a private
                // group placeholder with invite code input. Any per-group sub whose
                // ID embeds an 8-char group prefix is a reliable signal when it
                // closes with "restricted" — msg_, meta_, members_, admins_ all
                // isolate exactly one group.
                if (isRestricted) {
                    val prefix = when {
                        subId.startsWith("msg_") -> subId.removePrefix("msg_").substringBefore("_")
                        subId.startsWith("meta_") -> subId.removePrefix("meta_")
                        subId.startsWith("members_") -> subId.removePrefix("members_")
                        subId.startsWith("admins_") -> subId.removePrefix("admins_")
                        else -> null
                    }
                    if (prefix != null) {
                        val groupId = groupManager.getGroupIdByPrefix(prefix)
                            ?: groupManager.activeGroupId?.takeIf { it.startsWith(prefix) }
                        if (groupId != null) {
                            groupManager.markGroupRestricted(client.getRelayUrl(), groupId, reason)
                        }
                    }
                }

                // Unblock any pending state-machine load (transitions InitialLoading → Exhausted).
                // Exception: a pre-AUTH auth-required CLOSE on a msg_ initial load is not a real
                // "no results" — resubscribeAfterAuth replays the read once AUTH completes. Hold
                // the load in InitialLoading (skeletons) instead of settling it to a false empty
                // state, which is the "open a private group from the homepage" flicker.
                val sourceRelayUrl = client.getRelayUrl()
                scope.launch {
                    val held = isAuthRequired &&
                        subId.startsWith("msg_") &&
                        groupManager.holdInitialLoadForReauth(subId)
                    if (!held) {
                        groupManager.handleEoseSuspend(subId, sourceRelayUrl)
                    }
                }

                // A mux sub CLOSED "restricted" was terminal: no branch below re-arms it, so
                // the live feed for the whole relay went silently deaf (publishes still OK'd,
                // nothing received) until restart. Observed on groups.0xchat.com, which answers
                // a mux re-REQ moments after a successful AUTH with "restricted: not a member"
                // even for the group's own admin — its membership check races. Retry with
                // exponential backoff (a genuinely restricted relay just re-CLOSEs one tiny
                // REQ per minute at the cap); the tracker must be cleared first, since a
                // relay-side CLOSED does not invalidate muxTracker and refreshMux would no-op.
                if (isRestricted && subId.startsWith("mux_")) {
                    val relayUrl = client.getRelayUrl()
                    // The sibling mux_* CLOSEDs (chat/reactions/del) land together — coalesce
                    // into one retry and count the round once.
                    val rescheduling = muxRestrictedRetryJobs[relayUrl]?.isActive == true
                    val attempt = if (rescheduling) {
                        muxRestrictedRetryAttempts[relayUrl] ?: 1
                    } else {
                        ((muxRestrictedRetryAttempts[relayUrl] ?: 0) + 1)
                            .also { muxRestrictedRetryAttempts[relayUrl] = it }
                    }
                    muxRestrictedRetryJobs[relayUrl]?.cancel()
                    muxRestrictedRetryJobs[relayUrl] = scope.launch {
                        delay(minOf(5_000L * (1L shl (attempt - 1)), 60_000L))
                        groupManager.clearMuxTrackerForRelay(relayUrl)
                        groupManager.refreshMuxDebounced(relayUrl)
                    }
                }

                // Re-open the mux subscription when the relay closes it for non-auth reasons.
                // pyramid.fiatjaf.com and similar relays drop idle subs without closing the WS.
                if (!isAuthRequired &&
                    !isRestricted &&
                    (
                        subId.startsWith("mux_chat_") ||
                            subId.startsWith("mux_reactions_") ||
                            subId.startsWith("mux_meta_")
                        )
                ) {
                    val relayUrl = client.getRelayUrl()
                    scope.launch {
                        delay(2_000) // brief back-off before re-opening
                        groupManager.refreshMuxDebounced(relayUrl)
                    }
                    metadataRefreshJob?.cancel()
                    metadataRefreshJob = scope.launch {
                        delay(3_000)
                        refreshVisibleUserMetadata()
                    }
                }
            }

            "EVENT" -> {
                if (arr.size < 3) return
                // A socket still AUTH'd as another account answers REQs under THAT identity, so
                // its events belong to a read scope this session does not have (a private group's
                // messages and member lists). Drop them instead of letting them land in the active
                // account's state and get cached under its key. Public sockets never authenticated
                // and are not filtered.
                if (client.isAuthedAsOther(sessionManager.getPublicKey())) return
                val subId = arr[1].jsonPrimitive.content
                val event = arr[2].jsonObject
                val kind = event["kind"]?.jsonPrimitive?.int

                // Handle event subscriptions (event_*, e_* or the #h-scoped eh_*) for quotes
                if (isQuoteFetchSubId(subId)) {
                    metadataManager.parseAndCacheEvent(event, client.getRelayUrl())?.let { cachedEvent ->
                        if (!metadataManager.hasMetadata(cachedEvent.pubkey)) {
                            scope.launch {
                                requestUserMetadata(setOf(cachedEvent.pubkey))
                            }
                        }
                    }
                    return
                }

                // Dispatch by kind — each parser works on the pre-extracted JsonObject
                when (kind) {
                    39000 -> {
                        val groupMetadata = client.parseGroupMetadata(event) ?: return
                        groupManager.handleGroupMetadata(groupMetadata, client.getRelayUrl())
                        scope.launch { client.handleGroupCreationEvent(subId, groupMetadata) }
                    }

                    39002 -> {
                        val groupMembers = client.parseGroupMembers(event) ?: return
                        val createdAt = event["created_at"]?.jsonPrimitive?.long ?: 0L
                        val memberPubkeys = groupManager.handleGroupMembers(groupMembers, createdAt, client.getRelayUrl())
                        val pubkeysNeedingMetadata = memberPubkeys.filter { !metadataManager.hasMetadata(it) }
                        if (pubkeysNeedingMetadata.isNotEmpty()) {
                            scope.launch {
                                // forceStale bypasses the 5-minute negative cache: a member
                                // whose kind:0 we failed to fetch on first open (slow relay,
                                // EOSE timeout) would otherwise stay blank until the cache
                                // expires, even when the user switches back to the group. A
                                // fresh 39002 is the signal to retry those profiles; already
                                // cached ones stay filtered, so this does not refetch them.
                                requestUserMetadata(pubkeysNeedingMetadata.toSet(), forceStale = true)
                            }
                        }
                    }

                    39001 -> {
                        val groupAdmins = client.parseGroupAdmins(event) ?: return
                        val createdAt = event["created_at"]?.jsonPrimitive?.long ?: 0L
                        groupManager.handleGroupAdmins(groupAdmins, createdAt, client.getRelayUrl())
                    }

                    39003 -> {
                        val groupRoles = client.parseGroupRoles(event) ?: return
                        val createdAt = event["created_at"]?.jsonPrimitive?.long ?: 0L
                        groupManager.handleGroupRoles(groupRoles, createdAt, client.getRelayUrl())
                    }

                    39004 -> {
                        val live = client.parseLiveKitParticipants(event) ?: return
                        val createdAt = event["created_at"]?.jsonPrimitive?.long ?: 0L
                        groupManager.handleLiveKitParticipants(live, createdAt)
                        // Names and avatars for the room tiles; cached pubkeys are filtered out.
                        val needMetadata = live.participants.filter { !metadataManager.hasMetadata(it) }
                        if (needMetadata.isNotEmpty()) {
                            scope.launch { requestUserMetadata(needMetadata.toSet()) }
                        }
                    }

                    9008 -> {
                        val tags = event["tags"]?.jsonArray ?: return
                        val groupId = tags.firstOrNull {
                            it.jsonArray.getOrNull(0)?.jsonPrimitive?.contentOrNull == "h"
                        }?.jsonArray?.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: return
                        val relayUrl = client.getRelayUrl()
                        val pubKey = sessionManager.getPublicKey()
                        scope.launch {
                            val changed = groupManager.handleRemoteDeleteGroup(groupId, relayUrl, pubKey)
                            if (changed) publishJoinedGroupsList()
                        }
                    }

                    0 -> {
                        val parsed = client.parseUserMetadata(event) ?: return
                        metadataManager.handleMetadataEvent(parsed.pubkey, parsed.metadata, parsed.createdAt)
                    }

                    9735 -> {
                        // NIP-57 zap receipt — aggregate per zapped event id for UI totals.
                        zapManager.handleZapReceipt(event)
                    }

                    7 -> {
                        val reaction = client.parseReaction(event) ?: return
                        val reactorPubkey = groupManager.handleReaction(reaction, relayUrl = client.getRelayUrl())
                        if (reactorPubkey != null && !metadataManager.hasMetadata(reactorPubkey)) {
                            scope.launch {
                                requestUserMetadata(setOf(reactorPubkey))
                            }
                        }
                        val currentPubkey = sessionManager.getPublicKey()
                        if (currentPubkey != null && reaction.pubkey != currentPubkey) {
                            // Prefer the NIP-25 `p` tag — it tells us the target author
                            // directly, so we don't have to wait for the target message
                            // to clear the EventOrderingBuffer. Fall back to the cross-
                            // group cache lookup when the reactor omitted the `p` tag.
                            val groupId = reaction.groupId
                                ?: groupManager.findMessageByIdAcrossGroups(reaction.targetEventId)?.first
                            val isForSelf = when {
                                reaction.targetAuthorPubkey != null ->
                                    reaction.targetAuthorPubkey == currentPubkey
                                else ->
                                    groupManager
                                        .findMessageByIdAcrossGroups(reaction.targetEventId)
                                        ?.second?.pubkey == currentPubkey
                            }
                            if (isForSelf && groupId != null) {
                                unreadManager.onReactionReceived(client.getRelayUrl(), groupId, reaction)
                            }
                        }
                    }

                    else -> {
                        // Chat messages (kind 9, 5, 9000-9003, 9021-9022, etc.)
                        val message = client.parseMessage(event) ?: return
                        val senderPubkey = groupManager.handleMessage(message, msg, subId, client.getRelayUrl())
                        if (senderPubkey != null && (!metadataManager.hasMetadata(senderPubkey) || metadataManager.isStale(senderPubkey))) {
                            scope.launch {
                                requestUserMetadata(setOf(senderPubkey))
                            }
                        }
                        // Track message ID for reaction fetch after EOSE
                        if ((subId.startsWith("msg_") || isThreadSub(subId)) && message.id.isNotBlank()) {
                            pendingReactionFetch.getOrPut(subId) { mutableListOf() }.add(message.id)
                        }
                    }
                }
            }
        }
    }

    /**
     * CLOSE one-shot subscriptions once their EOSE arrives so the relay frees the
     * slot. Live subscriptions (mux_*, group-list) are intentionally absent and
     * stay open. Shared by both message handlers so the light metadata/outbox
     * handler ([handleRelayMessage]) cleans up the same way the full one does.
     */
    private fun closeOneShotSubAfterEose(subId: String, client: NostrGroupClient) {
        if (subId.startsWith("meta_") ||
            subId.startsWith("admins_") ||
            subId.startsWith("members_") ||
            subId.startsWith("metadata_") ||
            subId.startsWith("msg_") ||
            subId.startsWith("gapfill_") ||
            isQuoteFetchSubId(subId) ||
            subId.startsWith("a_") ||
            subId.startsWith("reactions_") ||
            subId.startsWith("zaps_") ||
            subId.startsWith("threadfocus_") ||
            // requestSelfPutUser's actor-enrichment fetch (distinct from the live mux_padd_ watch).
            subId.startsWith("padd_one_")
        ) {
            scope.launch {
                try {
                    client.send("""["CLOSE","$subId"]""")
                } catch (_: Exception) {}
            }
        }
    }

    private fun handleRelayMessage(msg: String, client: NostrGroupClient) {
        try {
            val arr = json.parseToJsonElement(msg).jsonArray

            // Handle EOSE
            if (arr.size >= 2 && arr[0].jsonPrimitive.content == "EOSE") {
                val subId = arr[1].jsonPrimitive.content
                // Relays connected via the metadata/outbox path route here and
                // would otherwise never close their one-shot fetches (observed:
                // zap-receipt subs leaking on nos.lol/damus).
                closeOneShotSubAfterEose(subId, client)
                return
            }

            // Handle EVENT
            if (arr.size >= 3 && arr[0].jsonPrimitive.content == "EVENT") {
                val subId = arr[1].jsonPrimitive.content
                val event = arr[2].jsonObject
                val kind = event["kind"]?.jsonPrimitive?.int

                // Handle event subscriptions (event_*, e_* or the #h-scoped eh_*)
                if (isQuoteFetchSubId(subId)) {
                    metadataManager.parseAndCacheEvent(event, client.getRelayUrl())?.let { cachedEvent ->
                        if (!metadataManager.hasMetadata(cachedEvent.pubkey)) {
                            scope.launch {
                                requestUserMetadata(setOf(cachedEvent.pubkey))
                            }
                        }
                    }
                    return
                }

                // Handle addressable event subscriptions (addr_* or a_*)
                if (subId.startsWith("addr_") || subId.startsWith("a_")) {
                    metadataManager.parseAndCacheAddressableEvent(event, client.getRelayUrl())?.let { cachedEvent ->
                        if (!metadataManager.hasMetadata(cachedEvent.pubkey)) {
                            scope.launch {
                                requestUserMetadata(setOf(cachedEvent.pubkey))
                            }
                        }
                    }
                    return
                }

                // Handle kind:10009 (joined groups)
                if (kind == 10009) {
                    val pubKey = sessionManager.getPublicKey() ?: ""
                    scope.launch {
                        outboxManager.handleKind10009Event(
                            event = event,
                            currentRelayUrl = connectionManager.currentRelayUrl.value,
                            pubKey = pubKey,
                            onGroupsUpdated = { groups -> groupManager.setJoinedGroups(groups) },
                            onRelaysRestored = { newRelays ->
                                groupManager.prePopulateRelayList(newRelays)
                                _relayMetadataManager.fetchAll(newRelays)
                                // Auto-connect to the first relay if no focused is connected yet
                                autoConnectFirstRelay(newRelays)
                            },
                            onRelayGroupsUpdated = { relayGroups ->
                                groupManager.updateAllRelayJoinedGroups(relayGroups)
                                val authoritativeRelays = outboxManager.kind10009Relays.value +
                                    relayGroups.keys
                                if (authoritativeRelays.isNotEmpty()) {
                                    groupManager.pruneRelaysNotIn(authoritativeRelays)
                                }
                            },
                            messageHandler = { m, c -> enqueueToRelayPipeline(m, c) },
                            isGroupDropped = { relay, gid -> groupManager.isLocallyDropped(gid, relay) },
                            decryptPrivate = { ciphertext -> decryptOwnListSection(ciphertext) },
                        )
                    }
                    return
                }

                // Handle kind:10002 (NIP-65 relay list)
                if (kind == 10002) {
                    outboxManager.handleKind10002Event(event, sessionManager.getPublicKey())
                    return
                }

                // Handle kind:3 (NIP-02 contact list) for the active account
                if (kind == 3) {
                    handleKind3Event(event)
                    return
                }

                // Handle kind:10000 (NIP-51 mute list) for the active account
                if (kind == org.nostr.nostrord.nostr.Nip51.KIND_MUTE_LIST) {
                    handleKind10000Event(event)
                    return
                }

                // NIP-78 per-group notification levels from another device.
                if (kind == Nip78.KIND_APP_DATA) {
                    handleKind30078Event(event)
                    return
                }
            }
        } catch (_: Exception) {}

        // Handle user metadata
        val parsed = client.parseUserMetadata(msg)
        if (parsed != null) {
            metadataManager.handleMetadataEvent(parsed.pubkey, parsed.metadata, parsed.createdAt)
        }
    }

    /**
     * Re-subscribe to all groups on a pool relay after it reconnects.
     * Called by [relayReconnectScheduler]'s doReconnect lambda and [resubscribeAllGroups].
     */
    private suspend fun resubscribePoolRelay(relayUrl: String, client: NostrGroupClient) {
        val groupsOnRelay = groupManager.getGroupsForRelay(relayUrl)
        if (groupsOnRelay.isNotEmpty()) {
            groupManager.handleConnectionLostForGroups(groupsOnRelay.map { it.id })
        }
        // Fast-lane: prioritize the active group on pool relay reconnect.
        val activeGroupId = groupManager.activeGroupId
        if (activeGroupId != null && groupManager.getRelayForGroup(activeGroupId) == relayUrl) {
            scope.launch {
                groupManager.requestGroupMessages(activeGroupId)
                groupManager.requestGroupMembers(activeGroupId)
                groupManager.requestGroupAdmins(activeGroupId)
                groupManager.requestGroupRoles(activeGroupId)
            }
        }

        // Mux subs cover all kinds: 39000/39001/39002 (metadata/members/admins) +
        // chat/reactions for opened groups.
        groupManager.refreshMuxSubscriptionsForRelay(relayUrl)

        // If this revived relay is one of our DM relays, re-arm the gift-wrap inbox on it.
        if (relayUrl.normalizeRelayUrl() in _myDmRelays.value) resubscribeDmInbox()
    }

    /**
     * Revive NIP-29 pool relays that were previously connected (had been focused at some point)
     * and dropped during an internet outage.
     *
     * Only reconnects relays in [connectedPoolRelays] — relays that were never selected by
     * the user remain dormant (lazy connection model).
     */
    private fun reconnectDroppedNip29PoolRelays() {
        val focusedUrl = connectionManager.currentRelayUrl.value
        for (relayUrl in connectedPoolRelays.toList()) {
            // Skip a blank (or platform-null) entry that leaked into the pool from a relay list,
            // so it is never normalized or scheduled (the non-null receiver check would crash this
            // worker coroutine).
            if (relayUrl.isNullOrBlank() || relayUrl == focusedUrl) continue
            scope.launch {
                val existing = connectionManager.getClientForRelay(relayUrl)
                if (existing == null) {
                    val priority = if (relayUrl == activeRelayUrl) {
                        RelayReconnectScheduler.Priority.ACTIVE
                    } else {
                        RelayReconnectScheduler.Priority.BACKGROUND
                    }
                    relayReconnectScheduler.schedule(relayUrl, priority = priority)
                }
            }
        }
    }

    /**
     * Re-establish all group subscriptions after a connection (re-)connect.
     *
     * Called from both auto-reconnect (onReconnected) and manual reconnect (reconnect()).
     * Explicitly resets group loading states to Idle before re-subscribing so
     * startInitialLoad() always succeeds regardless of timing — groups might still be
     * in InitialLoading/Exhausted/HasMore if the previous disconnect was very fast.
     *
     * Relay separation: only touches NIP-29 groups stored in groupManager; bootstrap
     * and fallback relays connect on-demand and are NOT re-subscribed here.
     */
    private suspend fun resubscribeAllGroups(client: NostrGroupClient) {
        val relayUrl = connectionManager.currentRelayUrl.value
        // The focused relay can also be revived by connectFocused paths (network-change
        // reconnect, manual retry) that bypass onRelayConnected — consume its pending
        // resubscribe mark here so a later pool connect doesn't re-arm it a second time.
        relaysNeedingResubscribe.remove(relayUrl)
        // Restore cache so the UI shows groups immediately while the re-fetch is in flight.
        groupManager.restoreGroupsForRelay(relayUrl)
        // Short AUTH grace before the re-fetch: public groups load fast, and the few groups
        // gated behind AUTH recover via resubscribeAfterAuth + requestPrivateGroupData. The
        // full bunker sign budget is not spent here, so it doesn't stall the public list.
        client.awaitAuthOrTimeout()
        // Always re-fetch on reconnect. restoreGroupsForRelay already populated the UI;
        // the fresh EOSE will prune any stale groups the relay no longer serves
        // (e.g. an ephemeral relay that was restarted and lost its group list).
        lastRequestGroupsAt[relayUrl] = epochSeconds()
        groupManager.markRelayLoading(relayUrl)
        requestGroupsForRelay(client, relayUrl)

        // Re-subscribe opened groups, PRESERVING pagination cursors. A reconnect must
        // not reset a mid-pagination group to Idle: the fast-lane initial load below
        // would then re-fire with `until = oldest message - 1`, and when the oldest is a
        // bulk-delivered moderation event (an old join far older than the chat frontier)
        // it jumps to the floor and marks the group Exhausted, skipping all un-paginated
        // middle history. The cursor is a timestamp bookmark; the mux refresh re-sends
        // the live feed, so keeping it loses nothing.
        val openedGroupIds = groupManager.getOpenedGroupIds()
            .filter { groupManager.getRelayForGroup(it) == relayUrl }
        if (openedGroupIds.isNotEmpty()) {
            groupManager.handleReconnectForGroups(openedGroupIds.toList())
        }

        // An open threads pane has no mux behind it: its kind:11 / kind:1111 REQs died with the
        // socket, so without this the pane goes silent until the user leaves and re-enters it.
        groupManager.resubscribeOpenThreads(relayUrl)

        // Fast-lane: direct requests for the ACTIVE group so it renders first.
        // Mux provides breadth for all groups; direct requests provide speed for the
        // group the user is currently looking at. Deduplicator handles overlap.
        val activeGroupId = groupManager.activeGroupId
        if (activeGroupId != null && groupManager.getRelayForGroup(activeGroupId) == relayUrl) {
            scope.launch {
                groupManager.requestGroupMessages(activeGroupId)
                groupManager.requestGroupMembers(activeGroupId)
                groupManager.requestGroupAdmins(activeGroupId)
                groupManager.requestGroupRoles(activeGroupId)
            }
        }

        // Mux refresh covers all live subscriptions:
        // mux_meta (kinds 39000/39001/39002) for all joined groups,
        // mux_chat + mux_reactions for opened groups with cursor-based since.
        groupManager.refreshMuxSubscriptionsForRelay(relayUrl)
    }

    /**
     * Re-subscribe for group messages after a successful NIP-42 AUTH.
     * Covers groups whose subscriptions were closed with auth-required,
     * as well as all currently loaded groups on THIS relay.
     *
     * Only groups that belong to [client]'s relay are handled here.
     * Sending requestGroupMetadata for groups on a different relay causes
     * "Not connected" crashes and wastes relay bandwidth.
     */
    private suspend fun resubscribeAfterAuth(client: NostrGroupClient) {
        val relayUrl = client.getRelayUrl()
        if (!client.isConnected()) return

        if (connectionManager.getFocusedClient() === client) {
            val now = epochSeconds()
            val lastAt = lastRequestGroupsAt[relayUrl] ?: 0L
            val normalized = relayUrl.normalizeRelayUrl()
            // On the FIRST auth completion for this relay this session, any full-list
            // result captured so far is untrustworthy: an auth-required relay may
            // answer the unauthenticated pre-AUTH group-list REQ with an empty EOSE
            // (rather than CLOSED auth-required), which marks the relay "fully
            // fetched" and would make sessionFetched below skip this authed fetch —
            // leaving OTHER GROUPS empty even when expanded (observed on hzrd149's
            // relay). Invalidate only once: after the first authed fetch the marker
            // is trustworthy, so relays that re-issue AUTH periodically fall back to
            // the 10s dedup instead of re-fetching the full list on every challenge.
            if (normalized !in authedGroupListFetchedRelays) {
                groupManager.invalidateFullGroupListFetch(relayUrl)
            }
            // Bypass the 10s dedup when THIS SESSION hasn't received an EOSE for
            // the full group list yet. The previous REQ likely raced AUTH and was
            // CLOSED auth-required, so skipping the retry leaves OTHER GROUPS
            // permanently empty until the user switches relays or restarts. We
            // deliberately read the in-memory set (not hasFullGroupListBeenFetched)
            // so a fresh re-login doesn't get fooled by the still-fresh persisted
            // cache from a previous session; that cache predates the auth-required
            // CLOSED on this socket.
            val sessionFetched = normalized in groupManager.fullGroupListFetchedRelays.value
            if (!sessionFetched || now - lastAt > 10L) {
                lastRequestGroupsAt[relayUrl] = now
                authedGroupListFetchedRelays.add(normalized)
                groupManager.markRelayLoading(relayUrl)
                requestGroupsForRelay(client, relayUrl)
            }
            drainFullFetchRequest(client, relayUrl)
        }

        // Reset loading states for opened + auth-closed groups.
        // Uses resetLoadingForGroups (NOT handleConnectionLostForGroups) to avoid
        // clearing the mux tracker — resubscribeAllGroups already sent the mux refresh,
        // so the tracker correctly reflects the current state.
        val openedOnRelay = groupManager.getOpenedGroupIds()
            .filter { groupManager.getRelayForGroup(it) == relayUrl }
        val closedOnRelay = _closedGroupSubscriptions.value.filter {
            groupManager.getRelayForGroup(it) == relayUrl
        }
        if (closedOnRelay.isNotEmpty()) {
            val drop = closedOnRelay.toSet()
            _closedGroupSubscriptions.update { it - drop }
        }

        // Include joined groups from kind 10009 — essential for private groups
        // that don't appear in the general kind 39000 listing.
        val joinedOnRelay = groupManager.getGroupIdsForMux(relayUrl)

        val groupIds = (openedOnRelay + closedOnRelay + joinedOnRelay).distinct()
        // Re-load history ONLY for groups that have NO messages yet (their initial read was
        // CLOSED auth-required / never landed). Re-loading a group that already has its
        // messages on a periodic AUTH re-challenge (0xchat issues one every ~75s) reset the
        // active group's loading controller — wiping its HasMore state so scroll-up could no
        // longer paginate, showing only the first page — and fired an all-joined-groups msg
        // storm that hammered the relay. The live mux (refreshed below) keeps already-loaded
        // groups current; their in-memory history and pagination cursor stay untouched.
        val needHistory = groupIds.filter { groupManager.messages.value[it].isNullOrEmpty() }
        if (needHistory.isNotEmpty()) {
            // Cursor-preserving, though these are empty groups (page 0) so they reset to Idle
            // and the loop below re-inits them; a group that somehow has a live cursor is kept.
            groupManager.resetLoadingForGroupsPreservingCursor(needHistory)
        }

        // Force-clear the mux tracker so the refresh always re-sends subscriptions.
        // The relay may have dropped active subs when it sent the AUTH challenge
        // (e.g. communities.nos.social re-challenges AUTH periodically).
        groupManager.clearMuxTrackerForRelay(relayUrl)
        groupManager.refreshMuxSubscriptionsForRelay(relayUrl)
        // Messages that fired during the pre-AUTH dead window are behind the advanced
        // live cursor — the mux replay above never revisits them. Forward-fill the
        // recent window for already-loaded groups (dedup absorbs the overlap).
        groupManager.backfillRecentGapsForRelay(relayUrl)

        // Request metadata/members/admins for private groups that are not in the
        // group cache. The relay hides these from the general listing but returns
        // them on targeted #d requests after AUTH.
        groupManager.requestPrivateGroupData(relayUrl)
        // Also fetch metadata for the active group if the user navigated via URL
        // and the group isn't in the cache yet (e.g. invite link to a private group).
        groupManager.requestActiveGroupMetadataIfMissing(relayUrl)
        // Timing-robust net: after AUTH the batched mux/meta REQ may still have lost the race
        // (JVM engine), so per-group fetch any joined group whose 39000 never landed.
        scheduleMissingMetadataSweep(relayUrl)

        // Re-request historical messages for groups that were CLOSED with
        // auth-required. The mux only delivers live messages (since cursor),
        // so without this the chat stays empty after AUTH completes.
        // AUTH just succeeded: clear any "restricted" marker (from a pre-AUTH CLOSED or a
        // persisted one) on every group so the chat unblocks instead of staying stuck on the
        // "Private group" placeholder. If the relay still denies a group post-AUTH, the fresh
        // CLOSED re-marks it.
        for (groupId in groupIds) {
            groupManager.clearGroupRestricted(relayUrl, groupId)
        }
        // Re-request history ONLY for the empty groups (see needHistory above). An already
        // -loaded group keeps its messages + pagination state; requesting it here would reset
        // its controller and break scroll-back on every periodic AUTH re-challenge.
        for (groupId in needHistory) {
            groupManager.requestGroupMessages(groupId)
        }
        // Re-fire forum thread subscriptions: a private group's pre-AUTH thread REQ was CLOSED
        // auth-required, and the threads pane has no mux to refresh it post-AUTH.
        groupManager.resubscribeOpenThreads(relayUrl)
    }
}

// Helper function for parsing bunker URLs
data class BunkerInfo(
    val pubkey: String,
    val relays: List<String>,
    val secret: String?,
)

fun parseBunkerUrl(url: String): BunkerInfo {
    val trimmed = url.trim()

    require(trimmed.startsWith("bunker://")) {
        "Invalid bunker URL: must start with bunker://"
    }

    val withoutScheme = trimmed.removePrefix("bunker://")
    val parts = withoutScheme.split("?", limit = 2)

    val pubkey = parts[0]
    require(pubkey.length == 64 && pubkey.all { it in '0'..'9' || it in 'a'..'f' }) {
        "Invalid pubkey in bunker URL"
    }

    val relays = mutableListOf<String>()
    var secret: String? = null

    if (parts.size > 1) {
        val queryParams = parts[1].split("&")
        for (param in queryParams) {
            val kv = param.split("=", limit = 2)
            if (kv.size == 2) {
                val key = kv[0]
                val value = kv[1].urlDecode()
                when (key) {
                    "relay" -> relays.add(value)
                    "secret" -> secret = value
                }
            }
        }
    }

    require(relays.isNotEmpty()) {
        "Bunker URL must contain at least one relay"
    }

    // Some signer apps (and some old saved URLs) repeat a relay= param, or list
    // the same relay with/without a trailing slash. Dedupe here — the single
    // source of every downstream Nip46Client relay connection — so a repeated
    // param doesn't open (and keep) two sockets to the same relay for the life
    // of the session, each independently receiving every published request.
    return BunkerInfo(pubkey, relays.map { it.trimEnd('/') }.distinct(), secret)
}
