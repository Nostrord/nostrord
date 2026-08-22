package org.nostr.nostrord.ui.screens.group.model

import androidx.compose.runtime.Immutable
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.sortedForDisplay
import org.nostr.nostrord.ui.screens.group.clampSystemEventsToLoadedWindow
import org.nostr.nostrord.utils.getDateLabel

// Time window for grouping consecutive messages from the same author (5 minutes)
private const val MESSAGE_GROUP_WINDOW_SECONDS = 5 * 60

// Time window for grouping consecutive system events (2 minutes)
private const val SYSTEM_EVENT_GROUP_WINDOW_SECONDS = 2 * 60

/**
 * Kind of system event, used to pick a distinct icon and color per moderation action
 * instead of inferring it from the (translatable) action text.
 */
enum class SystemEventType {
    JOINED, // kind 9021 — user joined
    ROLE_CHANGED, // kind 9000 with role(s) — admin set/changed a member's role
    REMOVED, // kind 9001 — admin removed a member
    LEFT, // kind 9022 — user left
}

/**
 * Sealed class representing different types of chat items.
 * Marked as @Immutable for Compose stability to prevent unnecessary recompositions.
 */
@Immutable
sealed class ChatItem {
    @Immutable
    data class DateSeparator(val date: String) : ChatItem()

    /**
     * Divider shown to indicate where new (unread) messages begin.
     */
    @Immutable
    data object NewMessagesDivider : ChatItem()

    /**
     * System event (join/leave) with optional grouping.
     * When multiple users perform the same action close together, they are grouped.
     */
    @Immutable
    data class SystemEvent(
        val pubkey: String,
        val action: String,
        val createdAt: Long,
        val id: String,
        val type: SystemEventType,
        val additionalUsers: List<String> = emptyList(), // Additional pubkeys for grouped events
    ) : ChatItem() {
        val totalUsers: Int get() = 1 + additionalUsers.size
    }

    /**
     * Message item with grouping information.
     * @param message The actual message
     * @param isFirstInGroup True if this is the first message in a group (shows avatar/name)
     * @param isLastInGroup True if this is the last message in a group (adds bottom spacing)
     */
    @Immutable
    data class Message(
        val message: NostrGroupClient.NostrMessage,
        val isFirstInGroup: Boolean = true,
        val isLastInGroup: Boolean = true,
    ) : ChatItem()

    /**
     * Zap event (kind 9321) - Lightning payment request.
     * Shows sender avatar, amount, recipient info, and optional emoji/message content.
     */
    @Immutable
    data class ZapEvent(
        val id: String,
        val senderPubkey: String, // Who sent the zap
        val recipientPubkey: String, // Who received the zap
        val amount: Long, // Amount in sats
        val content: String, // Emoji or message
        val createdAt: Long,
    ) : ChatItem()
}

/**
 * Build chat items from messages.
 * @param messages The list of messages to process
 * @param lastReadTimestamp Optional timestamp of last read message (for new messages divider)
 */
fun buildChatItems(
    messages: List<NostrGroupClient.NostrMessage>,
    lastReadTimestamp: Long? = null,
    currentUserPubkey: String? = null,
): List<ChatItem> {
    val items = mutableListOf<ChatItem>()
    var lastDate: String? = null
    var lastMessagePubkey: String? = null
    var lastMessageTime: Long? = null
    var newMessagesDividerInserted = false

    val sortedAll = messages.sortedForDisplay()
    // Bound the rendered timeline to the loaded message window (shared with the web list):
    // hide moderation events older than the oldest loaded kind:9 message so a streamed older
    // join/leave never inserts mid-list and shifts the reading position (opens above old events).
    val sortedMessages = clampSystemEventsToLoadedWindow(sortedAll)

    // Map of pubkey → list of timestamps for leave requests (kind 9022). Built from the
    // unclamped list so a 9022 hidden above the frontier still suppresses its 9001 echo.
    val leaveRequestTimestamps: Map<String, List<Long>> = sortedAll
        .filter { it.kind == 9022 }
        .groupBy({ it.pubkey }, { it.createdAt })

    // Track system events for grouping
    var pendingSystemEvent: ChatItem.SystemEvent? = null
    val pendingSystemEventUsers = mutableListOf<String>()

    fun flushPendingSystemEvent() {
        pendingSystemEvent?.let { event ->
            items.add(event.copy(additionalUsers = pendingSystemEventUsers.toList()))
        }
        pendingSystemEvent = null
        pendingSystemEventUsers.clear()
    }

    sortedMessages.forEachIndexed { index, message ->
        val messageDate = getDateLabel(message.createdAt)

        // Check if we need a date separator
        if (messageDate != lastDate) {
            flushPendingSystemEvent()
            items.add(ChatItem.DateSeparator(messageDate))
            lastDate = messageDate
            // Reset grouping after date separator
            lastMessagePubkey = null
            lastMessageTime = null
        }

        // Insert new messages divider before first unread chat message from another user.
        // kind == 9 only (parity with web): a streamed join/leave/zap from another user must
        // not anchor the divider, or "New messages" would point at a non-chat line.
        if (!newMessagesDividerInserted &&
            lastReadTimestamp != null &&
            message.kind == 9 &&
            message.createdAt > lastReadTimestamp &&
            message.pubkey != currentUserPubkey
        ) {
            flushPendingSystemEvent()
            items.add(ChatItem.NewMessagesDivider)
            newMessagesDividerInserted = true
            // Reset grouping after the divider: a continuation row under it would lose its
            // author header and read as coming from whoever posted above the divider.
            lastMessagePubkey = null
            lastMessageTime = null
        }

        when (message.kind) {
            9 -> {
                // Flush any pending system events before adding a message
                flushPendingSystemEvent()

                // Determine if this message should be grouped with the previous one
                val timeSinceLastMessage = lastMessageTime?.let { message.createdAt - it } ?: Long.MAX_VALUE
                val isSameAuthor = message.pubkey == lastMessagePubkey
                val isWithinGroupWindow = timeSinceLastMessage <= MESSAGE_GROUP_WINDOW_SECONDS
                val isFirstInGroup = !isSameAuthor || !isWithinGroupWindow

                // Look ahead to determine if this is the last in group
                val nextMessage = sortedMessages.getOrNull(index + 1)
                val nextMessageDate = nextMessage?.let { getDateLabel(it.createdAt) }
                val isNextDifferentDate = nextMessageDate != null && nextMessageDate != messageDate
                val isNextDifferentAuthor = nextMessage?.pubkey != message.pubkey
                val isNextOutsideWindow = nextMessage?.let {
                    it.createdAt - message.createdAt > MESSAGE_GROUP_WINDOW_SECONDS
                } ?: true
                val isNextSystemEvent = nextMessage?.kind in listOf(9000, 9001, 9021, 9022, 9321)
                val isLastInGroup = nextMessage == null ||
                    isNextDifferentDate ||
                    isNextDifferentAuthor ||
                    isNextOutsideWindow ||
                    isNextSystemEvent

                items.add(
                    ChatItem.Message(
                        message = message,
                        isFirstInGroup = isFirstInGroup,
                        isLastInGroup = isLastInGroup,
                    ),
                )

                lastMessagePubkey = message.pubkey
                lastMessageTime = message.createdAt
            }
            9321 -> {
                // Zap request event (nutzap)
                flushPendingSystemEvent()

                // Parse amount from tags - "amount" tag contains sats as string
                val amountSats = message.tags
                    .find { it.size >= 2 && it[0] == "amount" }
                    ?.getOrNull(1)
                    ?.toLongOrNull() ?: 0L

                // Parse recipient from "p" tag
                val recipientPubkey = message.tags
                    .find { it.size >= 2 && it[0] == "p" }
                    ?.getOrNull(1)
                    ?: message.pubkey // Fallback to sender if no recipient

                items.add(
                    ChatItem.ZapEvent(
                        id = message.id,
                        senderPubkey = message.pubkey,
                        recipientPubkey = recipientPubkey,
                        amount = amountSats,
                        content = message.content,
                        createdAt = message.createdAt,
                    ),
                )

                // Reset message grouping after zap event
                lastMessagePubkey = null
                lastMessageTime = null
            }
            9000 -> {
                // kind 9000 (put-user) is reused both to add a member and to set/remove
                // roles. Only a role *assignment* carries role names in the "p" tag
                // (["p", <pubkey>, "<role>", ...]) and can be classified from the event
                // alone, so we show "is now <role>" for those. A no-role 9000 is
                // ambiguous (plain add vs. role removal) and the relay does not serve it
                // back reliably, so it is suppressed to avoid lines that flicker or
                // vanish on reload (issue #66).
                val pTag = message.tags.firstOrNull { it.firstOrNull() == "p" }
                val targetPubkey = pTag?.getOrNull(1)
                val roles = pTag?.drop(2)?.filter { it.isNotBlank() } ?: emptyList()

                if (targetPubkey != null && roles.isNotEmpty()) {
                    val action = "is now ${roles.joinToString(", ")}"
                    val pending = pendingSystemEvent

                    // Moderation rows are never merged across users: each promote names its
                    // target. Only an exact duplicate (same user, same action in-window, e.g.
                    // an offline-queued event flushed beside a fresh one) collapses.
                    val isDuplicate = pending != null &&
                        pending.action == action &&
                        pending.pubkey == targetPubkey &&
                        message.createdAt - pending.createdAt <= SYSTEM_EVENT_GROUP_WINDOW_SECONDS
                    if (!isDuplicate) {
                        flushPendingSystemEvent()
                        pendingSystemEvent = ChatItem.SystemEvent(
                            pubkey = targetPubkey,
                            action = action,
                            createdAt = message.createdAt,
                            id = message.id,
                            type = SystemEventType.ROLE_CHANGED,
                        )
                    }

                    lastMessagePubkey = null
                    lastMessageTime = null
                }
            }
            9001 -> {
                // Show "was removed" only when an admin manually removed someone.
                // Suppress if the target sent a kind 9022 leave request within 5 min
                // (that means the relay auto-confirmed the leave, already shown as "left").
                val targetPubkey = message.tags.firstOrNull { it.firstOrNull() == "p" }?.getOrNull(1)
                val hasRecentLeaveRequest = targetPubkey != null &&
                    leaveRequestTimestamps[targetPubkey]?.any {
                        kotlin.math.abs(message.createdAt - it) <= 5 * 60
                    } == true
                if (targetPubkey != null && !hasRecentLeaveRequest) {
                    val action = "was removed from the group"
                    val pending = pendingSystemEvent

                    // Never merged across users (each removal names its target); only an
                    // exact same-user duplicate in-window collapses.
                    val isDuplicate = pending != null &&
                        pending.action == action &&
                        pending.pubkey == targetPubkey &&
                        message.createdAt - pending.createdAt <= SYSTEM_EVENT_GROUP_WINDOW_SECONDS
                    if (!isDuplicate) {
                        flushPendingSystemEvent()
                        pendingSystemEvent = ChatItem.SystemEvent(
                            pubkey = targetPubkey,
                            action = action,
                            createdAt = message.createdAt,
                            id = message.id,
                            type = SystemEventType.REMOVED,
                        )
                    }

                    lastMessagePubkey = null
                    lastMessageTime = null
                }
            }
            9021 -> {
                val action = "joined the group"
                val pending = pendingSystemEvent

                // Check if we can group with pending system event
                if (pending != null &&
                    pending.action == action &&
                    message.createdAt - pending.createdAt <= SYSTEM_EVENT_GROUP_WINDOW_SECONDS
                ) {
                    // Add to existing group (same user twice is one fact, not "2 members")
                    if (message.pubkey != pending.pubkey && message.pubkey !in pendingSystemEventUsers) {
                        pendingSystemEventUsers.add(message.pubkey)
                    }
                } else {
                    // Flush previous and start new group
                    flushPendingSystemEvent()
                    pendingSystemEvent = ChatItem.SystemEvent(
                        pubkey = message.pubkey,
                        action = action,
                        createdAt = message.createdAt,
                        id = message.id,
                        type = SystemEventType.JOINED,
                    )
                }

                // Reset message grouping after system event
                lastMessagePubkey = null
                lastMessageTime = null
            }
            9022 -> {
                val action = "left the group"
                val pending = pendingSystemEvent

                // Check if we can group with pending system event
                if (pending != null &&
                    pending.action == action &&
                    message.createdAt - pending.createdAt <= SYSTEM_EVENT_GROUP_WINDOW_SECONDS
                ) {
                    // Add to existing group (same user twice is one fact, not "2 members")
                    if (message.pubkey != pending.pubkey && message.pubkey !in pendingSystemEventUsers) {
                        pendingSystemEventUsers.add(message.pubkey)
                    }
                } else {
                    // Flush previous and start new group
                    flushPendingSystemEvent()
                    pendingSystemEvent = ChatItem.SystemEvent(
                        pubkey = message.pubkey,
                        action = action,
                        createdAt = message.createdAt,
                        id = message.id,
                        type = SystemEventType.LEFT,
                    )
                }

                // Reset message grouping after system event
                lastMessagePubkey = null
                lastMessageTime = null
            }
        }
    }

    // Flush any remaining pending system event
    flushPendingSystemEvent()

    return items
}
