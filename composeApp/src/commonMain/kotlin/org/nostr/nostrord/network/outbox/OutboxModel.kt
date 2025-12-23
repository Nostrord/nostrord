package org.nostr.nostrord.network.outbox

/**
 * # Nostr Outbox Model (NIP-65)
 *
 * ## Architecture Overview
 *
 * The Outbox model separates relay usage into distinct roles:
 *
 * ```
 * ┌─────────────────────────────────────────────────────────────────┐
 * │                        OUTBOX MODEL                              │
 * ├─────────────────────────────────────────────────────────────────┤
 * │                                                                  │
 * │  BOOTSTRAP RELAYS (Discovery Only)                              │
 * │  ├── wss://relay.damus.io                                       │
 * │  ├── wss://nos.lol                                              │
 * │  └── wss://relay.nostr.net                                      │
 * │      │                                                          │
 * │      └──> Fetch kind:10002 (relay lists)                        │
 * │                                                                  │
 * │  USER'S WRITE RELAYS (Outbox - Publishing)                      │
 * │  ├── Author publishes their events here                         │
 * │  └── Readers fetch author's events from here                    │
 * │                                                                  │
 * │  USER'S READ RELAYS (Inbox - Receiving)                         │
 * │  ├── Others publish mentions/replies here                       │
 * │  └── User fetches events mentioning them here                   │
 * │                                                                  │
 * └─────────────────────────────────────────────────────────────────┘
 * ```
 *
 * ## Algorithm
 *
 * ### 1. Discovering Relay Lists
 * ```
 * function discoverRelayList(pubkey):
 *     if cache.has(pubkey) and !cache.isExpired(pubkey):
 *         return cache.get(pubkey)
 *
 *     for relay in bootstrapRelays:
 *         response = relay.fetch(kind=10002, author=pubkey)
 *         if response:
 *             relayList = parseRelayList(response)
 *             cache.set(pubkey, relayList, ttl=1hour)
 *             return relayList
 *
 *     return emptyList  // Fallback: use bootstrap relays
 * ```
 *
 * ### 2. Publishing Events (Author's Perspective)
 * ```
 * function publishEvent(event):
 *     myRelays = getMyRelayList()
 *     writeRelays = myRelays.filter(r => r.write)
 *
 *     if writeRelays.isEmpty():
 *         writeRelays = bootstrapRelays  // Fallback
 *
 *     results = []
 *     for relay in writeRelays:
 *         result = relay.publish(event)
 *         results.add(result)
 *
 *     return results.any(success)
 * ```
 *
 * ### 3. Fetching Events (Reader's Perspective)
 * ```
 * function fetchEventsFromAuthor(authorPubkey, filter):
 *     authorRelays = discoverRelayList(authorPubkey)
 *     writeRelays = authorRelays.filter(r => r.write)  // Author's outbox
 *
 *     if writeRelays.isEmpty():
 *         writeRelays = bootstrapRelays  // Fallback
 *
 *     events = Set()  // Deduplication
 *     for relay in writeRelays:
 *         relayEvents = relay.fetch(filter)
 *         for event in relayEvents:
 *             if !events.contains(event.id):
 *                 events.add(event)
 *
 *     return events
 * ```
 *
 * ### 4. Fetching Mentions/Replies (Inbox)
 * ```
 * function fetchMentions(myPubkey):
 *     myRelays = getMyRelayList()
 *     readRelays = myRelays.filter(r => r.read)  // My inbox
 *
 *     events = Set()
 *     for relay in readRelays:
 *         mentions = relay.fetch(#p=myPubkey)
 *         events.addAll(mentions)
 *
 *     return events
 * ```
 *
 * ## Relay Selection Priority
 *
 * 1. Explicit relay hints (from nostr: URIs)
 * 2. Author's WRITE relays (for fetching their content)
 * 3. Tagged user's READ relays (for mentions)
 * 4. Current user's READ relays (for general browsing)
 * 5. Bootstrap relays (fallback)
 *
 * ## Caching Strategy
 *
 * - Relay lists: 1 hour TTL, refresh on access if expired
 * - Event deduplication: In-memory set with LRU eviction
 * - Connection pooling: Reuse WebSocket connections
 */

import kotlinx.coroutines.flow.StateFlow

/**
 * Represents a relay with read/write capabilities (NIP-65)
 */
data class Nip65Relay(
    val url: String,
    val read: Boolean = true,
    val write: Boolean = true
) {
    companion object {
        fun fromTag(tag: List<String>): Nip65Relay? {
            if (tag.size < 2 || tag[0] != "r") return null
            val url = tag[1]
            val marker = tag.getOrNull(2)
            return when (marker) {
                "read" -> Nip65Relay(url, read = true, write = false)
                "write" -> Nip65Relay(url, read = false, write = true)
                else -> Nip65Relay(url, read = true, write = true)
            }
        }
    }

    fun toTag(): List<String> {
        return when {
            read && write -> listOf("r", url)
            read -> listOf("r", url, "read")
            write -> listOf("r", url, "write")
            else -> listOf("r", url)
        }
    }
}

/**
 * Cached relay list with expiration
 */
data class CachedRelayList(
    val pubkey: String,
    val relays: List<Nip65Relay>,
    val fetchedAt: Long,
    val expiresAt: Long
) {
    fun isExpired(currentTimeMs: Long): Boolean = currentTimeMs > expiresAt

    companion object {
        const val DEFAULT_TTL_MS = 3600_000L // 1 hour
    }
}

/**
 * Result of a relay operation
 */
sealed class RelayResult<T> {
    data class Success<T>(val data: T, val relay: String) : RelayResult<T>()
    data class Error<T>(val message: String, val relay: String) : RelayResult<T>()
}

/**
 * Interface for the Outbox Model relay manager
 */
interface OutboxModel {
    /**
     * Bootstrap relays for discovering relay lists
     */
    val bootstrapRelays: List<String>

    /**
     * Current user's relay list
     */
    val myRelayList: StateFlow<List<Nip65Relay>>

    /**
     * Get relay list for a pubkey (from cache or fetch)
     */
    suspend fun getRelayList(pubkey: String): List<Nip65Relay>

    /**
     * Select relays for fetching events from an author
     */
    fun selectFetchRelays(authorPubkey: String): List<String>

    /**
     * Select relays for publishing events
     */
    fun selectPublishRelays(): List<String>

    /**
     * Select relays for fetching mentions/replies to a user
     */
    fun selectInboxRelays(pubkey: String): List<String>
}
