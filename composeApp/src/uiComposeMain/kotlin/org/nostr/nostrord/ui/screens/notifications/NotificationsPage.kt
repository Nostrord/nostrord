package org.nostr.nostrord.ui.screens.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.notifications.NotificationEntry
import org.nostr.nostrord.ui.components.avatars.OptimizedSmallAvatar
import org.nostr.nostrord.ui.components.layout.PageHeader
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.navigation.notificationRoute
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.utils.formatTimestamp
import org.nostr.nostrord.utils.resolveGroupRef
import org.nostr.nostrord.utils.shortNpub

/**
 * New-design notifications page (prototype Notifications): a header (bell + title + unread
 * pill + "Mark all as read") over the filtered list. The type / group / unread-only filters
 * live in [NotificationsSidebar], sharing the same [vm]. Click a row to mark it read and open
 * its group.
 */
@Composable
fun NotificationsPage(
    vm: NotificationsViewModel,
    onOpenRoute: (GroupRoute) -> Unit,
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
) {
    val shown by vm.filtered.collectAsState()
    val unread by vm.unreadCount.collectAsState()
    val userMetadata by vm.userMetadata.collectAsState()
    val groupsByRelay by vm.groupsByRelay.collectAsState()
    val totalEntries by vm.entries.collectAsState()

    LaunchedEffect(shown.size) {
        vm.requestUserMetadata(shown.map { it.actorPubkey }.toSet())
    }

    Column(modifier = modifier.fillMaxSize().background(NostrordColors.Background)) {
        PageHeader(
            icon = Icons.Default.Notifications,
            title = "Notifications",
            onOpenDrawer = onOpenDrawer,
        ) {
            if (unread > 0) {
                Box(
                    modifier =
                    Modifier
                        .clip(NostrordShapes.shapeCircle)
                        .background(NostrordColors.Error)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text("$unread", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            val enabled = unread > 0
            Row(
                modifier =
                Modifier
                    .clip(NostrordShapes.shapeMedium)
                    .then(if (enabled) Modifier.clickable { vm.markAllRead() } else Modifier)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val tint = if (enabled) NostrordColors.TextSecondary else NostrordColors.TextMuted.copy(alpha = 0.4f)
                Icon(Icons.Default.Check, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                Text("Mark all as read", color = tint, fontSize = 13.sp)
            }
        }

        if (shown.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (totalEntries.isEmpty()) "No notifications" else "No notifications match this filter",
                    color = NostrordColors.TextMuted,
                    fontSize = 14.sp,
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 672.dp).fillMaxWidth().align(Alignment.TopCenter),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(shown, key = { it.id }) { entry ->
                        val meta = userMetadata[entry.actorPubkey]
                        val authorName =
                            meta?.displayName?.takeIf { it.isNotBlank() }
                                ?: meta?.name?.takeIf { it.isNotBlank() }
                                ?: shortNpub(entry.actorPubkey)
                        // The entry's own relay decides: NIP-29 group ids are relay-local, so a
                        // same-id group elsewhere must not name or paint this row.
                        val groupMeta = resolveGroupRef(groupsByRelay, entry.groupId, entry.relayUrl)
                        // Live metadata first: the snapshot taken at notification time can be
                        // the truncated id (metadata hadn't landed yet, e.g. a private group
                        // pre-AUTH) and would otherwise shadow the real name forever.
                        val groupName =
                            groupMeta?.name?.takeIf { it.isNotBlank() }
                                ?: entry.groupName?.takeIf { it.isNotBlank() }
                                ?: entry.groupId.take(8)
                        NotificationRow(
                            entry = entry,
                            authorName = authorName,
                            avatarUrl = meta?.picture,
                            groupName = groupName,
                            groupPicture = groupMeta?.picture,
                            onClick = {
                                vm.markRead(entry.id)
                                onOpenRoute(notificationRoute(entry.relayUrl, entry.groupId, entry.messageId, entry.threadRootId))
                            },
                        )
                    }
                }
            }
        }
    }
}

// Inline-content slot id for the tiny group avatar inside the row header text.
private const val GROUP_AVATAR_INLINE = "groupAvatar"

@Composable
private fun NotificationRow(
    entry: NotificationEntry,
    authorName: String,
    avatarUrl: String?,
    groupName: String,
    groupPicture: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(NostrordShapes.shapeMedium)
            .background(if (!entry.read) NostrordColors.Surface else Color.Transparent)
            .clickable(onClick = onClick)
            .alpha(if (entry.read) 0.6f else 1f)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Unread dot (brand), invisible placeholder when read keeps alignment.
        Box(
            modifier =
            Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(if (!entry.read) NostrordColors.Primary else Color.Transparent),
        )
        OptimizedSmallAvatar(
            imageUrl = avatarUrl,
            identifier = entry.actorPubkey,
            displayName = authorName,
            size = 40.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            val header =
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = NostrordColors.TextPrimary)) {
                        append(authorName)
                    }
                    append(' ')
                    withStyle(SpanStyle(color = NostrordColors.TextMuted)) {
                        append(actionLabel(entry))
                        append(" in ")
                    }
                    // The group identity: its standard rounded-square avatar + name.
                    appendInlineContent(GROUP_AVATAR_INLINE, "▢")
                    append(' ')
                    withStyle(SpanStyle(color = NostrordColors.TextLink, fontWeight = FontWeight.Medium)) {
                        append(groupName)
                    }
                }
            Text(
                text = header,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                inlineContent =
                mapOf(
                    GROUP_AVATAR_INLINE to
                        InlineTextContent(
                            Placeholder(16.sp, 16.sp, PlaceholderVerticalAlign.TextCenter),
                        ) {
                            OptimizedSmallAvatar(
                                imageUrl = groupPicture?.ifBlank { null },
                                identifier = entry.groupId,
                                displayName = groupName,
                                size = 16.dp,
                                shape = RoundedCornerShape(4.dp),
                                isGroup = true,
                            )
                        },
                ),
            )
            val preview = entry.preview.takeIf { it.isNotBlank() }
            if (preview != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    preview,
                    color = NostrordColors.TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            formatTimestamp(entry.createdAt),
            color = NostrordColors.TextMuted,
            fontSize = 11.sp,
        )
    }
}

private fun actionLabel(entry: NotificationEntry): String = notificationActionLabel(entry)
