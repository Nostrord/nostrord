package org.nostr.nostrord.web.screens

import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.sortedForDisplay
import org.nostr.nostrord.ui.screens.group.clampSystemEventsToLoadedWindow
import org.nostr.nostrord.utils.getDateLabel
import kotlin.math.abs

// Mirrors the native buildChatItems grouping windows.
private const val MESSAGE_GROUP_WINDOW = 5 * 60
private const val SYSTEM_EVENT_GROUP_WINDOW = 2 * 60

/** System (moderation) event kind, used to pick a distinct icon and colour. Mirrors native. */
enum class SystemEventType { JOINED, ROLE_CHANGED, REMOVED, LEFT }

/** One rendered row in the chat: a date separator, a moderation/system event, or a message. */
sealed class WebChatItem {
    data class DateSeparator(val date: String) : WebChatItem()

    /** Divider shown to indicate where new (unread) messages begin. Mirrors native. */
    data object NewMessagesDivider : WebChatItem()

    data class SystemEvent(
        val pubkey: String,
        val action: String,
        val createdAt: Long,
        val id: String,
        val type: SystemEventType,
        val additionalUsers: List<String> = emptyList(),
    ) : WebChatItem() {
        val totalUsers: Int get() = 1 + additionalUsers.size
    }

    data class Message(
        val message: NostrGroupClient.NostrMessage,
        val firstInGroup: Boolean,
    ) : WebChatItem()
}

/**
 * Build the ordered chat-item list from raw messages — a faithful port of the native
 * `buildChatItems`: date separators per calendar day, kind-9 messages grouped by author within
 * 5 min, and NIP-29 moderation events (9021 join, 9022 leave, 9000 role-change, 9001 removed)
 * turned into system rows. Consecutive same-action events within 2 min are merged. A no-role
 * 9000 (ambiguous add vs. role removal) and a 9001 that just confirms a recent 9022 leave are
 * suppressed, exactly like native.
 */
fun buildWebChatItems(
    messages: List<NostrGroupClient.NostrMessage>,
    lastReadTimestamp: Long? = null,
    currentUserPubkey: String? = null,
): List<WebChatItem> {
    val items = mutableListOf<WebChatItem>()
    val sortedAll = messages.sortedForDisplay()

    // Bound the rendered timeline to the loaded message window (shared with the native list).
    val sorted = clampSystemEventsToLoadedWindow(sortedAll)

    val leaveRequestTimestamps: Map<String, List<Long>> =
        sortedAll.filter { it.kind == 9022 }.groupBy({ it.pubkey }, { it.createdAt })

    var lastDate: String? = null
    var lastMessagePubkey: String? = null
    var lastMessageTime: Long? = null
    var pending: WebChatItem.SystemEvent? = null
    val pendingUsers = mutableListOf<String>()
    // Insert the "New messages" divider exactly once, before the first message
    // from someone else that's newer than the last-read snapshot. Skipping our
    // own messages avoids the obvious bug where the divider sits above what we
    // just wrote (issue #83).
    var dividerInserted = false

    fun flush() {
        pending?.let { items.add(it.copy(additionalUsers = pendingUsers.toList())) }
        pending = null
        pendingUsers.clear()
    }

    // Append a system event. Joins/leaves with the same action in-window merge into one
    // "N members" row; moderation rows (role change / removal) never merge across users,
    // each names its target. An exact same-user duplicate in-window (e.g. an
    // offline-queued event flushed beside a fresh one) collapses for every type.
    fun addSystemEvent(pubkey: String, action: String, createdAt: Long, id: String, type: SystemEventType) {
        val p = pending
        val sameActionInWindow = p != null && p.action == action && createdAt - p.createdAt <= SYSTEM_EVENT_GROUP_WINDOW
        val isDuplicate = sameActionInWindow && (pubkey == p.pubkey || pubkey in pendingUsers)
        val mergeable = type == SystemEventType.JOINED || type == SystemEventType.LEFT
        when {
            isDuplicate -> {}
            sameActionInWindow && mergeable -> pendingUsers.add(pubkey)
            else -> {
                flush()
                pending = WebChatItem.SystemEvent(pubkey, action, createdAt, id, type)
            }
        }
        lastMessagePubkey = null
        lastMessageTime = null
    }

    for (message in sorted) {
        val date = getDateLabel(message.createdAt)
        if (date != lastDate) {
            flush()
            items.add(WebChatItem.DateSeparator(date))
            lastDate = date
            lastMessagePubkey = null
            lastMessageTime = null
        }

        // Insert the divider before the first unread chat message from another
        // user. Restricted to kind:9 because the divider is a "new chat
        // messages" marker — placing it above a 9021 join or 9022 leave would
        // both look wrong and (worse) make the entry-alignment effect anchor
        // the viewport to a moderation row in the middle of the feed when the
        // socket streamed joins / leaves before any new chat.
        if (!dividerInserted &&
            message.kind == 9 &&
            lastReadTimestamp != null &&
            message.createdAt > lastReadTimestamp &&
            message.pubkey != currentUserPubkey
        ) {
            flush()
            items.add(WebChatItem.NewMessagesDivider)
            dividerInserted = true
            // Reset grouping after the divider: a continuation row under it would lose its
            // author header and read as coming from whoever posted above the divider.
            lastMessagePubkey = null
            lastMessageTime = null
        }

        when (message.kind) {
            9 -> {
                flush()
                val gap = lastMessageTime?.let { message.createdAt - it } ?: Long.MAX_VALUE
                val firstInGroup = message.pubkey != lastMessagePubkey || gap > MESSAGE_GROUP_WINDOW
                items.add(WebChatItem.Message(message, firstInGroup))
                lastMessagePubkey = message.pubkey
                lastMessageTime = message.createdAt
            }

            9000 -> {
                val pTag = message.tags.firstOrNull { it.firstOrNull() == "p" }
                val target = pTag?.getOrNull(1)
                val roles = pTag?.drop(2)?.filter { it.isNotBlank() } ?: emptyList()
                if (target != null && roles.isNotEmpty()) {
                    addSystemEvent(
                        target,
                        "is now ${roles.joinToString(", ")}",
                        message.createdAt,
                        message.id,
                        SystemEventType.ROLE_CHANGED,
                    )
                }
            }

            9001 -> {
                val target = message.tags.firstOrNull { it.firstOrNull() == "p" }?.getOrNull(1)
                val recentLeave =
                    target != null &&
                        leaveRequestTimestamps[target]?.any { abs(message.createdAt - it) <= 5 * 60 } == true
                if (target != null && !recentLeave) {
                    addSystemEvent(target, "was removed from the group", message.createdAt, message.id, SystemEventType.REMOVED)
                }
            }

            9021 -> addSystemEvent(message.pubkey, "joined the group", message.createdAt, message.id, SystemEventType.JOINED)

            9022 -> addSystemEvent(message.pubkey, "left the group", message.createdAt, message.id, SystemEventType.LEFT)
        }
    }
    flush()
    return collapseSystemEventFlaps(items)
}

/**
 * Collapse a run of consecutive join/leave events from the SAME single user into one
 * summary row ("joined and left", "joined and left 6 times"). A user that flaps (joins
 * and leaves repeatedly) otherwise produces a wall of alternating rows, since the
 * per-action merge above only groups the SAME action across DIFFERENT users.
 *
 * Runs are broken by date separators, the unread divider, chat messages, role/removal
 * events and already-merged multi-user events, so the collapse is per-day and in order,
 * and the upstream member/approval logic is untouched. A lone join (no matching leave)
 * is left as-is so "joined the group" still shows for users who are still present.
 */
private fun collapseSystemEventFlaps(items: List<WebChatItem>): List<WebChatItem> {
    fun isFlap(e: WebChatItem.SystemEvent): Boolean = e.totalUsers == 1 && (e.type == SystemEventType.JOINED || e.type == SystemEventType.LEFT)

    val result = mutableListOf<WebChatItem>()
    var run = mutableListOf<WebChatItem.SystemEvent>()

    fun flush() {
        when {
            run.isEmpty() -> {}
            run.size == 1 -> result.add(run.first())
            else -> {
                val first = run.first()
                val last = run.last()
                val cycles = minOf(
                    run.count { it.type == SystemEventType.JOINED },
                    run.count { it.type == SystemEventType.LEFT },
                )
                val action = if (cycles <= 1) "joined and left" else "joined and left $cycles times"
                // Keep first.id for a stable key; last.createdAt/type so the row sits at
                // the end of the run and its icon reflects whether the user is currently in.
                result.add(first.copy(action = action, createdAt = last.createdAt, type = last.type))
            }
        }
        run = mutableListOf()
    }

    for (item in items) {
        if (item is WebChatItem.SystemEvent && isFlap(item)) {
            val prev = run.lastOrNull()
            if (prev != null && prev.pubkey != item.pubkey) flush()
            run.add(item)
        } else {
            flush()
            result.add(item)
        }
    }
    flush()
    return result
}
