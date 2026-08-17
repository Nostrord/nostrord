package org.nostr.nostrord.ui.components.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.nostr.nostrord.ui.theme.NostrordAnimation
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Context menu for a DM bubble: the group chat's [MessageContextMenu] shell (same popup,
 * positioning and card styling) trimmed to the actions that exist for NIP-17 messages.
 * Rumors are unsigned and never addressable on public relays, so there is no
 * nevent/link/pin/reaction here — just the local copy actions.
 */
@Composable
fun DmMessageContextMenu(
    visible: Boolean,
    anchorOffsetPx: Offset?,
    onDismiss: () -> Unit,
    onViewSource: () -> Unit,
    onCopyText: () -> Unit,
    onReact: (String) -> Unit,
    onOpenReactionPicker: () -> Unit,
    onReply: () -> Unit,
) {
    if (!visible) return
    val marginPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    val positionProvider =
        remember(anchorOffsetPx, marginPx) {
            MessageMenuPositionProvider(anchorOffsetPx, anchorWidthPx = 0, marginPx = marginPx)
        }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties =
        PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        DisableSelection {
            AnimatedVisibility(
                visible = true,
                enter =
                fadeIn(animationSpec = tween(NostrordAnimation.popupEnter)) +
                    scaleIn(
                        animationSpec = tween(NostrordAnimation.popupEnter),
                        initialScale = 0.9f,
                        transformOrigin = TransformOrigin(0f, 0f),
                    ),
                exit =
                fadeOut(animationSpec = tween(NostrordAnimation.popupExit)) +
                    scaleOut(
                        animationSpec = tween(NostrordAnimation.popupExit),
                        transformOrigin = TransformOrigin(0f, 0f),
                    ),
            ) {
                Column(
                    modifier =
                    Modifier
                        .width(214.dp)
                        .shadow(
                            elevation = 12.dp,
                            shape = NostrordShapes.shapeMedium,
                            ambientColor = Color.Black.copy(alpha = 0.4f),
                            spotColor = Color.Black.copy(alpha = 0.4f),
                        ).clip(NostrordShapes.shapeMedium)
                        .background(NostrordColors.Surface)
                        .border(
                            width = Spacing.dividerThickness,
                            color = NostrordColors.Divider,
                            shape = NostrordShapes.shapeMedium,
                        ).padding(6.dp)
                        .pointerInput(Unit) {
                            detectTapGestures { /* consume */ }
                        },
                ) {
                    QuickReactionsRow(
                        onQuickReact = {
                            onReact(it)
                            onDismiss()
                        },
                        onOpenPicker = {
                            onOpenReactionPicker()
                            onDismiss()
                        },
                    )
                    ContextMenuItem(
                        icon = Icons.AutoMirrored.Outlined.Reply,
                        label = "Reply",
                        onClick = {
                            onReply()
                            onDismiss()
                        },
                    )
                    ContextMenuItem(
                        icon = Icons.Outlined.Visibility,
                        label = "View source",
                        onClick = {
                            onViewSource()
                            onDismiss()
                        },
                    )
                    ContextMenuItem(
                        icon = Icons.Outlined.ContentCopy,
                        label = "Copy text",
                        onClick = {
                            onCopyText()
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}
