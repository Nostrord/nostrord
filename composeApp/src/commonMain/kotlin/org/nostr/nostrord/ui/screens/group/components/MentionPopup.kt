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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.ui.components.avatars.ProfileAvatar
import org.nostr.nostrord.ui.screens.group.model.MemberInfo

@Composable
fun MentionPopup(
    members: List<MemberInfo>,
    query: String,
    onMemberSelect: (MemberInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    val filteredMembers = if (query.isEmpty()) {
        members.take(8)
    } else {
        members.filter { member ->
            member.displayName.contains(query, ignoreCase = true) ||
            member.pubkey.contains(query, ignoreCase = true)
        }.take(8)
    }

    if (filteredMembers.isEmpty()) {
        return
    }

    Surface(
        modifier = modifier
            .width(300.dp)
            .heightIn(max = 320.dp),
        shape = RoundedCornerShape(4.dp),
        color = Color(0xFF2B2D31), // dark background
        shadowElevation = 16.dp,
        tonalElevation = 0.dp
    ) {
        Column {
            // Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Members",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFB5BAC1), // muted text
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    letterSpacing = 0.02.sp
                )
            }

            HorizontalDivider(
                color = Color(0xFF1E1F22),
                thickness = 1.dp
            )

            // Members list
            LazyColumn(
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(filteredMembers) { index, member ->
                    MentionItem(
                        member = member,
                        onClick = { onMemberSelect(member) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MentionItem(
    member: MemberInfo,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val backgroundColor = when {
        isPressed -> Color(0xFF3F4147) // Slightly brighter for press feedback (mobile)
        isHovered -> Color(0xFF35373C) // hover color (desktop)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .background(backgroundColor)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        ProfileAvatar(
            imageUrl = member.picture,
            displayName = member.displayName,
            pubkey = member.pubkey,
            size = 32.dp
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Name and pubkey
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = member.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFDBDEE1), // primary text
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = member.pubkey.take(8) + "..." + member.pubkey.takeLast(4),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF949BA4), // secondary text
                fontSize = 11.sp,
                maxLines = 1
            )
        }
    }
}
