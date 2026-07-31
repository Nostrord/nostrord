package org.nostr.nostrord.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.upload.MediaServer
import org.nostr.nostrord.network.upload.MediaServerProtocol
import org.nostr.nostrord.ui.components.forms.AppField
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.Result

/**
 * Settings panel for the upload destination. Mirrors the web MediaServerSection and shares
 * [org.nostr.nostrord.settings.MediaServerSettings], so the server list, validation and
 * persistence live in commonMain.
 */
@Composable
fun MediaServerPanelContent() {
    val settings = AppModule.mediaServerSettings
    val servers by settings.servers.collectAsState()
    val selected by settings.selected.collectAsState()

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
                text = "UPLOAD SERVER",
                style = NostrordTypography.SectionHeader,
                color = NostrordColors.TextMuted,
            )
            Text(
                text = "Where images, video and audio you send are stored. Blossom servers " +
                    "address files by their hash, so the same file can be mirrored anywhere.",
                color = NostrordColors.TextSecondary,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(Spacing.sm))

            servers.forEachIndexed { index, server ->
                MediaServerRow(
                    server = server,
                    selected = server.url == selected.url,
                    onSelect = { settings.select(server) },
                    onRemove = { settings.removeCustomServer(server) },
                )
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
            Spacer(Modifier.height(Spacing.sm))

            AppField(
                value = newUrl,
                onValueChange = {
                    newUrl = it
                    addError = null
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "blossom.example.com",
            )

            addError?.let {
                Text(text = it, color = NostrordColors.Error, fontSize = 12.sp)
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                val canAdd = newUrl.isNotBlank()
                val addTint = if (canAdd) NostrordColors.Primary else NostrordColors.TextMuted
                TextButton(
                    onClick = {
                        when (val result = settings.addCustomServer(newUrl)) {
                            is Result.Success -> {
                                settings.select(result.data)
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
        }
    }
}

@Composable
private fun MediaServerRow(
    server: MediaServer,
    selected: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = NostrordColors.Primary,
                unselectedColor = NostrordColors.TextMuted,
            ),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = server.name, color = NostrordColors.TextPrimary, fontSize = 14.sp)
            Text(
                text = server.url.removePrefix("https://") +
                    if (server.protocol == MediaServerProtocol.Blossom) "  ·  Blossom" else "",
                color = NostrordColors.TextMuted,
                fontSize = 12.sp,
            )
        }
        if (!server.builtIn) {
            IconButton(
                onClick = onRemove,
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
    }
}
