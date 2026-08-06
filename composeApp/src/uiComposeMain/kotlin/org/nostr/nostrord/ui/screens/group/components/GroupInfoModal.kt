package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.settings.NotificationLevel
import org.nostr.nostrord.ui.components.ModalTitleBar
import org.nostr.nostrord.ui.components.RadioCircle
import org.nostr.nostrord.ui.components.RichAboutText
import org.nostr.nostrord.ui.navigation.LocalFrameNavigator
import org.nostr.nostrord.ui.navigation.RelayRoute
import org.nostr.nostrord.ui.screens.home.RelayHeaderIcon
import org.nostr.nostrord.ui.theme.AvatarGradients
import org.nostr.nostrord.ui.theme.Hsl
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing

private fun Hsl.toColor(): Color = Color.hsl(hue.toFloat(), saturation / 100f, lightness / 100f)

/**
 * Group info modal — prototype GroupInfoModal: title bar, gradient cover with the
 * centered group avatar, name, status badges, ABOUT, per-group NOTIFICATIONS level,
 * the RELAY link and, for members, Leave group with an inline confirm. (The group
 * address moved to ShareGroupModal.)
 */
@Composable
fun GroupInfoModal(
    groupId: String,
    groupName: String?,
    groupMetadata: GroupMetadata?,
    relayUrl: String = "",
    isMember: Boolean = false,
    memberCount: Int = 0,
    userMetadata: Map<String, UserMetadata> = emptyMap(),
    onUserClick: ((String) -> Unit)? = null,
    // Opens the relay page from the RELAY section. Passed explicitly because
    // LocalFrameNavigator does not propagate into the Dialog's composition.
    onOpenRelay: ((String) -> Unit)? = null,
    onLeave: () -> Unit = {},
    isListedPrivately: Boolean = false,
    onListedPrivatelyChange: (Boolean) -> Unit = {},
    privateListSectionOpaque: Boolean = false,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties =
        DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier =
                Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth(0.9f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { /* consume click */ },
                shape = RoundedCornerShape(16.dp),
                colors =
                CardDefaults.cardColors(
                    containerColor = NostrordColors.Surface,
                ),
            ) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    ModalTitleBar(title = "Group Info", onClose = onDismiss)

                    // Gradient cover band with the centered group avatar (prototype).
                    // Same seeded hue pair as the group's avatar identity.
                    val banner = remember(groupId) { AvatarGradients.banner(groupId) }
                    Box(
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(112.dp)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(banner.start.toColor(), banner.end.toColor()),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier =
                            Modifier
                                .border(3.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(16.dp)),
                        ) {
                            GroupHeaderIcon(
                                pictureUrl = groupMetadata?.picture,
                                groupId = groupId,
                                displayName = groupName ?: "Group",
                                size = 72.dp,
                                cornerRadius = 16.dp,
                            )
                        }
                    }

                    // Content section
                    Column(
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg)
                            .padding(top = Spacing.md, bottom = Spacing.lg),
                    ) {
                        Text(
                            text = groupMetadata?.name ?: groupName ?: groupId.ifBlank { "Unknown Group" },
                            color = NostrordColors.TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        )

                        Spacer(modifier = Modifier.height(Spacing.sm))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            // Restrictive states get warm tones (Private yellow, Closed
                            // orange) so they read as states, not neutral chips.
                            StatusBadge(
                                text = if (groupMetadata?.isPublic == true) "Public" else "Private",
                                color = if (groupMetadata?.isPublic == true) NostrordColors.Success else NostrordColors.Warning,
                                tinted = true,
                            )
                            StatusBadge(
                                text = if (groupMetadata?.isOpen == true) "Open" else "Closed",
                                color = if (groupMetadata?.isOpen == true) NostrordColors.Primary else NostrordColors.WarningOrange,
                                tinted = true,
                            )
                            if (groupMetadata?.isRestricted == true) {
                                StatusBadge(
                                    text = "Restricted",
                                    color = NostrordColors.Error,
                                    tinted = true,
                                )
                            }
                            if (groupMetadata?.isHidden == true) {
                                StatusBadge(
                                    text = "Hidden",
                                    color = NostrordColors.TextLink,
                                    tinted = true,
                                )
                            }
                            if (memberCount > 0) {
                                StatusBadge(
                                    text = "$memberCount members",
                                    color = NostrordColors.TextMuted,
                                    tinted = false,
                                )
                            }
                        }

                        if (!groupMetadata?.about.isNullOrBlank()) {
                            SectionHead("ABOUT")
                            RichAboutText(
                                text = groupMetadata?.about ?: "",
                                userMetadata = userMetadata,
                                style = NostrordTypography.MessageBody,
                                color = NostrordColors.TextContent,
                                onMentionClick = onUserClick,
                            )
                        }

                        // Notifications — per-group override of the global default.
                        val notificationSettings = AppModule.notificationSettings
                        val groupLevels by notificationSettings.groupLevels.collectAsState()
                        val defaultLevel by notificationSettings.defaultLevel.collectAsState()
                        val effectiveLevel = groupLevels[groupId] ?: defaultLevel

                        SectionHead("NOTIFICATIONS")
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            NotificationLevelOption(
                                label = "All messages",
                                description = "Notify for every message in this group.",
                                selected = effectiveLevel == NotificationLevel.ALL,
                                onClick = { notificationSettings.setGroupLevel(groupId, NotificationLevel.ALL) },
                            )
                            NotificationLevelOption(
                                label = "Mentions & replies",
                                description = "Only replies, @mentions, and reactions to your messages.",
                                selected = effectiveLevel == NotificationLevel.MENTIONS_REPLIES,
                                onClick = { notificationSettings.setGroupLevel(groupId, NotificationLevel.MENTIONS_REPLIES) },
                            )
                            NotificationLevelOption(
                                label = "Muted",
                                description = "Silence everything, including replies and mentions.",
                                selected = effectiveLevel == NotificationLevel.MUTED,
                                onClick = { notificationSettings.setGroupLevel(groupId, NotificationLevel.MUTED) },
                            )
                        }

                        val relayMetadata by AppModule.nostrRepository.relayMetadata.collectAsState()

                        // Relay this group lives on: tappable to open the relay page (parity with
                        // the web group banner's relay link).
                        if (relayUrl.isNotBlank()) {
                            val navigate = LocalFrameNavigator.current
                            val openRelay: (() -> Unit)? =
                                when {
                                    onOpenRelay != null -> {
                                        { onOpenRelay(relayUrl) }
                                    }
                                    navigate != null -> {
                                        { navigate(RelayRoute(relayUrl)) }
                                    }
                                    else -> null
                                }
                            val relayHost = relayUrl.removePrefix("wss://").removePrefix("ws://").trimEnd('/')
                            val relayInteraction = remember { MutableInteractionSource() }
                            val relayHovered by relayInteraction.collectIsHoveredAsState()
                            SectionHead("RELAY")
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (relayHovered) NostrordColors.HoverBackground else Color.Transparent)
                                    .hoverable(relayInteraction)
                                    .clickable(enabled = openRelay != null) {
                                        onDismiss()
                                        openRelay?.invoke()
                                    }
                                    .padding(8.dp),
                            ) {
                                RelayHeaderIcon(
                                    relayUrl = relayUrl,
                                    iconUrl = relayMetadata[relayUrl]?.icon,
                                    label = relayHost,
                                    size = 20.dp,
                                    // Rounded square (web .info-relay-icon radius-sm), not a circle.
                                    cornerRadius = 4.dp,
                                )
                                Spacer(modifier = Modifier.width(Spacing.sm))
                                Text(
                                    relayHost,
                                    color = NostrordColors.TextSecondary,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(modifier = Modifier.height(Spacing.md))
                        }

                        if (isMember) {
                            Spacer(modifier = Modifier.height(Spacing.lg))
                            HorizontalDivider(color = NostrordColors.Divider, thickness = 1.dp)
                            Spacer(modifier = Modifier.height(Spacing.lg))

                            SectionHead("YOUR LIST")
                            PrivateOnListRow(
                                checked = isListedPrivately,
                                onCheckedChange = onListedPrivatelyChange,
                            )
                            if (privateListSectionOpaque) {
                                Spacer(modifier = Modifier.height(Spacing.sm))
                                Text(
                                    text =
                                    "Part of your private list was written by another app and cannot be read here. " +
                                        "It is kept untouched.",
                                    style = NostrordTypography.Caption,
                                    color = NostrordColors.TextMuted,
                                )
                            }

                            Spacer(modifier = Modifier.height(Spacing.lg))
                            HorizontalDivider(color = NostrordColors.Divider, thickness = 1.dp)
                            Spacer(modifier = Modifier.height(Spacing.lg))

                            // Leave group: danger row swapped for an inline confirm box (prototype).
                            var confirmLeave by remember { mutableStateOf(false) }
                            if (confirmLeave) {
                                Column(
                                    modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, NostrordColors.Error.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                        .background(NostrordColors.Error.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                        .padding(Spacing.md),
                                ) {
                                    Text(
                                        text =
                                        "Leave ${groupMetadata?.name ?: groupName ?: "this group"}? " +
                                            if (groupMetadata?.isOpen == false) {
                                                "To come back you will need approval or an invite."
                                            } else {
                                                "You can rejoin whenever you want."
                                            },
                                        fontSize = 13.sp,
                                        color = NostrordColors.TextSecondary,
                                    )
                                    Spacer(modifier = Modifier.height(Spacing.sm))
                                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                        Button(
                                            onClick = onLeave,
                                            colors = ButtonDefaults.buttonColors(containerColor = NostrordColors.Error),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                            shape = RoundedCornerShape(6.dp),
                                        ) {
                                            Text("Confirm leave", style = NostrordTypography.Caption, color = Color.White)
                                        }
                                        TextButton(onClick = { confirmLeave = false }) {
                                            Text("Cancel", style = NostrordTypography.Caption, color = NostrordColors.TextSecondary)
                                        }
                                    }
                                }
                            } else {
                                Row(
                                    modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { confirmLeave = true }
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .padding(Spacing.sm),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ExitToApp,
                                        contentDescription = null,
                                        tint = NostrordColors.Error,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = "Leave group",
                                        fontSize = 14.sp,
                                        color = NostrordColors.Error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 11sp bold uppercase section header (prototype InfoHead). */
@Composable
private fun SectionHead(text: String) {
    Spacer(modifier = Modifier.height(Spacing.lg))
    Text(
        text = text,
        style = NostrordTypography.SectionHeader,
        color = NostrordColors.TextMuted,
    )
    Spacer(modifier = Modifier.height(Spacing.sm))
}

/**
 * Keep this group in the encrypted section of the kind:10009 list. Mirrors the web
 * `privateOnList` row in `web/modals/GroupInfoModal.kt`.
 */
@Composable
private fun PrivateOnListRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = if (checked) NostrordColors.Primary else NostrordColors.TextMuted,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Private on my list",
                fontSize = 14.sp,
                color = NostrordColors.TextPrimary,
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = "Encrypted, so nobody can see you are in this group. People who follow you stop discovering it.",
                style = NostrordTypography.Caption,
                color = NostrordColors.TextMuted,
            )
        }
        Spacer(modifier = Modifier.width(Spacing.md))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors =
            SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = NostrordColors.Primary,
                uncheckedThumbColor = NostrordColors.TextMuted,
                uncheckedTrackColor = NostrordColors.InputBackground,
            ),
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
        )
    }
}

/** Prototype InfoBadge: 11sp semibold pill, tone-tinted background when [tinted]. */
@Composable
private fun StatusBadge(
    text: String,
    color: Color,
    tinted: Boolean,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (tinted) color.copy(alpha = 0.15f) else NostrordColors.BackgroundFloating,
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * Selectable bordered card for a per-group [NotificationLevel] choice
 * (prototype InfoRadio).
 */
@Composable
private fun NotificationLevelOption(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val borderColor =
        when {
            selected -> NostrordColors.Primary
            isHovered -> NostrordColors.TextMuted
            else -> NostrordColors.Divider
        }
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .background(if (selected) NostrordColors.Primary.copy(alpha = 0.1f) else NostrordColors.Surface)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(Spacing.md),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        RadioCircle(
            selected = selected,
            modifier = Modifier.padding(top = 2.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 14.sp,
                color = NostrordColors.TextPrimary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = description,
                fontSize = 12.sp,
                color = NostrordColors.TextMuted,
            )
        }
    }
}
