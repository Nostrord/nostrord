package org.nostr.nostrord.ui.components.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.nostr.nostrord.ui.mentions.MentionAutocomplete
import org.nostr.nostrord.ui.mentions.MentionCtx
import org.nostr.nostrord.ui.screens.group.components.GroupMentionPopup
import org.nostr.nostrord.ui.screens.group.components.MentionPopup
import org.nostr.nostrord.ui.screens.group.components.getFilteredGroups
import org.nostr.nostrord.ui.screens.group.components.getFilteredMembers
import org.nostr.nostrord.ui.screens.group.model.GroupInfo
import org.nostr.nostrord.ui.screens.group.model.MemberInfo

/**
 * The `@user` / `%group` autocomplete attached to one text field: which mention is being typed and
 * which suggestion is highlighted. Lives next to the field's [TextFieldValue]; the field feeds it
 * through [onValueChange] and asks it to rewrite the text through [apply].
 *
 * Only one trigger is ever active at a caret, so a single context covers both kinds.
 */
@Stable
class MentionFieldState {
    var ctx by mutableStateOf<MentionCtx?>(null)
        private set

    var selectedIndex by mutableStateOf(0)
        private set

    val trigger: Char? get() = ctx?.trigger

    val query: String get() = ctx?.query.orEmpty()

    val isActive: Boolean get() = ctx != null

    /** Re-track the mention after a text edit; a changed query resets the highlighted row. */
    fun onValueChange(value: TextFieldValue) {
        val next = MentionAutocomplete.track(value.text, value.selection.start, ctx)
        if (next?.query != ctx?.query) selectedIndex = 0
        ctx = next
    }

    fun dismiss() {
        ctx = null
        selectedIndex = 0
    }

    fun moveSelection(delta: Int, matchCount: Int) {
        if (matchCount <= 0) return
        selectedIndex = (selectedIndex + delta).coerceIn(0, matchCount - 1)
    }

    /** [value] with the typed token replaced by [label], caret after it. Null when idle. */
    fun apply(value: TextFieldValue, label: String): TextFieldValue? {
        val active = ctx ?: return null
        val inserted = MentionAutocomplete.insert(value.text, active, label)
        dismiss()
        return TextFieldValue(inserted.text, TextRange(inserted.cursor))
    }
}

@Composable
fun rememberMentionFieldState(): MentionFieldState = remember { MentionFieldState() }

/** Header + divider of a suggestion list, in dp: lets a floating popup offset itself above a field. */
internal const val MENTION_POPUP_HEADER_DP = 30

/** One suggestion row, in dp. */
internal const val MENTION_POPUP_ROW_DP = 36

/** Members offered for the active `@` mention; empty while a `%` (or no) mention is being typed. */
fun MentionFieldState.memberMatches(members: List<MemberInfo>): List<MemberInfo> = if (trigger == MentionAutocomplete.USER) getFilteredMembers(members, query) else emptyList()

/** Groups offered for the active `%` mention; empty while a `@` (or no) mention is being typed. */
fun MentionFieldState.groupMatches(groups: List<GroupInfo>): List<GroupInfo> = if (trigger == MentionAutocomplete.GROUP) getFilteredGroups(groups, query) else emptyList()

/**
 * Popup keyboard handling for a composer: Esc closes, arrows move the highlight, Enter/Tab confirm.
 * Returns true when the key was consumed, so the field's own Enter-sends stays out of the way while
 * a suggestion list is open.
 */
fun MentionFieldState.handleKeyEvent(
    event: KeyEvent,
    matchCount: Int,
    onConfirm: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown || !isActive) return false
    return when (event.key) {
        Key.Escape -> {
            dismiss()
            true
        }
        Key.DirectionUp -> if (matchCount > 0) {
            moveSelection(-1, matchCount)
            true
        } else {
            false
        }
        Key.DirectionDown -> if (matchCount > 0) {
            moveSelection(1, matchCount)
            true
        } else {
            false
        }
        Key.Enter, Key.Tab -> if (matchCount > 0) {
            onConfirm()
            true
        } else {
            false
        }
        else -> false
    }
}

/**
 * The suggestion list for whichever mention is active: members for `@`, groups for `%`, nothing
 * when idle. Placement is the caller's (inline above the field on Android, a floating Popup
 * elsewhere), so this renders only the list itself.
 */
@Composable
fun MentionSuggestions(
    state: MentionFieldState,
    members: List<MemberInfo>,
    groups: List<GroupInfo>,
    onMemberSelect: (MemberInfo) -> Unit,
    onGroupSelect: (GroupInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state.trigger) {
        MentionAutocomplete.USER -> MentionPopup(
            members = members,
            query = state.query,
            selectedIndex = state.selectedIndex,
            onMemberSelect = onMemberSelect,
            modifier = modifier,
        )
        MentionAutocomplete.GROUP -> GroupMentionPopup(
            groups = groups,
            query = state.query,
            selectedIndex = state.selectedIndex,
            onGroupSelect = onGroupSelect,
            modifier = modifier,
        )
        else -> Unit
    }
}
