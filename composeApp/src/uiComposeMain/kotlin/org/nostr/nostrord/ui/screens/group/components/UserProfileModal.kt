package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.nostr.nostrord.auth.ActiveAccountManager
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.nostr.Nip57
import org.nostr.nostrord.ui.components.IdentifierField
import org.nostr.nostrord.ui.components.ModalTitleBar
import org.nostr.nostrord.ui.components.ReportUserModal
import org.nostr.nostrord.ui.components.avatars.ProfileAvatar
import org.nostr.nostrord.ui.components.buttons.AppButton
import org.nostr.nostrord.ui.components.buttons.AppButtonVariant
import org.nostr.nostrord.ui.components.buttons.FollowButton
import org.nostr.nostrord.ui.components.zap.ZapController
import org.nostr.nostrord.ui.isValidNip05
import org.nostr.nostrord.ui.navigation.DmRoute
import org.nostr.nostrord.ui.navigation.LocalFrameNavigator
import org.nostr.nostrord.ui.navigation.UserRoute
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.Spacing

/**
 * One row in the profile modal's action list (prototype ActionRow). Disabled rows
 * (backend not wired yet) render at reduced opacity and ignore clicks, like the
 * chat context menu's "Coming soon" items.
 */
@Composable
private fun ProfileActionRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    emoji: String? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val tint =
        when {
            danger -> NostrordColors.Error
            isHovered && enabled -> NostrordColors.TextPrimary
            else -> NostrordColors.TextSecondary
        }
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isHovered && enabled) NostrordColors.HoverBackground else Color.Transparent)
            .hoverable(interactionSource)
            .then(
                if (enabled) {
                    Modifier
                        .clickable(onClick = onClick)
                        .pointerHoverIcon(PointerIcon.Hand)
                } else {
                    Modifier.alpha(0.45f)
                },
            )
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            icon != null ->
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp),
                )
            emoji != null -> Text(emoji, fontSize = 16.sp)
        }
        Text(label, color = tint, fontSize = 14.sp)
    }
}

/**
 * Quick user profile modal (prototype UserProfileCard): clickable header row to the
 * full profile page, about, the cycling IdentifierField, Follow + Message buttons,
 * View profile, and the action list (zap / mention / mute / report, plus the admin
 * rows when opened from a group by an admin). Actions whose backends don't exist
 * yet render disabled, like the chat context menu's "Coming soon" items.
 */
@Composable
fun UserProfileModal(
    pubkey: String,
    metadata: UserMetadata?,
    userMetadata: Map<String, UserMetadata> = emptyMap(),
    iAmAdmin: Boolean = false,
    targetIsAdmin: Boolean = false,
    onRemoveFromGroup: (() -> Unit)? = null,
    onToggleAdminRole: (() -> Unit)? = null,
    onMention: ((String) -> Unit)? = null,
    onUserClick: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val npub = remember(pubkey) { Nip19.encodeNpub(pubkey) }
    val displayName =
        metadata?.displayName?.takeIf { it.isNotBlank() }
            ?: metadata?.name?.takeIf { it.isNotBlank() }
            ?: (npub.take(12) + "…")
    val isSelf = ActiveAccountManager.currentPubkey == pubkey

    // Follow state from the active account's kind:3 contact list (fetched once on open).
    val scope = rememberCoroutineScope()
    val following by AppModule.nostrRepository.following.collectAsState()
    val mutedPubkeys by AppModule.nostrRepository.mutedPubkeys.collectAsState()
    val isMuted = pubkey in mutedPubkeys
    val dmEnabled by AppModule.dmSettings.dmEnabled.collectAsState()
    val isFollowing = pubkey in following
    var followBusy by remember(pubkey) { mutableStateOf(false) }
    var showReport by remember(pubkey) { mutableStateOf(false) }
    LaunchedEffect(Unit) { AppModule.nostrRepository.requestContactList() }

    // Zaps require signing a kind:9734 request, so only offer them when an account
    // with a usable signer is active AND the profile has a lightning address.
    val activeSession by ActiveAccountManager.session.collectAsState()
    val canZap = activeSession != null &&
        Nip57.resolvePayEndpoint(metadata?.lud16, metadata?.lud06) != null

    // Full-page navigation is only available inside the new-design frame (the
    // navigator local is absent under the legacy navigation).
    val frameNavigator = LocalFrameNavigator.current
    val openFull: (() -> Unit)? =
        frameNavigator?.let {
            {
                onDismiss()
                it(UserRoute(pubkey))
            }
        }

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
                    .widthIn(max = 440.dp)
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
                    ModalTitleBar(title = "Profile", onClose = onDismiss)

                    Column(
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg)
                            .padding(top = Spacing.md, bottom = Spacing.lg),
                    ) {
                        // Header row: avatar + name (+ ADMIN badge) + nip-05, opens the full page.
                        Row(
                            modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .then(
                                    if (openFull != null) {
                                        Modifier
                                            .clickable { openFull() }
                                            .pointerHoverIcon(PointerIcon.Hand)
                                    } else {
                                        Modifier
                                    },
                                )
                                .padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        ) {
                            ProfileAvatar(
                                imageUrl = metadata?.picture,
                                displayName = displayName,
                                pubkey = pubkey,
                                size = 64.dp,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                                ) {
                                    Text(
                                        text = displayName,
                                        color = NostrordColors.TextPrimary,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (targetIsAdmin) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = NostrordColors.Primary.copy(alpha = 0.2f),
                                        ) {
                                            Text(
                                                text = "ADMIN",
                                                color = NostrordColors.Primary,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                                val nip05 = metadata?.nip05
                                if (nip05 != null && isValidNip05(nip05)) {
                                    Text(
                                        text = nip05,
                                        color = NostrordColors.Success,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (openFull != null) {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = NostrordColors.TextMuted,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }

                        // The bio lives on the full profile page (#/u/), not in the quick card.

                        // Cycling identifier (prototype IdentifierField): npub / nprofile /
                        // link / hex / nip-05 with swap + copy.
                        Spacer(modifier = Modifier.height(Spacing.md))
                        IdentifierField(pubkey = pubkey, nip05 = metadata?.nip05)

                        if (!isSelf) {
                            Spacer(modifier = Modifier.height(Spacing.md))
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                FollowButton(
                                    isFollowing = isFollowing,
                                    isBusy = followBusy,
                                    onToggle = {
                                        followBusy = true
                                        scope.launch {
                                            if (isFollowing) {
                                                AppModule.nostrRepository.unfollowUser(pubkey)
                                            } else {
                                                AppModule.nostrRepository.followUser(pubkey)
                                            }
                                            followBusy = false
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                if (dmEnabled) {
                                    AppButton(
                                        text = "Message",
                                        onClick = {
                                            onDismiss()
                                            frameNavigator?.invoke(DmRoute(pubkey))
                                        },
                                        enabled = frameNavigator != null,
                                        variant = AppButtonVariant.Secondary,
                                        icon = Icons.Default.Mail,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }

                        // Prototype UserProfileCard: jump to the full profile page.
                        if (openFull != null) {
                            Spacer(modifier = Modifier.height(Spacing.sm))
                            AppButton(
                                text = "View profile",
                                onClick = { openFull() },
                                variant = AppButtonVariant.Secondary,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        if (!isSelf) {
                            Spacer(modifier = Modifier.height(Spacing.lg))
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                ProfileActionRow(
                                    label = "Send zap",
                                    icon = Icons.Outlined.Bolt,
                                    enabled = canZap,
                                ) {
                                    ZapController.request(pubkey, null)
                                    onDismiss()
                                }
                                // Mention only exists inside a group chat (where a composer
                                // can receive it); elsewhere (home sidebar, etc.) the row is
                                // absent rather than shown disabled. Report joins when its
                                // backend lands (NIP-56 reports).
                                if (onMention != null) {
                                    ProfileActionRow(
                                        label = "Mention",
                                        icon = Icons.AutoMirrored.Filled.Reply,
                                    ) {
                                        onMention.invoke(pubkey)
                                    }
                                }
                                ProfileActionRow(
                                    label = if (isMuted) "Unmute user" else "Mute user",
                                    emoji = "🔕",
                                ) {
                                    scope.launch {
                                        if (isMuted) {
                                            AppModule.nostrRepository.unmuteUser(pubkey)
                                        } else {
                                            AppModule.nostrRepository.muteUser(pubkey)
                                        }
                                    }
                                }
                                ProfileActionRow(label = "Report user", icon = Icons.Default.Shield) {
                                    showReport = true
                                }

                                if (iAmAdmin && (onToggleAdminRole != null || onRemoveFromGroup != null)) {
                                    HorizontalDivider(
                                        color = NostrordColors.Divider,
                                        thickness = 1.dp,
                                        modifier = Modifier.padding(vertical = 4.dp),
                                    )
                                    if (onToggleAdminRole != null) {
                                        ProfileActionRow(
                                            label = if (targetIsAdmin) "Demote from admin" else "Promote to admin",
                                            icon = Icons.Default.Shield,
                                        ) {
                                            onToggleAdminRole()
                                        }
                                    }
                                    if (onRemoveFromGroup != null) {
                                        ProfileActionRow(
                                            label = "Remove from group",
                                            icon = Icons.Default.Delete,
                                            danger = true,
                                        ) {
                                            onRemoveFromGroup()
                                        }
                                    }
                                }
                            }
                        }

                        if (isSelf) {
                            Spacer(modifier = Modifier.height(Spacing.lg))
                            Text(
                                text = "This is you. Edit your profile in Settings.",
                                color = NostrordColors.TextMuted,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showReport) {
        ReportUserModal(
            pubkey = pubkey,
            metadata = metadata,
            onDismiss = { showReport = false },
        )
    }
}
