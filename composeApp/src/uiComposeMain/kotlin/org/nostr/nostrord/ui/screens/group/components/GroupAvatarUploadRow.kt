package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.upload.mimeTypeForFilename
import org.nostr.nostrord.network.upload.rememberMediaPickerLauncher
import org.nostr.nostrord.network.upload.uploadMedia
import org.nostr.nostrord.ui.components.avatars.OptimizedSmallAvatar
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.Result

/**
 * Group avatar preview + "Change photo" that uploads to nostr.build and reports the URL. Shared by
 * Create Group and Manage > Info so the picture is edited the same way in both.
 */
@Composable
fun GroupAvatarUploadRow(
    pictureUrl: String?,
    seed: String,
    name: String,
    onPictureChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onError: (String?) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var isUploading by remember { mutableStateOf(false) }
    val photoPicker =
        rememberMediaPickerLauncher { bytes, filename ->
            isUploading = true
            onError(null)
            scope.launch {
                try {
                    val mime = mimeTypeForFilename(filename)
                    when (
                        val result =
                            uploadMedia(bytes, filename, mime)
                    ) {
                        is Result.Success -> onPictureChange(result.data.url)
                        is Result.Error -> onError(result.error.message)
                    }
                } finally {
                    isUploading = false
                }
            }
        }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        OptimizedSmallAvatar(
            imageUrl = pictureUrl?.ifBlank { null },
            identifier = seed,
            displayName = name.ifBlank { seed },
            size = 56.dp,
            shape = RoundedCornerShape(12.dp),
            isGroup = true,
        )
        OutlinedButton(
            onClick = { photoPicker.launch() },
            enabled = !isUploading,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
        ) {
            if (isUploading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = NostrordColors.Primary, strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Spacer(modifier = Modifier.width(Spacing.sm))
            Text("Change photo", style = NostrordTypography.Button)
        }
    }
}
