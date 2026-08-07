package org.nostr.nostrord.ui.components.layout

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import org.nostr.nostrord.ui.components.chat.ContextMenuItem
import org.nostr.nostrord.ui.components.chat.ContextMenuPopup
import org.nostr.nostrord.ui.components.chat.ContextMenuSurface

/**
 * Right-click / long-press menu for a group row (rail chip, channel row, group card).
 * Shares the message menu's popup chrome so both open the same card at the cursor.
 *
 * Muting is the whole menu today; it lives in its own component so group-scoped
 * actions have a home rather than accreting onto the message menu.
 */
@Composable
fun GroupContextMenu(
    visible: Boolean,
    muted: Boolean,
    onDismiss: () -> Unit,
    onToggleMute: () -> Unit,
    anchorOffsetPx: Offset? = null,
) {
    if (!visible) return
    ContextMenuPopup(onDismiss = onDismiss, anchorOffsetPx = anchorOffsetPx, anchorWidthPx = 0) {
        ContextMenuSurface {
            ContextMenuItem(
                icon = if (muted) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff,
                label = if (muted) "Unmute group" else "Mute group",
                onClick = {
                    onToggleMute()
                    onDismiss()
                },
            )
        }
    }
}

/**
 * Opens [onOpen] at the cursor on a secondary (right) click, reporting the position
 * local to the modified element so [GroupContextMenu] can anchor there.
 *
 * Deliberately not the shared `rightClickContextMenuModifier`: its Android actual maps
 * to a plain tap, which on a group row is already "open the group". Touch platforms
 * reach muting through the row's long-press or the group info modal instead.
 */
fun Modifier.secondaryClickMenu(
    key: Any?,
    onOpen: (Offset) -> Unit,
): Modifier = pointerInput(key) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                event.changes.forEach { it.consume() }
                onOpen(event.changes.first().position)
            }
        }
    }
}
