package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.screens.group.components.GroupHeaderIcon
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.resolveGroupRef

/**
 * DM group-invite card (prototype InviteCard): "GROUP INVITE" eyebrow + group avatar/name,
 * two-line about, and a full-width primary "View group" button. Rendered by DmPageScreen
 * when a message carries a kind:39000 naddr on its own line; the button (and the card)
 * navigate to the group. Metadata resolves from the group caches with a preview fetch
 * when unknown (same contract as GroupLinkCard).
 */
@Composable
fun GroupInviteCard(
    groupId: String,
    relayUrl: String?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val repo = AppModule.nostrRepository
    val groups by repo.groups.collectAsState()
    val groupsByRelay by repo.groupsByRelay.collectAsState()

    // The invite's own relay decides: NIP-29 ids are relay-local, so a same-id group from
    // another relay must not paint this card.
    val groupMeta = resolveGroupRef(groupsByRelay, groupId, relayUrl, fallback = groups)

    LaunchedEffect(groupId, relayUrl) {
        if (relayUrl != null && groupMeta?.name == null) {
            repo.fetchGroupPreview(groupId, relayUrl)
        }
    }

    val displayName = groupMeta?.name?.takeIf { it.isNotBlank() } ?: groupId

    Column(
        modifier =
        modifier
            .widthIn(max = 280.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(NostrordColors.Surface)
            .clickable { onOpen() }
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(Spacing.md),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GroupHeaderIcon(
                pictureUrl = groupMeta?.picture,
                groupId = groupId,
                displayName = displayName,
                size = 40.dp,
                cornerRadius = 8.dp,
            )
            Column {
                Text(
                    text = "GROUP INVITE",
                    color = NostrordColors.TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                )
                Text(
                    text = displayName,
                    color = NostrordColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        groupMeta?.about?.takeIf { it.isNotBlank() }?.let { about ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = about,
                color = NostrordColors.TextMuted,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onOpen,
            colors = ButtonDefaults.buttonColors(containerColor = NostrordColors.Primary),
            shape = RoundedCornerShape(8.dp),
            modifier =
            Modifier
                .fillMaxWidth()
                .pointerHoverIcon(PointerIcon.Hand),
        ) {
            Text("View group", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.height(16.dp),
            )
        }
    }
}
