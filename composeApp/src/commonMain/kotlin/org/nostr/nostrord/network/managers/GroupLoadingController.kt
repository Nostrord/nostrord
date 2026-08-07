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
import org.nostr.nostrord.utils.epochMillis

/**
 * Formal state machine for group message loading.
 *
 * State transitions are explicit, deterministic, and race-free.
 * Each group has its own independent state and lock.
 */

/**
 * Sealed hierarchy representing all possible loading states for a group.
 * Replaces ad-hoc boolean flags with explicit, type-safe states.
 */
sealed class GroupLoadingState {
    /** Group not loaded, no messages requested */
    data object Idle : GroupLoadingState()

    /** Initial message request sent, waiting for EOSE */
    data class InitialLoading(
        val subscriptionId: String,
        val startedAt: Long = epochMillis(),
    ) : GroupLoadingState()

    /** Messages loaded, more available for pagination */
    data class HasMore(
        val cursor: PaginationCursor,
    ) : GroupLoadingState()

    /** Pagination request in flight */
    data class Paginating(
        val subscriptionId: String,
        val cursor: PaginationCursor,
        val startedAt: Long = epochMillis(),
    ) : GroupLoadingState()

    /** All messages loaded, feed exhausted */
    data class Exhausted(
        val cursor: PaginationCursor,
    ) : GroupLoadingState()

    /**
     * Auto-pagination paused: several consecutive scroll-back REQs timed out with zero
     * events on the same frontier, so re-firing from scroll position alone would spin the
     * loading pill forever (each round is a full timeout window) against a relay that is
     * not answering. `hasMore` is false, which stops the scroll-position triggers; only an
     * explicit user retry (retryStalled → loadMore) resumes from [cursor].
     */
    data class Stalled(
        val cursor: PaginationCursor,
    ) : GroupLoadingState()

    /** Loading failed, can retry */
    data class Error(
        val cursor: PaginationCursor?,
        val reason: ErrorReason,
        val retryCount: Int = 0,
    ) : GroupLoadingState()

    /** Retry in progress after error */
    data class Retrying(
        val cursor: PaginationCursor?,
        val subscriptionId: String,
        val attemptNumber: Int,
    ) : GroupLoadingState()

    enum class ErrorReason {
        /** Relay never emitted EOSE and no messages arrived in the window. */
        TIMEOUT,

        /** Relay sent some messages then went silent without EOSE — cursor preserved. */
        PARTIAL_TIMEOUT,
        SEND_FAILED,
        DISCONNECTED,
    }

    // Helper properties for UI consumption
    val isLoading: Boolean get() = this is InitialLoading || this is Paginating || this is Retrying
    val hasMore: Boolean get() = this is HasMore || this is Paginating || this is Retrying
    val canLoadMore: Boolean get() = this is HasMore
}

/**
 * Immutable pagination cursor that tracks position independently of message list.
 *
 * Uses both timestamp and event ID for precise cursor positioning,
 * handling timestamp collisions correctly.
 */
data class PaginationCursor(
    /** Timestamp of the oldest message in current page */
    val untilTimestamp: Long,
    /** Event ID of the oldest message (for tie-breaking) */
    val lastEventId: String?,
    /** Total messages received across all pages */
    val totalReceived: Int,
    /** Number of pages loaded */
    val pageNumber: Int,
) {
    companion object {
        fun initial() = PaginationCursor(
            untilTimestamp = Long.MAX_VALUE,
            lastEventId = null,
            totalReceived = 0,
            pageNumber = 0,
        )
    }

    /**
     * Create next cursor from received messages.
     * Subtracts 1 from timestamp to handle relays with exclusive "until" semantics.
     */
    fun advance(
        oldestTimestamp: Long,
        oldestEventId: String,
        messagesReceived: Int,
    ): PaginationCursor = PaginationCursor(
        untilTimestamp = oldestTimestamp - 1, // Handle < vs <= relay differences
        lastEventId = oldestEventId,
        totalReceived = totalReceived + messagesReceived,
        pageNumber = pageNumber + 1,
    )
}

/**
 * Tracks subscription state for EOSE handling.
 * Supports future multi-relay quorum tracking.
 */
data class SubscriptionTracker(
    val subscriptionId: String,
    val groupId: String,
    val isInitialLoad: Boolean,
    val expectedRelays: Int = 1,
    val receivedEose: MutableSet<String> = mutableSetOf(), // relay URLs that sent EOSE
    var messageCount: Int = 0,
    var oldestTimestamp: Long = Long.MAX_VALUE,
    var oldestEventId: String? = null,
    val timeoutJob: Job? = null,
) {
    fun trackMessage(timestamp: Long, eventId: String) {
        messageCount++
        if (timestamp < oldestTimestamp) {
            oldestTimestamp = timestamp
            oldestEventId = eventId
        }
    }

    fun isComplete(quorumSize: Int = 1): Boolean = receivedEose.size >= quorumSize
}

/**
 * Per-group loading controller with its own mutex.
 * Manages state transitions and subscription tracking for a single group.
 *
 * [subIdPrefix] namespaces the generated subscription ids. NostrRepository routes inbound
 * frames by subId prefix, so a non-group consumer of this controller must pick its own.
 */
class GroupLoadingController(
    private val groupId: String,
    private val scope: CoroutineScope,
    private val pageSize: Int = 50,
    private val timeoutMs: Long = 10_000L,
    private val maxRetries: Int = 3,
    private val maxConsecutivePaginationTimeouts: Int = 3,
    private val subIdPrefix: String = "msg",
) {
    // Zero-event pagination timeouts in a row on the current frontier. Reset by any
    // completed page (EOSE) or partial delivery; reaching the cap transitions to Stalled.
    private var consecutivePaginationTimeouts = 0
    private val mutex = Mutex()

    private val _state = MutableStateFlow<GroupLoadingState>(GroupLoadingState.Idle)
    val state: StateFlow<GroupLoadingState> = _state.asStateFlow()

    /** The group this controller manages. */
    val id: String get() = groupId

    private var currentTracker: SubscriptionTracker? = null
    private var timeoutJob: Job? = null

    /**
     * Attempt to start initial message loading.
     * Returns subscription ID if started, null if already loading.
     *
     * Pass [armTimeout] = false when the caller defers the REQ behind a NIP-42 AUTH wait
     * (private groups on a remote signer): the state enters InitialLoading immediately so
     * the UI shows skeletons, but the load timeout is armed later via [armInitialTimeout]
     * once the REQ is actually on the wire, so a multi-second AUTH wait does not expire it.
     */
    suspend fun startInitialLoad(armTimeout: Boolean = true): String? = mutex.withLock {
        val currentState = _state.value

        // Only start from Idle or Error states
        when (currentState) {
            is GroupLoadingState.Idle,
            is GroupLoadingState.Error,
            -> {
                val subId = "${subIdPrefix}_${groupId.take(8)}_${epochMillis()}"
                val tracker = SubscriptionTracker(
                    subscriptionId = subId,
                    groupId = groupId,
                    isInitialLoad = true,
                )
                currentTracker = tracker

                _state.value = GroupLoadingState.InitialLoading(subId)
                if (armTimeout) startTimeout(subId, isInitial = true)

                subId
            }
            else -> null // Already loading or has messages
        }
    }

    /**
     * Arm the initial-load timeout once the REQ has actually been sent. Pairs with
     * [startInitialLoad] (armTimeout = false): starting the timeout at start-of-load time
     * would let it expire during a NIP-42 AUTH wait and fail a load that is merely waiting
     * to authenticate. No-op if [subscriptionId] is no longer the in-flight initial load.
     */
    suspend fun armInitialTimeout(subscriptionId: String) = mutex.withLock {
        val tracker = currentTracker ?: return@withLock
        if (tracker.subscriptionId != subscriptionId) return@withLock
        if (_state.value is GroupLoadingState.InitialLoading) {
            startTimeout(subscriptionId, isInitial = true)
        }
    }

    /**
     * The relay returned/CLOSED an in-flight INITIAL load while it still needs NIP-42 AUTH
     * (e.g. opening a private group from the homepage, where the first read races the AUTH
     * challenge the relay issues in response to it). Returns true to signal the caller it
     * must NOT settle this load: resubscribeAfterAuth replays the read once AUTH completes,
     * and keeping the state in InitialLoading lets the UI hold skeletons instead of flashing
     * a false "No messages yet". The load timeout stays armed, so a signer that never AUTHs
     * still terminates to Error. No-op (false) for pagination or once the load has settled.
     */
    suspend fun holdInitialLoadForReauth(subscriptionId: String): Boolean = mutex.withLock {
        val tracker = currentTracker ?: return@withLock false
        if (tracker.subscriptionId != subscriptionId) return@withLock false
        if (!tracker.isInitialLoad) return@withLock false
        _state.value is GroupLoadingState.InitialLoading
    }

    /**
     * Attempt to start pagination (load more messages).
     * Returns subscription ID and cursor if started, null if cannot paginate.
     */
    suspend fun startPagination(): Pair<String, PaginationCursor>? = mutex.withLock {
        val currentState = _state.value

        when (currentState) {
            is GroupLoadingState.HasMore -> {
                val subId = "${subIdPrefix}_${groupId.take(8)}_${epochMillis()}"
                val cursor = currentState.cursor
                val tracker = SubscriptionTracker(
                    subscriptionId = subId,
                    groupId = groupId,
                    isInitialLoad = false,
                )
                currentTracker = tracker

                _state.value = GroupLoadingState.Paginating(subId, cursor)
                startTimeout(subId, isInitial = false)

                Pair(subId, cursor)
            }
            else -> null // Not in pageable state
        }
    }

    /**
     * Track an incoming message for the current subscription.
     * Thread-safe: can be called from WebSocket callback.
     */
    suspend fun trackMessage(subscriptionId: String, timestamp: Long, eventId: String) {
        mutex.withLock {
            val tracker = currentTracker
            if (tracker?.subscriptionId == subscriptionId) {
                tracker.trackMessage(timestamp, eventId)
            }
        }
    }

    /**
     * Handle EOSE for a subscription.
     * Returns true if this was our subscription.
     */
    suspend fun handleEose(subscriptionId: String, relayUrl: String): Boolean {
        return mutex.withLock {
            val tracker = currentTracker ?: return@withLock false
            if (tracker.subscriptionId != subscriptionId) return@withLock false

            timeoutJob?.cancel()
            timeoutJob = null

            // Mark EOSE received from this relay
            tracker.receivedEose.add(relayUrl)

            // Check if we have quorum (currently single-relay, so always complete)
            if (!tracker.isComplete()) {
                return@withLock true // Wait for more relays
            }

            // Determine next state based on message count.
            // For the initial load, never mark Exhausted here: messages may arrive via other
            // subscriptions (mux/live) before EOSE and not be counted by this tracker, causing
            // a false Exhausted when there is still more history. The next (empty) pagination
            // page will correctly transition to Exhausted.
            // A completed round trip (EOSE) proves the relay is answering on this frontier.
            consecutivePaginationTimeouts = 0

            val messagesReceived = tracker.messageCount
            val isExhausted = !tracker.isInitialLoad && messagesReceived < pageSize

            val newCursor = if (tracker.oldestEventId != null) {
                (getCurrentCursor() ?: PaginationCursor.initial()).advance(
                    oldestTimestamp = tracker.oldestTimestamp,
                    oldestEventId = tracker.oldestEventId!!,
                    messagesReceived = messagesReceived,
                )
            } else {
                // No messages received - exhausted
                getCurrentCursor() ?: PaginationCursor.initial()
            }

            _state.value = if (isExhausted) {
                GroupLoadingState.Exhausted(newCursor)
            } else {
                GroupLoadingState.HasMore(newCursor)
            }

            currentTracker = null
            true
        }
    }

    /**
     * Handle send failure.
     * Preserves retry count if we were in a retry attempt.
     */
    suspend fun handleSendFailure(subscriptionId: String) {
        mutex.withLock {
            val tracker = currentTracker ?: return@withLock
            if (tracker.subscriptionId != subscriptionId) return@withLock

            timeoutJob?.cancel()
            timeoutJob = null

            // Preserve retry count from current state
            val existingRetryCount = getRetryCount()

            _state.value = GroupLoadingState.Error(
                cursor = getCurrentCursor(),
                reason = GroupLoadingState.ErrorReason.SEND_FAILED,
                retryCount = existingRetryCount,
            )
            currentTracker = null
        }
    }

    /**
     * Get current retry count from state (for preserving across transitions).
     */
    private fun getRetryCount(): Int = when (val s = _state.value) {
        is GroupLoadingState.Retrying -> s.attemptNumber
        is GroupLoadingState.Error -> s.retryCount
        else -> 0
    }

    /**
     * Handle connection lost.
     *
     * Preserves the pagination frontier exactly like [handleReconnect]: the cursor is a
     * timestamp bookmark, not tied to the dropped socket, so a group that has loaded history
     * stays HasMore and the next scroll resumes from the same frontier. Resetting a loaded
     * group to Idle here stranded it with hasMore=false when the reconnect's re-load never
     * fired (flaky relay), so pagination died until an app restart. Only never-loaded groups
     * (no cursor) go Idle so reconnect re-runs the initial load; the mux re-establishes the
     * live feed and backfills the gap.
     */
    suspend fun handleDisconnect() {
        mutex.withLock {
            timeoutJob?.cancel()
            timeoutJob = null
            currentTracker = null
            val cursor = getCurrentCursor()
            _state.value = if (cursor != null) {
                GroupLoadingState.HasMore(cursor)
            } else {
                GroupLoadingState.Idle
            }
        }
    }

    /**
     * Handle a reconnect/re-subscribe while PRESERVING pagination progress.
     *
     * Keeps the cursor whenever the group has loaded history (any cursor, not only after a
     * manual page). The cursor is a timestamp bookmark, not tied to the dropped socket, so
     * there is no reason to discard it on reconnect. Resetting to Idle here caused the initial
     * load to re-fire with `until = oldest message - 1`; when the oldest message is a
     * bulk-delivered moderation event (an old join/leave far older than the paginated chat
     * frontier), that jumps straight to the floor and marks the group Exhausted, silently
     * skipping all the un-paginated middle history. It also stranded a freshly-loaded group
     * (HasMore, pageNumber 0) on Idle/hasMore=false when the re-load never fired. Only a group
     * still on its initial load (no cursor yet) falls back to Idle so a fresh load runs. The
     * live feed is re-established separately by the mux refresh, so preserving the cursor
     * loses nothing.
     */
    suspend fun handleReconnect() {
        mutex.withLock {
            timeoutJob?.cancel()
            timeoutJob = null
            currentTracker = null
            val cursor = getCurrentCursor()
            _state.value = if (cursor != null) {
                GroupLoadingState.HasMore(cursor)
            } else {
                GroupLoadingState.Idle
            }
        }
    }

    /**
     * Retry after error.
     * Returns subscription ID if retry started.
     */
    suspend fun retry(): String? = mutex.withLock {
        val currentState = _state.value

        when (currentState) {
            is GroupLoadingState.Error -> {
                if (currentState.retryCount >= maxRetries) {
                    return@withLock null // Max retries exceeded
                }

                val subId = "${subIdPrefix}_${groupId.take(8)}_${epochMillis()}"
                val tracker = SubscriptionTracker(
                    subscriptionId = subId,
                    groupId = groupId,
                    isInitialLoad = currentState.cursor == null,
                )
                currentTracker = tracker

                _state.value = GroupLoadingState.Retrying(
                    cursor = currentState.cursor,
                    subscriptionId = subId,
                    attemptNumber = currentState.retryCount + 1,
                )
                startTimeout(subId, isInitial = currentState.cursor == null)

                subId
            }
            else -> null
        }
    }

    /**
     * Reset to Idle state.
     */
    suspend fun reset() {
        mutex.withLock {
            timeoutJob?.cancel()
            timeoutJob = null
            currentTracker = null
            consecutivePaginationTimeouts = 0
            _state.value = GroupLoadingState.Idle
        }
    }

    /**
     * Resume pagination from [GroupLoadingState.Stalled] — the UI's explicit retry.
     * Flips back to HasMore at the stalled frontier (one fresh attempt; another
     * zero-event timeout re-stalls immediately). Returns false in any other state.
     */
    suspend fun retryStalled(): Boolean = mutex.withLock {
        val s = _state.value as? GroupLoadingState.Stalled ?: return@withLock false
        consecutivePaginationTimeouts = maxConsecutivePaginationTimeouts - 1
        _state.value = GroupLoadingState.HasMore(s.cursor)
        true
    }

    /**
     * Check if a subscription ID belongs to this controller.
     */
    suspend fun ownsSubscription(subscriptionId: String): Boolean = mutex.withLock {
        currentTracker?.subscriptionId == subscriptionId
    }

    private fun getCurrentCursor(): PaginationCursor? = when (val s = _state.value) {
        is GroupLoadingState.HasMore -> s.cursor
        is GroupLoadingState.Paginating -> s.cursor
        is GroupLoadingState.Exhausted -> s.cursor
        is GroupLoadingState.Error -> s.cursor
        is GroupLoadingState.Retrying -> s.cursor
        is GroupLoadingState.Stalled -> s.cursor
        else -> null
    }

    private fun startTimeout(subscriptionId: String, isInitial: Boolean) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(timeoutMs)
            handleTimeout(subscriptionId, isInitial)
        }
    }

    private suspend fun handleTimeout(subscriptionId: String, isInitial: Boolean) {
        mutex.withLock {
            val tracker = currentTracker ?: return@withLock
            if (tracker.subscriptionId != subscriptionId) return@withLock

            val cursor = getCurrentCursor()

            val advancedCursor = if (tracker.messageCount > 0 && tracker.oldestEventId != null) {
                (cursor ?: PaginationCursor.initial()).advance(
                    oldestTimestamp = tracker.oldestTimestamp,
                    oldestEventId = tracker.oldestEventId!!,
                    messagesReceived = tracker.messageCount,
                )
            } else {
                cursor
            }

            // A pagination REQ that times out (no EOSE in the window) must never strand the group.
            // Switching groups rapidly tears down and recreates subscriptions, and the relay can
            // drop an in-flight pagination sub, so its EOSE never arrives and this fires with the
            // sub still mid-page. Revert to HasMore (keeping the cursor frontier) so the user's
            // next scroll retries from the same place. Settling on Error set hasMore=false, which
            // hid pagination on web until an app restart. Initial-load timeouts (no cursor) still
            // go to Error so the load screen can show its retry affordance.
            //
            // But not forever: with the view parked at the top, each HasMore revert re-fires the
            // scroll trigger immediately, so an unresponsive relay turns into an endless loop of
            // timeout windows with the loading pill up. Zero-event timeouts count toward a cap;
            // at the cap the group goes Stalled (hasMore=false) and pagination resumes only from
            // the UI's explicit retry. Partial delivery is progress, not a stall: reset.
            _state.value = if (!isInitial && advancedCursor != null) {
                if (tracker.messageCount > 0) {
                    consecutivePaginationTimeouts = 0
                    GroupLoadingState.HasMore(advancedCursor)
                } else if (++consecutivePaginationTimeouts >= maxConsecutivePaginationTimeouts) {
                    GroupLoadingState.Stalled(advancedCursor)
                } else {
                    GroupLoadingState.HasMore(advancedCursor)
                }
            } else {
                GroupLoadingState.Error(
                    cursor = advancedCursor,
                    reason = if (tracker.messageCount > 0) {
                        GroupLoadingState.ErrorReason.PARTIAL_TIMEOUT
                    } else {
                        GroupLoadingState.ErrorReason.TIMEOUT
                    },
                    retryCount = getRetryCount(),
                )
            }

            currentTracker = null
        }
    }
}

/**
 * Registry of per-group loading controllers.
 * Provides O(1) lookup by group ID and subscription ID.
 */
class GroupLoadingRegistry(
    private val scope: CoroutineScope,
    private val pageSize: Int = 50,
    private val timeoutMs: Long = 10_000L,
    private val subIdPrefix: String = "msg",
) {
    private val mutex = Mutex()
    private val controllers = mutableMapOf<String, GroupLoadingController>()

    // Reverse lookup: subscriptionId -> controller for O(1) lookup
    private val subscriptionToController = mutableMapOf<String, GroupLoadingController>()

    /**
     * Get or create a controller for a group.
     */
    suspend fun getController(groupId: String): GroupLoadingController = mutex.withLock {
        controllers.getOrPut(groupId) {
            GroupLoadingController(groupId, scope, pageSize, timeoutMs, subIdPrefix = subIdPrefix)
        }
    }

    /**
     * Register a subscription ID for O(1) lookup.
     * Called when a new subscription is started.
     */
    suspend fun registerSubscription(subscriptionId: String, controller: GroupLoadingController) {
        mutex.withLock {
            subscriptionToController[subscriptionId] = controller
        }
    }

    /**
     * Unregister a subscription ID.
     * Called when subscription completes (EOSE, timeout, error).
     */
    suspend fun unregisterSubscription(subscriptionId: String) {
        mutex.withLock {
            subscriptionToController.remove(subscriptionId)
        }
    }

    /**
     * Find controller by subscription ID - O(1) lookup.
     */
    suspend fun findBySubscription(subscriptionId: String): GroupLoadingController? = mutex.withLock {
        subscriptionToController[subscriptionId]
    }

    /**
     * Get state for a group (for UI binding).
     */
    suspend fun getState(groupId: String): StateFlow<GroupLoadingState> = getController(groupId).state

    /**
     * Remove a group's controller (on leave/clear).
     */
    suspend fun remove(groupId: String) {
        mutex.withLock {
            val controller = controllers.remove(groupId)
            // Remove any subscriptions for this controller
            subscriptionToController.entries.removeAll { it.value == controller }
            controller?.reset()
        }
    }

    /**
     * Clear all controllers.
     */
    suspend fun clear() {
        val toReset = mutex.withLock {
            val all = controllers.values.toList()
            controllers.clear()
            subscriptionToController.clear()
            all
        }
        toReset.forEach { it.reset() }
    }

    /**
     * Handle EOSE by finding the right controller - O(1) lookup.
     * Also unregisters the subscription.
     */
    suspend fun handleEose(subscriptionId: String, relayUrl: String): Boolean {
        val controller = findBySubscription(subscriptionId) ?: return false
        val result = controller.handleEose(subscriptionId, relayUrl)
        if (result) {
            unregisterSubscription(subscriptionId)
        }
        return result
    }

    /**
     * Track a message for the given subscription - O(1) lookup.
     * This is synchronous-safe (called from coroutine context).
     */
    suspend fun trackMessage(subscriptionId: String, timestamp: Long, eventId: String) {
        findBySubscription(subscriptionId)?.trackMessage(subscriptionId, timestamp, eventId)
    }

    /**
     * Handle disconnect for all active controllers.
     */
    suspend fun handleDisconnectAll() {
        val snapshot = mutex.withLock {
            subscriptionToController.clear() // Clear all pending subscriptions
            controllers.values.toList()
        }
        snapshot.forEach { it.handleDisconnect() }
    }

    /**
     * Handle disconnect for a specific set of groups (e.g. when a pool relay drops).
     * Resets only the affected groups to Idle so startInitialLoad() can re-subscribe.
     */
    suspend fun handleDisconnectForGroups(groupIds: List<String>) {
        val snapshot = mutex.withLock {
            groupIds.mapNotNull { controllers[it] }.also { affected ->
                subscriptionToController.entries.removeAll { e -> e.value in affected }
            }
        }
        snapshot.forEach { it.handleDisconnect() }
    }

    /**
     * Handle reconnect for a specific set of groups, PRESERVING pagination cursors.
     * See [GroupLoadingController.handleReconnect]. Used by resubscribeAllGroups so a
     * reconnect mid-pagination does not discard how far back the user has scrolled.
     */
    suspend fun handleReconnectForGroups(groupIds: List<String>) {
        val snapshot = mutex.withLock {
            groupIds.mapNotNull { controllers[it] }.also { affected ->
                subscriptionToController.entries.removeAll { e -> e.value in affected }
            }
        }
        snapshot.forEach { it.handleReconnect() }
    }
}
