package org.nostr.nostrord.ui.screens.group.components

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.nostr.nostrord.ui.components.buttons.AppButton
import org.nostr.nostrord.ui.components.buttons.AppButtonVariant
import org.nostr.nostrord.ui.components.chat.MentionSuggestions
import org.nostr.nostrord.ui.components.chat.groupMatches
import org.nostr.nostrord.ui.components.chat.handleKeyEvent
import org.nostr.nostrord.ui.components.chat.memberMatches
import org.nostr.nostrord.ui.components.chat.rememberMentionFieldState
import org.nostr.nostrord.ui.components.forms.AppField
import org.nostr.nostrord.ui.components.upload.MessageUploadButton
import org.nostr.nostrord.ui.screens.group.model.GroupInfo
import org.nostr.nostrord.ui.screens.group.model.MemberInfo
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.ui.theme.rememberEmojiFontFamily

/**
 * Compose-a-new-thread dialog (kind:11 root): native mirror of the web CreateThreadModal. Title is
 * the thread headline (required), then the body, which takes the same `@user` / `%group`
 * autocomplete as the chat and reply composers. Logic stays in
 * [org.nostr.nostrord.ui.screens.group.ThreadsViewModel]; this is pure UI and reports back through
 * [onCreate], which receives the chosen mentions so the caller can resolve them at publish.
 */
@Composable
fun CreateThreadDialog(
    onDismiss: () -> Unit,
    onCreate: (
        title: String,
        content: String,
        shareToChat: Boolean,
        mentions: Map<String, String>,
        groupMentions: Map<String, GroupInfo>,
    ) -> Unit,
    members: List<MemberInfo> = emptyList(),
    availableGroups: List<GroupInfo> = emptyList(),
) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf(TextFieldValue("")) }
    var mentions by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var groupMentions by remember { mutableStateOf<Map<String, GroupInfo>>(emptyMap()) }

    val emojiFontFamily = rememberEmojiFontFamily()
    val mentionVisualTransformation = remember(mentions.keys, groupMentions.keys, emojiFontFamily) {
        MentionVisualTransformation(
            mentionedNames = mentions.keys,
            mentionColor = NostrordColors.MentionText,
            emojiFontFamily = emojiFontFamily,
            groupMentionedNames = groupMentions.keys,
        )
    }

    val mentionState = rememberMentionFieldState()
    val memberMatches = mentionState.memberMatches(members)
    val groupMatches = mentionState.groupMatches(availableGroups)

    fun selectMember(member: MemberInfo) {
        body = mentionState.apply(body, member.displayName) ?: return
        mentions = mentions + (member.displayName to member.pubkey)
    }

    fun selectGroup(group: GroupInfo) {
        body = mentionState.apply(body, group.name) ?: return
        groupMentions = groupMentions + (group.name to group)
    }

    // Default on: announcing the new thread in chat is the common case; one click opts out.
    var shareToChat by remember { mutableStateOf(true) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier =
                Modifier
                    // Cap BEFORE fillMaxWidth: fillMaxWidth fixes min=max, so a later
                    // widthIn(max) cannot shrink it and the dialog spanned the desktop window.
                    .widthIn(max = 480.dp)
                    .fillMaxWidth(0.92f)
                    .padding(Spacing.lg)
                    // Absorb clicks so tapping the card doesn't fall through to the scrim.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                shape = NostrordShapes.shapeLarge,
                color = NostrordColors.Surface,
            ) {
                Column(modifier = Modifier.padding(Spacing.lg)) {
                    Text("New thread", color = NostrordColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(Spacing.xs))
                    Text("Start a new discussion in this group.", color = NostrordColors.TextMuted, fontSize = 13.sp)

                    Spacer(Modifier.height(Spacing.md))
                    Text("Title", color = NostrordColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(Spacing.xs))
                    AppField(value = title, onValueChange = { title = it }, placeholder = "Thread title", modifier = Modifier.fillMaxWidth())

                    Spacer(Modifier.height(Spacing.md))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Content", color = NostrordColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        // Attach media to the thread body (rendered inline like chat).
                        MessageUploadButton(
                            onUploadComplete = { result ->
                                val current = body.text
                                val appended = if (current.isBlank()) result.url else "$current ${result.url}"
                                body = TextFieldValue(appended, TextRange(appended.length))
                            },
                        )
                    }
                    Spacer(Modifier.height(Spacing.xs))
                    AppField(
                        value = body,
                        onValueChange = {
                            body = it
                            mentionState.onValueChange(it)
                        },
                        placeholder = "Start a discussion...",
                        modifier = Modifier
                            .fillMaxWidth()
                            .onPreviewKeyEvent { event ->
                                mentionState.handleKeyEvent(event, maxOf(memberMatches.size, groupMatches.size)) {
                                    memberMatches.getOrNull(mentionState.selectedIndex)?.let { selectMember(it) }
                                        ?: groupMatches.getOrNull(mentionState.selectedIndex)?.let { selectGroup(it) }
                                }
                            },
                        singleLine = false,
                        minLines = 4,
                        visualTransformation = mentionVisualTransformation,
                    )

                    // Inline below the field: a floating Popup inside a Dialog lands in its own
                    // window and would sit behind the scrim on desktop.
                    if (memberMatches.isNotEmpty() || groupMatches.isNotEmpty()) {
                        Spacer(Modifier.height(Spacing.xs))
                        MentionSuggestions(
                            state = mentionState,
                            members = members,
                            groups = availableGroups,
                            onMemberSelect = { selectMember(it) },
                            onGroupSelect = { selectGroup(it) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Spacer(Modifier.height(Spacing.sm))
                    // Announce the new thread in the group chat (kind:9 with the root's nevent).
                    Row(
                        modifier = Modifier.clickable { shareToChat = !shareToChat },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = shareToChat, onCheckedChange = { shareToChat = it })
                        Text("Share to chat", color = NostrordColors.TextSecondary, fontSize = 13.sp)
                    }

                    Spacer(Modifier.height(Spacing.md))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End)) {
                        AppButton(text = "Cancel", onClick = onDismiss, variant = AppButtonVariant.Ghost)
                        AppButton(
                            text = "Publish thread",
                            onClick = {
                                onCreate(title, body.text, shareToChat, mentions, groupMentions)
                                onDismiss()
                            },
                            enabled = title.isNotBlank() && body.text.isNotBlank(),
                        )
                    }
                }
            }
        }
    }
}
