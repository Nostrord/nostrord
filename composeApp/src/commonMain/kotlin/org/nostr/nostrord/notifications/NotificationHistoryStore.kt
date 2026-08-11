package org.nostr.nostrord.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.getAnnouncedNotificationIds
import org.nostr.nostrord.storage.getPersistedNotifications
import org.nostr.nostrord.storage.saveAnnouncedNotificationIds
import org.nostr.nostrord.storage.savePersistedNotifications

@Serializable
enum class NotificationType { REPLY, MENTION, REACTION, MESSAGE, GROUP_ADD }

@Serializable
data class NotificationEntry(
    val id: String,
    val type: NotificationType,
    val groupId: String,
    val relayUrl: String,
    val actorPubkey: String,
    val createdAt: Long,
    val preview: String,
    val messageId: String? = null,
    /**
     * Set when the entry is about a forum thread event: the kind:11 root to open. Chat entries
     * leave it null. Defaulted so entries persisted before threads notified still deserialize.
     */
    val threadRootId: String? = null,
    val emoji: String? = null,
    val read: Boolean = false,
    // Snapshot of the group/relay display names at the moment the notification
    // was generated. Stored so the feed remains readable even when the group is
    // later forgotten or the relay's metadata cache misses on cold startup.
    // Pictures/icons are intentionally NOT snapshotted — those should refresh.
    val groupName: String? = null,
    val relayName: String? = null,
)

class NotificationHistoryStore {
    private val _entries = MutableStateFlow<List<NotificationEntry>>(emptyList())
    val entries: StateFlow<List<NotificationEntry>> = _entries.asStateFlow()

    private var currentPubkey: String? = null

    /**
     * Ids already announced, oldest first. The feed keeps only [MAX_ENTRIES], while the thread and
     * chat subscriptions re-serve their whole window on every pane open, re-AUTH and restart: an id
     * that fell off the feed's tail would come back as unread every time. This outlives the feed and
     * the session, so an event notifies exactly once.
     */
    private val announced = LinkedHashSet<String>()

    fun initialize(pubkey: String?) {
        currentPubkey = pubkey
        announced.clear()
        if (pubkey == null) {
            _entries.value = emptyList()
            return
        }
        val persisted = SecureStorage.getPersistedNotifications(pubkey)
        _entries.value = persisted
        announced.addAll(SecureStorage.getAnnouncedNotificationIds(pubkey))
        // Seed from the feed so an install upgrading into this does not re-announce what it
        // is already showing.
        announced.addAll(persisted.map { it.id }.asReversed())
        trimAnnounced()
    }

    /**
     * Record a notification, unless its event already announced once. Silently dropping a repeat is
     * the point: the relay re-delivering old events must not resurface them as unread.
     */
    fun add(entry: NotificationEntry) {
        if (!announced.add(entry.id)) return
        trimAnnounced()
        _entries.update { current -> (listOf(entry) + current).take(MAX_ENTRIES) }
        persist()
    }

    private fun trimAnnounced() {
        while (announced.size > MAX_ANNOUNCED) {
            announced.remove(announced.first())
        }
    }

    fun markRead(id: String) {
        var changed = false
        _entries.update { current ->
            current.map {
                if (it.id == id && !it.read) {
                    changed = true
                    it.copy(read = true)
                } else {
                    it
                }
            }
        }
        if (changed) persist()
    }

    /** Mark every unread notification for [groupId] as read. No-op if none match. */
    /**
     * Mark a group's CHAT notifications read, as reading its chat does.
     *
     * Thread entries are left alone: their event is not in the chat, so opening the chat is no
     * evidence the user saw it. They clear via [markReadForThread] when that thread is opened.
     */
    fun markReadForGroup(groupId: String) {
        var changed = false
        _entries.update { current ->
            current.map {
                if (it.groupId == groupId && it.threadRootId == null && !it.read) {
                    changed = true
                    it.copy(read = true)
                } else {
                    it
                }
            }
        }
        if (changed) persist()
    }

    /** Mark every notification about thread [rootId] read: the user opened that thread. */
    fun markReadForThread(rootId: String) {
        var changed = false
        _entries.update { current ->
            current.map {
                if (it.threadRootId == rootId && !it.read) {
                    changed = true
                    it.copy(read = true)
                } else {
                    it
                }
            }
        }
        if (changed) persist()
    }

    fun markAllRead() {
        _entries.update { current -> current.map { it.copy(read = true) } }
        persist()
    }

    /**
     * Drop every entry from the in-memory feed and erase the persisted blob. The announced ids
     * stay: emptying the feed is not a request to be told about those events again.
     */
    fun clearHistory() {
        _entries.value = emptyList()
        persist()
    }

    fun clear() {
        currentPubkey = null
        _entries.value = emptyList()
        announced.clear()
    }

    private fun persist() {
        val pubkey = currentPubkey ?: return
        SecureStorage.savePersistedNotifications(pubkey, _entries.value)
        SecureStorage.saveAnnouncedNotificationIds(pubkey, announced)
    }

    /**
     * Count of unread notifications for any account, active or not. For the
     * active account this reads in-memory state so badges react instantly to
     * marks/clears. For inactive accounts it falls back to the persisted blob
     * — the app isn't subscribed for those, so the count only changes on
     * account switch / add / remove, which already triggers recomposition.
     */
    fun unreadCountFor(pubkey: String): Int {
        if (pubkey.isBlank()) return 0
        return if (pubkey == currentPubkey) {
            _entries.value.count { !it.read }
        } else {
            SecureStorage.getPersistedNotifications(pubkey).count { !it.read }
        }
    }

    companion object {
        private const val MAX_ENTRIES = 50

        // Well above the widest re-served window (the thread replies REQ asks for 500 per group),
        // so a repeat is still recognised after several groups' worth of history is replayed.
        internal const val MAX_ANNOUNCED = 2_000
    }
}
