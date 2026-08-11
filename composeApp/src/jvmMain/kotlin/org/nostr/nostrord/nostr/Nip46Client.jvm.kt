package org.nostr.nostrord.nostr

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.PublishResult
import org.nostr.nostrord.network.sendAndAwaitOkOrError
import org.nostr.nostrord.network.summarizeFailures
import org.nostr.nostrord.utils.epochMillis
import kotlin.random.Random

// Per-relay WebSocket connect timeout for the NIP-46 signer relays. Longer than
// NostrGroupClient's 7s default: the QR/bunker flow runs while the account's own
// relay sockets are (re)connecting, and a cold handshake can exceed 7s under that
// load. A dropped signer relay here silently loses the signer's connect response
// ("Waiting for signer..." forever) - mirrors the web's 20s timeout.
private const val SIGNER_RELAY_CONNECT_TIMEOUT_MS = 20_000L

// After the first signer relay is listening, how long the QR flow waits for the
// rest before drawing the code. kind:24133 is ephemeral, so a connect event the
// signer publishes to a relay we are not subscribed on is lost for good and the
// screen waits for a signer that already answered. Short enough to stay
// imperceptible when every relay is healthy, long enough to cover a slow one.
private const val REMAINING_RELAY_GRACE_MS = 2_500L

// Backoff bounds for re-opening a signer relay that dropped mid-session.
private const val RELAY_RECONNECT_BASE_DELAY_MS = 1_000L
private const val RELAY_RECONNECT_MAX_DELAY_MS = 15_000L

actual class Nip46Client actual constructor(
    existingPrivateKey: String?,
) {
    private val clientKeyPair: KeyPair =
        if (existingPrivateKey != null) {
            KeyPair.fromPrivateKeyHex(existingPrivateKey)
        } else {
            KeyPair.generate()
        }

    private var remoteSignerPubkey: String? = null

    // Every read is a snapshot and every mutation goes through addClient /
    // removeClient under this lock: the list is touched from the ws frame
    // threads (drop watch), the connect fan-out and concurrent sendRequests, so
    // an unguarded iteration throws ConcurrentModificationException and fails a
    // request that had nothing wrong with it.
    private val relayClients: MutableList<NostrGroupClient> = mutableListOf()

    private fun clientsSnapshot(): List<NostrGroupClient> = synchronized(relayClients) { relayClients.toList() }

    private fun addClient(client: NostrGroupClient) {
        synchronized(relayClients) { relayClients.add(client) }
    }

    private fun removeClient(client: NostrGroupClient) {
        synchronized(relayClients) { relayClients.remove(client) }
    }

    // Set by disconnect(); stops the drop watch from resurrecting sockets.
    @kotlin.concurrent.Volatile
    private var closed = false

    // Guards ensureRelaysConnected's check-then-act (see its own doc comment):
    // without it, concurrent sendRequest calls racing on an empty relayClients
    // (e.g. a DM-decrypt backlog burst) each independently reconnect and append,
    // piling up duplicate sockets to the same relay that every future
    // sendRequest then also publishes the same signed event to.
    private val relayConnectMutex = Mutex()
    private var relayUrls: List<String> = emptyList()
    private val pendingRequests: MutableMap<String, CompletableDeferred<String>> = java.util.concurrent.ConcurrentHashMap()

    // Shared pacing + rate-limit backoff for every request publish (see Nip46PublishPacer).
    private val publishPacer = Nip46PublishPacer()
    private var responseSubscriptionId: String? = null
    private var nostrConnectSecret: String? = null

    // networkClientDispatcher, NOT Default: ws handshakes park in Ktor's generateNonce
    // runBlocking and can deadlock the Default pool (see utils/NetworkDispatcher.kt).
    private val clientScope = CoroutineScope(SupervisorJob() + org.nostr.nostrord.utils.networkClientDispatcher)
    private val nip46Json = Json { ignoreUnknownKeys = true }

    actual var onAuthUrl: ((String) -> Unit)? = null
    actual val clientPubkey: String get() = clientKeyPair.publicKeyHex
    actual val clientPrivateKey: String get() = clientKeyPair.privateKeyHex

    actual fun generateNostrConnectUri(
        relays: List<String>,
        name: String,
    ): String {
        // Advertise only the relays we are actually subscribed on. kind:24133 is
        // ephemeral: a connect event published to a relay we are not listening on
        // is dropped by the relay, not replayed later, and the QR screen then waits
        // forever for a signer that already answered.
        val advertised = clientsSnapshot()
            .map { it.getRelayUrl() }
            .ifEmpty { relays.map { it.trimEnd('/') }.distinct() }
        val relayParams = advertised.joinToString("&") { "relay=${it.encodeForUri()}" }
        val metadata = """{"name":"$name"}"""
        val secretParam = nostrConnectSecret?.let { "&secret=${it.encodeForUri()}" } ?: ""
        val permsParam = "&perms=${NIP46_REQUESTED_PERMS.encodeForUri()}"
        val uri = "nostrconnect://${clientKeyPair.publicKeyHex}?$relayParams$secretParam$permsParam&metadata=${metadata.encodeForUri()}"
        return uri
    }

    /**
     * Opens one signer relay end to end: WebSocket, durable response
     * subscription, drop watch. Returns null when either the socket never
     * opened or the REQ could not be sent — a socket with no live response sub
     * is deaf, and counting it as a connection is what left the QR screen
     * waiting for a signer event that could never be delivered. The caller owns
     * adding the result to [relayClients].
     */
    private suspend fun openRelay(relayUrl: String): NostrGroupClient? = try {
        val client = NostrGroupClient(relayUrl)
        client.connect { msg -> handleMessage(msg, client) }
        if (!client.waitForConnection(SIGNER_RELAY_CONNECT_TIMEOUT_MS) || !openResponseSubscription(client)) {
            client.disconnect()
            null
        } else {
            armDropWatch(client, relayUrl)
            client
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        null
    }

    /**
     * Re-open [relayUrl] when its socket drops. Without this the response
     * subscription dies with the socket and nothing notices: the only liveness
     * check is [ensureRelaysConnected] at the head of the next request, and the
     * QR wait issues no requests at all, so the signer's reply lands on a relay
     * we left.
     */
    private fun armDropWatch(client: NostrGroupClient, relayUrl: String) {
        client.onConnectionLost = {
            removeClient(client)
            clientScope.launch { reconnectRelay(relayUrl) }
        }
    }

    private suspend fun reconnectRelay(relayUrl: String) {
        var delayMs = RELAY_RECONNECT_BASE_DELAY_MS
        while (!closed) {
            delay(delayMs)
            if (closed) return
            addRelays(listOf(relayUrl))
            if (clientsSnapshot().any { it.getRelayUrl() == relayUrl }) return
            delayMs = minOf(delayMs * 2, RELAY_RECONNECT_MAX_DELAY_MS)
        }
    }

    /**
     * Connect [relays] and register them, skipping any already connected, under
     * [relayConnectMutex]. Every path that grows [relayClients] goes through
     * here: a session restore's connect racing the first request's
     * [ensureRelaysConnected] had both running their own fan-out and both
     * appending, leaving two live sockets to one relay. Each request was then
     * published twice and each signer reply delivered twice, which is what
     * earned the "rate-limited: you are noting too much" rejections and got the
     * relay to drop the socket mid-QR-wait.
     */
    private suspend fun addRelays(relays: List<String>) {
        relayConnectMutex.withLock {
            val known = clientsSnapshot().mapTo(mutableSetOf()) { it.getRelayUrl() }
            val missing = relays.map { it.trimEnd('/') }.distinct().filterNot { it in known }
            if (missing.isEmpty()) return@withLock
            connectRelaysParallel(missing).forEach { addClient(it) }
        }
    }

    private suspend fun connectRelaysParallel(relays: List<String>): List<NostrGroupClient> = coroutineScope {
        // Dedupe: a repeated relay= param (or the same relay with/without a trailing
        // slash) would otherwise open two sockets to it, and every later sendRequest
        // publishes to both — see relayConnectMutex's doc comment for the full story.
        relays
            .map { it.trimEnd('/') }
            .distinct()
            .map { relayUrl -> async { openRelay(relayUrl) } }
            .awaitAll()
            .filterNotNull()
    }

    /**
     * QR-flow variant of [connectRelaysParallel]. Unblocks as soon as one relay
     * is listening, then gives the rest [REMAINING_RELAY_GRACE_MS] to join
     * before the caller draws the code, so the advertised relay set matches the
     * set we can actually hear on. Throws only if every relay fails.
     */
    private suspend fun connectRelaysFirstWins(relaysIn: List<String>) {
        val relays = relaysIn.map { it.trimEnd('/') }.distinct()
        if (relays.isEmpty()) throw Exception("Failed to connect to any relay")
        val firstReady = CompletableDeferred<Unit>()
        val allSettled = CompletableDeferred<Unit>()
        val total = relays.size
        val settled = java.util.concurrent.atomic.AtomicInteger(0)

        for (relayUrl in relays) {
            clientScope.launch {
                val client = openRelay(relayUrl)
                if (client != null) {
                    addClient(client)
                    firstReady.complete(Unit)
                }
                if (settled.incrementAndGet() == total) {
                    if (!firstReady.isCompleted) {
                        firstReady.completeExceptionally(Exception("Failed to connect to any relay"))
                    }
                    allSettled.complete(Unit)
                }
            }
        }

        firstReady.await()
        withTimeoutOrNull(REMAINING_RELAY_GRACE_MS) { allSettled.await() }
    }

    /**
     * Fire `get_public_key` in the background and cache the in-flight
     * [CompletableDeferred] under [pendingRequests]'s special slot. Subsequent
     * calls to [getPublicKey] await this deferred instead of starting a fresh
     * round trip. Safe to call multiple times — putIfAbsent ensures only the
     * first call kicks off the RPC.
     */
    private fun prefetchUserPubkey() {
        val deferred = CompletableDeferred<String>()
        val existing = pendingRequests.putIfAbsent("_pending_user_pubkey", deferred)
        if (existing != null) return
        clientScope.launch {
            // No extra timeout here: the signer-side delay is often a user-tap
            // approval prompt that can legitimately take tens of seconds. The
            // underlying sendRequest already caps at 120s, which is the right
            // ceiling for an interactive flow — a 10s wall here would mistake
            // a slow human for a failed signer and abort the entire login.
            try {
                val pk = sendRequest(generateRequestId(), "get_public_key", emptyList())
                deferred.complete(pk)
            } catch (e: Exception) {
                deferred.completeExceptionally(e)
            }
        }
    }

    /**
     * Opens the durable NIP-46 response subscription on [client]. The filter
     * (`kinds:[24133], #p:[clientPubkey]`) covers both incoming `connect`
     * requests (nostrconnect:// flow) and signer replies (bunker:// flow), so
     * one stable subscription replaces the previous per-request ephemeral REQs.
     * Returns false when the REQ could not be sent, so the caller drops a socket
     * that would otherwise sit in [relayClients] connected but deaf.
     */
    private suspend fun openResponseSubscription(client: NostrGroupClient): Boolean {
        val subId = responseSubscriptionId
            ?: "nip46-resp-${clientKeyPair.publicKeyHex.take(8)}".also { responseSubscriptionId = it }
        val since = (epochMillis() / 1000) - 10
        val filter = buildJsonObject {
            putJsonArray("kinds") { add(24133) }
            putJsonArray("#p") { add(clientKeyPair.publicKeyHex) }
            put("since", since)
        }
        val req = buildJsonArray {
            add("REQ")
            add(subId)
            add(filter)
        }.toString()
        return try {
            client.send(req)
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }
    }

    /**
     * Ensure at least one relay client is connected before sending a request.
     * Disconnected clients are replaced with fresh connections.
     */
    private suspend fun ensureRelaysConnected() {
        relayConnectMutex.withLock {
            // Remove dead clients
            val dead = clientsSnapshot().filter { !it.isConnected() }
            dead.forEach {
                removeClient(it)
                try {
                    it.disconnect()
                } catch (_: Exception) {
                }
            }

            // If we still have live clients, we're good
            if (clientsSnapshot().isNotEmpty()) return@withLock

            // Reconnect using stored relay URLs
            if (relayUrls.isEmpty()) return@withLock
            connectRelaysParallel(relayUrls).forEach { addClient(it) }
        }
    }

    actual suspend fun connectRelaysOnly(
        remoteSignerPubkey: String,
        relays: List<String>,
    ) {
        this.remoteSignerPubkey = remoteSignerPubkey
        this.relayUrls = relays.map { it.trimEnd('/') }.distinct()
        addRelays(relays)
        if (clientsSnapshot().isEmpty()) throw Exception("Failed to connect to any bunker relay")
    }

    actual suspend fun startListeningForConnection(
        relays: List<String>,
        secret: String?,
    ) {
        nostrConnectSecret = secret ?: generateRequestId().take(16)
        this.relayUrls = relays.map { it.trimEnd('/') }.distinct()

        // Install the listening deferred BEFORE launching connects so that
        // handleMessage can complete it as soon as the first relay starts
        // delivering events — first-relay-wins must not race the dispatch.
        pendingRequests["_incoming_connect"] = CompletableDeferred()

        // Unblock on the first listening relay, then let the rest join within the
        // grace window before the caller builds the URI: kind:24133 is ephemeral,
        // so a relay that subscribes after the signer published replays nothing.
        connectRelaysFirstWins(relays)
    }

    actual suspend fun awaitIncomingConnection(): String {
        val connectDeferred =
            pendingRequests["_incoming_connect"]
                ?: throw Exception("Not listening. Call startListeningForConnection first.")

        return try {
            // No wall-clock timeout: the QR sheet lifecycle drives cancellation
            // via the calling coroutine. The listen subscription stays open
            // until disconnect(), so a late scanner still completes instead of
            // being killed by a 120s deadline.
            connectDeferred.await()
        } finally {
            pendingRequests.remove("_incoming_connect")
        }
    }

    actual suspend fun connect(
        remoteSignerPubkey: String,
        relays: List<String>,
        secret: String?,
    ): String {
        this.remoteSignerPubkey = remoteSignerPubkey
        this.relayUrls = relays.map { it.trimEnd('/') }.distinct()

        addRelays(relays)

        if (clientsSnapshot().isEmpty()) {
            throw Exception("Failed to connect to any bunker relay")
        }

        val requestId = generateRequestId()
        // NIP-46 connect params: [remote_signer_pubkey, secret, requested_permissions]. The secret
        // slot must be present (empty string if none) so the signer reads perms as the 3rd param.
        val params =
            buildList {
                add(remoteSignerPubkey)
                add(secret ?: "")
                add(NIP46_REQUESTED_PERMS)
            }

        sendRequest(requestId, "connect", params)

        return remoteSignerPubkey
    }

    actual suspend fun getPublicKey(): String {
        // In the nostrconnect:// flow, handleMessage pre-fires get_public_key
        // the moment the signer's connect event arrives, so this await almost
        // always returns the cached result instead of paying another full
        // signer round trip.
        pendingRequests["_pending_user_pubkey"]?.let { return it.await() }
        val requestId = generateRequestId()
        return sendRequest(requestId, "get_public_key", emptyList())
    }

    actual suspend fun signEvent(eventJson: String): String {
        val requestId = generateRequestId()
        return sendRequest(requestId, "sign_event", listOf(eventJson))
    }

    actual suspend fun nip44Encrypt(peerPubkey: String, plaintext: String): String = sendRequest(generateRequestId(), "nip44_encrypt", listOf(peerPubkey, plaintext))

    actual suspend fun nip44Decrypt(peerPubkey: String, ciphertext: String): String = sendRequest(generateRequestId(), "nip44_decrypt", listOf(peerPubkey, ciphertext))

    private suspend fun sendRequest(
        requestId: String,
        method: String,
        params: List<String>,
    ): String = withTimeout(120_000) {
        val signerPubkey =
            remoteSignerPubkey
                ?: throw Exception("Not connected to signer")

        // Ensure relay connections are alive before sending
        ensureRelaysConnected()
        if (clientsSnapshot().isEmpty()) {
            throw Exception("No bunker relay connections available")
        }

        val requestJson =
            buildJsonObject {
                put("id", requestId)
                put("method", method)
                putJsonArray("params") { params.forEach { add(it) } }
            }.toString()

        val encryptedContent =
            Nip44.encrypt(
                plaintext = requestJson,
                privateKeyHex = clientKeyPair.privateKeyHex,
                pubKeyHex = signerPubkey,
            )

        // Background lane = the DM gift-wrap decrypt backlog, the one flood source. It is
        // paced, windowed and cooled down; interactive requests (login handshake, AUTH,
        // user-action signs/encrypts) publish immediately so they never queue behind it.
        val background = method.startsWith("nip44_decrypt")
        // In-flight window: pauses new background requests when the signer stops answering
        // (its own response publishes got rate-limited, which our OK-based backoff cannot
        // see). created_at is stamped after the slot wait so a long pause cannot expire the
        // event.
        publishPacer.withRequestSlot(background) {
            val requestStartMs = epochMillis()
            val event =
                Event(
                    pubkey = clientKeyPair.publicKeyHex,
                    createdAt = epochMillis() / 1000,
                    kind = 24133,
                    tags = listOf(listOf("p", signerPubkey)),
                    content = encryptedContent,
                )

            val signedEvent = event.sign(clientKeyPair)
            val eventId = signedEvent.id
                ?: throw Exception("Failed to sign NIP-46 request event")
            val responseDeferred = CompletableDeferred<String>()
            pendingRequests[requestId] = responseDeferred

            val eventMessage =
                buildJsonArray {
                    add("EVENT")
                    add(signedEvent.toJsonObject())
                }.toString()

            try {
                // Publish in parallel via sendAndAwaitOk so a relay-side rejection
                // surfaces immediately. The response sub opened on connect routes
                // the signer's reply back into responseDeferred. A rate-limited
                // publish retries under the pacer's escalating cooldown until this
                // request's own deadline: failing hard reads as "signer unreachable"
                // upstream and tears the session down, when the relay only asked us
                // to slow down. Any other rejection throws immediately.
                while (true) {
                    publishPacer.awaitTurn(background)
                    // Re-read: a paced request can wait minutes for its turn, and
                    // a disconnect (logout) in that window leaves nothing to
                    // publish to. Reporting that as "rejected by every relay"
                    // reads as a signer fault in the logs when it is a teardown.
                    val targets = clientsSnapshot()
                    if (targets.isEmpty()) {
                        throw Exception("No bunker relay connections available")
                    }
                    val publishResults = targets.map { client ->
                        async { client.sendAndAwaitOkOrError(eventMessage, eventId) }
                    }.awaitAll()
                    if (publishResults.any { it is PublishResult.Success }) {
                        publishPacer.noteAccepted()
                        break
                    }
                    val rateLimited = publishResults.any {
                        it is PublishResult.Rejected && Nip46PublishPacer.isRateLimitReason(it.reason)
                    }
                    if (!rateLimited) {
                        throw Exception("Failed to publish NIP-46 request: ${publishResults.summarizeFailures()}")
                    }
                    publishPacer.noteRateLimited()
                }
                try {
                    val response = responseDeferred.await()
                    if (background) publishPacer.noteResponseArrived(latencyMs = epochMillis() - requestStartMs)
                    response
                } catch (e: CancellationException) {
                    // Died unanswered (timeout/cancel): the signer or its relay path is behind.
                    if (background) publishPacer.noteResponseLost()
                    throw e
                }
            } finally {
                pendingRequests.remove(requestId)
            }
        }
    }

    private fun handleMessage(msg: String, source: NostrGroupClient) {
        try {
            val json = nip46Json
            val arr = json.parseToJsonElement(msg).jsonArray
            val msgType = arr.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return

            when (msgType) {
                "OK" -> {
                    // Route relay's publish ACK into the source client so that
                    // sendAndAwaitOk completes with the actual relay verdict
                    // instead of timing out. OK false → PublishResult.Rejected.
                    val parsed = source.parseOkMessage(arr) ?: return
                    val (eventId, success, message) = parsed
                    clientScope.launch { source.handleOkResponse(eventId, success, message) }
                    return
                }
                "NOTICE" -> {
                    val notice = arr.getOrNull(1)?.jsonPrimitive?.contentOrNull
                    if (!notice.isNullOrBlank()) {
                        println("[Nip46Client] NOTICE from ${source.getRelayUrl()}: $notice")
                    }
                    return
                }
                "EOSE" -> {
                    // Catch-up boundary for the listening sub. No state to
                    // advance yet, but the frame is no longer silently dropped.
                    return
                }
            }

            if (msgType == "EVENT" && arr.size >= 3) {
                val eventObj = arr[2].jsonObject
                val kind = eventObj["kind"]?.jsonPrimitive?.int ?: return

                if (kind != 24133) {
                    return
                }

                val eventPubkey = eventObj["pubkey"]?.jsonPrimitive?.content ?: return
                val encryptedContent = eventObj["content"]?.jsonPrimitive?.content ?: return

                try {
                    val decrypted =
                        decryptMessage(
                            ciphertext = encryptedContent,
                            privateKeyHex = clientKeyPair.privateKeyHex,
                            pubKeyHex = eventPubkey,
                        )
                    val responseObj = json.parseToJsonElement(decrypted).jsonObject
                    val responseId = responseObj["id"]?.jsonPrimitive?.content
                    val method = responseObj["method"]?.jsonPrimitive?.contentOrNull
                    val result = responseObj["result"]?.jsonPrimitive?.contentOrNull
                    val error = responseObj["error"]?.jsonPrimitive?.contentOrNull

                    // Handle incoming connect request (nostrconnect:// flow)
                    if (method == "connect") {
                        val params = responseObj["params"]?.jsonArray
                        val incomingSecret = params?.getOrNull(1)?.jsonPrimitive?.contentOrNull
                        val expectedSec = nostrConnectSecret
                        if (expectedSec != null && incomingSecret != null && incomingSecret != expectedSec) {
                            return // reject: secret mismatch
                        }

                        remoteSignerPubkey = eventPubkey

                        clientScope.launch {
                            try {
                                val ackResponse =
                                    buildJsonObject {
                                        responseId?.let { put("id", it) }
                                        put("result", "ack")
                                    }.toString()
                                val ackEncrypted = Nip44.encrypt(ackResponse, clientKeyPair.privateKeyHex, eventPubkey)
                                val ackEvent =
                                    Event(
                                        pubkey = clientKeyPair.publicKeyHex,
                                        createdAt = epochMillis() / 1000,
                                        kind = 24133,
                                        tags = listOf(listOf("p", eventPubkey)),
                                        content = ackEncrypted,
                                    ).sign(clientKeyPair)
                                val ackMessage =
                                    buildJsonArray {
                                        add("EVENT")
                                        add(ackEvent.toJsonObject())
                                    }.toString()
                                clientsSnapshot().forEach {
                                    try {
                                        it.send(ackMessage)
                                    } catch (e: Exception) {
                                    }
                                }
                            } catch (e: Exception) {
                            }
                        }

                        // Pipeline get_public_key: kick it off the instant the
                        // signer connects so the caller's subsequent
                        // getPublicKey() returns the cached result instead of
                        // paying another full signer round trip. putIfAbsent
                        // guards against duplicate fires when multiple relays
                        // deliver the same connect event.
                        prefetchUserPubkey()

                        val deferred = pendingRequests["_incoming_connect"]
                        deferred?.complete(eventPubkey)
                        return
                    }

                    // Handle signer connect response in nostrconnect:// flow.
                    // Result must equal the secret we generated (per NIP-46 spec).
                    val isConnectResponse =
                        pendingRequests.containsKey("_incoming_connect") &&
                            nostrConnectSecret != null &&
                            result == nostrConnectSecret
                    if (isConnectResponse) {
                        remoteSignerPubkey = eventPubkey
                        prefetchUserPubkey()
                        pendingRequests["_incoming_connect"]?.complete(eventPubkey)
                        return
                    }

                    if (result == "auth_url" && error != null) {
                        onAuthUrl?.invoke(error)
                        return
                    }

                    if (remoteSignerPubkey == null) {
                        remoteSignerPubkey = eventPubkey
                    }

                    if (responseId == null) {
                        return
                    }
                    val deferred = pendingRequests[responseId]
                    if (deferred == null) {
                        return
                    }

                    if (!error.isNullOrBlank() && result != "auth_url") {
                        deferred.completeExceptionally(Exception(error))
                        return
                    }

                    deferred.complete(result ?: "")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun decryptMessage(
        ciphertext: String,
        privateKeyHex: String,
        pubKeyHex: String,
    ): String = if (ciphertext.contains("?iv=")) {
        Nip04.decrypt(ciphertext, privateKeyHex, pubKeyHex)
    } else {
        Nip44.decrypt(ciphertext, privateKeyHex, pubKeyHex)
    }

    private fun generateRequestId(): String = Random.nextBytes(16).joinToString("") {
        it.toUByte().toString(16).padStart(2, '0')
    }

    actual fun backgroundConnect(
        secret: String?,
        onSuccess: (() -> Unit)?,
        onRevoked: (() -> Unit)?,
    ) {
        val signerPubkey = remoteSignerPubkey ?: return
        clientScope.launch {
            try {
                val requestId = generateRequestId()
                val params =
                    buildList {
                        add(signerPubkey)
                        secret?.let { add(it) }
                    }
                withTimeout(10_000) {
                    sendRequest(requestId, "connect", params)
                }
                onSuccess?.invoke()
            } catch (_: Exception) {
                // Timeout or explicit rejection → treat as revoked.
                onRevoked?.invoke()
            }
        }
    }

    actual fun disconnect() {
        closed = true
        clientScope.coroutineContext.cancelChildren()
        val open = synchronized(relayClients) { relayClients.toList().also { relayClients.clear() } }
        open.forEach { client ->
            // Drop the watch first: a graceful close must not schedule a reconnect.
            client.onConnectionLost = null
            clientScope.launch {
                try {
                    client.disconnect()
                } catch (_: Exception) {
                }
            }
        }
        // Fail any in-flight RPCs so callers unblock immediately instead of
        // waiting out their wrapping withTimeout.
        pendingRequests.values.forEach { it.completeExceptionally(CancellationException("Nip46Client disconnected")) }
        pendingRequests.clear()
    }
}

private fun String.encodeForUri(): String = buildString {
    for (c in this@encodeForUri) {
        when {
            c.isLetterOrDigit() || c in "-_.~" -> append(c)
            else -> {
                for (b in c.toString().encodeToByteArray()) {
                    append('%')
                    append((b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
                }
            }
        }
    }
}
