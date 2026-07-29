package org.nostr.nostrord.ui.screens.relay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.ui.components.buttons.AppButton
import org.nostr.nostrord.ui.components.buttons.AppButtonVariant
import org.nostr.nostrord.ui.components.home.GroupCard
import org.nostr.nostrord.ui.components.layout.PageHeader
import org.nostr.nostrord.ui.screens.home.HomePageViewModel
import org.nostr.nostrord.ui.screens.home.RelayHeaderIcon
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.utils.isJoinedOn
import org.nostr.nostrord.utils.normalizeRelayUrl

/**
 * Relay page (native counterpart of the web RelayScreen / prototype /r/:relayId): the relay's
 * NIP-11 header (icon, name, status, url, description, capability chips, add-to-list) plus a meta
 * grid (software / NIPs / operator), over the groups on it that you or people you follow are in,
 * shown with the same discovery [GroupCard]. Reached from the relay line on a group card.
 */
@Composable
fun RelayPageScreen(
    relayUrl: String,
    onOpenGroup: (relayUrl: String, groupId: String) -> Unit,
    // On compact layouts the rail + sidebar collapse into a drawer; this opens it. On
    // desktop it's null, so the hamburger hides and only the relay icon + name show.
    onOpenDrawer: (() -> Unit)? = null,
) {
    val repo = AppModule.nostrRepository
    val vm = viewModel { HomePageViewModel(repo) }
    val myGroups by vm.myGroups.collectAsState()
    val friendsGroups by vm.friendsGroups.collectAsState()
    val relayMetadata by vm.relayMetadata.collectAsState()
    val userMetadata by repo.userMetadata.collectAsState()
    val connState by repo.connectionState.collectAsState()
    val currentRelay by repo.currentRelayUrl.collectAsState()
    val myRelays by repo.kind10009Relays.collectAsState()
    val unreachable by repo.unreachableRelays.collectAsState()
    val joinedByRelay by repo.joinedGroupsByRelay.collectAsState()
    val scope = rememberCoroutineScope()

    val target = relayUrl.normalizeRelayUrl()
    val info = relayMetadata[relayUrl] ?: relayMetadata[target]
    val url = relayUrl.trimEnd('/')
    val host = url.removePrefix("wss://").removePrefix("ws://")
    val title = info?.name?.takeIf { it.isNotBlank() } ?: host
    val nips = info?.supportedNips.orEmpty()
    val inList = target in myRelays
    val isCurrent = currentRelay.normalizeRelayUrl() == target
    val isUnreachable = target in unreachable || relayUrl in unreachable
    val isConnecting = isCurrent &&
        (
            connState is ConnectionManager.ConnectionState.Connecting ||
                connState is ConnectionManager.ConnectionState.Reconnecting
            )
    val statusLabel = when {
        isUnreachable -> "Offline"
        isConnecting -> "Connecting…"
        else -> "Connected"
    }
    val statusColor = when {
        isUnreachable -> NostrordColors.TextMuted
        isConnecting -> NostrordColors.Warning
        else -> NostrordColors.Success
    }

    val operatorPk = info?.pubkey
    LaunchedEffect(operatorPk) {
        if (operatorPk != null) repo.requestUserMetadata(setOf(operatorPk))
    }
    val operatorName = operatorPk?.let { pk ->
        val m = userMetadata[pk]
        m?.displayName?.takeIf { it.isNotBlank() }
            ?: m?.name?.takeIf { it.isNotBlank() }
            ?: (pk.take(8) + "…")
    } ?: "Unknown"
    val software = info?.software?.let { it.substringAfterLast('/').ifBlank { it } } ?: "n/a"

    // My groups + friends' groups that live on this relay, de-duped, root-level only.
    val groups = (myGroups + friendsGroups)
        .filter { it.relayUrl.normalizeRelayUrl() == target && it.meta.parent == null }
        .distinctBy { it.meta.id }

    Column(modifier = Modifier.fillMaxSize().background(NostrordColors.Background)) {
        PageHeader(
            icon = Icons.Default.Public,
            title = title,
            onOpenDrawer = onOpenDrawer,
        )

        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Match the web .card-grid breakpoints (1 / 2 / 3 columns) off the content width.
            val columns =
                when {
                    maxWidth > 1024.dp -> 3
                    maxWidth > 640.dp -> 2
                    else -> 1
                }
            // On a narrow column the add-to-list button stacks below the relay header
            // (it would otherwise squeeze the name/url), and the meta grid drops to 2 cells.
            val compact = maxWidth < 560.dp
            val metaColumns = if (maxWidth >= 720.dp) 3 else 2
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Fixed centered content column (web .home-content: max-width 1024, padding 24).
                Column(
                    modifier = Modifier.widthIn(max = 1024.dp).fillMaxWidth().padding(24.dp),
                ) {
                    // Relay info card.
                    val addButton: @Composable (Modifier) -> Unit = { btnModifier ->
                        AppButton(
                            text = if (inList) "In your list" else "Add to list",
                            icon = if (inList) Icons.Default.Check else Icons.Default.Add,
                            variant = if (inList) AppButtonVariant.Secondary else AppButtonVariant.Primary,
                            modifier = btnModifier,
                            fullWidth = compact,
                            onClick = {
                                scope.launch {
                                    if (inList) repo.removeRelay(relayUrl) else repo.addRelay(relayUrl)
                                }
                            },
                        )
                    }
                    Surface(shape = NostrordShapes.shapeLarge, color = NostrordColors.Surface) {
                        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                RelayHeaderIcon(relayUrl = relayUrl, iconUrl = info?.icon, label = title, size = 56.dp)
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            title,
                                            color = NostrordColors.TextPrimary,
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        StatusPill(statusLabel, statusColor)
                                    }
                                    Text(
                                        url,
                                        color = NostrordColors.TextMuted,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    info?.description?.takeIf { it.isNotBlank() }?.let {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(it, color = NostrordColors.TextSecondary, fontSize = 14.sp)
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        if (29 in nips) CapChip("NIP-29")
                                        if (info?.supportsSubgroups == true) CapChip("Channels")
                                        if (42 in nips) CapChip("NIP-42")
                                        if (inList) ListTag()
                                    }
                                }
                                if (!compact) {
                                    Spacer(modifier = Modifier.width(12.dp))
                                    addButton(Modifier)
                                }
                            }
                            if (compact) {
                                Spacer(modifier = Modifier.height(16.dp))
                                addButton(Modifier.fillMaxWidth())
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    val metaItems =
                        listOf(
                            "Software" to software,
                            "NIPs" to (if (nips.isEmpty()) "n/a" else nips.joinToString(", ")),
                            "Operator" to operatorName,
                        )
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        metaItems.chunked(metaColumns).forEach { rowItems ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                rowItems.forEach { (label, value) ->
                                    MetaCell(label, value, Modifier.weight(1f))
                                }
                                repeat(metaColumns - rowItems.size) { Spacer(modifier = Modifier.weight(1f)) }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))
                    Text(
                        "GROUPS YOUR NETWORK IS IN · ${groups.size}",
                        color = NostrordColors.TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (groups.isEmpty()) {
                        Text(
                            "No groups you or your friends are in on this relay.",
                            color = NostrordColors.TextMuted,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            groups.chunked(columns).forEach { row ->
                                Row(
                                    modifier = Modifier.height(IntrinsicSize.Max),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    row.forEach { dg ->
                                        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                            GroupCard(
                                                name = dg.meta.name ?: dg.meta.id,
                                                description = dg.meta.about,
                                                picture = dg.meta.picture,
                                                groupId = dg.meta.id,
                                                memberCount = dg.memberCount,
                                                restricted = dg.meta.isRestricted,
                                                people = dg.people,
                                                peopleLoading = dg.peopleLoading,
                                                isPublic = dg.meta.isPublic,
                                                isOpen = dg.meta.isOpen,
                                                hasMetadata = dg.hasMetadata,
                                                relayUrl = dg.relayUrl,
                                                relayIconUrl = info?.icon,
                                                isJoined = joinedByRelay.isJoinedOn(dg.relayUrl, dg.meta.id),
                                                onClick = { onOpenGroup(dg.relayUrl, dg.meta.id) },
                                            )
                                        }
                                    }
                                    // Pad the last row so cards keep their column width instead of stretching.
                                    repeat(columns - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, color: Color) {
    Surface(shape = RoundedCornerShape(percent = 50), color = NostrordColors.SurfaceVariant) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
            Text(label, color = NostrordColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CapChip(text: String) {
    Surface(shape = NostrordShapes.shapeSmall, color = NostrordColors.Success.copy(alpha = 0.15f)) {
        Text(
            text,
            color = NostrordColors.Success,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ListTag() {
    Surface(shape = NostrordShapes.shapeSmall, color = NostrordColors.SurfaceVariant) {
        Text(
            "In your list",
            color = NostrordColors.TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun MetaCell(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = NostrordShapes.shapeMedium, color = NostrordColors.Surface) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(label, color = NostrordColors.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                value,
                color = NostrordColors.TextPrimary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
