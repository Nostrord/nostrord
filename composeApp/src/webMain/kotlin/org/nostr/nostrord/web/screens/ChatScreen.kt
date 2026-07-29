package org.nostr.nostrord.web.screens

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.nostr.nostrord.auth.ActiveAccountManager
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.network.managers.GroupLoadingState
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.network.upload.UploadResult
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.nostr.Nip57
import org.nostr.nostrord.nostr.Nip68
import org.nostr.nostrord.ui.components.emoji.QuickReactions
import org.nostr.nostrord.ui.keyboard.VirtualKeyboardPolicy
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.screens.group.GroupMembership
import org.nostr.nostrord.ui.screens.group.GroupViewModel
import org.nostr.nostrord.ui.screens.group.MentionableGroup
import org.nostr.nostrord.ui.screens.group.ReactionErrorKind
import org.nostr.nostrord.ui.screens.group.classifyReactionError
import org.nostr.nostrord.ui.screens.group.pluralizeSystemAction
import org.nostr.nostrord.ui.scroll.ChatScrollPolicy
import org.nostr.nostrord.ui.scroll.JumpPillTarget
import org.nostr.nostrord.utils.ChatSearch
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.epochSeconds
import org.nostr.nostrord.utils.formatDateTime
import org.nostr.nostrord.utils.formatTime
import org.nostr.nostrord.utils.formatTimestamp
import org.nostr.nostrord.utils.normalizeForSearch
import org.nostr.nostrord.utils.normalizeRelayUrl
import org.nostr.nostrord.utils.shortNpub
import org.nostr.nostrord.web.bridge.VirtualKeyboard
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.AvatarKind
import org.nostr.nostrord.web.components.ChatAudio
import org.nostr.nostrord.web.components.ChatImage
import org.nostr.nostrord.web.components.ChatMessageList
import org.nostr.nostrord.web.components.ChatVideo
import org.nostr.nostrord.web.components.EmojiPicker
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.Spoiler
import org.nostr.nostrord.web.components.UploadButton
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.WebZapController
import org.nostr.nostrord.web.components.YouTubeEmbed
import org.nostr.nostrord.web.components.confirmDialog
import org.nostr.nostrord.web.components.copyToClipboard
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.memberSkeleton
import org.nostr.nostrord.web.components.messageSendStatus
import org.nostr.nostrord.web.components.messageSkeleton
import org.nostr.nostrord.web.components.searchInput
import org.nostr.nostrord.web.components.sendStateIcon
import org.nostr.nostrord.web.components.uploadBlob
import org.nostr.nostrord.web.components.useEscClose
import org.nostr.nostrord.web.components.zapBadge
import org.nostr.nostrord.web.modals.AddMemberModal
import org.nostr.nostrord.web.modals.GroupInfoModal
import org.nostr.nostrord.web.modals.GroupInviteModal
import org.nostr.nostrord.web.modals.InviteCodesModal
import org.nostr.nostrord.web.modals.JoinWithCodeModal
import org.nostr.nostrord.web.modals.ManageGroupModal
import org.nostr.nostrord.web.modals.ReportUserModal
import org.nostr.nostrord.web.modals.ShareGroupModal
import org.nostr.nostrord.web.modals.UserProfileModal
import org.nostr.nostrord.web.navigation.pushRoute
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.br
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.code
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.em
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.kbd
import react.dom.html.ReactHTML.pre
import react.dom.html.ReactHTML.s
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.strong
import react.dom.html.ReactHTML.textarea
import react.useEffect
import react.useMemo
import react.useRef
import react.useState
import web.cssom.ClassName
import web.dom.ElementId
import web.html.HTMLDivElement
import web.html.HTMLTextAreaElement
import kotlin.math.abs
import org.nostr.nostrord.ui.screens.group.pendingJoinRequests as computePendingJoinRequests

external interface ChatScreenProps : Props {
    var group: GroupMetadata

    /**
     * Relay the route carried; scopes the shared GroupViewModel so same-id groups on
     * two relays don't share membership/metadata state.
     */
    var relayUrl: String?
    var onLeave: () -> Unit

    /** A deep-linked (&e=) message to scroll to + highlight once it's loaded, or null. */
    var scrollToMessageId: String?

    /** Called once the [scrollToMessageId] target has been scrolled into view. */
    var onScrolledToMessage: () -> Unit

    /** Open another group (from a decoded naddr reference): (groupId, relayUrl?). */
    var onNavigateGroup: (String, String?) -> Unit

    /** Opens the groups-sidebar drawer (mobile only — the chat header hamburger). */
    var onOpenDrawer: () -> Unit
}

/**
 * Parent message id of a reply (kind 9 only). Nostrord tags replies with "q"; other clients
 * may use the NIP-10 reply marker ["e", id, relay?, "reply"], which native also honors
 * (getReplyParentId). The plain unmarked "e" fallback is intentionally not used here — it is
 * ambiguous (root vs mention vs inline quote) without the content embed-check native does.
 */
private fun parentMessageOf(message: org.nostr.nostrord.network.NostrGroupClient.NostrMessage): String? {
    if (message.kind != 9) return null
    fun isHexId(s: String) = s.length == 64 && s.all { it.isLetterOrDigit() }
    message.tags.firstOrNull { it.size >= 2 && it[0] == "q" && isHexId(it[1]) }?.let { return it[1] }
    return message.tags
        .firstOrNull { it.size >= 4 && it[0] == "e" && it[3] == "reply" && isHexId(it[1]) }
        ?.get(1)
}

/** Plain-text preview of a parent message for a reply chip/banner: mentions resolved,
 *  newlines flattened, trimmed, and capped at [max] chars. */
private fun replyPreviewText(content: String, userMetadata: Map<String, UserMetadata>, max: Int): String = processMentions(content, userMetadata).replace('\n', ' ').trim().take(max)

/** Reply-preview payload: author name, plain-text body, and the parent event's tags for emoji resolution. */
data class ReplyPreviewData(
    val author: String,
    val content: String,
    val tags: List<List<String>>,
)

private val NOSTR_URI_REGEX =
    Regex(
        """nostr:(npub1[a-z0-9]+|nprofile1[a-z0-9]+|note1[a-z0-9]+|nevent1[a-z0-9]+|naddr1[a-z0-9]+)""",
        RegexOption.IGNORE_CASE,
    )

/**
 * Replace `nostr:` mentions with their @name, [note], [event], [article] or
 * `%groupId` short form. Used in plain-text spans (reply previews, search
 * snippets) where the full pill rendering would be visual overkill.
 *
 * Web counterpart of `processMentionsInContent` in commonMain (which lives
 * inside the uiComposeMain source set and isn't visible from webMain).
 */
private fun processMentions(content: String, userMetadata: Map<String, UserMetadata>): String = NOSTR_URI_REGEX.replace(content) { match ->
    val bech32 = match.groupValues[1]
    runCatching {
        when (val entity = Nip19.decode(bech32)) {
            is Nip19.Entity.Npub -> {
                val name = userMetadata[entity.pubkey]?.let { it.displayName ?: it.name }
                if (!name.isNullOrBlank()) "@$name" else "@" + shortNpub(entity.pubkey)
            }
            is Nip19.Entity.Nprofile -> {
                val name = userMetadata[entity.pubkey]?.let { it.displayName ?: it.name }
                if (!name.isNullOrBlank()) "@$name" else "@" + shortNpub(entity.pubkey)
            }
            is Nip19.Entity.Note -> "[note]"
            is Nip19.Entity.Nevent -> "[event]"
            is Nip19.Entity.Naddr -> if (entity.kind == 39000) "%${entity.identifier}" else "[article]"
            else -> match.value
        }
    }.getOrDefault(match.value)
}

private fun displayName(pubkey: String, meta: UserMetadata?): String = meta?.displayName?.takeIf { it.isNotBlank() }
    ?: meta?.name?.takeIf { it.isNotBlank() }
    ?: shortNpub(pubkey)

/** Active mention being typed in the composer: the trigger (@ or %), its query, and start index. */
private data class MentionCtx(val trigger: Char, val query: String, val start: Int)

/** A single autocomplete suggestion: how to show it, a subtitle, and the `nostr:` ref to insert. */
private data class MentionMatch(
    val label: String,
    val picture: String?,
    val seed: String,
    val group: Boolean,
    val ref: String,
    val sub: String,
)

/**
 * Find the mention being typed at [cursor]: the nearest `@`/`%` that starts a word and runs
 * unbroken (no whitespace) up to the cursor. Returns null when there's no active mention.
 */
private fun detectMention(text: String, cursor: Int): MentionCtx? {
    val before = text.take(cursor)
    val idx = maxOf(before.lastIndexOf('@'), before.lastIndexOf('%'))
    if (idx < 0) return null
    val boundary = idx == 0 || before[idx - 1].isWhitespace()
    if (!boundary) return null
    val query = before.substring(idx + 1)
    if (query.any { it.isWhitespace() }) return null
    return MentionCtx(before[idx], query, idx)
}

/** A mention requested from the profile modal, inserted into the composer draft. */
private data class MentionRequest(val name: String, val pubkey: String, val nonce: Int)

/** A run of composer text, flagged as a resolved @mention (gold) or plain. */
private data class HlSeg(val text: String, val mention: Boolean)

/** Per-message values that depend only on the message (+ group/relay), precomputed once per
 *  message-list change so the row list doesn't re-encode bech32 + re-serialize JSON every render. */
private data class RowMeta(val nevent: String, val eventJson: String, val link: String)

/**
 * Split [text] into plain / mention runs for the composer's colored mirror. Only the literal
 * [tokens] (e.g. "@alice", "%my group") that are resolved mentions are tinted — same rule as
 * native's MentionVisualTransformation (it colors the chosen mentions, not every "@word").
 */
private fun highlightSegments(text: String, tokens: Collection<String>): List<HlSeg> {
    if (text.isEmpty()) return emptyList()
    val colored = BooleanArray(text.length)
    tokens.forEach { token ->
        if (token.isEmpty()) return@forEach
        var i = text.indexOf(token)
        while (i >= 0) {
            for (j in i until i + token.length) colored[j] = true
            i = text.indexOf(token, i + token.length)
        }
    }
    val segs = mutableListOf<HlSeg>()
    var start = 0
    for (k in 1..text.length) {
        if (k == text.length || colored[k] != colored[start]) {
            segs.add(HlSeg(text.substring(start, k), colored[start]))
            start = k
        }
    }
    return segs
}

/** Markdown-toolbar button (prototype FmtBtn). Unsupported tokens render disabled. */
private fun ChildrenBuilder.fmtBtn(ic: Ic, label: String, disabled: Boolean = false, onSelect: () -> Unit) {
    button {
        className = ClassName(if (disabled) "fmt-btn disabled" else "fmt-btn")
        title = if (disabled) "$label (Coming soon)" else label
        this.disabled = disabled
        // Keep the textarea's focus/selection so wrapSelection still sees it.
        onMouseDown = { it.preventDefault() }
        onClick = { if (!disabled) onSelect() }
        icon(ic)
    }
}

private external interface ChatComposerProps : Props {
    var groupId: String
    var groupName: String
    var groupIsOpen: Boolean
    var canPost: Boolean

    /** Group deleted / no longer on the relay: render no composer or join bar. */
    var isOrphaned: Boolean
    var isPending: Boolean
    var members: List<String>
    var allGroups: List<MentionableGroup>
    var userMetadata: Map<String, UserMetadata>
    var relayUrl: String

    /** Relay's NIP-11 pubkey for a given relay URL, used as the naddr author when building a
     *  `%group` mention so the reference carries the mentioned group's own relay, not the chat's. */
    var relayPubkeyOf: (String) -> String?

    /** Id of the message being replied to (null = no reply), plus the parent's display name and a
     *  content preview for the banner (native ReplyingToBar shows both). */
    var replyingToId: String?

    /** Bumped on EVERY reply tap so re-replying to the same message still refocuses
     *  the composer (the id alone doesn't change then). */
    var replyNonce: Int
    var replyParentName: String?
    var replyParentContent: String?
    var onCancelReply: () -> Unit

    /** Cleared after a successful publish so the parent can drop the reply target. */
    var onSent: () -> Unit
    var onJoin: () -> Unit

    /** When the request to join a closed group is pending: the time it was sent (for the
     *  "Requested ..." line) and the action to cancel it (mirrors native MessageInput). */
    var pendingRequestedAtSeconds: Long?
    var onCancelJoinRequest: () -> Unit

    /** True while the member list is still loading for a group we're in our list of: render
     *  neither the composer nor the pending/join state until we know which to show. */
    var membersResolving: Boolean

    /** Mention requested from the profile modal ("Mention" action); nonce dedupes. */
    var mentionRequest: MentionRequest?

    /** The screen's GroupViewModel (shared, not a second instance). */
    var groupVm: GroupViewModel
}

/**
 * Composer — the message input bar (textarea + colored mention mirror, @user / %group autocomplete,
 * emoji picker, paste/drag upload, Send). Split out of [ChatScreen] so that typing only re-renders
 * this subtree: the draft and mention state live here, not at screen level, so each keystroke no
 * longer re-runs ChatScreen's message sort/filter or re-renders the message list.
 */
private val ChatComposer =
    FC<ChatComposerProps> { props ->
        val vm = props.groupVm
        val members = props.members
        val userMetadata = props.userMetadata
        val allGroups = props.allGroups
        val relayUrl = props.relayUrl
        val groupName = props.groupName

        val (draft, setDraft) = useState { "" }
        // Tracks an in-flight sendMessage so we (a) don't double-send if the user
        // mashes Enter, and (b) keep the draft visible until the signer + relay
        // come back — clearing optimistically erased the message when a NIP-07
        // cancel or relay reject killed the publish.
        val (sending, setSending) = useState { false }
        // Active media uploads in flight (paste / drag-and-drop). The send button
        // shows a spinner instead of the Send icon while > 0.
        val (uploadCount, setUploadCount) = useState { 0 }
        // Successful uploads pending attachment. Their NIP-68 imeta tags ride along
        // on the next sendMessage so dimensions / sha256 / video poster propagate
        // (parity with native GroupScreen.pendingUploads).
        val (pendingUploads, setPendingUploads) = useState<List<UploadResult>> { emptyList() }
        // Last upload failure (too large, unsupported, auth/server). Shown in a
        // dialog instead of being swallowed silently, matching the native picker.
        val (uploadError, setUploadError) = useState<String?> { null }
        // Composer emoji picker open state.
        val (emojiOpen, setEmojiOpen) = useState { false }
        // Esc closes the emoji picker (its search input steals focus, so the
        // textarea's own onKeyDown never sees the Escape). Document-level, same
        // as the message reaction picker.
        useEscClose { if (emojiOpen) setEmojiOpen(false) }
        // Active @user / %group mention being typed in the composer.
        val (mention, setMention) = useState<MentionCtx?> { null }
        // displayName -> pubkeyHex for @user mentions chosen from the popup.
        val (mentions, setMentions) = useState<Map<String, String>> { emptyMap() }
        // groupName -> "nostr:naddr…" for %group mentions, resolved into the content at send.
        val (groupMentions, setGroupMentions) = useState<Map<String, String>> { emptyMap() }
        // Highlighted row in the mention popup, driven by ArrowUp/ArrowDown and hover.
        val (mentionSelected, setMentionSelected) = useState { 0 }

        val composerInputRef = useRef<HTMLTextAreaElement>(null)
        val composerHighlightRef = useRef<HTMLDivElement>(null)

        // Per-group draft: restore what was being typed when this group was last open.
        // Runs on group change only (the same component is reused across groups, so the
        // draft state would otherwise leak from one group to the next).
        useEffect(props.groupId) {
            val draftEntry = AppModule.messageDraftStore.get(props.groupId)
            setDraft(draftEntry.text)
            setMentions(draftEntry.mentions)
            setGroupMentions(draftEntry.groupMentions)
        }
        // Persist on change. The store is a plain in-memory map (no re-render), so saving
        // on every keystroke is free. On group switch this effect's deps are still the old
        // group's values, so it does not fire until the load effect above swaps them in.
        useEffect(draft, mentions, groupMentions) {
            AppModule.messageDraftStore.setText(props.groupId, draft)
            AppModule.messageDraftStore.setMentions(props.groupId, mentions)
            AppModule.messageDraftStore.setGroupMentions(props.groupId, groupMentions)
        }

        // Autocomplete suggestions for the active mention (members for @, groups for %).
        val mentionMatches: List<MentionMatch> =
            when (mention?.trigger) {
                '@' -> {
                    val normalizedQuery = mention.query.normalizeForSearch()
                    members.asSequence()
                        .map { pk -> pk to displayName(pk, userMetadata[pk]) }
                        .filter { (_, nm) -> mention.query.isBlank() || nm.normalizeForSearch().contains(normalizedQuery) }
                        .take(6)
                        .map { (pk, nm) ->
                            MentionMatch(
                                nm,
                                userMetadata[pk]?.picture,
                                pk,
                                group = false,
                                ref = "nostr:" + Nip19.encodeNpub(pk),
                                sub = shortNpub(pk) + pk.takeLast(4),
                            )
                        }
                        .toList()
                }
                '%' -> {
                    val normalizedQuery = mention.query.normalizeForSearch()
                    allGroups.asSequence()
                        .filter { mg ->
                            mention.query.isBlank() ||
                                (mg.meta.name ?: mg.meta.id).normalizeForSearch().contains(normalizedQuery)
                        }
                        .take(6)
                        .map { mg ->
                            // Encode the naddr with the group's OWN hosting relay (and that relay's
                            // pubkey), not the current chat relay, so the mention navigates correctly.
                            val gRelayHost = mg.relayUrl.removePrefix("wss://").removePrefix("ws://").trimEnd('/')
                            val ref = "nostr:" + Nip19.encodeNaddr(mg.meta.id, mg.relayUrl, 39000, props.relayPubkeyOf(mg.relayUrl))
                            MentionMatch(mg.meta.name ?: mg.meta.id, mg.meta.picture, mg.meta.id, group = true, ref = ref, sub = gRelayHost)
                        }
                        .toList()
                }
                else -> emptyList()
            }

        // Replace the typed "@query"/"%query" with the chosen suggestion.
        fun insertMention(mm: MentionMatch) {
            val m = mention ?: return
            val cursorEnd = (m.start + 1 + m.query.length).coerceAtMost(draft.length)
            val inserted = if (mm.group) "%${mm.label}" else "@${mm.label}"
            setDraft(draft.take(m.start) + inserted + " " + draft.substring(cursorEnd))
            if (mm.group) {
                setGroupMentions { it + (mm.label to mm.ref) }
            } else {
                setMentions { it + (mm.label to mm.seed) }
            }
            setMention(null)
            setMentionSelected(0)
        }

        fun isMediaMime(type: String?): Boolean = type != null &&
            (type.startsWith("image/") || type.startsWith("video/") || type.startsWith("audio/"))

        // Upload a pasted/dropped file to nostr.build and append the URL to the draft.
        fun handleMediaFile(file: dynamic) {
            setUploadCount { it + 1 }
            launchApp {
                try {
                    when (val r = uploadBlob(file)) {
                        is Result.Success -> {
                            val url = r.data.url
                            setDraft { prev -> if (prev.isBlank()) url else "$prev $url" }
                            setPendingUploads { it + r.data }
                        }
                        is Result.Error -> setUploadError(r.error.message)
                    }
                } finally {
                    setUploadCount { it - 1 }
                }
            }
        }

        fun send() {
            var text = draft.trim()
            if (text.isEmpty() || sending) return
            // Resolve %group mentions to their nostr:naddr inline (native does this at send too);
            // @user mentions are resolved by sendMessage from the mentions map (+ p-tag).
            groupMentions.forEach { (name, ref) -> text = text.replace("%$name", ref) }
            val replyId = props.replyingToId
            // NIP-68 imeta for any media uploaded into this draft (native parity).
            val imetaTags = pendingUploads.map { it.toImetaTag() }
            setSending(true)
            vm.sendMessage(
                content = text,
                channel = null,
                mentions = mentions,
                replyToId = replyId,
                extraTags = imetaTags,
                onSuccess = {
                    setSending(false)
                    // Clear only after publish succeeded so a cancel/reject keeps the draft.
                    setDraft("")
                    setMentions(emptyMap())
                    setGroupMentions(emptyMap())
                    setPendingUploads(emptyList())
                    // Keep the mobile keyboard up and the cursor in the field for the next message.
                    composerInputRef.current?.focus()
                    props.onSent()
                },
                onFailure = { setSending(false) },
            )
        }

        // Focus the composer AND make sure the keyboard comes up: focus() is a
        // no-op when the textarea is already focused with the keyboard dismissed
        // (Android back button), so blur first and let the refocus re-summon the
        // IME. Never blur while the keyboard is up: a refocus outside a user
        // gesture is not guaranteed to bring it back.
        fun focusComposerForTyping() {
            val ta = composerInputRef.current ?: return
            if (!VirtualKeyboard.isOpen && document.asDynamic().activeElement === ta.asDynamic()) {
                ta.blur()
            }
            ta.focus()
        }

        useEffect(props.replyingToId, props.replyNonce) {
            if (props.replyingToId != null) focusComposerForTyping()
        }
        // Insert an @mention requested from the profile modal (prototype behavior).
        val lastMentionNonce = useRef(0)
        useEffect(props.mentionRequest) {
            val req = props.mentionRequest
            if (req != null && req.nonce != lastMentionNonce.current) {
                lastMentionNonce.current = req.nonce
                setDraft { t -> (if (t.isBlank()) "" else t.trimEnd() + " ") + "@${req.name} " }
                setMentions { it + (req.name to req.pubkey) }
                focusComposerForTyping()
            }
        }
        useEffect(draft) {
            val el = composerInputRef.current ?: return@useEffect
            // Reset before reading scrollHeight so the field can shrink when text
            // is deleted — otherwise scrollHeight only grows.
            el.style.height = "auto"
            el.style.height = "${el.scrollHeight}px"
        }

        // Markdown toolbar (prototype Composer): wraps the selection via execCommand
        // so edits join the textarea's NATIVE undo stack (Ctrl+Z works).
        val (toolbarOpen, setToolbarOpen) = useState { false }
        // Hints popup (keyboard shortcuts on desktop, mention triggers on touch).
        // Opened by Ctrl+/ or the footer pill; Esc, typing, or clicking outside
        // closes it. Never triggered by a typed character, so every glyph
        // ("?" included) stays a normal message character.
        val (showHints, setShowHints) = useState { false }
        // Touch-first device: coarse pointer OR no hover. Phones always match at least
        // one; a touchscreen laptop (fine pointer + hover) stays on the keyboard-first
        // behavior (Enter sends, Ctrl+/ shortcut hint).
        val isTouch = window.matchMedia("(pointer: coarse)").matches ||
            window.matchMedia("(hover: none)").matches
        // Click/tap anywhere outside the hints box (or its footer toggle, which would
        // otherwise close-then-reopen) dismisses it. pointerdown covers touch too.
        useEffect(showHints) {
            if (showHints) {
                val handler: (dynamic) -> Unit = { e ->
                    if (e.target?.closest(".composer-hints, .composer-hint-footer") == null) {
                        setShowHints(false)
                    }
                }
                document.asDynamic().addEventListener("pointerdown", handler)
                try {
                    awaitCancellation()
                } finally {
                    document.asDynamic().removeEventListener("pointerdown", handler)
                }
            }
        }

        fun insertAtCursor(replacement: String, caretBack: Int = 0) {
            val ta = composerInputRef.current
            if (ta == null) {
                setDraft { it + replacement }
                return
            }
            ta.focus()
            document.asDynamic().execCommand("insertText", false, replacement)
            if (caretBack > 0) {
                val pos = ((ta.asDynamic().selectionStart as? Int) ?: 0) - caretBack
                ta.asDynamic().setSelectionRange(pos, pos)
            }
        }

        // Wrap the current selection (or cursor) with markdown tokens; the caret
        // lands inside, before the closing token.
        fun wrapSelection(pre: String, post: String = pre) {
            val ta = composerInputRef.current ?: return
            val start = (ta.asDynamic().selectionStart as? Int) ?: 0
            val end = (ta.asDynamic().selectionEnd as? Int) ?: start
            val sel = ta.value.substring(start, end)
            insertAtCursor(pre + sel + post, post.length)
        }

        // List toolbar buttons: prefix the covered line(s) with a marker; clicking
        // again toggles it off (prototype insertList).
        fun insertList(ordered: Boolean) {
            val ta = composerInputRef.current ?: return
            val v = ta.value
            val selStart = (ta.asDynamic().selectionStart as? Int) ?: 0
            val selEnd = (ta.asDynamic().selectionEnd as? Int) ?: selStart
            val start = v.lastIndexOf('\n', selStart - 1) + 1
            var end = v.indexOf('\n', selEnd)
            if (end == -1) end = v.length
            val lines = v.substring(start, end).split('\n')
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
            ta.focus()
            ta.asDynamic().setSelectionRange(start, end)
            document.asDynamic().execCommand("insertText", false, out)
        }

        // Enter inside a list: continue with the next marker; on an empty item, drop
        // the marker (exit the list) instead — so a second Enter ends the list rather
        // than sending the message (prototype continueList).
        fun continueList(): Boolean {
            val ta = composerInputRef.current ?: return false
            val pos = (ta.asDynamic().selectionStart as? Int) ?: return false
            val end = (ta.asDynamic().selectionEnd as? Int) ?: pos
            if (pos != end) return false
            val v = ta.value
            val lineStart = v.lastIndexOf('\n', pos - 1) + 1
            val nextBreak = v.indexOf('\n', pos)
            val lineEnd = if (nextBreak == -1) v.length else nextBreak
            val line = v.substring(lineStart, lineEnd)
            val ul = Regex("^(\\s*)([-*+])\\s+(.*)$").find(line)
            val ol = Regex("^(\\s*)(\\d+)\\.\\s+(.*)$").find(line)
            if (ul == null && ol == null) return false
            val groups = (ul ?: ol)!!.groupValues
            val indent = groups[1]
            val content = if (ul != null) ul.groupValues[3] else ol!!.groupValues[3]
            if (content.isBlank()) {
                // Empty item: remove the marker and exit the list.
                ta.asDynamic().setSelectionRange(lineStart, lineEnd)
                document.asDynamic().execCommand("delete")
                if (indent.isNotEmpty()) document.asDynamic().execCommand("insertText", false, indent)
                return true
            }
            val marker = if (ul != null) "$indent${ul.groupValues[2]} " else "$indent${ol!!.groupValues[2].toInt() + 1}. "
            insertAtCursor("\n" + marker)
            return true
        }

        if (props.membersResolving || props.isOrphaned) {
            // Member list still loading, or the group is gone (orphaned / deleted): render neither
            // the composer nor the pending/join bar (the message area shows the "no longer
            // available" panel instead).
        } else if (!props.canPost) {
            // Not a member — prompt to join (or show pending) instead of the composer.
            div {
                className = ClassName("composer-join")
                if (props.isPending) {
                    div {
                        className = ClassName("composer-pending-text")
                        span {
                            className = ClassName("composer-pending-title")
                            +"Your join request is pending admin approval"
                        }
                        props.pendingRequestedAtSeconds?.let { ts ->
                            span {
                                className = ClassName("composer-pending-time")
                                +"Requested ${formatTimestamp(ts)}"
                            }
                        }
                    }
                    button {
                        className = ClassName("composer-cancel-btn")
                        onClick = { props.onCancelJoinRequest() }
                        +"Cancel request"
                    }
                } else {
                    span { +"Join the group to send messages" }
                    button {
                        className = ClassName("composer-join-btn")
                        onClick = { props.onJoin() }
                        icon(Ic.PersonAdd)
                        span { +(if (!props.groupIsOpen) "Request to Join" else "Join Now") }
                    }
                }
            }
        } else {
            // Prototype Composer: one bordered frame holding the reply chip, the
            // toggleable markdown toolbar and the input row; hints float above it.
            div {
                className = ClassName("composer-area")

                // Every direct child carries a stable key: the hints / mention popups
                // are conditional siblings rendered before the frame, and without keys
                // React reconciles by index and REMOUNTS the frame - the focused
                // textarea included (same failure mode as the chat-header note).
                // Hints box (keyboard shortcuts on desktop, mention triggers on touch,
                // where Enter is a newline), opened by Ctrl+/ or the footer pill.
                if (showHints) {
                    div {
                        key = "composer-hints"
                        className = ClassName("composer-hints")
                        div {
                            className = ClassName("composer-hints-title")
                            +(if (isTouch) "Mentions" else "Shortcuts")
                        }
                        val rows =
                            (
                                if (isTouch) {
                                    emptyList()
                                } else {
                                    listOf("Enter" to "send", "Shift + Enter" to "new line")
                                }
                                ) + listOf("@" to "mention a person", "%" to "mention a group")
                        rows.forEach { (k, v) ->
                            div {
                                className = ClassName("composer-hints-row")
                                kbd { +k }
                                span { +v }
                            }
                        }
                    }
                }

                // Mention popup floats above the frame (anchored to the area, the
                // frame's overflow:hidden would clip it inside the input row).
                if (mention != null && mentionMatches.isNotEmpty()) {
                    div {
                        key = "mention-popup"
                        className = ClassName("mention-popup")
                        div {
                            className = ClassName("mention-header")
                            +(if (mention.trigger == '%') "GROUPS" else "MEMBERS")
                        }
                        val sel = mentionSelected.coerceIn(0, mentionMatches.size - 1)
                        mentionMatches.forEachIndexed { idx, mm ->
                            div {
                                key = mm.ref
                                className = ClassName(if (idx == sel) "mention-row selected" else "mention-row")
                                onMouseDown = { e ->
                                    e.preventDefault()
                                    insertMention(mm)
                                }
                                onMouseEnter = { setMentionSelected(idx) }
                                WebAvatar {
                                    url = mm.picture
                                    seed = mm.seed
                                    this.name = mm.label
                                    kind = if (mm.group) AvatarKind.GROUP else AvatarKind.USER
                                    cls = "mention-avatar"
                                }
                                div {
                                    className = ClassName("mention-text")
                                    span {
                                        className = ClassName("mention-name")
                                        +mm.label
                                    }
                                    span {
                                        className = ClassName("mention-key")
                                        +mm.sub
                                    }
                                }
                            }
                        }
                    }
                }

                div {
                    key = "composer-frame"
                    className = ClassName("composer-frame")

                    // Reply chip pinned to the top of the frame (prototype): the
                    // textarea below keeps its own room and grows independently.
                    val parentName = props.replyParentName
                    if (props.replyingToId != null && parentName != null) {
                        div {
                            key = "composer-reply"
                            className = ClassName("composer-reply")
                            icon(Ic.Reply)
                            span {
                                className = ClassName("composer-reply-label")
                                +"Replying to"
                            }
                            span {
                                className = ClassName("composer-reply-name")
                                +parentName
                            }
                            props.replyParentContent?.takeIf { it.isNotBlank() }?.let { preview ->
                                span {
                                    className = ClassName("composer-reply-text")
                                    +preview
                                }
                            }
                            button {
                                className = ClassName("composer-reply-close")
                                onClick = { props.onCancelReply() }
                                icon(Ic.Close)
                            }
                        }
                    }

                    // Markdown toolbar (toggleable). Tokens match what the message
                    // renderer understands: *bold*, _italic_, `code`, ``` blocks,
                    // ~~strikethrough~~ and ||spoiler||; quote/lists are plain-text
                    // conventions.
                    if (toolbarOpen) {
                        div {
                            key = "composer-toolbar"
                            className = ClassName("composer-toolbar")
                            fmtBtn(Ic.FormatBold, "Bold") { wrapSelection("*") }
                            fmtBtn(Ic.FormatItalic, "Italic") { wrapSelection("_") }
                            fmtBtn(Ic.FormatStrikethrough, "Strikethrough") { wrapSelection("~~") }
                            fmtBtn(Ic.Code, "Code") { wrapSelection("`") }
                            fmtBtn(Ic.DataObject, "Code block") { wrapSelection("```\n", "\n```") }
                            fmtBtn(Ic.FormatQuote, "Quote") { wrapSelection("> ", "") }
                            fmtBtn(Ic.FormatListBulleted, "Bulleted list") { insertList(false) }
                            fmtBtn(Ic.FormatListNumbered, "Numbered list") { insertList(true) }
                            fmtBtn(Ic.VisibilityOff, "Spoiler") { wrapSelection("||") }
                            button {
                                className = ClassName("fmt-btn fmt-close")
                                title = "Close formatting (Esc)"
                                onClick = { setToolbarOpen(false) }
                                icon(Ic.Close)
                            }
                        }
                    }

                    // Input row
                    div {
                        key = "composer-row"
                        className = ClassName("composer")
                        button {
                            className = ClassName(if (toolbarOpen) "composer-btn active" else "composer-btn")
                            title = "Text formatting"
                            onClick = {
                                setToolbarOpen(!toolbarOpen)
                                composerInputRef.current?.focus()
                            }
                            icon(Ic.TextFields)
                        }
                        UploadButton {
                            cls = "composer-btn"
                            icon = Ic.AttachFile
                            // Paste / drag-and-drop uploads run through handleMediaFile and are
                            // tracked by uploadCount; surface their spinner on the attach icon.
                            busy = uploadCount > 0
                            // Count a file-pick upload in uploadCount too, so the send button is
                            // disabled until the picked URL lands in the draft (it isn't sent empty).
                            onBusyChange = { b -> setUploadCount { if (b) it + 1 else it - 1 } }
                            // The native file picker collapses the soft keyboard (no web API keeps it
                            // open while the OS dialog is up). Refocus the composer the instant the
                            // dialog closes, whether a file was chosen or cancelled, so the keyboard
                            // re-opens and the user keeps typing. Fired from the file input's change /
                            // cancel handlers, the closest point to the gesture, so Android re-pops the
                            // keyboard; iOS Safari needs a live gesture and may not.
                            onPickerClosed = { composerInputRef.current?.focus() }
                            onUploaded = { upload ->
                                val url = upload.url
                                setDraft { prev -> if (prev.isBlank()) url else "$prev $url" }
                                setPendingUploads { it + upload }
                            }
                            onError = { setUploadError(it) }
                        }
                        div {
                            className = ClassName("composer-input-wrap")
                            // Colored mirror painted behind the textarea: same text, but with
                            // resolved @user / %group mentions tinted gold (native parity).
                            div {
                                className = ClassName("composer-highlight")
                                ref = composerHighlightRef
                                val tokens = mentions.keys.map { "@$it" } + groupMentions.keys.map { "%$it" }
                                highlightSegments(draft, tokens).forEach { seg ->
                                    if (seg.mention) {
                                        span {
                                            className = ClassName("msg-mention")
                                            +seg.text
                                        }
                                    } else {
                                        +seg.text
                                    }
                                }
                            }
                            textarea {
                                ref = composerInputRef
                                onScroll = {
                                    composerHighlightRef.current?.scrollTop =
                                        composerInputRef.current?.scrollTop ?: 0.0
                                }
                                className = ClassName("composer-input")
                                placeholder =
                                    props.replyParentName?.takeIf { props.replyingToId != null }
                                        ?.let { "Reply to $it" }
                                        ?: "Message $groupName"
                                value = draft
                                // Intentionally NOT readOnly while sending: toggling readOnly on the
                                // focused field dismisses the mobile virtual keyboard. The double-send
                                // guard lives in send() (sending check) and the Send button's disabled.
                                rows = 1
                                onChange = { event ->
                                    val v = event.currentTarget.value
                                    if (showHints) setShowHints(false)
                                    setDraft(v)
                                    val cursor = (event.currentTarget.asDynamic().selectionStart as? Int) ?: v.length
                                    setMention(detectMention(v, cursor))
                                    setMentionSelected(0)
                                }
                                onKeyDown = { event ->
                                    val hasMentions = mention != null && mentionMatches.isNotEmpty()
                                    when {
                                        hasMentions && event.key == "ArrowDown" -> {
                                            event.preventDefault()
                                            setMentionSelected { (it + 1).coerceAtMost(mentionMatches.size - 1) }
                                        }
                                        hasMentions && event.key == "ArrowUp" -> {
                                            event.preventDefault()
                                            setMentionSelected { (it - 1).coerceAtLeast(0) }
                                        }
                                        hasMentions && (event.key == "Enter" || event.key == "Tab") -> {
                                            event.preventDefault()
                                            insertMention(mentionMatches[mentionSelected.coerceIn(0, mentionMatches.size - 1)])
                                        }
                                        mention != null && event.key == "Escape" -> {
                                            event.preventDefault()
                                            setMention(null)
                                        }
                                        showHints && event.key == "Escape" -> {
                                            event.preventDefault()
                                            setShowHints(false)
                                        }
                                        toolbarOpen && event.key == "Escape" -> {
                                            event.preventDefault()
                                            setToolbarOpen(false)
                                        }
                                        props.replyingToId != null && event.key == "Escape" -> {
                                            event.preventDefault()
                                            props.onCancelReply()
                                        }
                                        event.ctrlKey && (event.key == "/" || event.key == "?") -> {
                                            // Ctrl+/ toggles the shortcuts box ("?" accepted too, the
                                            // same physical key shifted). Never a plain typed
                                            // character, so any glyph can be typed and sent.
                                            event.preventDefault()
                                            setShowHints(!showHints)
                                        }
                                        event.key == "Enter" && !event.shiftKey -> {
                                            // Inside a list: continue / exit it, never send. On touch,
                                            // Enter stays a newline (Send is the button).
                                            if (continueList()) {
                                                event.preventDefault()
                                            } else if (!isTouch) {
                                                event.preventDefault()
                                                send()
                                            }
                                        }
                                    }
                                }
                                onBlur = { window.setTimeout({ setMention(null) }, 150) }
                                onPaste = { event ->
                                    val items = event.asDynamic().clipboardData?.items
                                    val count = (items?.length as? Int) ?: 0
                                    for (i in 0 until count) {
                                        val item = items[i]
                                        val type = item.type.unsafeCast<String?>()
                                        if (item.kind == "file" && isMediaMime(type)) {
                                            val file = item.getAsFile()
                                            if (file != null) {
                                                event.preventDefault()
                                                handleMediaFile(file)
                                            }
                                        }
                                    }
                                }
                                onDragOver = { it.preventDefault() }
                                onDrop = { event ->
                                    val files = event.asDynamic().dataTransfer?.files
                                    val count = (files?.length as? Int) ?: 0
                                    if (count > 0) event.preventDefault()
                                    for (i in 0 until count) {
                                        val file = files[i]
                                        val type = file.type.unsafeCast<String?>()
                                        if (isMediaMime(type)) handleMediaFile(file)
                                    }
                                }
                            }
                        }
                        button {
                            // composer-emoji is hidden on touch/mobile (CSS @media pointer:coarse),
                            // where the OS keyboard already provides emoji (native parity).
                            className = ClassName(if (emojiOpen) "composer-btn composer-emoji active" else "composer-btn composer-emoji")
                            onClick = { setEmojiOpen(!emojiOpen) }
                            icon(Ic.EmojiEmotions)
                        }
                        button {
                            val uploading = uploadCount > 0
                            // The upload spinner now lives on the attach icon; the send button
                            // only spins for an in-flight send, but stays disabled while a paste
                            // upload finishes so its URL can land in the draft first.
                            className = ClassName(
                                if (draft.isNotBlank() || sending) "composer-send active" else "composer-send",
                            )
                            disabled = (draft.isBlank() && !uploading) || sending || uploading
                            // Keyboard-neutral tap: keep textarea focus (so the keyboard stays up)
                            // only when it is already up; rules in VirtualKeyboardPolicy.
                            onMouseDown = { e ->
                                val keep = VirtualKeyboardPolicy.keepComposerFocusOnTap(
                                    keyboardOpen = VirtualKeyboard.isOpen,
                                    touchDevice = window.navigator.maxTouchPoints > 0,
                                )
                                if (keep) e.preventDefault()
                            }
                            onClick = { send() }
                            if (sending) {
                                span { className = ClassName("btn-spinner") }
                            } else {
                                icon(Ic.Send)
                            }
                        }
                        if (emojiOpen) {
                            div {
                                className = ClassName("emoji-overlay")
                                onClick = { setEmojiOpen(false) }
                                EmojiPicker {
                                    onPick = { emoji ->
                                        insertAtCursor(emoji)
                                        setEmojiOpen(false)
                                    }
                                }
                            }
                        }
                    }
                }

                div {
                    key = "composer-footer"
                    className = ClassName("composer-hint-footer")
                    // Clickable affordance: toggles the same box.
                    onClick = { setShowHints(!showHints) }
                    when {
                        showHints && !isTouch -> {
                            +"Press "
                            kbd { +"Esc" }
                            +" to close"
                        }
                        showHints -> +"Tap outside to close"
                        isTouch -> +"Tap to see mention triggers"
                        else -> {
                            +"Press "
                            kbd { +"Ctrl + /" }
                            +" to see shortcuts"
                        }
                    }
                }
            }
            uploadError?.let { error ->
                uploadErrorDialog(error) { setUploadError(null) }
            }
        }
    }

/**
 * Chat view — real data port of the Compose GroupScreenDesktop: header + grouped messages
 * (live `messages` flow) + composer (`sendMessage`), members sidebar (`groupMembers` /
 * `groupAdmins`, split into Admins / Members). Opening the group requests its messages and
 * the author/member metadata. Moderation modals are wired; their submits land later.
 */
val ChatScreen =
    FC<ChatScreenProps> { props ->
        val group = props.group
        // Fall back to the group id while metadata is loading, same as the sidebar
        // banner (group-side-banner-name), instead of a generic "Group" placeholder.
        val groupName = group.name?.takeIf { it.isNotBlank() } ?: group.id
        // `vm` owns reads + most actions (shared with Compose). `repo` is kept ONLY for the
        // calls deliberately NOT routed through the VM:
        //   - leaveGroup / deleteGroup: followed immediately by onLeave() (unmount), so they
        //     need the app-lifetime launchApp scope to survive navigation — viewModelScope
        //     would cancel them mid-publish.
        //   - the entry loading/retry orchestration + pending-join poll: suspend calls awaited
        //     in sequence (reading .value between), which the VM's fire-and-forget methods
        //     can't express without a suspend variant.
        // Everything else should go through `vm`.
        val repo = AppModule.nostrRepository
        // Shared screen logic. Keyed by group.id so the VM (and its scope) is recreated
        // when the open group changes while this component stays mounted.
        val vm = useViewModel("${props.relayUrl}|${group.id}") {
            GroupViewModel(AppModule.nostrRepository, group.id, props.relayUrl)
        }

        val messagesByGroup = useStateFlow(vm.messages)
        val messageStatus = useStateFlow(vm.messageStatus)
        val membersByGroup = useStateFlow(vm.groupMembers)
        val adminsByGroup = useStateFlow(vm.groupAdmins)
        val userMetadata = useStateFlow(vm.userMetadata)
        val reactionsByMsg = useStateFlow(vm.reactions)
        val zapsByMsg = useStateFlow(vm.zaps)
        // %group mention candidates: only joined + friends' groups (the new discovery),
        // not every group the relay served.
        val allGroups = useStateFlow(vm.mentionableGroups)
        val relayMetadata = useStateFlow(vm.relayMetadata)
        val relayUrl = useStateFlow(vm.currentRelayUrl)
        val isLoadingMore = useStateFlow(vm.isLoadingMore)[group.id] ?: false
        val hasMore = useStateFlow(vm.hasMoreMessages)[group.id] ?: true
        // Per-group GroupLoadingState. Only the Exhausted state means the relay
        // truly confirmed "no more messages" THIS session — the cached
        // `hasMore == false` from a prior visit doesn't. Used by the empty-
        // state gate below to avoid showing "No messages yet" before the
        // socket has even spoken on this open.
        val groupLoadingState = useStateFlow(vm.groupStates)[group.id]
        // True while the initial read is waiting on NIP-42 AUTH (private group whose relay
        // challenges in response to the read, e.g. opened from the homepage). Keeps skeletons
        // up across the pre-AUTH dance, which briefly bounces the controller through Idle,
        // so the gate never flashes "No messages yet" before an authenticated read.
        val awaitingAuthRead = group.id in useStateFlow(vm.groupsAwaitingAuthRead)
        val connState = useStateFlow(vm.connectionState)
        val membersLoading = group.id in useStateFlow(vm.loadingMembers)
        val myPubkey = vm.getPublicKey()
        // Zaps require signing a kind:9734 request, so only offer them when an account
        // with a usable signer is active.
        val canSign = useStateFlow(ActiveAccountManager.session) != null
        // Re-key effects that fire per-session work (requestGroupMessages,
        // metadata, polling) on activeAccountId too. Without this, switching
        // accounts while the same group is open leaves group.id unchanged, the
        // effects below don't re-run, and the new session never issues its own
        // REQ — chat sits in skeletons forever because groupStates[group.id]
        // never reaches Exhausted (the new GroupManager session has no state
        // for it).
        val activeAccountId = useStateFlow(AppModule.accountStore.activeId)

        val rawMessages = messagesByGroup[group.id].orEmpty()
        // Memoized so the O(n log n) sort + index only re-run when THIS group's messages change,
        // not on every re-render (reactions, zaps, metadata and members each emit separately and
        // would otherwise re-sort the whole list 15+ times during a group switch).
        val messages = useMemo(rawMessages) { rawMessages.sortedBy { it.createdAt } }
        val messagesById = useMemo(messages) { messages.associateBy { it.id } }
        // The relay that actually HOSTS this group (where its kind:39000 lives), which may differ
        // from the relay we're viewing it through. The copied nevent embeds this so readers fetch
        // the event from its real home, not from wherever we happened to be connected.
        val allGroupsByRelay = useStateFlow(AppModule.nostrRepository.groupsByRelay)
        val neventRelay =
            allGroupsByRelay.entries.firstOrNull { it.value.any { g -> g.id == group.id } }?.key ?: relayUrl
        // Pure per-message values (bech32 nevent, the event JSON, the share link): expensive to
        // compute and constant for a message, so build them once per message-list change instead of
        // re-encoding/re-serializing all 200 rows on every re-render.
        val rowMeta =
            useMemo(messages, group.id, relayUrl, neventRelay) {
                val host = relayUrl.removePrefix("wss://").removePrefix("ws://")
                messages.associate { m ->
                    m.id to
                        RowMeta(
                            nevent = Nip19.encodeNevent(m.id, relays = listOf(neventRelay), authorHex = m.pubkey, kind = m.kind),
                            eventJson = eventJsonOf(m),
                            link = "https://nostrord.com/open/?relay=$host&group=${group.id}&e=${m.id}",
                        )
                }
            }
        val members = membersByGroup[group.id].orEmpty()
        val admins = adminsByGroup[group.id].orEmpty().toSet()
        val isAdmin = myPubkey != null && myPubkey in admins
        // Online = posted at least one event in the last 10 minutes. Same heuristic as the
        // native GroupScreen.kt:295 — no presence protocol, just "recently active". Drives the
        // green/grey dot on member avatars in the sidebar.
        val tenMinutesAgo = epochSeconds() - (10 * 60)
        val recentlyActiveMembers =
            messages.asSequence()
                .filter { it.createdAt >= tenMinutesAgo }
                .map { it.pubkey }
                .toSet()
        val isMember = myPubkey != null && myPubkey in members
        // Membership standing (None / Resolving / Pending / Member / Admin) is derived once in the
        // shared GroupViewModel so web and Compose apply identical rules. RESOLVING = joined but the
        // kind:39002 list hasn't landed yet: render NEITHER composer nor pending bar (no flash on a
        // background refresh, which re-fires requestGroupMembers). PENDING also fires from our own
        // outstanding kind:9021 on a closed group, so it shows immediately instead of sitting blank
        // until the member list arrives. Authoritative for BOTH composer and chat/members panels.
        val membership = useStateFlow(vm.membershipState)
        // Pending external add (an admin's kind:9000): the accept/decline prompt below.
        // Dismissal is per group id (the component stays mounted across group switches).
        val pendingInvite = useStateFlow(vm.pendingInvite)
        val inviteActorLabel = useStateFlow(vm.pendingInviteActorLabel)
        val (dismissedInviteGroupId, setDismissedInviteGroupId) = useState<String?>(null)
        // Mirror Compose (remember(groupId)): leaving the group forgets the dismissal, so
        // the prompt returns on the NEXT open. Guarded render-time adjustment.
        if (dismissedInviteGroupId != null && dismissedInviteGroupId != group.id) {
            setDismissedInviteGroupId(null)
        }
        // Access shape for the Private/Closed labels: trusts kind:39000 when present, else infers
        // from the relay's restricted signal (metadata is withheld from non-members). Replaces the
        // raw group.isPublic/isOpen so an outsider sees Private/Closed even before any placeholder.
        val access = useStateFlow(vm.groupAccess)
        val canPost = membership.status == GroupMembership.MEMBER || membership.status == GroupMembership.ADMIN
        // Relay-side member with no entry in our own kind:10009: readable, postable, but in no
        // list and no rail. One tap reconciles them.
        val canAddToMyList = useStateFlow(vm.canAddToMyList)
        val isPendingApproval = membership.status == GroupMembership.PENDING
        val membersResolving = membership.status == GroupMembership.RESOLVING
        val composerPending = isPendingApproval
        // When the request was sent (latest own kind:9021), for the pending bar's "Requested ..." line.
        val pendingRequestedAt = membership.requestedAtSeconds
        // Restricted: relay returned a CLOSED "restricted" frame for this group.
        // Used to render the "Private group" UI in place of skeletons when the
        // account has no read access (NIP-29 private+closed group).
        val restrictedGroups = useStateFlow(vm.restrictedGroups)
        val isGroupRestricted = group.id in restrictedGroups
        // Orphaned: pinned in kind:10009 but the relay served no kind:39000 after its group list
        // finished, i.e. the group was deleted / no longer exists. Drives the "no longer available"
        // panel instead of perpetual loading skeletons.
        val isOrphaned = useStateFlow(vm.isOrphaned)
        // Pending join-request count (admin only) drives the header badge. Shares the Manage >
        // Requests logic (pendingJoinRequests) so the badge and the list never disagree: a member
        // removed by an admin (kind 9001) must not resurface as pending. Open groups count too:
        // some relays leave a kind:9021 pending on a leave + rejoin, and the admin clears it.
        val pendingJoinRequests = if (isAdmin) {
            computePendingJoinRequests(messages, members.toSet()).size
        } else {
            0
        }

        // Members panel: persistent side column on desktop (open by default), slide-over
        // drawer on mobile (closed by default). Same split as the prototype.
        val (membersOpen, setMembersOpen) = useState { window.innerWidth > 1000 }
        val (infoOpen, setInfoOpen) = useState { false }
        val (profilePubkey, setProfilePubkey) = useState<String?> { null }
        val (reportTarget, setReportTarget) = useState<ReportTargetRef?> { null }
        val (replyingToId, setReplyingToId) = useState<String?> { null }
        val (replyNonce, setReplyNonce) = useState { 0 }
        // Mention requested from the profile modal, consumed by the composer.
        val (mentionRequest, setMentionRequest) = useState<MentionRequest?> { null }
        // Members sidebar search query.
        val (memberQuery, setMemberQuery) = useState { "" }
        // The deep-linked message currently flashing (cleared after the highlight animation).
        val (highlightId, setHighlightId) = useState<String?> { null }
        // moderation modal: edit | share | members | addmember | invite | requests | subgroup | children
        val (modal, setModal) = useState<String?> { null }
        // Message id pending delete confirmation. Native pops an AlertDialog
        // first (GroupScreen.kt:523); the web used to call repo.deleteMessage
        // straight from the onDelete callback — one stray click on the
        // overflow menu would wipe the message with no undo.
        val (messageToDelete, setMessageToDelete) = useState<String?> { null }
        // Surface relay-side rejects of the kind:5 (e.g., "blocked: only
        // admins can delete this", "not a member"). Mirrors native's
        // deleteMessageError flow at GroupViewModel.kt:260-275 +
        // GroupScreen.kt:548-562 — the web used to swallow the failure
        // silently so the user thought the message was deleted when it
        // wasn't.
        val deleteError = useStateFlow(vm.deleteMessageError)
        // Relay rejected a kind:7 reaction (e.g., "kind 7 not allowed", or an
        // "unknown member" needing to join). Mirrors native's reactionError flow
        // (GroupViewModel.kt:148-157 + GroupScreen.kt:620-665) — the web used to
        // swallow it, so the reaction just blinked away with no explanation.
        val reactionError = useStateFlow(vm.reactionError)
        // Relay rejected the kind:9021 join request (closed group needing an invite code, auth
        // failure, etc.). Surfaced so tapping Join is never a silent no-op. Mirrors native's
        // join-error dialog and the reactionError flow above.
        val joinError = useStateFlow(vm.joinError)

        // In-chat search (UI-local). matchIds is cheap to recompute per render; the current
        // index walks it with wraparound and drives a scroll command to that row.
        val (searchActive, setSearchActive) = useState { false }
        val (searchQuery, setSearchQuery) = useState { "" }
        // The cursor is anchored to a message id, not a position. "Search older messages" prepends
        // older matches to searchMatchIds (oldest-first order), which would shift a positional index
        // onto a different message; tracking by id keeps the selection on the row the user picked.
        val (anchoredHitId, setAnchoredHitId) = useState<String?> { null }
        // True while paginating older history on demand looking for a match (PR 2).
        val (searchingOlder, setSearchingOlder) = useState { false }
        // Bumped on every prev/next press so re-pressing scrolls back to the current match even when
        // the anchored id does not change (e.g. a single match, or re-centering after scrolling away).
        val (searchScrollNonce, setSearchScrollNonce) = useState { 0 }
        // Anchor index captured when a "search older" dig starts; older matches prepend and push the
        // anchor right, so the loop detects a new older match by the index growing past this.
        val searchOlderBaseline = useRef(0)
        // Pages loaded in the current dig, bounded so one click never drains all history.
        val searchOlderPages = useRef(0)
        // Debounce timer for the search input. The input is UNCONTROLLED (its DOM value is not
        // bound to state) so keystrokes don't re-render ChatScreen — only the debounced commit
        // below updates searchQuery, which is what re-runs the match scan + row highlights.
        val searchDebounce = useRef(0)
        // Memoized so unrelated flow emissions (reactions, zaps, metadata) that re-render
        // ChatScreen don't re-scan every message. Recomputes only when the group's messages
        // change, the committed query changes, or search is toggled.
        //
        // searchTextById enriches each message with what the user SEES: mentions resolved to
        // @names and nevent/note quotes resolved to the referenced note's text (in-memory only).
        // It is the expensive step, so it memoizes on messages + metadata + cache (only while
        // search is open); the cheap per-keystroke match below then reuses it via the extractor.
        val searchCachedEvents = useStateFlow(vm.cachedEvents)
        val searchTextById = useMemo(messagesByGroup[group.id], userMetadata, searchCachedEvents, searchActive) {
            if (!searchActive) {
                emptyMap()
            } else {
                ChatSearch.buildSearchTextById(
                    messages,
                    resolveMention = { pubkey -> userMetadata[pubkey]?.let { it.displayName ?: it.name }?.takeIf(String::isNotBlank) },
                    resolveCachedQuote = { id -> searchCachedEvents[id]?.content },
                )
            }
        }
        // searchTextById is already emptyMap() while inactive and matchingIds returns nothing for a
        // sub-2-char query, so no extra searchActive guard is needed here (parity with native).
        val searchMatchIds = useMemo(searchTextById, searchQuery) {
            ChatSearch.matchingIds(messages, searchQuery) { searchTextById[it.id] ?: it.content }
        }
        // Cursor: anchored hit position, current id, and inverted 1-based display number (1 = newest).
        // indexById makes the anchor lookup O(1) so prev/next (which re-renders ChatScreen each press)
        // never re-scans the match list to resolve the cursor (parity with native).
        val searchIndexById = useMemo(searchMatchIds) { ChatSearch.indexById(searchMatchIds) }
        val searchCursor = ChatSearch.cursor(searchMatchIds, searchIndexById, anchoredHitId)
        val searchClampedIndex = searchCursor.index
        val currentSearchHitId = searchCursor.currentId
        val searchHitIdSet = useMemo(searchMatchIds) { searchMatchIds.toSet() }
        // Lock the anchor onto the resolved hit (the first match for a new query) so later list
        // changes keep the cursor by identity. No-op once they agree, so this can't loop.
        useEffect(currentSearchHitId) {
            if (currentSearchHitId != null && currentSearchHitId != anchoredHitId) setAnchoredHitId(currentSearchHitId)
        }
        fun closeSearch() {
            window.clearTimeout(searchDebounce.current ?: 0)
            setSearchActive(false)
            setSearchQuery("")
            setAnchoredHitId(null)
            setSearchingOlder(false)
        }
        fun gotoPrevMatch() {
            if (searchMatchIds.isNotEmpty()) {
                setAnchoredHitId(searchMatchIds[ChatSearch.step(searchClampedIndex, searchMatchIds.size, -1)])
                setSearchScrollNonce { it + 1 }
            }
        }
        fun gotoNextMatch() {
            if (searchMatchIds.isNotEmpty()) {
                setAnchoredHitId(searchMatchIds[ChatSearch.step(searchClampedIndex, searchMatchIds.size, +1)])
                setSearchScrollNonce { it + 1 }
            }
        }

        // Commit a new query: reset the match cursor and drop any older-history search in progress.
        fun commitQuery(value: String) {
            setSearchQuery(value)
            setAnchoredHitId(null)
            setSearchingOlder(false)
        }
        // Reset search on group switch (parity with native's remember(groupId)). The web ChatScreen
        // instance is reused across groups, so without this the open bar, query and older-search loop
        // would carry into the next group and scan / auto-paginate it under the prior group's intent.
        useEffect(group.id) {
            window.clearTimeout(searchDebounce.current ?: 0)
            setSearchActive(false)
            setSearchQuery("")
            setAnchoredHitId(null)
            setSearchingOlder(false)
        }

        // Scroll/pagination bookkeeping (refs so they don't trigger re-render).
        //
        // Scroll + pagination (the latch, stall detection and scrollHeight-restore)
        // all live inside ChatMessageList now; this screen only mirrors the at-bottom
        // state for the FAB / divider dismissal.
        val atBottom = useRef(true)
        // Commands to the list (ChatMessageList owns the scroll container ref):
        // scrollKey jumps to a row (deep-link / reply), jumpNonce jumps to bottom.
        val (scrollKey, setScrollKey) = useState<String?> { null }
        val (jumpNonce, setJumpNonce) = useState { 0 }
        // Two-stage jump: false until the first tap focuses the "New messages" divider,
        // then the next tap drops to the bottom. Reset per group on entry.
        val dividerSeen = useRef(false)

        // Entering reply mode grows the composer with the reply banner, which can
        // cover the message being replied to. Once the banner has grown (.chat-messages
        // shrinks, it's a flex sibling above .composer-area), scroll that message just
        // into view above the composer (prototype MessageList: scrollIntoView
        // block:'nearest'). 'nearest' only moves the feed if the row is actually
        // covered, so a reply to an already-visible message doesn't jump.
        useEffect(replyingToId) {
            val id = replyingToId ?: return@useEffect
            // Wait a frame for the reply banner to render and the list to reflow.
            delay(48)
            document.getElementById("msg-$id")?.asDynamic()
                ?.scrollIntoView(js("({ block: 'nearest', behavior: 'smooth' })"))
            Unit
        }

        // "New messages" divider snapshot. Captured once on group entry; the group opens
        // at the bottom with the divider above. It is consumed (see consumeDivider) only
        // when the user acknowledges it: scrolls it into view (onDividerVisible), sends a
        // message, or taps the jump pill to the bottom. Reaching the bottom on the entry
        // settle must NOT clear it (issue #83). wasNotAtBottom records that the user has
        // genuinely scrolled away this entry, gating onDividerVisible so the open-at-bottom
        // settle can never wipe the divider unseen.
        val (lastReadSnapshot, setLastReadSnapshot) = useState<Long?> { null }
        val wasNotAtBottom = useRef(false)
        // Latches once the divider has been on screen this entry (reported by onDividerVisible,
        // including at the bottom on open). Leaving the bottom then consumes the line even though
        // a single-unread divider slides off the viewport before that fires — a live-visibility
        // check would miss the overlap and strand it (issue #83).
        val dividerWasOnScreen = useRef(false)
        // Mirror of atBottom for re-renders the FAB needs. The ref stays as the
        // hot-path source of truth for the scroll handler; setAtBottomState is
        // only invoked on the transition so we don't re-render every scroll tick.
        val (atBottomState, setAtBottomState) = useState { true }
        // Unread count for the FAB badge — only counts unread CHAT messages (kind:9) from
        // *other* users, mirroring the divider's own filter (ChatItems.kt:116). Without the
        // kind:9 guard, membership/moderation events (9021 join, 9022 leave, 9000/9001) that the
        // divider deliberately skips would still inflate the count, so a single "hi" plus a join
        // shows "3 new". Updates whenever messages or the snapshot change.
        val unreadCount =
            if (lastReadSnapshot == null) {
                0
            } else {
                messages.count { it.createdAt > lastReadSnapshot && it.pubkey != myPubkey && it.kind == 9 }
            }

        // Consume the "New messages" divider: the user has acknowledged the boundary (sent a
        // message, or scrolled it into view). Re-anchor the snapshot to the newest loaded message
        // so the current divider disappears AND a later arrival re-arms a fresh divider + "N new"
        // count. markAsRead persists it so the divider does not return on the next entry. Mirrors
        // GroupScreen.consumeDivider on native.
        val consumeDivider = {
            setLastReadSnapshot(messages.maxOfOrNull { it.createdAt })
            vm.markAsRead()
        }

        // While following at the bottom after the user engaged the scroll this entry, keep the
        // read snapshot at the newest message. The divider is an entry artifact (consumed by
        // scroll-up, send, or jump); messages watched as they land are already read, so they must
        // not flash a fresh divider above the latest while sitting at the bottom (mirrors GroupScreen).
        useEffect(messages, atBottomState) {
            if (atBottomState && wasNotAtBottom.current == true) {
                messages.maxOfOrNull { it.createdAt }?.let { setLastReadSnapshot(it) }
            }
        }

        // Load messages + author/member metadata when the group (or its rosters)
        // change. Re-firing covers three race windows during account swap:
        //
        //  - activeAccountId flips: applyActiveAccountChange wiped messages,
        //    groupStates, joinedGroups — the new session needs its own REQ.
        //  - isConnected goes false→true: the connectionState transition is the
        //    cleanest "client healthy again" signal. But the state can briefly
        //    stay Connected while reconnect() has already nulled focusedClient
        //    (state is updated by connectFocused, not by disconnect).
        //  - relayUrl flips empty → non-empty: clearCurrentRelay sets the URL
        //    to '' under the swap, then loadSavedRelay restores it. The empty
        //    interval is observable through useStateFlow and is the safest
        //    gate — if relayUrl is blank, there's literally no relay to fetch
        //    from yet, regardless of what connectionState says.
        //
        // Without all three checks the REQ would silently drop into
        // clientForGroup -> null and groupStates stays Idle -> skeleton forever.
        //
        // For OTHER GROUPS specifically (non-joined): G isn't in
        // joinedGroupsByRelay so the mux doesn't auto-subscribe, AND it isn't
        // restored to groupsByRelay from storage on swap, so clientForGroup
        // falls back to the primary client. If that primary's session is mid-
        // settle (post-reconnect AUTH still pending), the REQ may silently
        // no-op (send() on a half-open socket doesn't throw) and the
        // controller is stuck in InitialLoading until the long network
        // timeout. To cover that, retry up to 5x with a 2s gap while the
        // group is still unsettled. CancellationException from the next
        // effect run / unmount tears the loop down.
        val isConnected = connState is ConnectionManager.ConnectionState.Connected
        // Also re-fire on isMember change — when an admin approves a pending
        // join request, the relay broadcasts the updated kind:39002 member
        // list and our reactive members flow flips us to a member. The
        // earlier REQ for this group (run while we were non-member) likely
        // returned empty / restricted — controller is sitting in Exhausted /
        // Error with 0 msgs and won't re-issue on its own. Re-running the
        // effect with isMember as a dep + the force-refresh block below
        // catches that moment and replays the fetch so messages stream in
        // without the user having to leave / re-enter the group.
        // Keyed on identity + isConnected ONLY. relayUrl and isMember were also deps, but they
        // oscillate during a normal open-with-relay-switch (relayUrl: blank -> url, isMember:
        // false -> true), and re-running this body re-issued the WHOLE subscription cluster
        // (msg/meta/members/admins) and tore down the in-flight load. That web-only duplicate
        // -REQ churn made loading slow and starved pagination of a settled HasMore state. Native
        // (GroupScreen) keeps the loader split the same way. The reset below is guarded to run
        // once per group entry; post-approval (isMember) re-fetch is its own effect below.
        val resetForGroup = useRef<String>(null)
        useEffect(group.id, activeAccountId, isConnected) {
            if (!isConnected) return@useEffect
            if (relayUrl.isBlank()) return@useEffect
            // Snapshot the previous read point BEFORE markGroupAsRead persists "now",
            // so the divider can anchor on the user's actual last-read message
            // through the session. Native does the same with remember(groupId).
            setLastReadSnapshot(vm.getLastReadTimestamp())
            wasNotAtBottom.current = false
            dividerSeen.current = false
            dividerWasOnScreen.current = false
            // Reset atBottom to true on group entry. Without this, the ref
            // carries the PREVIOUS group's value across the ChatScreen re-
            // render: if the user was reading mid-feed in group A (atBottom
            // = false) and opens group B, the pin-to-bottom useLayoutEffect
            // sees atBottom=false and skips, leaving B at scrollTop=0 or
            // wherever onScroll's first calculation lands. Forcing true at
            // entry lets the next pin-to-bottom commit fire as the natural
            // 'just opened, go to latest' behaviour. Subsequent user scroll
            // flips it via the onScroll handler as normal.
            atBottom.current = true
            setAtBottomState(true)
            // Preemptive reset: if the controller was already in InitialLoading
            // when we entered, it's almost certainly the stale REQ that
            // resubscribePoolRelay (NostrRepository.kt:2628) fired during the
            // reconnect — that REQ went out on the dying socket and won't get
            // an EOSE. Resetting now lets attempt 0 actually re-issue instead
            // of being a no-op (startInitialLoad rejects when state is
            // already InitialLoading). Skip the reset for legitimate
            // states (HasMore / Paginating) — those mean we already have data.
            //
            // Also reset on Exhausted / Error with zero messages: covers the
            // post-approval case where a previous non-member REQ returned
            // empty (or was refused), and now that the user is a member the
            // relay would serve the chat. Without this reset the controller
            // sits in Exhausted with 0 msgs and never tries again. Safe
            // because the retry loop below exits as soon as state settles
            // (Exhausted with 0 msgs is still settled — we just trigger one
            // extra fetch on relay reconnect of an empty group, which is
            // fine).
            // Force-reset a stale InitialLoading (the REQ resubscribePoolRelay fired on the
            // dying socket) or an empty Exhausted/Error ONCE per group entry, so the first load
            // actually re-issues. Guarded by resetForGroup so a later reconnect / relay tick
            // can't reset + re-issue and churn the relay; a genuinely stuck load is caught by
            // the controller timeout -> Error -> the recovery effect below.
            if (resetForGroup.current != group.id) {
                resetForGroup.current = group.id
                val entryState = repo.groupStates.value[group.id]
                val haveMsgsAtEntry = repo.messages.value[group.id].orEmpty().isNotEmpty()
                val needsForceRefresh = entryState is GroupLoadingState.InitialLoading ||
                    (
                        !haveMsgsAtEntry &&
                            (
                                entryState is GroupLoadingState.Exhausted ||
                                    entryState is GroupLoadingState.Error
                                )
                        )
                if (needsForceRefresh) {
                    repo.resetGroupLoadingState(group.id)
                }
            }
            // Fire the initial read once. requestGroupMessages now waits for NIP-42 AUTH
            // internally (private groups on a remote signer) while the controller stays in
            // InitialLoading, so the UI keeps showing skeletons until the authenticated read
            // lands. The previous delay(2_000) x5 loop reset the controller between ticks,
            // which kicked it out of InitialLoading and flashed "No messages yet" before AUTH
            // completed. A REQ that stalls (e.g. a silent no-op on a half-open socket) is now
            // caught by the controller's load timeout → Error → the Error-recovery effect
            // below, and a dropped connection by this effect's isConnected dep re-running.
            repo.requestGroupMessages(group.id)
        }
        // Auto-recover from controller PARTIAL_TIMEOUT / TIMEOUT (state=Error).
        //
        // Web cold-boot is heavier than native (SW + kotlin/js bundle + ~30
        // simultaneous websockets for kind:0 fan-out and outbox). Messages
        // arrive within the 10s controller timeout but the EOSE on the
        // primary's REQ often doesn't, so the controller flips to Error
        // with the cursor advanced. From the user's POV: 50 messages
        // visible, scroll-up does nothing because hasMore=false.
        //
        // Native has the same controller but doesn't hit this case in
        // practice because the connection settles in < 1s. Web needs an
        // explicit recovery. Manually navigating away and back already
        // works (the entry useEffect kicks a fresh REQ because Error →
        // InitialLoading is allowed in startInitialLoad); we just plumb
        // the same kick in automatically when the user is sitting on a
        // chat the controller gave up on.
        //
        // Capped at 2 attempts per group entry to avoid hot-looping on a
        // genuinely dead relay; the counter resets when the user changes
        // groups (group.id dep on the reset effect below).
        val errorRetryCount = useRef(0)
        useEffect(group.id) {
            errorRetryCount.current = 0
        }
        useEffect(groupLoadingState, isMember) {
            val s = groupLoadingState
            if (s !is GroupLoadingState.Error) return@useEffect
            val count = errorRetryCount.current ?: 0
            if (count >= 2) return@useEffect
            errorRetryCount.current = count + 1
            // Brief debounce so we don't fight a relay that's mid-reconnect.
            delay(1_500)
            // Re-check: state might have moved on (user reset by switching
            // groups, fresh REQ from elsewhere, etc.).
            val stillError = repo.groupStates.value[group.id] is GroupLoadingState.Error
            if (!stillError) return@useEffect
            repo.resetGroupLoadingState(group.id)
            repo.requestGroupMessages(group.id)
        }
        // Post-approval: an admin approving a pending join flips kind:39002 to make us a member.
        // The prior non-member REQ left the controller Exhausted/Error with 0 msgs; re-fetch so
        // the chat streams in. Its own effect keyed on isMember alone (isMember was dropped from
        // the loader keys above to stop the churn), so it fires only on that transition.
        useEffect(isMember) {
            if (!isMember) return@useEffect
            val s = repo.groupStates.value[group.id]
            val haveMsgs = repo.messages.value[group.id].orEmpty().isNotEmpty()
            if (!haveMsgs && (s is GroupLoadingState.Exhausted || s is GroupLoadingState.Error)) {
                repo.resetGroupLoadingState(group.id)
                repo.requestGroupMessages(group.id)
            }
        }
        // Admin in a closed group: pull pending join requests explicitly + poll.
        // The standard chat REQ caps at 50 events and buries old 9021s under recent
        // chat, so the badge wouldn't appear on a fresh page load until navigating
        // away and back triggered a mux refresh. The poll also covers relays that
        // don't push 9021s on open subscriptions after EOSE, keeping the badge live.
        // The useEffect block is suspend (this wrappers version — same pattern as
        // useEscClose / useStateFlow); `delay` propagates CancellationException when
        // deps change, so leaving the group / losing admin tears down the loop.
        useEffect(group.id, isAdmin, group.isOpen, activeAccountId) {
            if (!(isAdmin && !group.isOpen)) return@useEffect
            repo.requestPendingJoinRequests(group.id)
            while (true) {
                delay(15_000)
                repo.requestPendingJoinRequests(group.id)
            }
        }
        // Pending member in a closed group: the group is restricted, so it's excluded from the live
        // mux and the approval kind:39002 is never pushed — the member would sit "Awaiting approval"
        // until a restart re-fetched it. Poll the member list while pending so onApprovalDetected
        // fires live. Mirrors native GroupScreen's pending poll (GroupScreen.kt:285-294). The suspend
        // effect's delay propagates CancellationException, so approval / leaving tears down the loop.
        useEffect(group.id, isPendingApproval, activeAccountId) {
            if (!isPendingApproval) return@useEffect
            while (true) {
                delay(10_000)
                vm.refreshGroupData()
            }
        }
        useEffect(group.id, members.size, messages.size, activeAccountId) {
            val pubkeys = (members + messages.map { it.pubkey }).toSet()
            if (pubkeys.isNotEmpty()) vm.requestUserMetadata(pubkeys)
        }
        // The full ordered row list (date separators, grouped messages, system rows,
        // the "new messages" divider) — fed to ChatMessageList as its data. Scroll,
        // pagination latch and stall detection all live inside that component now.
        // Memoized: rebuilding the date-separator / message-grouping / system-row tree is O(n) and
        // only needs to re-run when the sorted messages, the read divider, or the viewer change.
        val chatItems =
            useMemo(messages, lastReadSnapshot, myPubkey) {
                if (messages.isEmpty()) emptyList() else buildWebChatItems(messages, lastReadSnapshot, myPubkey)
            }
        // The group always opens at the bottom (newest). The "New messages" line still
        // renders in the feed, and the jump-to-bottom pill still offers a tap to it, but we
        // no longer auto-scroll to the divider on entry: it landed far up in unread history
        // and the scroll-into-view tripped the near-top auto-pagination, walking the view
        // upward on its own. Mark-as-read still advances from the bottom (onRangeChange /
        // onAtBottomChange), so the line clears on the next entry.
        // Deep-link target (?e=<id>): fetch the exact event by id once. This is the fast path —
        // a single targeted REQ that lands the message even when it's far older than the loaded
        // window, instead of paginating the whole history (mirrors native's fetchMessageById).
        useEffect(props.scrollToMessageId, group.id) {
            val target = props.scrollToMessageId ?: return@useEffect
            vm.fetchMessageById(target)
        }
        // Then seek to it: scroll into view + flash once it's loaded. While it's still missing and
        // older messages remain, paginate one page at a time until it appears (or history runs
        // out) — covers the case where the direct fetch is dropped. hasMore/isLoadingMore are keys
        // so the loop advances after each page lands. Runs after the auto-scroll effect so it wins
        // the race on group entry.
        useEffect(props.scrollToMessageId, messages.size, hasMore, isLoadingMore) {
            val target = props.scrollToMessageId ?: return@useEffect
            if (target in messagesById) {
                // Command ChatMessageList to scroll the target row into view (by its
                // DOM id, msg-<id>).
                setScrollKey("msg-$target")
                atBottom.current = false
                setHighlightId(target)
                props.onScrolledToMessage()
                window.setTimeout({ setHighlightId(null) }, 3_000)
            } else if (messages.isNotEmpty() && hasMore && !isLoadingMore) {
                vm.loadMoreMessages()
            }
        }

        // Scroll the current search match into view (reuses the list's scrollKey command). Keyed on
        // the nonce too so prev/next re-scrolls to the same id (single match, or re-centering).
        // Re-asserts over a short window: avatars / media / nevent quote cards settle their height
        // after the first scroll and shift the row, so a single scrollIntoView often drifts off. The
        // suspend effect is auto-cancelled when the target changes, so stale scrolls never linger.
        useEffect(currentSearchHitId, searchScrollNonce) {
            val id = currentSearchHitId ?: return@useEffect
            repeat(12) {
                setScrollKey("msg-$id")
                delay(70)
            }
        }

        // Prefetch quoted events referenced in loaded messages so their text is searchable even for
        // messages not yet scrolled into view (a quote is otherwise cached only once its row renders).
        useEffect(searchActive, messagesByGroup[group.id]) {
            if (!searchActive) return@useEffect
            launchApp {
                val cached = repo.cachedEvents.value
                for (ref in ChatSearch.quotedEventRefs(messages)) {
                    if (ref.eventId !in cached) repo.requestEventById(ref.eventId, ref.relays, ref.author)
                }
            }
        }

        // On-demand "search older messages": page back through history ONLY until the next older
        // match appears, then stop and jump to it. Detection is by the ANCHOR's index (older matches
        // prepend and push the anchor right), so a new live match appended at the tail can't end the
        // dig. Bounded by a page cap + a watchdog so one click never drains all history (which would
        // flip hasMore false and hide the affordance) and the spinner can't stick if paging stalls.
        // The 300ms pace lets the quote prefetch resolve nevent content before the next page loads;
        // re-pinning the viewport to the current match keeps the feed from drifting while it digs.
        useEffect(searchingOlder, hasMore, isLoadingMore, searchCursor.index) {
            if (!searchingOlder) return@useEffect
            val baseline = searchOlderBaseline.current ?: 0
            when {
                searchCursor.index > baseline -> {
                    // The newest of the newly-found older matches sits just before the anchor's slot.
                    setAnchoredHitId(searchMatchIds[searchCursor.index - baseline - 1])
                    setSearchScrollNonce { it + 1 }
                    setSearchingOlder(false)
                }
                !hasMore -> setSearchingOlder(false)
                (searchOlderPages.current ?: 0) >= ChatSearch.MAX_SEARCH_OLDER_PAGES -> setSearchingOlder(false)
                !isLoadingMore -> {
                    delay(300)
                    searchOlderPages.current = (searchOlderPages.current ?: 0) + 1
                    setSearchScrollNonce { it + 1 }
                    vm.loadMoreMessages()
                }
            }
        }
        // Watchdog: stop the dig if paging stops progressing, so the spinner can't stick and the
        // affordance comes back. Cancelled the moment the dig ends on its own (key flips false).
        useEffect(searchingOlder) {
            if (!searchingOlder) return@useEffect
            delay(10_000)
            setSearchingOlder(false)
        }

        fun join() {
            vm.joinGroup()
        }

        // Scroll a loaded message into view and flash it (used by reply-preview clicks).
        // Commands ChatMessageList via scrollKey (the row's DOM id, msg-<id>).
        fun scrollToMessage(id: String) {
            setScrollKey("msg-$id")
            setHighlightId(id)
            window.setTimeout({ setHighlightId(null) }, 2_600)
        }

        div {
            // `searching` adds scroll-padding so scrollIntoView centers a hit below the floating
            // search overlay instead of behind it (see .chat.searching .chat-messages-list).
            className =
                ClassName(
                    buildString {
                        append("chat")
                        if (membersOpen) append(" members-open")
                        if (searchActive) append(" searching")
                    },
                )

            // Main column: header + messages + composer
            div {
                className = ClassName("chat-main")

                // Header
                //
                // Every direct child of .chat-main carries a stable `key`. The search bar and
                // the jump FAB are conditional siblings rendered BEFORE / around the message
                // list; without keys React reconciles .chat-main's children by index, so
                // toggling search shifted .chat-messages by one slot and REMOUNTED it — every
                // row's avatar <img> reloaded, flashing to the gradient fallback and back.
                // (Same failure mode the loading pill hit one level down; see its key comment.)
                div {
                    key = "chat-header"
                    className = ClassName("chat-header")
                    // Mobile-only: opens the groups-sidebar drawer (the `≡` in native's header).
                    // CSS hides it on desktop where the sidebar is always visible.
                    button {
                        className = ClassName("chat-icon-btn chat-drawer-btn")
                        onClick = { props.onOpenDrawer() }
                        icon(Ic.Menu)
                    }
                    div {
                        className = ClassName("chat-header-title")
                        onClick = { setInfoOpen(true) }
                        WebAvatar {
                            url = group.picture
                            seed = group.id
                            kind = AvatarKind.GROUP
                            name = groupName
                            cls = "chat-header-icon"
                        }
                        div {
                            className = ClassName("chat-header-name")
                            +groupName
                        }
                        // Relay status dot (prototype RelayStatusDot): colored by the
                        // active relay's connection state, tooltip = relay URL + status.
                        val (dotCls, dotLabel) =
                            when (connState) {
                                is ConnectionManager.ConnectionState.Connected -> "st-connected" to "Connected"
                                is ConnectionManager.ConnectionState.Connecting,
                                is ConnectionManager.ConnectionState.Reconnecting,
                                -> "st-connecting" to "Connecting..."
                                else -> "st-offline" to "Offline"
                            }
                        span {
                            className = ClassName("chat-relay-dot $dotCls")
                            title = "${relayUrl.ifBlank { "relay" }} · $dotLabel"
                        }
                        if (!group.about.isNullOrBlank()) {
                            div {
                                className = ClassName("chat-header-about")
                                +group.about
                            }
                        }
                    }
                    // Pending join-requests indicator (admin + closed group, count > 0).
                    // Opens Manage group on its Requests tab directly so admins don't need to
                    // dig into the 3-dots menu when the badge is calling for attention.
                    if (pendingJoinRequests > 0) {
                        button {
                            className = ClassName("chat-icon-btn chat-requests-btn")
                            title = "Join requests"
                            onClick = { setModal("requests") }
                            icon(Ic.PersonAdd)
                            span {
                                className = ClassName("chat-requests-badge")
                                +pendingJoinRequests.toString()
                            }
                        }
                    }
                    // A private group exposes nothing to non-members (no messages, member list or
                    // shareable invite), so hide Search / Invite / Group Info / Members there; a
                    // public group, or a private one you're a member of, keeps them.
                    if (!access.isPrivate || canPost) {
                        button {
                            className = ClassName(if (searchActive) "chat-icon-btn active" else "chat-icon-btn")
                            title = "Search"
                            onClick = { if (searchActive) closeSearch() else setSearchActive(true) }
                            icon(Ic.Search)
                        }
                        // Prototype header actions: Share (group address / link) and Info, then Members.
                        button {
                            className = ClassName("chat-icon-btn")
                            title = "Share"
                            onClick = { setModal("share") }
                            icon(Ic.Link)
                        }
                        button {
                            className = ClassName("chat-icon-btn chat-info-btn")
                            title = "Group Info"
                            onClick = { setInfoOpen(true) }
                            icon(Ic.Info)
                        }
                        button {
                            className = ClassName(if (membersOpen) "chat-icon-btn active" else "chat-icon-btn")
                            title = "Members"
                            onClick = { setMembersOpen(!membersOpen) }
                            icon(Ic.People)
                        }
                        // Subgroup channel: the header cog is the discoverable admin entry for
                        // managing THIS channel (root groups keep the sidebar "Manage group" row).
                        if (isAdmin && group.parent != null) {
                            button {
                                className = ClassName("chat-icon-btn")
                                title = "Manage channel"
                                onClick = { setModal("edit") }
                                icon(Ic.Settings)
                            }
                        }
                    }
                    if (canAddToMyList && !membersResolving && !isOrphaned) {
                        button {
                            className = ClassName("chat-join-btn")
                            title = "You are a member on this relay but the group is not in your groups"
                            onClick = { vm.addToMyList() }
                            icon(Ic.Add)
                            span { +"Add to my groups" }
                        }
                    }
                    if (!canPost && !membersResolving && !isOrphaned) {
                        if (composerPending) {
                            span {
                                className = ClassName("chat-pending")
                                +"Request pending"
                            }
                        } else {
                            // Closed groups: surface an "Invite Code" button next to
                            // Join (matches native GroupHeader.kt:208-232). Opens the
                            // JoinWithCodeModal — the web port of native InviteCodeJoinModal.
                            if (!access.isOpen) {
                                button {
                                    className = ClassName("chat-invite-btn")
                                    onClick = { setModal("joincode") }
                                    icon(Ic.Key)
                                    span { +"Invite Code" }
                                }
                            }
                            button {
                                className = ClassName("chat-join-btn")
                                onClick = { join() }
                                icon(Ic.PersonAdd)
                                span { +(if (!access.isOpen) "Request to Join" else "Join") }
                            }
                        }
                    }
                    // No 3-dots menu (prototype shape): Leave lives in the info modal,
                    // admin management in the sidebar "Manage group" entry and, for
                    // subgroup channels, the header cog above.
                }

                // In-chat search bar. Floats as an overlay anchored under the header (absolute
                // inside .chat-main) so opening search does NOT shrink the .chat-messages viewport
                // or force the scroller to recalculate. One keyed child of .chat-main (see the
                // header key note); the bar + older affordance stack inside it.
                if (searchActive) {
                    div {
                        key = "chat-search-overlay"
                        className = ClassName("chat-search-overlay")
                        div {
                            className = ClassName("chat-search-bar")
                            input {
                                className = ClassName("chat-search-input")
                                placeholder = "Search messages"
                                // Uncontrolled (no `value`): the DOM owns the text so typing does NOT
                                // re-render ChatScreen. A debounced commit updates searchQuery.
                                autoFocus = true
                                onChange = { event ->
                                    val v = event.currentTarget.value
                                    window.clearTimeout(searchDebounce.current ?: 0)
                                    searchDebounce.current = window.setTimeout({ commitQuery(v) }, 160)
                                }
                                onKeyDown = { event ->
                                    when {
                                        event.key == "Escape" -> closeSearch()
                                        event.key == "Enter" && event.shiftKey -> {
                                            event.preventDefault()
                                            gotoPrevMatch()
                                        }
                                        event.key == "Enter" -> {
                                            event.preventDefault()
                                            // Flush the debounce so a fresh query commits immediately;
                                            // if it's unchanged, advance to the next match.
                                            val v = event.currentTarget.value
                                            if (v.trim() != searchQuery.trim()) {
                                                window.clearTimeout(searchDebounce.current ?: 0)
                                                commitQuery(v)
                                            } else {
                                                gotoNextMatch()
                                            }
                                        }
                                    }
                                }
                            }
                            val counter =
                                when {
                                    searchQuery.trim().length < 2 -> ""
                                    searchMatchIds.isEmpty() -> "No matches"
                                    else -> "${searchCursor.position} / ${searchMatchIds.size}"
                                }
                            if (counter.isNotEmpty()) {
                                span {
                                    className = ClassName("chat-search-count")
                                    +counter
                                }
                            }
                            button {
                                className = ClassName("chat-icon-btn")
                                disabled = searchMatchIds.isEmpty()
                                onClick = { gotoPrevMatch() }
                                icon(Ic.ExpandLess)
                            }
                            button {
                                className = ClassName("chat-icon-btn")
                                disabled = searchMatchIds.isEmpty()
                                onClick = { gotoNextMatch() }
                                icon(Ic.ExpandMore)
                            }
                            button {
                                className = ClassName("chat-icon-btn")
                                onClick = { closeSearch() }
                                icon(Ic.Close)
                            }
                        }
                        // Older-history search affordance (PR 2): shown whenever there's a query and
                        // the relay still has older history to page through (so older matches can be
                        // found even when the loaded window already has some), or while a dig runs.
                        if (searchingOlder || (searchQuery.trim().length >= 2 && hasMore)) {
                            div {
                                className = ClassName("chat-search-older")
                                if (searchingOlder) {
                                    span { className = ClassName("btn-spinner") }
                                    span { +"Searching older messages" }
                                    button {
                                        className = ClassName("chat-search-older-btn")
                                        onClick = { setSearchingOlder(false) }
                                        +"Cancel"
                                    }
                                } else {
                                    button {
                                        className = ClassName("chat-search-older-btn")
                                        onClick = {
                                            searchOlderBaseline.current = searchCursor.index
                                            searchOlderPages.current = 0
                                            setSearchingOlder(true)
                                        }
                                        +"Search older messages"
                                    }
                                }
                            }
                        }
                    }
                }

                // Messages — non-scrolling flex wrapper; Virtuoso (below) owns the
                // scroll, pagination and scroll anchoring.
                div {
                    key = "chat-messages"
                    className = ClassName("chat-messages")
                    if (isLoadingMore && messages.isNotEmpty()) {
                        div {
                            // Stable key: this pill is a sibling rendered BEFORE the
                            // message list and toggles with isLoadingMore. Without
                            // keys, React reconciles the wrapper's children by index,
                            // so the pill appearing/disappearing shifted the list's
                            // index and REMOUNTED it — and each remount snapped the
                            // feed to the bottom (the pagination "jump to bottom").
                            key = "chat-loading-pill"
                            className = ClassName("chat-loading-more")
                            +"Loading earlier messages…"
                        }
                    }
                    // Pagination stalled (repeated unanswered scroll-back REQs): honest
                    // failure row with a manual retry instead of an endless spinner. The
                    // stalled state has hasMore=false, so Virtuoso's startReached no
                    // longer auto-fires; only this button resumes.
                    if (groupLoadingState is GroupLoadingState.Stalled && messages.isNotEmpty()) {
                        div {
                            key = "chat-load-stalled"
                            className = ClassName("chat-loading-more chat-load-stalled")
                            +"Couldn't load older messages"
                            button {
                                className = ClassName("chat-load-retry")
                                onClick = { vm.retryLoadMore() }
                                +"Retry"
                            }
                        }
                    }
                    // Empty-state gating — matches native GroupScreen.kt:343:
                    // `isInitialLoading = isLoadingMoreMap[groupId] == true && empty`.
                    // Skeleton only while the active REQ is in flight; once
                    // isLoadingMore flips false, fall through to "No messages
                    // yet" regardless of which terminal state the controller
                    // reached. Earlier the gate required Exhausted / Error
                    // specifically, which left web stuck on skeletons in cases
                    // where native shows the empty-state cleanly (e.g. relay
                    // returns an empty EOSE but the controller settles into
                    // HasMore-with-zero, or into a state web didn't whitelist).
                    val isLoadingThis = isLoadingMore ||
                        awaitingAuthRead ||
                        groupLoadingState is GroupLoadingState.InitialLoading ||
                        groupLoadingState is GroupLoadingState.Retrying
                    // Restricted / pending-approval / skeleton / empty panels apply
                    // ONLY when there are no messages. Once messages exist we always
                    // render the list (the final else). This is essential: isMember /
                    // canPost / isPendingApproval / isGroupRestricted all derive from
                    // flows that flip while loading (member list / restricted flag
                    // arrive late); if the panel could win with messages present, that
                    // flip would unmount and remount ChatMessageList, and each remount
                    // snapped the feed to the bottom (the pagination "jump to bottom").
                    // Not gated on messages.isEmpty(): a deleted group can still have leftover
                    // moderation events (e.g. the kind:9002 that named it) that the relay keeps
                    // serving, and isOrphaned already means "no kind:39000", so it can't hide a live group.
                    if (isOrphaned) {
                        div {
                            className = ClassName("chat-restricted")
                            icon(Ic.Block, "chat-restricted-icon")
                            div {
                                className = ClassName("chat-restricted-title")
                                +"Group no longer available"
                            }
                            div {
                                className = ClassName("chat-restricted-body")
                                +"This group has been deleted or is no longer on the relay."
                            }
                            button {
                                className = ClassName("btn-secondary chat-restricted-action")
                                onClick = { vm.forget { props.onLeave() } }
                                +"Remove from your list"
                            }
                        }
                    } else if (messages.isEmpty() && (isGroupRestricted || isPendingApproval)) {
                        div {
                            className = ClassName("chat-restricted")
                            icon(Ic.Lock, "chat-restricted-icon")
                            div {
                                className = ClassName("chat-restricted-title")
                                +(if (isPendingApproval) "Awaiting admin approval" else "Private group")
                            }
                            div {
                                className = ClassName("chat-restricted-body")
                                +(
                                    if (isPendingApproval) {
                                        "Messages will appear once an admin approves your request."
                                    } else {
                                        "You need an invite code or admin approval to see messages."
                                    }
                                    )
                            }
                        }
                    } else if (messages.isEmpty() && (!isConnected || isLoadingThis)) {
                        repeat(8) { messageSkeleton() }
                    } else if (messages.isEmpty()) {
                        div {
                            className = ClassName("chat-empty")
                            +"No messages yet. Say hello 👋"
                        }
                    } else {
                        // Non-virtualized message list: renders every row as DOM and
                        // keeps the reading position on prepend with a scrollHeight
                        // delta. This screen owns the data; the component owns scroll +
                        // pagination. (aliases avoid the prop-name shadowing the locals.)
                        val moreAvail = hasMore
                        val loadingMore = isLoadingMore
                        ChatMessageList {
                            // Stable key so the list is never remounted when a sibling
                            // (the loading pill) toggles — a remount snaps to the bottom.
                            key = "chat-message-list"
                            items = chatItems.toTypedArray().unsafeCast<Array<dynamic>>()
                            resetKey = group.id
                            this.hasMore = moreAvail
                            this.isLoadingMore = loadingMore
                            scrollToKey = scrollKey
                            // Search lands the hit at the top (under the overlay, via scroll-padding),
                            // matching Compose; reply / deep-link jumps stay centered.
                            scrollToKeyBlock = if (searchActive || scrollKey == "new-msg-divider") "start" else "center"
                            onScrolledToKey = { setScrollKey(null) }
                            this.jumpNonce = jumpNonce
                            onStartReached = { vm.loadMoreMessages() }
                            onAtBottomChange = { ab ->
                                atBottom.current = ab
                                setAtBottomState(ab)
                                if (!ab) {
                                    wasNotAtBottom.current = true
                                    // Re-arm the unread baseline at the moment we leave the bottom when
                                    // it was already cleared (caught up). Otherwise the jump pill's count
                                    // stays dead for the rest of the session: a message arriving while
                                    // scrolled up is never newer than a null snapshot, so "N new" never
                                    // shows. Anchoring to the current newest means only later arrivals count.
                                    if (lastReadSnapshot == null) {
                                        messages.maxOfOrNull { it.createdAt }?.let { setLastReadSnapshot(it) }
                                    }
                                    // The divider was already on screen (seen at the bottom) and the user
                                    // has now scrolled away: consume it even though it slid off the viewport
                                    // before onDividerVisible could fire for this scroll (issue #83).
                                    if (dividerWasOnScreen.current == true && lastReadSnapshot != null) consumeDivider()
                                } else {
                                    // Reaching the bottom persists read state but does NOT dismiss the
                                    // divider (issue #83): the entry settle opens at the bottom, so a
                                    // reach-bottom clear there would wipe it unseen. The divider is
                                    // consumed only via onDividerVisible (scrolled to it), send, or the
                                    // jump pill's second tap.
                                    vm.markAsRead()
                                }
                            }
                            onDividerVisible = {
                                // The divider is within the viewport. Latch it as seen (covers the
                                // bottom-on-open case so the scroll-away can consume it later), and
                                // consume now if a genuine scroll-away already happened this entry, so
                                // the open-at-bottom settle never clears it.
                                dividerWasOnScreen.current = true
                                if (wasNotAtBottom.current == true && lastReadSnapshot != null) consumeDivider()
                            }
                            onRangeChange = { end ->
                                // Mark read up to the newest message in/above the
                                // visible window (mirrors native's per-visible pass).
                                var maxSeen = 0L
                                val e = end.coerceIn(0, chatItems.size - 1)
                                for (i in 0..e) {
                                    val ci = chatItems[i]
                                    if (ci is WebChatItem.Message && ci.message.createdAt > maxSeen) maxSeen = ci.message.createdAt
                                }
                                if (maxSeen > 0L) vm.markAsReadUpTo(maxSeen)
                            }
                            renderRow = { cb, itDyn ->
                                val item = itDyn.unsafeCast<WebChatItem>()
                                cb.run {
                                    when (item) {
                                        is WebChatItem.DateSeparator ->
                                            div {
                                                key = "date-${item.date}"
                                                // Stable DOM id for the pagination scroll-anchor restore.
                                                id = ElementId("date-${item.date}")
                                                className = ClassName("date-sep")
                                                span {
                                                    className = ClassName("date-sep-label")
                                                    +item.date
                                                }
                                            }

                                        is WebChatItem.NewMessagesDivider ->
                                            div {
                                                key = "new-messages-divider"
                                                // Stable DOM id so the entry-alignment effect can
                                                // scrollIntoView the divider itself rather than the
                                                // adjacent message (which fails for moderation rows
                                                // that have no msg-${id} element).
                                                id = ElementId("new-msg-divider")
                                                className = ClassName("new-msg-divider")
                                                span {
                                                    className = ClassName("new-msg-divider-label")
                                                    +"New Messages"
                                                }
                                            }

                                        is WebChatItem.SystemEvent -> systemEventRow(item, userMetadata) { setProfilePubkey(it) }

                                        is WebChatItem.Message -> {
                                            val message = item.message
                                            val parent = parentMessageOf(message)?.let { messagesById[it] }
                                            val replyPreview =
                                                parent?.let {
                                                    ReplyPreviewData(
                                                        author = displayName(it.pubkey, userMetadata[it.pubkey]),
                                                        content = replyPreviewText(it.content, userMetadata, 120),
                                                        tags = it.tags,
                                                    )
                                                }
                                            val authorMeta = userMetadata[message.pubkey]
                                            val zapInfo = zapsByMsg[message.id]
                                            val meta = rowMeta[message.id]
                                            MessageRow {
                                                key = message.id
                                                domId = "msg-${message.id}"
                                                highlighted = message.id == highlightId
                                                searchHit = message.id in searchHitIdSet
                                                searchCurrent = message.id == currentSearchHitId
                                                pubkey = message.pubkey
                                                name = displayName(message.pubkey, userMetadata[message.pubkey])
                                                avatarUrl = userMetadata[message.pubkey]?.picture
                                                time = formatTime(message.createdAt)
                                                content = message.content
                                                this.tags = message.tags
                                                this.firstInGroup = item.firstInGroup
                                                isAuthorAdmin = message.pubkey in admins
                                                reactions = reactionsByMsg[message.id].orEmpty()
                                                this.myPubkey = myPubkey
                                                this.userMetadata = userMetadata
                                                this.messagesById = messagesById
                                                onEventRef = { id -> scrollToMessage(id) }
                                                onGroupRef = { gid, relay -> props.onNavigateGroup(gid, relay) }
                                                canZap =
                                                    canSign &&
                                                    message.pubkey != myPubkey &&
                                                    Nip57.resolvePayEndpoint(authorMeta?.lud16, authorMeta?.lud06) != null
                                                zapTotalMsats = zapInfo?.totalMsats ?: 0L
                                                zapCount = zapInfo?.count ?: 0
                                                zappedByMe = myPubkey != null && zapInfo != null && myPubkey in zapInfo.zappers
                                                onZap = { WebZapController.request(message.pubkey, message.id) }
                                                replyTo = replyPreview
                                                onReplyClick = { parent?.let { scrollToMessage(it.id) } }
                                                canDelete = myPubkey != null && (message.pubkey == myPubkey || myPubkey in admins)
                                                this.isMine = message.pubkey == myPubkey
                                                this.isAdmin = myPubkey != null && myPubkey in admins
                                                messageLink = meta?.link ?: ""
                                                nevent = meta?.nevent ?: ""
                                                eventJson = meta?.eventJson ?: ""
                                                status = messageStatus[message.id]
                                                onRetrySend = { vm.retrySend(message.id) }
                                                onDismissFailed = { vm.dismissFailed(message.id) }
                                                onUser = { setProfilePubkey(it) }
                                                onReply = {
                                                    setReplyingToId(message.id)
                                                    setReplyNonce { it + 1 }
                                                }
                                                onReact = { emoji ->
                                                    // Relay rejections (e.g. "kind 7 not allowed") surface via
                                                    // vm.reactionError instead of the reaction blinking away.
                                                    vm.sendReaction(message.id, message.pubkey, emoji)
                                                }
                                                onDelete = { setMessageToDelete(message.id) }
                                                onReport = { setReportTarget(ReportTargetRef(message.pubkey, message.id)) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Jump-to-bottom pill. Lives INSIDE .chat-messages (which ends at the
                    // composer's top edge), so it always floats 12px above the composer
                    // regardless of its height (reply banner, toolbar, multiline) — the
                    // prototype's anchoring. Visible only when scrolled up from the bottom.
                    // With unread from others it shows the count and a two-stage jump: the
                    // first tap focuses the "New messages" divider so they're read top-down,
                    // the second drops to the very latest.
                    if (!atBottomState) {
                        // Stage-1 (divider) tap gated by the shared policy: only with unread
                        // from others to read there, otherwise straight to the bottom.
                        val jumpTarget = ChatScrollPolicy.onJumpPillTap(
                            hasDivider = lastReadSnapshot != null,
                            dividerSeen = dividerSeen.current == true,
                            unreadFromOthers = unreadCount,
                        )
                        val jumpsToDivider = jumpTarget == JumpPillTarget.Divider
                        button {
                            key = "chat-jump-bottom"
                            className = ClassName("chat-jump-bottom")
                            title = if (jumpsToDivider) "Jump to new messages" else "Jump to latest message"
                            // Keyboard-neutral tap (#199 both ways): rules and rationale in
                            // VirtualKeyboardPolicy (commonMain, unit-tested).
                            onMouseDown = {
                                val keep = VirtualKeyboardPolicy.keepComposerFocusOnTap(
                                    keyboardOpen = VirtualKeyboard.isOpen,
                                    touchDevice = window.navigator.maxTouchPoints > 0,
                                )
                                if (keep) it.preventDefault()
                            }
                            onClick = {
                                if (jumpsToDivider) {
                                    // First tap: land on the "New messages" divider.
                                    dividerSeen.current = true
                                    setScrollKey("new-msg-divider")
                                } else {
                                    // Second tap (or nothing unread): drop to the latest and
                                    // dismiss the divider for this session.
                                    dividerSeen.current = false
                                    setJumpNonce { it + 1 }
                                    if (lastReadSnapshot != null) setLastReadSnapshot(null)
                                }
                            }
                            val unreadLabel = if (unreadCount > 99) "99+" else unreadCount.toString()
                            // Badge + count until the divider has been seen; after that the
                            // pill reads as stage 2, "Jump to latest" (native parity).
                            val showCount = unreadCount > 0 && dividerSeen.current != true
                            if (showCount) {
                                span {
                                    className = ClassName("chat-jump-badge")
                                    +unreadLabel
                                }
                            }
                            span { +(if (showCount) "$unreadLabel new" else "Jump to latest") }
                            // Down chevron (matches native KeyboardArrowDown).
                            icon(Ic.ExpandMore)
                        }
                    }
                }

                ChatComposer {
                    key = "chat-composer"
                    // Share the screen's GroupViewModel — don't let the composer spin up a
                    // second instance (separate viewModelScope + unobserved sendError) for
                    // the same group.
                    this.groupVm = vm
                    this.groupId = group.id
                    this.groupName = groupName
                    this.groupIsOpen = group.isOpen
                    this.canPost = canPost
                    this.isOrphaned = isOrphaned
                    this.isPending = composerPending
                    this.mentionRequest = mentionRequest
                    this.members = members
                    this.allGroups = allGroups
                    this.userMetadata = userMetadata
                    this.relayUrl = relayUrl
                    this.relayPubkeyOf = { r -> relayMetadata[r]?.groupNaddrAuthor ?: relayMetadata[r.normalizeRelayUrl()]?.groupNaddrAuthor }
                    this.replyingToId = replyingToId
                    this.replyNonce = replyNonce
                    this.replyParentName =
                        replyingToId?.let { id -> messagesById[id]?.let { p -> displayName(p.pubkey, userMetadata[p.pubkey]) } }
                    this.replyParentContent =
                        replyingToId?.let { id ->
                            messagesById[id]?.let { p -> replyPreviewText(p.content, userMetadata, 80) }
                        }
                    this.onCancelReply = { setReplyingToId(null) }
                    this.onSent = {
                        setReplyingToId(null)
                        // Sending acknowledges the "New messages" boundary: drop the divider.
                        consumeDivider()
                        // Own send always lands the view at the bottom, even when reading
                        // history (native parity: AutoScrollEffect's ownAppend).
                        setJumpNonce { it + 1 }
                    }
                    this.onJoin = { join() }
                    this.pendingRequestedAtSeconds = pendingRequestedAt
                    this.membersResolving = membersResolving
                    // Cancel a pending join request = leave the group, then navigate away.
                    this.onCancelJoinRequest = {
                        launchApp { repo.leaveGroup(group.id) }
                        props.onLeave()
                    }
                }
            }

            // Member sidebar
            div {
                className = ClassName("member-backdrop")
                onClick = { setMembersOpen(false) }
            }
            div {
                className = ClassName("member-sidebar")
                div {
                    className = ClassName("member-header")
                    span {
                        className = ClassName("member-header-title")
                        +"Members · ${members.size}"
                    }
                    // Only admins can add members — kind:9000 (put-user) is admin-only and
                    // the relay rejects it from non-admins. Member management lives in the
                    // Manage modal (sidebar Members row / the header cog), not here.
                    if (isAdmin) {
                        div {
                            className = ClassName("member-header-actions")
                            button {
                                className = ClassName("member-add-btn")
                                title = "Add member"
                                onClick = { setModal("addmember") }
                                icon(Ic.PersonAdd)
                            }
                        }
                    }
                }
                div {
                    className = ClassName("member-search")
                    searchInput(
                        placeholder = "Search members",
                        value = memberQuery,
                        onChange = { setMemberQuery(it) },
                        compact = true,
                    )
                }
                div {
                    className = ClassName("member-scroll")
                    val memberQ = memberQuery.trim().normalizeForSearch()
                    val filtered =
                        if (memberQ.isEmpty()) {
                            members.toList()
                        } else {
                            members.filter { displayName(it, userMetadata[it]).normalizeForSearch().contains(memberQ) }
                        }
                    // Mirrors native MemberSidebar.kt:100-101 — chat apps surface
                    // who can reply NOW (online) above static role hierarchy.
                    // The ADMIN badge still appears inline on each row, in either
                    // section, so the role info isn't lost.
                    val online = filtered.filter { it in recentlyActiveMembers }
                    val offline = filtered.filter { it !in recentlyActiveMembers }
                    if ((isGroupRestricted || isPendingApproval) && !props.group.isPublic) {
                        // A private group's member list is hidden until you join / get
                        // approved, surfaced as a centered lock panel instead of a skeleton
                        // or an empty list. A PUBLIC group's members are served to everyone,
                        // so they show even while pending (parity with native MemberSidebar).
                        div {
                            className = ClassName("member-private")
                            icon(Ic.Lock, "member-private-icon")
                            div {
                                className = ClassName("member-private-label")
                                +(if (isPendingApproval) "Members hidden until approved" else "Members are private")
                            }
                        }
                    } else if (membersLoading && members.isEmpty()) {
                        repeat(6) { memberSkeleton() }
                    } else {
                        if (online.isNotEmpty()) {
                            memberSection("ONLINE", online.size)
                            online.forEach { pubkey ->
                                memberRow(
                                    pubkey,
                                    displayName(pubkey, userMetadata[pubkey]),
                                    userMetadata[pubkey]?.picture,
                                    isAdmin = pubkey in admins,
                                    isOnline = true,
                                ) { setProfilePubkey(it) }
                            }
                        }
                        if (offline.isNotEmpty()) {
                            memberSection("OFFLINE", offline.size)
                            offline.forEach { pubkey ->
                                memberRow(
                                    pubkey,
                                    displayName(pubkey, userMetadata[pubkey]),
                                    userMetadata[pubkey]?.picture,
                                    isAdmin = pubkey in admins,
                                    isOnline = false,
                                ) { setProfilePubkey(it) }
                            }
                        }
                        if (online.isEmpty() && offline.isEmpty()) {
                            div {
                                className = ClassName("member-section")
                                +(if (members.isEmpty()) "No members yet" else "No members found")
                            }
                        }
                    }
                }
            }

            if (infoOpen) {
                GroupInfoModal {
                    this.group = group
                    this.relayUrl = props.relayUrl
                    onLeave = {
                        setInfoOpen(false)
                        // App-lifetime scope: the leave publish must survive the
                        // navigation away that follows (see `repo` note above).
                        launchApp { repo.leaveGroup(group.id) }
                        props.onLeave()
                    }
                    onClose = { setInfoOpen(false) }
                }
            }
            profilePubkey?.let { pubkey ->
                UserProfileModal {
                    this.pubkey = pubkey
                    // Group context unlocks the admin section (remove / role rows).
                    groupId = group.id
                    iAmAdmin = isAdmin
                    targetIsAdmin = pubkey in admins
                    onMention = { pk ->
                        setMentionRequest(
                            MentionRequest(
                                name = displayName(pk, userMetadata[pk]),
                                pubkey = pk,
                                nonce = (mentionRequest?.nonce ?: 0) + 1,
                            ),
                        )
                        setProfilePubkey(null)
                    }
                    onClose = { setProfilePubkey(null) }
                }
            }

            reportTarget?.let { target ->
                ReportUserModal {
                    pubkey = target.pubkey
                    eventId = target.messageId
                    onClose = { setReportTarget(null) }
                }
            }

            when (modal) {
                "edit" ->
                    ManageGroupModal {
                        this.group = group
                        this.relayUrl = props.relayUrl
                        onClose = { setModal(null) }
                    }
                "share" ->
                    ShareGroupModal {
                        this.group = group
                        onClose = { setModal(null) }
                    }
                "members" ->
                    ManageGroupModal {
                        this.group = group
                        initialTab = "members"
                        this.relayUrl = props.relayUrl
                        onClose = { setModal(null) }
                    }
                "addmember" ->
                    AddMemberModal {
                        groupId = group.id
                        onClose = { setModal(null) }
                    }
                "invite" ->
                    InviteCodesModal {
                        groupId = group.id
                        this.relayUrl = props.relayUrl
                        onClose = { setModal(null) }
                    }
                "joincode" ->
                    JoinWithCodeModal {
                        initialCode = null
                        onJoin = { code -> vm.joinGroup(code) }
                        onClose = { setModal(null) }
                    }
                "requests" ->
                    ManageGroupModal {
                        this.group = group
                        initialTab = "requests"
                        this.relayUrl = props.relayUrl
                        onClose = { setModal(null) }
                    }
            }
            // Accept/decline prompt for an external add (an admin's kind:9000). Mirrors the
            // native GroupScreen dialog: Accept adopts the group into the joined list +
            // kind:10009, Decline leaves (kind:9022), backdrop/Esc = decide later (the
            // group stays readable as a preview and the prompt returns on the next open).
            if (pendingInvite != null && dismissedInviteGroupId != group.id) {
                GroupInviteModal {
                    this.groupId = group.id
                    this.groupName = group.name?.takeIf { it.isNotBlank() } ?: "#${group.id.take(8)}"
                    this.picture = group.picture
                    relayHost = pendingInvite.relayUrl.removePrefix("wss://").removePrefix("ws://").trimEnd('/')
                    actorLabel = inviteActorLabel
                    onAccept = { vm.acceptInvite() }
                    onDecline = {
                        // launchApp, not the VM: onLeave() unmounts and viewModelScope would
                        // cancel the kind:9022 publish mid-flight (same rule as leaveGroup above).
                        launchApp { repo.leaveGroup(group.id) }
                        props.onLeave()
                    }
                    onDismiss = { setDismissedInviteGroupId(group.id) }
                }
            }
            // Delete-message confirm. Mirrors the native AlertDialog
            // (GroupScreen.kt:523-545): destructive action, red confirm,
            // explicit "cannot be undone" copy. Esc closes via useEscClose,
            // backdrop click cancels.
            messageToDelete?.let { msgId ->
                confirmDialog(
                    title = "Delete Message",
                    body = "Are you sure you want to delete this message? This action cannot be undone.",
                    confirmLabel = "Delete",
                    danger = true,
                    onCancel = { setMessageToDelete(null) },
                    onConfirm = {
                        setMessageToDelete(null)
                        // Relay rejection (kind:5 refused) surfaces via vm.deleteMessageError,
                        // same 'blocked:'/'error:' stripping + capitalisation as before.
                        vm.deleteMessage(msgId)
                    },
                )
            }
            // Relay rejected the kind:5 (e.g., not an admin, message
            // gone, group restricted) — show the reason instead of
            // silently swallowing.
            deleteError?.let { error ->
                deleteMessageErrorDialog(error) { vm.clearDeleteMessageError() }
            }
            // Relay rejected the kind:7 reaction — explain it instead of the
            // reaction just blinking away. Mirrors native's "Cannot React" /
            // "Join Required" dialog (GroupScreen.kt:620-665).
            reactionError?.let { error ->
                reactionErrorDialog(
                    message = error,
                    onDismiss = { vm.clearReactionError() },
                    onJoin = {
                        vm.clearReactionError()
                        join()
                    },
                )
            }
            // Relay rejected the join request — show the reason instead of a silent no-op.
            joinError?.let { error ->
                joinErrorDialog(error) { vm.clearJoinError() }
            }
        }
    }

/** Error dialog shown when the relay rejects a kind:9021 join request. Single OK button. */
private fun ChildrenBuilder.joinErrorDialog(message: String, onDismiss: () -> Unit) {
    div {
        className = ClassName("modal-overlay")
        onClick = { onDismiss() }
        div {
            className = ClassName("modal-card sm")
            onClick = { it.stopPropagation() }
            div {
                className = ClassName("modal-title")
                +"Could Not Join"
            }
            div {
                className = ClassName("modal-subtitle tight")
                +message
            }
            div {
                className = ClassName("modal-footer")
                button {
                    className = ClassName("btn-primary")
                    onClick = { onDismiss() }
                    +"OK"
                }
            }
        }
    }
}

/** Error dialog shown when the relay rejects a kind:5. Single OK button —
 *  matches the native AlertDialog at GroupScreen.kt:548-562. */
private fun ChildrenBuilder.deleteMessageErrorDialog(message: String, onDismiss: () -> Unit) {
    div {
        className = ClassName("modal-overlay")
        onClick = { onDismiss() }
        div {
            className = ClassName("modal-card sm")
            onClick = { it.stopPropagation() }
            div {
                className = ClassName("modal-title")
                +"Could Not Delete Message"
            }
            div {
                className = ClassName("modal-subtitle tight")
                +message
            }
            div {
                className = ClassName("modal-footer")
                button {
                    className = ClassName("btn-primary")
                    onClick = { onDismiss() }
                    +"OK"
                }
            }
        }
    }
}

/** Error dialog shown when a media upload is rejected (too large, unsupported
 *  format, or an auth/server failure). Single OK button — mirrors the native
 *  "Upload Failed" AlertDialog at MessageUploadButton.kt:70-82. The message can
 *  carry newlines (the supported-formats list), so render it line by line. */
private fun ChildrenBuilder.uploadErrorDialog(message: String, onDismiss: () -> Unit) {
    div {
        className = ClassName("modal-overlay")
        onClick = { onDismiss() }
        div {
            className = ClassName("modal-card sm")
            onClick = { it.stopPropagation() }
            div {
                className = ClassName("modal-title")
                +"Upload Failed"
            }
            div {
                className = ClassName("modal-subtitle tight")
                message.split("\n").forEachIndexed { i, line ->
                    if (i > 0) br {}
                    +line
                }
            }
            div {
                className = ClassName("modal-footer")
                button {
                    className = ClassName("btn-primary")
                    onClick = { onDismiss() }
                    +"OK"
                }
            }
        }
    }
}

/** Dialog shown when a kind:7 reaction fails. Mirrors the native ConfirmDialog in
 *  GroupScreen: a "Join Required" variant (offers Join) when the relay says we're an
 *  unknown member, a signer-failure variant when the signer could not sign, otherwise
 *  "Cannot React" + OK. Classification is shared via classifyReactionError. */
private fun ChildrenBuilder.reactionErrorDialog(message: String, onDismiss: () -> Unit, onJoin: () -> Unit) {
    val errorKind = classifyReactionError(message)
    val isUnknownMember = errorKind == ReactionErrorKind.JoinRequired
    div {
        className = ClassName("modal-overlay")
        onClick = { onDismiss() }
        div {
            className = ClassName("modal-card sm")
            onClick = { it.stopPropagation() }
            div {
                className = ClassName("modal-title")
                +(if (isUnknownMember) "Join Required" else "Cannot React")
            }
            div {
                className = ClassName("modal-subtitle tight")
                if (isUnknownMember) {
                    +"You need to join this group before you can react to messages."
                } else {
                    div {
                        +(
                            if (errorKind == ReactionErrorKind.SignerFailure) {
                                "Your signer could not sign the reaction. Please try again."
                            } else {
                                "This relay does not support reactions."
                            }
                            )
                    }
                    div {
                        className = ClassName("modal-reason")
                        +message
                    }
                }
            }
            div {
                className = ClassName("modal-footer")
                if (isUnknownMember) {
                    button {
                        className = ClassName("btn-text")
                        onClick = { onDismiss() }
                        +"Cancel"
                    }
                    button {
                        className = ClassName("btn-primary")
                        onClick = { onJoin() }
                        +"Join Group"
                    }
                } else {
                    button {
                        className = ClassName("btn-primary")
                        onClick = { onDismiss() }
                        +"OK"
                    }
                }
            }
        }
    }
}

/** Message the report modal is pinned to (author + event id). */
private data class ReportTargetRef(val pubkey: String, val messageId: String)

external interface MessageRowProps : Props {
    var domId: String
    var highlighted: Boolean
    var searchHit: Boolean
    var searchCurrent: Boolean
    var pubkey: String
    var name: String
    var avatarUrl: String?
    var time: String
    var content: String
    var tags: List<List<String>>
    var firstInGroup: Boolean
    var isAuthorAdmin: Boolean
    var reactions: Map<String, GroupManager.ReactionInfo>
    var myPubkey: String?
    var userMetadata: Map<String, UserMetadata>
    var messagesById: Map<String, NostrGroupClient.NostrMessage>
    var onEventRef: (String) -> Unit
    var onGroupRef: (String, String?) -> Unit
    var replyTo: ReplyPreviewData?
    var onReplyClick: () -> Unit
    var canDelete: Boolean

    /** The viewer authored this message (hides Report). */
    var isMine: Boolean

    /** Opens the NIP-56 report modal pinned to this message. */
    var onReport: () -> Unit

    /** The viewer is a group admin (shows the moderation section). */
    var isAdmin: Boolean
    var canZap: Boolean
    var zapTotalMsats: Long
    var zapCount: Int
    var zappedByMe: Boolean
    var messageLink: String
    var nevent: String
    var eventJson: String

    // Optimistic-send delivery status for the author's own message. Null = delivered.
    var status: GroupManager.MessageStatus?
    var onRetrySend: () -> Unit
    var onDismissFailed: () -> Unit
    var onUser: (String) -> Unit
    var onReply: () -> Unit
    var onReact: suspend (String) -> Unit
    var onZap: () -> Unit
    var onDelete: () -> Unit
}

private val MessageRow =
    FC<MessageRowProps> { props ->
        val (menuOpen, setMenuOpen) = useState { false }
        val (reactOpen, setReactOpen) = useState { false }
        // Emojis with an in-flight sendReaction. The reaction only appears
        // optimistically AFTER signEvent resolves, which on NIP-46 (Amber /
        // bunker) is a 1-2s round-trip with no feedback otherwise. We show a
        // pending badge + spinner during that window (same idea as the send
        // button's spinner), and drop it once the optimistic badge lands.
        val (pendingEmojis, setPendingEmojis) = useState<Set<String>> { emptySet() }
        val react: (String) -> Unit = { emoji ->
            if (emoji !in pendingEmojis) {
                setPendingEmojis { it + emoji }
                launchApp {
                    try {
                        props.onReact(emoji)
                    } finally {
                        setPendingEmojis { it - emoji }
                    }
                }
            }
        }
        // Esc closes whichever overlay is open (emoji picker or context menu),
        // matching the backdrop click. Document-level listener so it fires even
        // when focus sits in the picker's search input.
        useEscClose {
            if (reactOpen) setReactOpen(false)
            if (menuOpen) setMenuOpen(false)
        }
        // Anchor (viewport x,y) for the context menu; positioned/flipped by the effect below.
        val (menuAt, setMenuAt) = useState<Pair<Int, Int>?> { null }
        // Right-click opens the menu to the right of the cursor; the ⋯ button hangs it
        // off the button's right edge (opens leftward). Drives the flip math below.
        val (menuFromCursor, setMenuFromCursor) = useState { false }
        val menuRef = useRef<HTMLDivElement>(null)

        // Swipe-to-reply (touch): drag the row left past a threshold to reply (mirrors native).
        // The .msg-swipe-icon sits absolutely-positioned on the right edge under the row; its
        // opacity + color rise with the swipe distance, snapping to the primary blue once the
        // threshold is crossed. .chat-messages has `overflow-x: hidden` so the translateX
        // can't leak horizontal scroll onto the page.
        val rowRef = useRef<HTMLDivElement>(null)
        val swipeIconRef = useRef<HTMLDivElement>(null)
        val touchStartX = useRef(0.0)
        val touchStartY = useRef(0.0)
        val swiping = useRef(false)
        val swipeArmed = useRef(false)
        // Long-press to open the context menu on touch (mirrors the Android app). The
        // menu opens when the finger is LIFTED after a stationary hold — not mid-hold —
        // so the page never jumps and the ensuing synthesized click can be suppressed
        // (otherwise it lands on the overlay and instantly closes the menu).
        val longPressTimer = useRef(0)
        // True once the press has been held long enough (and hasn't moved) to count as
        // a long-press; the menu opens on touchend while this is set.
        val longPressReady = useRef(false)
        // Timestamp (ms) of a touch-opened menu, to swallow the trailing ghost click.
        val menuOpenedAt = useRef(0.0)

        // Place the fixed context menu at its anchor, flipping left/up when it would overflow.
        useEffect(menuOpen) {
            if (!menuOpen) return@useEffect
            val el = menuRef.current?.asDynamic() ?: return@useEffect
            val anchor = menuAt ?: return@useEffect
            val w = el.offsetWidth as Int
            val h = el.offsetHeight as Int
            val vw = window.innerWidth
            val vh = window.innerHeight
            // Cursor anchor opens rightward (left = x); button anchor opens leftward
            // (left = x - w). Either way, clamp into the viewport with an 8px margin.
            var left = if (menuFromCursor) anchor.first else anchor.first - w
            if (left + w > vw - 8) left = (vw - 8 - w).coerceAtLeast(8)
            if (left < 8) left = 8
            var top = anchor.second
            if (top + h > vh - 8) top = (anchor.second - h).coerceAtLeast(8)
            el.style.left = "${left}px"
            el.style.top = "${top}px"
            el.style.visibility = "visible"
        }

        div {
            id = ElementId(props.domId)
            className =
                ClassName(
                    (if (props.firstInGroup) "msg first" else "msg grouped") +
                        (if (menuOpen) " menu-open" else "") +
                        (if (props.highlighted) " highlight" else "") +
                        (
                            if (props.searchCurrent) {
                                " search-current"
                            } else if (props.searchHit) {
                                " search-hit"
                            } else {
                                ""
                            }
                            ),
                )
            ref = rowRef
            // Two-stage right-click (Telegram-style): the first right-click on a
            // message opens our app menu at the cursor and suppresses the browser's
            // native menu. A second right-click escapes to the native menu. Right-click
            // off any message keeps the browser default untouched. Right-click directly
            // on a hyperlink always keeps the native menu (so "Copy link address" copies
            // the actual URL, not our event link).
            onContextMenu = { event ->
                if (event.target.asDynamic().closest("a") != null) {
                    setMenuOpen(false)
                } else if (!menuOpen) {
                    // First right-click: open ours at the cursor and suppress native.
                    event.preventDefault()
                    setReactOpen(false)
                    menuOpenedAt.current = 0.0 // mouse/right-click: no ghost click to swallow
                    setMenuAt(event.clientX.toInt() to event.clientY.toInt())
                    setMenuFromCursor(true)
                    setMenuOpen(true)
                } else {
                    // Second right-click: our menu is open and the row sits above the
                    // overlay (.msg.menu-open z-index 60 > .ctx-overlay 50), so the
                    // event lands here, not on the overlay. Close ours and do NOT
                    // preventDefault, so the browser shows its native menu.
                    setMenuOpen(false)
                }
            }
            onTouchStart = { event ->
                val t = event.asDynamic().touches[0]
                touchStartX.current = t.clientX as Double
                touchStartY.current = t.clientY as Double
                swiping.current = false
                swipeArmed.current = false
                longPressReady.current = false
                window.clearTimeout(longPressTimer.current ?: 0)
                // Arm the long-press after a stationary hold; the menu itself opens on
                // touchend (below) so the page can't jump while the finger is down. A
                // light haptic signals that releasing now will open the menu.
                longPressTimer.current = window.setTimeout({
                    if (swiping.current != true) {
                        longPressReady.current = true
                        val nav = window.navigator.asDynamic()
                        if (nav.vibrate != null) nav.vibrate(15)
                    }
                }, 380)
            }
            onTouchMove = { event ->
                val t = event.asDynamic().touches[0]
                val dx = (t.clientX as Double) - (touchStartX.current ?: 0.0)
                val dy = (t.clientY as Double) - (touchStartY.current ?: 0.0)
                // Any real movement means this is a scroll/swipe, not a long-press.
                if (abs(dx) > 10.0 || abs(dy) > 10.0) {
                    window.clearTimeout(longPressTimer.current ?: 0)
                    longPressReady.current = false
                }
                if (swiping.current != true && abs(dx) > 10.0 && abs(dx) > abs(dy)) {
                    swiping.current = true
                }
                if (swiping.current == true && dx < 0) {
                    val off = dx.coerceAtLeast(-80.0)
                    val armed = off <= -56.0
                    swipeArmed.current = armed
                    rowRef.current?.asDynamic()?.style?.transform = "translateX(${off}px)"
                    // Drive the reply icon's opacity (0→1 as the row slides 0→56px)
                    // and flip to the primary blue once the threshold is crossed —
                    // same affordance as the native gesture's reveal layer.
                    val iconStyle = swipeIconRef.current?.asDynamic()?.style
                    if (iconStyle != null) {
                        val progress = (-off / 56.0).coerceIn(0.0, 1.0)
                        iconStyle.opacity = progress.toString()
                        iconStyle.color =
                            if (armed) "var(--color-primary)" else "var(--color-text-muted)"
                        // A tiny scale bump on cross helps the discrete "armed" moment
                        // read as a snap rather than a colour change in isolation.
                        // Keep the translateY(-50%) from the base CSS or the icon
                        // jumps off vertical center when the JS overrides transform.
                        // The icon is a child of the row, so the row's translateX would
                        // drag it left with the content; counter-translate by -off so it
                        // stays pinned to the right edge as the row slides (Android parity).
                        val scale = if (armed) "scale(1.15)" else "scale(1.0)"
                        iconStyle.transform = "translateX(${-off}px) translateY(-50%) $scale"
                    }
                }
            }
            onTouchEnd = { event ->
                window.clearTimeout(longPressTimer.current ?: 0)
                // Open the menu at the finger if the press was a stationary long-press
                // and didn't land on something interactive (link, avatar, badge, ...).
                val tgt = event.target.asDynamic()
                val onInteractive = tgt != null &&
                    tgt.closest("a, button, img, video, input, textarea, .clickable, .mention") != null
                val openMenu = longPressReady.current == true &&
                    swiping.current != true &&
                    !onInteractive &&
                    !menuOpen
                if (openMenu) {
                    // Suppress the synthesized mouse/click sequence so it can't hit the
                    // overlay and immediately close the menu we're about to open.
                    event.preventDefault()
                    setReactOpen(false)
                    menuOpenedAt.current = kotlin.js.Date.now()
                    setMenuAt((touchStartX.current ?: 0.0).toInt() to (touchStartY.current ?: 0.0).toInt())
                    setMenuFromCursor(true)
                    setMenuOpen(true)
                }
                // Animate the row back ONLY if it was actually swiped. Giving the row a
                // transform otherwise (even translateX(0)) makes it the containing block
                // for the position:fixed context menu, which would yank the menu out of
                // place the moment it opens.
                if (swiping.current == true) {
                    val el = rowRef.current?.asDynamic()
                    if (el != null) {
                        el.style.transition = "transform 0.15s ease"
                        el.style.transform = "translateX(0)"
                        window.setTimeout({
                            el.style.transition = ""
                            el.style.transform = ""
                        }, 180)
                    }
                    val iconStyle = swipeIconRef.current?.asDynamic()?.style
                    if (iconStyle != null) {
                        iconStyle.transition = "opacity 0.15s ease, transform 0.15s ease"
                        iconStyle.opacity = "0"
                        iconStyle.transform = "translateY(-50%) scale(1.0)"
                        window.setTimeout({
                            iconStyle.transition = ""
                        }, 180)
                    }
                }
                if (!openMenu && swipeArmed.current == true) props.onReply()
                swiping.current = false
                swipeArmed.current = false
                longPressReady.current = false
            }

            // Swipe-to-reply icon (revealed under the row as it slides left). Sits
            // behind everything via z-index so the row scrolls over it.
            div {
                ref = swipeIconRef
                className = ClassName("msg-swipe-icon")
                icon(Ic.Reply)
            }

            div {
                className = ClassName("msg-gutter")
                if (props.firstInGroup) {
                    WebAvatar {
                        url = props.avatarUrl
                        seed = props.pubkey
                        name = props.name
                        cls = "msg-avatar clickable"
                        onClick = { props.onUser(props.pubkey) }
                    }
                } else {
                    span {
                        className = ClassName("msg-hover-time")
                        +props.time
                    }
                }
            }
            div {
                className = ClassName("msg-body")
                if (props.firstInGroup) {
                    div {
                        className = ClassName("msg-meta")
                        span {
                            className = ClassName("msg-author clickable")
                            onClick = { props.onUser(props.pubkey) }
                            +props.name
                        }
                        if (props.isAuthorAdmin) {
                            span {
                                className = ClassName("msg-admin")
                                +"ADMIN"
                            }
                        }
                        span {
                            className = ClassName("msg-time")
                            +props.time
                        }
                    }
                }
                props.replyTo?.let { reply ->
                    div {
                        className = ClassName("msg-reply clickable")
                        onClick = { props.onReplyClick() }
                        div { className = ClassName("msg-reply-bar") }
                        div {
                            className = ClassName("msg-reply-content")
                            div {
                                className = ClassName("msg-reply-author")
                                +reply.author
                            }
                            div {
                                className = ClassName("msg-reply-text")
                                // Mentions were already resolved to @name in the parent
                                // build step (so we don't need userMetadata here); this
                                // pass swaps :shortcode: tokens for inline <img>.
                                renderTextWithEmojis(reply.content, extractEmojiMap(reply.tags))
                            }
                        }
                    }
                }
                div {
                    className = ClassName("msg-text")
                    renderMessageContent(
                        props.content,
                        props.tags,
                        props.userMetadata,
                        props.messagesById,
                        props.onUser,
                        props.onEventRef,
                        props.onGroupRef,
                    )
                    // Send-state icon (own messages: clock while sending, check when
                    // delivered) flows inline after the content so no extra line shifts
                    // the chat.
                    if (props.myPubkey != null && props.myPubkey == props.pubkey) {
                        sendStateIcon(props.status)
                    }
                }
                // Failure row (own messages only): Not delivered + Retry / Dismiss.
                if (props.myPubkey != null && props.myPubkey == props.pubkey) {
                    messageSendStatus(props.status, props.onRetrySend, props.onDismissFailed)
                }
                // Pending emojis still waiting on signEvent + relay; hide any
                // that the optimistic update already merged into props.reactions
                // so we never show a spinner badge next to its real counterpart.
                val visiblePending = pendingEmojis.filter { it !in props.reactions }
                if (props.reactions.isNotEmpty() || visiblePending.isNotEmpty()) {
                    div {
                        className = ClassName("msg-reactions")
                        props.reactions.forEach { (emoji, info) ->
                            val mine = props.myPubkey != null && props.myPubkey in info.reactors
                            button {
                                className = ClassName(if (mine) "reaction-badge mine" else "reaction-badge")
                                onClick = { react(emoji) }
                                val emojiUrl = info.emojiUrl
                                if (!emojiUrl.isNullOrBlank()) {
                                    img {
                                        className = ClassName("reaction-emoji")
                                        src = emojiUrl
                                        alt = emoji
                                    }
                                } else {
                                    +emoji
                                }
                                // Stacked avatars of who reacted (up to 3, overlapping), then +N overflow.
                                div {
                                    className = ClassName("reaction-avatars")
                                    info.reactors.take(3).forEach { reactor ->
                                        WebAvatar {
                                            url = props.userMetadata[reactor]?.picture
                                            seed = reactor
                                            this.name = displayName(reactor, props.userMetadata[reactor])
                                            cls = "reaction-avatar"
                                        }
                                    }
                                }
                                if (info.reactors.size > 3) {
                                    span {
                                        className = ClassName("reaction-count")
                                        +"+${info.reactors.size - 3}"
                                    }
                                }
                            }
                        }
                        visiblePending.forEach { emoji ->
                            div {
                                className = ClassName("reaction-badge pending")
                                +emoji
                                span { className = ClassName("reaction-spinner") }
                            }
                        }
                    }
                }
                if (props.zapTotalMsats > 0) {
                    div {
                        className = ClassName("msg-zaps")
                        zapBadge(props.zapTotalMsats, props.zapCount, props.zappedByMe) {
                            if (props.canZap) props.onZap()
                        }
                    }
                }
            }

            // Context menu (right-click / long-press)
            if (menuOpen) {
                div {
                    className = ClassName("ctx-overlay")
                    // Don't let overlay/menu touches reach the row's swipe + long-press
                    // handlers (they would transform the row and yank this fixed menu).
                    onTouchStart = { it.stopPropagation() }
                    onTouchMove = { it.stopPropagation() }
                    onTouchEnd = { it.stopPropagation() }
                    onClick = {
                        // Ignore the synthesized click that trails a touch-open
                        // (it would otherwise close the menu the instant it opens).
                        if (kotlin.js.Date.now() - (menuOpenedAt.current ?: 0.0) > 400.0) setMenuOpen(false)
                    }
                    // Second right-click (this overlay is now on top): close our menu
                    // and let the browser show its native menu, since we don't
                    // preventDefault here. Matches Telegram's two-stage behavior.
                    onContextMenu = { setMenuOpen(false) }
                }
                div {
                    ref = menuRef
                    className = ClassName("ctx-menu")
                    onTouchStart = { it.stopPropagation() }
                    onTouchMove = { it.stopPropagation() }
                    onTouchEnd = { it.stopPropagation() }
                    // Quick-reactions row (one tap to react) + an affordance to open
                    // the full picker. Mirrors the native menu's QuickReactionsRow.
                    div {
                        className = ClassName("ctx-reactions")
                        for (emoji in QuickReactions) {
                            button {
                                className = ClassName("ctx-reaction")
                                onClick = {
                                    react(emoji)
                                    setMenuOpen(false)
                                }
                                +emoji
                            }
                        }
                        button {
                            className = ClassName("ctx-reaction ctx-reaction-more")
                            title = "Add reaction"
                            onClick = {
                                setMenuOpen(false)
                                setReactOpen(true)
                            }
                            icon(Ic.EmojiEmotions)
                        }
                    }
                    div { className = ClassName("ctx-divider") }
                    ctxItem(Ic.Reply, "Reply") {
                        props.onReply()
                        setMenuOpen(false)
                    }
                    // Threads / saved are not implemented yet; shown disabled so the
                    // menu shape matches the prototype.
                    ctxItem(Ic.Forum, "Start thread here", disabled = true) {}
                    if (props.canZap) {
                        ctxItem(Ic.Bolt, "Zap", zap = true) {
                            props.onZap()
                            setMenuOpen(false)
                        }
                    }
                    ctxItem(Ic.Bookmark, "Save for later", disabled = true) {}
                    if (!props.isMine) {
                        ctxItem(Ic.Shield, "Report") {
                            props.onReport()
                            setMenuOpen(false)
                        }
                    }
                    div { className = ClassName("ctx-divider") }
                    ctxItem(Ic.ContentCopy, "Copy text") {
                        copyToClipboard(props.content)
                        setMenuOpen(false)
                    }
                    ctxItem(Ic.Link, "Copy event link") {
                        copyToClipboard(props.messageLink)
                        setMenuOpen(false)
                    }
                    // Prototype: shareable NIP-19 event reference.
                    ctxItem(Ic.Code, "Copy nevent") {
                        copyToClipboard(props.nevent)
                        setMenuOpen(false)
                    }
                    ctxItem(Ic.Code, "Copy event JSON") {
                        copyToClipboard(props.eventJson)
                        setMenuOpen(false)
                    }
                    if (props.isAdmin) {
                        div { className = ClassName("ctx-divider") }
                        // Disabled until the pinning backend exists (matches native).
                        ctxItem(Ic.PushPin, "Pin message", disabled = true) {}
                    }
                    if (props.canDelete) {
                        div { className = ClassName("ctx-divider") }
                        ctxItem(Ic.Delete, "Delete message", danger = true) {
                            props.onDelete()
                            setMenuOpen(false)
                        }
                    }
                }
            }

            // Reaction emoji picker (opened from the 😊 hover action or the context menu).
            if (reactOpen) {
                div {
                    className = ClassName("emoji-overlay")
                    onClick = { setReactOpen(false) }
                    EmojiPicker {
                        onPick = { emoji ->
                            react(emoji)
                            setReactOpen(false)
                        }
                    }
                }
            }
        }
    }

internal fun ChildrenBuilder.ctxItem(
    ic: Ic,
    label: String,
    danger: Boolean = false,
    zap: Boolean = false,
    disabled: Boolean = false,
    onSelect: () -> Unit,
) {
    div {
        className =
            ClassName(
                when {
                    disabled -> "ctx-item disabled"
                    danger -> "ctx-item danger"
                    zap -> "ctx-item zap"
                    else -> "ctx-item"
                },
            )
        if (disabled) title = "Coming soon"
        onClick = { if (!disabled) onSelect() }
        span {
            className = ClassName("ctx-item-icon")
            icon(ic)
        }
        span { +label }
    }
}

/** A moderation/system event row (join / role change / removed / left), matching native. */
private fun ChildrenBuilder.systemEventRow(
    event: WebChatItem.SystemEvent,
    userMetadata: Map<String, UserMetadata>,
    onUser: (String) -> Unit,
) {
    val typeClass =
        when (event.type) {
            SystemEventType.JOINED -> "joined"
            SystemEventType.ROLE_CHANGED -> "role"
            SystemEventType.REMOVED -> "removed"
            SystemEventType.LEFT -> "left"
        }
    val typeIcon =
        when (event.type) {
            SystemEventType.JOINED -> Ic.Login
            SystemEventType.ROLE_CHANGED -> Ic.Shield
            SystemEventType.REMOVED -> Ic.PersonRemove
            SystemEventType.LEFT -> Ic.Logout
        }
    div {
        // Stable React key — without it React reconciles SystemEvent rows by
        // position. When pagination prepends older system events at the top,
        // positional reconciliation re-renders existing rows in place with the
        // older data and appends fresh rows at the bottom, scrambling the DOM
        // order and breaking the scroll anchor. All other row types (Message,
        // DateSeparator, NewMessagesDivider) already key correctly.
        key = "sys-${event.id}"
        // Stable DOM id so the pagination scroll restore can anchor to a
        // system event row (the only thing visible in some join/leave-heavy
        // groups when the user paginates up).
        id = ElementId("sys-${event.id}")
        className = ClassName("sys-event")
        div {
            className = ClassName("sys-event-icon $typeClass")
            icon(typeIcon)
        }
        if (event.totalUsers > 1) {
            div {
                className = ClassName("sys-event-avatars")
                (listOf(event.pubkey) + event.additionalUsers).take(4).forEach { pk ->
                    WebAvatar {
                        url = userMetadata[pk]?.picture
                        seed = pk
                        name = displayName(pk, userMetadata[pk])
                        cls = "sys-event-avatar"
                    }
                }
                val overflow = event.totalUsers - 4
                if (overflow > 0) {
                    span {
                        className = ClassName("sys-event-overflow")
                        +"+$overflow"
                    }
                }
            }
        } else {
            WebAvatar {
                url = userMetadata[event.pubkey]?.picture
                seed = event.pubkey
                name = displayName(event.pubkey, userMetadata[event.pubkey])
                cls = "sys-event-avatar clickable"
                onClick = { onUser(event.pubkey) }
            }
        }
        span {
            className = ClassName("sys-event-text")
            span {
                className = ClassName("sys-event-name")
                +(if (event.totalUsers > 1) "${event.totalUsers} members" else displayName(event.pubkey, userMetadata[event.pubkey]))
            }
            +" ${if (event.totalUsers > 1) pluralizeSystemAction(event.action) else event.action}"
        }
        span {
            className = ClassName("sys-event-time")
            +formatTimestamp(event.createdAt)
        }
    }
}

private fun eventJsonOf(message: NostrGroupClient.NostrMessage): String = buildJsonObject {
    put("id", message.id)
    put("pubkey", message.pubkey)
    put("created_at", message.createdAt)
    put("kind", message.kind)
    put("content", message.content)
    putJsonArray("tags") {
        message.tags.forEach { tag -> addJsonArray { tag.forEach { add(it) } } }
    }
}.toString()

private val URL_REGEX =
    Regex(
        "(data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]+)" +
            "|(https?://[^\\s]+)" +
            // ws(s):// relay urls, incl. the NIP-29 group address `wss://relay'groupId`
            // (mirrors native MessageContentParser so the apostrophe form renders a group card).
            "|(wss?://[^\\s<>\"]+)" +
            "|(nostr:(?:npub1|nprofile1|nevent1|note1|naddr1)[0-9a-z]+)" +
            "|\\b((?:npub1|nprofile1|nevent1|note1|naddr1)[0-9a-z]{20,})" +
            // Bare NIP-29 group address without the scheme, `relay.host'groupId` (the share field's
            // copy form). Dotted host + 4+ char id so ordinary apostrophes don't match. No
            // lookbehind here on purpose: older Safari throws on it and would break all rendering.
            "|([a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)+'[a-zA-Z0-9]{4,})",
    )
private val IMAGE_EXT = Regex("\\.(jpg|jpeg|png|gif|webp|avif|svg)(\\?.*)?$", RegexOption.IGNORE_CASE)
private val VIDEO_EXT = Regex("\\.(mp4|webm|mov|avi|mkv|m4v|ogv)(\\?.*)?$", RegexOption.IGNORE_CASE)
private val AUDIO_EXT = Regex("\\.(mp3|wav|ogg|flac|m4a|aac|opus)(\\?.*)?$", RegexOption.IGNORE_CASE)

// Detect YouTube links (watch / shorts / live / embed / youtu.be) and capture
// the 11-char video id, mirroring the native MessageContentParser regex.
private val YOUTUBE_REGEX =
    Regex(
        """(?:youtube\.com/(?:watch\?v=|shorts/|live/|embed/)|youtu\.be/)([\w-]{11})""",
        RegexOption.IGNORE_CASE,
    )

private val CODE_BLOCK_REGEX = Regex("```(?:([A-Za-z0-9_+-]*)\n)?([\\s\\S]*?)```")
private val INLINE_CODE_REGEX = Regex("`([^`\n]+)`")

// NIP-30 custom emoji. Mirrors MessageContentParser in commonMain: tag = ["emoji",
// shortcode, url]; the body uses :shortcode: which renders as an inline image.
private val EMOJI_SHORTCODE_REGEX = Regex(""":([a-zA-Z0-9_-]+):""")

private fun extractEmojiMap(tags: List<List<String>>): Map<String, String> = tags.asSequence()
    .filter { it.size >= 3 && it[0] == "emoji" }
    .mapNotNull { tag ->
        val shortcode = tag[1]
        val url = tag[2]
        if (shortcode.isBlank()) return@mapNotNull null
        if (!shortcode.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' }) return@mapNotNull null
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return@mapNotNull null
        shortcode to url
    }.toMap()

/** NIP-92 imeta thumbnails: maps a media url to its poster image url (thumb, else
 *  image). Used as the <video> poster so a click-to-load player shows a preview
 *  frame instead of a black box — without fetching the video itself. */
private fun extractVideoPosters(tags: List<List<String>>): Map<String, String> {
    val result = mutableMapOf<String, String>()
    for (tag in tags) {
        if (tag.isEmpty() || tag[0] != "imeta") continue
        var url: String? = null
        var thumb: String? = null
        for (i in 1 until tag.size) {
            val field = tag[i]
            when {
                field.startsWith("url ") -> url = field.removePrefix("url ")
                field.startsWith("thumb ") -> thumb = field.removePrefix("thumb ")
                field.startsWith("image ") -> if (thumb == null) thumb = field.removePrefix("image ")
            }
        }
        if (url != null && thumb != null) result[url] = thumb
    }
    return result
}

/** Emit [text] verbatim, replacing :shortcode: tokens with `<img>` when in [emojiMap]. */
private fun ChildrenBuilder.renderTextWithEmojis(text: String, emojiMap: Map<String, String>) {
    if (emojiMap.isEmpty() || ':' !in text) {
        +text
        return
    }
    var last = 0
    for (m in EMOJI_SHORTCODE_REGEX.findAll(text)) {
        val url = emojiMap[m.groupValues[1]] ?: continue
        if (m.range.first > last) +text.substring(last, m.range.first)
        img {
            className = ClassName("msg-emoji")
            src = url
            alt = m.value
        }
        last = m.range.last + 1
    }
    if (last < text.length) +text.substring(last)
}

/**
 * Render message text: fenced code blocks (```), then inline `code`, then links / images /
 * videos / NIP-27 mentions / event & group refs in the remaining text — mirroring native.
 */
// JS RegExp rejects inline (?m); use the MULTILINE option so `^` matches each line start.
private val BLOCKQUOTE_REGEX = Regex("^[ \\t]*>[ \\t]?.*(?:\\r?\\n[ \\t]*>[ \\t]?.*)*", RegexOption.MULTILINE)
private val BLOCKQUOTE_LINE_PREFIX = Regex("^[ \\t]*>[ \\t]?", RegexOption.MULTILINE)

internal fun ChildrenBuilder.renderMessageContent(
    content: String,
    tags: List<List<String>>,
    userMetadata: Map<String, UserMetadata>,
    messagesById: Map<String, NostrGroupClient.NostrMessage>,
    onUser: (String) -> Unit,
    onEventRef: (String) -> Unit,
    onGroupRef: (String, String?) -> Unit,
) {
    val emojiMap = extractEmojiMap(tags)
    val posters = extractVideoPosters(tags)
    val dims = Nip68.extractImetaDimensions(tags)
    // Split blockquote blocks (`> ` line runs) out first; each renders in a quote box with its
    // inner text (markers stripped) run back through the normal body. Mirrors native Blockquote.
    var qLast = 0
    for (q in BLOCKQUOTE_REGEX.findAll(content)) {
        if (q.range.first > qLast) {
            renderBody(content.substring(qLast, q.range.first), emojiMap, posters, dims, userMetadata, messagesById, onUser, onEventRef, onGroupRef)
        }
        div {
            className = ClassName("msg-quote")
            renderBody(q.value.replace(BLOCKQUOTE_LINE_PREFIX, ""), emojiMap, posters, dims, userMetadata, messagesById, onUser, onEventRef, onGroupRef)
        }
        qLast = q.range.last + 1
    }
    if (qLast < content.length) {
        renderBody(content.substring(qLast), emojiMap, posters, dims, userMetadata, messagesById, onUser, onEventRef, onGroupRef)
    }
}

/** The message body pipeline (code fences → inline code → entities → formatting), quote-free. */
private fun ChildrenBuilder.renderBody(
    content: String,
    emojiMap: Map<String, String>,
    posters: Map<String, String>,
    dims: Map<String, Pair<Int, Int>>,
    userMetadata: Map<String, UserMetadata>,
    messagesById: Map<String, NostrGroupClient.NostrMessage>,
    onUser: (String) -> Unit,
    onEventRef: (String) -> Unit,
    onGroupRef: (String, String?) -> Unit,
) {
    var last = 0
    for (block in CODE_BLOCK_REGEX.findAll(content)) {
        if (block.range.first > last) {
            renderInline(content.substring(last, block.range.first), emojiMap, posters, dims, userMetadata, messagesById, onUser, onEventRef, onGroupRef)
        }
        val lang = block.groupValues[1].takeIf { it.isNotBlank() }
        div {
            className = ClassName("code-block")
            if (lang != null) {
                div {
                    className = ClassName("code-lang")
                    +lang
                }
            }
            pre {
                className = ClassName("code-pre")
                +block.groupValues[2].trim('\n')
            }
        }
        last = block.range.last + 1
    }
    if (last < content.length) {
        renderInline(content.substring(last), emojiMap, posters, dims, userMetadata, messagesById, onUser, onEventRef, onGroupRef)
    }
}

/** Render a non-code text span: inline `code` as monospace, the rest through [renderEntities]. */
private fun ChildrenBuilder.renderInline(
    text: String,
    emojiMap: Map<String, String>,
    posters: Map<String, String>,
    dims: Map<String, Pair<Int, Int>>,
    userMetadata: Map<String, UserMetadata>,
    messagesById: Map<String, NostrGroupClient.NostrMessage>,
    onUser: (String) -> Unit,
    onEventRef: (String) -> Unit,
    onGroupRef: (String, String?) -> Unit,
) {
    var last = 0
    for (m in INLINE_CODE_REGEX.findAll(text)) {
        if (m.range.first > last) {
            renderEntities(text.substring(last, m.range.first), emojiMap, posters, dims, userMetadata, messagesById, onUser, onEventRef, onGroupRef)
        }
        code {
            className = ClassName("msg-code")
            +m.groupValues[1]
        }
        last = m.range.last + 1
    }
    if (last < text.length) {
        renderEntities(text.substring(last), emojiMap, posters, dims, userMetadata, messagesById, onUser, onEventRef, onGroupRef)
    }
}

private fun ChildrenBuilder.renderEntities(
    content: String,
    emojiMap: Map<String, String>,
    posters: Map<String, String>,
    dims: Map<String, Pair<Int, Int>>,
    userMetadata: Map<String, UserMetadata>,
    messagesById: Map<String, NostrGroupClient.NostrMessage>,
    onUser: (String) -> Unit,
    onEventRef: (String) -> Unit,
    onGroupRef: (String, String?) -> Unit,
) {
    var last = 0
    for (match in URL_REGEX.findAll(content)) {
        if (match.range.first > last) {
            renderFormattedText(content.substring(last, match.range.first), emojiMap)
        }
        val token = match.value
        if (token.startsWith("data:image/")) {
            ChatImage {
                imageUrl = token
                dimensions = dims[token]
            }
        } else if (token.startsWith("http")) {
            val url = token.trimEnd('.', ',', ')', '!', '?', ';', ':')
            val youtubeId = YOUTUBE_REGEX.find(url)?.groupValues?.get(1)
            if (youtubeId != null) {
                YouTubeEmbed { videoId = youtubeId }
            } else if (IMAGE_EXT.containsMatchIn(url)) {
                ChatImage {
                    imageUrl = url
                    dimensions = dims[url]
                }
            } else if (VIDEO_EXT.containsMatchIn(url)) {
                ChatVideo {
                    videoUrl = url
                    posterUrl = posters[url]
                    dimensions = dims[url]
                }
            } else if (AUDIO_EXT.containsMatchIn(url)) {
                ChatAudio {
                    audioUrl = url
                }
            } else {
                a {
                    className = ClassName("msg-link")
                    href = url
                    asDynamic().target = "_blank"
                    rel = "noopener noreferrer"
                    +url
                }
            }
            if (url.length < token.length) +token.substring(url.length)
        } else if (token.startsWith("wss://") || token.startsWith("ws://")) {
            // NIP-29 group address `wss://relay'groupId` renders as a tappable group card,
            // mirroring native MessageContent (RelayPart). A bare relay url stays plain text.
            val apostrophe = token.indexOf('\'')
            if (apostrophe > 0) {
                GroupLinkCard {
                    groupId = token.substring(apostrophe + 1)
                    relayUrl = token.substring(0, apostrophe)
                    onNavigate = onGroupRef
                }
            } else {
                +token
            }
        } else if (token.contains('\'')) {
            // Bare NIP-29 group address `relay'groupId` (no scheme): normalize with wss:// and
            // render the same group card as the scheme form.
            val apostrophe = token.indexOf('\'')
            GroupLinkCard {
                groupId = token.substring(apostrophe + 1)
                relayUrl = "wss://" + token.substring(0, apostrophe)
                onNavigate = onGroupRef
            }
        } else {
            // A mention standing alone (on its own line) keeps the rich form (group card,
            // @name); inline with other text it becomes a compact avatar+name chip, matching
            // the prototype. Per-line, not whole-message, so an invite DM ("You've been
            // added to X" + the naddr on the next line) still renders the group card.
            val standalone = content.split('\n').any { it.trim() == token }
            when (val entity = Nip19.decode(token.removePrefix("nostr:"))) {
                is Nip19.Entity.Npub ->
                    if (standalone) {
                        mentionSpan(entity.pubkey, userMetadata, onUser)
                    } else {
                        userMentionChip(entity.pubkey, userMetadata, onUser)
                    }
                is Nip19.Entity.Nprofile ->
                    if (standalone) {
                        mentionSpan(entity.pubkey, userMetadata, onUser)
                    } else {
                        userMentionChip(entity.pubkey, userMetadata, onUser)
                    }
                is Nip19.Entity.Nevent ->
                    QuotedEvent {
                        eventId = entity.eventId
                        relays = entity.relays
                        author = entity.author
                        kind = entity.kind
                        localById = messagesById
                        onScrollTo = onEventRef
                        this.onUser = onUser
                        this.onGroupRef = onGroupRef
                    }
                is Nip19.Entity.Note ->
                    QuotedEvent {
                        eventId = entity.eventId
                        relays = emptyList()
                        author = null
                        kind = null
                        localById = messagesById
                        onScrollTo = onEventRef
                        this.onUser = onUser
                        this.onGroupRef = onGroupRef
                    }
                is Nip19.Entity.Naddr ->
                    if (entity.kind == 39000) {
                        if (standalone) {
                            GroupLinkCard {
                                groupId = entity.identifier
                                relayUrl = entity.relays.firstOrNull()
                                onNavigate = onGroupRef
                            }
                        } else {
                            GroupMentionChip {
                                groupId = entity.identifier
                                relayUrl = entity.relays.firstOrNull()
                                onNavigate = onGroupRef
                            }
                        }
                    } else {
                        a {
                            className = ClassName("msg-link")
                            href = "https://njump.me/${token.removePrefix("nostr:")}"
                            asDynamic().target = "_blank"
                            rel = "noopener noreferrer"
                            +"[article]"
                        }
                    }
                else -> +token
            }
        }
        last = match.range.last + 1
    }
    if (last < content.length) renderFormattedText(content.substring(last), emojiMap)
}

// Inline formatting tokens, longest markers first so **bold** wins over *bold* and ~~/|| aren't
// split. Mirrors native MessageContentParser: * and ** are bold (additive), _ italic, ~~ strike,
// || spoiler.
private val INLINE_FORMAT_REGEX =
    Regex("~~[\\s\\S]+?~~|\\|\\|[\\s\\S]+?\\|\\||\\*\\*[\\s\\S]+?\\*\\*|\\*[^*\\n]+?\\*|_[^_\\n]+?_")

/**
 * Render inline *bold* / **bold**, _italic_, ~~strikethrough~~ and ||spoiler|| in a non-URL text
 * run, delegating the remaining text to [renderTextWithEmojis]. Mirrors native MessageContent.
 */
private fun ChildrenBuilder.renderFormattedText(text: String, emojiMap: Map<String, String>) {
    var last = 0
    for (m in INLINE_FORMAT_REGEX.findAll(text)) {
        if (m.range.first > last) renderTextWithEmojis(text.substring(last, m.range.first), emojiMap)
        val token = m.value
        when {
            token.startsWith("~~") ->
                s {
                    className = ClassName("msg-strike")
                    renderTextWithEmojis(token.substring(2, token.length - 2), emojiMap)
                }
            token.startsWith("||") -> Spoiler { content = token.substring(2, token.length - 2) }
            token.startsWith("**") -> strong { renderTextWithEmojis(token.substring(2, token.length - 2), emojiMap) }
            token.startsWith("*") -> strong { renderTextWithEmojis(token.substring(1, token.length - 1), emojiMap) }
            else -> em { renderTextWithEmojis(token.substring(1, token.length - 1), emojiMap) }
        }
        last = m.range.last + 1
    }
    if (last < text.length) renderTextWithEmojis(text.substring(last), emojiMap)
}

private fun ChildrenBuilder.mentionSpan(pubkey: String, userMetadata: Map<String, UserMetadata>, onUser: (String) -> Unit) {
    span {
        className = ClassName("msg-mention")
        onClick = { onUser(pubkey) }
        +"@${displayName(pubkey, userMetadata[pubkey])}"
    }
}

/** Inline user mention chip (prototype): circular avatar + display name, tinted and tappable. */
private fun ChildrenBuilder.userMentionChip(pubkey: String, userMetadata: Map<String, UserMetadata>, onUser: (String) -> Unit) {
    val name = displayName(pubkey, userMetadata[pubkey])
    span {
        className = ClassName("msg-mention-chip")
        onClick = { onUser(pubkey) }
        WebAvatar {
            url = userMetadata[pubkey]?.picture
            seed = pubkey
            this.name = name
            cls = "msg-mention-chip-avatar"
        }
        +name
    }
}

private external interface QuotedEventProps : Props {
    var eventId: String
    var relays: List<String>
    var author: String?

    /** nevent kind hint (null for a bare note) — shown on the not-found card. */
    var kind: Int?
    var localById: Map<String, NostrGroupClient.NostrMessage>
    var onScrollTo: (String) -> Unit
    var onUser: (String) -> Unit
    var onGroupRef: (String, String?) -> Unit
}

/**
 * A decoded nevent/note reference rendered as a quote card (author + content), mirroring the
 * native QuotedEvent. Resolves the event from the current group or the global event cache, and
 * fetches it by id from the nevent's relay hints when unknown. Clicking scrolls to it if loaded.
 */
private val QuotedEvent =
    FC<QuotedEventProps> { props ->
        val repo = AppModule.nostrRepository
        val cached = useStateFlow(repo.cachedEvents)
        val userMetadata = useStateFlow(repo.userMetadata)

        val local = props.localById[props.eventId]
        val cachedEv = cached[props.eventId]
        val pubkey = local?.pubkey ?: cachedEv?.pubkey ?: props.author
        val content = local?.content ?: cachedEv?.content
        val createdAt = local?.createdAt ?: cachedEv?.createdAt
        val quotedTags = local?.tags ?: cachedEv?.tags ?: emptyList()

        // Flips true when the event never resolves (e.g. its relay isn't connected), so the card
        // shows "Event not found" instead of spinning forever — parity with native's 5s give-up.
        val (notFound, setNotFound) = useState { false }
        val (menuOpen, setMenuOpen) = useState { false }

        useEffect(props.eventId) {
            setNotFound(false)
            if (props.eventId !in cached && local == null) {
                launchApp { repo.requestEventById(props.eventId, props.relays, props.author) }
            }
        }
        // Give up after 5s of no resolution. Re-runs when content arrives (cancels the timer).
        useEffect(content, props.eventId) {
            if (content != null) return@useEffect
            val timer = window.setTimeout({ setNotFound(true) }, 5000)
            try {
                awaitCancellation()
            } finally {
                window.clearTimeout(timer)
            }
        }
        useEffect(pubkey) {
            val pk = pubkey
            if (pk != null && userMetadata[pk] == null) {
                launchApp { repo.requestUserMetadata(setOf(pk)) }
            }
        }
        // Resolve names for pubkeys mentioned inside the quoted text (nostr:npub / nprofile).
        useEffect(content) {
            val c = content ?: return@useEffect
            val missing =
                NOSTR_URI_REGEX.findAll(c)
                    .mapNotNull { m ->
                        runCatching {
                            when (val e = Nip19.decode(m.groupValues[1])) {
                                is Nip19.Entity.Npub -> e.pubkey
                                is Nip19.Entity.Nprofile -> e.pubkey
                                else -> null
                            }
                        }.getOrNull()
                    }
                    .filter { userMetadata[it] == null }
                    .toSet()
            if (missing.isNotEmpty()) launchApp { repo.requestUserMetadata(missing) }
        }

        // A resolved NIP-29 group event (carries an `h` tag) that is NOT a message in the current
        // group is a forwarded reference: it renders with a "forwarded from <group>" header that
        // opens the source group (mirroring native's ForwardedEventCard). A plain quote would be
        // unscrollable here and the event is only fetchable on its own group relay. The relay rides
        // on the nevent hint (embedded by the "copy nevent" action).
        val groupsByRelay = useStateFlow(repo.groupsByRelay)
        val refGroupId =
            cachedEv?.tags?.firstOrNull { it.size >= 2 && it.getOrNull(0) == "h" }?.getOrNull(1)
        val isForwarded = local == null && refGroupId != null
        // Navigate using the nevent's own relay hint — that's where the event lives. The
        // navigation effect connects that relay on demand if it isn't already, then loads the
        // group + scrolls to the message. (The group name/avatar below resolve from any relay we
        // already know the group on, so the header still fills in.)
        val fwdRelay = props.relays.firstOrNull()
        val fwdGroupMeta = refGroupId?.let { gid -> groupsByRelay.values.flatten().firstOrNull { it.id == gid } }
        val fwdGroupName = fwdGroupMeta?.name?.takeIf { it.isNotBlank() } ?: refGroupId.orEmpty()
        useEffect(refGroupId, fwdRelay, fwdGroupMeta?.name) {
            if (isForwarded && refGroupId != null && fwdRelay != null && fwdGroupMeta?.name == null) {
                launchApp { repo.fetchGroupPreview(refGroupId, fwdRelay) }
            }
        }

        // Reply preview: if the quoted event is itself a reply (q tag), resolve the parent so the
        // card shows "↳ <author>: <snippet>" like native, or "Loading reply…" until it arrives.
        val replyParentId =
            quotedTags.firstOrNull {
                it.size >= 2 && it[0] == "q" && it[1].length == 64 && it[1].all { c -> c.isLetterOrDigit() }
            }?.getOrNull(1)
        val replyLocal = replyParentId?.let { props.localById[it] }
        val replyCached = replyParentId?.let { cached[it] }
        val replyPubkey = replyLocal?.pubkey ?: replyCached?.pubkey
        val replyContent = replyLocal?.content ?: replyCached?.content
        useEffect(replyParentId) {
            val pid = replyParentId ?: return@useEffect
            if (pid !in cached && replyLocal == null) {
                launchApp { repo.requestEventById(pid, props.relays, null) }
            }
        }
        useEffect(replyPubkey) {
            val pk = replyPubkey
            if (pk != null && userMetadata[pk] == null) launchApp { repo.requestUserMetadata(setOf(pk)) }
        }

        // Clickable only when the referenced event is a local message we can scroll to; an
        // external nevent reference has nowhere to scroll, so it stays static (no pointer).
        val scrollable = local != null

        div {
            className =
                ClassName(
                    when {
                        isForwarded -> "quoted-event quoted-event-static forwarded-event"
                        scrollable -> "quoted-event"
                        else -> "quoted-event quoted-event-static"
                    },
                )
            if (scrollable) onClick = { props.onScrollTo(props.eventId) }
            if (isForwarded && refGroupId != null) {
                div {
                    className = ClassName("forwarded-event-head")
                    // Open the source group AND scroll to + highlight this message (same path as a
                    // ?...&e= deep link): pushRoute fires hashchange, AppFrame feeds messageId to
                    // ChatScreen.scrollToMessageId. Falls back to a plain group open if no relay.
                    onClick = { ev ->
                        ev.stopPropagation()
                        if (fwdRelay != null) {
                            pushRoute(GroupRoute(relayUrl = fwdRelay, groupId = refGroupId, messageId = props.eventId))
                        } else {
                            props.onGroupRef(refGroupId, null)
                        }
                    }
                    icon(Ic.Reply)
                    span {
                        className = ClassName("forwarded-event-from")
                        +"forwarded from"
                    }
                    WebAvatar {
                        url = fwdGroupMeta?.picture
                        seed = refGroupId
                        kind = AvatarKind.GROUP
                        this.name = fwdGroupName
                        cls = "forwarded-event-group-avatar"
                    }
                    span {
                        className = ClassName("forwarded-event-group")
                        +fwdGroupName
                    }
                }
            }
            if (content != null && pubkey != null) {
                div {
                    className = ClassName("quoted-event-head")
                    WebAvatar {
                        url = userMetadata[pubkey]?.picture
                        seed = pubkey
                        this.name = displayName(pubkey, userMetadata[pubkey])
                        cls = "quoted-event-avatar"
                        onClick = { props.onUser(pubkey) }
                    }
                    span {
                        className = ClassName("quoted-event-author")
                        onClick = { ev ->
                            ev.stopPropagation()
                            props.onUser(pubkey)
                        }
                        +displayName(pubkey, userMetadata[pubkey])
                    }
                    if (createdAt != null) {
                        span {
                            className = ClassName("quoted-event-time")
                            +formatDateTime(createdAt)
                        }
                    }
                    a {
                        className = ClassName("quoted-event-open")
                        href = "https://jumble.social/notes/${Nip19.encodeNote(props.eventId)}"
                        asDynamic().target = "_blank"
                        rel = "noopener noreferrer"
                        title = "Open in another client"
                        onClick = { it.stopPropagation() }
                        icon(Ic.OpenInNew)
                    }
                }
                if (replyParentId != null) {
                    if (replyContent != null && replyPubkey != null) {
                        div {
                            className = ClassName("quoted-event-reply")
                            span {
                                className = ClassName("quoted-event-reply-arrow")
                                +"↳"
                            }
                            WebAvatar {
                                url = userMetadata[replyPubkey]?.picture
                                seed = replyPubkey
                                this.name = displayName(replyPubkey, userMetadata[replyPubkey])
                                cls = "quoted-event-reply-avatar"
                            }
                            span {
                                className = ClassName("quoted-event-reply-author")
                                +displayName(replyPubkey, userMetadata[replyPubkey])
                            }
                            span {
                                className = ClassName("quoted-event-reply-text")
                                +replyPreviewText(replyContent, userMetadata, 100)
                            }
                        }
                    } else {
                        div {
                            className = ClassName("quoted-event-reply quoted-event-reply-loading")
                            span {
                                className = ClassName("quoted-event-reply-arrow")
                                +"↳"
                            }
                            +"Loading reply…"
                        }
                    }
                }
                div {
                    className = ClassName("quoted-event-content")
                    renderMessageContent(
                        content.trim(),
                        quotedTags,
                        userMetadata,
                        props.localById,
                        props.onUser,
                        props.onScrollTo,
                        props.onGroupRef,
                    )
                }
            } else if (notFound) {
                div {
                    className = ClassName("quoted-event-notfound")
                    div {
                        className = ClassName("quoted-event-notfound-title")
                        icon(Ic.Warning)
                        span {
                            className = ClassName("quoted-event-notfound-label")
                            +"Event not found"
                        }
                        div {
                            className = ClassName("quoted-event-menu")
                            button {
                                className = ClassName("quoted-event-menu-btn")
                                title = "More options"
                                onClick = { ev ->
                                    ev.stopPropagation()
                                    setMenuOpen(!menuOpen)
                                }
                                icon(Ic.MoreVert)
                            }
                            if (menuOpen) {
                                div {
                                    className = ClassName("quoted-event-menu-backdrop")
                                    onClick = { ev ->
                                        ev.stopPropagation()
                                        setMenuOpen(false)
                                    }
                                }
                                div {
                                    className = ClassName("quoted-event-menu-pop")
                                    button {
                                        className = ClassName("quoted-event-menu-item")
                                        onClick = { ev ->
                                            ev.stopPropagation()
                                            val parsed =
                                                buildJsonObject {
                                                    put("type", if (props.kind != null) "nevent" else "note")
                                                    put("event_id", props.eventId)
                                                    props.kind?.let { put("kind", it) }
                                                    props.author?.let { put("author", it) }
                                                    putJsonArray("relays") { props.relays.forEach { add(it) } }
                                                }.toString()
                                            copyToClipboard(parsed)
                                            setMenuOpen(false)
                                        }
                                        +"Copy Parsed JSON"
                                    }
                                }
                            }
                        }
                    }
                    props.kind?.let {
                        div {
                            className = ClassName("quoted-event-notfound-row")
                            +"kind: $it"
                        }
                    }
                    div {
                        className = ClassName("quoted-event-notfound-row")
                        +"id: ${props.eventId}"
                    }
                    props.author?.let {
                        div {
                            className = ClassName("quoted-event-notfound-row")
                            +"author: $it"
                        }
                    }
                    if (props.relays.isNotEmpty()) {
                        div {
                            className = ClassName("quoted-event-notfound-row")
                            +"relays: ${props.relays.joinToString(", ")}"
                        }
                    }
                }
            } else {
                span {
                    className = ClassName("quoted-event-loading")
                    +"Loading quoted message…"
                }
            }
        }
    }

private external interface GroupLinkCardProps : Props {
    var groupId: String
    var relayUrl: String?
    var onNavigate: (String, String?) -> Unit
}

/**
 * A decoded naddr group reference (kind 39000) rendered as a card (avatar + name + about +
 * relay), mirroring the native GroupLinkCard. Resolves metadata from groupsByRelay and fetches
 * a preview if unknown; clicking opens the group.
 */
private val GroupLinkCard =
    FC<GroupLinkCardProps> { props ->
        val repo = AppModule.nostrRepository
        val groupsByRelay = useStateFlow(repo.groupsByRelay)
        val meta = groupsByRelay.values.flatten().firstOrNull { it.id == props.groupId }
        val name = meta?.name?.takeIf { it.isNotBlank() } ?: props.groupId
        val relayDisplay = props.relayUrl?.removePrefix("wss://")?.removePrefix("ws://")?.trimEnd('/')

        useEffect(props.groupId, props.relayUrl, meta?.name) {
            val relay = props.relayUrl
            if (relay != null && meta?.name == null) {
                launchApp { repo.fetchGroupPreview(props.groupId, relay) }
            }
        }

        div {
            className = ClassName("group-link-card")
            onClick = { props.onNavigate(props.groupId, props.relayUrl) }
            WebAvatar {
                url = meta?.picture
                seed = props.groupId
                kind = AvatarKind.GROUP
                this.name = name
                cls = "group-link-avatar"
            }
            div {
                className = ClassName("group-link-meta")
                div {
                    className = ClassName("group-link-name")
                    +name
                }
                meta?.about?.takeIf { it.isNotBlank() }?.let { about ->
                    div {
                        className = ClassName("group-link-about")
                        +about
                    }
                }
                relayDisplay?.let {
                    div {
                        className = ClassName("group-link-relay")
                        +it
                    }
                }
            }
        }
    }

/**
 * Inline group mention chip (prototype): a decoded naddr (kind 39000) shown compactly with the
 * group's square avatar + name when resolved, or `#name` when it has no picture. Clicking opens
 * the group. Standalone group references still render as the full GroupLinkCard.
 */
private val GroupMentionChip =
    FC<GroupLinkCardProps> { props ->
        val repo = AppModule.nostrRepository
        val groupsByRelay = useStateFlow(repo.groupsByRelay)
        val meta = groupsByRelay.values.flatten().firstOrNull { it.id == props.groupId }
        val name = meta?.name?.takeIf { it.isNotBlank() } ?: props.groupId

        useEffect(props.groupId, props.relayUrl, meta?.name) {
            val relay = props.relayUrl
            if (relay != null && meta?.name == null) {
                launchApp { repo.fetchGroupPreview(props.groupId, relay) }
            }
        }

        span {
            className = ClassName("msg-mention-chip")
            onClick = { props.onNavigate(props.groupId, props.relayUrl) }
            val picture = meta?.picture
            if (!picture.isNullOrBlank()) {
                WebAvatar {
                    url = picture
                    seed = props.groupId
                    this.name = name
                    kind = AvatarKind.GROUP
                    cls = "msg-mention-chip-avatar group"
                }
                +name
            } else {
                +"#$name"
            }
        }
    }

private fun ChildrenBuilder.memberSection(title: String, count: Int) {
    div {
        className = ClassName("member-section")
        +"$title · $count"
    }
}

private fun ChildrenBuilder.memberRow(
    pubkey: String,
    name: String,
    avatarUrl: String?,
    isAdmin: Boolean,
    isOnline: Boolean,
    onUser: (String) -> Unit,
) {
    div {
        className = ClassName("member-row")
        onClick = { onUser(pubkey) }
        div {
            className = ClassName("member-avatar-wrap")
            WebAvatar {
                url = avatarUrl
                seed = pubkey
                this.name = name
                // Offline avatars fade (`.member-avatar.dimmed`) — mirrors native's
                // MemberAvatar(dimmed = isOnline == false) in MemberSidebar.kt:388.
                cls = if (isOnline) "member-avatar" else "member-avatar dimmed"
            }
            // Status sticker on the bottom-right of the avatar: Success green when
            // online (a member who posted in the last 10 min), TextMuted grey
            // otherwise. The 2px Surface-coloured ring around the dot mirrors
            // MemberSidebar.kt:391's `.background(Surface, CircleShape).padding(2.dp)`
            // and reads as a sticker, not as a pixel glued to the avatar edge.
            span {
                className = ClassName(if (isOnline) "member-dot online" else "member-dot")
            }
        }
        span {
            // Native dims the name to TextMuted when the member is offline
            // (MemberSidebar.kt:415). The `.dimmed` modifier already maps to
            // `color: var(--color-text-muted)` in the CSS.
            className = ClassName(if (isOnline) "member-name" else "member-name dimmed")
            +name
        }
        if (isAdmin) {
            span {
                className = ClassName("member-admin")
                +"ADMIN"
            }
        }
    }
}
