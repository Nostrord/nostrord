package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.PublishResult
import org.nostr.nostrord.network.RelayRejection
import org.nostr.nostrord.network.classifyRejection
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.utils.epochMillis

/**
 * Represents a pending event waiting to be sent to the relay.
 */
data class PendingEvent(
    val id: String,
    val eventJson: String,
    val eventId: String,
    val groupId: String,
    val createdAt: Long = epochMillis(),
    /** Drives the backoff only; there is no attempt ceiling. Persisted so the backoff carries
     *  across restarts instead of restarting from zero on every launch. */
    val retryCount: Int = 0,
    val lastError: String? = null,
    val lastAttemptAt: Long = 0,
)

/**
 * Status of a pending event.
 */
sealed class PendingEventStatus {
    data object Queued : PendingEventStatus()

    data object Sending : PendingEventStatus()

    data class Sent(
        val result: PublishResult,
    ) : PendingEventStatus()

    /** Last attempt did not land, but the reason passes: offline, timeout, AUTH pending. Still queued. */
    data class Retrying(
        val lastError: String,
    ) : PendingEventStatus()

    /** The relay refused it for a reason that will not change. Terminal, and the only state that
     *  surfaces the message's Not delivered / Retry / Dismiss row. */
    data class Failed(
        val reason: String,
    ) : PendingEventStatus()
}

/**
 * Manages events that failed to send or were queued while offline.
 *
 * An event stays queued until a relay accepts it: attempts are never exhausted and entries never
 * expire, so a message written while the group's relay is unreachable lands whenever connectivity
 * returns, including after the app is closed and reopened (the queue is persisted per account and
 * reloaded in [setCurrentPubkey]). Retries use exponential backoff capped at [MAX_RETRY_DELAY_MS];
 * a reconnect makes every entry due at once via [onConnectionRestored].
 *
 * The exception is an outright relay rejection ("blocked:", "group doesn't exist"): asking again
 * cannot change that answer, so the event is dropped from the queue and reported through
 * [onEventPermanentlyFailed] for the UI to offer Retry / Dismiss.
 */
class PendingEventManager(
    private val connectionManager: ConnectionManager,
    private val scope: CoroutineScope,
) {
    companion object {
        const val MAX_QUEUE_SIZE = 100
        const val BASE_RETRY_DELAY_MS = 1000L
        const val MAX_RETRY_DELAY_MS = 30_000L
        const val RETRY_SWEEP_MS = 5_000L
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var currentPubkey: String? = null

    /**
     * Set the current user pubkey for storage scoping.
     */
    fun setCurrentPubkey(pubkey: String?) {
        currentPubkey = pubkey
        // Load persisted events when pubkey is set
        if (pubkey != null) {
            loadFromStorage()
        }
    }

    // In-memory queue of pending events
    private val _pendingEvents = MutableStateFlow<List<PendingEvent>>(emptyList())
    val pendingEvents: StateFlow<List<PendingEvent>> = _pendingEvents.asStateFlow()

    // Status for each pending event
    private val _eventStatuses = MutableStateFlow<Map<String, PendingEventStatus>>(emptyMap())
    val eventStatuses: StateFlow<Map<String, PendingEventStatus>> = _eventStatuses.asStateFlow()

    // Whether retry is currently in progress
    private val _isRetrying = MutableStateFlow(false)
    val isRetrying: StateFlow<Boolean> = _isRetrying.asStateFlow()

    /**
     * Terminal-outcome hooks for optimistic send. [onEventDelivered] fires when a
     * queued event is finally accepted by the relay; [onEventPermanentlyFailed]
     * fires when a relay refuses it for good, or when it is evicted to keep the
     * queue bounded. Both are keyed by the nostr event id so the owner (GroupManager)
     * can resolve the on-screen status.
     */
    var onEventDelivered: ((eventId: String) -> Unit)? = null
    var onEventPermanentlyFailed: ((eventId: String, groupId: String, eventJson: String, reason: String) -> Unit)? = null

    /**
     * Resolves the client for the relay that hosts a group. A NIP-29 relay rejects a
     * kind:9 for a group it doesn't host ("group doesn't exist"), so retries must be
     * routed per group, never blanket-sent to the focused relay. Wired by GroupManager.
     * When it returns null the relay is down: the event stays queued for the next sweep.
     */
    var resolveClient: (suspend (groupId: String) -> NostrGroupClient?)? = null

    // Periodic retry sweep: runs while the queue is non-empty so a send that timed out
    // on a live socket still retries without waiting for a reconnect event.
    private var retrySweepJob: Job? = null

    private fun ensureRetrySweep() {
        if (retrySweepJob?.isActive == true) return
        retrySweepJob = scope.launch {
            while (_pendingEvents.value.isNotEmpty()) {
                delay(RETRY_SWEEP_MS)
                retryPendingEvents()
            }
        }
    }

    /**
     * Queue an event for later sending.
     * Called when send fails due to offline or network error.
     *
     * @return true if event was queued, false if queue is full
     */
    suspend fun queueEvent(
        eventJson: String,
        eventId: String,
        groupId: String,
    ): Boolean {
        var evicted: PendingEvent? = null
        val queued = mutex.withLock {
            val current = _pendingEvents.value

            // Prevent duplicate events
            if (current.any { it.eventId == eventId }) {
                return@withLock false
            }

            // Retries never stop, so the queue is bounded instead: the oldest entry drops once it
            // is full. It is reported as failed rather than vanishing, or the message would sit in
            // the chat as Sending with nothing left to send it.
            if (current.size >= MAX_QUEUE_SIZE) {
                val oldest = current.minByOrNull { it.createdAt }
                if (oldest != null) {
                    _pendingEvents.value = current - oldest
                    _eventStatuses.value = _eventStatuses.value - oldest.id
                    evicted = oldest
                }
            }

            val pendingEvent =
                PendingEvent(
                    id = "pending_${epochMillis()}_$eventId",
                    eventJson = eventJson,
                    eventId = eventId,
                    groupId = groupId,
                )

            _pendingEvents.value = _pendingEvents.value + pendingEvent
            _eventStatuses.value = _eventStatuses.value + (pendingEvent.id to PendingEventStatus.Queued)
            true
        }
        evicted?.let { onEventPermanentlyFailed?.invoke(it.eventId, it.groupId, it.eventJson, "Send queue full") }
        if (queued) {
            saveToStorage()
            ensureRetrySweep()
        }
        return queued
    }

    /**
     * Remove a pending event (e.g., user cancelled).
     */
    suspend fun removeEvent(pendingId: String) {
        mutex.withLock {
            _pendingEvents.value = _pendingEvents.value.filter { it.id != pendingId }
            _eventStatuses.value = _eventStatuses.value - pendingId
        }
        saveToStorage()
    }

    /**
     * Remove a pending event by its nostr event id. Used when the relay's live feed
     * echoes back an event whose OK was lost: the echo proves acceptance, so the
     * queued retry must be dropped before it double-sends.
     */
    suspend fun removeByEventId(eventId: String) {
        val removed = mutex.withLock {
            val matches = _pendingEvents.value.filter { it.eventId == eventId }
            if (matches.isEmpty()) return@withLock false
            _pendingEvents.value = _pendingEvents.value.filterNot { it.eventId == eventId }
            _eventStatuses.value = _eventStatuses.value - matches.map { it.id }.toSet()
            true
        }
        if (removed) saveToStorage()
    }

    /**
     * Retry all pending events.
     * Should be called when connection is restored.
     */
    suspend fun retryPendingEvents() {
        if (_isRetrying.value) return
        _isRetrying.value = true

        try {
            val events = _pendingEvents.value.toList()
            if (events.isEmpty()) return

            for (event in events) {
                // Honor per-event exponential backoff without blocking the rest of the queue.
                if (event.retryCount > 0 && epochMillis() < event.lastAttemptAt + calculateRetryDelay(event.retryCount)) {
                    continue
                }

                // Route to the group's own relay; null means it is down, so the event
                // stays queued for the next sweep (no attempt is counted).
                val resolve = resolveClient
                val client = if (resolve != null) resolve(event.groupId) else connectionManager.getFocusedClient()
                if (client == null) continue

                // Update status to sending
                updateStatus(event.id, PendingEventStatus.Sending)

                // Attempt to send
                val result = client.sendAndAwaitOk(event.eventJson, event.eventId)

                when (result) {
                    is PublishResult.Success -> {
                        // Remove from queue on success
                        updateStatus(event.id, PendingEventStatus.Sent(result))
                        onEventDelivered?.invoke(event.eventId)
                        removeEvent(event.id)
                    }
                    is PublishResult.Rejected -> {
                        when (classifyRejection(result.reason)) {
                            RelayRejection.AlreadyStored -> {
                                // The relay already holds it: our earlier OK was lost, not the event.
                                updateStatus(event.id, PendingEventStatus.Sent(PublishResult.Success(event.eventId, result.reason)))
                                onEventDelivered?.invoke(event.eventId)
                                removeEvent(event.id)
                                continue
                            }
                            // "not yet", not "never": the publish raced NIP-42, or the relay is
                            // rate limiting. Keep the event queued instead of failing it.
                            RelayRejection.Transient -> {
                                incrementRetryCount(event.id, result.reason)
                                continue
                            }
                            RelayRejection.Permanent -> Unit
                        }
                        // Don't retry rejected events - relay explicitly refused
                        updateStatus(event.id, PendingEventStatus.Failed(result.reason))
                        onEventPermanentlyFailed?.invoke(event.eventId, event.groupId, event.eventJson, result.reason)
                        removeEvent(event.id)
                    }
                    is PublishResult.Timeout, is PublishResult.Error -> {
                        val errorMsg =
                            when (result) {
                                is PublishResult.Timeout -> "Timeout"
                                is PublishResult.Error -> result.exception.message ?: "Unknown error"
                                else -> "Unknown error"
                            }
                        incrementRetryCount(event.id, errorMsg)
                    }
                }
            }
        } finally {
            _isRetrying.value = false
        }
    }

    /**
     * Retry a specific pending event.
     */
    suspend fun retrySingleEvent(pendingId: String): PublishResult? {
        val event = _pendingEvents.value.find { it.id == pendingId } ?: return null

        val resolve = resolveClient
        val client = (if (resolve != null) resolve(event.groupId) else connectionManager.getFocusedClient()) ?: return null

        updateStatus(event.id, PendingEventStatus.Sending)

        val result = client.sendAndAwaitOk(event.eventJson, event.eventId)

        when (result) {
            is PublishResult.Success -> {
                updateStatus(event.id, PendingEventStatus.Sent(result))
                onEventDelivered?.invoke(event.eventId)
                removeEvent(event.id)
            }
            is PublishResult.Rejected -> {
                // Same classification as the batch path.
                when (classifyRejection(result.reason)) {
                    RelayRejection.AlreadyStored -> {
                        updateStatus(event.id, PendingEventStatus.Sent(PublishResult.Success(event.eventId, result.reason)))
                        onEventDelivered?.invoke(event.eventId)
                        removeEvent(event.id)
                        return result
                    }
                    RelayRejection.Transient -> {
                        incrementRetryCount(event.id, result.reason)
                        return result
                    }
                    RelayRejection.Permanent -> Unit
                }
                updateStatus(event.id, PendingEventStatus.Failed(result.reason))
                onEventPermanentlyFailed?.invoke(event.eventId, event.groupId, event.eventJson, result.reason)
                removeEvent(event.id)
            }
            is PublishResult.Timeout, is PublishResult.Error -> {
                val errorMsg =
                    when (result) {
                        is PublishResult.Timeout -> "Timeout"
                        is PublishResult.Error -> result.exception.message ?: "Unknown error"
                        else -> "Unknown error"
                    }
                incrementRetryCount(event.id, errorMsg)
            }
        }

        return result
    }

    /**
     * Get count of pending events for a specific group.
     */
    fun getPendingCountForGroup(groupId: String): Int = _pendingEvents.value.count { it.groupId == groupId }

    /**
     * Get all pending events for a specific group.
     */
    fun getPendingEventsForGroup(groupId: String): List<PendingEvent> = _pendingEvents.value.filter { it.groupId == groupId }

    /**
     * Clear all pending events (e.g., on logout).
     */
    suspend fun clear() {
        // In-memory only: the persisted per-account queue survives an account switch
        // so undelivered messages still send when that account becomes active again.
        retrySweepJob?.cancel()
        retrySweepJob = null
        mutex.withLock {
            _pendingEvents.value = emptyList()
            _eventStatuses.value = emptyMap()
        }
    }

    /**
     * Called when connection is restored.
     * Triggers automatic retry of pending events.
     */
    fun onConnectionRestored() {
        scope.launch {
            // Every entry becomes due now: the relay that was unreachable is the reason they are
            // still queued, so making them wait out a backoff earned while it was down is wasted time.
            mutex.withLock {
                _pendingEvents.value = _pendingEvents.value.map { it.copy(lastAttemptAt = 0) }
            }
            retryPendingEvents()
            ensureRetrySweep()
        }
    }

    private suspend fun updateStatus(
        pendingId: String,
        status: PendingEventStatus,
    ) {
        mutex.withLock {
            _eventStatuses.value = _eventStatuses.value + (pendingId to status)
        }
    }

    private suspend fun incrementRetryCount(
        pendingId: String,
        errorMsg: String,
    ) {
        mutex.withLock {
            val current = _pendingEvents.value
            val event = current.find { it.id == pendingId } ?: return@withLock

            val updated =
                event.copy(
                    retryCount = event.retryCount + 1,
                    lastError = errorMsg,
                    lastAttemptAt = epochMillis(),
                )

            _pendingEvents.value = current.map { if (it.id == pendingId) updated else it }
            // The message stays Sending on screen: a transient failure is the queue's business,
            // not something to ask the user about.
            _eventStatuses.value = _eventStatuses.value + (pendingId to PendingEventStatus.Retrying(errorMsg))
        }
        saveToStorage()
    }

    private fun calculateRetryDelay(retryCount: Int): Long {
        // Exponential backoff: 1s, 2s, 4s, 8s, ... up to max. The shift is clamped because
        // retryCount is unbounded, and shifting past the width of an Int wraps to a short delay.
        val delay = BASE_RETRY_DELAY_MS shl retryCount.coerceIn(0, 20)
        return delay.coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    // ==========================================================================
    // PERSISTENCE
    // ==========================================================================

    /**
     * Load pending events from storage.
     */
    private fun loadFromStorage() {
        val pubkey = currentPubkey ?: return
        val eventsJson = SecureStorage.getPendingEvents(pubkey) ?: return

        try {
            val eventsArray = json.parseToJsonElement(eventsJson).jsonArray
            val events =
                eventsArray.mapNotNull { element ->
                    try {
                        val obj = element.jsonObject
                        PendingEvent(
                            id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                            eventJson = obj["eventJson"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                            eventId = obj["eventId"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                            groupId = obj["groupId"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                            createdAt = obj["createdAt"]?.jsonPrimitive?.long ?: epochMillis(),
                            retryCount = obj["retryCount"]?.jsonPrimitive?.int ?: 0,
                            lastError = obj["lastError"]?.jsonPrimitive?.contentOrNull,
                            lastAttemptAt = obj["lastAttemptAt"]?.jsonPrimitive?.long ?: 0,
                        )
                    } catch (e: Exception) {
                        null
                    }
                }

            if (events.isNotEmpty()) {
                _pendingEvents.value = events
                events.forEach { event ->
                    _eventStatuses.value = _eventStatuses.value + (event.id to PendingEventStatus.Queued)
                }
                ensureRetrySweep()
            }
        } catch (e: Exception) {
            // Ignore parsing errors
        }
    }

    /**
     * Save pending events to storage.
     */
    fun saveToStorage() {
        val pubkey = currentPubkey ?: return
        val events = _pendingEvents.value

        if (events.isEmpty()) {
            SecureStorage.clearPendingEvents(pubkey)
            return
        }

        try {
            val eventsJson =
                buildJsonArray {
                    events.forEach { event ->
                        add(
                            buildJsonObject {
                                put("id", event.id)
                                put("eventJson", event.eventJson)
                                put("eventId", event.eventId)
                                put("groupId", event.groupId)
                                put("createdAt", event.createdAt)
                                put("retryCount", event.retryCount)
                                put("lastAttemptAt", event.lastAttemptAt)
                                event.lastError?.let { put("lastError", it) }
                            },
                        )
                    }
                }.toString()

            SecureStorage.savePendingEvents(pubkey, eventsJson)
        } catch (e: Exception) {
            // Ignore save errors
        }
    }

    /**
     * Clear persisted pending events.
     */
    fun clearStorage() {
        val pubkey = currentPubkey ?: return
        SecureStorage.clearPendingEvents(pubkey)
    }
}
