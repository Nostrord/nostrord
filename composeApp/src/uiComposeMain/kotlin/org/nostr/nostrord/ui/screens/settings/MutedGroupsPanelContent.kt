package org.nostr.nostrord.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.settings.NotificationLevel
import org.nostr.nostrord.ui.components.avatars.OptimizedSmallAvatar
import org.nostr.nostrord.ui.components.buttons.AppButton
import org.nostr.nostrord.ui.components.buttons.AppButtonVariant
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.navigation.LocalFrameNavigator
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Settings "Muted groups" panel: every group with a notification level of its own,
 * so an override can be found and undone without opening the group. Web parity:
 * MutedGroupsPanel in web/screens/SettingsScreen.kt.
 */
@Composable
fun MutedGroupsPanelContent(vm: MutedGroupsViewModel) {
    val rows by vm.rows.collectAsState()
    val defaultLevel by vm.defaultLevel.collectAsState()
    val frameNavigator = LocalFrameNavigator.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Muted groups never notify and drop their unread count to a dot. " +
                "These overrides are stored per account and sync to your other devices.",
            color = NostrordColors.TextSecondary,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(Spacing.lg))

        if (rows.isEmpty()) {
            Text(
                text = "No group overrides yet. Right-click a group in the sidebar, or open its info panel, to mute it.",
                color = NostrordColors.TextMuted,
                fontSize = 13.sp,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                rows.forEach { row ->
                    val openable = frameNavigator != null && row.relayUrl.isNotBlank()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .then(
                                if (openable) {
                                    Modifier
                                        .clickable { frameNavigator?.invoke(GroupRoute(row.relayUrl, row.groupId)) }
                                        .pointerHoverIcon(PointerIcon.Hand)
                                } else {
                                    Modifier
                                },
                            )
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        OptimizedSmallAvatar(
                            imageUrl = row.picture,
                            identifier = row.groupId,
                            displayName = row.name,
                            size = 36.dp,
                            shape = NostrordShapes.shapeSmall,
                            isGroup = true,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = row.name,
                                color = NostrordColors.TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = levelLabel(row.level),
                                color = NostrordColors.TextMuted,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        AppButton(
                            text = if (row.level == NotificationLevel.MUTED) "Unmute" else "Reset",
                            onClick = { vm.clearOverride(row.groupId) },
                            variant = AppButtonVariant.Secondary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = "Clearing an override returns the group to the global default (${levelLabel(defaultLevel).lowercase()}).",
                color = NostrordColors.TextMuted,
                fontSize = 12.sp,
            )
        }
    }
}

internal fun levelLabel(level: NotificationLevel): String = when (level) {
    NotificationLevel.ALL -> "All messages"
    NotificationLevel.MENTIONS_REPLIES -> "Mentions & replies"
    NotificationLevel.MUTED -> "Muted"
}
