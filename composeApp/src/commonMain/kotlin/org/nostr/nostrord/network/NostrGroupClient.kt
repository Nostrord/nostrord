package org.nostr.nostrord.network

import androidx.compose.runtime.Immutable
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import org.nostr.nostrord.utils.epochMillis

@Serializable
@Immutable
data class GroupMetadata(
    val id: String,
    val name: String?,
    val about: String?,
    val picture: String?,
    val isPublic: Boolean,
    val isOpen: Boolean,
    /** NIP-29 `restricted`: only members can write. Absence = anyone can post. */
    val isRestricted: Boolean = false,
    /** NIP-29 `hidden`: relays hide metadata from non-members (not discoverable). */
    val isHidden: Boolean = false,
    /** Declared parent group id (from kind:39000 `parent` tag). Null for root groups. */
    val parent: String? = null,
    /**
     * Child group ids from the `["child", "<id>"]` tags in this group's kind:39000,
     * in tag order (sibling display order). The relay keeps parent/child bilateral
     * and validated, so a listed child is authoritative.
     */
    val children: List<String> = emptyList(),
) {
    companion object
}

/** Explicit serializer — required on Kotlin/Wasm where runtime serializer lookup fails for user classes. */
val groupMetadataListSerializer: KSerializer<List<GroupMetadata>> = ListSerializer(GroupMetadata.serializer())

/**
 * Group members list from kind 39002 event.
 * Contains all pubkeys that are members of the group.
 */
@Immutable
data class GroupMembers(
    val groupId: String,
    val members: List<String>, // List of pubkeys
)

/**
 * Group admins list from kind 39001 event.
 * Contains pubkeys of group administrators.
 */
@Immutable
data class GroupAdmins(
    val groupId: String,
    val admins: List<String>, // List of admin pubkeys
)

/**
 * Group roles from kind 39003 event.
 * Contains the roles supported by the relay for a specific group,
 * along with each role's permissions.
 */
@Immutable
data class GroupRoles(
    val groupId: String,
    val roles: List<RoleDefinition>,
)

@Serializable
@Immutable
data class RoleDefinition(
    val name: String,
    val description: String = "",
)

@Serializable
@Immutable
data class UserMetadata(
    val pubkey: String,
    val name: String?,
    val displayName: String?,
    val picture: String?,
    val about: String?,
    val nip05: String?,
    val banner: String? = null,
    val lud16: String? = null,
    /** LNURL-pay bech32 (`lnurl1...`) — NIP-57 fallback when lud16 is absent. */
    val lud06: String? = null,
    val website: String? = null,
    /** Original kind:0 content JSON — preserved so updates can merge without losing unknown fields. */
    val rawContentJson: String? = null,
)

data class CachedEvent(
    val id: String,
    val pubkey: String,
    val kind: Int,
    val content: String,
    val createdAt: Long,
    val tags: List<List<String>>,
)

/**
 * Result of publishing an event to a relay.
 * Represents the relay's OK response per NIP-01.
 */
sealed class PublishResult {
    /** Event accepted by relay */
    data class Success(val eventId: String, val message: String?) : PublishResult()

    /** Event rejected by relay */
    data class Rejected(val eventId: String, val reason: String) : PublishResult()

    /** Timeout waiting for OK response */
    data class Timeout(val eventId: String) : PublishResult()

    /** Error during publish (network, etc.) */
    data class Error(val eventId: String, val exception: Exception) : PublishResult()
}

/**
 * Aggregate a batch of publish results into a single diagnostic string of the
 * form `rejected=N timeout=N error=N first=<reason>`. Used by callers that
 * fan out an event to multiple relays and need to surface why none accepted.
 */
fun List<PublishResult>.summarizeFailures(): String {
    var rejected = 0
    var timeout = 0
    var errors = 0
    var firstReason: String? = null
    for (r in this) {
        when (r) {
            is PublishResult.Rejected -> {
                rejected++
                if (firstReason == null) firstReason = r.reason
            }
            is PublishResult.Timeout -> {
                timeout++
                if (firstReason == null) firstReason = "timeout"
            }
            is PublishResult.Error -> {
                errors++
                if (firstReason == null) firstReason = r.exception.message
            }
            is PublishResult.Success -> {}
        }
    }
    return "rejected=$rejected timeout=$timeout error=$errors first=${firstReason ?: "unknown"}"
}

/**
 * Publish [eventJson] and return the relay's verdict as a [PublishResult],
 * converting non-cancellation exceptions to [PublishResult.Error] so callers
 * can collect results from many relays in parallel via `awaitAll`.
 */
suspend fun NostrGroupClient.sendAndAwaitOkOrError(eventJson: String, eventId: String): PublishResult = try {
    sendAndAwaitOk(eventJson, eventId)
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    PublishResult.Error(eventId, e)
}

class NostrGroupClient(
    private val relayUrl: String = "wss://groups.fiatjaf.com",
) {
    // Managed coroutine scope for this client - cancelled on disconnect
    // networkClientDispatcher, NOT Default: concurrent ws handshakes park their thread
    // in Ktor's generateNonce runBlocking and can deadlock the whole Default pool
    // (see utils/NetworkDispatcher.kt).
    private val clientScope = CoroutineScope(SupervisorJob() + org.nostr.nostrord.utils.networkClientDispatcher)

    private val client = createHttpClient()
    private var session: DefaultClientWebSocketSession? = null
    private val json = Json { ignoreUnknownKeys = true }
    private var connectionJob: Job? = null
    private val connectionResult = CompletableDeferred<Boolean>()

    // Signalled after the relay's NIP-42 AUTH challenge has been answered.
    // connect()/switchRelay() await this so REQs are not sent before auth.
    private var authCompleted = CompletableDeferred<Unit>()

    // NIP-42 AUTH callback - set this to handle AUTH challenges
    var onAuthChallenge: ((challenge: String) -> Unit)? = null

    // Connection lost callback - notifies when WebSocket disconnects unexpectedly
    var onConnectionLost: (() -> Unit)? = null

    // OK response callback - notifies when relay sends OK for published event
    var onOkResponse: ((eventId: String, success: Boolean, message: String?) -> Unit)? = null

    // Pending OK responses - maps event ID to CompletableDeferred for awaiting
    private val pendingOkResponses = mutableMapOf<String, CompletableDeferred<PublishResult>>()
    private val pendingOkMutex = Mutex()

    // Pending group creation confirmations - maps subscription ID to deferred GroupMetadata
    private val pendingGroupCreation = mutableMapOf<String, CompletableDeferred<GroupMetadata>>()
    private val pendingGroupCreationMutex = Mutex()

    // Track if this was a graceful disconnect
    private var isDisconnecting = false

    // Epoch ms of the last frame received from the relay. A socket that stays
    // silent while an OK is awaited (or across a whole mux stale window) is a
    // zombie: mobile networks kill TCP without a close frame, writes buffer
    // locally without throwing, and the engine ping can be deferred under Doze.
    // Zombies must be torn down via markDead() — nothing else ever detects them.
    @kotlin.concurrent.Volatile
    private var lastInboundAtMs: Long = 0L

    // Open subscription IDs tracked for diagnostic logging.
    // Updated in send() on every REQ/CLOSE. Not synchronised — count may be off by ±1
    // under concurrent sends, which is acceptable for logging purposes.
    private val openSubscriptions = mutableSetOf<String>()

    // Last unfiltered kind:39000 group-list REQ timestamp, used to coalesce
    // back-to-back calls from concurrent code paths (e.g. resubscribeAfterAuth
    // + drainFullFetchRequest landing on the same socket within 1 ms). Without
    // this guard the relay sees the same {kinds:[39000]} filter three times on
    // one socket on cold start — see analyze_har.py output for the symptom.
    private var lastUnfilteredGroupListAtMs: Long = 0L

    companion object {
        /** Probe interval for browser targets without engine ws ping/pong. */
        private const val BROWSER_PROBE_INTERVAL_MS = 30_000L

        /**
         * Min gap between two unfiltered kind:39000 REQs on the same socket.
         * Sized to catch back-to-back duplicates fired by concurrent callers
         * (connect() vs resubscribeAfterAuth's drainFullFetchRequest land within
         * a single millisecond) without blocking the legitimate post-AUTH
         * refetch, which arrives only after handleAuthChallenge's 500 ms wait.
         */
        private const val GROUP_LIST_DEDUP_WINDOW_MS = 200L

        /** Upper bound on the graceful ws close handshake in disconnect(). */
        private const val CLOSE_HANDSHAKE_TIMEOUT_MS = 2_000L
    }

    fun getRelayUrl(): String = relayUrl

    /**
     * Check if the client is currently connected
     */
    fun isConnected(): Boolean = session != null && connectionJob?.isActive == true

    /** How long the relay has been completely silent (no frames of any kind), or null if not connected. */
    fun inboundSilenceMs(nowMs: Long = epochMillis()): Long? = if (isConnected()) nowMs - lastInboundAtMs else null

    /**
     * Tear down a socket believed to be a zombie (writes buffer, nothing inbound).
     * Cancelling the frame loop makes its finally run the normal lost-connection
     * path: session is cleared, onConnectionLost fires, and the reconnect
     * scheduler rebuilds the socket + resubscribes + flushes pending events.
     */
    fun markDead() {
        if (isDisconnecting) return
        connectionJob?.cancel()
    }

    @kotlin.concurrent.Volatile
    private var probeInFlight = false

    /**
     * Actively verify the socket is alive. Sends a REQ any relay must answer
     * (EOSE, or CLOSED on auth-gated relays — any inbound frame counts) and
     * waits for inbound traffic; total silence means zombie → [markDead].
     * Cheap on a live relay (one REQ+CLOSE round trip). Needed because engine
     * pongs never reach the frame loop, so inbound silence alone cannot
     * distinguish a quiet-but-alive relay from a dead socket.
     */
    suspend fun probeLiveness(timeoutMs: Long = 5_000) {
        if (!isConnected() || probeInFlight) return
        probeInFlight = true
        try {
            val sentAtMs = epochMillis()
            try {
                session?.send(Frame.Text("""["REQ","_probe",{"ids":["${"0".repeat(64)}"],"limit":1}]"""))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                markDead()
                return
            }
            while (epochMillis() - sentAtMs < timeoutMs) {
                delay(250)
                if (lastInboundAtMs >= sentAtMs) {
                    try {
                        session?.send(Frame.Text("""["CLOSE","_probe"]"""))
                    } catch (_: Exception) {}
                    return
                }
            }
            markDead()
        } finally {
            probeInFlight = false
        }
    }

    /**
     * Parse NIP-42 AUTH challenge from relay
     * Returns the challenge string if this is an AUTH message, null otherwise
     */
    fun parseAuthChallenge(message: String): String? = try {
        parseAuthChallenge(json.parseToJsonElement(message).jsonArray)
    } catch (e: Exception) {
        null
    }

    fun parseAuthChallenge(arr: JsonArray): String? = if (arr.size >= 2 && arr[0].jsonPrimitive.content == "AUTH") {
        arr[1].jsonPrimitive.content
    } else {
        null
    }

    suspend fun connect(onMessage: (String) -> Unit) {
        isDisconnecting = false
        authCompleted = CompletableDeferred()
        connectionJob = clientScope.launch {
            try {
                client.webSocket(relayUrl) {
                    session = this
                    lastInboundAtMs = epochMillis()

                    // Signal that connection is ready
                    connectionResult.complete(true)

                    // Liveness detection is delegated to the engine on platforms
                    // that perform ws-level ping/pong (JVM/Android via Ktor,
                    // iOS via NSURLSession). The frame loop exits naturally
                    // when the engine closes a dead socket.
                    //
                    // Browser engines hide ping/pong frames from JS code, so on
                    // those targets we send a periodic CLOSE for an unknown sub
                    // id. By NIP-01 the relay silently ignores it, so this is a
                    // pure write-probe — the send() call surfaces a dead socket
                    // through the catch below, without making the relay scan its
                    // kind:0 index or leaking random metadata events into the
                    // pipeline (which the old REQ+CLOSE form did).
                    val wsSession = this
                    if (!hasEngineWebSocketPing()) {
                        launch {
                            while (isActive) {
                                delay(BROWSER_PROBE_INTERVAL_MS)
                                try {
                                    wsSession.send(Frame.Text("""["CLOSE","_hb"]"""))
                                } catch (_: Exception) {
                                    // Send failed — socket is gone, frame loop
                                    // below will exit and trigger reconnect.
                                    break
                                }
                            }
                        }
                    }

                    // Listen to incoming messages
                    for (frame in incoming) {
                        lastInboundAtMs = epochMillis()
                        when (frame) {
                            is Frame.Text -> onMessage(frame.readText())
                            is Frame.Close -> {} // CLOSED event below captures code + reason
                            else -> {} // ping/pong handled by Ktor
                        }
                    }
                    closeReason.await()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Signal failure immediately so waitForConnection() returns without
                // burning the full 7-second timeout on unreachable relays.
                connectionResult.complete(false)
            } finally {
                val wasConnected = session != null
                session = null
                connectionResult.complete(false) // no-op if already completed
                if (wasConnected && !isDisconnecting) {
                    onConnectionLost?.invoke()
                }
            }
        }
    }

    suspend fun waitForConnection(timeoutMs: Long = 7_000): Boolean = withTimeoutOrNull(timeoutMs) {
        connectionResult.await()
    } ?: false

    /**
     * Has this socket already signed and sent an AUTH response that the relay
     * accepted (or replied to)? Used by the bunker-ready watcher to skip a
     * reconnect when AUTH actually succeeded during the verifying window — a
     * full bunker round-trip can complete signing before [_isBunkerVerifying]
     * flips to false, so the assumption "verifying=true ⇒ AUTH was unsigned"
     * is wrong for slow signers and produces a needless socket churn.
     */
    fun hasAuthSucceeded(): Boolean = authCompleted.isCompleted

    // True once this relay has ever sent a NIP-42 AUTH challenge — i.e. it gates
    // reads behind auth (private/closed groups). Persists across reconnects (a
    // relay that required auth once always will), so pagination can decide whether
    // it must await re-AUTH before firing a REQ. Public relays never set this.
    private var authChallengeSeen = false

    // Completes the first time this socket sees an AUTH challenge. Lets awaitAuthSigned wait
    // for "the relay asked for auth" separately from "the (possibly remote/slow) sign
    // finished", so a long sign budget is only spent on relays that actually gate reads.
    private val authChallengeArrived = CompletableDeferred<Unit>()

    fun markAuthChallengeSeen() {
        authChallengeSeen = true
        authChallengeArrived.complete(Unit)
    }

    fun requiresAuth(): Boolean = authChallengeSeen

    /**
     * Wait for the relay's NIP-42 AUTH challenge to be answered.
     * Returns true if auth completed, false if the relay didn't send a challenge
     * within [timeoutMs] (meaning it likely doesn't require auth).
     */
    suspend fun awaitAuthOrTimeout(timeoutMs: Long = 2_000): Boolean = withTimeoutOrNull(timeoutMs) {
        authCompleted.await()
        true
    } ?: false

    /**
     * Two-phase AUTH wait tuned for slow remote (bunker) signers. First wait
     * [challengeGraceMs] for the relay to issue a challenge — if none arrives the relay
     * does not gate reads, so return false fast (no stall on public relays). Once a
     * challenge is (or was already) seen, give the signer up to [signBudgetMs] to finish
     * signing + sending the AUTH response. This stops a 2s budget from racing a bunker
     * round-trip and firing the first private-group REQ before AUTH lands.
     */
    suspend fun awaitAuthSigned(signBudgetMs: Long, challengeGraceMs: Long = 2_000): Boolean {
        val gated = authChallengeSeen ||
            withTimeoutOrNull(challengeGraceMs) {
                authChallengeArrived.await()
                true
            } == true
        if (!gated) return false
        return withTimeoutOrNull(signBudgetMs) {
            authCompleted.await()
            true
        } ?: false
    }

    /** Called after the AUTH response has been sent to the relay. */
    fun notifyAuthCompleted() {
        authCompleted.complete(Unit)
    }

    suspend fun send(message: String) {
        trackSubLifecycle(message)
        val currentSession = session
            ?: throw IllegalStateException("Not connected to $relayUrl")
        try {
            currentSession.send(Frame.Text(message))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // WebSocket channel died — clear stale session so reconnect logic kicks in
            session = null
            throw e
        }
    }

    /**
     * Tracks open/closed subscriptions for slot-count diagnostics.
     * No per-sub logging here — mux subs are logged at the call site in sendMuxSubscriptions.
     */
    private fun trackSubLifecycle(message: String) {
        try {
            if (!message.startsWith("""["REQ"""") && !message.startsWith("""["CLOSE"""")) return
            val arr = json.parseToJsonElement(message).jsonArray
            val subId = arr[1].jsonPrimitive.content
            when (arr[0].jsonPrimitive.content) {
                "REQ" -> openSubscriptions.add(subId)
                "CLOSE" -> openSubscriptions.remove(subId)
            }
        } catch (_: Exception) {}
    }

    /**
     * Send an EVENT message and wait for OK response from relay.
     * @param eventJson The full EVENT message JSON (["EVENT", {...}])
     * @param eventId The event ID to track for OK response
     * @param timeoutMs Timeout in milliseconds to wait for OK
     * @return PublishResult indicating success, rejection, timeout, or error
     */
    suspend fun sendAndAwaitOk(
        eventJson: String,
        eventId: String,
        timeoutMs: Long = 10_000,
    ): PublishResult {
        val deferred = CompletableDeferred<PublishResult>()
        val sentAtMs = epochMillis()

        // Register pending response
        pendingOkMutex.withLock {
            pendingOkResponses[eventId] = deferred
        }

        return try {
            // Send the event
            val currentSession = session ?: run {
                pendingOkMutex.withLock { pendingOkResponses.remove(eventId) }
                return PublishResult.Error(eventId, Exception("Not connected"))
            }
            currentSession.send(Frame.Text(eventJson))

            // Wait for OK with timeout
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            pendingOkMutex.withLock { pendingOkResponses.remove(eventId) }
            // No OK and not a single frame of any kind while we waited: the socket
            // is a zombie, not a slow relay. Tear it down so the reconnect path
            // (resubscribe + pending-event flush) takes over; otherwise the write
            // keeps buffering into a dead pipe and the message stays Sending forever.
            if (lastInboundAtMs < sentAtMs) {
                markDead()
            }
            PublishResult.Timeout(eventId)
        } catch (e: Exception) {
            pendingOkMutex.withLock { pendingOkResponses.remove(eventId) }
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Channel cancelled = WebSocket dropped; clear stale session so
            // isConnected() returns false and reconnect logic can kick in.
            val msg = e.message ?: ""
            if (msg.contains("Channel was cancelled", ignoreCase = true) ||
                msg.contains("closed", ignoreCase = true)
            ) {
                session = null
            }
            PublishResult.Error(eventId, e)
        }
    }

    /**
     * Parse an OK message from the relay.
     * Format: ["OK", <event_id>, <success>, <message>]
     * @return Triple of (eventId, success, message) or null if not an OK message
     */
    fun parseOkMessage(message: String): Triple<String, Boolean, String?>? = try {
        parseOkMessage(json.parseToJsonElement(message).jsonArray)
    } catch (e: Exception) {
        null
    }

    fun parseOkMessage(arr: JsonArray): Triple<String, Boolean, String?>? = if (arr.size >= 3 && arr[0].jsonPrimitive.content == "OK") {
        val eventId = arr[1].jsonPrimitive.content
        val success = arr[2].jsonPrimitive.boolean
        val okMessage = arr.getOrNull(3)?.jsonPrimitive?.contentOrNull
        Triple(eventId, success, okMessage)
    } else {
        null
    }

    /**
     * Handle an OK response from the relay.
     * This should be called when an OK message is received.
     * Completes any pending deferred for this event ID.
     */
    suspend fun handleOkResponse(eventId: String, success: Boolean, message: String?) {
        onOkResponse?.invoke(eventId, success, message)

        // Complete pending deferred
        val deferred = pendingOkMutex.withLock {
            pendingOkResponses.remove(eventId)
        }

        if (deferred != null) {
            val result = if (success) {
                PublishResult.Success(eventId, message)
            } else {
                PublishResult.Rejected(eventId, message ?: "Rejected by relay")
            }
            deferred.complete(result)
        }
    }

    /**
     * Parse a kind:39000 message and return (subscriptionId, GroupMetadata).
     * Used so the subscription ID is available alongside the metadata.
     */
    fun parseGroupMetadataWithSubId(message: String): Pair<String, GroupMetadata>? {
        return try {
            val arr = json.parseToJsonElement(message).jsonArray
            if (arr.size < 3 || arr[0].jsonPrimitive.content != "EVENT") return null
            val subId = arr[1].jsonPrimitive.content
            val metadata = parseGroupMetadata(arr[2].jsonObject) ?: return null
            Pair(subId, metadata)
        } catch (e: Exception) {
            null
        }
    }

    fun parseGroupMetadataWithSubId(arr: JsonArray): Pair<String, GroupMetadata>? {
        return try {
            if (arr.size < 3 || arr[0].jsonPrimitive.content != "EVENT") return null
            val subId = arr[1].jsonPrimitive.content
            val metadata = parseGroupMetadata(arr[2].jsonObject) ?: return null
            Pair(subId, metadata)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Complete a pending group creation deferred when kind:39000 arrives
     * for the matching subscription ID.
     */
    suspend fun handleGroupCreationEvent(subId: String, metadata: GroupMetadata) {
        val deferred = pendingGroupCreationMutex.withLock {
            pendingGroupCreation[subId]
        }
        deferred?.complete(metadata)
    }

    /**
     * Send kind:9007 (create-group), await relay OK, then subscribe for kind:39000
     * to discover the relay-confirmed group ID before returning.
     *
     * @param create9007EventJson Full ["EVENT", {...}] JSON for the 9007 event
     * @param create9007EventId   The 9007 event ID (for tracking the OK response)
     * @param suggestedGroupId    The `d` tag value we suggested in the 9007 event
     * @param timeoutMs           Total timeout split: half for OK, half for 39000
     * @return The relay-confirmed group ID, or [suggestedGroupId] as fallback
     */
    suspend fun awaitGroupCreated(
        create9007EventJson: String,
        create9007EventId: String,
        suggestedGroupId: String,
        timeoutMs: Long = 15_000,
    ): String {
        val halfTimeout = timeoutMs / 2

        // Step 1: send 9007 and wait for relay OK
        val okResult = sendAndAwaitOk(create9007EventJson, create9007EventId, halfTimeout)
        when (okResult) {
            is PublishResult.Rejected -> throw Exception("Group creation rejected: ${okResult.reason}")
            is PublishResult.Timeout -> throw Exception("Relay did not respond in time. Try again.")
            is PublishResult.Error -> throw Exception("Relay error: ${okResult.exception.message}")
            else -> Unit // Success — continue
        }

        // Step 2: subscribe for kind:39000 with #d = suggestedGroupId
        val subId = "gc_${epochMillis()}"
        val deferred = CompletableDeferred<GroupMetadata>()
        pendingGroupCreationMutex.withLock {
            pendingGroupCreation[subId] = deferred
        }

        return try {
            send(
                buildJsonArray {
                    add("REQ")
                    add(subId)
                    add(
                        buildJsonObject {
                            putJsonArray("kinds") { add(39000) }
                            putJsonArray("#d") { add(suggestedGroupId) }
                        },
                    )
                }.toString(),
            )

            // Step 3: wait for the 39000 event, fall back to suggestedGroupId on timeout
            val confirmedId = withTimeoutOrNull(halfTimeout) {
                deferred.await().id
            }

            // Close the temporary subscription
            send(
                buildJsonArray {
                    add("CLOSE")
                    add(subId)
                }.toString(),
            )

            confirmedId ?: suggestedGroupId
        } finally {
            pendingGroupCreationMutex.withLock {
                pendingGroupCreation.remove(subId)
            }
        }
    }

    /**
     * Clear all pending OK responses (called on disconnect).
     */
    private suspend fun clearPendingOkResponses() {
        pendingOkMutex.withLock {
            pendingOkResponses.values.forEach { deferred ->
                deferred.complete(PublishResult.Error("", Exception("Disconnected")))
            }
            pendingOkResponses.clear()
        }
    }

    suspend fun requestGroups() {
        // Coalesce duplicate unfiltered REQs from concurrent callers. Without
        // this, connect() + resubscribeAfterAuth + drainFullFetchRequest can
        // each fire on the same socket inside a single millisecond, sending
        // the same {kinds:[39000]} filter three times — the relay then streams
        // the full group directory two extra times (>3000 events of pure
        // overhead on cold start).
        val now = epochMillis()
        if (now - lastUnfilteredGroupListAtMs < GROUP_LIST_DEDUP_WINDOW_MS) return
        lastUnfilteredGroupListAtMs = now

        // Use a relay-specific subscription ID to avoid collisions when multiple
        // relay clients are active concurrently. The ID must be stable per relay
        // so the EOSE handler can map it back to the originating relay URL.
        val subId = "group-list-${relayUrl.hashCode().toUInt()}"
        // CLOSE the sub id before re-REQ'ing. requestGroupsForIds() reuses this
        // same id for the lazy joined-only (#d-filtered) fetch; sending an
        // unfiltered REQ on a sub that is still open with a narrower filter is a
        // re-REQ that some relays do not re-evaluate — they keep serving the old
        // (#d) filter or treat it as already-EOSEd, so the OTHER GROUPS full
        // fetch comes back empty (observed on hzrd149's relay). An explicit CLOSE
        // guarantees the relay runs the widened filter as a fresh subscription.
        sendJson(
            buildJsonArray {
                add("CLOSE")
                add(subId)
            },
        )
        val req = buildJsonArray {
            add("REQ")
            add(subId)
            add(
                buildJsonObject {
                    putJsonArray("kinds") { add(39000) }
                },
            )
        }
        sendJson(req)
    }

    /**
     * Returns the subscription ID that requestGroups() uses for this relay.
     * Used by callers (e.g., EOSE handler) to identify the originating relay.
     */
    fun groupListSubscriptionId(): String = "group-list-${relayUrl.hashCode().toUInt()}"

    /**
     * Fetch kind:39000 metadata only for the given group IDs.
     * Used in LAZY fetch mode: on connect we only pull metadata for joined groups,
     * deferring the full list until the user opens "OTHER GROUPS".
     * Reuses the same sub ID as requestGroups() so the EOSE handler works unchanged.
     */
    suspend fun requestGroupsForIds(groupIds: List<String>) {
        if (groupIds.isEmpty()) return
        val subId = groupListSubscriptionId()
        // CLOSE before re-REQ — see requestGroups(). This sub id is shared with
        // the unfiltered full fetch; closing first guarantees the relay applies
        // this #d filter as a fresh subscription instead of mishandling a re-REQ.
        sendJson(
            buildJsonArray {
                add("CLOSE")
                add(subId)
            },
        )
        val req = buildJsonArray {
            add("REQ")
            add(subId)
            add(
                buildJsonObject {
                    putJsonArray("kinds") { add(39000) }
                    putJsonArray("#d") { groupIds.forEach { add(it) } }
                },
            )
        }
        sendJson(req)
    }

    /**
     * One-shot fetch of the put-user event that added [pubkey] to [groupId] — the actor
     * enrichment for a 39002-inferred pending invite. Bare-#p kind:9000 queries are blocked
     * on 0xchat-style relays, but #p + #h passes their h/e/a rule. Closed after EOSE by
     * closeOneShotSubAfterEose (padd_one_ prefix).
     */
    suspend fun requestSelfPutUser(groupId: String, pubkey: String): String {
        val subId = "padd_one_${groupId.take(8)}"
        trySendClose(subId)
        val req = buildJsonArray {
            add("REQ")
            add(subId)
            add(
                buildJsonObject {
                    putJsonArray("kinds") { add(9000) }
                    put("#h", buildJsonArray { add(groupId) })
                    put("#p", buildJsonArray { add(pubkey) })
                    put("limit", 1)
                },
            )
        }
        sendJson(req)
        return subId
    }

    /**
     * Subscribe for kind:39000 metadata for a specific group.
     * Called when entering a group screen to ensure metadata is fresh.
     *
     * Uses a short deterministic sub ID (same pattern as admins/members) so repeated
     * calls reuse the same relay slot instead of leaking a new subscription each time.
     * Sends CLOSE before REQ so the relay resets and re-delivers the latest state.
     * Returns the sub ID so the caller can send CLOSE after EOSE.
     */
    suspend fun requestGroupMetadata(groupId: String): String {
        val subId = "meta_${groupId.take(8)}"
        trySendClose(subId)
        val req = buildJsonArray {
            add("REQ")
            add(subId)
            add(
                buildJsonObject {
                    putJsonArray("kinds") { add(39000) }
                    put("#d", buildJsonArray { add(groupId) })
                },
            )
        }
        sendJson(req)
        return subId
    }

    // Group ids behind each one-shot batch REQ (meta_batch_ / members_batch_). relay29
    // validates a filter as a whole: ONE private group in the #d list gets the ENTIRE
    // batch CLOSED ("auth-required"/"restricted"), starving the public groups batched
    // with it. The CLOSED handler takes these ids and splits the batch into per-group
    // REQs. Copy-on-write + @Volatile: written from request coroutines, consumed on the
    // relay pipeline.
    @kotlin.concurrent.Volatile
    private var batchSubGroupIds: Map<String, List<String>> = emptyMap()

    /** One-shot: returns the batch's ids and forgets them, so one CLOSED splits once. */
    fun takeBatchGroupIds(subId: String): List<String>? {
        val ids = batchSubGroupIds[subId] ?: return null
        batchSubGroupIds = batchSubGroupIds - subId
        return ids
    }

    /**
     * Batched kind:39000 metadata fetch for several groups in one REQ (friends-group
     * discovery). Uses its own sub id so it never disturbs the relay's group-list
     * subscription, unlike [requestGroupsForIds].
     */
    suspend fun requestGroupsMetadata(groupIds: List<String>): String {
        val subId = "meta_batch_${groupIds.sorted().joinToString(",").hashCode().toUInt()}"
        batchSubGroupIds = batchSubGroupIds + (subId to groupIds)
        trySendClose(subId)
        val req = buildJsonArray {
            add("REQ")
            add(subId)
            add(
                buildJsonObject {
                    putJsonArray("kinds") { add(39000) }
                    putJsonArray("#d") { groupIds.forEach { add(it) } }
                },
            )
        }
        sendJson(req)
        return subId
    }

    /** One-shot REQ for a user's public NIP-29 group list (kind:10009). */
    suspend fun requestUserGroupList(pubkey: String): String {
        val subId = "ugroups_${pubkey.take(8)}"
        trySendClose(subId)
        val req = buildJsonArray {
            add("REQ")
            add(subId)
            add(
                buildJsonObject {
                    putJsonArray("kinds") { add(10009) }
                    putJsonArray("authors") { add(pubkey) }
                    put("limit", 1)
                },
            )
        }
        sendJson(req)
        return subId
    }

    /**
     * Batched kind:10009 fetch for several authors in one REQ (friends/recommended
     * discovery). Each returned event is routed by its own "pubkey" field, not the sub
     * id — the handler already reads it that way for the single-author REQ above — so
     * one shared sub id safely covers every author's list, same as [requestGroupsMetadata].
     * Kind:10009 is replaceable, so a well-behaved relay already returns at most one
     * event per author; [limit] is still set (to the author count) as a defensive cap.
     */
    suspend fun requestUserGroupLists(pubkeys: List<String>): String {
        val subId = "ugroups_batch_${pubkeys.sorted().joinToString(",").hashCode().toUInt()}"
        trySendClose(subId)
        val req = buildJsonArray {
            add("REQ")
            add(subId)
            add(
                buildJsonObject {
                    putJsonArray("kinds") { add(10009) }
                    putJsonArray("authors") { pubkeys.forEach { add(it) } }
                    put("limit", pubkeys.size)
                },
            )
        }
        sendJson(req)
        return subId
    }

    suspend fun requestContactList(pubkey: String): String {
        val subId = "contacts_${pubkey.take(8)}"
        trySendClose(subId)
        val req = buildJsonArray {
            add("REQ")
            add(subId)
            add(
                buildJsonObject {
                    putJsonArray("kinds") { add(3) }
                    putJsonArray("authors") { add(pubkey) }
                    put("limit", 1)
                },
            )
            // The NIP-51 mute list (kind:10000) rides the same REQ as its own filter
            // (a shared filter with limit 1 would return only the newer of the two
            // events), so the contact-list retry machinery covers it too.
            add(
                buildJsonObject {
                    putJsonArray("kinds") { add(10000) }
                    putJsonArray("authors") { add(pubkey) }
                    put("limit", 1)
                },
            )
        }
        sendJson(req)
        return subId
    }

    suspend fun requestGroupMessages(
        groupId: String,
        channel: String? = null,
        until: Long? = null,
        limit: Int = 50,
        subscriptionId: String? = null,
    ): String {
        val subId = subscriptionId ?: "msg_${epochMillis()}"

        // Reactions (kind 7) and zaps (kind 9321) are excluded from the paginated
        // subscription to preserve the per-group limit budget. They are fetched via
        // the mux_reactions sub and the live subscription instead.
        val subscription = buildJsonArray {
            add("REQ")
            add(subId)
            add(
                buildJsonObject {
                    put(
                        "kinds",
                        buildJsonArray {
                            add(5)
                            add(9)
                            add(9000)
                            add(9001)
                            add(9002)
                            add(9003)
                            add(9005)
                            add(9009)
                            add(9021)
                            add(9022)
                        },
                    )
                    put("#h", buildJsonArray { add(groupId) })
                    // Only filter by channel if it's NOT "general"
                    if (channel != null && channel != "general") {
                        put(
                            "#channel",
                            buildJsonArray {
                                add(channel)
                            },
                        )
                    }
                    // Pagination: fetch messages before this timestamp
                    if (until != null) {
                        put("until", until)
                    }
                    put("limit", limit)
                },
            )
        }.toString()

        send(subscription) // may throw — caller (GroupManager) catches and handles via state machine
        return subId
    }

    /**
     * Subscribe to a group's forum thread roots (kind:11). The subscription stays open after EOSE
     * so new threads stream in live; the caller CLOSEs it on leaving the threads pane. Sends
     * CLOSE before REQ so re-entry with the same id is idempotent.
     */
    suspend fun requestGroupThreadRoots(
        groupId: String,
        until: Long? = null,
        limit: Int = 50,
        subscriptionId: String,
    ): String {
        send(
            buildJsonArray {
                add("CLOSE")
                add(subscriptionId)
            }.toString(),
        )
        val req = buildJsonArray {
            add("REQ")
            add(subscriptionId)
            add(
                buildJsonObject {
                    putJsonArray("kinds") { add(11) } // NIP-29 forum thread root
                    put("#h", buildJsonArray { add(groupId) })
                    if (until != null) put("until", until)
                    put("limit", limit)
                },
            )
        }.toString()
        send(req)
        return subscriptionId
    }

    /**
     * Subscribe to NIP-22 replies (kind:1111) for a group's threads. With [rootId] null this is
     * the batched group-wide reply feed used to derive per-thread counts and previews; with a
     * [rootId] it focuses a single thread (#E = root). Stays open after EOSE for live updates;
     * sends CLOSE before REQ for idempotency.
     */
    suspend fun requestThreadReplies(
        groupId: String,
        rootId: String? = null,
        until: Long? = null,
        limit: Int = 500,
        subscriptionId: String,
    ): String {
        send(
            buildJsonArray {
                add("CLOSE")
                add(subscriptionId)
            }.toString(),
        )
        val req = buildJsonArray {
            add("REQ")
            add(subscriptionId)
            add(
                buildJsonObject {
                    putJsonArray("kinds") { add(1111) } // NIP-22 comment = thread reply
                    put("#h", buildJsonArray { add(groupId) })
                    // Match by the uppercase root-scope kind (K), present on every reply; the
                    // lowercase k (parent) is omitted on top-level replies to save indexable tags.
                    put("#K", buildJsonArray { add("11") })
                    if (rootId != null) put("#E", buildJsonArray { add(rootId) })
                    if (until != null) put("until", until)
                    put("limit", limit)
                },
            )
        }.toString()
        send(req)
        return subscriptionId
    }

    /** CLOSE a subscription by id (no-op on the relay if it is not open). */
    suspend fun closeSubscription(subscriptionId: String) {
        send(
            buildJsonArray {
                add("CLOSE")
                add(subscriptionId)
            }.toString(),
        )
    }

    /**
     * CLOSE every live subscription on this socket. Called on account switch so the previous
     * account's still-open mux chat/meta and dm_inbox REQs (none of which are pubkey-filtered)
     * stop delivering that account's events into the newly-installed account's state. The
     * socket stays connected; the new account re-subscribes. Snapshot first: send() mutates
     * openSubscriptions on each CLOSE.
     */
    suspend fun closeAllSubscriptions() {
        for (subId in openSubscriptions.toList()) {
            try {
                closeSubscription(subId)
            } catch (_: Exception) {}
        }
    }

/**
     * Fetch reactions (kind 7) for specific message IDs.
     * Chachi-style approach: after loading messages, request reactions by event ID
     * so they don't consume the paginated message limit.
     */
    suspend fun requestReactionsForMessages(messageIds: List<String>): String? {
        if (messageIds.isEmpty()) return null
        val subId = "reactions_${epochMillis()}"
        val subscription = buildJsonArray {
            add("REQ")
            add(subId)
            add(
                buildJsonObject {
                    put(
                        "kinds",
                        buildJsonArray {
                            add(7)
                            add(9321)
                            add(9735) // NIP-57 zap receipts
                        },
                    )
                    put("#e", buildJsonArray { messageIds.forEach { add(it) } })
                },
            )
        }.toString()
        send(subscription)
        return subId
    }

    /**
     * Fetch NIP-57 zap receipts (kind 9735) for specific event IDs. Used against
     * general-purpose relays: zap receipts carry no `h` tag, so NIP-29 group relays do
     * not serve them — they live on the relays named in the zap request's `relays` tag.
     */
    suspend fun requestZapReceipts(eventIds: List<String>): String? {
        if (eventIds.isEmpty()) return null
        val subId = "zaps_${epochMillis()}"
        val subscription = buildJsonArray {
            add("REQ")
            add(subId)
            add(
                buildJsonObject {
                    putJsonArray("kinds") { add(9735) }
                    put("#e", buildJsonArray { eventIds.forEach { add(it) } })
                },
            )
        }.toString()
        send(subscription)
        return subId
    }

    /**
     * Fetch recent zap receipts (kind 9735) addressed to [recipientPubkey] (`#p`). Used to
     * confirm a profile zap, which carries no zapped-event id.
     */
    suspend fun requestZapReceiptsForRecipient(recipientPubkey: String, limit: Int = 20): String? {
        val subId = "zaps_${epochMillis()}"
        val subscription = buildJsonArray {
            add("REQ")
            add(subId)
            add(
                buildJsonObject {
                    putJsonArray("kinds") { add(9735) }
                    put("#p", buildJsonArray { add(recipientPubkey) })
                    put("limit", limit)
                },
            )
        }.toString()
        send(subscription)
        return subId
    }

/**
     * Deterministic sub ID for the relay-level multiplexed chat subscription.
     * Using the relay URL hash makes it stable across reconnects so the relay doesn't
     * accumulate duplicate slots.
     */
    fun muxChatSubId(): String = "mux_chat_${relayUrl.hashCode().toUInt()}"

/**
     * Deterministic sub ID for the relay-level multiplexed reactions subscription.
     */
    fun muxReactionsSubId(): String = "mux_reactions_${relayUrl.hashCode().toUInt()}"

/**
     * Deterministic sub ID for the relay-level multiplexed group-metadata subscription.
     */
    fun muxMetaSubId(): String = "mux_meta_${relayUrl.hashCode().toUInt()}"

/**
     * Deterministic sub ID for the relay-level delete-group watch (kind:9008).
     */
    fun muxDeleteSubId(): String = "mux_del_${relayUrl.hashCode().toUInt()}"

/**
     * Deterministic sub ID for the relay-level put-user watch (kind:9000, #p = self).
     */
    fun muxPutUserSubId(): String = "mux_padd_${relayUrl.hashCode().toUInt()}"

/**
     * Send (or refresh) the three relay-level multiplexed subscriptions.
     *
     * Replaces per-group `live_<id>` + `reactions_<id>` with three relay-scoped REQs that cover
     * ALL joined/loaded groups on this relay simultaneously.  Benefits:
     *
     * - N×3 per-group subs → 3 per-relay subs regardless of group count
     * - A single reconnect re-subscribes everything with one round-trip
     * - `since = min(cursors)` ensures no group misses events during the offline window
     *
     * Idempotent: sends CLOSE before REQ so calling it multiple times is safe.
     *
     * @param metadataGroupIds Group IDs for the metadata mux (kind:39000 — all joined groups).
     * @param chatGroupIds     Group IDs for chat + reactions mux (opened groups).
     * @param chatSinceSeconds Unix-seconds `since` for chat/reactions (cursor for opened groups).
     * @param putUserPubkey    Self pubkey for the kind:9000 put-user watch (null when logged out).
     * @param putUserSinceSeconds Unix-seconds `since` for the put-user watch (persisted cursor).
     * @param deleteSinceSeconds Unix-seconds `since` for the kind:9008 delete watch (persisted
     *   cursor; 0 falls back to now-60 for the first ever arm on this relay).
     */
    suspend fun sendMuxSubscriptions(
        metadataGroupIds: List<String>,
        chatGroupIds: List<String>,
        chatSinceSeconds: Long,
        putUserPubkey: String? = null,
        putUserSinceSeconds: Long = 0L,
        deleteSinceSeconds: Long = 0L,
    ) {
        if (metadataGroupIds.isEmpty() && chatGroupIds.isEmpty() && putUserPubkey == null) return
        val chatSubId = muxChatSubId()
        val reactSubId = muxReactionsSubId()
        val metaSubId = muxMetaSubId()
        val delSubId = muxDeleteSubId()
        val putUserSubId = muxPutUserSubId()

        // Put-user watch: how we learn an admin added us to a group on this relay (kind:9000
        // with #p = us), live or as catch-up after an offline window — including groups we
        // never opened, which no other subscription covers. Kept independent of the group
        // batches so it stays armed on a relay where we have zero groups yet.
        //
        // Two filters, because 0xchat's relay gates STORED queries on having an h/e/a tag
        // (a bare-#p kind:9000 REQ is CLOSED "invalid query") while its LIVE broadcast does
        // plain filter matching:
        //  - kinds 9000+39002 with since: live adds on every relay (the 39002 alongside makes
        //    0xchat accept the REQ instead of closing it) + stored catch-up on relays that
        //    serve bare-#p queries.
        //  - kinds 39002 no-since: membership sweep at every (re)subscribe — the offline
        //    catch-up for relays like 0xchat, which never advance a 39002's created_at
        //    (pinned at group creation), making any `since` filter blind to adds there.
        send(
            buildJsonArray {
                add("CLOSE")
                add(putUserSubId)
            }.toString(),
        )
        if (putUserPubkey != null) {
            send(
                buildJsonArray {
                    add("REQ")
                    add(putUserSubId)
                    add(
                        buildJsonObject {
                            putJsonArray("kinds") {
                                add(9000)
                                add(39002)
                            }
                            putJsonArray("#p") { add(putUserPubkey) }
                            put("since", putUserSinceSeconds)
                        },
                    )
                    add(
                        buildJsonObject {
                            putJsonArray("kinds") { add(39002) }
                            putJsonArray("#p") { add(putUserPubkey) }
                        },
                    )
                }.toString(),
            )
        }

        // Close the previous mux slots first (idempotent — no-op if not open).
        send(
            buildJsonArray {
                add("CLOSE")
                add(chatSubId)
            }.toString(),
        )
        send(
            buildJsonArray {
                add("CLOSE")
                add(reactSubId)
            }.toString(),
        )
        send(
            buildJsonArray {
                add("CLOSE")
                add(metaSubId)
            }.toString(),
        )
        send(
            buildJsonArray {
                add("CLOSE")
                add(delSubId)
            }.toString(),
        )

        // Chat + admin events for opened groups.
        if (chatGroupIds.isNotEmpty()) {
            send(
                buildJsonArray {
                    add("REQ")
                    add(chatSubId)
                    add(
                        buildJsonObject {
                            putJsonArray("kinds") {
                                add(5)
                                add(9)
                                add(9000)
                                add(9001)
                                add(9002)
                                add(9003)
                                add(9005)
                                add(9009)
                                add(9021)
                                add(9022)
                            }
                            putJsonArray("#h") { chatGroupIds.forEach { add(it) } }
                            put("since", chatSinceSeconds)
                        },
                    )
                }.toString(),
            )

            // Reactions / zaps for opened groups.
            send(
                buildJsonArray {
                    add("REQ")
                    add(reactSubId)
                    add(
                        buildJsonObject {
                            putJsonArray("kinds") {
                                add(7)
                                add(9321)
                            }
                            putJsonArray("#h") { chatGroupIds.forEach { add(it) } }
                            put("since", chatSinceSeconds)
                        },
                    )
                }.toString(),
            )
        }

        // Group metadata + members + admins (kinds 39000, 39001, 39002) for all joined groups.
        // All three are addressable (replaceable), so the relay always returns the latest
        // state. No `since` filter — avoids massive backfill traffic on relays with many groups.
        // Including 39001/39002 here makes member/admin lists arrive reliably via the mux
        // instead of depending solely on individual per-group requests that can fail silently.
        if (metadataGroupIds.isNotEmpty()) {
            send(
                buildJsonArray {
                    add("REQ")
                    add(metaSubId)
                    add(
                        buildJsonObject {
                            putJsonArray("kinds") {
                                add(39000)
                                add(39001)
                                add(39002)
                            }
                            putJsonArray("#d") { metadataGroupIds.forEach { add(it) } }
                        },
                    )
                }.toString(),
            )

            // Delete-group watch for every group in the metadata mux. kind:9008 is the only
            // authoritative signal that a group was destroyed; without this, non-admin members
            // never find out and the stale metadata lingers in their UI.
            // `since` is the persisted per-relay cursor (last arm time), so deletions that
            // happened while this client was offline still replay; bounded so ancient
            // deletions don't poison deletedGroupIds against re-created ids.
            val delSince = if (deleteSinceSeconds > 0) deleteSinceSeconds else epochMillis() / 1000 - 60
            send(
                buildJsonArray {
                    add("REQ")
                    add(delSubId)
                    add(
                        buildJsonObject {
                            putJsonArray("kinds") { add(9008) }
                            putJsonArray("#h") { metadataGroupIds.forEach { add(it) } }
                            put("since", delSince)
                        },
                    )
                }.toString(),
            )
        }
    }

/**
     * Superseded by [sendMuxSubscriptions] — kept as a single-group fallback.
     * @param sinceSeconds  Cursor-based Unix-seconds since value from [LiveCursorStore].
     */
    suspend fun sendLiveSubscription(groupId: String, sinceSeconds: Long? = null) {
        val subId = "live_${groupId.take(8)}"
        val since = sinceSeconds ?: (epochMillis() / 1000 - 60)
        send(
            buildJsonArray {
                add("CLOSE")
                add(subId)
            }.toString(),
        )
        send(
            buildJsonArray {
                add("REQ")
                add(subId)
                add(
                    buildJsonObject {
                        putJsonArray("kinds") {
                            add(5)
                            add(7)
                            add(9)
                            add(9000)
                            add(9001)
                            add(9002)
                            add(9003)
                            add(9021)
                            add(9022)
                            add(9321)
                        }
                        put("#h", buildJsonArray { add(groupId) })
                        put("since", since)
                    },
                )
            }.toString(),
        )
    }

    /** Best-effort CLOSE — swallows send failures so the subsequent REQ always runs. */
    private suspend fun trySendClose(subId: String) {
        try {
            send(
                buildJsonArray {
                    add("CLOSE")
                    add(subId)
                }.toString(),
            )
        } catch (_: Exception) {
            // Not connected yet or session gone — safe to ignore; relay will
            // simply treat the next REQ as a fresh subscription.
        }
    }

    private suspend fun sendJson(jsonElement: JsonElement) {
        val text = json.encodeToString(JsonElement.serializer(), jsonElement)
        try {
            send(text)
        } catch (_: Exception) {
            // sendJson is used for fire-and-forget subscriptions (group list, metadata, members).
            // Failures here are non-fatal — the caller will retry on next reconnect.
        }
    }

    fun parseGroupMetadata(message: String): GroupMetadata? {
        return try {
            val arr = json.parseToJsonElement(message).jsonArray
            if (arr.size < 3 || arr[0].jsonPrimitive.content != "EVENT") return null
            parseGroupMetadata(arr[2].jsonObject)
        } catch (e: Exception) {
            null
        }
    }

    fun parseGroupMetadata(event: JsonObject): GroupMetadata? {
        return try {
            if (event["kind"]?.jsonPrimitive?.int != 39000) return null

            val tags = event["tags"]?.jsonArray ?: return null

            // Build map for tags with values (size >= 2)
            val tagMap = tags
                .filter { it.jsonArray.size >= 2 }
                .associate { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.content }

            // Get all tag names (including presence-only tags like ["public"], ["open"])
            val tagNames = tags.map { it.jsonArray[0].jsonPrimitive.content }.toSet()

            // NIP-29 subgroups: `parent` carries the parent d-tag; a group with no (or an
            // empty) parent tag is a root.
            val parentTag = tags.firstOrNull {
                it.jsonArray.isNotEmpty() && it.jsonArray[0].jsonPrimitive.content == "parent"
            }?.jsonArray
            val parent = parentTag?.getOrNull(1)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

            // `["child", "<id>"]` tags, in tag order (sibling display order).
            val children = tags.mapNotNull { tag ->
                val arr = tag.jsonArray
                if (arr.size < 2 || arr[0].jsonPrimitive.content != "child") return@mapNotNull null
                arr[1].jsonPrimitive.content.takeIf { it.isNotBlank() }
            }

            GroupMetadata(
                id = tagMap["d"] ?: "unknown",
                name = tagMap["name"],
                about = tagMap["about"],
                picture = tagMap["picture"],
                isPublic = !tagNames.contains("private"),
                isOpen = !tagNames.contains("closed"),
                isRestricted = tagNames.contains("restricted"),
                isHidden = tagNames.contains("hidden"),
                parent = parent,
                children = children,
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse kind 39002 group members event.
     * Returns GroupMembers containing all member pubkeys from "p" tags.
     */
    fun parseGroupMembers(message: String): GroupMembers? {
        return try {
            val arr = json.parseToJsonElement(message).jsonArray
            if (arr.size < 3 || arr[0].jsonPrimitive.content != "EVENT") return null
            parseGroupMembers(arr[2].jsonObject)
        } catch (e: Exception) {
            null
        }
    }

    fun parseGroupMembers(event: JsonObject): GroupMembers? {
        return try {
            if (event["kind"]?.jsonPrimitive?.int != 39002) return null

            val tags = event["tags"]?.jsonArray ?: return null

            val groupId = tags
                .firstOrNull { it.jsonArray.size >= 2 && it.jsonArray[0].jsonPrimitive.content == "d" }
                ?.jsonArray?.get(1)?.jsonPrimitive?.content
                ?: return null

            val members = tags
                .filter { it.jsonArray.size >= 2 && it.jsonArray[0].jsonPrimitive.content == "p" }
                .map { it.jsonArray[1].jsonPrimitive.content }

            GroupMembers(
                groupId = groupId,
                members = members,
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse kind 39001 group admins event.
     * Returns GroupAdmins containing all admin pubkeys from "p" tags.
     */
    fun parseGroupAdmins(message: String): GroupAdmins? {
        return try {
            val arr = json.parseToJsonElement(message).jsonArray
            if (arr.size < 3 || arr[0].jsonPrimitive.content != "EVENT") return null
            parseGroupAdmins(arr[2].jsonObject)
        } catch (e: Exception) {
            null
        }
    }

    fun parseGroupAdmins(event: JsonObject): GroupAdmins? {
        return try {
            if (event["kind"]?.jsonPrimitive?.int != 39001) return null

            val tags = event["tags"]?.jsonArray ?: return null

            val groupId = tags
                .firstOrNull { it.jsonArray.size >= 2 && it.jsonArray[0].jsonPrimitive.content == "d" }
                ?.jsonArray?.get(1)?.jsonPrimitive?.content
                ?: return null

            val admins = tags
                .filter { it.jsonArray.size >= 2 && it.jsonArray[0].jsonPrimitive.content == "p" }
                .map { it.jsonArray[1].jsonPrimitive.content }

            GroupAdmins(groupId = groupId, admins = admins)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse a kind 39003 (group roles) event.
     * Tags: ["d", groupId], ["role", name, description?]
     */
    fun parseGroupRoles(event: JsonObject): GroupRoles? {
        return try {
            if (event["kind"]?.jsonPrimitive?.int != 39003) return null

            val tags = event["tags"]?.jsonArray ?: return null

            val groupId = tags
                .firstOrNull { it.jsonArray.size >= 2 && it.jsonArray[0].jsonPrimitive.content == "d" }
                ?.jsonArray?.get(1)?.jsonPrimitive?.content
                ?: return null

            val roles = tags
                .filter { it.jsonArray.size >= 2 && it.jsonArray[0].jsonPrimitive.content == "role" }
                .map { tag ->
                    RoleDefinition(
                        name = tag.jsonArray[1].jsonPrimitive.content,
                        description = if (tag.jsonArray.size >= 3) tag.jsonArray[2].jsonPrimitive.content else "",
                    )
                }

            GroupRoles(groupId = groupId, roles = roles)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Request group roles (kind 39003) for a specific group.
     *
     * Same deterministic ID rationale as requestGroupAdmins.
     */
    suspend fun requestGroupRoles(groupId: String): String {
        val subId = "roles_${groupId.take(8)}"
        trySendClose(subId)
        val req = buildJsonArray {
            add("REQ")
            add(subId)
            add(
                buildJsonObject {
                    putJsonArray("kinds") { add(39003) }
                    put("#d", buildJsonArray { add(groupId) })
                },
            )
        }
        sendJson(req)
        return subId
    }

    /**
     * Request group admins (kind 39001) for a specific group.
     *
     * Uses a deterministic subscription ID so repeated calls reuse the same relay slot
     * instead of leaking a new subscription each time. Without this, 5 groups × 4
     * reconnects = 20 admin subscriptions — most relays cap at 20–30 total.
     */
    suspend fun requestGroupAdmins(groupId: String): String {
        val subId = "admins_${groupId.take(8)}"
        trySendClose(subId)
        val req = buildJsonArray {
            add("REQ")
            add(subId)
            add(
                buildJsonObject {
                    putJsonArray("kinds") { add(39001) }
                    put("#d", buildJsonArray { add(groupId) })
                },
            )
        }
        sendJson(req)
        return subId
    }

    /**
     * Request pending join-request events (kind 9021 + 9022 leaves) for a specific group.
     *
     * The standard chat REQ (limit=50, all kinds) buries old 9021 events under recent
     * kind:9 chat in active groups, so admins of closed groups miss pending requests
     * until something else refreshes the mux. This dedicated REQ pulls them directly.
     * Includes 9022 so the UI's "ignore requests followed by a leave" rule still applies.
     */
    suspend fun requestPendingJoinRequests(groupId: String): String {
        val subId = "joinreq_${groupId.take(8)}"
        trySendClose(subId)
        val req = buildJsonArray {
            add("REQ")
            add(subId)
            add(
                buildJsonObject {
                    putJsonArray("kinds") {
                        add(9021)
                        add(9022)
                    }
                    putJsonArray("#h") { add(groupId) }
                    put("limit", 500)
                },
            )
        }
        sendJson(req)
        return subId
    }

    /**
     * Request group members (kind 39002) for a specific group.
     *
     * Same deterministic ID rationale as requestGroupAdmins.
     */
    suspend fun requestGroupMembers(groupId: String): String {
        val subId = "members_${groupId.take(8)}"
        trySendClose(subId)
        val req = buildJsonArray {
            add("REQ")
            add(subId)
            add(
                buildJsonObject {
                    putJsonArray("kinds") { add(39002) }
                    put("#d", buildJsonArray { add(groupId) })
                },
            )
        }
        sendJson(req)
        return subId
    }

    /**
     * Bounded forward-fill for messages that slipped through a dead live-sub window:
     * one batched REQ for [groupIds] with `since` only (no until, no controller
     * involvement). Events route by their own #h tag through the normal kind-based
     * handler; the deduplicator absorbs the overlap with what is already in memory.
     * The gapfill_ sub id keeps it out of every pagination/initial-load code path
     * (those key on msg_), so it can never reset a group's HasMore state.
     */
    suspend fun requestGroupsMessagesSince(groupIds: List<String>, since: Long): String {
        val subId = "gapfill_${epochMillis()}"
        val req = buildJsonArray {
            add("REQ")
            add(subId)
            add(
                buildJsonObject {
                    put(
                        "kinds",
                        buildJsonArray {
                            add(5)
                            add(9)
                            add(9000)
                            add(9001)
                            add(9002)
                            add(9003)
                            add(9005)
                            add(9009)
                            add(9021)
                            add(9022)
                        },
                    )
                    putJsonArray("#h") { groupIds.forEach { add(it) } }
                    put("since", since)
                },
            )
        }
        sendJson(req)
        return subId
    }

    /**
     * Batched kind:39002 members fetch for several groups in one REQ (discovery member
     * counts). Each returned event's own "d" tag identifies its group — parseGroupMembers
     * reads it from the event, not the sub id — so, like [requestGroupsMetadata], one shared
     * sub id safely routes every group's member list back correctly.
     */
    suspend fun requestGroupsMembers(groupIds: List<String>): String {
        val subId = "members_batch_${groupIds.sorted().joinToString(",").hashCode().toUInt()}"
        batchSubGroupIds = batchSubGroupIds + (subId to groupIds)
        trySendClose(subId)
        val req = buildJsonArray {
            add("REQ")
            add(subId)
            add(
                buildJsonObject {
                    putJsonArray("kinds") { add(39002) }
                    putJsonArray("#d") { groupIds.forEach { add(it) } }
                },
            )
        }
        sendJson(req)
        return subId
    }

    data class ParsedUserMetadata(
        val pubkey: String,
        val metadata: UserMetadata,
        val createdAt: Long,
    )

    fun parseUserMetadata(message: String): ParsedUserMetadata? {
        return try {
            val arr = json.parseToJsonElement(message).jsonArray
            if (arr.size < 3 || arr[0].jsonPrimitive.content != "EVENT") return null
            parseUserMetadata(arr[2].jsonObject)
        } catch (e: Exception) {
            null
        }
    }

    fun parseUserMetadata(event: JsonObject): ParsedUserMetadata? {
        return try {
            if (event["kind"]?.jsonPrimitive?.int != 0) return null

            val pubkey = event["pubkey"]?.jsonPrimitive?.content ?: return null
            val content = event["content"]?.jsonPrimitive?.content ?: "{}"
            val createdAt = event["created_at"]?.jsonPrimitive?.long ?: 0L

            val metadata = json.parseToJsonElement(content).jsonObject

            ParsedUserMetadata(
                pubkey = pubkey,
                createdAt = createdAt,
                metadata = UserMetadata(
                    pubkey = pubkey,
                    name = metadata["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                    displayName = metadata["display_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                    picture = metadata["picture"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                    about = metadata["about"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                    nip05 = metadata["nip05"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                    banner = metadata["banner"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                    lud16 = metadata["lud16"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                    lud06 = metadata["lud06"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                    website = metadata["website"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                    rawContentJson = content,
                ),
            )
        } catch (e: Exception) {
            null
        }
    }

    @Immutable
    data class NostrMessage(
        val id: String,
        val pubkey: String,
        val content: String,
        val createdAt: Long,
        val kind: Int,
        val tags: List<List<String>> = emptyList(),
        /**
         * Relay that delivered (or will receive) this event, normalized. Not part of the
         * NIP-01 event: it scopes readers of the shared bare-id message buckets, because
         * the same group id on two relays is two independent groups. Null when the origin
         * is unknown (pre-tag construction sites); such messages show everywhere.
         */
        val relayUrl: String? = null,
    )

    @Immutable
    data class NostrReaction(
        val id: String,
        val pubkey: String,
        val emoji: String,
        val emojiUrl: String? = null, // URL for custom emoji (NIP-30)
        val targetEventId: String,
        val createdAt: Long,
        // NIP-25 `p` tag: pubkey of the author of the event being reacted to.
        // Used to surface "someone reacted to your message" notifications without
        // needing the target message to already be in the in-memory cache.
        val targetAuthorPubkey: String? = null,
        // NIP-29 `h` tag: group id the reaction was published to.
        val groupId: String? = null,
    )

    fun parseMessage(message: String): NostrMessage? {
        return try {
            val arr = json.parseToJsonElement(message).jsonArray
            if (arr.size < 3 || arr[0].jsonPrimitive.content != "EVENT") return null
            parseMessage(arr[2].jsonObject)
        } catch (e: Exception) {
            null
        }
    }

    fun parseMessage(event: JsonObject): NostrMessage? {
        return try {
            val tags = event["tags"]?.jsonArray?.map { tag ->
                tag.jsonArray.map { it.jsonPrimitive.content }
            } ?: emptyList()

            NostrMessage(
                id = event["id"]?.jsonPrimitive?.content ?: return null,
                pubkey = event["pubkey"]?.jsonPrimitive?.content ?: return null,
                content = event["content"]?.jsonPrimitive?.content ?: "",
                createdAt = event["created_at"]?.jsonPrimitive?.long ?: (epochMillis() / 1000),
                kind = event["kind"]?.jsonPrimitive?.int ?: 0,
                tags = tags,
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse a kind 7 reaction event
     * Returns NostrReaction if valid, null otherwise
     */
    fun parseReaction(message: String): NostrReaction? {
        return try {
            val arr = json.parseToJsonElement(message).jsonArray
            if (arr.size < 3 || arr[0].jsonPrimitive.content != "EVENT") return null
            parseReaction(arr[2].jsonObject)
        } catch (e: Exception) {
            null
        }
    }

    fun parseReaction(event: JsonObject): NostrReaction? {
        return try {
            val kind = event["kind"]?.jsonPrimitive?.int ?: return null
            if (kind != 7) return null

            val tags = event["tags"]?.jsonArray?.map { tag ->
                tag.jsonArray.map { it.jsonPrimitive.content }
            } ?: emptyList()

            val targetEventId = tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1) ?: return null

            val emoji = event["content"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "+"

            val shortcode = emoji.trim(':')
            val emojiUrl = tags.firstOrNull { tag ->
                tag.size >= 3 && tag[0] == "emoji" && tag[1] == shortcode
            }?.getOrNull(2)

            val targetAuthorPubkey = tags.firstOrNull { it.size >= 2 && it[0] == "p" }?.get(1)
            val groupId = tags.firstOrNull { it.size >= 2 && it[0] == "h" }?.get(1)

            NostrReaction(
                id = event["id"]?.jsonPrimitive?.content ?: return null,
                pubkey = event["pubkey"]?.jsonPrimitive?.content ?: return null,
                emoji = emoji,
                emojiUrl = emojiUrl,
                targetEventId = targetEventId,
                createdAt = event["created_at"]?.jsonPrimitive?.long ?: (epochMillis() / 1000),
                targetAuthorPubkey = targetAuthorPubkey,
                groupId = groupId,
            )
        } catch (e: Exception) {
            null
        }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun prettyPrintEvent(message: String): String = try {
        val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            prettyPrintIndent = "  "
        }
        val arr = json.parseToJsonElement(message).jsonArray

        when (arr[0].jsonPrimitive.content) {
            "EVENT" -> {
                val event = arr[2].jsonObject
                val kind = event["kind"]?.jsonPrimitive?.int
                val pubkey = event["pubkey"]?.jsonPrimitive?.content?.take(8) ?: "unknown"
                val content = event["content"]?.jsonPrimitive?.content?.take(50) ?: ""

                buildString {
                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    appendLine("📨 EVENT Received")
                    appendLine("Type: kind $kind")
                    appendLine("From: $pubkey...")
                    when (kind) {
                        0 -> appendLine("Category: User Profile (metadata)")
                        5 -> appendLine("Category: Deletion Request (NIP-09)")
                        7 -> appendLine("Category: Reaction")
                        9 -> appendLine("Category: Group Message (NIP-29)")
                        9000 -> appendLine("Category: Admin Add User (NIP-29)")
                        9001 -> appendLine("Category: Admin Remove User (NIP-29)")
                        9005 -> appendLine("Category: Admin Delete Event (NIP-29)")
                        9008 -> appendLine("Category: Admin Edit Metadata (NIP-29)")
                        9021 -> appendLine("Category: Join Group Request")
                        9022 -> appendLine("Category: Leave Group Request")
                        9321 -> appendLine("Category: Zap Request (NIP-57)")
                        30382 -> appendLine("Category: Group List (NIP-51)")
                        39000 -> appendLine("Category: Group Metadata")
                        39002 -> appendLine("Category: Group Members")
                        else -> appendLine("Category: Unknown")
                    }
                    appendLine("Content: ${if (content.length > 50) content.take(50) + "..." else content}")
                    appendLine("Full JSON:")
                    appendLine(json.encodeToString(JsonElement.serializer(), arr))
                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                }
            }
            "EOSE" -> "✅ End of Stored Events (subscription: ${arr.getOrNull(1)?.jsonPrimitive?.content})"
            "OK" -> {
                val eventId = arr.getOrNull(1)?.jsonPrimitive?.content?.take(8)
                val success = arr.getOrNull(2)?.jsonPrimitive?.boolean
                val message = arr.getOrNull(3)?.jsonPrimitive?.content
                "✓ OK Response: Event $eventId -> ${if (success == true) "✅ Success" else "❌ Failed"}: $message"
            }
            "NOTICE" -> "⚠️ NOTICE: ${arr.getOrNull(1)?.jsonPrimitive?.content}"
            "CLOSED" -> "🔒 CLOSED: Subscription ${arr.getOrNull(1)?.jsonPrimitive?.content} closed: ${arr.getOrNull(2)?.jsonPrimitive?.content}"
            "AUTH" -> "🔐 AUTH: Challenge received: ${arr.getOrNull(1)?.jsonPrimitive?.content?.take(16)}..."
            else -> "❓ Unknown message type: $message"
        }
    } catch (e: Exception) {
        "⚠️ Failed to parse: $message (${e.message})"
    }

    suspend fun requestMetadata(pubkeys: List<String>, subId: String) {
        if (pubkeys.isEmpty()) return
        // CLOSE any previous subscription with this id before re-subscribing
        sendJson(
            buildJsonArray {
                add("CLOSE")
                add(subId)
            },
        )
        val req = buildJsonArray {
            add("REQ")
            add(subId)
            add(
                buildJsonObject {
                    putJsonArray("kinds") { add(0) } // kind 0 = metadata
                    putJsonArray("authors") {
                        pubkeys.forEach { add(it) }
                    }
                },
            )
        }
        sendJson(req)
    }

    suspend fun requestEventById(eventId: String) {
        // Use short subscription ID - many relays limit to 64 chars
        val subId = "e_${epochMillis()}"
        val req = buildJsonArray {
            add("REQ")
            add(subId)
            add(
                buildJsonObject {
                    putJsonArray("ids") { add(eventId) }
                },
            )
        }
        sendJson(req)
    }

    // #h filter included because some NIP-29 relays require it even for ID lookups.
    suspend fun requestGroupMessageById(groupId: String, messageId: String) {
        val subId = "e_${epochMillis()}"
        val req = buildJsonArray {
            add("REQ")
            add(subId)
            add(
                buildJsonObject {
                    putJsonArray("ids") { add(messageId) }
                    putJsonArray("#h") { add(groupId) }
                },
            )
        }
        sendJson(req)
    }

    /**
     * Request an addressable event by its coordinates (kind, pubkey, d-tag).
     * Addressable events are parameterized replaceable events (kinds 30000-39999).
     */
    suspend fun requestAddressableEvent(kind: Int, pubkey: String, identifier: String) {
        // Use short subscription ID - many relays limit to 64 chars
        val subId = "a_${epochMillis()}"
        val req = buildJsonArray {
            add("REQ")
            add(subId)
            add(
                buildJsonObject {
                    putJsonArray("kinds") { add(kind) }
                    putJsonArray("authors") { add(pubkey) }
                    putJsonArray("#d") { add(identifier) }
                    put("limit", 1)
                },
            )
        }
        sendJson(req)
    }

    suspend fun disconnect() {
        // Mark as graceful disconnect to prevent onConnectionLost callback
        isDisconnecting = true
        // Clear pending OK responses before disconnect
        clearPendingOkResponses()
        // Cancel all managed coroutines
        clientScope.coroutineContext.cancelChildren()
        connectionJob?.cancel()
        // Graceful close is bounded: Ktor's close() suspends until the close frame
        // drains, which never happens on a half-open socket (zombie after network
        // flap/sleep). Unbounded, it hangs the NonCancellable logout teardown after
        // credentials are wiped and the pool stopped accepting connects, bricking
        // the session until the app data is cleared. On timeout the forced
        // client.close() below tears the socket down anyway.
        withTimeoutOrNull(CLOSE_HANDSHAKE_TIMEOUT_MS) { session?.close() }
        client.close()
    }

    /**
     * Non-graceful cleanup for abandoned clients (timeout, error).
     * Closes the HttpClient and cancels coroutines WITHOUT touching session
     * (which may be null or mid-close). Does NOT send any frames.
     * Call this instead of disconnect() when the connection was never established
     * or when orphaning a client after a concurrent connect succeeded first.
     */
    fun cancelAndClose() {
        onConnectionLost = null // prevent any further callbacks
        isDisconnecting = true
        connectionJob?.cancel()
        clientScope.coroutineContext.cancelChildren()
        try {
            client.close()
        } catch (_: Exception) {}
    }
}
