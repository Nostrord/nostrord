package org.nostr.nostrord.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.Identifier
import org.nostr.nostrord.ui.nprofileRelayHints
import org.nostr.nostrord.ui.pubkeyIdentifiers
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.rememberClipboardWriter

/**
 * [IdentifierRow] over the pubkey formats (npub / nprofile / nostrord link /
 * hex / nip-05). Used by the profile modal and the profile page.
 */
@Composable
fun IdentifierField(
    pubkey: String,
    nip05: String? = null,
    modifier: Modifier = Modifier,
) {
    val ids =
        remember(pubkey, nip05) {
            val hints = nprofileRelayHints(AppModule.nostrRepository.getRelayListForPubkey(pubkey).orEmpty())
            pubkeyIdentifiers(pubkey, nip05, hints)
        }
    IdentifierRow(ids = ids, modifier = modifier)
}

/**
 * Cycling identifier field (prototype IdentifierField): the swap button rotates
 * the [ids] formats and the copy button copies the visible value (checkmark
 * feedback). Mirrors the web components/IdentifierField (.identifier-* object).
 */
@Composable
fun IdentifierRow(
    ids: List<Identifier>,
    modifier: Modifier = Modifier,
    showQr: Boolean = false,
    // Same reason as AppField's: on a panel painted the default colour the row would vanish.
    containerColor: Color = NostrordColors.BackgroundFloating,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    if (ids.isEmpty()) return
    var index by remember(ids) { mutableStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    var qrOpen by remember { mutableStateOf(false) }
    val copyToClipboard = rememberClipboardWriter()
    val id = ids[index % ids.size]

    LaunchedEffect(copied) {
        if (copied) {
            delay(1200)
            copied = false
        }
    }

    Column(modifier = modifier) {
        Surface(shape = NostrordShapes.shapeMedium, color = containerColor) {
            // Compact icon buttons + tight spacing to match the web .identifier-field (22px
            // buttons, 6px gap), instead of Material's 48dp touch targets.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    id.value,
                    color = NostrordColors.TextSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (ids.size > 1) {
                    IconButton(onClick = { index = (index + 1) % ids.size }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.Cached,
                            contentDescription = "Format: ${id.label} (switch)",
                            tint = NostrordColors.TextMuted,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                if (showQr) {
                    IconButton(onClick = { qrOpen = !qrOpen }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.QrCode,
                            contentDescription = "QR code",
                            tint = if (qrOpen) NostrordColors.Success else NostrordColors.TextMuted,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                IconButton(
                    onClick = {
                        copyToClipboard(id.value)
                        copied = true
                    },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        tint = if (copied) NostrordColors.Success else NostrordColors.TextMuted,
                        modifier = Modifier.size(16.dp),
                    )
                }
                trailing()
            }
        }
        if (showQr && qrOpen) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            QrCode(
                data = id.value,
                size = 200.dp,
                modifier = Modifier.wrapContentWidth(Alignment.CenterHorizontally),
            )
        }
        if (ids.size > 1) {
            Spacer(modifier = Modifier.height(Spacing.xxs))
            Row(modifier = Modifier.padding(horizontal = Spacing.xs)) {
                Text("Format: ", color = NostrordColors.TextMuted, fontSize = 11.sp)
                Text(
                    id.label,
                    color = NostrordColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
