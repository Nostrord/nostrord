package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.components.avatars.OptimizedUserAvatar
import org.nostr.nostrord.ui.screens.avspace.AvSpaceViewModel
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.shortNpub

/** Faces stacked in the bar before it stops. */
private const val AVATAR_STACK = 4

/**
 * Live NIP-29 AV space banner for [groupId], with a roster sheet behind it.
 *
 * The relay publishes the room's occupants as kind 39004, so the roster is readable on every
 * platform. Capture and playback need a LiveKit SDK, which only the web build has, so the
 * sheet here explains that instead of offering a Join button. Renders nothing when the group
 * has no room or the room is empty.
 */
@Composable
fun LiveSpaceSection(groupId: String, modifier: Modifier = Modifier) {
    val repo = AppModule.nostrRepository
    val vm = viewModel(key = "avspace-$groupId") {
        AvSpaceViewModel(repo, groupId, repo.activePubkey.value)
    }
    val hasSpace by vm.hasSpace.collectAsState()
    val participants by vm.participants.collectAsState()
    val userMetadata by repo.userMetadata.collectAsState()
    var sheetOpen by remember(groupId) { mutableStateOf(false) }

    if (!hasSpace || participants.isEmpty()) return

    fun nameOf(pubkey: String): String {
        val meta = userMetadata[pubkey]
        return meta?.displayName?.takeIf { it.isNotBlank() }
            ?: meta?.name?.takeIf { it.isNotBlank() }
            ?: shortNpub(pubkey)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(NostrordColors.PrimarySubtle)
            .clickable { sheetOpen = true }
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(NostrordColors.Error.copy(alpha = 0.15f))
                .padding(horizontal = Spacing.xs, vertical = 2.dp),
        ) {
            Text("LIVE", color = NostrordColors.Error, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = null,
            tint = NostrordColors.Primary,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Voice room",
                color = NostrordColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (participants.size == 1) "1 person" else "${participants.size} people",
                color = NostrordColors.TextMuted,
                fontSize = 12.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
            participants.take(AVATAR_STACK).forEach { participant ->
                OptimizedUserAvatar(
                    imageUrl = userMetadata[participant.pubkey]?.picture,
                    pubkey = participant.pubkey,
                    displayName = nameOf(participant.pubkey),
                    size = 26.dp,
                )
            }
        }
    }

    if (sheetOpen) {
        AlertDialog(
            onDismissRequest = { sheetOpen = false },
            containerColor = NostrordColors.Surface,
            title = {
                Text(
                    text = "Voice room · ${participants.size} in the room",
                    color = NostrordColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    participants.forEach { participant ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        ) {
                            OptimizedUserAvatar(
                                imageUrl = userMetadata[participant.pubkey]?.picture,
                                pubkey = participant.pubkey,
                                displayName = nameOf(participant.pubkey),
                                size = 32.dp,
                            )
                            Text(
                                text = if (participant.isSelf) "You" else nameOf(participant.pubkey),
                                color = NostrordColors.TextSecondary,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Text(
                        text = "Live audio and video are only available on the web for now.",
                        color = NostrordColors.TextMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = Spacing.sm),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { sheetOpen = false }) {
                    Text("Close", color = NostrordColors.Primary)
                }
            },
        )
    }
}
