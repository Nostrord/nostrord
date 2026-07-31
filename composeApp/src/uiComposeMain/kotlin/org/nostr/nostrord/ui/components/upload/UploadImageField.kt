package org.nostr.nostrord.ui.components.upload

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.upload.MediaPickerLauncher
import org.nostr.nostrord.network.upload.mimeTypeForFilename
import org.nostr.nostrord.network.upload.rememberMediaPickerLauncher
import org.nostr.nostrord.network.upload.uploadMedia
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.utils.Result

/**
 * URL text field with an upload-to-nostr.build button.
 * Intended for image URL fields (avatar, banner, group picture).
 */
@Composable
fun UploadImageField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "https://...",
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var isUploading by remember { mutableStateOf(false) }
    var uploadError by remember { mutableStateOf<String?>(null) }

    val picker: MediaPickerLauncher =
        rememberMediaPickerLauncher { bytes, filename ->
            isUploading = true
            uploadError = null
            scope.launch {
                try {
                    val mime = mimeTypeForFilename(filename)
                    val result =
                        uploadMedia(bytes, filename, mime)
                    when (result) {
                        is Result.Success -> onValueChange(result.data.url)
                        is Result.Error -> uploadError = result.error.message
                    }
                } finally {
                    isUploading = false
                }
            }
        }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    onValueChange(it)
                    uploadError = null
                },
                label = { Text(label, fontSize = 12.sp, color = NostrordColors.TextMuted) },
                placeholder = { Text(placeholder, color = NostrordColors.TextMuted, fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NostrordColors.Primary,
                    unfocusedBorderColor = NostrordColors.Divider,
                    focusedTextColor = NostrordColors.TextPrimary,
                    unfocusedTextColor = NostrordColors.TextPrimary,
                    focusedContainerColor = NostrordColors.BackgroundDark,
                    unfocusedContainerColor = NostrordColors.BackgroundDark,
                    cursorColor = NostrordColors.Primary,
                ),
                shape = RoundedCornerShape(8.dp),
            )

            IconButton(
                onClick = { picker.launch() },
                enabled = !isUploading,
                modifier =
                Modifier
                    .size(48.dp)
                    .pointerHoverIcon(PointerIcon.Hand),
            ) {
                if (isUploading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = NostrordColors.Primary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Upload,
                        contentDescription = "Upload image to nostr.build",
                        tint = NostrordColors.TextMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        if (uploadError != null) {
            Text(
                text = uploadError!!,
                color = NostrordColors.Error,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
    }
}
