package org.nostr.nostrord.nostr

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.utils.epochMillis
import kotlin.random.Random

actual class Nip46Client actual constructor(existingPrivateKey: String?) {
    // Use existing keypair if provided, otherwise generate new one
    private val clientKeyPair: KeyPair = if (existingPrivateKey != null) {
        KeyPair.fromPrivateKeyHex(existingPrivateKey)
    } else {
        KeyPair.generate()
    }
    
    private var remoteSignerPubkey: String? = null
    private var userPubkey: String? = null
    private var relayClients: MutableList<NostrGroupClient> = mutableListOf()
    private var pendingRequests: MutableMap<String, CompletableDeferred<String>> = mutableMapOf()
    private var connectionSecret: String? = null
    private var useNip44: Boolean = false
    
    actual var onAuthUrl: ((String) -> Unit)? = null
    actual val clientPubkey: String get() = clientKeyPair.publicKeyHex
    
    // Expose the client private key for persistence
    actual val clientPrivateKey: String get() = clientKeyPair.privateKeyHex

    actual suspend fun connect(
        remoteSignerPubkey: String,
        relays: List<String>,
        secret: String?
    ): String {
        this.remoteSignerPubkey = remoteSignerPubkey
        this.connectionSecret = secret
        
        println("🔐 NIP-46 Connect attempt:")
        println("   Remote signer pubkey: $remoteSignerPubkey")
        println("   Relays: $relays")
        println("   Secret: ${secret?.take(8) ?: "none"}...")
        println("   Client pubkey: ${clientKeyPair.publicKeyHex}")
        
        for (relayUrl in relays) {
            try {
                val cleanUrl = relayUrl.trimEnd('/')
                val client = NostrGroupClient(cleanUrl)
                client.connect { msg -> handleMessage(msg) }
                client.waitForConnection()
                relayClients.add(client)
                println("✅ Connected to bunker relay: $cleanUrl")
            } catch (e: Exception) {
                println("⚠️ Failed to connect to bunker relay $relayUrl: ${e.message}")
            }
        }
        
        if (relayClients.isEmpty()) {
            throw Exception("Failed to connect to any bunker relay")
        }

        val requestId = generateRequestId()
        val params = buildList {
            add(remoteSignerPubkey)
            secret?.let { add(it) }
        }
        
        val response = sendRequest(requestId, "connect", params)
        
        if (response != "ack" && secret != null && response != secret) {
            throw Exception("Connect failed: unexpected response '$response'")
        }

        println("✅ NIP-46 connect successful")
        return remoteSignerPubkey
    }

    actual suspend fun getPublicKey(): String {
        val requestId = generateRequestId()
        val response = sendRequest(requestId, "get_public_key", emptyList())
        userPubkey = response
        println("✅ Got user public key: ${response.take(16)}...")
        return response
    }

    actual suspend fun signEvent(eventJson: String): String {
        val requestId = generateRequestId()
        return sendRequest(requestId, "sign_event", listOf(eventJson))
    }

    private suspend fun sendRequest(
        requestId: String,
        method: String,
        params: List<String>
    ): String = withTimeout(120_000) {
        val signerPubkey = remoteSignerPubkey ?: throw Exception("Not connected to signer")

        val requestJson = buildJsonObject {
            put("id", requestId)
            put("method", method)
            putJsonArray("params") { params.forEach { add(it) } }
        }.toString()

        println("📤 NIP-46 Request: $method")

        val encryptedContent = Nip44.encrypt(
            plaintext = requestJson,
            privateKeyHex = clientKeyPair.privateKeyHex,
            pubKeyHex = signerPubkey
        )

        val event = Event(
            pubkey = clientKeyPair.publicKeyHex,
            createdAt = epochMillis() / 1000,
            kind = 24133,
            tags = listOf(listOf("p", signerPubkey)),
            content = encryptedContent
        )
        
        val signedEvent = event.sign(clientKeyPair)
        val responseDeferred = CompletableDeferred<String>()
        pendingRequests[requestId] = responseDeferred
        
        val subscriptionId = "nip46-${requestId.take(8)}"
        val since = (epochMillis() / 1000) - 120
        
        val filter = buildJsonObject {
            putJsonArray("kinds") { add(24133) }
            putJsonArray("#p") { add(clientKeyPair.publicKeyHex) }
            put("since", since)
        }
        
        val subMessage = buildJsonArray {
            add("REQ")
            add(subscriptionId)
            add(filter)
        }.toString()
        
        relayClients.forEach { try { it.send(subMessage) } catch (_: Exception) {} }
        delay(500)

        val eventMessage = buildJsonArray {
            add("EVENT")
            add(signedEvent.toJsonObject())
        }.toString()
        
        relayClients.forEach { try { it.send(eventMessage) } catch (_: Exception) {} }

        try {
            responseDeferred.await()
        } finally {
            pendingRequests.remove(requestId)
            val closeMessage = buildJsonArray { add("CLOSE"); add(subscriptionId) }.toString()
            relayClients.forEach { try { it.send(closeMessage) } catch (_: Exception) {} }
        }
    }

    private fun handleMessage(msg: String) {
        try {
            val json = Json { ignoreUnknownKeys = true }
            val arr = json.parseToJsonElement(msg).jsonArray
            val msgType = arr.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return
            
            if (msgType == "EVENT" && arr.size >= 3) {
                val eventObj = arr[2].jsonObject
                val kind = eventObj["kind"]?.jsonPrimitive?.int ?: return
                if (kind != 24133) return
                
                val eventPubkey = eventObj["pubkey"]?.jsonPrimitive?.content ?: return
                val encryptedContent = eventObj["content"]?.jsonPrimitive?.content ?: return
                
                try {
                    val decrypted = if (encryptedContent.contains("?iv=")) {
                        Nip04.decrypt(encryptedContent, clientKeyPair.privateKeyHex, eventPubkey)
                    } else {
                        Nip44.decrypt(encryptedContent, clientKeyPair.privateKeyHex, eventPubkey)
                    }
                    
                    val responseObj = json.parseToJsonElement(decrypted).jsonObject
                    val responseId = responseObj["id"]?.jsonPrimitive?.content ?: return
                    val result = responseObj["result"]?.jsonPrimitive?.contentOrNull
                    val error = responseObj["error"]?.jsonPrimitive?.contentOrNull
                    
                    if (result == "auth_url" && error != null) {
                        onAuthUrl?.invoke(error)
                        return
                    }
                    
                    val deferred = pendingRequests[responseId] ?: return
                    
                    if (!error.isNullOrBlank() && result != "auth_url") {
                        deferred.completeExceptionally(Exception(error))
                        return
                    }
                    
                    deferred.complete(result ?: "")
                } catch (e: Exception) {
                    println("⚠️ Could not decrypt: ${e.message}")
                }
            }
        } catch (e: Exception) {
            println("⚠️ Error handling message: ${e.message}")
        }
    }

    private fun generateRequestId(): String {
        return Random.nextBytes(16).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    }

    actual fun disconnect() {
        relayClients.forEach { client ->
            CoroutineScope(Dispatchers.Default).launch {
                try { client.disconnect() } catch (_: Exception) {}
            }
        }
        relayClients.clear()
        pendingRequests.clear()
    }
}
