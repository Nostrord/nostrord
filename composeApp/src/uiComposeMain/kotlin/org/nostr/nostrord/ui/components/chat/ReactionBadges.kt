package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.ui.components.avatars.UserGradientAvatar
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.ui.theme.rememberEmojiFontFamily
import org.nostr.nostrord.utils.proxyViaWeserv
import org.nostr.nostrord.utils.shortNpub

/**
 * Displays reaction badges for a message.
 * Each badge shows an emoji (or custom emoji image), stacked avatars of reactors, and the count.
 *
 * @param reactions Map of emoji to ReactionInfo containing URL and list of reactors
 * @param currentUserPubkey The current user's pubkey (to highlight their reactions)
 * @param resolveMetadata Resolves pubkey to UserMetadata for reactor avatars
 * @param onReactionClick Called when a reaction badge is clicked (for adding/removing reactions)
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ReactionBadges(
    reactions: Map<String, GroupManager.ReactionInfo>,
    currentUserPubkey: String? = null,
    resolveMetadata: (String) -> UserMetadata? = { null },
    onReactionClick: (emoji: String) -> Unit = {},
    pendingEmojis: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
) {
    // Pending emojis still without a real reaction badge: show a spinner placeholder
    // until the reaction lands (the optimistic update merges it into [reactions]).
    val visiblePending =
        remember(pendingEmojis, reactions) {
            pendingEmojis.filter { it !in reactions }
        }
    if (reactions.isEmpty() && visiblePending.isEmpty()) return

    val context = LocalPlatformContext.current
    val emojiUrls =
        remember(reactions) {
            reactions.values.mapNotNull { it.emojiUrl }.distinct()
        }
    LaunchedEffect(emojiUrls) {
        if (emojiUrls.isEmpty()) return@LaunchedEffect
        val loader = SingletonImageLoader.get(context)
        emojiUrls.forEach { url ->
            val request =
                ImageRequest
                    .Builder(context)
                    .data(url)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .size(Size(36, 36))
                    .build()
            loader.enqueue(request)
        }
    }

    FlowRow(
        modifier = modifier.padding(top = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        // Sort by count (highest first), then alphabetically — memoized to avoid
        // re-sorting on every recomposition when the reactions map hasn't changed.
        val sortedReactions =
            remember(reactions) {
                reactions.entries
                    .sortedWith(
                        compareByDescending<Map.Entry<String, GroupManager.ReactionInfo>> { it.value.reactors.size }
                            .thenBy { it.key },
                    )
            }

        sortedReactions.forEach { (emoji, info) ->
            val hasCurrentUserReacted = currentUserPubkey != null && currentUserPubkey in info.reactors

            // Who reacted: tooltip on hover (desktop) or long-press (touch).
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text(reactorNames(info.reactors, resolveMetadata)) } },
                state = rememberTooltipState(),
            ) {
                ReactionBadge(
                    emoji = emoji,
                    emojiUrl = info.emojiUrl,
                    reactors = info.reactors,
                    count = info.reactors.size,
                    isUserReacted = hasCurrentUserReacted,
                    resolveMetadata = resolveMetadata,
                    onClick = { onReactionClick(emoji) },
                )
            }
        }

        // In-flight reactions: emoji with a spinner where the avatar stack would be.
        visiblePending.forEach { emoji ->
            PendingReactionBadge(emoji = emoji)
        }
    }
}

/**
 * Placeholder badge shown while a reaction is being signed and published.
 * Mirrors [ReactionBadge] sizing but swaps the avatar stack for a small spinner.
 */
@Composable
private fun PendingReactionBadge(emoji: String) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier =
        Modifier
            .clip(shape)
            .background(NostrordColors.SurfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            val displayEmoji =
                when (emoji) {
                    "+" -> "👍" // 👍
                    "-" -> "👎" // 👎
                    else -> emoji
                }
            Text(
                text = displayEmoji,
                fontSize = 16.sp,
                fontFamily = rememberEmojiFontFamily(),
            )
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = NostrordColors.TextSecondary,
            )
        }
    }
}

/**
 * Individual reaction badge showing emoji (or custom emoji image), reactor avatars, and count.
 */
@Composable
private fun ReactionBadge(
    emoji: String,
    emojiUrl: String?,
    reactors: List<String>,
    count: Int,
    isUserReacted: Boolean,
    resolveMetadata: (String) -> UserMetadata?,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val shape = RoundedCornerShape(8.dp)

    // Background and border colors based on state
    val backgroundColor =
        when {
            isUserReacted -> NostrordColors.PrimarySubtle
            isHovered -> NostrordColors.HoverBackground
            else -> NostrordColors.SurfaceVariant.copy(alpha = 0.6f)
        }

    val borderColor =
        when {
            isUserReacted -> NostrordColors.Primary
            else -> Color.Transparent
        }

    Box(
        modifier =
        Modifier
            .clip(shape)
            .background(backgroundColor)
            .then(
                if (isUserReacted) {
                    Modifier.border(1.dp, borderColor, shape)
                } else {
                    Modifier
                },
            ).hoverable(interactionSource)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            // Display the emoji - either as image (custom emoji) or text
            if (emojiUrl != null) {
                // Custom emoji - render as image
                CustomEmojiImage(
                    url = emojiUrl,
                    shortcode = emoji.trim(':'),
                    size = 18,
                )
            } else {
                // Standard emoji or special cases
                val displayEmoji =
                    when (emoji) {
                        "+" -> "\uD83D\uDC4D" // 👍
                        "-" -> "\uD83D\uDC4E" // 👎
                        else -> emoji
                    }

                val emojiFontFamily = rememberEmojiFontFamily()
                Text(
                    text = displayEmoji,
                    fontSize = 16.sp,
                    fontFamily = emojiFontFamily,
                )
            }

            // Reactor avatar stack (max 3, overlapping)
            ReactorAvatarStack(
                reactors = reactors,
                resolveMetadata = resolveMetadata,
            )

            // Count (only show if more reactors than visible avatars)
            if (count > MAX_VISIBLE_AVATARS) {
                Text(
                    text = "+${count - MAX_VISIBLE_AVATARS}",
                    color = if (isUserReacted) NostrordColors.Primary else NostrordColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = if (isUserReacted) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** Tooltip content: who reacted, capped so a popular badge doesn't build a huge string. */
private fun reactorNames(
    reactors: List<String>,
    resolveMetadata: (String) -> UserMetadata?,
): String {
    val names = reactors.take(MAX_TOOLTIP_NAMES).map { pk ->
        val meta = resolveMetadata(pk)
        meta?.displayName?.takeIf { it.isNotBlank() }
            ?: meta?.name?.takeIf { it.isNotBlank() }
            ?: shortNpub(pk)
    }
    val extra = reactors.size - MAX_TOOLTIP_NAMES
    return names.joinToString(", ") + if (extra > 0) " +$extra" else ""
}

private const val MAX_TOOLTIP_NAMES = 20
private const val MAX_VISIBLE_AVATARS = 3
private val AVATAR_SIZE = 16.dp
private val AVATAR_OVERLAP = 6.dp

/**
 * Overlapping stack of reactor avatars.
 * Uses AsyncImage directly (instead of OptimizedSmallAvatar) because
 * OptimizedSmallAvatar treats sizes < 24dp as TINY and skips image loading.
 */
@Composable
private fun ReactorAvatarStack(
    reactors: List<String>,
    resolveMetadata: (String) -> UserMetadata?,
) {
    val visible = reactors.take(MAX_VISIBLE_AVATARS)
    if (visible.isEmpty()) return

    val totalWidth = AVATAR_SIZE + (AVATAR_OVERLAP * (visible.size - 1).coerceAtLeast(0))
    val context = LocalPlatformContext.current

    Box(
        modifier =
        Modifier
            .width(totalWidth)
            .height(AVATAR_SIZE),
    ) {
        visible.forEachIndexed { index, pubkey ->
            val meta = resolveMetadata(pubkey)
            val pictureUrl = meta?.picture

            Box(
                modifier =
                Modifier
                    .offset(x = AVATAR_OVERLAP * index)
                    .size(AVATAR_SIZE)
                    .clip(CircleShape)
                    .border(1.dp, NostrordColors.SurfaceVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                var imageState by remember(pictureUrl) {
                    mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty)
                }
                val showFallback =
                    pictureUrl.isNullOrBlank() ||
                        imageState is AsyncImagePainter.State.Loading ||
                        imageState is AsyncImagePainter.State.Error

                if (showFallback) {
                    UserGradientAvatar(
                        seed = pubkey,
                        size = AVATAR_SIZE,
                    )
                }

                if (!pictureUrl.isNullOrBlank() && imageState !is AsyncImagePainter.State.Error) {
                    AsyncImage(
                        model =
                        ImageRequest
                            .Builder(context)
                            .data(pictureUrl)
                            .crossfade(true)
                            .size(Size(64, 64))
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .build(),
                        contentDescription = null,
                        modifier =
                        Modifier
                            .size(AVATAR_SIZE)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop,
                        onState = { imageState = it },
                    )
                }
            }
        }
    }
}

/**
 * Renders a custom emoji as an image with fallback to shortcode text.
 */
@Composable
private fun CustomEmojiImage(
    url: String,
    shortcode: String,
    size: Int = 18,
) {
    var useProxy by remember(url) { mutableStateOf(false) }
    var showFallback by remember(url) { mutableStateOf(false) }

    val effectiveUrl =
        if (useProxy) {
            proxyViaWeserv(url, width = size * 2, height = size * 2)
        } else {
            url
        }

    if (showFallback) {
        Text(
            text = ":$shortcode:",
            fontSize = 12.sp,
            color = NostrordColors.TextSecondary,
        )
    } else {
        EmojiImage(
            url = effectiveUrl,
            contentDescription = ":$shortcode:",
            modifier = Modifier.size(size.dp),
            contentScale = ContentScale.Fit,
            onError = {
                if (!useProxy) {
                    useProxy = true
                } else {
                    showFallback = true
                }
            },
        )
    }
}
