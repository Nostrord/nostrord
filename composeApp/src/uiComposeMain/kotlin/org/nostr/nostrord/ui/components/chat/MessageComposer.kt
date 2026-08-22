package org.nostr.nostrord.ui.components.chat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import org.nostr.nostrord.getPlatform
import org.nostr.nostrord.network.upload.FileTooLargeException
import org.nostr.nostrord.network.upload.MAX_UPLOAD_BYTES
import org.nostr.nostrord.network.upload.PasteMediaEffect
import org.nostr.nostrord.network.upload.UnsupportedFileTypeException
import org.nostr.nostrord.network.upload.mimeTypeForFilename
import org.nostr.nostrord.network.upload.rememberClipboardImageReader
import org.nostr.nostrord.network.upload.uploadMedia
import org.nostr.nostrord.ui.components.ConfirmDialog
import org.nostr.nostrord.ui.components.buttons.AppButton
import org.nostr.nostrord.ui.components.buttons.AppButtonSize
import org.nostr.nostrord.ui.components.emoji.EmojiPicker
import org.nostr.nostrord.ui.components.upload.MessageUploadButton
import org.nostr.nostrord.ui.mentions.MentionAutocomplete
import org.nostr.nostrord.ui.screens.group.components.MentionVisualTransformation
import org.nostr.nostrord.ui.screens.group.model.GroupInfo
import org.nostr.nostrord.ui.screens.group.model.MemberInfo
import org.nostr.nostrord.ui.screens.group.threadComposerSubmits
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.ui.theme.rememberEmojiFontFamily
import org.nostr.nostrord.utils.Result

/**
 * Single rounded "pill" composer shared by the DM page and the individual thread view (web
 * .composer parity): attach button, caret-aware text field (paste-image + emoji), and a send
 * button that swaps the glyph for a spinner while [isSending], matching the group MessageInput.
 * Callers own [value] so they can clear it on send; uploads/emoji append through [onValueChange].
 *
 * Passing [members] / [availableGroups] turns on the `@user` / `%group` autocomplete: picking a
 * suggestion writes the token into the text and reports it through [onMentionsChange] /
 * [onGroupMentionsChange], which the caller resolves at send time. Left empty (the DM page) the
 * composer behaves as a plain field.
 *
 * [sendLabel] + [minLines] + `sendOnEnter = false` turn it into the forum post box the thread
 * view uses: a tall field with a labeled button, where Enter writes a newline.
 */
@Composable
fun MessageComposer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    placeholder: String,
    isSending: Boolean,
    modifier: Modifier = Modifier,
    // Labeled button instead of the paper-plane glyph ("Post reply"), for a deliberate post.
    sendLabel: String? = null,
    // Opening height of the field, in lines.
    minLines: Int = 1,
    // false: Enter writes a newline and Ctrl/Cmd+Enter sends (threadComposerSubmits).
    sendOnEnter: Boolean = true,
    // Lets a caller put the caret here (picking Reply focuses the composer to type into).
    focusRequester: FocusRequester? = null,
    members: List<MemberInfo> = emptyList(),
    availableGroups: List<GroupInfo> = emptyList(),
    // displayName -> pubkey, resolved to nostr:npub + a p tag when the event is built.
    mentions: Map<String, String> = emptyMap(),
    onMentionsChange: (Map<String, String>) -> Unit = {},
    // groupName -> the mentioned group, resolved to nostr:naddr in the content by the caller.
    groupMentions: Map<String, GroupInfo> = emptyMap(),
    onGroupMentionsChange: (Map<String, GroupInfo>) -> Unit = {},
    // Set by callers that own the upload themselves (DMs encrypt the bytes first). Picked and
    // pasted files are handed over raw; the spinner stays up until the handler returns.
    onFilePicked: (suspend (ByteArray, String) -> Unit)? = null,
    // Message being replied to. Non-null shows its quote above the input with a way out.
    replyAuthorName: String? = null,
    replySnippet: String? = null,
    onCancelReply: (() -> Unit)? = null,
) {
    var showEmojiPicker by remember { mutableStateOf(false) }
    var isUploadingPaste by remember { mutableStateOf(false) }
    var pasteError by remember { mutableStateOf<String?>(null) }
    val clipboardReader = rememberClipboardImageReader()
    val scope = rememberCoroutineScope()

    val mentionState = rememberMentionFieldState()
    val memberMatches = mentionState.memberMatches(members)
    val groupMatches = mentionState.groupMatches(availableGroups)
    val isAndroid = remember { getPlatform().name.startsWith("Android") }

    // Keep a stable VisualTransformation instance: rebuilding it every recomposition restarts the
    // Android IME session, which drops the character being typed while the popup is open.
    val emojiFontFamily = rememberEmojiFontFamily()
    val mentionVisualTransformation = remember(mentions.keys, groupMentions.keys, emojiFontFamily) {
        MentionVisualTransformation(
            mentionedNames = mentions.keys,
            mentionColor = NostrordColors.MentionText,
            emojiFontFamily = emojiFontFamily,
            groupMentionedNames = groupMentions.keys,
        )
    }

    fun changeText(newValue: TextFieldValue) {
        onValueChange(newValue)
        mentionState.onValueChange(newValue)
    }

    fun selectMember(member: MemberInfo) {
        val updated = mentionState.apply(value, member.displayName) ?: return
        onValueChange(updated)
        if (!mentions.containsKey(member.displayName)) {
            onMentionsChange(mentions + (member.displayName to member.pubkey))
        }
        focusRequester?.requestFocus()
    }

    fun selectGroup(group: GroupInfo) {
        val updated = mentionState.apply(value, group.name) ?: return
        onValueChange(updated)
        if (!groupMentions.containsKey(group.name)) {
            onGroupMentionsChange(groupMentions + (group.name to group))
        }
        focusRequester?.requestFocus()
    }

    fun confirmMention() {
        memberMatches.getOrNull(mentionState.selectedIndex)?.let {
            selectMember(it)
            return
        }
        groupMatches.getOrNull(mentionState.selectedIndex)?.let { selectGroup(it) }
    }

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
            if (onFilePicked != null) {
                onFilePicked(bytes, filename)
                return
            }
            val mime = mimeTypeForFilename(filename)
            when (val result = uploadMedia(bytes, filename, mime)) {
                is Result.Success -> appendUploadedUrl(result.data.url)
                is Result.Error -> pasteError = result.error.message
            }
        } finally {
            isUploadingPaste = false
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Android renders the suggestions inline (same window): a Popup is a separate Android
        // window whose add/remove restarts the IME InputConnection and eats the character being
        // typed. canFocus = false keeps the clickable rows from stealing focus from the field.
        if (isAndroid && (memberMatches.isNotEmpty() || groupMatches.isNotEmpty())) {
            Box(
                modifier = Modifier
                    .focusProperties { canFocus = false }
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .padding(bottom = Spacing.sm),
            ) {
                MentionSuggestions(
                    state = mentionState,
                    members = members,
                    groups = availableGroups,
                    onMemberSelect = { selectMember(it) },
                    onGroupSelect = { selectGroup(it) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (replyAuthorName != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReplyQuote(
                    authorName = replyAuthorName,
                    snippet = replySnippet.orEmpty(),
                    modifier = Modifier.weight(1f),
                )
                onCancelReply?.let { cancel ->
                    IconButton(onClick = cancel, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel reply",
                            tint = NostrordColors.TextMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            // Post box (sendLabel != null): field on its own full-width line with the controls in
            // a row under it, mirroring the web .thread-composer. Chat / DM keep the one-line pill.
            val boxModifier = Modifier
                .fillMaxWidth()
                .clip(NostrordShapes.inputShape)
                .background(NostrordColors.SurfaceVariant)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            val uploadButton: @Composable () -> Unit = {
                MessageUploadButton(
                    externalBusy = isUploadingPaste,
                    onUploadComplete = { uploadResult -> appendUploadedUrl(uploadResult.url) },
                    onFilePicked = onFilePicked,
                )
            }
            val emojiButton: @Composable () -> Unit = {
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
            }
            val sendButton: @Composable () -> Unit = {
                if (sendLabel != null) {
                    AppButton(
                        text = sendLabel,
                        onClick = onSend,
                        enabled = canSend,
                        loading = isSending,
                        size = AppButtonSize.Small,
                    )
                } else {
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
            }
            val textField: @Composable (Modifier) -> Unit = { fieldModifier ->
                BasicTextField(
                    value = value,
                    onValueChange = { changeText(it) },
                    cursorBrush = SolidColor(NostrordColors.TextContent),
                    textStyle = NostrordTypography.Input.copy(color = NostrordColors.TextContent),
                    visualTransformation = mentionVisualTransformation,
                    minLines = minLines,
                    maxLines = if (minLines > 1) 12 else 7,
                    modifier =
                    fieldModifier
                        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                        .onFocusChanged { focusState ->
                            // Android drops focus transiently while the IME composes; dismissing there
                            // would flicker the inline list. Typing or Esc closes it instead.
                            if (!isAndroid && !focusState.isFocused) mentionState.dismiss()
                        }
                        .onPreviewKeyEvent { event ->
                            // The popup owns Esc / arrows / Enter / Tab while it is open, so Enter
                            // completes the highlighted suggestion instead of sending.
                            if (mentionState.handleKeyEvent(
                                    event,
                                    maxOf(memberMatches.size, groupMatches.size),
                                ) { confirmMention() }
                            ) {
                                return@onPreviewKeyEvent true
                            }
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
                                // Post box: only Ctrl/Cmd+Enter sends, every other Enter falls
                                // through to the field so it writes a newline.
                                !sendOnEnter && event.type == KeyEventType.KeyDown && event.key == Key.Enter -> {
                                    val submits = threadComposerSubmits(
                                        isEnter = true,
                                        ctrlOrMeta = event.isCtrlPressed || event.isMetaPressed,
                                        shift = event.isShiftPressed,
                                    )
                                    if (submits && canSend) onSend()
                                    submits
                                }
                                event.type == KeyEventType.KeyDown && event.key == Key.Enter && event.isShiftPressed -> {
                                    val sel = value.selection
                                    val t = value.text
                                    val newText = t.substring(0, sel.start) + "\n" + t.substring(sel.end)
                                    changeText(TextFieldValue(newText, TextRange(sel.start + 1)))
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
                        Box(
                            contentAlignment = if (minLines > 1) Alignment.TopStart else Alignment.CenterStart,
                            modifier = Modifier.padding(vertical = 4.dp),
                        ) {
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
            }
            if (sendLabel != null) {
                Column(modifier = boxModifier) {
                    textField(Modifier.fillMaxWidth())
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        uploadButton()
                        emojiButton()
                        Spacer(modifier = Modifier.weight(1f))
                        sendButton()
                    }
                }
            } else {
                Row(
                    modifier = boxModifier,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    uploadButton()
                    textField(Modifier.weight(1f))
                    emojiButton()
                    sendButton()
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
                                changeText(TextFieldValue(newText, TextRange(cursor + emoji.length)))
                            },
                            onDismiss = { showEmojiPicker = false },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(end = Spacing.lg, bottom = 56.dp),
                        )
                    }
                }
            }

            // Desktop / iOS float the list over the page; the transparent scrim above it closes on an
            // outside tap (dismissOnClickOutside stays off so soft-keyboard taps never close it).
            if (!isAndroid && (memberMatches.isNotEmpty() || groupMatches.isNotEmpty())) {
                val density = LocalDensity.current
                val rows = maxOf(memberMatches.size, groupMatches.size).coerceAtMost(MentionAutocomplete.MAX_SUGGESTIONS)
                val popupHeightPx = with(density) { (MENTION_POPUP_HEADER_DP + rows * MENTION_POPUP_ROW_DP).dp.roundToPx() }

                Popup(
                    alignment = Alignment.Center,
                    onDismissRequest = { mentionState.dismiss() },
                    properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { mentionState.dismiss() },
                            ),
                    )
                }

                Popup(
                    alignment = Alignment.TopStart,
                    offset = IntOffset(x = 0, y = -popupHeightPx),
                    onDismissRequest = { mentionState.dismiss() },
                    properties = PopupProperties(focusable = false, dismissOnClickOutside = false, dismissOnBackPress = true),
                ) {
                    MentionSuggestions(
                        state = mentionState,
                        members = members,
                        groups = availableGroups,
                        onMemberSelect = { selectMember(it) },
                        onGroupSelect = { selectGroup(it) },
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
