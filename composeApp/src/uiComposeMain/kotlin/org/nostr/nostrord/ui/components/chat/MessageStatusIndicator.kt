package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Inline send-state icon for the author's own message: a muted clock while Sending, a check
 * once Delivered. Rendered on the same line as the message content (no extra row, so the
 * chat never shifts). Failed renders nothing here; [MessageStatusIndicator] handles it.
 * Shared by chat (MessageItem), threads and DMs.
 *
 * [allInboxes] is the DM-only second tick: the wrap reached every relay the recipient publishes.
 * A group message has one relay to reach, so its caller leaves this false and one check is the
 * whole story.
 */
@Composable
fun SendStateIcon(
    status: GroupManager.MessageStatus,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color = NostrordColors.TextMuted,
    allInboxes: Boolean = false,
) {
    val (image, description) = when (status) {
        is GroupManager.MessageStatus.Sending -> Icons.Default.Schedule to "Sending"
        is GroupManager.MessageStatus.Delivered ->
            if (allInboxes) {
                Icons.Default.DoneAll to "On all of their inbox relays"
            } else {
                Icons.Default.Check to "Delivered to a relay"
            }
        is GroupManager.MessageStatus.Failed -> return
    }
    Icon(
        imageVector = image,
        contentDescription = description,
        tint = tint,
        // The caller bottom-aligns this in the content row; the 2dp lift keeps the
        // glyph on the text baseline instead of down in the font's descent.
        modifier = modifier.padding(start = Spacing.xxs, bottom = 2.dp).size(13.dp),
    )
}

/**
 * Failure row shown under the author's own message: the reason plus Retry / Dismiss.
 * Sending/Delivered render nothing here; they show as an inline [SendStateIcon] instead.
 * Shared by chat (MessageItem) and threads so the indicator stays identical.
 */
@Composable
fun MessageStatusIndicator(
    status: GroupManager.MessageStatus,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (status !is GroupManager.MessageStatus.Failed) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = Spacing.xxs),
    ) {
        Text(text = "Not delivered", color = NostrordColors.Error, style = NostrordTypography.Timestamp)
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(
            text = "Retry",
            color = NostrordColors.Error,
            style = NostrordTypography.Timestamp,
            modifier = Modifier.clickable { onRetry() },
        )
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(
            text = "Dismiss",
            color = NostrordColors.TextMuted,
            style = NostrordTypography.Timestamp,
            modifier = Modifier.clickable { onDismiss() },
        )
    }
}
