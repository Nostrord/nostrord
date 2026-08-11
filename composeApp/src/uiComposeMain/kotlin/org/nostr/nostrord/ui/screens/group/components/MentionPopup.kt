package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.components.avatars.ProfileAvatar
import org.nostr.nostrord.ui.mentions.MentionAutocomplete
import org.nostr.nostrord.ui.screens.group.model.MemberInfo
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.shortNpub

/**
 * Members offered for `@query`. Also used by the composers to know the count for keyboard
 * navigation, so the highlighted row and the rendered list can never disagree.
 */
fun getFilteredMembers(
    members: List<MemberInfo>,
    query: String,
): List<MemberInfo> = MentionAutocomplete.filter(members, query) { listOf(it.displayName, it.pubkey) }

@Composable
fun MentionPopup(
    members: List<MemberInfo>,
    query: String,
    selectedIndex: Int = 0,
    onMemberSelect: (MemberInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filteredMembers = getFilteredMembers(members, query)

    if (filteredMembers.isEmpty()) {
        return
    }

    // Clamp selected index to valid range
    val safeSelectedIndex = selectedIndex.coerceIn(0, filteredMembers.size - 1)

    Surface(
        modifier =
        modifier
            .width(300.dp)
            .heightIn(max = 320.dp),
        shape = NostrordShapes.menuShape,
        color = NostrordColors.Surface,
        shadowElevation = 16.dp,
        tonalElevation = 0.dp,
    ) {
        Column {
            // Header - uppercase section
            Box(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.inputPadding, vertical = Spacing.sm),
            ) {
                Text(
                    text = "MEMBERS",
                    style = NostrordTypography.SectionHeader,
                    color = NostrordColors.TextMuted,
                )
            }

            HorizontalDivider(
                color = NostrordColors.BackgroundDark,
                thickness = Spacing.dividerThickness,
            )

            // Members list
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
            ) {
                itemsIndexed(filteredMembers) { index, member ->
                    MentionItem(
                        member = member,
                        isSelected = index == safeSelectedIndex,
                        onClick = { onMemberSelect(member) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MentionItem(
    member: MemberInfo,
    isSelected: Boolean = false,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    // Keyboard selection (isSelected) takes precedence, hover provides secondary highlight
    val backgroundColor =
        when {
            isPressed -> NostrordColors.SurfaceVariant
            isSelected -> NostrordColors.Primary.copy(alpha = 0.2f) // Distinct selection color
            isHovered -> NostrordColors.HoverBackground
            else -> Color.Transparent
        }

    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .hoverable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ).pointerHoverIcon(PointerIcon.Hand)
            .background(backgroundColor)
            .padding(horizontal = Spacing.inputPadding, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar
        ProfileAvatar(
            imageUrl = member.picture,
            displayName = member.displayName,
            pubkey = member.pubkey,
            size = Spacing.avatarSizeSmall,
        )

        Spacer(modifier = Modifier.width(Spacing.inputPadding))

        // Name and pubkey
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = member.displayName,
                style = NostrordTypography.MemberName,
                color = NostrordColors.TextContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = shortNpub(member.pubkey),
                style = NostrordTypography.Tiny,
                color = NostrordColors.TextMuted,
                maxLines = 1,
            )
        }
    }
}
