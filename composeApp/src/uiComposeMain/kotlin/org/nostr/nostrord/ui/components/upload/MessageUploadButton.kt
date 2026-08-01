package org.nostr.nostrord.ui.components.upload

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.upload.MediaAccept
import org.nostr.nostrord.network.upload.UploadResult
import org.nostr.nostrord.network.upload.mimeTypeForFilename
import org.nostr.nostrord.network.upload.rememberMediaPickerLauncher
import org.nostr.nostrord.network.upload.uploadMedia
import org.nostr.nostrord.ui.components.ConfirmDialog
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.utils.Result

/**
 * Attach/upload button for the message input row.
 * Picks a file, uploads to nostr.build, and returns the full upload result
 * (URL + NIP-68 metadata) so the caller can build imeta tags.
 */
@Composable
fun MessageUploadButton(
    onUploadComplete: (UploadResult) -> Unit,
    modifier: Modifier = Modifier,
    // Upload owned by the caller (paste / drag-and-drop) that doesn't go through
    // this button's picker. When true, the spinner shows here on the attach icon.
    externalBusy: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var isUploading by remember { mutableStateOf(false) }
    var uploadError by remember { mutableStateOf<String?>(null) }
    val busy = isUploading || externalBusy
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()
    val isPressed by interaction.collectIsPressedAsState()

    val picker =
        rememberMediaPickerLauncher(
            accept = MediaAccept.ImagesVideosAudio,
            onPickStart = { isUploading = true },
            onError = {
                isUploading = false
                uploadError = it
            },
        ) { bytes, filename ->
            scope.launch {
                try {
                    val mime = mimeTypeForFilename(filename)
                    val result =
                        uploadMedia(bytes, filename, mime)
                    when (result) {
                        is Result.Success -> onUploadComplete(result.data)
                        is Result.Error -> uploadError = result.error.message
                    }
                } finally {
                    isUploading = false
                }
            }
        }

    uploadError?.let { error ->
        ConfirmDialog(
            title = "Upload Failed",
            message = error,
            confirmLabel = "OK",
            cancelLabel = null,
            onConfirm = { uploadError = null },
            onDismiss = { uploadError = null },
        )
    }

    IconButton(
        onClick = { picker.launch() },
        enabled = !busy,
        interactionSource = interaction,
        modifier =
        modifier
            // Sized close to the icon (web .composer-btn padding:0) so the composer
            // icons sit tight rather than spread across 32dp squares.
            .size(width = 26.dp, height = 32.dp)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = NostrordColors.Primary,
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = Icons.Default.AttachFile,
                contentDescription = "Attach image",
                // Press shows white (tap feedback on Android, which has no hover);
                // hover brightens to TextContent (web .composer-btn:hover).
                tint = when {
                    isPressed -> NostrordColors.TextPrimary
                    isHovered -> NostrordColors.TextContent
                    else -> NostrordColors.TextMuted
                },
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
