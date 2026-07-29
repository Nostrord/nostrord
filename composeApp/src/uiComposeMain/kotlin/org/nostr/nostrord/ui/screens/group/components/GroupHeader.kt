package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.ui.components.avatars.GroupGradientAvatar
import org.nostr.nostrord.ui.components.avatars.rememberAvatarImageState
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.Spacing

/** Group header with avatar, name, join/invite actions, and admin menu. */
@Composable
fun GroupHeader(
    groupName: String?,
    groupMetadata: GroupMetadata?,
    relayUrl: String = "",
    groupId: String = "",
    isJoined: Boolean,
    /** Relay-side member with no entry in our own kind:10009: offer to add it. */
    canAddToList: Boolean = false,
    onAddToListClick: () -> Unit = {},
    onJoinClick: (inviteCode: String?) -> Unit,
    onLeaveClick: () -> Unit,
    onTitleClick: () -> Unit = {},
    onEditClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {},
    onManageMembersClick: () -> Unit = {},
    onInviteCodesClick: () -> Unit = {},
    showSubgroupControls: Boolean = true,
    parentGroupName: String? = null,
    onParentClick: () -> Unit = {},
    childCount: Int = 0,
    isAdmin: Boolean = false,
    isClosed: Boolean = false,
    initialInviteCode: String? = null,
    pendingJoinRequestCount: Int = 0,
    onJoinRequestsClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    searchActive: Boolean = false,
    connectionState: ConnectionManager.ConnectionState? = null,
    modifier: Modifier = Modifier,
    // Compact (mobile) header: hides the inline description and the Info button, and shows an
    // icon-only Join, matching the web mobile chat header.
    compact: Boolean = false,
    navigationIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    // Prototype chat header: the body background (not a darker block) with a hairline bottom
    // border and a soft shadow, so the header reads as a distinct bar over the message list.
    val lineColor = NostrordColors.Line
    Column(
        modifier =
        modifier
            .fillMaxWidth()
            .shadow(elevation = 2.dp, clip = false)
            .background(NostrordColors.Background)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawLine(
                    color = lineColor,
                    start = Offset(0f, size.height - stroke / 2f),
                    end = Offset(size.width, size.height - stroke / 2f),
                    strokeWidth = stroke,
                )
            },
    ) {
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .height(Spacing.headerHeight)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (navigationIcon != null) {
                navigationIcon()
            }

            Row(
                modifier =
                Modifier
                    .weight(1f)
                    .clickable(onClick = onTitleClick)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GroupHeaderIcon(
                    pictureUrl = groupMetadata?.picture,
                    groupId = groupMetadata?.id ?: "",
                    displayName = groupName ?: "Group",
                    size = 26.dp,
                )

                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    // Discord model: hierarchy lives in the sidebar channel list, so the header
                    // shows no parent breadcrumb or child-count chip (web parity).
                    // Prototype single-row title: name · relay dot · inline description.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = groupMetadata?.name ?: groupName ?: groupId.ifBlank { "Unknown Group" },
                            color = NostrordColors.TextPrimary,
                            style = MaterialTheme.typography.titleMedium,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (connectionState != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            RelayStatusDot(connectionState)
                        }
                        if (!compact && !groupMetadata?.about.isNullOrBlank()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = groupMetadata?.about ?: "",
                                color = NostrordColors.TextMuted,
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            // Header actions in prototype order: Search, Invite, Info, Members.
            // Square 30dp buttons with a 4dp gap, matching the web .chat-icon-btn row.
            var showShareModal by remember { mutableStateOf(false) }
            // A private group exposes nothing to non-members (no messages, member list or
            // shareable invite), so hide Search / Invite / Info there; a public group, or a
            // private one you've joined, keeps them. (mirrors the web chat header)
            val showGroupActions = groupMetadata?.isPublic != false || isJoined

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isAdmin && pendingJoinRequestCount > 0) {
                    Box {
                        IconButton(
                            onClick = onJoinRequestsClick,
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(
                                Icons.Default.PersonAdd,
                                contentDescription = "Join requests",
                                tint = NostrordColors.TextSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Box(
                            modifier =
                            Modifier
                                // Top-right corner, nudged just outside the icon (mirrors the web
                                // .chat-requests-badge at top:-2px/right:-2px) so it doesn't sit on
                                // the glyph.
                                .align(Alignment.TopEnd)
                                .offset(x = 2.dp, y = (-2).dp)
                                .size(16.dp)
                                .background(NostrordColors.Error, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (pendingJoinRequestCount > 9) "9+" else pendingJoinRequestCount.toString(),
                                // Trim the line box and center the glyph within it so the count
                                // sits vertically centered in the circle (cross-platform; the
                                // Android-only includeFontPadding flag isn't available here).
                                style =
                                TextStyle(
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 10.sp,
                                    textAlign = TextAlign.Center,
                                    lineHeightStyle =
                                    LineHeightStyle(
                                        alignment = LineHeightStyle.Alignment.Center,
                                        trim = LineHeightStyle.Trim.Both,
                                    ),
                                ),
                            )
                        }
                    }
                }

                if (showGroupActions) {
                    IconButton(
                        onClick = onSearchClick,
                        modifier = Modifier.size(30.dp),
                        // Active fill via the container so it matches the hover state-layer circle.
                        colors =
                        if (searchActive) {
                            IconButtonDefaults.iconButtonColors(containerColor = NostrordColors.SurfaceVariant)
                        } else {
                            IconButtonDefaults.iconButtonColors()
                        },
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Search messages",
                            tint = if (searchActive) NostrordColors.TextPrimary else NostrordColors.TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    if (relayUrl.isNotBlank() && groupId.isNotBlank()) {
                        IconButton(
                            onClick = { showShareModal = true },
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(
                                Icons.Default.Link,
                                contentDescription = "Share",
                                tint = NostrordColors.TextSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    if (!compact) {
                        IconButton(
                            onClick = onTitleClick,
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = "Group info",
                                tint = NostrordColors.TextSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    // Subgroup channel: the header cog is the discoverable admin entry for
                    // managing THIS channel (root groups keep the sidebar "Manage group" row).
                    if (isAdmin && groupMetadata?.parent != null) {
                        IconButton(
                            onClick = onEditClick,
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Manage channel",
                                tint = NostrordColors.TextSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                if (trailingIcon != null) {
                    trailingIcon()
                }
            }

            if (showShareModal && relayUrl.isNotBlank() && groupId.isNotBlank()) {
                ShareGroupModal(
                    relayUrl = relayUrl,
                    groupId = groupId,
                    onDismiss = { showShareModal = false },
                )
            }

            Row(
                // 4dp from the icon-button cluster, then 4dp between the buttons —
                // the same uniform gap as the web .chat-header row.
                modifier = Modifier.padding(start = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Relay grants membership but our list has no entry: one tap reconciles them.
                // Placed before the join branch, which this state never reaches.
                if (canAddToList) {
                    Button(
                        onClick = onAddToListClick,
                        colors = ButtonDefaults.buttonColors(containerColor = NostrordColors.Primary),
                        contentPadding =
                        if (compact) {
                            PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        } else {
                            PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        },
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Add to my groups",
                            modifier = Modifier.size(16.dp),
                        )
                        if (!compact) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add to my groups", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                if (!isJoined) {
                    val showInviteButton = isClosed || initialInviteCode != null
                    var showInviteModal by remember { mutableStateOf(initialInviteCode != null) }

                    if (showInviteButton) {
                        Button(
                            onClick = { showInviteModal = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors =
                            ButtonDefaults.buttonColors(
                                containerColor = NostrordColors.SurfaceVariant,
                                contentColor = NostrordColors.TextPrimary,
                            ),
                        ) {
                            Icon(
                                Icons.Default.VpnKey,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Invite Code", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    Button(
                        onClick = { onJoinClick(null) },
                        colors = ButtonDefaults.buttonColors(containerColor = NostrordColors.Primary),
                        contentPadding =
                        if (compact) {
                            PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        } else {
                            PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        },
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(
                            Icons.Default.PersonAdd,
                            contentDescription = if (isClosed) "Request to Join" else "Join",
                            modifier = Modifier.size(16.dp),
                        )
                        // Compact (mobile) shows an icon-only Join, like the web mobile header.
                        if (!compact) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (isClosed) "Request to Join" else "Join",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }

                    if (showInviteModal) {
                        InviteCodeJoinModal(
                            initialCode = initialInviteCode ?: "",
                            onJoin = { code ->
                                showInviteModal = false
                                onJoinClick(code)
                            },
                            onDismiss = { showInviteModal = false },
                        )
                    }
                }
                // No 3-dots menu (prototype shape): Leave lives in the info modal,
                // admin management in the sidebar "Manage group" entry and the
                // members panel gear.
            }
        }
    }
}

/**
 * Connection dot for the group's relay (prototype RelayStatusDot):
 * green = connected, yellow = connecting/reconnecting, red = offline/error.
 */
@Composable
internal fun RelayStatusDot(state: ConnectionManager.ConnectionState) {
    val color =
        when (state) {
            is ConnectionManager.ConnectionState.Connected -> NostrordColors.Success
            is ConnectionManager.ConnectionState.Connecting,
            is ConnectionManager.ConnectionState.Reconnecting,
            -> NostrordColors.Warning
            else -> NostrordColors.Error
        }
    Box(
        modifier =
        Modifier
            .size(8.dp)
            .background(color, CircleShape),
    )
}

/** Modal for entering an invite code to join a closed group. */
@Composable
fun InviteCodeJoinModal(
    initialCode: String = "",
    onJoin: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var inviteCode by remember { mutableStateOf(initialCode) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NostrordColors.Surface,
        title = {
            Text(
                "Join with Invite Code",
                color = NostrordColors.TextPrimary,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            OutlinedTextField(
                value = inviteCode,
                onValueChange = { inviteCode = it.trim() },
                placeholder = {
                    Text(
                        "Enter invite code",
                        color = NostrordColors.TextMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors =
                OutlinedTextFieldDefaults.colors(
                    focusedTextColor = NostrordColors.TextContent,
                    unfocusedTextColor = NostrordColors.TextContent,
                    focusedBorderColor = NostrordColors.Primary,
                    unfocusedBorderColor = NostrordColors.SurfaceVariant,
                    cursorColor = NostrordColors.Primary,
                ),
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(8.dp),
            )
        },
        confirmButton = {
            Button(
                onClick = { onJoin(inviteCode) },
                enabled = inviteCode.isNotBlank(),
                colors =
                ButtonDefaults.buttonColors(
                    containerColor = NostrordColors.Primary,
                    contentColor = Color.White,
                    disabledContainerColor = NostrordColors.Primary.copy(alpha = 0.3f),
                    disabledContentColor = Color.White.copy(alpha = 0.5f),
                ),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Join")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = NostrordColors.TextSecondary)
            }
        },
    )
}

@Composable
internal fun GroupHeaderIcon(
    pictureUrl: String?,
    groupId: String,
    displayName: String,
    size: androidx.compose.ui.unit.Dp,
    cornerRadius: androidx.compose.ui.unit.Dp = 8.dp,
) {
    val context = LocalPlatformContext.current
    val iconShape = RoundedCornerShape(cornerRadius)
    // Self-healing load (keyed on the URL): a transient failure no longer latches the icon to its
    // gradient placeholder for the rest of the session.
    val avatar = rememberAvatarImageState(pictureUrl)
    val hasImage = !pictureUrl.isNullOrBlank() && avatar.state !is AsyncImagePainter.State.Error
    // Only treat the icon as "image-backed" once the photo has actually loaded. While loading,
    // keep the gradient (not a bare dark/black tile) so a missing or slow picture still shows
    // the seeded fallback, matching the web group avatar.
    val loaded = avatar.state is AsyncImagePainter.State.Success

    Box(
        modifier =
        Modifier
            .size(size)
            .clip(iconShape)
            .background(if (loaded) NostrordColors.BackgroundDark else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            // Seeded conic-swirl gradient + initial letter, matching the web group
            // avatar fallback (groupGradientCss). The outer Box clips it to iconShape.
            GroupGradientAvatar(
                seed = groupId,
                name = displayName,
                size = size,
            )
        }
        // Gate out on Error so the retry's Error -> Empty reset re-composes a fresh load.
        if (hasImage) {
            AsyncImage(
                model =
                ImageRequest
                    .Builder(context)
                    .data(pictureUrl)
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = displayName,
                modifier =
                Modifier
                    .fillMaxSize()
                    .clip(iconShape),
                contentScale = ContentScale.Crop,
                onState = avatar.onState,
            )
        }
    }
}
