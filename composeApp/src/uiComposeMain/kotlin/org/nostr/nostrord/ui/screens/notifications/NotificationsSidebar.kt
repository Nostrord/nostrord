package org.nostr.nostrord.ui.screens.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.ui.components.avatars.OptimizedSmallAvatar
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.utils.normalizeRelayUrl

/**
 * Notifications filter column (prototype NotificationsSidebar): the type tabs with counts,
 * an "Unread only" toggle and the per-group filter, all driving the shared [vm]. Shown in the
 * 240dp sidebar while the notifications page is open.
 */
@Composable
fun NotificationsSidebar(
    vm: NotificationsViewModel,
    modifier: Modifier = Modifier,
) {
    val typeFilter by vm.typeFilter.collectAsState()
    val unreadOnly by vm.unreadOnly.collectAsState()
    val groupFilter by vm.groupFilter.collectAsState()
    val counts by vm.typeCounts.collectAsState()
    val buckets by vm.groupBuckets.collectAsState()
    val groupsByRelay by vm.groupsByRelay.collectAsState()

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp),
    ) {
        HubTab(Icons.Default.Notifications, "All", counts.all, typeFilter == NotifFilter.ALL) {
            vm.setTypeFilter(NotifFilter.ALL)
        }
        HubTab(null, "Mentions", counts.mentions, typeFilter == NotifFilter.MENTIONS, glyph = "@") {
            vm.setTypeFilter(NotifFilter.MENTIONS)
        }
        HubTab(Icons.AutoMirrored.Filled.Reply, "Replies", counts.replies, typeFilter == NotifFilter.REPLIES) {
            vm.setTypeFilter(NotifFilter.REPLIES)
        }
        HubTab(Icons.Default.Forum, "Messages", counts.messages, typeFilter == NotifFilter.MESSAGES) {
            vm.setTypeFilter(NotifFilter.MESSAGES)
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Unread-only toggle row
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .clip(NostrordShapes.shapeMedium)
                .clickable { vm.setUnreadOnly(!unreadOnly) }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.Notifications,
                contentDescription = null,
                tint = NostrordColors.TextSecondary,
                modifier = Modifier.size(17.dp),
            )
            Text(
                "Unread only",
                color = NostrordColors.TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            PillToggle(on = unreadOnly)
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "GROUPS",
            color = NostrordColors.TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
        GroupTab(
            picture = null,
            identifier = "all",
            label = "All groups",
            unread = 0,
            active = groupFilter == null,
            icon = Icons.Default.People,
        ) { vm.setGroupFilter(null) }
        buckets.forEach { bucket ->
            // This relay's kind:39000 first: a same-id group elsewhere is a different group and
            // must not lend its avatar to this tab.
            val picture =
                groupsByRelay.entries.firstOrNull { it.key.normalizeRelayUrl() == bucket.relayUrl.normalizeRelayUrl() }
                    ?.value?.firstOrNull { it.id == bucket.groupId }?.picture
            GroupTab(
                picture = picture,
                identifier = bucket.key,
                label = bucket.name,
                unread = bucket.unread,
                active = groupFilter == bucket.key,
                icon = null,
            ) { vm.setGroupFilter(bucket.key) }
        }
    }
}

@Composable
private fun HubTab(
    icon: ImageVector?,
    label: String,
    count: Int,
    active: Boolean,
    glyph: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(NostrordShapes.shapeMedium)
            .background(if (active) NostrordColors.SurfaceVariant else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val contentColor = if (active) NostrordColors.TextPrimary else NostrordColors.TextSecondary
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when {
                glyph != null ->
                    Text(glyph, color = contentColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                icon != null ->
                    Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(17.dp))
            }
        }
        Text(
            label,
            color = contentColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (count > 0) CountBadge(count)
    }
}

@Composable
private fun GroupTab(
    picture: String?,
    identifier: String,
    label: String,
    unread: Int,
    active: Boolean,
    icon: ImageVector?,
    onClick: () -> Unit,
) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(NostrordShapes.shapeMedium)
            .background(if (active) NostrordColors.SurfaceVariant else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val contentColor = if (active) NostrordColors.TextPrimary else NostrordColors.TextSecondary
        if (icon != null) {
            Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
            }
        } else {
            OptimizedSmallAvatar(
                imageUrl = picture,
                identifier = identifier,
                displayName = label,
                size = 20.dp,
                shape = NostrordShapes.shapeSmall,
                isGroup = true,
            )
        }
        Text(
            label,
            color = contentColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (unread > 0) CountBadge(unread)
    }
}

@Composable
private fun CountBadge(count: Int) {
    Box(
        modifier =
        Modifier
            .clip(NostrordShapes.shapeCircle)
            .background(NostrordColors.Error)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(if (count > 99) "99+" else "$count", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/** Prototype's pill switch: 32x18 track, 14 thumb, brand when on. */
@Composable
private fun PillToggle(on: Boolean) {
    Box(
        modifier =
        Modifier
            .width(32.dp)
            .height(18.dp)
            .clip(CircleShape)
            .background(if (on) NostrordColors.Primary else NostrordColors.SurfaceVariant),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier =
            Modifier
                .padding(horizontal = 2.dp)
                .size(14.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}
