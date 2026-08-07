package org.nostr.nostrord.ui.components.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import org.nostr.nostrord.ui.components.emoji.QuickReactions
import org.nostr.nostrord.ui.theme.NostrordAnimation
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.ui.theme.rememberEmojiFontFamily
import org.nostr.nostrord.utils.supportsNativeShare

/**
 * Context menu actions for messages.
 *
 * Required items per design spec:
 * - Add Reaction
 * - Reply
 * - Copy Text
 * - Copy Message Link
 * - Pin Message (if admin)
 * - Delete Message (if author or admin)
 */
sealed class MessageContextAction {
    data object AddReaction : MessageContextAction()

    /** One-tap reaction from the quick-reactions row on top of the menu. */
    data class QuickReact(val emoji: String) : MessageContextAction()

    data object Reply : MessageContextAction()

    /** Threads: announce the thread in the group chat (kind:9 with the root's nevent). */
    data object ShareToChat : MessageContextAction()

    data object ZapMessage : MessageContextAction()

    data object CopyText : MessageContextAction()

    data object CopyMessageLink : MessageContextAction()

    data object ShareMessageLink : MessageContextAction()

    data object CopyNevent : MessageContextAction()

    data object CopyEventJson : MessageContextAction()

    data object PinMessage : MessageContextAction()

    /** NIP-56 report on this message's author, pinned to the message. */
    data object ReportMessage : MessageContextAction()

    data object DeleteMessage : MessageContextAction()
}

/**
 * Message context menu - appears on right-click (desktop) or long-press (mobile).
 *
 * IMPORTANT: This is implemented as a true floating Popup overlay, completely
 * detached from the layout tree. This ensures:
 * - No layout shifts when showing/hiding
 * - No interference with text selection
 * - No recomposition of parent message content
 * - Proper z-ordering above all content
 *
 * Animation timing:
 * - Appear: 150ms with decelerate easing
 * - Disappear: 100ms with accelerate easing
 */
@Composable
fun MessageContextMenu(
    visible: Boolean,
    onDismiss: () -> Unit,
    onAction: (MessageContextAction) -> Unit,
    anchorOffsetPx: Offset? = null,
    anchorWidthPx: Int = 0,
    isAuthor: Boolean = false,
    isAdmin: Boolean = false,
    canZap: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Only render Popup when visible to avoid layout participation
    if (visible) {
        ContextMenuPopup(onDismiss, anchorOffsetPx, anchorWidthPx) {
            ContextMenuContent(
                onAction = onAction,
                onDismiss = onDismiss,
                isAuthor = isAuthor,
                isAdmin = isAdmin,
                canZap = canZap,
            )
        }
    }
}

/**
 * Compact context menu for thread messages (right-click / long-press): the quick-reactions
 * row, copy text, and delete for the author. Shares the chat menu's chrome and action
 * vocabulary ([MessageContextAction]) but not its chat-only items (reply, zap, pin, links).
 */
@Composable
fun ThreadMessageContextMenu(
    visible: Boolean,
    onDismiss: () -> Unit,
    onAction: (MessageContextAction) -> Unit,
    anchorOffsetPx: Offset? = null,
    // Author (kind:5) or group admin (kind:9005); see canDeleteThreadMessage.
    canDelete: Boolean = false,
    // Roots only: announce the thread in the group chat.
    canShareToChat: Boolean = false,
) {
    if (!visible) return
    ContextMenuPopup(onDismiss, anchorOffsetPx, anchorWidthPx = 0) {
        ContextMenuSurface {
            QuickReactionsRow(
                onQuickReact = { emoji ->
                    onAction(MessageContextAction.QuickReact(emoji))
                    onDismiss()
                },
                onOpenPicker = {
                    onAction(MessageContextAction.AddReaction)
                    onDismiss()
                },
            )

            ContextMenuDivider()

            ContextMenuItem(
                icon = Icons.AutoMirrored.Outlined.Reply,
                label = "Reply",
                onClick = {
                    onAction(MessageContextAction.Reply)
                    onDismiss()
                },
            )

            if (canShareToChat) {
                ContextMenuItem(
                    icon = Icons.Outlined.Forum,
                    label = "Share to chat",
                    onClick = {
                        onAction(MessageContextAction.ShareToChat)
                        onDismiss()
                    },
                )
            }

            ContextMenuDivider()

            ContextMenuItem(
                icon = Icons.Outlined.ContentCopy,
                label = "Copy text",
                onClick = {
                    onAction(MessageContextAction.CopyText)
                    onDismiss()
                },
            )

            ContextMenuItem(
                icon = Icons.Outlined.Link,
                label = "Copy event link",
                onClick = {
                    onAction(MessageContextAction.CopyMessageLink)
                    onDismiss()
                },
            )

            ContextMenuItem(
                icon = Icons.Outlined.Code,
                label = "Copy nevent",
                onClick = {
                    onAction(MessageContextAction.CopyNevent)
                    onDismiss()
                },
            )

            ContextMenuItem(
                icon = Icons.Outlined.Code,
                label = "Copy event JSON",
                onClick = {
                    onAction(MessageContextAction.CopyEventJson)
                    onDismiss()
                },
            )

            if (canDelete) {
                ContextMenuDivider()

                ContextMenuItem(
                    icon = Icons.Outlined.Delete,
                    label = "Delete message",
                    onClick = {
                        onAction(MessageContextAction.DeleteMessage)
                        onDismiss()
                    },
                    isDestructive = true,
                )
            }
        }
    }
}

/** Popup chrome shared by the chat and thread menus: cursor-anchored position + scale/fade in. */
@Composable
internal fun ContextMenuPopup(
    onDismiss: () -> Unit,
    anchorOffsetPx: Offset?,
    anchorWidthPx: Int,
    content: @Composable () -> Unit,
) {
    val marginPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    // Open at the click position (web parity); fall back to the row's
    // top-right when there's no cursor anchor (the hover More button).
    val positionProvider =
        remember(anchorOffsetPx, anchorWidthPx, marginPx) {
            MessageMenuPositionProvider(anchorOffsetPx, anchorWidthPx, marginPx)
        }
    // Grow from the cursor (top-left) when opened at a click, from the
    // top-right when hung off the More button.
    val transformOrigin =
        if (anchorOffsetPx != null) TransformOrigin(0f, 0f) else TransformOrigin(1f, 0f)
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties =
        PopupProperties(
            // focusable = true allows clicking outside to dismiss
            // but must be careful not to steal focus from selection
            focusable = true,
            // Don't dismiss on back press automatically - let onDismissRequest handle it
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        // DisableSelection prevents the menu itself from participating in text selection
        DisableSelection {
            // Animated content inside the popup
            AnimatedVisibility(
                visible = true, // Always visible when popup is shown
                enter =
                fadeIn(animationSpec = tween(NostrordAnimation.popupEnter)) +
                    scaleIn(
                        animationSpec = tween(NostrordAnimation.popupEnter),
                        initialScale = 0.9f,
                        transformOrigin = transformOrigin,
                    ),
                exit =
                fadeOut(animationSpec = tween(NostrordAnimation.popupExit)) +
                    scaleOut(
                        animationSpec = tween(NostrordAnimation.popupExit),
                        transformOrigin = transformOrigin,
                    ),
            ) {
                content()
            }
        }
    }
}

/**
 * Positions the context-menu popup at the click point, flipping it back into the
 * viewport when it would overflow. Mirrors the web menu's positioning effect:
 * a cursor anchor opens rightward/downward from the click; a null anchor (the
 * hover More button) hangs the menu off the row's right edge (opens leftward).
 */
internal class MessageMenuPositionProvider(
    private val anchorOffsetPx: Offset?,
    private val anchorWidthPx: Int,
    private val marginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val w = popupContentSize.width
        val h = popupContentSize.height

        var x =
            if (anchorOffsetPx != null) {
                anchorBounds.left + anchorOffsetPx.x.toInt()
            } else {
                anchorBounds.left + anchorWidthPx - w
            }
        if (x + w > windowSize.width - marginPx) x = (windowSize.width - marginPx - w).coerceAtLeast(marginPx)
        if (x < marginPx) x = marginPx

        var y = anchorBounds.top + (anchorOffsetPx?.y?.toInt() ?: 0)
        if (y + h > windowSize.height - marginPx) y = (y - h).coerceAtLeast(marginPx)
        if (y < marginPx) y = marginPx

        return IntOffset(x, y)
    }
}

/**
 * The actual menu content, separated for clarity.
 */
/** The menu card chrome (width, shadow, border), shared by the chat and thread menu contents. */
@Composable
internal fun ContextMenuSurface(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier =
        Modifier
            // 210dp + 6dp padding + 1dp border, mirroring the web `.ctx-menu`.
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
            // Consume pointer events to prevent them from reaching underlying content
            .pointerInput(Unit) {
                detectTapGestures { /* consume */ }
            },
        content = content,
    )
}

@Composable
private fun ContextMenuContent(
    onAction: (MessageContextAction) -> Unit,
    onDismiss: () -> Unit,
    isAuthor: Boolean,
    isAdmin: Boolean,
    canZap: Boolean,
) {
    ContextMenuSurface {
        // Quick reactions row (one tap to react) + an affordance to open the full picker.
        QuickReactionsRow(
            onQuickReact = { emoji ->
                onAction(MessageContextAction.QuickReact(emoji))
                onDismiss()
            },
            onOpenPicker = {
                onAction(MessageContextAction.AddReaction)
                onDismiss()
            },
        )

        ContextMenuDivider()

        ContextMenuItem(
            icon = Icons.AutoMirrored.Outlined.Reply,
            label = "Reply",
            onClick = {
                onAction(MessageContextAction.Reply)
                onDismiss()
            },
        )

        // Threads are not implemented yet (prototype "Iniciar thread aqui");
        // shown disabled so the menu shape matches the prototype.
        ContextMenuItem(
            icon = Icons.Outlined.Forum,
            label = "Start thread here",
            enabled = false,
            onClick = {},
        )

        // Zap (only when the author has a Lightning address and zaps are enabled)
        if (canZap) {
            ContextMenuItem(
                icon = Icons.Outlined.Bolt,
                label = "Zap",
                onClick = {
                    onAction(MessageContextAction.ZapMessage)
                    onDismiss()
                },
                isZap = true,
            )
        }

        // Saved-for-later is not implemented yet.
        ContextMenuItem(
            icon = Icons.Outlined.Bookmark,
            label = "Save for later",
            enabled = false,
            onClick = {},
        )
        if (!isAuthor) {
            ContextMenuItem(
                icon = Icons.Outlined.Shield,
                label = "Report",
                onClick = {
                    onAction(MessageContextAction.ReportMessage)
                    onDismiss()
                },
            )
        }

        ContextMenuDivider()

        ContextMenuItem(
            icon = Icons.Outlined.ContentCopy,
            label = "Copy text",
            onClick = {
                onAction(MessageContextAction.CopyText)
                onDismiss()
            },
        )

        if (!supportsNativeShare) {
            ContextMenuItem(
                icon = Icons.Outlined.Link,
                label = "Copy link",
                onClick = {
                    onAction(MessageContextAction.CopyMessageLink)
                    onDismiss()
                },
            )
        }

        if (supportsNativeShare) {
            ContextMenuItem(
                icon = Icons.Outlined.Share,
                label = "Share link",
                onClick = {
                    onAction(MessageContextAction.ShareMessageLink)
                    onDismiss()
                },
            )
        }

        // Copy nevent (prototype: shareable NIP-19 event reference)
        ContextMenuItem(
            icon = Icons.Outlined.Code,
            label = "Copy nevent",
            onClick = {
                onAction(MessageContextAction.CopyNevent)
                onDismiss()
            },
        )

        ContextMenuItem(
            icon = Icons.Outlined.Code,
            label = "Copy event JSON",
            onClick = {
                onAction(MessageContextAction.CopyEventJson)
                onDismiss()
            },
        )

        // Pin message (admin only): disabled until the pinning backend exists
        // (the action was never wired to anything).
        if (isAdmin) {
            ContextMenuDivider()

            ContextMenuItem(
                icon = Icons.Outlined.PushPin,
                label = "Pin message",
                enabled = false,
                onClick = {},
            )
        }

        // Delete message (author or admin)
        if (isAuthor || isAdmin) {
            ContextMenuDivider()

            ContextMenuItem(
                icon = Icons.Outlined.Delete,
                label = "Delete message",
                onClick = {
                    onAction(MessageContextAction.DeleteMessage)
                    onDismiss()
                },
                isDestructive = true,
            )
        }
    }
}

/**
 * Horizontal row of one-tap reactions on top of the menu, ending in an
 * affordance that opens the full emoji picker. Mirrors the web menu's
 * `.ctx-reactions` row.
 */
@Composable
private fun QuickReactionsRow(
    onQuickReact: (String) -> Unit,
    onOpenPicker: () -> Unit,
) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xxs),
        // Spread the buttons across the full width and let them shrink to fit,
        // matching the web `.ctx-reactions` flex row.
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // NotoColorEmoji so the glyphs render in color on Skia (desktop/iOS);
        // the system SansSerif lacks color emoji. Same as ReactionBadges.
        val emojiFont = rememberEmojiFontFamily()
        QuickReactions.forEach { emoji ->
            QuickReactionButton(onClick = { onQuickReact(emoji) }) {
                Text(text = emoji, fontFamily = emojiFont, fontSize = 18.sp)
            }
        }
        QuickReactionButton(onClick = onOpenPicker) {
            Icon(
                imageVector = Icons.Outlined.EmojiEmotions,
                contentDescription = "Add Reaction",
                tint = NostrordColors.TextSecondary,
                modifier = Modifier.size(Spacing.iconMd - Spacing.xs),
            )
        }
    }
}

/**
 * A single 30dp quick-reaction button. Mirrors the web `.ctx-reaction`: it
 * tints its background and scales to 1.15x on hover.
 */
@Composable
private fun QuickReactionButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val scale by animateFloatAsState(targetValue = if (isHovered) 1.15f else 1f)

    Box(
        modifier =
        Modifier
            .size(26.dp)
            .scale(scale)
            .clip(RoundedCornerShape(6.dp))
            .background(if (isHovered) NostrordColors.HoverBackground else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * Individual context menu item.
 */
@Composable
internal fun ContextMenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    isDestructive: Boolean = false,
    isZap: Boolean = false,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val textColor =
        when {
            !enabled -> NostrordColors.TextMuted
            isDestructive -> NostrordColors.Error
            // Zap highlights amber on hover, matching the hover toolbar's zap button.
            isZap && isHovered -> NostrordColors.Warning
            else -> NostrordColors.TextContent
        }

    val iconColor =
        when {
            !enabled -> NostrordColors.TextMuted
            isDestructive -> NostrordColors.Error
            isZap && isHovered -> NostrordColors.Warning
            else -> NostrordColors.TextSecondary
        }

    val backgroundColor = if (isHovered && enabled) NostrordColors.HoverBackground else Color.Transparent

    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            // Rounded, inset hover highlight (web `.ctx-item` has border-radius 4px
            // inside the menu's 6px padding).
            .clip(NostrordShapes.menuShape)
            .height(Spacing.channelItemHeight + Spacing.xs) // 36dp
            .background(backgroundColor)
            .hoverable(interactionSource)
            .clickable(enabled = enabled, onClick = onClick)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .padding(horizontal = Spacing.md - Spacing.xxs), // 10dp
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(Spacing.iconMd - Spacing.xs), // 20dp
        )

        Spacer(modifier = Modifier.width(Spacing.md - Spacing.xxs)) // 10dp gap

        Text(
            text = label,
            style = NostrordTypography.MenuItem,
            color = textColor,
        )
    }
}

/**
 * Divider between menu sections.
 */
@Composable
internal fun ContextMenuDivider() {
    HorizontalDivider(
        // Web `.ctx-divider` has margin 6px 4px.
        modifier = Modifier.padding(horizontal = Spacing.xs, vertical = 6.dp),
        thickness = Spacing.dividerThickness,
        color = NostrordColors.Divider,
    )
}
