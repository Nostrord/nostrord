package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.nostr.nostrord.utils.epochMillis

/**
 * Self-tuning runtime configuration that adapts to network conditions,
 * device constraints, and usage patterns.
 *
 * Replaces hardcoded performance constants with dynamic values.
 * All parameters start at safe defaults and converge toward optimal values
 * within the first 30 seconds of operation.
 *
 * Reads signals from explicit recording (reconnects, event bursts, relay latency).
 *
 * Controls:
 * - Request cooldown (how long to suppress duplicate REQs)
 * - Buffer window (how long to batch events before flushing to UI)
 * - Prefetch scope (whether to load data for non-active groups)
 * - Background work budget
 *
 * Data stays in-memory — no persistence, no transmission, no user data.
 */
class AdaptiveConfig(
    private val scope: CoroutineScope,
) {
    // ── Adaptive parameters (read by consumers) ──────────────────────────

    /** Dynamic request cooldown in ms. Replaces fixed REQUEST_COOLDOWN_MS. */
    var requestCooldownMs: Long = DEFAULT_COOLDOWN_MS
        private set

    /** Dynamic buffer window in ms. Replaces fixed WINDOW_MS. */
    var bufferWindowMs: Long = DEFAULT_BUFFER_MS
        private set

    /** Whether to prefetch data for recently-viewed (non-active) groups. */
    var prefetchEnabled: Boolean = false
        private set

    /** Max concurrent background operations (0 = suppress all background work). */
    var maxBackgroundWork: Int = 2
        private set

    // ── Signal tracking ──────────────────────────────────────────────────

    // Signal stores are mutated from multiple coroutines on Dispatchers.Default
    // (every WebSocket frame handler can record an event/latency/reconnect).
    // Without serialization, ArrayList.add races during grow produced an
    // ArrayIndexOutOfBoundsException ("Index 1 out of bounds for length 0").
    private val mutex = Mutex()
    private val reconnectTimestamps = mutableListOf<Long>()
    private val eventTimestamps = mutableListOf<Long>()
    private val relayLatencies = mutableMapOf<String, MutableList<Long>>()

    // ── Computed state (updated by recompute) ────────────────────────────

    /** Current network stability assessment. */
    var networkStability: Stability = Stability.NORMAL
        private set

    enum class Stability { GOOD, NORMAL, DEGRADED, UNSTABLE }

    init {
        scope.launch {
            while (true) {
                delay(TUNE_INTERVAL_MS)
                recompute()
                evictStaleSignals()
            }
        }
    }

    // ── Signal recording (called by ConnectionManager, GroupManager) ─────
    //
    // Recording is non-suspending so callers stay simple, but the underlying
    // list mutation is dispatched into [scope] and serialized through [mutex].
    // Fire-and-forget: for adaptive stats, eventual ordering is sufficient.

    /** Record a successful reconnect event for frequency tracking. */
    fun recordReconnect() {
        val now = epochMillis()
        scope.launch { mutex.withLock { reconnectTimestamps.add(now) } }
    }

    /** Record a batch of events arriving for throughput tracking. */
    fun recordEventBurst(count: Int) {
        if (count <= 0) return
        val now = epochMillis()
        val n = count.coerceAtMost(50)
        // Compact: store one timestamp per burst, not per event
        scope.launch { mutex.withLock { repeat(n) { eventTimestamps.add(now) } } }
    }

    /** Record observed relay response latency (connect time or REQ→EVENT). */
    fun recordRelayLatency(
        relayUrl: String,
        latencyMs: Long,
    ) {
        scope.launch {
            mutex.withLock {
                val window = relayLatencies.getOrPut(relayUrl) { mutableListOf() }
                window.add(latencyMs)
                // removeAt(0), not removeFirst(): the latter binds to java.util.List.removeFirst,
                // which only exists on Android API 35+ and throws NoSuchMethodError below it.
                if (window.size > LATENCY_WINDOW_SIZE) window.removeAt(0)
            }
        }
    }

    // ── Relay scoring ────────────────────────────────────────────────────

    /** Average latency for a relay, or [Long.MAX_VALUE] if unknown. */
    suspend fun getRelayLatency(relayUrl: String): Long = mutex.withLock {
        val samples = relayLatencies[relayUrl]
        if (samples.isNullOrEmpty()) {
            Long.MAX_VALUE
        } else {
            samples.average().toLong()
        }
    }

    /** Returns the relay with the lowest average latency from the given set. */
    suspend fun fastestRelay(relayUrls: Collection<String>): String? = mutex.withLock {
        relayUrls.minByOrNull { url ->
            val samples = relayLatencies[url]
            if (samples.isNullOrEmpty()) {
                Long.MAX_VALUE
            } else {
                samples.average().toLong()
            }
        }
    }

    // ── Core adaptation logic ────────────────────────────────────────────

    private suspend fun recompute() {
        val now = epochMillis()

        // Snapshot the three counters under the mutex so we never iterate a
        // list that's being mutated by a concurrent recordEventBurst /
        // recordReconnect / recordRelayLatency.
        data class Snapshot(
            val reconnects: Int,
            val events: Int,
            val avgLatency: Long,
        )
        val snap =
            mutex.withLock {
                Snapshot(
                    reconnects = reconnectTimestamps.count { now - it < 60_000 },
                    events = eventTimestamps.count { now - it < 10_000 },
                    avgLatency =
                    relayLatencies.values
                        .flatMap { it.takeLast(5) }
                        .let { if (it.isEmpty()) 200L else it.average().toLong() },
                )
            }

        val recentReconnects = snap.reconnects

        // Network stability classification
        networkStability =
            when {
                recentReconnects >= 5 -> Stability.UNSTABLE
                recentReconnects >= 3 -> Stability.DEGRADED
                recentReconnects >= 1 -> Stability.NORMAL
                else -> Stability.GOOD
            }

        val avgLatency = snap.avgLatency

        // Event throughput (events in last 10 seconds)
        val eventsPerSecond = snap.events / 10.0

        // ── Adapt request cooldown ───────────────────────────────────
        requestCooldownMs =
            when (networkStability) {
                Stability.UNSTABLE -> 2500L
                Stability.DEGRADED -> 1500L
                Stability.NORMAL ->
                    when {
                        avgLatency > 500 -> 1200L
                        avgLatency > 200 -> 800L
                        else -> 500L
                    }
                Stability.GOOD ->
                    when {
                        avgLatency > 300 -> 600L
                        else -> 300L
                    }
            }

        // ── Adapt buffer window ──────────────────────────────────────
        bufferWindowMs =
            when {
                eventsPerSecond > 50 -> 200L // high burst → batch aggressively
                eventsPerSecond > 20 -> 150L // moderate burst
                eventsPerSecond > 5 -> 100L // steady state
                else -> 50L // idle → near-instant render
            }

        // ── Adapt prefetch scope ─────────────────────────────────────
        prefetchEnabled = networkStability == Stability.GOOD && avgLatency < 300

        // ── Adapt background work budget ─────────────────────────────
        maxBackgroundWork =
            when (networkStability) {
                Stability.UNSTABLE -> 0
                Stability.DEGRADED -> 1
                Stability.NORMAL -> 2
                Stability.GOOD -> 3
            }
    }

    private suspend fun evictStaleSignals() {
        val cutoff = epochMillis() - SIGNAL_TTL_MS
        mutex.withLock {
            reconnectTimestamps.removeAll { it < cutoff }
            eventTimestamps.removeAll { it < cutoff }
        }
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 1000L
        const val DEFAULT_BUFFER_MS = 100L
        const val TUNE_INTERVAL_MS = 10_000L
        const val SIGNAL_TTL_MS = 120_000L // keep last 2 minutes of signals
        const val LATENCY_WINDOW_SIZE = 20 // rolling window per relay
    }
}
