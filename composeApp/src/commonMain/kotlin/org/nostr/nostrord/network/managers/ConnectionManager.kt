package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.nostr.nostrord.auth.ActiveAccountManager
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.getCurrentRelayUrlFor
import org.nostr.nostrord.storage.saveCurrentRelayUrlFor
import org.nostr.nostrord.utils.normalizeRelayUrl
import kotlin.concurrent.Volatile

/**
 * Manages relay connections and connection pooling.
 * Handles focused NIP-29 relay connection and auxiliary relay pool.
 * Includes automatic reconnection with exponential backoff.
 */
class ConnectionManager(
    private val scope: CoroutineScope,
    private val connStats: ConnectionStats? = null,
    private val adaptiveConfig: AdaptiveConfig? = null,
) {
    /**
     * Owned child of [scope] for cancellable connection work: in-flight connects,
     * lost-socket handling, send fan-outs, and the reconnect scheduler's retries.
     * [clearAll] cancels ONLY this scope's children. It used to cancelChildren() on
     * the SHARED app scope, which killed every collector in the app on logout (DM
     * inbox lifecycle, mux refresh timers, eager stateIn derivations), so a re-login
     * came up without its plumbing until a process restart.
     */
    val connectionWorkScope: CoroutineScope =
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private var currentMessageHandler: ((String, NostrGroupClient) -> Unit)? = null

    // Serialises connectFocused so two coroutines cannot both pass the "already connecting"
    // guard simultaneously (Dispatchers.Default is multi-threaded).
    private val connectMutex = Mutex()

    private val _currentRelayUrl = MutableStateFlow("")
    val currentRelayUrl: StateFlow<String> = _currentRelayUrl.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Gate on whether a lost connection should be handed to the reconnect driver at all.
    // False after setError() (relay actively rejected access) or an intentional disconnect.
    private var autoReconnectEnabled = true

    // Shared relay pool for all auxiliary connections (outbox, metadata, NIP-65)
    private val poolMutex = Mutex()
    private val relayPool = mutableMapOf<String, NostrGroupClient>()

    /**
     * The client for [_currentRelayUrl], if connected. It lives in [relayPool] like
     * every other relay — there is no separate "focused" storage slot.
     */
    private fun focusedClient(): NostrGroupClient? = relayPool[_currentRelayUrl.value]

    // Gate for opening NEW pool sockets. clearAll() (logout) flips it off so
    // background jobs can't reconnect relays during/after teardown and leak them;
    // resumeConnections() (a session activating) flips it back on. Default on so
    // the normal logged-in path is unaffected.
    @Volatile
    private var acceptingConnections = true

    /** Re-allow pool connections after a logout gated them off. Idempotent. */
    fun resumeConnections() {
        acceptingConnections = true
    }

    // Per-relay (normalized URL) outcome of the most recent WebSocket connect:
    // true = opened, false = failed. Drives reachability filtering on discovery
    // surfaces. A relay that connected then dropped stays `true` (it is reachable,
    // just reconnecting) so flaky relays are not hidden.
    private val _relayReachability = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val relayReachability: StateFlow<Map<String, Boolean>> = _relayReachability.asStateFlow()

    private fun markReachability(relayUrl: String, reachable: Boolean) {
        _relayReachability.update { current ->
            if (current[relayUrl] == reachable) current else current + (relayUrl to reachable)
        }
    }

    // Singleflight: in-flight connection attempts per URL. Without this,
    // concurrent callers of getOrConnectRelay for the same URL each create
    // their own NostrGroupClient (the existing double-check pattern released
    // the lock during the actual WebSocket handshake), opening N parallel
    // sockets and then closing all-but-one as they each lose the race-to-
    // insert. The browser network panel shows the full N sockets and the
    // relay sees N connects/disconnects in quick succession.
    private val pendingConnects = mutableMapOf<String, kotlinx.coroutines.CompletableDeferred<NostrGroupClient?>>()

    /**
     * Called when the focused connection drops unexpectedly.
     * Set by NostrRepository to also notify GroupManager.
     */
    var onConnectionDropped: (() -> Unit)? = null

    /**
     * Called after a successful auto-reconnect with the new client.
     * Set by NostrRepository to re-auth and re-subscribe.
     */
    var onReconnected: (suspend (NostrGroupClient) -> Unit)? = null

    /**
     * Called when a pool relay drops unexpectedly.
     * Set by NostrRepository to trigger reconnection with backoff.
     */
    var onPoolRelayLost: ((relayUrl: String) -> Unit)? = null

    /**
     * Called whenever [getOrConnectRelay] lands a NEW socket, regardless of which caller
     * won the singleflight. Set by NostrRepository to re-arm live subscriptions on a relay
     * that dropped: the reconnect scheduler is NOT the only path that revives a relay —
     * any background fetch (metadata, previews, outbox) calling getOrConnectRelay during
     * the scheduler's backoff delay reconnects it first, and that caller only sends its
     * own REQ. Without this hook the scheduler then sees the relay "already connected",
     * skips the resubscribe, and the group's mux/chat subscription stays silently dead
     * (messages stop arriving live) until an app restart.
     */
    var onRelayConnected: ((relayUrl: String, client: NostrGroupClient) -> Unit)? = null

    sealed class ConnectionState {
        data object Disconnected : ConnectionState()

        data object Connecting : ConnectionState()

        data object Connected : ConnectionState()

        data class Error(
            val message: String,
        ) : ConnectionState()

        data class Reconnecting(
            val attempt: Int,
            val maxAttempts: Int,
        ) : ConnectionState()
    }

    private var networkMonitorJob: Job? = null

    /**
     * Start listening for platform network change events.
     * Call once after construction (e.g. from NostrRepository.initialize).
     */
    fun startNetworkMonitor() {
        networkMonitorJob?.cancel()
        networkMonitorJob =
            scope.launch {
                createNetworkMonitorFlow().collect { event ->
                    when (event) {
                        NetworkEvent.CHANGED -> {
                            // IP changed — kill stale socket and reconnect immediately.
                            if (focusedClient() != null) {
                                reconnectImmediate()
                            }
                        }
                        NetworkEvent.DISCONNECTED -> {
                            // No point retrying while offline — save battery. The reconnect
                            // driver's in-flight retry (if any) becomes a no-op: getOrConnectRelay
                            // returns null while offline and it just reschedules.
                            autoReconnectEnabled = false
                        }
                        NetworkEvent.CONNECTED -> {
                            autoReconnectEnabled = true
                            if (focusedClient() == null && _currentRelayUrl.value.isNotBlank()) {
                                reconnectImmediate()
                            }
                        }
                    }
                }
            }
    }

    /**
     * Fast-path reconnect for network change events.
     * Bypasses exponential backoff — the network is available, just different.
     */
    private suspend fun reconnectImmediate() {
        val url = _currentRelayUrl.value
        val dead = focusedClient()
        dead?.onConnectionLost = null
        if (dead != null) poolMutex.withLock { relayPool.remove(url) }
        dead?.cancelAndClose()

        onConnectionDropped?.invoke()

        val handler = currentMessageHandler ?: return
        val success = connectFocused(url, handler)
        if (success) {
            adaptiveConfig?.recordReconnect()
            focusedClient()?.let { client -> connectionWorkScope.launch { onReconnected?.invoke(client) } }
        } else {
            // Hand off to the same backoff driver a passive drop would use (see
            // [handleClientLost]) instead of retrying in a loop of our own.
            onPoolRelayLost?.invoke(url)
        }
    }

    /**
     * Load the active account's saved relay URL into [currentRelayUrl].
     *
     * Pubkey-scoped: without an active account we leave the StateFlow blank
     * so the rail starts empty for a freshly added (yet-to-be-activated)
     * identity instead of inheriting the previous account's relay.
     */
    suspend fun loadSavedRelay() {
        val pubkey = ActiveAccountManager.currentPubkey
        val savedRelayUrl =
            if (pubkey.isNullOrBlank()) {
                null
            } else {
                SecureStorage.getCurrentRelayUrlFor(pubkey)
            }
        _currentRelayUrl.value = savedRelayUrl?.normalizeRelayUrl().orEmpty()
    }

    fun clearCurrentRelay() {
        _currentRelayUrl.value = ""
    }

    /**
     * Get the focused NIP-29 client
     */
    fun getFocusedClient(): NostrGroupClient? = focusedClient()

    /**
     * Returns the client for a specific relay URL: the pool (focused included), then
     * any in-flight connect.
     * Does not START a new connection, but if one is already in flight for this
     * URL (the [getOrConnectRelay] singleflight, e.g. the bootstrap fan-out)
     * this awaits it instead of reporting "not connected".
     *
     * Awaiting the pending connect is what keeps cold start from opening
     * duplicate sockets: the metadata / nip65 / contacts readers all call this
     * the instant the app boots, while the bootstrap relays are still
     * connecting. Returning null there made each reader treat the relay as
     * down and open its own socket (observed: 8 concurrent sockets to nos.lol).
     * Converging on the shared singleflight deferred collapses that to one.
     */
    suspend fun getClientForRelay(relayUrl: String): NostrGroupClient? {
        // A blank URL (or, via a platform-type leak in a relay list, a runtime null) has no
        // client. Guard before normalizeRelayUrl, whose non-null receiver check would otherwise
        // crash the caller; this is a suspend fun, so the parameter null-check is not emitted.
        if (relayUrl.isNullOrBlank()) return null
        val normalized = relayUrl.normalizeRelayUrl()
        // The pool holds every connected relay, focused included, so a single lookup covers it.
        val (pooled, pending) =
            poolMutex.withLock { relayPool[normalized] to pendingConnects[normalized] }
        if (pooled != null) return pooled
        return pending?.await()
    }

    /**
     * Every currently-connected client (focused + pool). Used to broadcast a by-id REQ
     * when a relay hint is wrong or missing: a NIP-29 event lives only on its group's
     * relay, and the hint can point elsewhere, so querying just the hint misses it.
     */
    suspend fun getAllConnectedClients(): List<NostrGroupClient> = poolMutex.withLock { relayPool.values.toList() }.filter { it.isConnected() }

    /**
     * Connect to the focused NIP-29 relay.
     * Serialised by [connectMutex] — concurrent callers block until the in-flight
     * attempt finishes, then return false immediately if a client is already up.
     */
    suspend fun connectFocused(
        relayUrl: String = _currentRelayUrl.value,
        onMessage: (String, NostrGroupClient) -> Unit,
    ): Boolean = connectMutex.withLock {
        if (relayUrl.isBlank()) return@withLock false
        val normalized = relayUrl.normalizeRelayUrl()
        if (focusedClient() != null) return@withLock false

        currentMessageHandler = onMessage
        // Guarded like every other write below: connectMutex only serialises connectFocused
        // against itself, not against setFocusedRelay moving focus elsewhere mid-connect, so
        // connectionState must keep reflecting whichever relay is focused NOW, not the one
        // this call started out connecting.
        if (_currentRelayUrl.value == normalized) {
            _connectionState.value = ConnectionState.Connecting
        }
        connStats?.onConnecting(relayUrl)

        try {
            val newClient = NostrGroupClient(relayUrl)
            poolMutex.withLock { relayPool[normalized] = newClient }
            wireConnectionLost(newClient, normalized)

            newClient.connect { msg -> onMessage(msg, newClient) }
            val opened = newClient.waitForConnection()
            if (!opened) {
                // CRITICAL: detach onConnectionLost BEFORE removing from the pool.
                // Without this, the orphaned client's WebSocket may open then close later
                // and fire handleClientLost(), killing any focused that was connected
                // in the meantime by the reconnect loop.
                newClient.onConnectionLost = null
                newClient.cancelAndClose()
                poolMutex.withLock { relayPool.remove(normalized) }
                if (_currentRelayUrl.value == normalized) {
                    _connectionState.value = ConnectionState.Error("Connection timed out")
                }
                connStats?.onConnectFailed(relayUrl)
                markReachability(normalized, false)
                return@withLock false
            }
            if (_currentRelayUrl.value == normalized) {
                _connectionState.value = ConnectionState.Connected
            }
            connStats?.onConnected(relayUrl)
            markReachability(normalized, true)
            // Feed adaptive config: relay connection latency
            connStats?.getStats()?.get(relayUrl)?.lastReconnectMs?.let { latency ->
                adaptiveConfig?.recordRelayLatency(relayUrl, latency)
            }
            true
        } catch (e: Exception) {
            if (_currentRelayUrl.value == normalized) {
                _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
            }
            focusedClient()?.let {
                it.onConnectionLost = null
                it.cancelAndClose()
            }
            poolMutex.withLock { relayPool.remove(normalized) }
            connStats?.onConnectFailed(relayUrl)
            false
        }
    }

    /**
     * Every pooled client — focused or not — is wired through this one handler. It
     * re-derives the client's role from [_currentRelayUrl] at drop time instead of at
     * wiring time, so moving focus ([setFocusedRelay]) never has to re-wire a client's
     * callback: the same closure resolves to the fast focused-reconnect path or the
     * background pool-reconnect path depending on who currently holds focus.
     */
    private fun wireConnectionLost(client: NostrGroupClient, url: String) {
        client.onConnectionLost = {
            connectionWorkScope.launch { handleClientLost(client, url) }
        }
    }

    private suspend fun handleClientLost(client: NostrGroupClient, url: String) {
        // Detach the callback first to prevent re-entrant calls if cancelAndClose()
        // somehow triggers another connection-lost event on the dying client.
        client.onConnectionLost = null
        // Remove by identity, not URL: this runs via scope.launch, so a fresh client for
        // the same relay can already be pooled by the time it executes — evicting THAT
        // would orphan a live, subscribed socket and reconnect a duplicate over it.
        val evicted = poolMutex.withLock {
            if (relayPool[url] === client) {
                relayPool.remove(url)
                true
            } else {
                false
            }
        }
        connStats?.onDisconnected(url)
        client.cancelAndClose() // release HttpClient threads/pool, cancel lingering coroutines
        if (!evicted) return // a replacement is already live; nothing to reconnect

        val wasFocused = _currentRelayUrl.value == url
        if (wasFocused) {
            onConnectionDropped?.invoke()
            if (!autoReconnectEnabled) {
                _connectionState.value = ConnectionState.Disconnected
                return
            }
        }
        // Both roles hand off to the same external reconnect driver (a
        // RelayReconnectScheduler owned by the caller) — see [reportReconnectAttempt] and
        // [notifyReconnected] for how a focused relay's retries still reach [connectionState].
        onPoolRelayLost?.invoke(url)
    }

    /**
     * Reports that an external reconnect driver is about to retry [relayUrl] for the
     * [attempt]-th time. A no-op unless [relayUrl] is still the focused relay — a stale
     * callback for a relay the user has since switched away from should not touch
     * [connectionState]. [maxFastAttempts] is only used to size the displayed counter; the
     * driver itself decides when to stop retrying.
     */
    fun reportReconnectAttempt(relayUrl: String, attempt: Int, maxFastAttempts: Int) {
        if (_currentRelayUrl.value != relayUrl) return
        // Retry chains overlap: each drop spawns a fresh chain and "Retry now" doesn't
        // cancel the old ones, so a straggler attempt can fire after the socket is
        // already back. Once the focused client is genuinely connected, no attempt
        // report may stomp Connected back to Reconnecting/Error.
        if (_connectionState.value is ConnectionState.Connected && focusedClient()?.isConnected() == true) return
        _connectionState.value =
            if (attempt <= maxFastAttempts) {
                ConnectionState.Reconnecting(attempt, maxFastAttempts)
            } else {
                ConnectionState.Error("Connection lost. Tap to retry.")
            }
    }

    /**
     * Reports that an external reconnect driver re-established [client] via the generic pool
     * path ([getOrConnectRelay]), which — unlike [connectFocused] — doesn't update
     * [connectionState] itself. A no-op unless [client] is still the focused relay.
     */
    fun notifyReconnected(client: NostrGroupClient) {
        if (_currentRelayUrl.value != client.getRelayUrl().normalizeRelayUrl()) return
        _connectionState.value = ConnectionState.Connected
        adaptiveConfig?.recordReconnect()
        connectionWorkScope.launch { onReconnected?.invoke(client) }
    }

    /**
     * Manually trigger reconnection (e.g., from a retry button)
     */
    suspend fun reconnect(): Boolean {
        autoReconnectEnabled = true

        // Detach onConnectionLost BEFORE disconnecting: the dying socket's close
        // event fires asynchronously and would otherwise run handleClientLost
        // AFTER connectFocused installs the replacement client — killing the fresh
        // connection and flipping the banner to "Unable to connect" (seen on every
        // account switch, which reconnects through here).
        val url = _currentRelayUrl.value
        val dying = focusedClient()
        dying?.onConnectionLost = null
        dying?.disconnect()
        if (dying != null) poolMutex.withLock { relayPool.remove(url) }

        val handler = currentMessageHandler ?: return false
        return connectFocused(url, handler)
    }

    /**
     * Set the connection state to Error and stop auto-reconnect for [relayUrl].
     * Used when the relay actively rejects access (e.g. "restricted"). A no-op if
     * [relayUrl] is no longer the focused relay — a stale rejection for a relay the user
     * has since switched away from must not disable auto-reconnect for the new one.
     */
    fun setError(relayUrl: String, message: String) {
        if (_currentRelayUrl.value != relayUrl.normalizeRelayUrl()) return
        autoReconnectEnabled = false
        _connectionState.value = ConnectionState.Error(message)
    }

    /**
     * Disconnect the focused relay
     */
    suspend fun disconnectFocused() {
        autoReconnectEnabled = false

        // Same orphan-callback hazard as reconnect(): a late close event from this
        // socket must not tear down whatever focused connects next.
        val url = _currentRelayUrl.value
        val client = focusedClient()
        client?.onConnectionLost = null
        client?.disconnect()
        if (client != null) poolMutex.withLock { relayPool.remove(url) }
        // Same staleness guard as connectFocused: disconnect() suspends, and focus could
        // have moved to a different relay (already Connecting/Connected) by the time it
        // returns — don't stomp that with this call's Disconnected.
        if (_currentRelayUrl.value == url) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    /**
     * Move focus to [newRelayUrl]. Every pooled client (the previously-focused relay
     * included) is already wired through the same role-agnostic [wireConnectionLost]
     * handler, so there is no promote/demote: the old relay simply keeps its pool
     * entry and starts reconnecting via the background path the next time it drops,
     * and the new relay's entry (if any) becomes focused just by updating
     * [_currentRelayUrl].
     */
    suspend fun setFocusedRelay(
        newRelayUrl: String,
        onMessage: (String, NostrGroupClient) -> Unit,
    ): Boolean {
        val normalizedNewUrl = newRelayUrl.normalizeRelayUrl()
        // Already focused on this relay: keep the existing socket. Falling through would
        // treat it as a fresh connect, which on a deep link to a group whose relay
        // initialize() already connected shows as OPEN/CLOSE/OPEN churn and, on a private
        // relay, throws away the AUTH'd socket. Just refresh the handler.
        if (_currentRelayUrl.value == normalizedNewUrl && focusedClient() != null) {
            currentMessageHandler = onMessage
            return focusedClient()?.isConnected() == true
        }

        // Capture an in-flight pool connect (the singleflight deferred in
        // pendingConnects) for the new relay before moving focus. Without this,
        // setFocusedRelay misses a connection that is mid-handshake and connectFocused
        // opens a SECOND socket to the same relay. On a private relay that splits the
        // NIP-42 handshake across two sockets (challenge on one, reads on the other),
        // so the group never authenticates. Observed as OPEN/CLOSE/OPEN churn on
        // cold-start deep links.
        val pendingConnect = poolMutex.withLock { pendingConnects[normalizedNewUrl] }

        autoReconnectEnabled = true

        _currentRelayUrl.value = normalizedNewUrl
        ActiveAccountManager.currentPubkey?.takeIf { it.isNotBlank() }?.let { pubkey ->
            SecureStorage.saveCurrentRelayUrlFor(pubkey, normalizedNewUrl)
        }

        // Reclaim the in-flight connect's client once it lands (its leader parks it in
        // relayPool on success) so it's visible as focused instead of opening a duplicate.
        pendingConnect?.await()

        if (focusedClient() != null) {
            // Already pooled (either it was already connected, or the pending connect
            // above just landed it) — just adopt it as focused. Re-check currentRelayUrl:
            // the await above suspends, and a second setFocusedRelay call could have moved
            // focus again in the meantime.
            currentMessageHandler = onMessage
            if (_currentRelayUrl.value == normalizedNewUrl) {
                _connectionState.value = ConnectionState.Connected
            }
            return true
        }

        return connectFocused(normalizedNewUrl, onMessage)
    }

    /**
     * Disconnect and remove a specific relay, whether it's in the pool or is the focused.
     */
    suspend fun disconnectRelay(url: String) {
        val normalized = url.normalizeRelayUrl()
        if (_currentRelayUrl.value == normalized) {
            disconnectFocused()
            return
        }
        // Detach onConnectionLost BEFORE disconnecting — same hazard as connectFocused's
        // and getOrConnectRelay's failed-connect paths. Without this, an intentional
        // removal (user drops the relay, account switch cleanup) fires handleClientLost()
        // and schedules a reconnect for a relay the caller just asked to disconnect.
        val client = poolMutex.withLock { relayPool.remove(normalized) }
        client?.onConnectionLost = null
        client?.disconnect()
    }

    /**
     * Get or create a connection to a relay in the pool
     */
    suspend fun getOrConnectRelay(
        relayUrl: String,
        onMessage: (String, NostrGroupClient) -> Unit,
    ): NostrGroupClient? {
        val normalized = relayUrl.normalizeRelayUrl()
        // Fast-path: already pooled. Covers the focused relay too — it lives in the
        // same map since the connection pool fold.
        poolMutex.withLock {
            relayPool[normalized]?.let { return it }
        }

        // Logged-out gate: clearAll() flips this off at the start of a logout, so
        // background discovery / DM / metadata jobs on app-lifetime scopes that
        // keep calling here for a beat after the pool is drained cannot reconnect
        // (and leak) every relay. resumeConnections() turns it back on when a
        // session activates. Existing pooled clients are still returned above.
        if (!acceptingConnections) return null

        // Singleflight gate: at most one in-flight connect per URL. Concurrent
        // callers attach to the same CompletableDeferred and share its result
        // instead of each opening their own socket.
        val deferred: kotlinx.coroutines.CompletableDeferred<NostrGroupClient?>
        val isLeader: Boolean
        poolMutex.withLock {
            // Re-check the pool under the lock — a sibling caller may have
            // resolved during our handoff between mutex acquisitions.
            relayPool[normalized]?.let { return it }
            val existing = pendingConnects[normalized]
            if (existing != null) {
                deferred = existing
                isLeader = false
            } else {
                deferred = kotlinx.coroutines.CompletableDeferred()
                pendingConnects[normalized] = deferred
                isLeader = true
            }
        }
        if (!isLeader) {
            return deferred.await()
        }

        // We're the leader. Open the socket, then publish the result.
        connStats?.onConnecting(normalized)
        var result: NostrGroupClient? = null
        var newClient: NostrGroupClient? = null
        try {
            val client = NostrGroupClient(normalized)
            newClient = client
            wireConnectionLost(client, normalized)
            client.connect { msg -> onMessage(msg, client) }
            val connected = client.waitForConnection()
            if (!connected) {
                // Detach onConnectionLost BEFORE disconnecting — same hazard connectFocused
                // already guards against. Without this, this "give up" cleanup itself fires
                // handleClientLost(), which schedules a SPURIOUS extra reconnect via
                // RelayReconnectScheduler on top of whatever this caller already does to
                // retry (MetadataManager's own attempt loop, fetchUserGroupLists, etc). If
                // this relay had a prior successful connection this session, every caller's
                // own failed attempt stacks another independent retry chain for the same
                // relay — observed as dozens of concurrent open/close cycles to one relay.
                client.onConnectionLost = null
                client.cancelAndClose()
                connStats?.onConnectFailed(normalized)
                markReachability(normalized, false)
                result = null
            } else {
                poolMutex.withLock { relayPool[normalized] = client }
                connStats?.onConnected(normalized)
                markReachability(normalized, true)
                connStats?.getStats()?.get(normalized)?.lastReconnectMs?.let { latency ->
                    adaptiveConfig?.recordRelayLatency(normalized, latency)
                }
                onRelayConnected?.invoke(normalized, client)
                result = client
            }
        } catch (e: Exception) {
            newClient?.onConnectionLost = null
            newClient?.cancelAndClose()
            connStats?.onConnectFailed(normalized)
            markReachability(normalized, false)
            result = null
        } finally {
            poolMutex.withLock { pendingConnects.remove(normalized) }
            deferred.complete(result)
        }
        return result
    }

    /**
     * Send a message to multiple relays in the pool
     */
    fun sendToRelays(
        relayUrls: List<String>,
        message: String,
        onMessage: (String, NostrGroupClient) -> Unit,
    ) {
        relayUrls.forEach { relayUrl ->
            connectionWorkScope.launch {
                try {
                    val client = getOrConnectRelay(relayUrl, onMessage)
                    client?.send(message)
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Connect to multiple relays in parallel
     */
    suspend fun connectToRelaysParallel(
        relayUrls: List<String>,
        onMessage: (String, NostrGroupClient) -> Unit,
        timeoutMs: Long = 2000,
    ) {
        relayUrls.map { relayUrl ->
            connectionWorkScope.launch {
                try {
                    getOrConnectRelay(relayUrl, onMessage)
                } catch (_: Exception) {
                }
            }
        }

        // Wait briefly for at least one connection
        withTimeoutOrNull(timeoutMs) {
            while (!hasPoolConnections()) {
                delay(50)
            }
        }
    }

    /**
     * Check if any relay is connected in the pool
     */
    suspend fun hasPoolConnections(): Boolean = poolMutex.withLock {
        relayPool.isNotEmpty()
    }

    /**
     * CLOSE every live subscription on every open socket (focused + pool). Called on account
     * switch so the outgoing account's mux chat/meta and dm_inbox REQs stop pushing events
     * into the incoming account's freshly-cleared state (those subs are group/kind-filtered,
     * not pubkey-filtered, so they would otherwise re-populate the new account's rail/DM list
     * with the old account's data). Sockets stay connected; the new account re-subscribes.
     */
    suspend fun closeAllSubscriptionsOnAllClients() {
        val clients = poolMutex.withLock { relayPool.values.toList() }
        for (client in clients) {
            try {
                client.closeAllSubscriptions()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Drop every socket that answered NIP-42 as an account other than [pubkey].
     *
     * A relay serves reads under the identity that AUTH'd the socket. After an account swap the
     * old sockets are still authenticated as the outgoing account, so the incoming account's REQ
     * on one of them comes back with events it must not see (a private group only the previous
     * account belongs to reads as fully visible). Closing subscriptions is not enough — the next
     * REQ over the same socket is answered under the same stale identity. NIP-42 is reactive, so
     * only a fresh socket produces a challenge the new account can answer.
     *
     * Public sockets (never challenged) are left alone. Reconnecting is the caller's job: the
     * pool reopens them on demand. Returns the normalized URLs that were dropped.
     */
    suspend fun disconnectSocketsAuthedAsOther(pubkey: String?): List<String> {
        // Collect under the lock, disconnect outside it: disconnectRelay takes the same mutex.
        val stale =
            poolMutex.withLock {
                relayPool.entries.filter { (_, client) -> client.isAuthedAsOther(pubkey) }.map { it.key }
            }
        for (url in stale) {
            try {
                disconnectRelay(url)
            } catch (_: Exception) {
            }
        }
        return stale
    }

    /**
     * Clear all connections, disconnecting pool clients in parallel.
     */
    suspend fun clearAll() {
        // Stop accepting NEW pool connects for the rest of the teardown so a
        // discovery/DM/metadata job on a surviving scope can't reconnect a relay
        // right after we drain the pool. resumeConnections() re-opens on login.
        acceptingConnections = false
        // Cancel only the connection work (in-flight connects, reconnect retries) —
        // never the shared app scope's children (see connectionWorkScope).
        connectionWorkScope.coroutineContext.cancelChildren()
        disconnectFocused()

        // Get clients to disconnect while holding lock, then disconnect outside lock
        val clientsToDisconnect =
            poolMutex.withLock {
                val clients = relayPool.values.toList()
                relayPool.clear()
                clients
            }
        // Parallel disconnect — avoids multiplying per-relay close latency. Detach
        // onConnectionLost first, same hazard as every other intentional-disconnect site.
        clientsToDisconnect
            .map { client ->
                connectionWorkScope.launch {
                    try {
                        client.onConnectionLost = null
                        client.disconnect()
                    } catch (_: Exception) {
                    }
                }
            }.forEach { it.join() }
    }
}
