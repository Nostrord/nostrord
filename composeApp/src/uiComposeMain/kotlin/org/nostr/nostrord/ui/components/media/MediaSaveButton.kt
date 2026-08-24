package org.nostr.nostrord.ui.components.media

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.utils.downloadFileName
import org.nostr.nostrord.utils.fetchMediaForDownload
import org.nostr.nostrord.utils.rememberMediaDownloader
import org.nostr.nostrord.utils.supportsMediaDownload

/**
 * Saves an inline media file to the device. Renders nothing on a platform that has nowhere to
 * save to.
 *
 * [bytes] short-circuits the network for content the app already holds in plaintext (a decrypted
 * NIP-17 attachment, whose url only ever serves ciphertext); everything else is fetched, and the
 * response's Content-Type is what names the file, since a host that keys blobs by hash serves
 * them all under one generic object name. [mimeType] is the type known up front, used when the
 * bytes are handed in.
 */
@Composable
fun MediaSaveButton(
    url: String,
    modifier: Modifier = Modifier,
    bytes: ByteArray? = null,
    mimeType: String? = null,
    fallbackBase: String = "file",
    tint: Color = NostrordColors.TextMuted,
    contentDescription: String = "Save file",
) {
    if (!supportsMediaDownload) return

    val scope = rememberCoroutineScope()
    val saveMedia = rememberMediaDownloader()
    var isSaving by remember { mutableStateOf(false) }

    IconButton(
        onClick = {
            if (isSaving) return@IconButton
            isSaving = true
            scope.launch {
                try {
                    val (data, type) =
                        if (bytes != null) {
                            bytes to mimeType
                        } else {
                            fetchMediaForDownload(url) ?: (null to null)
                        }
                    if (data != null) {
                        val cleanType = type?.substringBefore(';')?.trim().orEmpty()
                        saveMedia(
                            data,
                            downloadFileName(url, cleanType, fallbackBase),
                            cleanType.ifBlank { "application/octet-stream" },
                        )
                    }
                } finally {
                    isSaving = false
                }
            }
        },
        modifier = modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand),
    ) {
        if (isSaving) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = tint)
        } else {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
