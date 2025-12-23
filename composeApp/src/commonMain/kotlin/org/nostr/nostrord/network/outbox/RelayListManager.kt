package org.nostr.nostrord.network.outbox

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.utils.epochMillis

/**
 * Manages NIP-65 relay list discovery and caching.
 *
 * ## Responsibilities:
 * - Discover relay lists from bootstrap relays
 * - Cache relay lists with TTL
 * - Provide fallbacks when relay lists unavailable
 * - Handle concurrent requests for same pubkey
 *
 * Note: Does not manage its own connections - uses connectionProvider from NostrRepository
 */
class RelayListManager(
    override val bootstrapRelays: List<String> = DEFAULT_BOOTSTRAP_RELAYS,
    private val connectionProvider: (suspend (String) -> NostrGroupClient?)? = null
) : OutboxModel {

    companion object {
        val DEFAULT_BOOTSTRAP_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.nostr.net",
            "wss://purplepag.es"  // Specialized for NIP-65
        )

        const val CACHE_TTL_MS = 3600_000L  // 1 hour
        const val MAX_CACHE_SIZE = 1000
        const val FETCH_TIMEOUT_MS = 5_000L  // 5 seconds (faster)
    }

    // Current user's relay list
    private val _myRelayList = MutableStateFlow<List<Nip65Relay>>(emptyList())
    override val myRelayList: StateFlow<List<Nip65Relay>> = _myRelayList.asStateFlow()

    // Cache of relay lists by pubkey
    private val relayListCache = mutableMapOf<String, CachedRelayList>()
    private val cacheMutex = Mutex()

    // Pending requests to avoid duplicate fetches
    private val pendingRequests = mutableSetOf<String>()
    private val pendingMutex = Mutex()

    // Current user's pubkey (set after login)
    private var myPubkey: String? = null

    /**
     * Set the connection provider (called by NostrRepository after init)
     */
    private var _connectionProvider: (suspend (String) -> NostrGroupClient?)? = connectionProvider

    fun setConnectionProvider(provider: suspend (String) -> NostrGroupClient?) {
        _connectionProvider = provider
    }

    /**
     * Set the current user's pubkey and relay list
     */
    fun setMyRelayList(pubkey: String, relays: List<Nip65Relay>) {
        myPubkey = pubkey
        _myRelayList.value = relays
        // Also cache it
        cacheRelayList(pubkey, relays)
    }

    /**
     * Cache relay list for any user (called when receiving kind:10002 events)
     */
    fun cacheRelayListForUser(pubkey: String, relays: List<Nip65Relay>) {
        cacheRelayList(pubkey, relays)
    }

    /**
     * Get relay list for a pubkey.
     * Returns cached version if valid, otherwise fetches from bootstrap relays.
     */
    override suspend fun getRelayList(pubkey: String): List<Nip65Relay> {
        // Check cache first
        cacheMutex.withLock {
            relayListCache[pubkey]?.let { cached ->
                if (!cached.isExpired(epochMillis())) {
                    println("📋 Relay list cache hit for ${pubkey.take(8)}")
                    return cached.relays
                }
            }
        }

        // Check if already fetching
        val shouldFetch = pendingMutex.withLock {
            if (pendingRequests.contains(pubkey)) {
                false
            } else {
                pendingRequests.add(pubkey)
                true
            }
        }

        if (!shouldFetch) {
            // Wait for existing fetch to complete
            println("⏳ Waiting for pending relay list fetch for ${pubkey.take(8)}")
            kotlinx.coroutines.delay(500)
            return cacheMutex.withLock {
                relayListCache[pubkey]?.relays ?: emptyList()
            }
        }

        try {
            // Fetch from bootstrap relays
            val relays = fetchRelayListFromBootstrap(pubkey)
            if (relays.isNotEmpty()) {
                cacheRelayList(pubkey, relays)
            }
            return relays
        } finally {
            pendingMutex.withLock {
                pendingRequests.remove(pubkey)
            }
        }
    }

    /**
     * Fetch relay list from bootstrap relays (parallel, returns first success)
     */
    private suspend fun fetchRelayListFromBootstrap(pubkey: String): List<Nip65Relay> {
        println("🔍 Fetching relay list for ${pubkey.take(8)} from bootstrap relays (parallel)")

        // Fetch from all bootstrap relays in parallel
        val results = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            coroutineScope {
                val deferred = bootstrapRelays.map { relayUrl ->
                    async {
                        try {
                            fetchFromRelay(relayUrl, pubkey)
                        } catch (e: Exception) {
                            println("⚠️ Failed to fetch from $relayUrl: ${e.message}")
                            emptyList()
                        }
                    }
                }

                // Return first non-empty result
                for (d in deferred) {
                    val result = d.await()
                    if (result.isNotEmpty()) {
                        println("✅ Found relay list for ${pubkey.take(8)}: ${result.size} relays")
                        return@coroutineScope result
                    }
                }
                emptyList<Nip65Relay>()
            }
        } ?: emptyList()

        if (results.isEmpty()) {
            println("ℹ️ No relay list found for ${pubkey.take(8)}, will use fallback")
        }
        return results
    }

    /**
     * Fetch relay list from a specific relay
     */
    private suspend fun fetchFromRelay(relayUrl: String, pubkey: String): List<Nip65Relay> {
        val client = getRelayConnection(relayUrl) ?: return emptyList()

        val filter = buildJsonObject {
            putJsonArray("kinds") { add(10002) }
            putJsonArray("authors") { add(pubkey) }
            put("limit", 1)
        }

        val subId = "nip65-${pubkey.take(8)}-${epochMillis()}"
        val message = buildJsonArray {
            add("REQ")
            add(subId)
            add(filter)
        }.toString()

        var relays: List<Nip65Relay> = emptyList()
        var eoseReceived = false

        // Set up one-time message handler
        val originalHandler = client.messageHandler
        client.messageHandler = { msg ->
            val parsed = parseRelayListResponse(msg, subId)
            if (parsed != null) {
                relays = parsed
            }
            if (isEose(msg, subId)) {
                eoseReceived = true
            }
            originalHandler?.invoke(msg)
        }

        try {
            client.send(message)

            // Wait for response with timeout
            val startTime = epochMillis()
            while (!eoseReceived && epochMillis() - startTime < FETCH_TIMEOUT_MS) {
                kotlinx.coroutines.delay(50)
            }

            // Close subscription
            val closeMsg = buildJsonArray {
                add("CLOSE")
                add(subId)
            }.toString()
            client.send(closeMsg)

        } finally {
            client.messageHandler = originalHandler
        }

        return relays
    }

    /**
     * Parse relay list from EVENT response
     */
    private fun parseRelayListResponse(msg: String, expectedSubId: String): List<Nip65Relay>? {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val arr = json.parseToJsonElement(msg).jsonArray

            if (arr.size >= 3 &&
                arr[0].jsonPrimitive.content == "EVENT" &&
                arr[1].jsonPrimitive.content == expectedSubId
            ) {
                val event = arr[2].jsonObject
                val kind = event["kind"]?.jsonPrimitive?.int
                if (kind == 10002) {
                    val tags = event["tags"]?.jsonArray ?: return null
                    tags.mapNotNull { tag ->
                        Nip65Relay.fromTag(tag.jsonArray.map { it.jsonPrimitive.content })
                    }
                } else null
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if message is EOSE for subscription
     */
    private fun isEose(msg: String, subId: String): Boolean {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val arr = json.parseToJsonElement(msg).jsonArray
            arr.size >= 2 &&
                    arr[0].jsonPrimitive.content == "EOSE" &&
                    arr[1].jsonPrimitive.content == subId
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get a connection to a relay via the connection provider
     */
    private suspend fun getRelayConnection(relayUrl: String): NostrGroupClient? {
        val provider = _connectionProvider ?: run {
            println("⚠️ No connection provider set for RelayListManager")
            return null
        }
        return provider(relayUrl)
    }

    /**
     * Cache a relay list
     */
    private fun cacheRelayList(pubkey: String, relays: List<Nip65Relay>) {
        val now = epochMillis()
        val cached = CachedRelayList(
            pubkey = pubkey,
            relays = relays,
            fetchedAt = now,
            expiresAt = now + CACHE_TTL_MS
        )

        // Evict oldest entries if cache is full
        if (relayListCache.size >= MAX_CACHE_SIZE) {
            val oldest = relayListCache.entries.minByOrNull { it.value.fetchedAt }
            oldest?.let { relayListCache.remove(it.key) }
        }

        relayListCache[pubkey] = cached
        println("💾 Cached relay list for ${pubkey.take(8)}: ${relays.size} relays")
    }

    /**
     * Select relays for fetching events from an author.
     * Uses author's WRITE relays (their outbox).
     */
    override fun selectFetchRelays(authorPubkey: String): List<String> {
        val authorRelays = relayListCache[authorPubkey]?.relays ?: emptyList()
        val writeRelays = authorRelays.filter { it.write }.map { it.url }

        return if (writeRelays.isNotEmpty()) {
            println("📤 Using ${writeRelays.size} WRITE relays for ${authorPubkey.take(8)}")
            writeRelays + bootstrapRelays // Add bootstrap as fallback
        } else {
            println("ℹ️ No WRITE relays for ${authorPubkey.take(8)}, using bootstrap")
            bootstrapRelays
        }
    }

    /**
     * Select relays for publishing events.
     * Uses current user's WRITE relays.
     */
    override fun selectPublishRelays(): List<String> {
        val writeRelays = _myRelayList.value.filter { it.write }.map { it.url }

        return if (writeRelays.isNotEmpty()) {
            println("📤 Publishing to ${writeRelays.size} WRITE relays")
            writeRelays
        } else {
            println("ℹ️ No WRITE relays configured, using bootstrap")
            bootstrapRelays
        }
    }

    /**
     * Select relays for fetching mentions/replies.
     * Uses target user's READ relays (their inbox).
     */
    override fun selectInboxRelays(pubkey: String): List<String> {
        val relays = if (pubkey == myPubkey) {
            _myRelayList.value
        } else {
            relayListCache[pubkey]?.relays ?: emptyList()
        }

        val readRelays = relays.filter { it.read }.map { it.url }

        return if (readRelays.isNotEmpty()) {
            println("📥 Using ${readRelays.size} READ relays for ${pubkey.take(8)}")
            readRelays + bootstrapRelays
        } else {
            println("ℹ️ No READ relays for ${pubkey.take(8)}, using bootstrap")
            bootstrapRelays
        }
    }

    /**
     * Get cached relay list for a pubkey (synchronous, doesn't fetch)
     */
    fun getCachedRelayList(pubkey: String): List<Nip65Relay> {
        return if (pubkey == myPubkey) {
            _myRelayList.value
        } else {
            relayListCache[pubkey]?.relays ?: emptyList()
        }
    }

    /**
     * Clear cache for a specific pubkey
     */
    fun invalidateCache(pubkey: String) {
        relayListCache.remove(pubkey)
    }

    /**
     * Clear all cached relay lists
     */
    fun clearCache() {
        relayListCache.clear()
    }

    /**
     * Clear state (connections are managed by NostrRepository)
     */
    fun clear() {
        relayListCache.clear()
        _myRelayList.value = emptyList()
        myPubkey = null
    }
}

// Extension property for NostrGroupClient to support message handler
private var NostrGroupClient.messageHandler: ((String) -> Unit)?
    get() = null  // Stored externally
    set(value) {}  // Stored externally
