package org.nostr.nostrord.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.upload.MediaUploadService
import org.nostr.nostrord.network.upload.mediaServerDisplayName
import org.nostr.nostrord.ui.components.forms.AppField
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.Result

/**
 * Settings panel for the upload destination. Mirrors the web MediaServerSection and shares
 * [org.nostr.nostrord.settings.MediaServerSettings], so the server list, ordering, validation
 * and persistence live in commonMain.
 */
@Composable
fun MediaServerPanelContent() {
    val settings = AppModule.mediaServerSettings
    val service by settings.service.collectAsState()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = NostrordShapes.cardShape,
            colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(Spacing.xl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Media upload service", color = NostrordColors.TextPrimary, fontSize = 14.sp)
                    Text(
                        text = "Blossom spreads your media across several servers. A NIP-96 host keeps it in one place.",
                        color = NostrordColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.width(Spacing.md))
                ServicePicker(
                    service = service,
                    nip96Services = settings.nip96Services,
                    onPickBlossom = { settings.useBlossom() },
                    onPickNip96 = { settings.useNip96(it) },
                )
            }
        }

        if (service is MediaUploadService.Blossom) BlossomServerList()
    }
}

@Composable
private fun ServicePicker(
    service: MediaUploadService,
    nip96Services: List<String>,
    onPickBlossom: () -> Unit,
    onPickNip96: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label =
        when (service) {
            MediaUploadService.Blossom -> "Blossom"
            is MediaUploadService.Nip96 -> mediaServerDisplayName(service.url)
        }

    Box {
        Row(
            modifier = Modifier
                .clip(NostrordShapes.cardShape)
                .clickable { expanded = true }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, color = NostrordColors.TextPrimary, fontSize = 14.sp)
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = NostrordColors.TextMuted,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Blossom", color = NostrordColors.TextPrimary) },
                onClick = {
                    onPickBlossom()
                    expanded = false
                },
            )
            nip96Services.forEach { url ->
                DropdownMenuItem(
                    text = { Text(mediaServerDisplayName(url), color = NostrordColors.TextPrimary) },
                    onClick = {
                        onPickNip96(url)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun BlossomServerList() {
    val settings = AppModule.mediaServerSettings
    val servers by settings.blossomServers.collectAsState()
    var newUrl by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = NostrordShapes.cardShape,
        colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = "BLOSSOM SERVERS",
                style = NostrordTypography.SectionHeader,
                color = NostrordColors.TextMuted,
            )

            if (servers.isEmpty()) {
                Text(
                    text = "No servers. Add one below, or uploads will fail.",
                    color = NostrordColors.TextMuted,
                    fontSize = 13.sp,
                )
            }

            servers.forEachIndexed { index, url ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = mediaServerDisplayName(url),
                            color = NostrordColors.TextPrimary,
                            fontSize = 14.sp,
                        )
                        if (index == 0) {
                            Text(text = "Preferred", color = NostrordColors.Primary, fontSize = 11.sp)
                        }
                    }
                    IconButton(
                        onClick = { settings.moveBlossomServer(url, up = true) },
                        enabled = index > 0,
                        modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowUp,
                            contentDescription = "Move up",
                            tint = if (index > 0) NostrordColors.TextSecondary else NostrordColors.Divider,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    IconButton(
                        onClick = { settings.moveBlossomServer(url, up = false) },
                        enabled = index < servers.lastIndex,
                        modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "Move down",
                            tint = if (index < servers.lastIndex) NostrordColors.TextSecondary else NostrordColors.Divider,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    IconButton(
                        onClick = { settings.removeBlossomServer(url) },
                        modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove server",
                            tint = NostrordColors.TextMuted,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                if (index < servers.lastIndex) HorizontalDivider(color = NostrordColors.Divider)
            }

            Spacer(Modifier.height(Spacing.sm))
            HorizontalDivider(color = NostrordColors.Divider)
            Spacer(Modifier.height(Spacing.sm))

            Text(
                text = "ADD BLOSSOM SERVER",
                style = NostrordTypography.SectionHeader,
                color = NostrordColors.TextMuted,
            )

            AppField(
                value = newUrl,
                onValueChange = {
                    newUrl = it
                    addError = null
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "blossom.example.com",
            )

            addError?.let { Text(text = it, color = NostrordColors.Error, fontSize = 12.sp) }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                val canAdd = newUrl.isNotBlank()
                val addTint = if (canAdd) NostrordColors.Primary else NostrordColors.TextMuted
                TextButton(
                    onClick = {
                        when (val result = settings.addBlossomServer(newUrl)) {
                            is Result.Success -> {
                                newUrl = ""
                                addError = null
                            }

                            is Result.Error -> addError = result.error.message
                        }
                    },
                    enabled = canAdd,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = addTint, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add", color = addTint, style = NostrordTypography.Button)
                }
            }

            val recommended = settings.recommendedNotAdded()
            if (recommended.isNotEmpty()) {
                Text(
                    text = "Recommended servers",
                    color = NostrordColors.TextMuted,
                    fontSize = 12.sp,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    recommended.forEach { url ->
                        AssistChip(
                            onClick = { settings.addBlossomServer(url) },
                            label = { Text(mediaServerDisplayName(url), fontSize = 12.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        )
                    }
                }
            }

            Text(
                text = "Media is uploaded to the preferred server and mirrored to the others.",
                color = NostrordColors.TextSecondary,
                fontSize = 12.sp,
            )
        }
    }
}
