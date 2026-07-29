package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.launch
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.components.ConfirmDialog
import org.nostr.nostrord.ui.components.IdentifierRow
import org.nostr.nostrord.ui.components.avatars.OptimizedSmallAvatar
import org.nostr.nostrord.ui.components.avatars.UserGradientAvatar
import org.nostr.nostrord.ui.components.forms.AppField
import org.nostr.nostrord.ui.components.forms.AppSearchField
import org.nostr.nostrord.ui.components.forms.AppSegmentedTabs
import org.nostr.nostrord.ui.components.forms.InputSize
import org.nostr.nostrord.ui.components.forms.SegmentedTab
import org.nostr.nostrord.ui.groupIdentifiers
import org.nostr.nostrord.ui.screens.group.GroupAccessCopy
import org.nostr.nostrord.ui.screens.group.GroupViewModel
import org.nostr.nostrord.ui.screens.group.HierarchyPrompt
import org.nostr.nostrord.ui.screens.group.addChannelPrompt
import org.nostr.nostrord.ui.screens.group.detachChannelPrompt
import org.nostr.nostrord.ui.screens.group.hierarchyView
import org.nostr.nostrord.ui.screens.group.makeRootPrompt
import org.nostr.nostrord.ui.screens.group.model.MemberInfo
import org.nostr.nostrord.ui.screens.group.moveUnderPrompt
import org.nostr.nostrord.ui.screens.group.movedChildOrder
import org.nostr.nostrord.ui.screens.group.pendingJoinRequests
import org.nostr.nostrord.ui.screens.group.reparentGroup
import org.nostr.nostrord.ui.screens.home.RelayHeaderIcon
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.normalizeRelayUrl
import org.nostr.nostrord.utils.rememberClipboardWriter
import org.nostr.nostrord.utils.shortNpub

/**
 * Tabs of the unified admin "Manage group" modal. Mirrors the web web/modals/ManageGroupModal
 * ManageTab: Info / Members / Invites / Requests / Hierarchy / Danger. Hierarchy is only shown
 * where the relay advertises NIP-29 subgroup support.
 */
enum class ManageTab(val label: String, val icon: ImageVector) {
    Info("Info", Icons.Default.Settings),
    Members("Members", Icons.Default.People),
    Invites("Invites", Icons.Default.Link),
    Requests("Requests", Icons.Default.Shield),
    Hierarchy("Hierarchy", Icons.Default.AccountTree),
    Danger("Danger", Icons.Default.Warning),
}

/**
 * Unified admin "Manage group" modal (native port of web web/modals/ManageGroupModal): a left tab
 * nav over the group's real moderation features (Info / Members / Invites / Requests / Hierarchy /
 * Danger). The card has a fixed height so switching tabs never resizes it; the content column
 * scrolls internally instead (parity with the web .manage-modal). All actions go through the
 * shared GroupViewModel so native and web can't drift.
 */
@Composable
fun ManageGroupModal(
    groupId: String,
    currentMetadata: GroupMetadata?,
    relayUrl: String,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
    initialTab: ManageTab = ManageTab.Info,
    supportsSubgroups: Boolean = true,
    /** Navigate to a group/channel (Hierarchy rows); the caller closes the modal. */
    onOpenGroup: ((groupId: String) -> Unit)? = null,
) {
    val vm = viewModel(key = "$relayUrl|$groupId") { GroupViewModel(AppModule.nostrRepository, groupId, relayUrl) }
    var tab by remember { mutableStateOf(initialTab) }

    // Hierarchy only makes sense where the relay hosts subgroups; hide that tab otherwise.
    val visibleTabs = remember(supportsSubgroups) {
        ManageTab.entries.filter { it != ManageTab.Hierarchy || supportsSubgroups }
    }

    // Refresh metadata when the modal opens so the Info fields show current relay state.
    LaunchedEffect(groupId) { AppModule.nostrRepository.refreshGroupMetadata(groupId) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() }
                .safeDrawingPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier =
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth(0.95f)
                    // Fixed height (not content-driven) so the card never resizes when the tab
                    // changes; min(90% of the window, 720dp) mirrors the web height:90vh/max:720px.
                    .fillMaxHeight(0.9f)
                    .heightIn(max = 720.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { /* consume click */ },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface),
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(Spacing.xxl)) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (currentMetadata?.parent != null) "Manage channel" else "Manage group",
                            style = NostrordTypography.ServerHeader,
                            color = NostrordColors.TextPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                        IconButton(
                            onClick = onDismiss,
                            modifier =
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = NostrordColors.TextSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.lg))

                    BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        // Narrow widths put the nav in a horizontal scroll row on top (web's
                        // <600px .manage-layout); wider widths use the left rail.
                        val wide = maxWidth >= 520.dp
                        if (wide) {
                            Row(modifier = Modifier.fillMaxSize()) {
                                ManageNav(
                                    tabs = visibleTabs,
                                    selected = tab,
                                    vertical = true,
                                    onSelect = { tab = it },
                                    modifier = Modifier.width(150.dp),
                                )
                                Spacer(modifier = Modifier.width(Spacing.lg))
                                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    ManageTabContent(tab, vm, groupId, currentMetadata, relayUrl, onDeleted, onOpenGroup)
                                }
                            }
                        } else {
                            Column(modifier = Modifier.fillMaxSize()) {
                                ManageNav(
                                    tabs = visibleTabs,
                                    selected = tab,
                                    vertical = false,
                                    onSelect = { tab = it },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(modifier = Modifier.height(Spacing.md))
                                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                    ManageTabContent(tab, vm, groupId, currentMetadata, relayUrl, onDeleted, onOpenGroup)
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
private fun ManageTabContent(
    tab: ManageTab,
    vm: GroupViewModel,
    groupId: String,
    currentMetadata: GroupMetadata?,
    relayUrl: String,
    onDeleted: () -> Unit,
    onOpenGroup: ((groupId: String) -> Unit)?,
) {
    when (tab) {
        ManageTab.Info -> ManageInfoSection(groupId, currentMetadata, relayUrl)
        ManageTab.Members -> ManageMembersSection(vm, groupId)
        ManageTab.Invites -> ManageInvitesSection(vm, groupId, relayUrl)
        ManageTab.Requests -> ManageRequestsSection(vm, groupId, isOpen = currentMetadata?.isOpen != false)
        ManageTab.Hierarchy -> ManageHierarchySection(vm, groupId, currentMetadata, relayUrl, onOpenGroup)
        ManageTab.Danger -> ManageDangerSection(groupId, isChannel = currentMetadata?.parent != null, onDeleted = onDeleted)
    }
}

@Composable
private fun ManageNav(
    tabs: List<ManageTab>,
    selected: ManageTab,
    vertical: Boolean,
    onSelect: (ManageTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (vertical) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            // Vertical rail: each item fills the rail width so the active/hover background spans
            // the full column (matches the web .manage-nav-item).
            tabs.forEach { ManageNavItem(it, it == selected, fillWidth = true) { onSelect(it) } }
        }
    } else {
        Row(
            modifier = modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            tabs.forEach { ManageNavItem(it, it == selected) { onSelect(it) } }
        }
    }
}

@Composable
private fun ManageNavItem(
    tab: ManageTab,
    selected: Boolean,
    fillWidth: Boolean = false,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Row(
        modifier =
        Modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    selected -> NostrordColors.SurfaceVariant
                    hovered -> NostrordColors.HoverBackground
                    else -> Color.Transparent
                },
            )
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = null,
            tint = if (selected || hovered) NostrordColors.TextPrimary else NostrordColors.TextMuted,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = tab.label,
            style = NostrordTypography.Caption,
            color = if (selected || hovered) NostrordColors.TextPrimary else NostrordColors.TextMuted,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

// ---- Info ----

@Composable
private fun ManageInfoSection(
    groupId: String,
    currentMetadata: GroupMetadata?,
    relayUrl: String,
) {
    val scope = rememberCoroutineScope()
    var name by remember(currentMetadata) { mutableStateOf(currentMetadata?.name ?: "") }
    var about by remember(currentMetadata) { mutableStateOf(currentMetadata?.about ?: "") }
    var picture by remember(currentMetadata) { mutableStateOf(currentMetadata?.picture ?: "") }
    var isPrivate by remember(currentMetadata) { mutableStateOf(currentMetadata?.isPublic == false) }
    var isClosed by remember(currentMetadata) { mutableStateOf(currentMetadata?.isOpen == false) }
    var isRestricted by remember(currentMetadata) { mutableStateOf(currentMetadata?.isRestricted == true) }
    var isHidden by remember(currentMetadata) { mutableStateOf(currentMetadata?.isHidden == true) }
    var hasLiveKit by remember(currentMetadata) { mutableStateOf(currentMetadata?.hasLiveKit == true) }
    // The relay mints the LiveKit token, so the toggle only appears where the relay advertises
    // support. A group already carrying the tag keeps it visible, so it can still be switched off.
    var avSupported by remember(currentMetadata) { mutableStateOf(currentMetadata?.hasLiveKit == true) }
    LaunchedEffect(groupId) {
        if (AppModule.nostrRepository.relaySupportsAv(groupId)) avSupported = true
    }
    var isSaving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val relayMetadata by AppModule.nostrRepository.relayMetadata.collectAsState()
    val relayHost = relayUrl.removePrefix("wss://").removePrefix("ws://").trimEnd('/')
    val relayIconUrl = (relayMetadata[relayUrl] ?: relayMetadata[relayUrl.normalizeRelayUrl()])?.icon

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        GroupAvatarUploadRow(
            pictureUrl = picture,
            seed = groupId,
            name = name,
            onPictureChange = { picture = it },
            onError = { error = it },
        )

        Spacer(modifier = Modifier.height(Spacing.lg))

        EditFieldLabel("Name")
        Spacer(modifier = Modifier.height(Spacing.xs))
        AppField(
            value = name,
            onValueChange = {
                name = it
                error = null
            },
            placeholder = "#example",
            modifier = Modifier.fillMaxWidth(),
        )

        if (relayHost.isNotBlank()) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                RelayHeaderIcon(
                    relayUrl = relayUrl,
                    iconUrl = relayIconUrl,
                    label = relayHost,
                    size = 14.dp,
                    cornerRadius = 3.dp,
                )
                Text(relayHost, color = NostrordColors.TextSecondary, style = NostrordTypography.Caption)
            }
        }

        Spacer(modifier = Modifier.height(Spacing.lg))

        EditFieldLabel("Description")
        Spacer(modifier = Modifier.height(Spacing.xs))
        AppField(
            value = about,
            onValueChange = { about = it },
            placeholder = "What is this group about?",
            singleLine = false,
            minLines = 3,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(Spacing.xxl))

        Text("ACCESS", style = NostrordTypography.SectionHeader, color = NostrordColors.TextMuted)
        Spacer(modifier = Modifier.height(Spacing.sm))
        EditAccessToggleRow(Icons.Default.Lock, GroupAccessCopy.PRIVATE_LABEL, GroupAccessCopy.PRIVATE_DESC, isPrivate) { isPrivate = it }
        Spacer(modifier = Modifier.height(Spacing.xs))
        EditAccessToggleRow(Icons.Default.Block, GroupAccessCopy.CLOSED_LABEL, GroupAccessCopy.CLOSED_DESC, isClosed) { isClosed = it }
        Spacer(modifier = Modifier.height(Spacing.xs))
        EditAccessToggleRow(Icons.AutoMirrored.Filled.Send, GroupAccessCopy.RESTRICTED_LABEL, GroupAccessCopy.RESTRICTED_DESC, isRestricted) { isRestricted = it }
        Spacer(modifier = Modifier.height(Spacing.xs))
        EditAccessToggleRow(Icons.Default.VisibilityOff, GroupAccessCopy.HIDDEN_LABEL, GroupAccessCopy.HIDDEN_DESC, isHidden) { isHidden = it }

        Spacer(modifier = Modifier.height(Spacing.xxl))

        Text("LIVE ROOM", style = NostrordTypography.SectionHeader, color = NostrordColors.TextMuted)
        Spacer(modifier = Modifier.height(Spacing.sm))
        if (avSupported) {
            EditAccessToggleRow(Icons.Default.Mic, GroupAccessCopy.LIVEKIT_LABEL, GroupAccessCopy.LIVEKIT_DESC, hasLiveKit) { hasLiveKit = it }
        } else {
            Text(
                GroupAccessCopy.LIVEKIT_UNSUPPORTED,
                style = NostrordTypography.Caption,
                color = NostrordColors.TextMuted,
            )
        }

        if (error != null) {
            Spacer(modifier = Modifier.height(Spacing.md))
            Text(error!!, style = NostrordTypography.Caption, color = NostrordColors.Error)
        }

        Spacer(modifier = Modifier.height(Spacing.xxl))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = {
                    if (name.isBlank()) {
                        error = "Group name is required."
                        return@Button
                    }
                    isSaving = true
                    scope.launch {
                        val result =
                            AppModule.nostrRepository.editGroup(
                                groupId = groupId,
                                name = name.trim(),
                                about = about.trim().ifBlank { null },
                                isPrivate = isPrivate,
                                isClosed = isClosed,
                                isRestricted = isRestricted,
                                isHidden = isHidden,
                                picture = picture.trim().ifBlank { null },
                                hasLiveKit = hasLiveKit,
                            )
                        isSaving = false
                        if (result is Result.Error) {
                            error = result.error.cause?.message ?: result.error.message ?: "Failed to update group."
                        }
                    }
                },
                enabled = !isSaving && name.isNotBlank(),
                colors = primaryButtonColors(),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(Spacing.sm))
                }
                Text(if (isSaving) "Saving…" else "Save Changes", style = NostrordTypography.Button)
            }
        }
    }
}

// ---- Members ----

@Composable
private fun ManageMembersSection(
    vm: GroupViewModel,
    groupId: String,
) {
    val members by vm.groupMembers.collectAsState()
    val admins by vm.groupAdmins.collectAsState()
    val userMetadata by vm.userMetadata.collectAsState()
    val myPubkey = remember { vm.getPublicKey() }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    // (member, action) whose promote/demote/remove is awaiting confirmation.
    var confirmAction by remember { mutableStateOf<Pair<MemberInfo, String>?>(null) }
    // Gate Confirm while a kind:9000/9001 awaits its OK so a slow relay can't
    // collect a second, duplicate action.
    val moderationBusy by vm.moderationBusy.collectAsState()

    val adminSet = admins[groupId].orEmpty().toSet()
    val memberInfos =
        members[groupId].orEmpty().map { pubkey ->
            val meta = userMetadata[pubkey]
            MemberInfo(
                pubkey = pubkey,
                displayName =
                meta?.displayName?.takeIf { it.isNotBlank() }
                    ?: meta?.name?.takeIf { it.isNotBlank() }
                    ?: shortNpub(pubkey),
                picture = meta?.picture,
                isAdmin = pubkey in adminSet,
            )
        }.sortedWith(compareByDescending<MemberInfo> { it.isAdmin }.thenBy { it.displayName.lowercase() })

    val adminCount = memberInfos.count { it.isAdmin }
    val regularCount = memberInfos.size - adminCount

    val filtered =
        memberInfos
            .filter {
                when (filter) {
                    "Admins" -> it.isAdmin
                    "Members" -> !it.isAdmin
                    else -> true
                }
            }
            .filter {
                query.isBlank() ||
                    it.displayName.contains(query, ignoreCase = true) ||
                    it.pubkey.contains(query, ignoreCase = true) ||
                    Nip19.encodeNpub(it.pubkey).contains(query, ignoreCase = true)
            }

    Column(modifier = Modifier.fillMaxSize()) {
        AppSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search members...",
            modifier = Modifier.fillMaxWidth(),
            size = InputSize.Compact,
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        // Per-category counts ride on the filter tabs (All / Admins / Members) instead of a
        // separate stats row.
        val filterKeys = listOf("All", "Admins", "Members")
        val filterCounts = listOf(memberInfos.size, adminCount, regularCount)
        AppSegmentedTabs(
            tabs = filterKeys.mapIndexed { i, key -> SegmentedTab("$key · ${filterCounts[i]}") },
            selectedIndex = filterKeys.indexOf(filter).coerceAtLeast(0),
            onSelect = { filter = filterKeys[it] },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (filtered.isEmpty()) {
                item { ModEmpty(if (query.isNotBlank()) "No members found" else "No members") }
            }
            items(filtered, key = { it.pubkey }) { member ->
                AdminMemberItem(
                    member = member,
                    isSelf = member.pubkey == myPubkey,
                    onPromote = { confirmAction = member to "promote" },
                    onDemote = { confirmAction = member to "demote" },
                    onRemove = { confirmAction = member to "remove" },
                )
            }
        }
    }

    confirmAction?.let { (member, action) ->
        val title =
            when (action) {
                "promote" -> "Promote to Admin"
                "demote" -> "Remove Admin Role"
                else -> "Remove from Group"
            }
        val desc =
            when (action) {
                "promote" -> "${member.displayName} will be able to manage members and group settings."
                "demote" -> "${member.displayName} will lose admin privileges."
                else -> "${member.displayName} will be removed from the group."
            }
        ConfirmDialog(
            title = title,
            message = desc,
            destructive = action == "remove",
            confirmEnabled = !moderationBusy,
            onConfirm = {
                when (action) {
                    "promote" -> vm.promoteToAdmin(member.pubkey)
                    "demote" -> vm.demoteFromAdmin(member.pubkey)
                    "remove" -> vm.removeUser(member.pubkey)
                }
                confirmAction = null
            },
            onDismiss = { confirmAction = null },
        )
    }
}

@Composable
private fun AdminMemberItem(
    member: MemberInfo,
    isSelf: Boolean,
    onPromote: () -> Unit,
    onDemote: () -> Unit,
    onRemove: () -> Unit,
) {
    var showRoleMenu by remember { mutableStateOf(false) }
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(NostrordColors.SurfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AdminMemberAvatar(member = member, size = 36.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = member.displayName,
                    color = NostrordColors.TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isSelf) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "YOU",
                        color = NostrordColors.TextMuted,
                        style = MaterialTheme.typography.labelSmall,
                        modifier =
                        Modifier
                            .background(NostrordColors.TextMuted.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                text = Nip19.encodeNpub(member.pubkey).take(20) + "...",
                color = NostrordColors.TextMuted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
        if (member.isAdmin) {
            Text(
                text = "ADMIN",
                color = NostrordColors.Primary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier =
                Modifier
                    .background(NostrordColors.Primary.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        // No self-demote / self-remove: a NIP-29 relay only accepts moderation from an admin, so
        // demoting yourself is one-way and could lock you out. Use Leave group instead.
        if (!isSelf) {
            Box {
                IconButton(
                    onClick = { showRoleMenu = true },
                    modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Member actions",
                        tint = NostrordColors.TextMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(
                    expanded = showRoleMenu,
                    onDismissRequest = { showRoleMenu = false },
                    containerColor = NostrordColors.Surface,
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (member.isAdmin) "Remove Admin Role" else "Promote to Admin",
                                color = NostrordColors.TextPrimary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Shield,
                                contentDescription = null,
                                tint = if (member.isAdmin) NostrordColors.Warning else NostrordColors.Primary,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = {
                            showRoleMenu = false
                            if (member.isAdmin) onDemote() else onPromote()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text("Remove from Group", color = NostrordColors.Error, style = MaterialTheme.typography.bodySmall)
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Close, contentDescription = null, tint = NostrordColors.Error, modifier = Modifier.size(18.dp))
                        },
                        onClick = {
                            showRoleMenu = false
                            onRemove()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AdminMemberAvatar(
    member: MemberInfo,
    size: androidx.compose.ui.unit.Dp,
) {
    val context = LocalPlatformContext.current
    Box(modifier = Modifier.size(size).clip(CircleShape), contentAlignment = Alignment.Center) {
        var imageState by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
        val showPlaceholder =
            member.picture.isNullOrBlank() ||
                imageState is AsyncImagePainter.State.Loading ||
                imageState is AsyncImagePainter.State.Error
        if (showPlaceholder) {
            UserGradientAvatar(seed = member.pubkey, size = size)
        }
        if (!member.picture.isNullOrBlank() && imageState !is AsyncImagePainter.State.Error) {
            AsyncImage(
                model =
                ImageRequest
                    .Builder(context)
                    .data(member.picture!!)
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = member.displayName,
                modifier = Modifier.size(size).clip(CircleShape),
                contentScale = ContentScale.Crop,
                onState = { imageState = it },
            )
        }
    }
}

// ---- Invites ----

@Composable
private fun ManageInvitesSection(
    vm: GroupViewModel,
    groupId: String,
    relayUrl: String,
) {
    val copyToClipboard = rememberClipboardWriter()
    val messages by vm.messages.collectAsState()
    val relayMetadata by vm.relayMetadata.collectAsState()
    // createInviteCode reports failure (e.g. relay rejects kind 9009) via the VM's moderation
    // error; reset the button and surface it when that arrives.
    val moderationError by vm.moderationError.collectAsState()
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(moderationError) { if (moderationError != null) busy = false }

    val msgs = messages[groupId].orEmpty()
    val revoked =
        msgs.filter { it.kind == 9005 }
            .flatMap { m -> m.tags.filter { it.firstOrNull() == "e" }.mapNotNull { it.getOrNull(1) } }
            .toSet()
    val codes =
        msgs.filter { it.kind == 9009 && it.id !in revoked }
            .mapNotNull { m ->
                val code = m.tags.firstOrNull { it.firstOrNull() == "code" }?.getOrNull(1) ?: return@mapNotNull null
                Triple(code, m.id, m.createdAt)
            }
            .sortedByDescending { it.third }
    val relayPubkey = relayMetadata[relayUrl]?.groupNaddrAuthor ?: relayMetadata[relayUrl.trimEnd('/')]?.groupNaddrAuthor

    Column(modifier = Modifier.fillMaxSize()) {
        Button(
            onClick = {
                busy = true
                vm.clearModerationError()
                vm.createInviteCode { busy = false }
            },
            enabled = !busy,
            colors = primaryButtonColors(),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand),
        ) {
            Text(if (busy) "Creating…" else "Create invite code", style = NostrordTypography.Button)
        }
        if (moderationError != null) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(moderationError!!, style = NostrordTypography.Caption, color = NostrordColors.Error)
        }
        Spacer(modifier = Modifier.height(Spacing.lg))
        Text("ACTIVE CODES (${codes.size})", style = NostrordTypography.SectionHeader, color = NostrordColors.TextMuted)
        Spacer(modifier = Modifier.height(Spacing.sm))
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            if (codes.isEmpty()) {
                item { ModEmpty("No active invite codes") }
            }
            items(codes, key = { it.second }) { (code, eventId, _) ->
                Column(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(NostrordColors.SurfaceVariant, RoundedCornerShape(8.dp))
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            code,
                            style = NostrordTypography.Caption,
                            color = NostrordColors.TextPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { copyToClipboard(code) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.ContentCopy, "Copy code", tint = NostrordColors.TextSecondary, modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = { vm.revokeInviteCode(eventId) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Delete, "Revoke", tint = NostrordColors.Error, modifier = Modifier.size(16.dp))
                        }
                    }
                    IdentifierRow(ids = groupIdentifiers(relayUrl, groupId, relayPubkey, code))
                }
            }
        }
    }
}

// ---- Requests ----

@Composable
private fun ManageRequestsSection(
    vm: GroupViewModel,
    groupId: String,
    isOpen: Boolean,
) {
    val messages by vm.messages.collectAsState()
    val members by vm.groupMembers.collectAsState()
    val userMetadata by vm.userMetadata.collectAsState()

    val msgs = messages[groupId].orEmpty()
    val memberSet = members[groupId].orEmpty().toSet()
    val pending = pendingJoinRequests(msgs, memberSet)

    fun nameOf(pubkey: String): String {
        val meta = userMetadata[pubkey]
        return meta?.displayName?.takeIf { it.isNotBlank() }
            ?: meta?.name?.takeIf { it.isNotBlank() }
            ?: shortNpub(pubkey)
    }

    if (pending.isEmpty()) {
        // Open groups auto-join, so an empty queue is the normal state; closed groups
        // just have nobody waiting.
        ModEmpty(
            if (isOpen) {
                "Open group: people join automatically, so there is no request queue."
            } else {
                "No pending requests"
            },
        )
        return
    }

    // Header: count + Accept all. Open groups still surface these because some relays
    // leave a kind:9021 pending when a member leaves and asks to rejoin; the admin lets
    // them back in here (open-group policy is to accept everyone).
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${if (isOpen) "Rejoining" else "Requests"} (${pending.size})",
                color = NostrordColors.TextSecondary,
                style = NostrordTypography.Caption,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { pending.forEach { vm.approveJoinRequest(it.pubkey) } }) {
                Text("Accept all", color = NostrordColors.Primary, style = NostrordTypography.Caption)
            }
        }
        if (isOpen) {
            Text(
                "These people left and asked to rejoin. Open groups accept everyone, so you can let them back in.",
                color = NostrordColors.TextMuted,
                style = NostrordTypography.Caption,
                modifier = Modifier.padding(horizontal = Spacing.md).padding(bottom = Spacing.sm),
            )
        }
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(pending, key = { it.id }) { req ->
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(NostrordColors.SurfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MemberAvatar(req.pubkey)
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(
                        nameOf(req.pubkey),
                        color = NostrordColors.TextPrimary,
                        style = NostrordTypography.Caption,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.approveJoinRequest(req.pubkey) }) {
                        Text("Approve", color = NostrordColors.Primary, style = NostrordTypography.Caption)
                    }
                    TextButton(onClick = { vm.rejectJoinRequest(req.id) }) {
                        Text("Reject", color = NostrordColors.Error, style = NostrordTypography.Caption)
                    }
                }
            }
        }
    }
}

// ---- Hierarchy ----

/** A hierarchy edit awaiting the user's confirmation. */
private data class PendingHierarchyOp(
    val prompt: HierarchyPrompt,
    val target: GroupMetadata,
    val op: GroupManager.ParentOp,
    val fail: String,
)

@Composable
private fun ManageHierarchySection(
    vm: GroupViewModel,
    groupId: String,
    currentMetadata: GroupMetadata?,
    relayUrl: String,
    onOpenGroup: ((groupId: String) -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    val groupsByRelay by vm.groupsByRelay.collectAsState()
    val childrenByParent by vm.childrenByParent.collectAsState()
    val groupAdmins by vm.groupAdmins.collectAsState()
    val myPubkey = remember { vm.getPublicKey() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<PendingHierarchyOp?>(null) }
    var showCreateChannel by remember { mutableStateOf(false) }

    val relayGroups = groupsByRelay[relayUrl].orEmpty()
    val byId = remember(relayGroups) { relayGroups.associateBy { it.id } }
    val view = hierarchyView(groupId, currentMetadata, relayGroups, childrenByParent, groupAdmins, myPubkey)
    val groupName = currentMetadata?.name?.takeIf { it.isNotBlank() } ?: groupId

    // Channel rows need each child's kind:39000 (name + full state for the detach PUT).
    LaunchedEffect(view.missingChildMeta) {
        view.missingChildMeta.forEach { AppModule.nostrRepository.refreshGroupMetadata(it) }
    }

    fun run(op: PendingHierarchyOp) {
        busy = true
        error = null
        scope.launch {
            val r = reparentGroup(AppModule.nostrRepository, op.target, op.op)
            busy = false
            if (r is Result.Error) error = r.error.message?.ifBlank { op.fail } ?: op.fail
        }
    }

    // Reordering re-declares the parent's child tags, so it needs THIS group's admin and a
    // channel list in sync with the declared tags (an in-flight attach/detach desyncs them
    // briefly, and reorderChildren rejects a non-permutation).
    val canReorder = myPubkey != null &&
        myPubkey in groupAdmins[groupId].orEmpty() &&
        view.childIds.size > 1 &&
        currentMetadata?.children?.toSet() == view.childIds.toSet()

    fun moveChild(sid: String, delta: Int) {
        val newOrder = movedChildOrder(view.childIds, sid, delta) ?: return
        busy = true
        error = null
        scope.launch {
            val r = AppModule.nostrRepository.reorderChildren(groupId, newOrder)
            busy = false
            if (r is Result.Error) error = r.error.message?.ifBlank { "Failed to reorder channels." } ?: "Failed to reorder channels."
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (view.parentId != null) {
            // Channel view: where this channel lives (breadcrumb), its siblings, then the
            // move actions. The parent crumb navigates like the sidebar rows do.
            Text("LOCATION", style = NostrordTypography.SectionHeader, color = NostrordColors.TextMuted)
            Spacer(modifier = Modifier.height(Spacing.xs))
            val parentMeta = byId[view.parentId]
            val parentLabel = view.parentName ?: view.parentId
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Row(
                    modifier =
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .then(
                            if (onOpenGroup != null) {
                                Modifier
                                    .clickable { onOpenGroup(view.parentId!!) }
                                    .pointerHoverIcon(PointerIcon.Hand)
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    OptimizedSmallAvatar(
                        imageUrl = parentMeta?.picture,
                        identifier = view.parentId!!,
                        displayName = parentLabel,
                        size = 24.dp,
                        shape = RoundedCornerShape(6.dp),
                        isGroup = true,
                    )
                    Text(parentLabel, style = NostrordTypography.Caption, color = NostrordColors.TextPrimary)
                }
                Text("›", style = NostrordTypography.Caption, color = NostrordColors.TextMuted)
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    OptimizedSmallAvatar(
                        imageUrl = currentMetadata?.picture,
                        identifier = groupId,
                        displayName = groupName,
                        size = 24.dp,
                        shape = RoundedCornerShape(6.dp),
                        isGroup = true,
                    )
                    Text(groupName, style = NostrordTypography.Caption, color = NostrordColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(modifier = Modifier.height(Spacing.xxl))

            Text("SIBLINGS (${view.siblingIds.size})", style = NostrordTypography.SectionHeader, color = NostrordColors.TextMuted)
            Spacer(modifier = Modifier.height(Spacing.sm))
            view.siblingIds.forEach { sid ->
                val isSelf = sid == groupId
                val m = byId[sid]
                val name = m?.name?.takeIf { it.isNotBlank() } ?: "${sid.take(12)}…"
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .then(
                            when {
                                isSelf -> Modifier.background(NostrordColors.SurfaceVariant)
                                onOpenGroup != null ->
                                    Modifier
                                        .clickable { onOpenGroup(sid) }
                                        .pointerHoverIcon(PointerIcon.Hand)
                                else -> Modifier
                            },
                        )
                        .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    OptimizedSmallAvatar(
                        imageUrl = m?.picture,
                        identifier = sid,
                        displayName = name,
                        size = 24.dp,
                        shape = RoundedCornerShape(6.dp),
                        isGroup = true,
                    )
                    Text(
                        name,
                        style = NostrordTypography.Caption,
                        color = if (m != null) NostrordColors.TextPrimary else NostrordColors.TextMuted,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isSelf) {
                        Text("this channel", style = NostrordTypography.Caption, color = NostrordColors.TextMuted)
                    }
                }
            }
            Spacer(modifier = Modifier.height(Spacing.xxl))

            Text("MOVE", style = NostrordTypography.SectionHeader, color = NostrordColors.TextMuted)
        } else {
            Text("PARENT", style = NostrordTypography.SectionHeader, color = NostrordColors.TextMuted)
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                "Current: Root group",
                style = NostrordTypography.Caption,
                color = NostrordColors.TextSecondary,
            )
        }
        Spacer(modifier = Modifier.height(Spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            GroupPickerDropdown(
                placeholder = when {
                    !view.canMove -> "Detach its channels to move this group"
                    view.parentCandidates.isEmpty() -> "No other groups on this relay"
                    else -> "Move under..."
                },
                candidates = view.parentCandidates,
                enabled = !busy && view.canMove && view.parentCandidates.isNotEmpty(),
                modifier = Modifier.weight(1f),
                onPick = { g ->
                    currentMetadata?.let { meta ->
                        pending = PendingHierarchyOp(
                            moveUnderPrompt(groupName, g.name?.takeIf { it.isNotBlank() } ?: g.id),
                            meta,
                            GroupManager.ParentOp.SetTo(g.id),
                            "Failed to update hierarchy.",
                        )
                    }
                },
            )
            if (view.parentId != null) {
                OutlinedButton(
                    onClick = {
                        currentMetadata?.let { meta ->
                            pending = PendingHierarchyOp(
                                makeRootPrompt(groupName, view.parentName),
                                meta,
                                GroupManager.ParentOp.Detach,
                                "Failed to convert to root group.",
                            )
                        }
                    },
                    enabled = !busy,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Text("Make root group", style = NostrordTypography.Button, color = NostrordColors.TextSecondary)
                }
            }
        }
        if (view.parentId != null) {
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                "Making this a root group keeps its members, messages and settings; it leaves ${view.parentName ?: "its parent"} and gets its own spot in the rail.",
                style = MaterialTheme.typography.labelSmall,
                color = NostrordColors.TextMuted,
            )
        }
        if (error != null) {
            Spacer(modifier = Modifier.height(Spacing.md))
            Text(error!!, style = NostrordTypography.Caption, color = NostrordColors.Error)
        }

        // Channels don't have channels (single-level hierarchy): the section only
        // exists on root groups.
        if (view.parentId == null) {
            Spacer(modifier = Modifier.height(Spacing.xxl))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "CHANNELS (${view.childIds.size})",
                    style = NostrordTypography.SectionHeader,
                    color = NostrordColors.TextMuted,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { showCreateChannel = true },
                    enabled = !busy,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Text("Create channel", style = NostrordTypography.Caption, color = NostrordColors.Primary)
                }
            }
            Spacer(modifier = Modifier.height(Spacing.sm))
            if (view.childIds.isEmpty()) {
                ModEmpty("No channels.")
            } else {
                view.childIds.forEachIndexed { index, sid ->
                    val sub = byId[sid]
                    val subName = sub?.name?.takeIf { it.isNotBlank() } ?: "${sid.take(12)}…"
                    Row(
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .then(
                                if (onOpenGroup != null) {
                                    Modifier
                                        .clickable { onOpenGroup(sid) }
                                        .pointerHoverIcon(PointerIcon.Hand)
                                } else {
                                    Modifier
                                },
                            )
                            .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        OptimizedSmallAvatar(
                            imageUrl = sub?.picture,
                            identifier = sid,
                            displayName = subName,
                            size = 24.dp,
                            shape = RoundedCornerShape(6.dp),
                            isGroup = true,
                        )
                        Text(
                            subName,
                            style = NostrordTypography.Caption,
                            color = if (sub != null) NostrordColors.TextPrimary else NostrordColors.TextMuted,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (canReorder) {
                            IconButton(
                                onClick = { moveChild(sid, -1) },
                                enabled = !busy && index > 0,
                                modifier = Modifier.size(24.dp).pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, "Move up", tint = NostrordColors.TextMuted, modifier = Modifier.size(18.dp))
                            }
                            IconButton(
                                onClick = { moveChild(sid, +1) },
                                enabled = !busy && index < view.childIds.lastIndex,
                                modifier = Modifier.size(24.dp).pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, "Move down", tint = NostrordColors.TextMuted, modifier = Modifier.size(18.dp))
                            }
                        }
                        // Detaching edits the CHILD's kind:9002, so it needs its admin key.
                        if (sub != null && myPubkey != null && myPubkey in groupAdmins[sid].orEmpty()) {
                            TextButton(
                                onClick = {
                                    pending = PendingHierarchyOp(
                                        detachChannelPrompt(subName),
                                        sub,
                                        GroupManager.ParentOp.Detach,
                                        "Failed to detach channel.",
                                    )
                                },
                                enabled = !busy,
                                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Text("Detach", style = NostrordTypography.Caption, color = NostrordColors.TextSecondary)
                            }
                        } else {
                            Text(
                                if (sub == null) "loading…" else "channel admin only",
                                style = NostrordTypography.Caption,
                                color = NostrordColors.TextMuted,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(Spacing.sm))
            GroupPickerDropdown(
                placeholder = if (view.childCandidates.isEmpty()) "No groups you admin to add" else "Add an existing group as a channel...",
                candidates = view.childCandidates,
                enabled = !busy && view.childCandidates.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                onPick = { g ->
                    pending = PendingHierarchyOp(
                        addChannelPrompt(g.name?.takeIf { it.isNotBlank() } ?: g.id, groupName),
                        g,
                        GroupManager.ParentOp.SetTo(groupId),
                        "Failed to add channel.",
                    )
                },
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                "Only childless root groups you administer on this relay can be added as channels; channels can't contain channels. Detaching a channel turns it back into a root group.",
                style = MaterialTheme.typography.labelSmall,
                color = NostrordColors.TextMuted,
            )
        }
    }

    pending?.let { p ->
        ConfirmDialog(
            title = p.prompt.title,
            message = p.prompt.message,
            confirmLabel = p.prompt.confirmLabel,
            onConfirm = {
                pending = null
                run(p)
            },
            onDismiss = { pending = null },
        )
    }

    if (showCreateChannel) {
        CreateGroupModal(
            currentRelayUrl = relayUrl,
            parentGroupId = groupId,
            onDismiss = { showCreateChannel = false },
            onGroupCreated = { _, _, _ -> showCreateChannel = false },
        )
    }
}

// ---- Danger ----

@Composable
private fun ManageDangerSection(
    groupId: String,
    isChannel: Boolean,
    onDeleted: () -> Unit,
) {
    val noun = if (isChannel) "channel" else "group"
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier =
        Modifier
            .fillMaxWidth()
            .border(1.dp, NostrordColors.Error.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .background(NostrordColors.Error.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Icon(Icons.Default.Warning, null, tint = NostrordColors.Error, modifier = Modifier.size(18.dp))
            Text("Delete $noun", style = NostrordTypography.MessageBody, color = NostrordColors.Error, fontWeight = FontWeight.Bold)
        }
        Text(
            if (confirmDelete) {
                "Are you sure? This permanently deletes the $noun from the relay and cannot be undone."
            } else {
                "This permanently deletes the $noun from the relay. This cannot be undone."
            },
            style = NostrordTypography.Caption,
            color = NostrordColors.TextSecondary,
        )
        if (error != null) {
            Text(error!!, style = NostrordTypography.Caption, color = NostrordColors.Error)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End)) {
            if (!confirmDelete) {
                Button(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.buttonColors(containerColor = NostrordColors.Error, contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Text("Delete $noun", style = NostrordTypography.Button)
                }
            } else {
                TextButton(onClick = { confirmDelete = false }, enabled = !deleting) {
                    Text("Cancel", color = NostrordColors.TextSecondary)
                }
                Button(
                    onClick = {
                        deleting = true
                        error = null
                        scope.launch {
                            when (val result = AppModule.nostrRepository.deleteGroup(groupId)) {
                                is Result.Success -> onDeleted()
                                is Result.Error -> {
                                    deleting = false
                                    error = result.error.message.ifBlank { "Failed to delete $noun." }
                                }
                            }
                        }
                    },
                    enabled = !deleting,
                    colors = ButtonDefaults.buttonColors(containerColor = NostrordColors.Error, contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    if (deleting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(Spacing.sm))
                    }
                    Text(if (deleting) "Deleting…" else "Confirm delete", style = NostrordTypography.Button)
                }
            }
        }
    }
}

// ---- Shared bits ----

@Composable
private fun ModEmpty(text: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) {
        Text(text, color = NostrordColors.TextMuted, style = NostrordTypography.Caption)
    }
}

@Composable
private fun MemberAvatar(pubkey: String) {
    Box(modifier = Modifier.size(32.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
        UserGradientAvatar(seed = pubkey, size = 32.dp)
    }
}

/** Picker that renders like an input and drops down the candidate groups (web's <select>). */
@Composable
private fun GroupPickerDropdown(
    placeholder: String,
    candidates: List<GroupMetadata>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onPick: (GroupMetadata) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Box(modifier = modifier) {
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(NostrordColors.BackgroundFloating)
                .clickable(enabled = enabled) { expanded = true }
                .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                placeholder,
                style = NostrordTypography.Caption,
                color = NostrordColors.TextMuted,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Default.KeyboardArrowDown, null, tint = NostrordColors.TextMuted, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                query = ""
            },
            containerColor = NostrordColors.Surface,
        ) {
            // Search only pays off past a handful of entries; small lists stay one click.
            if (candidates.size > 6) {
                AppSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search groups...",
                    size = InputSize.Compact,
                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs).widthIn(min = 220.dp),
                )
            }
            val shown = candidates.filter {
                query.isBlank() ||
                    (it.name ?: "").contains(query, ignoreCase = true) ||
                    it.id.contains(query, ignoreCase = true)
            }
            if (shown.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No matches", color = NostrordColors.TextMuted, style = NostrordTypography.Caption) },
                    onClick = {},
                    enabled = false,
                )
            }
            shown.forEach { g ->
                val gName = g.name?.takeIf { it.isNotBlank() } ?: g.id
                DropdownMenuItem(
                    leadingIcon = {
                        OptimizedSmallAvatar(
                            imageUrl = g.picture,
                            identifier = g.id,
                            displayName = gName,
                            size = 20.dp,
                            shape = RoundedCornerShape(5.dp),
                            isGroup = true,
                        )
                    },
                    text = { Text(gName, color = NostrordColors.TextPrimary, style = NostrordTypography.Caption) },
                    onClick = {
                        expanded = false
                        query = ""
                        onPick(g)
                    },
                )
            }
        }
    }
}

@Composable
private fun primaryButtonColors() = ButtonDefaults.buttonColors(
    containerColor = NostrordColors.Primary,
    contentColor = Color.White,
    disabledContainerColor = NostrordColors.Primary.copy(alpha = 0.5f),
    disabledContentColor = Color.White.copy(alpha = 0.5f),
)
