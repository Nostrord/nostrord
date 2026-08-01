package org.nostr.nostrord.ui.components.chat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.upload.FileTooLargeException
import org.nostr.nostrord.network.upload.MAX_UPLOAD_BYTES
import org.nostr.nostrord.network.upload.PasteMediaEffect
import org.nostr.nostrord.network.upload.UnsupportedFileTypeException
import org.nostr.nostrord.network.upload.mimeTypeForFilename
import org.nostr.nostrord.network.upload.rememberClipboardImageReader
import org.nostr.nostrord.network.upload.uploadMedia
import org.nostr.nostrord.ui.components.ConfirmDialog
import org.nostr.nostrord.ui.components.emoji.EmojiPicker
import org.nostr.nostrord.ui.components.upload.MessageUploadButton
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.Result

/**
 * Single rounded "pill" composer shared by the DM page and the individual thread view (web
 * .composer parity): attach button, caret-aware text field (paste-image + emoji), and a send
 * button that swaps the glyph for a spinner while [isSending], matching the group MessageInput.
 * Callers own [value] so they can clear it on send; uploads/emoji append through [onValueChange].
 */
@Composable
fun MessageComposer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    placeholder: String,
    isSending: Boolean,
    modifier: Modifier = Modifier,
) {
    var showEmojiPicker by remember { mutableStateOf(false) }
    var isUploadingPaste by remember { mutableStateOf(false) }
    var pasteError by remember { mutableStateOf<String?>(null) }
    val clipboardReader = rememberClipboardImageReader()
    val scope = rememberCoroutineScope()

    val canSend = value.text.isNotBlank() && !isSending && !isUploadingPaste

    fun appendUploadedUrl(url: String) {
        val current = value.text
        val sep = if (current.isNotEmpty() && !current.endsWith(" ") && !current.endsWith("\n")) " " else ""
        val newText = current + sep + url
        onValueChange(TextFieldValue(newText, TextRange(newText.length)))
    }

    suspend fun handlePastedMedia(bytes: ByteArray, filename: String) {
        if (bytes.size.toLong() > MAX_UPLOAD_BYTES) {
            isUploadingPaste = false
            pasteError = "This file is too large. The maximum upload size is 20 MB."
            return
        }
        try {
            val mime = mimeTypeForFilename(filename)
            when (val result = uploadMedia(bytes, filename, mime)) {
                is Result.Success -> appendUploadedUrl(result.data.url)
                is Result.Error -> pasteError = result.error.message
            }
        } finally {
            isUploadingPaste = false
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .clip(NostrordShapes.inputShape)
                .background(NostrordColors.SurfaceVariant)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MessageUploadButton(
                externalBusy = isUploadingPaste,
                onUploadComplete = { uploadResult -> appendUploadedUrl(uploadResult.url) },
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                cursorBrush = SolidColor(NostrordColors.TextContent),
                textStyle = NostrordTypography.Input.copy(color = NostrordColors.TextContent),
                maxLines = 7,
                modifier =
                Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { event ->
                        when {
                            event.type == KeyEventType.KeyDown && event.key == Key.Escape && showEmojiPicker -> {
                                showEmojiPicker = false
                                true
                            }
                            // Ctrl+V media: read the clipboard image and upload it.
                            event.type == KeyEventType.KeyDown && event.key == Key.V && event.isCtrlPressed && !isUploadingPaste -> {
                                val hasMedia = runCatching { clipboardReader.hasImage() }.getOrDefault(false)
                                if (hasMedia) {
                                    isUploadingPaste = true
                                    scope.launch {
                                        val image =
                                            try {
                                                clipboardReader.read()
                                            } catch (e: FileTooLargeException) {
                                                isUploadingPaste = false
                                                pasteError = e.message
                                                return@launch
                                            } catch (e: UnsupportedFileTypeException) {
                                                isUploadingPaste = false
                                                pasteError = e.message
                                                return@launch
                                            }
                                        if (image == null) {
                                            isUploadingPaste = false
                                            return@launch
                                        }
                                        handlePastedMedia(image.first, image.second)
                                    }
                                    true
                                } else {
                                    false
                                }
                            }
                            event.type == KeyEventType.KeyDown && event.key == Key.Enter && event.isShiftPressed -> {
                                val sel = value.selection
                                val t = value.text
                                val newText = t.substring(0, sel.start) + "\n" + t.substring(sel.end)
                                onValueChange(TextFieldValue(newText, TextRange(sel.start + 1)))
                                true
                            }
                            event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed -> {
                                if (canSend) onSend()
                                true
                            }
                            else -> false
                        }
                    },
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.padding(vertical = 4.dp)) {
                        if (value.text.isEmpty()) {
                            Text(
                                placeholder,
                                style = NostrordTypography.InputPlaceholder,
                                color = NostrordColors.TextMuted,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            IconButton(
                onClick = { showEmojiPicker = !showEmojiPicker },
                modifier = Modifier.size(width = 26.dp, height = 32.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.EmojiEmotions,
                    contentDescription = "Emoji",
                    tint = if (showEmojiPicker) NostrordColors.Primary else NostrordColors.TextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(
                onClick = onSend,
                enabled = canSend,
                modifier = Modifier.size(width = 26.dp, height = 32.dp),
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = NostrordColors.Primary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (value.text.isNotBlank()) NostrordColors.Primary else NostrordColors.TextMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        if (showEmojiPicker) {
            Popup(
                alignment = Alignment.BottomEnd,
                onDismissRequest = { showEmojiPicker = false },
                properties = PopupProperties(focusable = true),
            ) {
                Box(
                    modifier =
                    Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { showEmojiPicker = false },
                        ),
                ) {
                    EmojiPicker(
                        onEmojiSelect = { emoji ->
                            val t = value.text
                            val cursor = value.selection.start
                            val newText = t.substring(0, cursor) + emoji + t.substring(cursor)
                            onValueChange(TextFieldValue(newText, TextRange(cursor + emoji.length)))
                        },
                        onDismiss = { showEmojiPicker = false },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = Spacing.lg, bottom = 56.dp),
                    )
                }
            }
        }
    }

    // Desktop / Android paste of media (web is a no-op here, handled in the JS composer).
    PasteMediaEffect(
        onMediaPasted = { bytes, filename ->
            if (!isUploadingPaste) {
                isUploadingPaste = true
                scope.launch { handlePastedMedia(bytes, filename) }
            }
        },
        onError = { pasteError = it },
    )

    pasteError?.let { error ->
        ConfirmDialog(
            title = "Upload Failed",
            message = error,
            confirmLabel = "OK",
            cancelLabel = null,
            onConfirm = { pasteError = null },
            onDismiss = { pasteError = null },
        )
    }
}
