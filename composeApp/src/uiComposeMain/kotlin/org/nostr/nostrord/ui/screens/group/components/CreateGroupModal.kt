package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.outbox.RelayListManager
import org.nostr.nostrord.ui.components.forms.AppField
import org.nostr.nostrord.ui.screens.group.GroupAccessCopy
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.isValidRelayUrl
import org.nostr.nostrord.utils.toRelayUrl

/** Sentinel relay value that reveals the custom-relay text field. */
private const val CUSTOM_RELAY = "__custom__"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupModal(
    currentRelayUrl: String,
    userRelays: Set<String> = emptySet(),
    parentGroupId: String? = null,
    onDismiss: () -> Unit,
    // relayUrl is the relay the group was actually created on (may be a custom relay,
    // not currentRelayUrl), so the caller navigates to the right place.
    onGroupCreated: (relayUrl: String, groupId: String, groupName: String) -> Unit,
) {
    val isSubgroup = parentGroupId != null
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var name by remember { mutableStateOf("") }
    var customGroupId by remember { mutableStateOf("") }
    // The Group ID is an advanced option (the relay assigns a random one); reveal its field only
    // when the user opts in, so the common case stays uncluttered.
    var showCustomId by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf("") }
    var picture by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }
    var isClosed by remember { mutableStateOf(false) }
    var isRestricted by remember { mutableStateOf(false) }
    var isHidden by remember { mutableStateOf(false) }
    var listPrivately by remember { mutableStateOf(false) }
    var isCreating by remember { mutableStateOf(false) }
    var creatingJob by remember { mutableStateOf<Job?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val relayOptions =
        remember(currentRelayUrl, userRelays) {
            val list = userRelays.toMutableList()
            if (currentRelayUrl.isNotBlank() && currentRelayUrl !in list) {
                list.add(0, currentRelayUrl)
            } else if (currentRelayUrl.isNotBlank()) {
                list.remove(currentRelayUrl)
                list.add(0, currentRelayUrl)
            }
            // With no relays yet, offer a known NIP-29 group relay so the user can create without
            // typing one; "Custom relay…" stays available.
            list.filter { it.isNotBlank() }.distinct().ifEmpty { RelayListManager.SUGGESTED_GROUP_RELAYS }
        }
    var selectedRelay by remember(currentRelayUrl) {
        mutableStateOf(currentRelayUrl.ifBlank { RelayListManager.SUGGESTED_GROUP_RELAYS.first() })
    }
    var relayDropdownExpanded by remember { mutableStateOf(false) }
    // Custom relay: picking "Custom relay…" reveals a text field so a group can be created on any
    // relay, not just the listed ones.
    var customRelay by remember { mutableStateOf("") }
    val usingCustomRelay = selectedRelay == CUSTOM_RELAY
    // A subgroup must live on its parent's relay: the parent tag carries a relay-scoped
    // group id and the relay validates the link against its own state, so cross-relay
    // channels are not expressible. The relay is therefore not selectable in subgroup mode.
    val effectiveRelay = when {
        isSubgroup -> currentRelayUrl
        usingCustomRelay -> customRelay.trim().toRelayUrl()
        else -> selectedRelay
    }

    val relayWebUrl = effectiveRelay.replace("wss://", "https://").replace("ws://", "http://")

    fun cancelCreation() {
        creatingJob?.cancel()
        creatingJob = null
        isCreating = false
    }

    Dialog(
        onDismissRequest = {
            if (isCreating) cancelCreation()
            onDismiss()
        },
        properties =
        DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = !isCreating,
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
                ) { if (!isCreating) onDismiss() }
                .safeDrawingPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier =
                Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth(0.9f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { /* consume click */ },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface),
            ) {
                Column(
                    modifier =
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(Spacing.xxl),
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isSubgroup) "Create Channel" else "Create a Group",
                                style = NostrordTypography.ServerHeader,
                                color = NostrordColors.TextPrimary,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.height(Spacing.xs))
                            Text(
                                text = "Give your new group a name and description. You can always change these later.",
                                style = NostrordTypography.Caption,
                                color = NostrordColors.TextSecondary,
                            )
                        }
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        IconButton(
                            onClick = {
                                if (isCreating) cancelCreation()
                                onDismiss()
                            },
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

                    Spacer(modifier = Modifier.height(Spacing.xxl))

                    GroupAvatarUploadRow(
                        pictureUrl = picture,
                        seed = customGroupId.ifBlank { name },
                        name = name,
                        onPictureChange = { picture = it },
                        onError = { errorMessage = it },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(Spacing.lg))

                    // Group Name
                    FieldLabel("Group Name")
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    AppField(
                        value = name,
                        onValueChange = {
                            name = it
                            errorMessage = null
                        },
                        placeholder = "#example",
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(Spacing.lg))

                    // Custom Group ID (optional): hidden behind a disclosure link so the common
                    // case (random relay-assigned ID) stays uncluttered.
                    if (showCustomId) {
                        FieldLabel("Group ID (optional)")
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        AppField(
                            value = customGroupId,
                            onValueChange = {
                                customGroupId =
                                    it.lowercase().filter { c ->
                                        c.isLetterOrDigit() || c == '-' || c == '_'
                                    }
                                errorMessage = null
                            },
                            placeholder = "my-group",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        Text(
                            text = "Leave empty for a random ID. The relay may override your choice.",
                            style = NostrordTypography.Caption,
                            color = NostrordColors.TextMuted,
                        )
                    } else {
                        Text(
                            text = "Set a custom ID",
                            style = NostrordTypography.Caption,
                            color = NostrordColors.Primary,
                            textDecoration = TextDecoration.Underline,
                            modifier =
                            Modifier
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable { showCustomId = true },
                        )
                    }

                    Spacer(modifier = Modifier.height(Spacing.lg))

                    // Relay: fixed to the parent's relay for a subgroup, selectable otherwise.
                    FieldLabel("Relay")
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    if (isSubgroup) {
                        Text(
                            "Created on ${effectiveRelay.removePrefix("wss://")} (same relay as the parent group).",
                            style = NostrordTypography.Caption,
                            color = NostrordColors.TextSecondary,
                        )
                    } else {
                        ExposedDropdownMenuBox(
                            expanded = relayDropdownExpanded,
                            onExpandedChange = { if (!isCreating) relayDropdownExpanded = it },
                        ) {
                            AppField(
                                value = if (usingCustomRelay) "Custom relay…" else selectedRelay.removePrefix("wss://"),
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = relayDropdownExpanded)
                                },
                                modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                    // It is a select, not an editable field: a hand cursor (overriding
                                    // the inner text field's I-beam), matching the web `.modal-select`.
                                    .pointerHoverIcon(PointerIcon.Hand, overrideDescendants = true),
                            )
                            ExposedDropdownMenu(
                                expanded = relayDropdownExpanded,
                                onDismissRequest = { relayDropdownExpanded = false },
                                containerColor = NostrordColors.BackgroundFloating,
                            ) {
                                relayOptions.forEach { relay ->
                                    RelayMenuItem(
                                        label = relay.removePrefix("wss://"),
                                        selected = relay == selectedRelay,
                                        onClick = {
                                            selectedRelay = relay
                                            errorMessage = null
                                            relayDropdownExpanded = false
                                        },
                                    )
                                }
                                RelayMenuItem(
                                    label = "Custom relay…",
                                    selected = usingCustomRelay,
                                    onClick = {
                                        selectedRelay = CUSTOM_RELAY
                                        errorMessage = null
                                        relayDropdownExpanded = false
                                    },
                                )
                            }
                        }

                        if (usingCustomRelay) {
                            Spacer(modifier = Modifier.height(Spacing.xs))
                            AppField(
                                value = customRelay,
                                onValueChange = {
                                    customRelay = it
                                    errorMessage = null
                                },
                                placeholder = "relay.example.com",
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isCreating,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.lg))

                    // Description
                    FieldLabel("Description")
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

                    // Access settings
                    Text(
                        text = "ACCESS SETTINGS",
                        style = NostrordTypography.SectionHeader,
                        color = NostrordColors.TextMuted,
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))

                    AccessToggleRow(
                        icon = Icons.Default.Lock,
                        label = GroupAccessCopy.PRIVATE_LABEL,
                        description = GroupAccessCopy.PRIVATE_DESC,
                        checked = isPrivate,
                        onCheckedChange = { isPrivate = it },
                    )

                    Spacer(modifier = Modifier.height(Spacing.xs))

                    AccessToggleRow(
                        icon = Icons.Default.Block,
                        label = GroupAccessCopy.CLOSED_LABEL,
                        description = GroupAccessCopy.CLOSED_DESC,
                        checked = isClosed,
                        onCheckedChange = { isClosed = it },
                    )

                    Spacer(modifier = Modifier.height(Spacing.xs))

                    AccessToggleRow(
                        icon = Icons.AutoMirrored.Filled.Send,
                        label = GroupAccessCopy.RESTRICTED_LABEL,
                        description = GroupAccessCopy.RESTRICTED_DESC,
                        checked = isRestricted,
                        onCheckedChange = { isRestricted = it },
                    )

                    Spacer(modifier = Modifier.height(Spacing.xs))

                    AccessToggleRow(
                        icon = Icons.Default.VisibilityOff,
                        label = GroupAccessCopy.HIDDEN_LABEL,
                        description = GroupAccessCopy.HIDDEN_DESC,
                        checked = isHidden,
                        onCheckedChange = { isHidden = it },
                    )

                    Spacer(modifier = Modifier.height(Spacing.xxl))

                    // Local-list setting, not a relay flag: encrypted NIP-51 entry from the start.
                    Text(
                        text = "YOUR LIST",
                        style = NostrordTypography.SectionHeader,
                        color = NostrordColors.TextMuted,
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))

                    AccessToggleRow(
                        icon = Icons.Default.Shield,
                        label = GroupAccessCopy.LIST_PRIVATELY_LABEL,
                        description = GroupAccessCopy.LIST_PRIVATELY_DESC,
                        checked = listPrivately,
                        onCheckedChange = { listPrivately = it },
                    )

                    // Error message
                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(Spacing.md))
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        ) {
                            Text(
                                text = errorMessage!!,
                                style = NostrordTypography.Caption,
                                color = NostrordColors.Error,
                                modifier = Modifier.weight(1f),
                            )
                            // When the relay rejects in-app creation (must use its website) or
                            // requires authorization, offer a link to the relay site. "website"
                            // covers the friendlyError text for the blocked case.
                            val showLink =
                                errorMessage!!.contains("website", ignoreCase = true) ||
                                    errorMessage!!.contains("not allowed", ignoreCase = true) ||
                                    errorMessage!!.contains("restricted", ignoreCase = true) ||
                                    errorMessage!!.contains("authorization", ignoreCase = true) ||
                                    errorMessage!!.contains("auth-required", ignoreCase = true) ||
                                    errorMessage!!.contains("blocked", ignoreCase = true)
                            if (showLink) {
                                Spacer(modifier = Modifier.width(Spacing.xs))
                                Text(
                                    text = "Open relay website →",
                                    style = NostrordTypography.Caption,
                                    color = NostrordColors.Error,
                                    textDecoration = TextDecoration.Underline,
                                    modifier =
                                    Modifier
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .clickable { uriHandler.openUri(relayWebUrl) },
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.xxl))

                    // Footer
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                cancelCreation()
                                onDismiss()
                            },
                        ) {
                            Text(
                                "Cancel",
                                color = if (isCreating) NostrordColors.Error else NostrordColors.TextSecondary,
                            )
                        }
                        Button(
                            onClick = {
                                if (name.isBlank()) {
                                    errorMessage = "Group name is required."
                                    return@Button
                                }
                                // Validate the (normalized) relay before publishing: an unchecked
                                // custom value like "asdasd" would be saved and then fail to connect.
                                if (!isValidRelayUrl(effectiveRelay)) {
                                    errorMessage =
                                        if (usingCustomRelay) {
                                            "Enter a valid relay URL (e.g. relay.example.com)."
                                        } else {
                                            "Pick a relay."
                                        }
                                    return@Button
                                }
                                errorMessage = null
                                isCreating = true
                                creatingJob =
                                    scope.launch {
                                        try {
                                            val result =
                                                if (parentGroupId != null) {
                                                    AppModule.nostrRepository.createSubgroup(
                                                        parentGroupId = parentGroupId,
                                                        name = name.trim(),
                                                        about = about.trim().ifBlank { null },
                                                        relayUrl = effectiveRelay,
                                                        isPrivate = isPrivate,
                                                        isClosed = isClosed,
                                                        isRestricted = isRestricted,
                                                        isHidden = isHidden,
                                                        picture = picture.trim().ifBlank { null },
                                                        customGroupId = customGroupId.trim().ifBlank { null },
                                                        listPrivately = listPrivately,
                                                    )
                                                } else {
                                                    AppModule.nostrRepository.createGroup(
                                                        name = name.trim(),
                                                        about = about.trim().ifBlank { null },
                                                        relayUrl = effectiveRelay,
                                                        isPrivate = isPrivate,
                                                        isClosed = isClosed,
                                                        isRestricted = isRestricted,
                                                        isHidden = isHidden,
                                                        picture = picture.trim().ifBlank { null },
                                                        customGroupId = customGroupId.trim().ifBlank { null },
                                                        listPrivately = listPrivately,
                                                    )
                                                }
                                            isCreating = false
                                            creatingJob = null
                                            when (result) {
                                                is Result.Success -> onGroupCreated(effectiveRelay, result.data, name.trim())
                                                is Result.Error -> {
                                                    val raw = result.error.cause?.message ?: result.error.message
                                                    errorMessage = friendlyError(raw)
                                                }
                                            }
                                        } catch (_: CancellationException) {
                                            // user cancelled — no error shown
                                            isCreating = false
                                            creatingJob = null
                                        }
                                    }
                            },
                            enabled = !isCreating && name.isNotBlank() && effectiveRelay.isNotBlank(),
                            colors =
                            ButtonDefaults.buttonColors(
                                containerColor = NostrordColors.Primary,
                                contentColor = Color.White,
                                disabledContainerColor = NostrordColors.Primary.copy(alpha = 0.5f),
                                disabledContentColor = Color.White.copy(alpha = 0.5f),
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            if (isCreating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.width(Spacing.sm))
                            }
                            Text(if (isSubgroup) "Create Channel" else "Create Group", style = NostrordTypography.Button)
                        }
                    }
                }
            }
        }
    }
}

private fun friendlyError(raw: String?): String = when {
    raw == null -> "Something went wrong. Try again."
    // "blocked: to create groups open https://... in your web browser"
    raw.contains("blocked:", ignoreCase = true) ->
        "Group creation on this relay must be done via the relay's website."
    raw.contains("auth-required", ignoreCase = true) ||
        raw.contains("not allowed", ignoreCase = true) ||
        raw.contains("restricted", ignoreCase = true) ->
        "This relay requires authorization to create groups."
    raw.contains("did not respond", ignoreCase = true) ||
        raw.contains("timeout", ignoreCase = true) ->
        "Relay did not respond. Try again."
    raw.contains("Not connected", ignoreCase = true) ->
        "Not connected to relay. Try again."
    else -> raw
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = NostrordTypography.Caption,
        color = NostrordColors.TextSecondary,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun AccessToggleRow(
    icon: ImageVector,
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .background(NostrordColors.SurfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = NostrordColors.TextMuted,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(Spacing.sm))
            Column {
                Text(
                    text = label,
                    style = NostrordTypography.Caption,
                    color = NostrordColors.TextPrimary,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = description,
                    style = NostrordTypography.Caption,
                    color = NostrordColors.TextMuted,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors =
            SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = NostrordColors.Primary,
                uncheckedThumbColor = NostrordColors.TextMuted,
                uncheckedTrackColor = NostrordColors.InputBackground,
            ),
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
        )
    }
}

/**
 * One relay option in the [ExposedDropdownMenu] (web `.modal-select` option parity): a compact,
 * custom row (Material's [DropdownMenuItem] floors at 48dp) with a full-width brand highlight on the
 * selected row, a hover tint on the rest, and the default cursor (native `<option>`s are not hand).
 */
@Composable
private fun RelayMenuItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val background =
        when {
            selected -> NostrordColors.Primary
            hovered -> NostrordColors.HoverBackground
            else -> Color.Transparent
        }
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .background(background)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (selected) Color.White else NostrordColors.TextPrimary,
            style = NostrordTypography.MessageBody,
        )
    }
}
