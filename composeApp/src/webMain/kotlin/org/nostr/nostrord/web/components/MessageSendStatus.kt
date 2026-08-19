package org.nostr.nostrord.web.components

import org.nostr.nostrord.network.managers.GroupManager
import react.ChildrenBuilder
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

/**
 * Inline send-state icon for the author's own message: a muted clock while Sending, a check
 * once Delivered. Flows at the end of the message content (no extra row, so the chat never
 * shifts). Failed renders nothing here; [messageSendStatus] handles it.
 * Shared by chat (MessageRow), threads and DMs.
 *
 * [allInboxes] is the DM-only second tick: the wrap reached every relay the recipient publishes.
 * A group message has one relay to reach, so its caller leaves this false and one check is the
 * whole story.
 */
fun ChildrenBuilder.sendStateIcon(
    status: GroupManager.MessageStatus?,
    allInboxes: Boolean = false,
) {
    when (status) {
        is GroupManager.MessageStatus.Sending ->
            span {
                className = ClassName("msg-state-icon")
                title = "Sending"
                icon(Ic.Schedule)
            }
        is GroupManager.MessageStatus.Delivered ->
            span {
                className = ClassName("msg-state-icon")
                title = if (allInboxes) "On all of their inbox relays" else "Delivered to a relay"
                icon(if (allInboxes) Ic.DoneAll else Ic.Check)
            }
        else -> {}
    }
}

/**
 * Failure row shown under the author's own message: "Not delivered" with Retry / Dismiss.
 * Sending/Delivered render nothing here; they show as an inline [sendStateIcon] instead.
 * Shared by chat (MessageRow) and threads so the indicator stays identical.
 */
fun ChildrenBuilder.messageSendStatus(
    status: GroupManager.MessageStatus?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (status !is GroupManager.MessageStatus.Failed) return
    div {
        className = ClassName("msg-status failed")
        span { +"Not delivered" }
        button {
            className = ClassName("msg-status-action")
            onClick = { onRetry() }
            +"Retry"
        }
        button {
            className = ClassName("msg-status-action dismiss")
            onClick = { onDismiss() }
            +"Dismiss"
        }
    }
}
