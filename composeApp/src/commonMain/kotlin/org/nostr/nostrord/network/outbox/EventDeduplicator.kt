package org.nostr.nostrord.network.outbox

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.nostr.nostrord.utils.epochMillis

/**
 * Handles event deduplication across multiple relays.
 *
 * Uses a simple cache to track seen event IDs and prevent
 * duplicate processing when the same event arrives from
 * multiple relays.
 *
 * ## Features:
 * - Thread-safe operations using Mutex
 * - LRU-style eviction when cache is full
 * - Configurable cache size
 * - Statistics tracking
 */
class EventDeduplicator(
    private val maxSize: Int = DEFAULT_MAX_SIZE
) {
    companion object {
        const val DEFAULT_MAX_SIZE = 10_000
    }

    // Simple map for seen events (eventId -> timestamp)
    private val seenEvents = mutableMapOf<String, Long>()
    private val insertionOrder = mutableListOf<String>()
    private val mutex = Mutex()

    // Statistics
    private var totalEvents = 0L
    private var duplicateEvents = 0L

    /**
     * Try to add an event ID to the set.
     * Returns true if the event is new (not a duplicate).
     * Returns false if the event was already seen.
     */
    suspend fun tryAdd(eventId: String): Boolean {
        return mutex.withLock {
            totalEvents++

            if (seenEvents.containsKey(eventId)) {
                duplicateEvents++
                false
            } else {
                // Evict oldest if at capacity
                if (seenEvents.size >= maxSize && insertionOrder.isNotEmpty()) {
                    val oldest = insertionOrder.removeAt(0)
                    seenEvents.remove(oldest)
                }

                seenEvents[eventId] = epochMillis()
                insertionOrder.add(eventId)
                true
            }
        }
    }

    /**
     * Check if an event ID has been seen (without adding it)
     */
    suspend fun contains(eventId: String): Boolean {
        return mutex.withLock {
            seenEvents.containsKey(eventId)
        }
    }

    /**
     * Remove an event ID from the set
     */
    suspend fun remove(eventId: String) {
        mutex.withLock {
            seenEvents.remove(eventId)
            insertionOrder.remove(eventId)
        }
    }

    /**
     * Clear all seen events
     */
    suspend fun clear() {
        mutex.withLock {
            seenEvents.clear()
            insertionOrder.clear()
            totalEvents = 0
            duplicateEvents = 0
        }
    }

    /**
     * Non-suspend version for synchronous contexts.
     * Uses blocking approach - use sparingly.
     */
    fun tryAddSync(eventId: String): Boolean {
        totalEvents++

        return if (seenEvents.containsKey(eventId)) {
            duplicateEvents++
            false
        } else {
            // Evict oldest if at capacity
            if (seenEvents.size >= maxSize && insertionOrder.isNotEmpty()) {
                val oldest = insertionOrder.removeAt(0)
                seenEvents.remove(oldest)
            }

            seenEvents[eventId] = epochMillis()
            insertionOrder.add(eventId)
            true
        }
    }

    /**
     * Get current cache size
     */
    fun size(): Int = seenEvents.size

    /**
     * Get deduplication statistics
     */
    fun getStats(): DeduplicationStats {
        val rate = if (totalEvents > 0) {
            (duplicateEvents.toDouble() / totalEvents * 100)
        } else 0.0

        return DeduplicationStats(
            totalEvents = totalEvents,
            uniqueEvents = totalEvents - duplicateEvents,
            duplicateEvents = duplicateEvents,
            deduplicationRate = rate,
            cacheSize = seenEvents.size,
            maxCacheSize = maxSize
        )
    }

    data class DeduplicationStats(
        val totalEvents: Long,
        val uniqueEvents: Long,
        val duplicateEvents: Long,
        val deduplicationRate: Double,
        val cacheSize: Int,
        val maxCacheSize: Int
    ) {
        override fun toString(): String {
            val rateStr = ((deduplicationRate * 10).toLong() / 10.0).toString()
            return "EventDeduplicator: $uniqueEvents unique, $duplicateEvents duplicates " +
                    "($rateStr% dedup rate), cache $cacheSize/$maxCacheSize"
        }
    }
}

/**
 * Extension for batch deduplication
 */
suspend fun EventDeduplicator.filterNew(eventIds: List<String>): List<String> {
    return eventIds.filter { tryAdd(it) }
}

/**
 * Synchronous batch filter
 */
fun EventDeduplicator.filterNewSync(eventIds: List<String>): List<String> {
    return eventIds.filter { tryAddSync(it) }
}
