package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import org.nostr.nostrord.autoFocusTextInput
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.getPlatform
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.upload.FileTooLargeException
import org.nostr.nostrord.network.upload.MAX_UPLOAD_BYTES
import org.nostr.nostrord.network.upload.PasteMediaEffect
import org.nostr.nostrord.network.upload.ShareMediaEffect
import org.nostr.nostrord.network.upload.UnsupportedFileTypeException
import org.nostr.nostrord.network.upload.UploadResult
import org.nostr.nostrord.network.upload.mimeTypeForFilename
import org.nostr.nostrord.network.upload.rememberClipboardImageReader
import org.nostr.nostrord.network.upload.uploadMedia
import org.nostr.nostrord.ui.components.ConfirmDialog
import org.nostr.nostrord.ui.components.emoji.EmojiPicker
import org.nostr.nostrord.ui.components.upload.MessageUploadButton
import org.nostr.nostrord.ui.screens.group.model.GroupInfo
import org.nostr.nostrord.ui.screens.group.model.MemberInfo
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.ui.theme.rememberEmojiFontFamily
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.formatTimestamp

/**
 * Message input field with Discord-style keyboard behavior.
 *
 * - Enter: send (or confirm mention/group popup)
 * - Shift+Enter: insert newline
 * - Escape: close active popup
 * - Tab: confirm highlighted suggestion
 */
@Composable
fun MessageInput(
    isJoined: Boolean,
    isGroupClosed: Boolean = false,
    isPendingApproval: Boolean = false,
    pendingRequestedAtSeconds: Long? = null,
    onCancelJoinRequest: () -> Unit = {},
    selectedChannel: String,
    groupId: String,
    groupName: String?,
    messageInput: String,
    onSendMessage: (String) -> Unit,
    onJoinGroup: (inviteCode: String?) -> Unit,
    groupMembers: List<MemberInfo> = emptyList(),
    mentions: Map<String, String> = emptyMap(), // displayName -> pubkey
    onMentionsChange: (Map<String, String>) -> Unit = {},
    availableGroups: List<GroupInfo> = emptyList(),
    groupMentions: Map<String, GroupInfo> = emptyMap(), // name -> GroupInfo
    onGroupMentionsChange: (Map<String, GroupInfo>) -> Unit = {},
    replyingToMessage: NostrGroupClient.NostrMessage? = null,
    // Bumped on every reply tap; keys the focus effect so re-replying to the
    // same message still refocuses and re-summons the keyboard.
    replyNonce: Int = 0,
    replyingToMetadata: UserMetadata? = null,
    userMetadata: Map<String, UserMetadata> = emptyMap(),
    onCancelReply: () -> Unit = {},
    isSending: Boolean = false,
    onMediaUploaded: (UploadResult) -> Unit = {},
    onOverlayVisibilityChange: (Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val clipboardReader = rememberClipboardImageReader()
    var isUploadingPaste by remember { mutableStateOf(false) }
    var pasteError by remember { mutableStateOf<String?>(null) }

    var showMentionPopup by remember { mutableStateOf(false) }
    var mentionStartIndex by remember { mutableStateOf(-1) }
    var mentionQuery by remember { mutableStateOf("") }
    var mentionSelectedIndex by remember { mutableStateOf(0) }
    var showGroupMentionPopup by remember { mutableStateOf(false) }
    var groupMentionStartIndex by remember { mutableStateOf(-1) }
    var groupMentionQuery by remember { mutableStateOf("") }
    var groupMentionSelectedIndex by remember { mutableStateOf(0) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    // Keyboard-shortcuts hint, opened by Ctrl+/ or clicking the footer pill
    // (web parity: ChatScreen showHints).
    var showHints by remember { mutableStateOf(false) }
    // Consuming the Ctrl+/ KeyDown does not stop desktop's typed-char pipeline: the
    // "/" (or "?") still reaches the field and would land AND instantly close the
    // hints. One-shot flag so handleTextFieldValueChange drops that glyph.
    var swallowChordGlyph by remember { mutableStateOf(false) }
    val anyOverlayOpen = showMentionPopup || showGroupMentionPopup || showEmojiPicker
    val currentOnOverlayVisibilityChange by rememberUpdatedState(onOverlayVisibilityChange)
    LaunchedEffect(anyOverlayOpen) {
        currentOnOverlayVisibilityChange(anyOverlayOpen)
    }
    val focusRequester = remember { FocusRequester() }
    val showEmojiButton = remember {
        val platform = getPlatform().name
        !platform.startsWith("Android") && !platform.startsWith("iOS")
    }

    // Auto-focus only where there's a physical keyboard (desktop, desktop web).
    // On touch devices — Android, iOS, and coarse-pointer (mobile) web — this would
    // pop the on-screen keyboard the instant a group opens, so it's skipped.
    LaunchedEffect(isJoined, groupName) {
        if (isJoined && autoFocusTextInput) {
            focusRequester.requestFocus()
        }
    }

    // Refocus input when reply is activated. Unlike the screen-open auto-focus above,
    // this fires only on an explicit reply action (tap or swipe), so opening the
    // keyboard on Android here is desired — reply mode should land in the input.
    val softwareKeyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(replyingToMessage, replyNonce) {
        if (replyingToMessage != null) {
            focusRequester.requestFocus()
            // requestFocus is a no-op when the field is already focused with the IME
            // dismissed (Android back); show() re-summons it (web focusComposerForTyping).
            softwareKeyboard?.show()
        }
    }

    // Seed the field from the saved per-group draft and re-seed when the group changes,
    // so leaving a group and coming back restores the unsent text (text lives here, not
    // hoisted to the screen, so this is the source of truth for the draft body).
    var textFieldValue by remember(groupId) {
        val savedText = AppModule.messageDraftStore.get(groupId).text
        mutableStateOf(TextFieldValue(savedText, TextRange(savedText.length)))
    }

    // Persist the text on every change to the in-memory draft store. snapshotFlow keeps
    // this out of composition (a plain map write, no recomposition), so it does not
    // reintroduce the per-keystroke chat re-render this composer was split out to avoid.
    LaunchedEffect(groupId) {
        snapshotFlow { textFieldValue.text }
            .collect { AppModule.messageDraftStore.setText(groupId, it) }
    }

    suspend fun handlePastedMedia(bytes: ByteArray, filename: String) {
        if (bytes.size.toLong() > MAX_UPLOAD_BYTES) {
            isUploadingPaste = false
            pasteError = "This file is too large. The maximum upload size is 20 MB."
            return
        }
        try {
            val mime = mimeTypeForFilename(filename)
            val result = uploadMedia(bytes, filename, mime)
            when (result) {
                is Result.Success -> {
                    val url = result.data.url
                    val current = textFieldValue.text
                    val sep = if (current.isNotEmpty() && !current.endsWith(" ") && !current.endsWith("\n")) " " else ""
                    val newText = current + sep + url
                    textFieldValue = TextFieldValue(newText, TextRange(newText.length))
                    onMediaUploaded(result.data)
                }
                is Result.Error -> pasteError = result.error.message
            }
        } finally {
            isUploadingPaste = false
        }
    }

    // `messageInput` is a one-way restore channel from the parent: it carries a
    // failed send's draft so it can be recovered. It is intentionally NOT updated
    // while typing (that would recompose the whole screen + message list on every
    // keystroke), so we only pull from it when it has text AND the field is empty
    // (don't clobber a message the user has already started typing).
    LaunchedEffect(messageInput) {
        if (messageInput.isNotEmpty() && textFieldValue.text.isEmpty()) {
            textFieldValue = TextFieldValue(messageInput, TextRange(messageInput.length))
            // Skip touch/mobile web so a restore doesn't re-pop the on-screen keyboard.
            if (autoFocusTextInput) focusRequester.requestFocus()
        }
    }

    fun findMentionContext(text: String, cursorPosition: Int, trigger: Char): Pair<Int, String> {
        if (cursorPosition <= 0 || cursorPosition > text.length) return Pair(-1, "")
        val triggerIndex = text.substring(0, cursorPosition).lastIndexOf(trigger)
        if (triggerIndex == -1) return Pair(-1, "")
        val charBefore = text.getOrNull(triggerIndex - 1)
        if (charBefore != null && !charBefore.isWhitespace()) return Pair(-1, "")
        val queryPart = text.substring(triggerIndex + 1, cursorPosition)
        if (queryPart.contains(' ') || queryPart.contains('\n')) return Pair(-1, "")
        return Pair(triggerIndex, queryPart)
    }

    // The Android IME can briefly report a stale/zeroed cursor while composing,
    // which makes findMentionContext fail for one frame. As long as the trigger
    // token still runs unbroken to the end of the text, treat the mention as
    // active so the popup doesn't flicker closed mid-typing.
    fun triggerStillActive(text: String, startIndex: Int, trigger: Char): Boolean {
        if (startIndex < 0 || startIndex >= text.length || text[startIndex] != trigger) return false
        val charBefore = text.getOrNull(startIndex - 1)
        if (charBefore != null && !charBefore.isWhitespace()) return false
        val token = text.substring(startIndex + 1)
        return !token.contains(' ') && !token.contains('\n')
    }

    fun updateMentionState(value: TextFieldValue) {
        val (index, query) = findMentionContext(value.text, value.selection.start, '@')
        if (index >= 0) {
            if (mentionQuery != query) mentionSelectedIndex = 0
            showMentionPopup = true
            showEmojiPicker = false
            mentionStartIndex = index
            mentionQuery = query
        } else if (showMentionPopup && triggerStillActive(value.text, mentionStartIndex, '@')) {
            val token = value.text.substring(mentionStartIndex + 1)
            if (mentionQuery != token) mentionSelectedIndex = 0
            mentionQuery = token
        } else {
            showMentionPopup = false
            mentionStartIndex = -1
            mentionQuery = ""
        }
    }

    fun updateGroupMentionState(value: TextFieldValue) {
        val (index, query) = findMentionContext(value.text, value.selection.start, '%')
        if (index >= 0) {
            if (groupMentionQuery != query) groupMentionSelectedIndex = 0
            showGroupMentionPopup = true
            showEmojiPicker = false
            groupMentionStartIndex = index
            groupMentionQuery = query
        } else if (showGroupMentionPopup && triggerStillActive(value.text, groupMentionStartIndex, '%')) {
            val token = value.text.substring(groupMentionStartIndex + 1)
            if (groupMentionQuery != token) groupMentionSelectedIndex = 0
            groupMentionQuery = token
        } else {
            showGroupMentionPopup = false
            groupMentionStartIndex = -1
            groupMentionQuery = ""
        }
    }

    fun handleTextFieldValueChange(newValue: TextFieldValue) {
        // Lock typing while a send is in flight. We do this here instead of via
        // readOnly so the Android IME session is not torn down and recreated,
        // which closes and reopens the soft keyboard (flicker) on every send.
        // Simply dropping the change is not enough: the Android IME keeps a
        // composing region and replays the buffered keystrokes once the lock
        // lifts (e.g. "adas" + typed "das" -> "adasdas"). Re-assert the current
        // value with the composition cleared so nothing can accumulate.
        if (isSending) {
            if (textFieldValue.composition != null) {
                textFieldValue = textFieldValue.copy(composition = null)
            }
            return
        }
        // Drop the glyph the Ctrl+/ chord injected (see swallowChordGlyph).
        if (swallowChordGlyph) {
            swallowChordGlyph = false
            val pos = newValue.selection.start - 1
            if (pos >= 0 &&
                pos < newValue.text.length &&
                (newValue.text[pos] == '/' || newValue.text[pos] == '?') &&
                newValue.text.removeRange(pos, pos + 1) == textFieldValue.text
            ) {
                return
            }
        }
        // Typing closes the hints card; it opens via Ctrl+/ or the footer pill only,
        // never a typed character, so every glyph ("?" included) stays a normal
        // message character (web parity).
        if (showHints) showHints = false
        textFieldValue = newValue
        updateMentionState(newValue)
        updateGroupMentionState(newValue)
    }

    // Hand the composed text to the parent and clear the field optimistically.
    // The text lives here (not hoisted to the screen), so this is the single point
    // the parent learns what to send. On failure the parent pushes the draft back
    // via the `messageInput` restore channel.
    fun submit() {
        val text = textFieldValue.text
        if (text.isBlank() || isSending || isUploadingPaste) return
        showHints = false
        showEmojiPicker = false
        showMentionPopup = false
        showGroupMentionPopup = false
        mentionStartIndex = -1
        groupMentionStartIndex = -1
        mentionQuery = ""
        groupMentionQuery = ""
        onSendMessage(text)
        textFieldValue = TextFieldValue("")
        if (autoFocusTextInput) focusRequester.requestFocus()
    }

    fun handleMemberSelect(member: MemberInfo) {
        val currentText = textFieldValue.text
        val beforeMention = currentText.substring(0, mentionStartIndex)
        val afterMention = if (mentionStartIndex + 1 + mentionQuery.length < currentText.length) {
            currentText.substring(mentionStartIndex + 1 + mentionQuery.length).trimStart()
        } else {
            ""
        }
        val mentionPart = "@${member.displayName} "
        val newText = if (afterMention.isEmpty()) {
            "$beforeMention$mentionPart"
        } else {
            "$beforeMention$mentionPart$afterMention"
        }
        val cursorPosition = beforeMention.length + mentionPart.length
        textFieldValue = TextFieldValue(newText, TextRange(cursorPosition))
        if (!mentions.containsKey(member.displayName)) {
            onMentionsChange(mentions + (member.displayName to member.pubkey))
        }
        showMentionPopup = false
        mentionStartIndex = -1
        mentionQuery = ""
        focusRequester.requestFocus()
    }

    fun handleGroupSelect(group: GroupInfo) {
        val currentText = textFieldValue.text
        val beforeMention = currentText.substring(0, groupMentionStartIndex)
        val afterMention = if (groupMentionStartIndex + 1 + groupMentionQuery.length < currentText.length) {
            currentText.substring(groupMentionStartIndex + 1 + groupMentionQuery.length).trimStart()
        } else {
            ""
        }
        val mentionPart = "%${group.name} "
        val newText = if (afterMention.isEmpty()) {
            "$beforeMention$mentionPart"
        } else {
            "$beforeMention$mentionPart$afterMention"
        }
        val cursorPosition = beforeMention.length + mentionPart.length
        textFieldValue = TextFieldValue(newText, TextRange(cursorPosition))
        if (!groupMentions.containsKey(group.name)) {
            onGroupMentionsChange(groupMentions + (group.name to group))
        }
        showGroupMentionPopup = false
        groupMentionStartIndex = -1
        groupMentionQuery = ""
        focusRequester.requestFocus()
    }

    if (isPendingApproval) {
        Box(
            // Inset rounded card matching the web .composer-join (16dp side + bottom margin,
            // rounded, surface-variant) rather than a full-bleed bar.
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.lg)
                .clip(NostrordShapes.shapeMedium)
                .background(NostrordColors.SurfaceVariant)
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    Text(
                        text = "Your join request is pending admin approval",
                        color = NostrordColors.TextSecondary,
                        style = NostrordTypography.MessageBody,
                    )
                    if (pendingRequestedAtSeconds != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Requested ${formatTimestamp(pendingRequestedAtSeconds)}",
                            color = NostrordColors.TextMuted,
                            style = NostrordTypography.Caption,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(Spacing.md))
                // Outlined button: contrasts with the SurfaceVariant bar (a filled secondary
                // button would blend in), reads clearly as a button, stays understated.
                Box(
                    modifier =
                    Modifier
                        .clip(NostrordShapes.shapeMedium)
                        .border(1.dp, NostrordColors.Divider, NostrordShapes.shapeMedium)
                        .clickable(onClick = onCancelJoinRequest)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Text(
                        "Cancel request",
                        color = NostrordColors.TextSecondary,
                        style = NostrordTypography.Button,
                    )
                }
            }
        }
        return
    }

    if (!isJoined) {
        Box(
            // Inset rounded card matching the web .composer-join (16dp side + bottom margin,
            // rounded, surface-variant) rather than a full-bleed bar.
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.lg)
                .clip(NostrordShapes.shapeMedium)
                .background(NostrordColors.SurfaceVariant)
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Join the group to send messages",
                    color = NostrordColors.TextSecondary,
                    style = NostrordTypography.MessageBody,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.width(Spacing.md))
                // Filled primary button on the right (web .composer-join-btn): icon + label,
                // "Request to Join" for closed groups, "Join Now" for open.
                Button(
                    onClick = { onJoinGroup(null) },
                    colors = ButtonDefaults.buttonColors(containerColor = NostrordColors.Primary),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                    shape = NostrordShapes.shapeMedium,
                ) {
                    Icon(
                        Icons.Default.PersonAdd,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (isGroupClosed) "Request to Join" else "Join Now",
                        style = NostrordTypography.Button,
                    )
                }
            }
        }
    } else {
        val textFieldInteractionSource = remember { MutableInteractionSource() }
        val isAndroid = remember { getPlatform().name.startsWith("Android") }
        // Landscape on a phone has little vertical room, so the discoverability footer is
        // dropped there; cross-platform via the window size.
        val windowSize = LocalWindowInfo.current.containerSize
        val isLandscape = windowSize.width > windowSize.height

        // Markdown toolbar (prototype Composer, web parity): wraps the selection /
        // toggles list markers on the TextFieldValue, keeping mention state in sync.
        var toolbarOpen by remember { mutableStateOf(false) }

        fun applyEdit(newValue: TextFieldValue) {
            textFieldValue = newValue
            updateMentionState(newValue)
            updateGroupMentionState(newValue)
            focusRequester.requestFocus()
        }

        // Wrap the current selection (or cursor) with markdown tokens; the caret
        // lands inside, before the closing token.
        fun wrapSelection(pre: String, post: String = pre) {
            val v = textFieldValue
            val start = v.selection.min
            val end = v.selection.max
            val sel = v.text.substring(start, end)
            val newText = v.text.substring(0, start) + pre + sel + post + v.text.substring(end)
            applyEdit(TextFieldValue(newText, TextRange(start + pre.length + sel.length)))
        }

        // List toolbar buttons: prefix the covered line(s) with a marker; clicking
        // again toggles it off (prototype insertList).
        fun insertListMarkers(ordered: Boolean) {
            val v = textFieldValue
            val text = v.text
            val lineFrom = text.lastIndexOf('\n', (v.selection.min - 1).coerceAtLeast(0)).let { if (it == -1) 0 else it + 1 }
            var lineTo = text.indexOf('\n', v.selection.max)
            if (lineTo == -1) lineTo = text.length
            val lines = text.substring(lineFrom, lineTo).split('\n')
            val re = if (ordered) Regex("^\\d+\\.\\s") else Regex("^[-*+]\\s")
            // Either list marker, so switching types replaces instead of stacking (the
            // "1. - foo" bug). Toggle-off only when every line already has the requested type.
            val anyMarker = Regex("^(?:\\d+\\.|[-*+])\\s")
            val allListed = lines.all { it.isBlank() || re.containsMatchIn(it) }
            val out =
                lines.mapIndexed { i, ln ->
                    if (allListed) {
                        ln.replaceFirst(re, "")
                    } else {
                        (if (ordered) "${i + 1}. " else "- ") + ln.replaceFirst(anyMarker, "")
                    }
                }.joinToString("\n")
            val newText = text.substring(0, lineFrom) + out + text.substring(lineTo)
            applyEdit(TextFieldValue(newText, TextRange(lineFrom + out.length)))
        }

        // Enter inside a list: continue with the next marker; on an empty item, drop
        // the marker (exit the list) instead — so a second Enter ends the list rather
        // than sending the message (prototype continueList).
        fun continueList(): Boolean {
            val v = textFieldValue
            if (!v.selection.collapsed) return false
            val pos = v.selection.start
            val text = v.text
            val lineStart = text.lastIndexOf('\n', (pos - 1).coerceAtLeast(0)).let { if (it == -1) 0 else it + 1 }
            val nextBreak = text.indexOf('\n', pos)
            val lineEnd = if (nextBreak == -1) text.length else nextBreak
            val line = text.substring(lineStart, lineEnd)
            val ul = Regex("^(\\s*)([-*+])\\s+(.*)$").find(line)
            val ol = Regex("^(\\s*)(\\d+)\\.\\s+(.*)$").find(line)
            if (ul == null && ol == null) return false
            val indent = (ul ?: ol)!!.groupValues[1]
            val content = if (ul != null) ul.groupValues[3] else ol!!.groupValues[3]
            if (content.isBlank()) {
                // Empty item: remove the marker and exit the list.
                val newText = text.substring(0, lineStart) + indent + text.substring(lineEnd)
                applyEdit(TextFieldValue(newText, TextRange(lineStart + indent.length)))
                return true
            }
            val marker = if (ul != null) indent + ul.groupValues[2] + " " else indent + (ol!!.groupValues[2].toInt() + 1) + ". "
            val newText = text.substring(0, pos) + "\n" + marker + text.substring(pos)
            applyEdit(TextFieldValue(newText, TextRange(pos + 1 + marker.length)))
            return true
        }

        // Keep a stable VisualTransformation instance: rebuilding it on every
        // recomposition restarts the Android IME input session, which drops the
        // character being typed while the mention popup is open.
        val emojiFontFamily = rememberEmojiFontFamily()
        val mentionVisualTransformation = remember(mentions.keys, groupMentions.keys, emojiFontFamily) {
            MentionVisualTransformation(
                mentionedNames = mentions.keys,
                mentionColor = NostrordColors.MentionText,
                emojiFontFamily = emojiFontFamily,
                groupMentionedNames = groupMentions.keys,
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            // On Android the suggestion list is rendered INLINE (same window) rather
            // than in a Popup. A Popup is a separate Android window: adding/removing it
            // (or the soft keyboard gaining/losing focus to it) restarts the IME
            // InputConnection on the focused TextField, which drops the composing
            // character. Because the popup toggled on every keystroke, this produced
            // the "every other character eaten + popup flicker" symptom. Rendering the
            // list in the same window leaves the IME input connection untouched.
            // canFocus = false keeps the list (whose items are clickable/focusable)
            // from stealing focus away from the TextField while typing.
            if (isAndroid &&
                showMentionPopup &&
                groupMembers.isNotEmpty() &&
                getFilteredMembers(groupMembers, mentionQuery).isNotEmpty()
            ) {
                Box(
                    modifier = Modifier
                        .focusProperties { canFocus = false }
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .padding(horizontal = Spacing.lg),
                ) {
                    MentionPopup(
                        members = groupMembers,
                        query = mentionQuery,
                        selectedIndex = mentionSelectedIndex,
                        onMemberSelect = { handleMemberSelect(it) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (isAndroid &&
                showGroupMentionPopup &&
                availableGroups.isNotEmpty() &&
                getFilteredGroups(availableGroups, groupMentionQuery).isNotEmpty()
            ) {
                Box(
                    modifier = Modifier
                        .focusProperties { canFocus = false }
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .padding(horizontal = Spacing.lg),
                ) {
                    GroupMentionPopup(
                        groups = availableGroups,
                        query = groupMentionQuery,
                        selectedIndex = groupMentionSelectedIndex,
                        onGroupSelect = { handleGroupSelect(it) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Column(
                // Inset the composer from the window side edges and round it into a single
                // surface "pill". No bottom padding: the composer's baseline lines up with
                // the account bar at the bottom of the sidebar (their centers match, both ~52dp).
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg),
            ) {
                // Shortcuts hint card, floating just above the pill (web .composer-hints).
                if (showHints) {
                    // Transparent full-window scrim so a click/tap anywhere outside the card
                    // dismisses it (same pattern as the mention scrims). Non-focusable so the
                    // soft keyboard stays up; back press also closes. Only while the field is
                    // empty: with text present the scrim would swallow the tap on Send
                    // (typing, Esc and submit() close the card in that state).
                    if (textFieldValue.text.isEmpty()) {
                        Popup(
                            alignment = Alignment.Center,
                            onDismissRequest = { showHints = false },
                            properties = PopupProperties(focusable = false, dismissOnClickOutside = false, dismissOnBackPress = true),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Transparent)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { showHints = false },
                                    ),
                            )
                        }
                    }
                    ComposerHints(isTouch = isAndroid)
                    Spacer(modifier = Modifier.height(Spacing.xs))
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(NostrordShapes.inputShape)
                        .background(NostrordColors.SurfaceVariant),
                ) {
                    // Reply chip pinned to the top of the composer frame (web
                    // .composer-reply): part of the pill, not a full-width bar.
                    if (replyingToMessage != null) {
                        ReplyingToBar(
                            message = replyingToMessage,
                            metadata = replyingToMetadata,
                            userMetadata = userMetadata,
                            onCancelReply = onCancelReply,
                        )
                    }
                    if (toolbarOpen) {
                        ComposerToolbar(
                            onWrap = { pre, post -> wrapSelection(pre, post) },
                            onList = { ordered -> insertListMarkers(ordered) },
                            onClose = { toolbarOpen = false },
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Markdown toolbar toggle (prototype: the leftmost composer button).
                        // Buttons are sized close to the icon (web .composer-btn has padding:0)
                        // so the row reads tight instead of spread across 32dp squares.
                        IconButton(
                            onClick = { toolbarOpen = !toolbarOpen },
                            modifier = Modifier
                                .size(width = 26.dp, height = 32.dp)
                                .pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                Icons.Default.TextFields,
                                contentDescription = "Text formatting",
                                tint = if (toolbarOpen) NostrordColors.TextPrimary else NostrordColors.TextMuted,
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        MessageUploadButton(
                            externalBusy = isUploadingPaste,
                            onUploadComplete = { uploadResult ->
                                val url = uploadResult.url
                                val current = textFieldValue.text
                                val separator = if (current.isNotEmpty() && !current.endsWith(" ") && !current.endsWith("\n")) " " else ""
                                val newText = current + separator + url
                                textFieldValue = TextFieldValue(newText, TextRange(newText.length))
                                onMediaUploaded(uploadResult)
                            },
                        )

                        BasicTextField(
                            value = textFieldValue,
                            onValueChange = { handleTextFieldValueChange(it) },
                            // Typing is locked while a send is in flight inside
                            // handleTextFieldValueChange, not via readOnly: toggling
                            // readOnly restarts the Android IME (keyboard flicker) and
                            // behaves inconsistently across platforms. The handler is
                            // the single source of truth for the in-flight lock.
                            interactionSource = textFieldInteractionSource,
                            cursorBrush = SolidColor(NostrordColors.TextPrimary),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester)
                                .onFocusChanged { focusState ->
                                    // On Android the focus transiently drops while the IME
                                    // composes; dismissing here would flicker the inline
                                    // suggestion list and break keyboard input. Let typing,
                                    // selection, or back-press close it instead.
                                    if (!isAndroid && !focusState.isFocused) {
                                        showMentionPopup = false
                                        showGroupMentionPopup = false
                                    }
                                }
                                .onPreviewKeyEvent { event ->
                                    // AWT keyCodes for punctuation are layout-dependent (the ABNT2
                                    // "/" key can even report VK_UNDEFINED), so besides Key.Slash
                                    // also match the produced character; "?" is the shifted variant.
                                    val isHintsChord = event.isCtrlPressed &&
                                        (
                                            event.key == Key.Slash ||
                                                event.key == Key.NumPadDivide ||
                                                event.utf16CodePoint == '/'.code ||
                                                event.utf16CodePoint == '?'.code
                                            )
                                    // The swallow flag lives only until the next KeyDown: the
                                    // chord's typed glyph (when the platform delivers one) arrives
                                    // in between, so a later legit "/" is never eaten.
                                    if (event.type == KeyEventType.KeyDown && !isHintsChord) {
                                        swallowChordGlyph = false
                                    }
                                    val filteredMembers = getFilteredMembers(groupMembers, mentionQuery)
                                    val filteredGroups = getFilteredGroups(availableGroups, groupMentionQuery)
                                    when {
                                        event.type == KeyEventType.KeyDown &&
                                            event.key == Key.Escape &&
                                            showEmojiPicker -> {
                                            showEmojiPicker = false
                                            true
                                        }
                                        event.type == KeyEventType.KeyDown &&
                                            event.key == Key.Escape &&
                                            showMentionPopup -> {
                                            showMentionPopup = false
                                            mentionStartIndex = -1
                                            mentionQuery = ""
                                            true
                                        }
                                        event.type == KeyEventType.KeyDown &&
                                            event.key == Key.Escape &&
                                            showGroupMentionPopup -> {
                                            showGroupMentionPopup = false
                                            groupMentionStartIndex = -1
                                            groupMentionQuery = ""
                                            true
                                        }
                                        event.type == KeyEventType.KeyDown &&
                                            event.key == Key.Escape &&
                                            toolbarOpen -> {
                                            toolbarOpen = false
                                            true
                                        }
                                        event.type == KeyEventType.KeyDown &&
                                            event.key == Key.Escape &&
                                            showHints -> {
                                            showHints = false
                                            true
                                        }
                                        // Esc exits reply mode once no popup/picker is open (desktop).
                                        event.type == KeyEventType.KeyDown &&
                                            event.key == Key.Escape &&
                                            replyingToMessage != null -> {
                                            onCancelReply()
                                            true
                                        }
                                        // Ctrl+/ toggles the shortcuts hint.
                                        event.type == KeyEventType.KeyDown && isHintsChord -> {
                                            swallowChordGlyph = true
                                            showHints = !showHints
                                            true
                                        }
                                        event.type == KeyEventType.KeyDown &&
                                            event.key == Key.DirectionUp &&
                                            showMentionPopup &&
                                            filteredMembers.isNotEmpty() -> {
                                            mentionSelectedIndex = (mentionSelectedIndex - 1).coerceAtLeast(0)
                                            true
                                        }
                                        event.type == KeyEventType.KeyDown &&
                                            event.key == Key.DirectionDown &&
                                            showMentionPopup &&
                                            filteredMembers.isNotEmpty() -> {
                                            mentionSelectedIndex = (mentionSelectedIndex + 1).coerceAtMost(filteredMembers.size - 1)
                                            true
                                        }
                                        event.type == KeyEventType.KeyDown &&
                                            event.key == Key.DirectionUp &&
                                            showGroupMentionPopup &&
                                            filteredGroups.isNotEmpty() -> {
                                            groupMentionSelectedIndex = (groupMentionSelectedIndex - 1).coerceAtLeast(0)
                                            true
                                        }
                                        event.type == KeyEventType.KeyDown &&
                                            event.key == Key.DirectionDown &&
                                            showGroupMentionPopup &&
                                            filteredGroups.isNotEmpty() -> {
                                            groupMentionSelectedIndex = (groupMentionSelectedIndex + 1).coerceAtMost(filteredGroups.size - 1)
                                            true
                                        }
                                        event.type == KeyEventType.KeyDown &&
                                            event.key == Key.V &&
                                            event.isCtrlPressed &&
                                            !isUploadingPaste -> {
                                            val hasMedia = runCatching { clipboardReader.hasImage() }.getOrDefault(false)
                                            if (hasMedia) {
                                                isUploadingPaste = true
                                                scope.launch {
                                                    val image = try {
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
                                        // Shift+Enter: manually insert newline at cursor (Discord-style)
                                        // Returning false here is unreliable in Compose Desktop — insert explicitly.
                                        event.type == KeyEventType.KeyDown &&
                                            event.key == Key.Enter &&
                                            event.isShiftPressed -> {
                                            val sel = textFieldValue.selection
                                            val text = textFieldValue.text
                                            val newText = text.substring(0, sel.start) + "\n" + text.substring(sel.end)
                                            val newValue = TextFieldValue(newText, TextRange(sel.start + 1))
                                            textFieldValue = newValue
                                            updateMentionState(newValue)
                                            updateGroupMentionState(newValue)
                                            true
                                        }
                                        event.type == KeyEventType.KeyDown &&
                                            event.key == Key.Enter &&
                                            !event.isShiftPressed -> {
                                            if (showMentionPopup && filteredMembers.isNotEmpty()) {
                                                val selectedMember = filteredMembers.getOrNull(mentionSelectedIndex)
                                                if (selectedMember != null) handleMemberSelect(selectedMember)
                                                true
                                            } else if (showGroupMentionPopup && filteredGroups.isNotEmpty()) {
                                                val selectedGroup = filteredGroups.getOrNull(groupMentionSelectedIndex)
                                                if (selectedGroup != null) handleGroupSelect(selectedGroup)
                                                true
                                            } else if (continueList()) {
                                                // Inside a list: continue / exit it, never send.
                                                true
                                            } else if (textFieldValue.text.isNotBlank()) {
                                                submit()
                                                true
                                            } else {
                                                true
                                            }
                                        }
                                        event.type == KeyEventType.KeyDown &&
                                            event.key == Key.Tab &&
                                            showMentionPopup &&
                                            filteredMembers.isNotEmpty() -> {
                                            val selectedMember = filteredMembers.getOrNull(mentionSelectedIndex)
                                            if (selectedMember != null) handleMemberSelect(selectedMember)
                                            true
                                        }
                                        event.type == KeyEventType.KeyDown &&
                                            event.key == Key.Tab &&
                                            showGroupMentionPopup &&
                                            filteredGroups.isNotEmpty() -> {
                                            val selectedGroup = filteredGroups.getOrNull(groupMentionSelectedIndex)
                                            if (selectedGroup != null) handleGroupSelect(selectedGroup)
                                            true
                                        }
                                        else -> false
                                    }
                                },
                            textStyle = NostrordTypography.Input.copy(color = NostrordColors.TextPrimary),
                            singleLine = false,
                            // ~7 visible lines before internal scroll (prototype max-h-40).
                            maxLines = 7,
                            visualTransformation = mentionVisualTransformation,
                            decorationBox = { innerTextField ->
                                // BasicTextField has no built-in placeholder/container; show
                                // the placeholder when empty and let the small vertical padding
                                // drive a compact one-line height (web parity) instead of the
                                // Material TextField's fixed ~56dp minimum.
                                Box(
                                    contentAlignment = Alignment.CenterStart,
                                    modifier = Modifier.padding(vertical = 4.dp),
                                ) {
                                    if (textFieldValue.text.isEmpty()) {
                                        // Replying flips the placeholder to the target
                                        // author (web parity).
                                        val replyAuthor = replyingToMessage?.let { msg ->
                                            replyingToMetadata?.displayName
                                                ?: replyingToMetadata?.name
                                                ?: (msg.pubkey.take(8) + "...")
                                        }
                                        Text(
                                            if (replyAuthor != null) {
                                                "Reply to $replyAuthor"
                                            } else {
                                                "Message ${groupName ?: selectedChannel}"
                                            },
                                            style = NostrordTypography.InputPlaceholder,
                                            color = NostrordColors.TextMuted,
                                        )
                                    }
                                    innerTextField()
                                }
                            },
                        )

                        if (showEmojiButton) {
                            val emojiInteraction = remember { MutableInteractionSource() }
                            val emojiHovered by emojiInteraction.collectIsHoveredAsState()
                            IconButton(
                                onClick = {
                                    showEmojiPicker = !showEmojiPicker
                                    if (showEmojiPicker) showMentionPopup = false
                                },
                                interactionSource = emojiInteraction,
                                modifier = Modifier
                                    .size(width = 26.dp, height = 32.dp)
                                    .pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Icon(
                                    Icons.Outlined.EmojiEmotions,
                                    contentDescription = "Emoji picker",
                                    // Hover brightens to TextContent (web .composer-btn:hover).
                                    tint = when {
                                        showEmojiPicker -> NostrordColors.Primary
                                        emojiHovered -> NostrordColors.TextContent
                                        else -> NostrordColors.TextMuted
                                    },
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }

                        IconButton(
                            onClick = { submit() },
                            // Disabled while a paste upload finishes (its URL must land in
                            // the draft first), but the spinner now shows on the attach icon.
                            enabled = textFieldValue.text.isNotBlank() && !isSending && !isUploadingPaste,
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
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send message",
                                    tint = if (textFieldValue.text.isNotBlank()) {
                                        NostrordColors.Primary
                                    } else {
                                        NostrordColors.TextMuted
                                    },
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }

                // Desktop / web / iOS keep the floating Popup (with keyboard nav).
                // Android renders the list inline above (see top of this Column) to
                // avoid the IME-restart character drop caused by the Popup window.
                if (!isAndroid && showMentionPopup && groupMembers.isNotEmpty()) {
                    val density = LocalDensity.current
                    val filteredCount = getFilteredMembers(groupMembers, mentionQuery).size
                    val popupHeightDp = 28 + 2 + (filteredCount.coerceAtMost(8) * 36)
                    val popupHeightPx = with(density) { popupHeightDp.dp.roundToPx() }
                    val offsetXPx = with(density) { Spacing.lg.roundToPx() }

                    // dismissOnClickOutside must stay false: on Android each soft-keyboard
                    // key tap is delivered as an outside touch and would otherwise close the
                    // popup on every character. Tap-to-dismiss is handled by the clickable Box.
                    Popup(
                        alignment = Alignment.Center,
                        onDismissRequest = { showMentionPopup = false },
                        properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Transparent)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { showMentionPopup = false },
                                ),
                        )
                    }

                    Popup(
                        alignment = Alignment.TopStart,
                        offset = IntOffset(x = offsetXPx, y = -popupHeightPx),
                        onDismissRequest = { showMentionPopup = false },
                        properties = PopupProperties(focusable = false, dismissOnClickOutside = false, dismissOnBackPress = true),
                    ) {
                        MentionPopup(
                            members = groupMembers,
                            query = mentionQuery,
                            selectedIndex = mentionSelectedIndex,
                            onMemberSelect = { handleMemberSelect(it) },
                        )
                    }
                }

                if (!isAndroid && showGroupMentionPopup && availableGroups.isNotEmpty()) {
                    val density = LocalDensity.current
                    val filteredCount = getFilteredGroups(availableGroups, groupMentionQuery).size
                    val popupHeightDp = 28 + 2 + (filteredCount.coerceAtMost(8) * 36)
                    val popupHeightPx = with(density) { popupHeightDp.dp.roundToPx() }
                    val offsetXPx = with(density) { Spacing.lg.roundToPx() }

                    Popup(
                        alignment = Alignment.Center,
                        onDismissRequest = { showGroupMentionPopup = false },
                        properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Transparent)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { showGroupMentionPopup = false },
                                ),
                        )
                    }

                    Popup(
                        alignment = Alignment.TopStart,
                        offset = IntOffset(x = offsetXPx, y = -popupHeightPx),
                        onDismissRequest = { showGroupMentionPopup = false },
                        properties = PopupProperties(focusable = false, dismissOnClickOutside = false, dismissOnBackPress = true),
                    ) {
                        GroupMentionPopup(
                            groups = availableGroups,
                            query = groupMentionQuery,
                            selectedIndex = groupMentionSelectedIndex,
                            onGroupSelect = { handleGroupSelect(it) },
                        )
                    }
                }

                if (showEmojiPicker) {
                    Popup(
                        alignment = Alignment.Center,
                        onDismissRequest = {
                            showEmojiPicker = false
                            focusRequester.requestFocus()
                        },
                        properties = PopupProperties(
                            focusable = true,
                            dismissOnClickOutside = false,
                            dismissOnBackPress = true,
                        ),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        showEmojiPicker = false
                                        focusRequester.requestFocus()
                                    },
                                ),
                        ) {
                            EmojiPicker(
                                onEmojiSelect = { emoji ->
                                    val text = textFieldValue.text
                                    val cursor = textFieldValue.selection.start
                                    val newText = text.substring(0, cursor) + emoji + text.substring(cursor)
                                    val newCursor = cursor + emoji.length
                                    textFieldValue = TextFieldValue(newText, TextRange(newCursor))
                                },
                                onDismiss = {
                                    showEmojiPicker = false
                                    focusRequester.requestFocus()
                                },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = Spacing.lg, bottom = 56.dp),
                            )
                        }
                    }
                }
                // Footer pill below the composer: discoverability cue + click toggle
                // (web .composer-hint-footer). Hidden on a phone in landscape to save the row.
                if (!(isAndroid && isLandscape)) {
                    ComposerHintFooter(
                        showHints = showHints,
                        isTouch = isAndroid,
                        onToggle = { showHints = !showHints },
                    )
                }
            }
        }

        PasteMediaEffect(
            onMediaPasted = { bytes, filename ->
                if (!isUploadingPaste) {
                    isUploadingPaste = true
                    scope.launch { handlePastedMedia(bytes, filename) }
                }
            },
            onError = { pasteError = it },
        )

        ShareMediaEffect(
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
}

/** A small monospace key cap, matching the web `kbd` chips in the composer hints. */
@Composable
private fun KbdChip(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(NostrordColors.BackgroundDark)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            label,
            color = NostrordColors.TextSecondary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * Shortcuts / mention-triggers card shown above the composer, opened by Ctrl+/ or
 * the footer pill (web .composer-hints). Touch shows only the mention triggers
 * (Enter is a newline there); desktop adds the Enter / Shift+Enter send shortcuts.
 */
@Composable
private fun ComposerHints(isTouch: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(NostrordShapes.shapeMedium)
            .background(NostrordColors.Surface)
            .border(1.dp, NostrordColors.Divider, NostrordShapes.shapeMedium)
            .padding(Spacing.xs),
    ) {
        Text(
            if (isTouch) "MENTIONS" else "SHORTCUTS",
            color = NostrordColors.TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp),
        )
        val rows = buildList {
            if (!isTouch) {
                add("Enter" to "send")
                add("Shift + Enter" to "new line")
            }
            add("@" to "mention a person")
            add("%" to "mention a group")
        }
        rows.forEach { (key, desc) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            ) {
                KbdChip(key)
                Text(desc, color = NostrordColors.TextSecondary, fontSize = 13.sp)
            }
        }
    }
}

/** Discoverability cue below the composer, clickable to toggle the hints. */
@Composable
private fun ComposerHintFooter(showHints: Boolean, isTouch: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = Spacing.xs)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onToggle)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = Spacing.xs, vertical = 2.dp),
    ) {
        when {
            showHints && !isTouch -> {
                Text("Press ", color = NostrordColors.TextMuted, fontSize = 11.sp)
                KbdChip("Esc")
                Text(" to close", color = NostrordColors.TextMuted, fontSize = 11.sp)
            }
            showHints -> {
                Text("Tap outside to close", color = NostrordColors.TextMuted, fontSize = 11.sp)
            }
            isTouch -> {
                Text("Tap to see mention triggers", color = NostrordColors.TextMuted, fontSize = 11.sp)
            }
            else -> {
                Text("Press ", color = NostrordColors.TextMuted, fontSize = 11.sp)
                KbdChip("Ctrl + /")
                Text(" to see shortcuts", color = NostrordColors.TextMuted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ReplyingToBar(
    message: NostrGroupClient.NostrMessage,
    metadata: UserMetadata?,
    userMetadata: Map<String, UserMetadata>,
    onCancelReply: () -> Unit,
) {
    val authorName = metadata?.displayName
        ?: metadata?.name
        ?: message.pubkey.take(8) + "..."

    LaunchedEffect(message.content) {
        val pubkeysToFetch = org.nostr.nostrord.ui.components.chat.extractPubkeysFromContent(message.content)
            .filter { !userMetadata.containsKey(it) }
            .toSet()
        if (pubkeysToFetch.isNotEmpty()) {
            org.nostr.nostrord.di.AppModule.nostrRepository.requestUserMetadata(pubkeysToFetch)
        }
    }

    val processedContent = remember(message.content, userMetadata) {
        org.nostr.nostrord.ui.components.chat.processMentionsInContent(message.content, userMetadata)
            .replace('\n', ' ')
    }

    // Web .composer-reply: one-line chip - reply icon, "Replying to", author,
    // inline ellipsized preview, close - over a slight darkening of the pill,
    // separated from the rows below by a hairline.
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.1f))
                .padding(horizontal = Spacing.md, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Reply,
                contentDescription = null,
                tint = NostrordColors.TextMuted,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "Replying to",
                color = NostrordColors.TextMuted,
                style = NostrordTypography.Caption,
            )
            Text(
                text = authorName,
                color = NostrordColors.TextSecondary,
                style = NostrordTypography.Caption,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            if (processedContent.isNotBlank()) {
                Text(
                    text = processedContent,
                    color = NostrordColors.TextMuted,
                    style = NostrordTypography.Caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            IconButton(
                onClick = onCancelReply,
                modifier = Modifier
                    .size(24.dp)
                    .pointerHoverIcon(PointerIcon.Hand),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Cancel reply",
                    tint = NostrordColors.TextMuted,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(NostrordColors.BackgroundFloating),
        )
    }
}

private val emojiRegex = Regex(
    "[" +
        "\u00A9\u00AE" +
        "\u200D" +
        "\u203C\u2049" +
        "\u2122\u2139" +
        "\u2194-\u21AA" +
        "\u231A-\u23FF" +
        "\u2460-\u24FF" +
        "\u25AA-\u27BF" +
        "\u2934-\u2935" +
        "\u2B05-\u2B55" +
        "\u3030\u303D\u3297\u3299" +
        "\uD83C\uDC04-\uD83D\uDEFF" +
        "\uD83E\uDD00-\uD83E\uDDFF" +
        "\uD83E\uDE00-\uD83E\uDEFF" +
        "\uD83C\uDDE6-\uD83C\uDDFF" +
        "\uD83C\uDF00-\uD83C\uDFFF" +
        "\uD83D\uDE00-\uD83D\uDE4F" +
        "\uD83D\uDE80-\uD83D\uDEFF" +
        "\uFE0E\uFE0F" +
        "\u20E3" +
        "]+",
)

private class MentionVisualTransformation(
    private val mentionedNames: Set<String>,
    private val mentionColor: Color,
    private val emojiFontFamily: FontFamily? = null,
    private val groupMentionedNames: Set<String> = emptySet(),
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val builder = AnnotatedString.Builder(text)

        fun highlight(prefix: String, name: String) {
            val mentionText = "$prefix$name"
            var startIndex = 0
            while (true) {
                val index = text.text.indexOf(mentionText, startIndex)
                if (index == -1) break
                builder.addStyle(SpanStyle(color = mentionColor), index, index + mentionText.length)
                startIndex = index + mentionText.length
            }
        }

        mentionedNames.forEach { highlight("@", it) }
        groupMentionedNames.forEach { highlight("%", it) }

        if (emojiFontFamily != null) {
            emojiRegex.findAll(text.text).forEach { match ->
                builder.addStyle(
                    SpanStyle(fontFamily = emojiFontFamily),
                    match.range.first,
                    match.range.last + 1,
                )
            }
        }

        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}

/**
 * Markdown toolbar row (prototype Composer FmtBtns, web .composer-toolbar parity).
 * Tokens match what the message renderer understands: *bold*, _italic_, `code`,
 * ``` blocks; quote/lists are plain-text conventions. Strikethrough and spoiler
 * wait on renderer support (rendered disabled).
 */
@Composable
private fun ComposerToolbar(
    onWrap: (pre: String, post: String) -> Unit,
    onList: (ordered: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        FmtIconButton(Icons.Default.FormatBold, "Bold") { onWrap("*", "*") }
        FmtIconButton(Icons.Default.FormatItalic, "Italic") { onWrap("_", "_") }
        FmtIconButton(Icons.Default.FormatStrikethrough, "Strikethrough") { onWrap("~~", "~~") }
        FmtIconButton(Icons.Default.Code, "Code") { onWrap("`", "`") }
        FmtIconButton(Icons.Default.DataObject, "Code block") { onWrap("```\n", "\n```") }
        FmtIconButton(Icons.Default.FormatQuote, "Quote") { onWrap("> ", "") }
        FmtIconButton(Icons.AutoMirrored.Filled.FormatListBulleted, "Bulleted list") { onList(false) }
        FmtIconButton(Icons.Default.FormatListNumbered, "Numbered list") { onList(true) }
        FmtIconButton(Icons.Default.VisibilityOff, "Spoiler") { onWrap("||", "||") }
        Spacer(modifier = Modifier.weight(1f))
        FmtIconButton(Icons.Default.Close, "Close formatting (Esc)") { onClose() }
    }
    HorizontalDivider(color = NostrordColors.BackgroundFloating, thickness = 1.dp)
}

@Composable
private fun FmtIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(28.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = NostrordColors.TextSecondary,
            modifier = Modifier.size(16.dp),
        )
    }
}
