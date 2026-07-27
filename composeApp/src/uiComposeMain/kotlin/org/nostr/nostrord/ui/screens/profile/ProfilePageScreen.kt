package org.nostr.nostrord.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import org.nostr.nostrord.auth.ActiveAccountManager
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.Nip57
import org.nostr.nostrord.ui.components.GroupTypeBadges
import org.nostr.nostrord.ui.components.IdentifierField
import org.nostr.nostrord.ui.components.ReportUserModal
import org.nostr.nostrord.ui.components.RichAboutText
import org.nostr.nostrord.ui.components.avatars.OptimizedSmallAvatar
import org.nostr.nostrord.ui.components.buttons.AppButton
import org.nostr.nostrord.ui.components.buttons.AppButtonSize
import org.nostr.nostrord.ui.components.buttons.AppButtonVariant
import org.nostr.nostrord.ui.components.buttons.FollowButton
import org.nostr.nostrord.ui.components.layout.PageHeader
import org.nostr.nostrord.ui.components.zap.ZapController
import org.nostr.nostrord.ui.isValidNip05
import org.nostr.nostrord.ui.navigation.DmRoute
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.navigation.LocalFrameNavigator
import org.nostr.nostrord.ui.navigation.UserRoute
import org.nostr.nostrord.ui.theme.AvatarGradients
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.Spacing

/**
 * New-design user profile page (prototype Profile, /u/:pubkey): header, identity
 * card (gradient banner matching the user's avatar identity, avatar, name + ADMIN
 * badge, NIP-05, about, npub with copy) and the groups this user is in. Follow /
 * DM actions arrive with the follow and DM features. Mirrors the web
 * web/screens/ProfilePage.
 */
@Composable
fun ProfilePageScreen(
    pubkey: String,
    onOpenGroup: (GroupRoute) -> Unit,
    onEditProfile: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
) {
    val vm = viewModel(key = "profile-$pubkey") { ProfilePageViewModel(AppModule.nostrRepository, pubkey) }
    val metadata by vm.metadata.collectAsState()
    val groups by vm.userGroups.collectAsState()
    val isFollowing by vm.isFollowing.collectAsState()
    val isFollowBusy by vm.isFollowBusy.collectAsState()

    val name =
        metadata?.displayName?.takeIf { it.isNotBlank() }
            ?: metadata?.name?.takeIf { it.isNotBlank() }
            ?: vm.npub.take(12) + "..."

    Column(modifier = modifier.fillMaxSize().background(NostrordColors.Background)) {
        PageHeader(
            icon = Icons.Default.Person,
            title = "Profile",
            onOpenDrawer = onOpenDrawer,
        )

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(modifier = Modifier.widthIn(max = 672.dp).padding(horizontal = Spacing.lg, vertical = Spacing.xxl)) {
                // Identity card: banner + avatar + name + about + npub.
                Surface(shape = NostrordShapes.shapeXLarge, color = NostrordColors.Surface) {
                    Column {
                        ProfileBanner(seed = pubkey, bannerUrl = metadata?.banner)
                        Column(modifier = Modifier.padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl)) {
                            Row(
                                modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .layout { measurable, constraints ->
                                        // Overlap the banner by 40dp (web .profile-page-avatar-row
                                        // margin-top:-40), reserving only the part below it so the
                                        // content reflows and the card keeps no extra bottom space.
                                        val placeable = measurable.measure(constraints)
                                        val overlap = 40.dp.roundToPx()
                                        layout(placeable.width, (placeable.height - overlap).coerceAtLeast(0)) {
                                            placeable.place(0, -overlap)
                                        }
                                    },
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                Box(
                                    modifier =
                                    Modifier
                                        .size(88.dp)
                                        .clip(CircleShape)
                                        .background(NostrordColors.Surface),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    OptimizedSmallAvatar(
                                        imageUrl = metadata?.picture,
                                        identifier = pubkey,
                                        displayName = name,
                                        size = 80.dp,
                                        shape = CircleShape,
                                    )
                                }
                                Spacer(modifier = Modifier.weight(1f))
                                if (vm.isSelf) {
                                    AppButton(
                                        text = "Edit profile",
                                        onClick = onEditProfile,
                                        variant = AppButtonVariant.Secondary,
                                    )
                                } else {
                                    val frameNavigator = LocalFrameNavigator.current
                                    val dmEnabled by AppModule.dmSettings.dmEnabled.collectAsState()
                                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                        if (dmEnabled) {
                                            AppButton(
                                                text = "Message",
                                                onClick = { frameNavigator?.invoke(DmRoute(pubkey)) },
                                                enabled = frameNavigator != null,
                                                variant = AppButtonVariant.Secondary,
                                                icon = Icons.Default.Mail,
                                            )
                                        }
                                        FollowButton(
                                            isFollowing = isFollowing,
                                            isBusy = isFollowBusy,
                                            onToggle = { vm.toggleFollow() },
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(Spacing.md))
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                                ) {
                                    Text(
                                        name,
                                        color = NostrordColors.TextPrimary,
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                metadata?.nip05?.takeIf { isValidNip05(it) }?.let {
                                    Text(it, color = NostrordColors.Success, fontSize = 14.sp)
                                }
                                metadata?.about?.takeIf { it.isNotBlank() }?.let { about ->
                                    Spacer(modifier = Modifier.height(Spacing.md))
                                    // Rich parser: npub/nprofile mentions resolve to @names and
                                    // navigate to that profile (same as the web renderAboutText).
                                    val frameNavigator = LocalFrameNavigator.current
                                    val allMeta by AppModule.nostrRepository.userMetadata.collectAsState()
                                    RichAboutText(
                                        text = about,
                                        userMetadata = allMeta,
                                        style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
                                        color = NostrordColors.TextSecondary,
                                        onMentionClick = frameNavigator?.let { nav -> { pk -> nav(UserRoute(pk)) } },
                                    )
                                }
                                Spacer(modifier = Modifier.height(Spacing.lg))
                                // Cycling identifier (prototype IdentifierField): npub /
                                // nprofile / link / hex / nip-05 with swap + copy.
                                IdentifierField(pubkey = pubkey, nip05 = metadata?.nip05)

                                if (!vm.isSelf) {
                                    // Zaps require a signer + a lightning address.
                                    val activeSession by ActiveAccountManager.session.collectAsState()
                                    val canZap = activeSession != null &&
                                        Nip57.resolvePayEndpoint(metadata?.lud16, metadata?.lud06) != null
                                    val isMuted by vm.isMuted.collectAsState()
                                    var showReport by remember { mutableStateOf(false) }
                                    Spacer(modifier = Modifier.height(Spacing.md))
                                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                        AppButton(
                                            text = "Zap",
                                            onClick = { ZapController.request(pubkey, null) },
                                            enabled = canZap,
                                            variant = AppButtonVariant.Secondary,
                                            size = AppButtonSize.Small,
                                            icon = Icons.Outlined.Bolt,
                                        )
                                        AppButton(
                                            text = if (isMuted) "Unmute" else "Mute",
                                            onClick = { vm.toggleMute() },
                                            variant = AppButtonVariant.Ghost,
                                            size = AppButtonSize.Small,
                                            icon = Icons.Outlined.NotificationsOff,
                                        )
                                        AppButton(
                                            text = "Report",
                                            onClick = { showReport = true },
                                            variant = AppButtonVariant.Ghost,
                                            size = AppButtonSize.Small,
                                            icon = Icons.Default.Shield,
                                        )
                                    }
                                    if (showReport) {
                                        ReportUserModal(
                                            pubkey = pubkey,
                                            metadata = metadata,
                                            onDismiss = { showReport = false },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.xxl))
                Text(
                    (if (vm.isSelf) "YOUR GROUPS" else name.uppercase() + "'S GROUPS") + " · ${groups.size}",
                    color = NostrordColors.TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                if (groups.isEmpty()) {
                    val dividerColor = NostrordColors.Divider
                    Box(
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .drawBehind {
                                drawRoundRect(
                                    color = dividerColor,
                                    cornerRadius = CornerRadius(16.dp.toPx()),
                                    style = Stroke(
                                        width = 1.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                                    ),
                                )
                            }
                            .padding(vertical = Spacing.xxxl),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No groups to show.",
                            color = NostrordColors.TextMuted,
                            fontSize = 13.sp,
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        groups.forEach { group ->
                            ProfileGroupRow(group = group, onClick = { onOpenGroup(GroupRoute(group.relayUrl, group.meta.id)) })
                        }
                    }
                }
            }
        }
    }
}

/**
 * Banner from the user's avatar identity hues, darkened (prototype: u.color into dark),
 * with the user's real banner image loaded over it when present (covers the gradient
 * once it loads; the gradient stays as the fallback if missing or it fails).
 */
@Composable
private fun ProfileBanner(seed: String, bannerUrl: String?) {
    val gradient = remember(seed) { AvatarGradients.user(seed) }
    val dark = NostrordColors.BackgroundDark
    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .height(112.dp)
            .drawBehind {
                drawRect(
                    brush =
                    Brush.linearGradient(
                        colors =
                        listOf(
                            Color.hsl(gradient.start.hue.toFloat(), 0.62f, 0.40f),
                            dark,
                        ),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    ),
                )
            },
    ) {
        bannerUrl?.takeIf { it.isNotBlank() }?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun ProfileGroupRow(
    group: ProfileGroup,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    Surface(
        modifier = Modifier.fillMaxWidth().clip(NostrordShapes.shapeMedium).hoverable(interactionSource),
        shape = NostrordShapes.shapeMedium,
        color = if (isHovered) NostrordColors.HoverBackground else NostrordColors.Surface,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            OptimizedSmallAvatar(
                imageUrl = group.meta.picture,
                identifier = group.meta.id,
                displayName = group.meta.name ?: group.meta.id,
                size = 44.dp,
                shape = NostrordShapes.shapeMedium,
                isGroup = true,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                // Top line: name + the admin chip (the viewer's role) hug the left as a
                // unit; the group's access tags are pushed to the right edge.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            group.meta.name ?: group.meta.id,
                            modifier = Modifier.weight(1f, fill = false),
                            color = NostrordColors.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (group.isAdmin) {
                            Spacer(modifier = Modifier.width(Spacing.sm))
                            Surface(shape = NostrordShapes.shapeSmall, color = NostrordColors.Primary.copy(alpha = 0.2f)) {
                                Text(
                                    "admin",
                                    color = NostrordColors.Primary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    GroupTypeBadges(
                        isPublic = group.meta.isPublic,
                        isOpen = group.meta.isOpen,
                        isRestricted = group.meta.isRestricted,
                        isHidden = group.meta.isHidden,
                    )
                }
                if (group.memberCount > 0) {
                    Text(
                        "${group.memberCount} members",
                        color = NostrordColors.TextMuted,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}
