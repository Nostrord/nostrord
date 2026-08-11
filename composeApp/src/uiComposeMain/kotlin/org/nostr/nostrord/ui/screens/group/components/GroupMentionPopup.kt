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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.nostr.nostrord.ui.mentions.MentionAutocomplete
import org.nostr.nostrord.ui.screens.group.model.GroupInfo
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.ui.util.generateColorFromString

/** Groups offered for `%query`; the count also drives keyboard navigation in the composers. */
fun getFilteredGroups(
    groups: List<GroupInfo>,
    query: String,
): List<GroupInfo> = MentionAutocomplete.filter(groups, query) { listOf(it.name, it.id) }

@Composable
fun GroupMentionPopup(
    groups: List<GroupInfo>,
    query: String,
    selectedIndex: Int = 0,
    onGroupSelect: (GroupInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filteredGroups = getFilteredGroups(groups, query)

    if (filteredGroups.isEmpty()) return

    val safeSelectedIndex = selectedIndex.coerceIn(0, filteredGroups.size - 1)

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
            Box(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.inputPadding, vertical = Spacing.sm),
            ) {
                Text(
                    text = "GROUPS",
                    style = NostrordTypography.SectionHeader,
                    color = NostrordColors.TextMuted,
                )
            }

            HorizontalDivider(
                color = NostrordColors.BackgroundDark,
                thickness = Spacing.dividerThickness,
            )

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                itemsIndexed(filteredGroups) { index, group ->
                    GroupMentionItem(
                        group = group,
                        isSelected = index == safeSelectedIndex,
                        onClick = { onGroupSelect(group) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupMentionItem(
    group: GroupInfo,
    isSelected: Boolean = false,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val backgroundColor =
        when {
            isPressed -> NostrordColors.SurfaceVariant
            isSelected -> NostrordColors.Primary.copy(alpha = 0.2f)
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
        GroupIcon(
            groupId = group.id,
            name = group.name,
            pictureUrl = group.picture,
            size = Spacing.avatarSizeSmall,
        )

        Spacer(modifier = Modifier.width(Spacing.inputPadding))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.name,
                style = NostrordTypography.MemberName,
                color = NostrordColors.TextContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text =
                group.relay
                    .removePrefix("wss://")
                    .removePrefix("ws://"),
                style = NostrordTypography.Tiny,
                color = NostrordColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GroupIcon(
    groupId: String,
    name: String,
    pictureUrl: String?,
    size: Dp,
) {
    val context = LocalPlatformContext.current
    val shape = RoundedCornerShape(8.dp)
    var imageState by remember(pictureUrl) {
        mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty)
    }
    val showImage = !pictureUrl.isNullOrBlank() && imageState !is AsyncImagePainter.State.Error

    Box(
        modifier =
        Modifier
            .size(size)
            .clip(shape)
            .background(if (!showImage) generateColorFromString(groupId) else NostrordColors.BackgroundDark),
        contentAlignment = Alignment.Center,
    ) {
        if (!showImage) {
            Text(
                text = name.take(1).uppercase(),
                color = Color.White,
                fontSize = (size.value * 0.5f).sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (!pictureUrl.isNullOrBlank()) {
            AsyncImage(
                model =
                ImageRequest
                    .Builder(context)
                    .data(pictureUrl)
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = name,
                modifier =
                Modifier
                    .fillMaxSize()
                    .clip(shape),
                contentScale = ContentScale.Crop,
                onState = { imageState = it },
            )
        }
    }
}
