package org.nostr.nostrord.nostr

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.PublishResult
import org.nostr.nostrord.network.sendAndAwaitOkOrError
import org.nostr.nostrord.network.summarizeFailures
import org.nostr.nostrord.utils.epochMillis
import kotlin.random.Random

// Mirrors the jvm actual; only the JVM concurrency primitives are swapped for
// atomicfu (ConcurrentHashMap -> lock-guarded map, AtomicInteger -> atomic).
private class ConcurrentMap<K, V> : SynchronizedObject() {
    private val map = mutableMapOf<K, V>()

    operator fun get(key: K): V? = synchronized(this) { map[key] }

    operator fun set(key: K, value: V) {
        synchronized(this) { map[key] = value }
    }

    fun putIfAbsent(key: K, value: V): V? = synchronized(this) {
        map[key] ?: run {
            map[key] = value
            null
        }
    }

    fun remove(key: K): V? = synchronized(this) { map.remove(key) }

    fun containsKey(key: K): Boolean = synchronized(this) { key in map }

    val values: List<V> get() = synchronized(this) { map.values.toList() }

    fun clear() = synchronized(this) { map.clear() }
}

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
    private val relayClientsLock = SynchronizedObject()
    private var relayClients: MutableList<NostrGroupClient> = mutableListOf()
    private var relayUrls: List<String> = emptyList()
    private val pendingRequests = ConcurrentMap<String, CompletableDeferred<String>>()

    // Shared pacing + rate-limit backoff for every request publish (see Nip46PublishPacer).
    private val publishPacer = Nip46PublishPacer()
    private var responseSubscriptionId: String? = null
    private var nostrConnectSecret: String? = null

    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nip46Json = Json { ignoreUnknownKeys = true }

    actual var onAuthUrl: ((String) -> Unit)? = null
    actual val clientPubkey: String get() = clientKeyPair.publicKeyHex
    actual val clientPrivateKey: String get() = clientKeyPair.privateKeyHex

    actual fun generateNostrConnectUri(
        relays: List<String>,
        name: String,
    ): String {
        val relayParams = relays.joinToString("&") { "relay=${it.encodeForUri()}" }
        val metadata = """{"name":"$name"}"""
        val secretParam = nostrConnectSecret?.let { "&secret=${it.encodeForUri()}" } ?: ""
        val permsParam = "&perms=${NIP46_REQUESTED_PERMS.encodeForUri()}"
        val uri = "nostrconnect://${clientKeyPair.publicKeyHex}?$relayParams$secretParam$permsParam&metadata=${metadata.encodeForUri()}"
        return uri
    }

    private suspend fun connectRelaysParallel(relays: List<String>): List<NostrGroupClient> = coroutineScope {
        relays
            .map { relayUrl ->
                async {
                    try {
                        val cleanUrl = relayUrl.trimEnd('/')
                        val client = NostrGroupClient(cleanUrl)
                        client.connect { msg -> handleMessage(msg, client) }
                        if (!client.waitForConnection()) {
                            // WS never opened (timeout) — drop it so callers don't
                            // treat a dead socket as a live relay connection.
                            client.disconnect()
                            null
                        } else {
                            openResponseSubscription(client)
                            client
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll()
            .filterNotNull()
    }

    /**
     * First-relay-wins variant of [connectRelaysParallel]. Returns as soon as
     * one relay's WebSocket is open and the durable response sub is sent.
     * Remaining relays continue connecting in [clientScope] and are appended
     * to [relayClients] as they become ready, so the listening sub spans all
     * relays even when this function has already returned. Throws only if
     * every relay fails — partial failure is acceptable.
     */
    private suspend fun connectRelaysFirstWins(relays: List<String>) {
        if (relays.isEmpty()) throw Exception("Failed to connect to any relay")
        val firstReady = CompletableDeferred<Unit>()
        val total = relays.size
        val failures = atomic(0)

        for (relayUrl in relays) {
            clientScope.launch {
                try {
                    val cleanUrl = relayUrl.trimEnd('/')
                    val client = NostrGroupClient(cleanUrl)
                    client.connect { msg -> handleMessage(msg, client) }
                    if (!client.waitForConnection()) {
                        // WS never opened — drop it (mirrors connectRelaysParallel)
                        // instead of adding a dead socket and completing firstReady.
                        // Otherwise the QR displays while no listening sub is live,
                        // so the signer's connect event never arrives and the user
                        // has to restart the app. Common right after a logout, which
                        // tears down many sockets at once and slows the next connect.
                        client.disconnect()
                        if (failures.incrementAndGet() == total && !firstReady.isCompleted) {
                            firstReady.completeExceptionally(Exception("Failed to connect to any relay"))
                        }
                        return@launch
                    }
                    openResponseSubscription(client)
                    synchronized(relayClientsLock) { relayClients.add(client) }
                    firstReady.complete(Unit)
                } catch (_: Exception) {
                    if (failures.incrementAndGet() == total && !firstReady.isCompleted) {
                        firstReady.completeExceptionally(Exception("Failed to connect to any relay"))
                    }
                }
            }
        }

        firstReady.await()
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
     */
    private suspend fun openResponseSubscription(client: NostrGroupClient) {
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
        try {
            client.send(req)
        } catch (_: Exception) {
        }
    }

    /**
     * Ensure at least one relay client is connected before sending a request.
     * Disconnected clients are replaced with fresh connections.
     */
    private suspend fun ensureRelaysConnected() {
        // Remove dead clients
        val dead = relayClients.filter { !it.isConnected() }
        if (dead.isNotEmpty()) {
            dead.forEach {
                try {
                    it.disconnect()
                } catch (_: Exception) {
                }
            }
            relayClients.removeAll(dead)
        }

        // If we still have live clients, we're good
        if (relayClients.isNotEmpty()) return

        // Reconnect using stored relay URLs
        if (relayUrls.isEmpty()) return
        val fresh = connectRelaysParallel(relayUrls)
        relayClients.addAll(fresh)
    }

    actual suspend fun connectRelaysOnly(
        remoteSignerPubkey: String,
        relays: List<String>,
    ) {
        this.remoteSignerPubkey = remoteSignerPubkey
        this.relayUrls = relays.map { it.trimEnd('/') }
        relayClients.addAll(connectRelaysParallel(relays))
        if (relayClients.isEmpty()) throw Exception("Failed to connect to any bunker relay")
    }

    actual suspend fun startListeningForConnection(
        relays: List<String>,
        secret: String?,
    ) {
        nostrConnectSecret = secret ?: generateRequestId().take(16)
        this.relayUrls = relays.map { it.trimEnd('/') }

        // Install the listening deferred BEFORE launching connects so that
        // handleMessage can complete it as soon as the first relay starts
        // delivering events — first-relay-wins must not race the dispatch.
        pendingRequests["_incoming_connect"] = CompletableDeferred()

        // Return as soon as one relay is listening. Remaining relays finish
        // in background; the durable sub on each picks up late-arriving
        // signer events thanks to the `since = now - 10s` filter.
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
        this.relayUrls = relays.map { it.trimEnd('/') }

        relayClients.addAll(connectRelaysParallel(relays))

        if (relayClients.isEmpty()) {
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
        if (relayClients.isEmpty()) {
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
            // the signer's reply back into responseDeferred. publishPacer spaces
            // the publishes and, when the relay rate-limits, holds a shared
            // cooldown and retries; it is never held across the response await.
            var attempt = 1
            while (true) {
                publishPacer.awaitTurn()
                val publishResults = relayClients.map { client ->
                    async { client.sendAndAwaitOkOrError(eventMessage, eventId) }
                }.awaitAll()
                if (publishResults.any { it is PublishResult.Success }) {
                    publishPacer.noteAccepted()
                    break
                }
                val rateLimited = publishResults.any {
                    it is PublishResult.Rejected && Nip46PublishPacer.isRateLimitReason(it.reason)
                }
                if (rateLimited) publishPacer.noteRateLimited()
                if (!rateLimited || attempt >= Nip46PublishPacer.MAX_PUBLISH_ATTEMPTS) {
                    throw Exception("Failed to publish NIP-46 request: ${publishResults.summarizeFailures()}")
                }
                attempt++
            }
            responseDeferred.await()
        } finally {
            pendingRequests.remove(requestId)
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
                                relayClients.forEach {
                                    try {
                                        it.send(ackMessage)
                                    } catch (_: Exception) {
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
        clientScope.coroutineContext.cancelChildren()
        relayClients.forEach { client ->
            clientScope.launch {
                try {
                    client.disconnect()
                } catch (_: Exception) {
                }
            }
        }
        relayClients.clear()
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
