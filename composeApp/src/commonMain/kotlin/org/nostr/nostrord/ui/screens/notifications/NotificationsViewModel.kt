package org.nostr.nostrord.ui.screens.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.NostrRepositoryApi
import org.nostr.nostrord.notifications.NotificationEntry
import org.nostr.nostrord.notifications.NotificationType
import org.nostr.nostrord.storage.SecureStorage

/** Notifications type filter (prototype tabs). Reactions have no tab; they show under [ALL]. */
enum class NotifFilter { ALL, MENTIONS, REPLIES, MESSAGES }

/** Per-tab counts for the sidebar badges (computed over the current unread-only base). */
data class NotifTypeCounts(
    val all: Int = 0,
    val mentions: Int = 0,
    val replies: Int = 0,
    val messages: Int = 0,
)

/** A group that appears in the notifications, for the sidebar group filter. */
data class NotifGroupBucket(
    val groupId: String,
    val name: String,
    val unread: Int,
)

/**
 * Screen logic for the notifications page, shared by the Compose UI and the React web UI.
 * Exposes the notification history, the metadata flows needed to render actors and group
 * context, the type/group/unread-only filters (mirroring the prototype's NotificationsSidebar),
 * and the read/clear actions. Compose obtains it via `viewModel { }`, web via `useViewModel { }`.
 */
class NotificationsViewModel(
    private val repo: NostrRepositoryApi,
) : ViewModel() {
    private val store = AppModule.notificationHistoryStore

    val entries = store.entries
    val userMetadata = repo.userMetadata

    // Cross-relay lookups: notifications can come from background relays, so the screen
    // resolves group / relay names from these maps rather than the active-relay `groups`.
    val groupsByRelay = repo.groupsByRelay
    val relayMetadata = repo.relayMetadata

    // ── Filters (prototype: type tabs + unread-only toggle + group filter) ──────
    private val _typeFilter = MutableStateFlow(NotifFilter.ALL)
    val typeFilter: StateFlow<NotifFilter> = _typeFilter.asStateFlow()

    private val _unreadOnly = MutableStateFlow(SecureStorage.getBooleanPref(KEY_UNREAD_ONLY, default = false))
    val unreadOnly: StateFlow<Boolean> = _unreadOnly.asStateFlow()

    private val _groupFilter = MutableStateFlow<String?>(null)
    val groupFilter: StateFlow<String?> = _groupFilter.asStateFlow()

    fun setTypeFilter(filter: NotifFilter) {
        _typeFilter.value = filter
    }

    fun setUnreadOnly(value: Boolean) {
        _unreadOnly.value = value
        SecureStorage.saveBooleanPref(KEY_UNREAD_ONLY, value)
    }

    fun setGroupFilter(groupId: String?) {
        _groupFilter.value = groupId
    }

    private fun matchesType(
        entry: NotificationEntry,
        filter: NotifFilter,
    ): Boolean = when (filter) {
        NotifFilter.ALL -> true
        NotifFilter.MENTIONS -> entry.type == NotificationType.MENTION
        NotifFilter.REPLIES -> entry.type == NotificationType.REPLY
        NotifFilter.MESSAGES -> entry.type == NotificationType.MESSAGE
    }

    /** The notifications to render, after the type, group and unread-only filters. */
    val filtered: StateFlow<List<NotificationEntry>> =
        combine(entries, _typeFilter, _unreadOnly, _groupFilter) { list, type, unread, group ->
            list.filter { e ->
                matchesType(e, type) &&
                    (group == null || e.groupId == group) &&
                    (!unread || !e.read)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Total unread, for the header pill. */
    val unreadCount: StateFlow<Int> =
        entries.map { list -> list.count { !it.read } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Per-tab counts over the unread-only base (prototype typeCount). */
    val typeCounts: StateFlow<NotifTypeCounts> =
        combine(entries, _unreadOnly) { list, unread ->
            val base = if (unread) list.filter { !it.read } else list
            NotifTypeCounts(
                all = base.size,
                mentions = base.count { it.type == NotificationType.MENTION },
                replies = base.count { it.type == NotificationType.REPLY },
                messages = base.count { it.type == NotificationType.MESSAGE },
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, NotifTypeCounts())

    /** Groups present in the notifications (unread-only base), with their unread counts. */
    val groupBuckets: StateFlow<List<NotifGroupBucket>> =
        combine(entries, _unreadOnly) { list, unread ->
            val base = if (unread) list.filter { !it.read } else list
            val order = LinkedHashMap<String, NotifGroupBucket>()
            base.forEach { e ->
                val prev = order[e.groupId]
                val unreadInc = if (e.read) 0 else 1
                order[e.groupId] =
                    NotifGroupBucket(
                        groupId = e.groupId,
                        name = prev?.name?.takeIf { it.isNotBlank() }
                            ?: e.groupName?.takeIf { it.isNotBlank() }
                            ?: e.groupId,
                        unread = (prev?.unread ?: 0) + unreadInc,
                    )
            }
            order.values.toList()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun markRead(id: String) {
        store.markRead(id)
    }

    fun markAllRead() {
        store.markAllRead()
    }

    fun clearHistory() {
        store.clearHistory()
    }

    /** Fetch display metadata for the given actor pubkeys (notification authors). */
    fun requestUserMetadata(pubkeys: Set<String>) {
        if (pubkeys.isEmpty()) return
        viewModelScope.launch { repo.requestUserMetadata(pubkeys) }
    }

    private companion object {
        const val KEY_UNREAD_ONLY = "notifications_unread_only"
    }
}

/**
 * Muted action text for a feed row. Reactions carry their emoji; anything about a forum thread
 * says so, since the row otherwise reads identically to the chat event it is not. Shared so the
 * Compose and web feeds cannot word the same entry differently.
 */
fun notificationActionLabel(entry: NotificationEntry): String {
    val inThread = if (entry.threadRootId != null) " in a thread" else ""
    return when (entry.type) {
        NotificationType.MENTION -> "mentioned you$inThread"
        NotificationType.REPLY -> "replied$inThread"
        NotificationType.MESSAGE -> "posted$inThread"
        NotificationType.REACTION -> "reacted ${entry.emoji ?: ""}".trim() + inThread
        NotificationType.GROUP_ADD -> "added you"
    }
}
