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
import org.nostr.nostrord.network.livekit.AV_UNSUPPORTED_MESSAGE
import org.nostr.nostrord.network.livekit.AvConnectionState
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
 * platform. Joining needs a media engine, which desktop has and Android and iOS do not yet, so
 * the sheet offers controls or an explanation accordingly. Renders nothing unless the group
 * carries the `livekit` tag; an empty room still shows, since that is when someone would want
 * to be the first one in.
 */
@Composable
fun LiveSpaceSection(groupId: String, modifier: Modifier = Modifier) {
    val repo = AppModule.nostrRepository
    val vm = viewModel(key = "avspace-$groupId") {
        AvSpaceViewModel(repo, groupId, repo.activePubkey.value)
    }
    val hasSpace by vm.hasSpace.collectAsState()
    val participants by vm.participants.collectAsState()
    val connection by vm.connectionState.collectAsState()
    val micOn by vm.micEnabled.collectAsState()
    val error by vm.error.collectAsState()
    val userMetadata by repo.userMetadata.collectAsState()
    var sheetOpen by remember(groupId) { mutableStateOf(false) }

    if (!hasSpace) return
    val live = participants.isNotEmpty()

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
        if (live) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(NostrordColors.Error.copy(alpha = 0.15f))
                    .padding(horizontal = Spacing.xs, vertical = 2.dp),
            ) {
                Text("LIVE", color = NostrordColors.Error, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
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
                text = when (participants.size) {
                    0 -> "Nobody here yet"
                    1 -> "1 person"
                    else -> "${participants.size} people"
                },
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
                    text = if (live) "Voice room · ${participants.size} in the room" else "Voice room",
                    color = NostrordColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (!live) {
                        Text(
                            text = "Nobody is in the room yet.",
                            color = NostrordColors.TextSecondary,
                            fontSize = 14.sp,
                        )
                    }
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
                    error?.let { message ->
                        Text(
                            text = message,
                            color = NostrordColors.Error,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = Spacing.sm),
                        )
                    }
                    if (!vm.canJoin) {
                        Text(
                            text = AV_UNSUPPORTED_MESSAGE + ".",
                            color = NostrordColors.TextMuted,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = Spacing.sm),
                        )
                    }
                }
            },
            confirmButton = {
                if (!vm.canJoin) {
                    TextButton(onClick = { sheetOpen = false }) {
                        Text("Close", color = NostrordColors.Primary)
                    }
                    return@AlertDialog
                }
                if (connection == AvConnectionState.Connected) {
                    TextButton(onClick = { vm.toggleMic() }) {
                        Text(if (micOn) "Mute" else "Unmute", color = NostrordColors.Primary)
                    }
                    TextButton(onClick = { vm.leave() }) {
                        Text("Leave", color = NostrordColors.Error)
                    }
                } else {
                    TextButton(
                        onClick = { vm.join() },
                        enabled = connection == AvConnectionState.Disconnected,
                    ) {
                        Text(
                            if (connection == AvConnectionState.Connecting) "Joining..." else "Join room",
                            color = NostrordColors.Primary,
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { sheetOpen = false }) {
                    Text("Close", color = NostrordColors.TextSecondary)
                }
            },
        )
    }
}
