package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.livekit.AV_UNSUPPORTED_MESSAGE
import org.nostr.nostrord.network.livekit.AvConnectionState
import org.nostr.nostrord.network.livekit.VideoFrameSink
import org.nostr.nostrord.ui.components.avatars.OptimizedUserAvatar
import org.nostr.nostrord.ui.screens.avspace.AvSpaceParticipant
import org.nostr.nostrord.ui.screens.avspace.AvSpaceViewModel
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.ui.util.rgbaToImageBitmap
import org.nostr.nostrord.utils.shortNpub

/** Faces stacked in the bar before it stops. */
private const val AVATAR_STACK = 4

private fun nameOf(pubkey: String, userMetadata: Map<String, UserMetadata>): String {
    val meta = userMetadata[pubkey]
    return meta?.displayName?.takeIf { it.isNotBlank() }
        ?: meta?.name?.takeIf { it.isNotBlank() }
        ?: shortNpub(pubkey)
}

/**
 * Live NIP-29 AV space banner for [groupId], opening the room dialog.
 *
 * Shown for any group carrying the `livekit` tag, empty room included: the relay creates the
 * room lazily on the first token request, so the empty state is exactly when someone needs the
 * entry point. Mirrors the web LiveSpaceBar (LIVE badge only while occupied, Start/Join pill).
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
    var roomOpen by remember(groupId) { mutableStateOf(false) }

    if (!hasSpace) return
    val live = participants.isNotEmpty()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (live) NostrordColors.PrimarySubtle else NostrordColors.SurfaceVariant)
            .clickable { roomOpen = true }
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (live) LiveBadge()
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
                    displayName = nameOf(participant.pubkey, userMetadata),
                    size = 26.dp,
                )
            }
        }
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(NostrordColors.Primary)
                .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        ) {
            Text(
                text = if (live) "Join" else "Start",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

    if (roomOpen) {
        AvSpaceRoomDialog(
            vm = vm,
            userMetadata = userMetadata,
            onClose = { roomOpen = false },
        )
    }
}

/**
 * Standalone room entry for callers outside the chat pane (the sidebar's voice row). Shares
 * the bar's ViewModel via the key, so both surfaces see one connection.
 */
@Composable
fun AvSpaceRoom(groupId: String, onClose: () -> Unit) {
    val repo = AppModule.nostrRepository
    val vm = viewModel(key = "avspace-$groupId") {
        AvSpaceViewModel(repo, groupId, repo.activePubkey.value)
    }
    val userMetadata by repo.userMetadata.collectAsState()
    AvSpaceRoomDialog(vm = vm, userMetadata = userMetadata, onClose = onClose)
}

/**
 * The room itself (prototype SpaceRoom): header with a LIVE badge, an audio-tile body split
 * into On stage / Listeners (or a video grid once someone publishes video), and a control bar.
 * Closing the dialog does NOT leave the room; Leave does.
 */
@Composable
private fun AvSpaceRoomDialog(
    vm: AvSpaceViewModel,
    userMetadata: Map<String, UserMetadata>,
    onClose: () -> Unit,
) {
    val participants by vm.participants.collectAsState()
    val connection by vm.connectionState.collectAsState()
    val micOn by vm.micEnabled.collectAsState()
    val error by vm.error.collectAsState()

    val connected = connection == AvConnectionState.Connected
    val anyVideo = participants.any { it.cameraEnabled }
    // Everyone unmuted, on camera, or speaking is on stage; the rest are listening (web parity).
    val onStage = participants.filter { it.micEnabled || it.isSpeaking || it.cameraEnabled }
    val listeners = participants - onStage.toSet()

    Dialog(onDismissRequest = onClose) {
        Surface(
            shape = NostrordShapes.shapeLarge,
            color = NostrordColors.Surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.lg, end = Spacing.xs, top = Spacing.sm, bottom = Spacing.sm),
                ) {
                    if (participants.isNotEmpty()) LiveBadge()
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (anyVideo) "Video room" else "Voice room",
                            color = NostrordColors.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = when (participants.size) {
                                0 -> "Nobody here yet"
                                1 -> "1 in the room"
                                else -> "${participants.size} in the room"
                            },
                            color = NostrordColors.TextMuted,
                            fontSize = 12.sp,
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = NostrordColors.TextMuted)
                    }
                }

                error?.let { message ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(NostrordColors.Error.copy(alpha = 0.12f))
                            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                    ) {
                        Text(
                            text = message,
                            color = NostrordColors.Error,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { vm.dismissError() }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Dismiss",
                                tint = NostrordColors.Error,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }

                // Body
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 420.dp)
                        .padding(Spacing.lg),
                ) {
                    when {
                        participants.isEmpty() -> Text(
                            text = "Nobody is in the room yet.",
                            color = NostrordColors.TextMuted,
                            fontSize = 14.sp,
                            modifier = Modifier.align(Alignment.Center),
                        )

                        anyVideo -> LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 140.dp),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            items(participants, key = { it.pubkey }) { participant ->
                                VideoTile(vm, participant, userMetadata)
                            }
                        }

                        else -> Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            AudioSection("On stage", onStage, userMetadata, small = false)
                            if (listeners.isNotEmpty()) {
                                AudioSection("Listeners", listeners, userMetadata, small = true)
                            }
                        }
                    }
                }

                // Controls
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(NostrordColors.SurfaceVariant)
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                ) {
                    when {
                        !vm.canJoin -> Text(
                            text = "$AV_UNSUPPORTED_MESSAGE.",
                            color = NostrordColors.TextMuted,
                            fontSize = 13.sp,
                        )

                        connected -> {
                            ControlButton(
                                active = micOn,
                                icon = if (micOn) Icons.Filled.Mic else Icons.Filled.MicOff,
                                label = if (micOn) "Mute" else "Unmute",
                                onClick = { vm.toggleMic() },
                            )
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(NostrordColors.Error)
                                    .clickable { vm.leave() }
                                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                                ) {
                                    Icon(
                                        Icons.Filled.CallEnd,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text("Leave", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        else -> Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (connection == AvConnectionState.Connecting) {
                                        NostrordColors.Primary.copy(alpha = 0.6f)
                                    } else {
                                        NostrordColors.Primary
                                    },
                                )
                                .clickable(enabled = connection == AvConnectionState.Disconnected) { vm.join() }
                                .padding(horizontal = Spacing.xxl, vertical = Spacing.sm),
                        ) {
                            Text(
                                text = if (connection == AvConnectionState.Connecting) "Joining..." else "Join room",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveBadge() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
        modifier = Modifier
            .clip(CircleShape)
            .background(NostrordColors.Error.copy(alpha = 0.15f))
            .padding(horizontal = Spacing.xs, vertical = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(NostrordColors.Error),
        )
        Text("LIVE", color = NostrordColors.Error, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AudioSection(
    label: String,
    people: List<AvSpaceParticipant>,
    userMetadata: Map<String, UserMetadata>,
    small: Boolean,
) {
    Text(
        text = "$label · ${people.size}",
        color = NostrordColors.TextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = Spacing.sm),
    )
    // A flowing grid without nesting a lazy scrollable inside the dialog's scroll column.
    people.chunked(if (small) 5 else 4).forEach { rowPeople ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            modifier = Modifier.padding(bottom = Spacing.md),
        ) {
            rowPeople.forEach { participant ->
                AudioTile(participant, userMetadata, small)
            }
        }
    }
}

@Composable
private fun AudioTile(
    participant: AvSpaceParticipant,
    userMetadata: Map<String, UserMetadata>,
    small: Boolean,
) {
    val avatarSize = if (small) 44.dp else 60.dp
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = Modifier.width(avatarSize + Spacing.md),
    ) {
        Box {
            Box(
                modifier = Modifier
                    .then(
                        if (participant.isSpeaking) {
                            Modifier.border(2.dp, NostrordColors.Success, CircleShape)
                        } else {
                            Modifier
                        },
                    )
                    .padding(2.dp),
            ) {
                OptimizedUserAvatar(
                    imageUrl = userMetadata[participant.pubkey]?.picture,
                    pubkey = participant.pubkey,
                    displayName = nameOf(participant.pubkey, userMetadata),
                    size = avatarSize,
                )
            }
            // Mic badge, bottom-end like the web tile.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(NostrordColors.Surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (participant.micEnabled) Icons.Filled.Mic else Icons.Filled.MicOff,
                    contentDescription = null,
                    tint = NostrordColors.TextSecondary,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
        Text(
            text = if (participant.isSelf) "You" else nameOf(participant.pubkey, userMetadata),
            color = NostrordColors.TextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A 16:9 tile that renders [participant]'s video once their track is attachable, falling back
 * to the avatar. Frames arrive through a [VideoFrameSink] the engine pushes RGBA into.
 */
@Composable
private fun VideoTile(
    vm: AvSpaceViewModel,
    participant: AvSpaceParticipant,
    userMetadata: Map<String, UserMetadata>,
) {
    val sink = remember(participant.identity) { VideoFrameSink() }
    var attached by remember(participant.identity) { mutableStateOf(false) }

    DisposableEffect(participant.identity, participant.cameraEnabled) {
        val identity = participant.identity
        attached = identity != null && participant.cameraEnabled && vm.attachVideo(identity, sink)
        onDispose {
            if (identity != null) vm.detachVideo(identity, sink)
        }
    }
    val frame by sink.frame.collectAsState()

    Box(
        modifier = Modifier
            .aspectRatio(16f / 9f)
            .clip(NostrordShapes.shapeMedium)
            .background(Color.Black)
            .then(
                if (participant.isSpeaking) {
                    Modifier.border(1.dp, NostrordColors.Success, NostrordShapes.shapeMedium)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val current = frame
        if (attached && current != null) {
            // A new ImageBitmap per frame: correctness first. Tile-sized frames keep this
            // cheap, and a reused bitmap would need pixel-write APIs Compose does not share.
            Image(
                bitmap = rgbaToImageBitmap(current.width, current.height, current.rgba),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OptimizedUserAvatar(
                imageUrl = userMetadata[participant.pubkey]?.picture,
                pubkey = participant.pubkey,
                displayName = nameOf(participant.pubkey, userMetadata),
                size = 48.dp,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
        ) {
            Icon(
                imageVector = if (participant.micEnabled) Icons.Filled.Mic else Icons.Filled.MicOff,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = if (participant.isSelf) "You" else nameOf(participant.pubkey, userMetadata),
                color = Color.White,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ControlButton(active: Boolean, icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (active) NostrordColors.Primary else NostrordColors.InputBackground)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) Color.White else NostrordColors.TextSecondary,
            modifier = Modifier.size(20.dp),
        )
    }
}
