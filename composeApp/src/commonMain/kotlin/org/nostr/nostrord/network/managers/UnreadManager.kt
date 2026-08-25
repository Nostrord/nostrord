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
import org.nostr.nostrord.storage.lastReadFor
import org.nostr.nostrord.storage.saveLastReadFor
import org.nostr.nostrord.storage.saveUnreadEntries
import org.nostr.nostrord.utils.epochSeconds
import org.nostr.nostrord.utils.groupKey
import org.nostr.nostrord.utils.groupKeyId
import org.nostr.nostrord.utils.groupKeyRelay
import org.nostr.nostrord.utils.isGroupKey
import org.nostr.nostrord.utils.normalizeRelayUrl

/**
 * Unread badges and notification triggers, keyed by (relay, group id).
 *
 * The same group id on two relays is two distinct groups: reading one must not clear
 * the other's badge, and a message on one must not raise a badge on the other. Every
 * map here is keyed by [groupKey]; the bare id alone is never a key.
 */
class UnreadManager(
    private val isJoined: (relayUrl: String, groupId: String) -> Boolean = { _, _ -> true },
    private val isRestricted: (relayUrl: String, groupId: String) -> Boolean = { _, _ -> false },
    private val isAppFocused: () -> Boolean = { true },
    private val findMessageAuthor: (messageId: String) -> String? = { null },
    // NIP-51 muted authors: their messages/reactions never bump badges or notify.
    // Checked per event (not per group) so unread counts match the filtered chat view.
    private val isMutedAuthor: (pubkey: String) -> Boolean = { false },
    // Per-group notification gate (issue #70). `isDirect` is true for replies,
    // mentions and reactions to the user's own message. Returning false suppresses
    // the notification callbacks below WITHOUT touching the unread badge — a muted
    // or "mentions only" group still accumulates its unread count.
    private val shouldNotify: (relayUrl: String, groupId: String, isDirect: Boolean) -> Boolean = { _, _, _ -> true },
    private val onUnreadIncrement: ((relayUrl: String, groupId: String, latestMessage: NostrGroupClient.NostrMessage, delta: Int) -> Unit)? = null,
    private val onReplyNotify: ((relayUrl: String, groupId: String, message: NostrGroupClient.NostrMessage) -> Unit)? = null,
    private val onMentionNotify: ((relayUrl: String, groupId: String, message: NostrGroupClient.NostrMessage) -> Unit)? = null,
    private val onReactionNotify: ((relayUrl: String, groupId: String, reaction: NostrGroupClient.NostrReaction) -> Unit)? = null,
    // A kind:1111 reply under a thread event of ours. Fired instead of [onReplyNotify] so the
    // entry can deep-link into the threads pane rather than the chat.
    private val onThreadReplyNotify: ((relayUrl: String, groupId: String, reply: NostrGroupClient.NostrMessage) -> Unit)? = null,
    // Called when a group is marked read so the notification feed can drop the
    // group's entries in lockstep with the unread badge (issue #67).
    private val onGroupRead: ((relayUrl: String, groupId: String) -> Unit)? = null,
    // Background scope (Dispatchers.Default) for the SecureStorage writes. The badge update is an
    // in-memory StateFlow write that stays on the caller; only the EncryptedSharedPreferences
    // encryption + I/O is offloaded so marking-read can't block the Main thread (ANR on Android).
    private val scope: CoroutineScope,
) {

    /** Counts keyed by [groupKey], not by group id. */
    private val _unreadByGroupKey = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadByGroupKey: StateFlow<Map<String, Int>> = _unreadByGroupKey.asStateFlow()

    // High-water mark per (relay, group): createdAt of the newest message we've
    // already processed. Used as an extra anchor floor so re-delivered history
    // (across reconnects or restarts) doesn't double-count.
    private val _latestMessageTimestamps = MutableStateFlow<Map<String, Long>>(emptyMap())
    val latestMessageTimestamps: StateFlow<Map<String, Long>> = _latestMessageTimestamps.asStateFlow()

    private var currentPubkey: String? = null
    private var activeGroupKey: String? = null

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

    fun setActiveGroup(
        relayUrl: String?,
        groupId: String?,
    ) {
        val previous = activeGroupKey
        val next = if (relayUrl != null && groupId != null) groupKey(relayUrl, groupId) else null
        activeGroupKey = next
        // markAsRead the outgoing group so messages buffered inside GroupManager's
        // 300ms ordering window — flushed after the screen unmounts — don't
        // retroactively count as unread.
        if (previous != null && previous != next) {
            markAsRead(groupKeyRelay(previous), groupKeyId(previous))
        }
    }

    fun initialize(pubkey: String?) {
        currentPubkey = pubkey
        firstSeenAtByGroup.clear()
        if (pubkey == null) {
            _unreadByGroupKey.value = emptyMap()
            _latestMessageTimestamps.value = emptyMap()
            return
        }
        // Legacy bare-id entries are dropped: their relay is unknowable, and attributing
        // them to a guess is what put a count on the wrong same-id group. The badge
        // reseeds from live traffic; the read anchor survives via [lastReadFor]'s fallback.
        val persisted = SecureStorage.getUnreadEntries(pubkey).filterKeys { isGroupKey(it) }
        _unreadByGroupKey.value = persisted.mapValues { it.value.count }
        _latestMessageTimestamps.value = persisted.mapValues { it.value.highWater }
    }

    fun markAsRead(
        relayUrl: String,
        groupId: String,
    ) {
        val pubkey = currentPubkey ?: return
        val key = groupKey(relayUrl, groupId)
        val now = epochSeconds()
        // In-memory badge clear + feed drop happen immediately; the storage writes go off-Main.
        _unreadByGroupKey.update { it + (key to 0) }
        onGroupRead?.invoke(relayUrl.normalizeRelayUrl(), groupId)
        scope.launch {
            SecureStorage.saveLastReadFor(pubkey, relayUrl, groupId, now)
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
    fun markAsReadUpTo(
        relayUrl: String,
        groupId: String,
        timestamp: Long,
    ) {
        val pubkey = currentPubkey ?: return
        val key = groupKey(relayUrl, groupId)
        // Off-Main: this runs per scroll-past, and the read + writes are EncryptedSharedPreferences
        // I/O that must not block the scroll on the Main thread.
        scope.launch {
            val stored = SecureStorage.lastReadFor(pubkey, relayUrl, groupId) ?: 0L
            if (timestamp <= stored) return@launch
            SecureStorage.saveLastReadFor(pubkey, relayUrl, groupId, timestamp)
            val highWater = _latestMessageTimestamps.value[key] ?: 0L
            if (timestamp >= highWater) {
                _unreadByGroupKey.update { it + (key to 0) }
                onGroupRead?.invoke(relayUrl.normalizeRelayUrl(), groupId)
            }
            persistEntries()
        }
    }

    fun getLastReadTimestamp(
        relayUrl: String,
        groupId: String,
    ): Long? = currentPubkey?.let { SecureStorage.lastReadFor(it, relayUrl, groupId) }

    fun getUnreadCount(
        relayUrl: String,
        groupId: String,
    ): Int = _unreadByGroupKey.value[groupKey(relayUrl, groupId)] ?: 0

    /**
     * Chat messages left GroupManager's ordering buffer. The bucket is shared by every
     * relay serving [groupId], so split by origin relay first: each same-id group owns
     * its badge, its read anchor and its notifications.
     *
     * Messages without an origin relay are optimistic local sends, which never qualify.
     */
    fun onMessagesFlushed(
        groupId: String,
        newMessages: List<NostrGroupClient.NostrMessage>,
    ) {
        if (newMessages.isEmpty()) return
        newMessages
            .groupBy { it.relayUrl?.normalizeRelayUrl() }
            .forEach { (relayUrl, batch) ->
                if (relayUrl != null) onMessagesFlushed(relayUrl, groupId, batch)
            }
    }

    private fun onMessagesFlushed(
        relayUrl: String,
        groupId: String,
        newMessages: List<NostrGroupClient.NostrMessage>,
    ) {
        val pubkey = currentPubkey ?: return
        if (!isJoined(relayUrl, groupId) || isRestricted(relayUrl, groupId)) return
        val key = groupKey(relayUrl, groupId)

        // Capture the previous high-water *before* advancing it, so the anchor
        // can use it to filter re-delivered history that was already counted.
        val previousHighWater = _latestMessageTimestamps.value[key] ?: 0L
        val latestInBatch = newMessages.maxOfOrNull { it.createdAt } ?: 0L
        val highWaterAdvanced = latestInBatch > previousHighWater
        if (highWaterAdvanced) {
            _latestMessageTimestamps.update { it + (key to latestInBatch) }
        }

        val isActive = key == activeGroupKey
        // Active group + app focused: user is reading live — silent. Persist
        // any high-water advance so a future session doesn't re-process.
        if (isActive && isAppFocused()) {
            if (highWaterAdvanced) persistEntries()
            return
        }

        val lastRead = SecureStorage.lastReadFor(pubkey, relayUrl, groupId)
        val anchor = maxOf(
            lastRead ?: firstSeenAtByGroup.getOrPut(key) { firstSeenFallback() },
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
            _unreadByGroupKey.update { current ->
                current + (key to ((current[key] ?: 0) + qualifying.size))
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
                if (shouldNotify(relayUrl, groupId, true)) onReplyNotify?.invoke(relayUrl, groupId, latestReply)
            latestMention != null ->
                if (shouldNotify(relayUrl, groupId, true)) onMentionNotify?.invoke(relayUrl, groupId, latestMention)
            else ->
                if (shouldNotify(relayUrl, groupId, false)) {
                    onUnreadIncrement?.invoke(relayUrl, groupId, qualifying.maxBy { it.createdAt }, qualifying.size)
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
     * and AppModule gates sound, popup and the unread flag on the event being realtime.
     */
    fun onThreadEventReceived(
        groupId: String,
        event: NostrGroupClient.NostrMessage,
    ) {
        val pubkey = currentPubkey
        val relayUrl = event.relayUrl?.normalizeRelayUrl()
        val verdict = when {
            pubkey == null -> "no-session"
            relayUrl == null -> "no-origin-relay"
            event.kind != 1111 -> "not-a-reply(${event.kind})"
            event.pubkey == pubkey -> "own-reply"
            isMutedAuthor(event.pubkey) -> "muted-author"
            !isJoined(relayUrl, groupId) -> "not-joined"
            !threadReplyTargetsMe(event.tags, pubkey, findMessageAuthor) -> "not-for-me"
            !shouldNotify(relayUrl, groupId, true) -> "group-muted"
            else -> null
        }
        if (verdict != null || relayUrl == null) return
        onThreadReplyNotify?.invoke(relayUrl, groupId, event)
    }

    fun onReactionReceived(
        relayUrl: String,
        groupId: String,
        reaction: NostrGroupClient.NostrReaction,
    ) {
        val pubkey = currentPubkey ?: return
        if (reaction.pubkey == pubkey) return
        if (isMutedAuthor(reaction.pubkey)) return
        if (!isJoined(relayUrl, groupId) || isRestricted(relayUrl, groupId)) return
        val key = groupKey(relayUrl, groupId)
        val lastRead = SecureStorage.lastReadFor(pubkey, relayUrl, groupId)
        val previousHighWater = _latestMessageTimestamps.value[key] ?: 0L
        val anchor = maxOf(
            lastRead ?: firstSeenAtByGroup.getOrPut(key) { firstSeenFallback() },
            previousHighWater,
        )
        if (reaction.createdAt <= anchor) return

        // Reactions on the user's own message are direct interactions worth
        // surfacing on the group/relay badges, not just the notification feed.
        // Caller already verified the target message author == self.
        val highWaterAdvanced = reaction.createdAt > previousHighWater
        if (highWaterAdvanced) {
            _latestMessageTimestamps.update { it + (key to reaction.createdAt) }
        }

        // Mirror onMessagesFlushed's active/focus handling so reactions don't behave
        // differently from messages for the group the user is looking at.
        val isActive = key == activeGroupKey
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
            _unreadByGroupKey.update { current ->
                current + (key to ((current[key] ?: 0) + 1))
            }
        }
        persistEntries()

        // A reaction is always to the user's own message, so it counts as a
        // direct interaction for the notification gate.
        if (shouldNotify(relayUrl, groupId, true)) onReactionNotify?.invoke(relayUrl, groupId, reaction)
    }

    fun clear() {
        currentPubkey = null
        activeGroupKey = null
        _unreadByGroupKey.value = emptyMap()
        _latestMessageTimestamps.value = emptyMap()
        firstSeenAtByGroup.clear()
    }

    private fun persistEntries() {
        val pubkey = currentPubkey ?: return
        val counts = _unreadByGroupKey.value
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
