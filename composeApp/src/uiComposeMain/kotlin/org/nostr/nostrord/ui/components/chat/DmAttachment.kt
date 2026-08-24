package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.managers.DmFileManager
import org.nostr.nostrord.nostr.Nip17File
import org.nostr.nostrord.ui.components.buttons.AppButton
import org.nostr.nostrord.ui.components.buttons.AppButtonSize
import org.nostr.nostrord.ui.components.buttons.AppButtonVariant
import org.nostr.nostrord.ui.components.loading.shimmerEffect
import org.nostr.nostrord.ui.components.media.MediaSaveButton
import org.nostr.nostrord.ui.media.INLINE_MEDIA_MAX_HEIGHT
import org.nostr.nostrord.ui.media.INLINE_MEDIA_MAX_WIDTH
import org.nostr.nostrord.ui.media.INLINE_MEDIA_MIN_SIDE
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.downloadFileName

/**
 * The body of a NIP-17 kind:15 message: an attachment whose bytes live encrypted on a media
 * server. [onLoad] starts the download and decryption; the plaintext never touches the URL, so
 * an image is handed to Coil as bytes rather than as a link.
 *
 * The sender's `dim` hint reserves the slot at the right aspect ratio before the bytes arrive,
 * the same trick the inline images in group chat use, so the bubble doesn't jump on load.
 */
@Composable
fun DmAttachment(
    file: Nip17File,
    state: DmFileManager.FileState?,
    onLoad: () -> Unit,
    onRetry: () -> Unit,
    onImage: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val autoLoad by AppModule.mediaSettings.autoLoadMedia.collectAsState()
    val dimensions = file.dimensions
    val slot =
        if (dimensions != null && dimensions.first > 0 && dimensions.second > 0) {
            Modifier
                .widthIn(max = INLINE_MEDIA_MAX_WIDTH.dp)
                .aspectRatio(
                    dimensions.first.toFloat() / dimensions.second.toFloat(),
                    matchHeightConstraintsFirst = dimensions.first < dimensions.second,
                )
        } else {
            Modifier
                .widthIn(max = INLINE_MEDIA_MAX_WIDTH.dp)
                .heightIn(max = INLINE_MEDIA_MAX_HEIGHT.dp)
                .defaultMinSize(minWidth = INLINE_MEDIA_MIN_SIDE.dp, minHeight = INLINE_MEDIA_MIN_SIDE.dp)
        }
    val textColor = if (onImage) Color.White.copy(alpha = 0.85f) else NostrordColors.TextMuted

    // The gate keeps this composable out of the tree entirely until the user reveals it, so a
    // gated attachment costs no download and no decrypt.
    GatedMedia(autoLoad = autoLoad, label = gateLabel(file)) {
        LaunchedEffect(file.url) { onLoad() }
        Attachment(file, state, onRetry, slot, textColor, dimensions, modifier)
    }
}

@Composable
private fun Attachment(
    file: Nip17File,
    state: DmFileManager.FileState?,
    onRetry: () -> Unit,
    slot: Modifier,
    textColor: Color,
    dimensions: Pair<Int, Int>?,
    modifier: Modifier,
) {
    var fullscreen by remember(file.url) { mutableStateOf(false) }

    when (state) {
        is DmFileManager.FileState.Ready ->
            if (file.isImage) {
                AsyncImage(
                    model = state.bytes,
                    contentDescription = null,
                    contentScale = if (dimensions != null) ContentScale.FillWidth else ContentScale.Fit,
                    modifier =
                    modifier
                        .then(slot)
                        .clip(NostrordShapes.imageShape)
                        .clickable { fullscreen = true },
                )
                if (fullscreen) {
                    // The viewer takes the plaintext: the url on the media server is ciphertext.
                    ImageViewerModal(
                        imageUrl = file.url,
                        imageBytes = state.bytes,
                        fileName = downloadFileName(file.url, state.mimeType ?: file.mimeType, "attachment"),
                        mimeType = state.mimeType ?: file.mimeType,
                        onDismiss = { fullscreen = false },
                    )
                }
            } else {
                Row(
                    modifier = modifier,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AttachmentNote(label(file) + " (" + readableSize(state.bytes.size.toLong()) + ")", textColor)
                    MediaSaveButton(
                        url = file.url,
                        bytes = state.bytes,
                        mimeType = state.mimeType ?: file.mimeType,
                        fallbackBase = "attachment",
                        tint = textColor,
                        contentDescription = "Save attachment",
                    )
                }
            }

        is DmFileManager.FileState.Failed ->
            Column(
                modifier = modifier.then(slot),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(state.reason, color = textColor, fontSize = 13.sp)
                AppButton(text = "Retry", onClick = onRetry, variant = AppButtonVariant.Secondary, size = AppButtonSize.Small)
            }

        else ->
            Box(
                modifier =
                modifier
                    .then(slot)
                    .fillMaxWidth()
                    .clip(NostrordShapes.imageShape)
                    .shimmerEffect(),
            )
    }
}

@Composable
private fun AttachmentNote(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        modifier = modifier.padding(vertical = Spacing.xxs),
    )
}

/** Matches the wording of the inline-media gate: "Tap to load image". */
private fun gateLabel(file: Nip17File): String = when {
    file.isVideo -> "video"
    file.isAudio -> "audio"
    file.isImage -> "image"
    else -> "file"
}

private fun label(file: Nip17File): String = when {
    file.isVideo -> "Video"
    file.isAudio -> "Audio"
    file.isImage -> "Photo"
    else -> "File"
}

private fun readableSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
