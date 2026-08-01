package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.UnreadEntry
import org.nostr.nostrord.storage.getUnreadEntries
import org.nostr.nostrord.storage.saveUnreadEntries
import org.nostr.nostrord.utils.epochSeconds

class UnreadManager(
    private val isJoined: (String) -> Boolean = { true },
    private val isRestricted: (String) -> Boolean = { false },
    private val isAppFocused: () -> Boolean = { true },
    private val findMessageAuthor: (messageId: String) -> String? = { null },
    // NIP-51 muted authors: their messages/reactions never bump badges or notify.
    // Checked per event (not per group) so unread counts match the filtered chat view.
    private val isMutedAuthor: (pubkey: String) -> Boolean = { false },
    // Per-group notification gate (issue #70). `isDirect` is true for replies,
    // mentions and reactions to the user's own message. Returning false suppresses
    // the notification callbacks below WITHOUT touching the unread badge — a muted
    // or "mentions only" group still accumulates its unread count.
    private val shouldNotify: (groupId: String, isDirect: Boolean) -> Boolean = { _, _ -> true },
    private val onUnreadIncrement: ((groupId: String, latestMessage: NostrGroupClient.NostrMessage, delta: Int) -> Unit)? = null,
    private val onReplyNotify: ((groupId: String, message: NostrGroupClient.NostrMessage) -> Unit)? = null,
    private val onMentionNotify: ((groupId: String, message: NostrGroupClient.NostrMessage) -> Unit)? = null,
    private val onReactionNotify: ((groupId: String, reaction: NostrGroupClient.NostrReaction) -> Unit)? = null,
    // A kind:1111 reply under a thread event of ours. Fired instead of [onReplyNotify] so the
    // entry can deep-link into the threads pane rather than the chat.
    private val onThreadReplyNotify: ((groupId: String, reply: NostrGroupClient.NostrMessage) -> Unit)? = null,
    // Called when a group is marked read so the notification feed can drop the
    // group's entries in lockstep with the unread badge (issue #67).
    private val onGroupRead: ((groupId: String) -> Unit)? = null,
    // Background scope (Dispatchers.Default) for the SecureStorage writes. The badge update is an
    // in-memory StateFlow write that stays on the caller; only the EncryptedSharedPreferences
    // encryption + I/O is offloaded so marking-read can't block the Main thread (ANR on Android).
    private val scope: CoroutineScope,
) {

    private val _unreadCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadCounts: StateFlow<Map<String, Int>> = _unreadCounts.asStateFlow()

    // High-water mark per group: createdAt of the newest message we've already
    // processed. Used as an extra anchor floor so re-delivered history (across
    // reconnects or restarts) doesn't double-count.
    private val _latestMessageTimestamps = MutableStateFlow<Map<String, Long>>(emptyMap())
    val latestMessageTimestamps: StateFlow<Map<String, Long>> = _latestMessageTimestamps.asStateFlow()

    private var currentPubkey: String? = null
    private var activeGroupId: String? = null

    // First-time-seen anchor for groups with no persisted lastRead. Prevents the
    // initial history sync from inflating the badge for groups never opened.
    private val firstSeenAtByGroup = mutableMapOf<String, Long>()

    // When a switch-in catch-up is active, the "first seen" fallback below is
    // pinned to this value (seconds) instead of `epochSeconds()`. Without
    // this, every event arriving via catch-up gets filtered as "older than
    // first encounter" because firstSeenAt = now while the event predates the
    // switch. Set by [setCatchUpAnchor] and cleared automatically when stale.
    @kotlin.concurrent.Volatile
    private var catchUpAnchorSeconds: Long? = null

    @kotlin.concurrent.Volatile
    private var catchUpAnchorSetAt: Long = 0L
    private val CATCH_UP_ANCHOR_TTL_S = 60L

    /**
     * Use [seconds] as the "first seen" fallback anchor for groups without a
     * persisted lastRead. Effective for [CATCH_UP_ANCHOR_TTL_S] seconds. Pass
     * null to clear. Called from `reloadForActiveAccount` right after a switch.
     */
    fun setCatchUpAnchor(seconds: Long?) {
        catchUpAnchorSeconds = seconds
        catchUpAnchorSetAt = if (seconds != null) epochSeconds() else 0L
    }

    private fun firstSeenFallback(): Long {
        val s = catchUpAnchorSeconds
        return if (s != null && epochSeconds() - catchUpAnchorSetAt <= CATCH_UP_ANCHOR_TTL_S) {
            s
        } else {
            catchUpAnchorSeconds = null
            epochSeconds()
        }
    }

    fun setActiveGroup(groupId: String?) {
        val previous = activeGroupId
        activeGroupId = groupId
        // markAsRead the outgoing group so messages buffered inside GroupManager's
        // 300ms ordering window — flushed after the screen unmounts — don't
        // retroactively count as unread.
        if (previous != null && previous != groupId) markAsRead(previous)
    }

    fun initialize(pubkey: String?) {
        currentPubkey = pubkey
        firstSeenAtByGroup.clear()
        if (pubkey == null) {
            _unreadCounts.value = emptyMap()
            _latestMessageTimestamps.value = emptyMap()
            return
        }
        val persisted = SecureStorage.getUnreadEntries(pubkey)
        _unreadCounts.value = persisted.mapValues { it.value.count }
        _latestMessageTimestamps.value = persisted.mapValues { it.value.highWater }
    }

    fun markAsRead(groupId: String) {
        val pubkey = currentPubkey ?: return
        val now = epochSeconds()
        // In-memory badge clear + feed drop happen immediately; the storage writes go off-Main.
        _unreadCounts.update { it + (groupId to 0) }
        onGroupRead?.invoke(groupId)
        scope.launch {
            SecureStorage.saveLastReadTimestamp(pubkey, groupId, now)
            persistEntries()
        }
    }

    /**
     * Advance the last-read timestamp to [timestamp] for partial-read tracking.
     * Used when the chat scroll passes individual messages — fixes the Telegram
     * "scrolled 1 of 10 unread → marked all 10 as read" class of bug by only
     * persisting how far the user actually got. The counter is cleared only when
     * [timestamp] catches up to the high-water mark (everything seen); otherwise
     * the counter is left alone and the next markAsRead clears it.
     */
    fun markAsReadUpTo(groupId: String, timestamp: Long) {
        val pubkey = currentPubkey ?: return
        // Off-Main: this runs per scroll-past, and the read + writes are EncryptedSharedPreferences
        // I/O that must not block the scroll on the Main thread.
        scope.launch {
            val stored = SecureStorage.getLastReadTimestamp(pubkey, groupId) ?: 0L
            if (timestamp <= stored) return@launch
            SecureStorage.saveLastReadTimestamp(pubkey, groupId, timestamp)
            val highWater = _latestMessageTimestamps.value[groupId] ?: 0L
            if (timestamp >= highWater) {
                _unreadCounts.update { it + (groupId to 0) }
                onGroupRead?.invoke(groupId)
            }
            persistEntries()
        }
    }

    fun getLastReadTimestamp(groupId: String): Long? = currentPubkey?.let { SecureStorage.getLastReadTimestamp(it, groupId) }

    fun getUnreadCount(groupId: String): Int = _unreadCounts.value[groupId] ?: 0

    fun onMessagesFlushed(groupId: String, newMessages: List<NostrGroupClient.NostrMessage>) {
        val pubkey = currentPubkey ?: return
        if (newMessages.isEmpty()) return
        if (!isJoined(groupId) || isRestricted(groupId)) return

        // Capture the previous high-water *before* advancing it, so the anchor
        // can use it to filter re-delivered history that was already counted.
        val previousHighWater = _latestMessageTimestamps.value[groupId] ?: 0L
        val latestInBatch = newMessages.maxOfOrNull { it.createdAt } ?: 0L
        val highWaterAdvanced = latestInBatch > previousHighWater
        if (highWaterAdvanced) {
            _latestMessageTimestamps.update { it + (groupId to latestInBatch) }
        }

        val isActive = groupId == activeGroupId
        // Active group + app focused: user is reading live — silent. Persist
        // any high-water advance so a future session doesn't re-process.
        if (isActive && isAppFocused()) {
            if (highWaterAdvanced) persistEntries()
            return
        }

        val lastRead = SecureStorage.getLastReadTimestamp(pubkey, groupId)
        val anchor = maxOf(
            lastRead ?: firstSeenAtByGroup.getOrPut(groupId) { firstSeenFallback() },
            previousHighWater,
        )
        val qualifying = newMessages.filter {
            it.kind == 9 && it.pubkey != pubkey && it.createdAt > anchor && !isMutedAuthor(it.pubkey)
        }
        if (qualifying.isEmpty()) {
            if (highWaterAdvanced) persistEntries()
            return
        }

        // Active-but-unfocused: still notify (sound + popup) but don't bump the
        // badge — refocus shows the messages immediately, marking them as read.
        if (!isActive) {
            _unreadCounts.update { current ->
                current + (groupId to ((current[groupId] ?: 0) + qualifying.size))
            }
        }
        persistEntries()

        // NIP-29 marks replies with a "q" (quote) tag; NIP-10 chats use "e".
        // Accept both so replies are classified correctly regardless of which
        // client posted them. A message counts as a reply to us when it is a
        // reply event AND either we can confirm from cache that the parent is
        // ours, or it directly p-tags us (set by the sender so we're notified
        // even when the parent message isn't loaded locally — see #70).
        val latestReply = qualifying.filter { msg ->
            val isReplyEvent = msg.tags.any { it.size >= 2 && (it[0] == "q" || it[0] == "e") }
            if (!isReplyEvent) {
                false
            } else {
                val parentIsMine = msg.tags.any { tag ->
                    tag.size >= 2 &&
                        (tag[0] == "q" || tag[0] == "e") &&
                        findMessageAuthor(tag[1]) == pubkey
                }
                val pTagsMe = msg.tags.any { it.size >= 2 && it[0] == "p" && it[1] == pubkey }
                parentIsMine || pTagsMe
            }
        }.maxByOrNull { it.createdAt }

        val latestMention = qualifying.filter { msg ->
            msg.tags.any { tag -> tag.size >= 2 && tag[0] == "p" && tag[1] == pubkey }
        }.maxByOrNull { it.createdAt }

        when {
            latestReply != null ->
                if (shouldNotify(groupId, true)) onReplyNotify?.invoke(groupId, latestReply)
            latestMention != null ->
                if (shouldNotify(groupId, true)) onMentionNotify?.invoke(groupId, latestMention)
            else ->
                if (shouldNotify(groupId, false)) {
                    onUnreadIncrement?.invoke(groupId, qualifying.maxBy { it.createdAt }, qualifying.size)
                }
        }
    }

    /**
     * A forum thread event (kind:11 root or kind:1111 reply) arrived from a relay.
     *
     * Only replies under something of ours notify: a reply to your thread root or to one of your
     * replies, or one that p-tags you. New roots by others are group activity, not an interaction,
     * and would turn every thread into a push.
     *
     * The group's unread badge and high-water are deliberately untouched. Those count chat
     * messages and are cleared by reading the chat; letting a thread reply bump them would leave
     * a badge that opening the chat cannot clear.
     *
     * No lastRead/high-water anchor either, unlike chat: those track chat reading, so a group
     * whose chat was just opened would silently swallow every thread reply that follows within
     * the same second. Re-announcing is handled where it belongs - the feed dedupes by event id,
     * and AppModule gates sound and popup on the event being realtime.
     */
    fun onThreadEventReceived(groupId: String, event: NostrGroupClient.NostrMessage) {
        val pubkey = currentPubkey
        val verdict = when {
            pubkey == null -> "no-session"
            event.kind != 1111 -> "not-a-reply(${event.kind})"
            event.pubkey == pubkey -> "own-reply"
            isMutedAuthor(event.pubkey) -> "muted-author"
            !isJoined(groupId) -> "not-joined"
            !threadReplyTargetsMe(event.tags, pubkey, findMessageAuthor) -> "not-for-me"
            !shouldNotify(groupId, true) -> "group-muted"
            else -> null
        }
        if (verdict != null) return
        onThreadReplyNotify?.invoke(groupId, event)
    }

    fun onReactionReceived(groupId: String, reaction: NostrGroupClient.NostrReaction) {
        val pubkey = currentPubkey ?: return
        if (reaction.pubkey == pubkey) return
        if (isMutedAuthor(reaction.pubkey)) return
        if (!isJoined(groupId) || isRestricted(groupId)) return
        val lastRead = SecureStorage.getLastReadTimestamp(pubkey, groupId)
        val previousHighWater = _latestMessageTimestamps.value[groupId] ?: 0L
        val anchor = maxOf(
            lastRead ?: firstSeenAtByGroup.getOrPut(groupId) { firstSeenFallback() },
            previousHighWater,
        )
        if (reaction.createdAt <= anchor) return

        // Reactions on the user's own message are direct interactions worth
        // surfacing on the group/relay badges, not just the notification feed.
        // Caller already verified the target message author == self.
        val highWaterAdvanced = reaction.createdAt > previousHighWater
        if (highWaterAdvanced) {
            _latestMessageTimestamps.update { it + (groupId to reaction.createdAt) }
        }

        // Mirror onMessagesFlushed's active/focus handling so reactions don't behave
        // differently from messages for the group the user is looking at.
        val isActive = groupId == activeGroupId
        // Active group + app focused: user is reading live — silent.
        if (isActive && isAppFocused()) {
            persistEntries()
            return
        }
        // Only inactive groups bump the badge. An active-but-unfocused group still
        // notifies below, but its badge stays clear — the group is open, so the
        // reaction is seen on refocus (matches messages; fixes the relay count
        // appearing for the group you're currently viewing).
        if (!isActive) {
            _unreadCounts.update { current ->
                current + (groupId to ((current[groupId] ?: 0) + 1))
            }
        }
        persistEntries()

        // A reaction is always to the user's own message, so it counts as a
        // direct interaction for the notification gate.
        if (shouldNotify(groupId, true)) onReactionNotify?.invoke(groupId, reaction)
    }

    fun clear() {
        currentPubkey = null
        activeGroupId = null
        _unreadCounts.value = emptyMap()
        _latestMessageTimestamps.value = emptyMap()
        firstSeenAtByGroup.clear()
    }

    private fun persistEntries() {
        val pubkey = currentPubkey ?: return
        val counts = _unreadCounts.value
        val highWaters = _latestMessageTimestamps.value
        val entries = (counts.keys + highWaters.keys).associateWith { id ->
            UnreadEntry(counts[id] ?: 0, highWaters[id] ?: 0L)
        }
        SecureStorage.saveUnreadEntries(pubkey, entries)
    }
}

/**
 * Whether a kind:1111 reply is a reply to [pubkey], read from the reply's own NIP-22 tags.
 *
 * The tags are authoritative and need no local state: uppercase `P` is the thread root's author
 * and `E`/`e` carry theirs in the 4th element. That matters because a top-level reply carries no
 * lowercase `p` (the parent IS the root, and duplicating the triple trips relays that cap
 * indexable tags), and the root itself is usually not in memory on a device that never opened
 * the pane. [findMessageAuthor] stays as the last resort for clients that send a leaner tag set.
 */
internal fun threadReplyTargetsMe(
    tags: List<List<String>>,
    pubkey: String,
    findMessageAuthor: (String) -> String?,
): Boolean {
    val pTagsMe = tags.any { it.size >= 2 && (it[0] == "p" || it[0] == "P") && it[1] == pubkey }
    if (pTagsMe) return true
    val scopeAuthorIsMe = tags.any { it.size >= 4 && (it[0] == "E" || it[0] == "e") && it[3] == pubkey }
    if (scopeAuthorIsMe) return true
    return tags.any { it.size >= 2 && (it[0] == "E" || it[0] == "e") && findMessageAuthor(it[1]) == pubkey }
}
