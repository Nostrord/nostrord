package org.nostr.nostrord.web.screens

import kotlinx.browser.document
import kotlinx.browser.window
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.network.toEventJson
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.components.emoji.QuickReactions
import org.nostr.nostrord.ui.keyboard.VirtualKeyboardPolicy
import org.nostr.nostrord.ui.mentions.MentionAutocomplete
import org.nostr.nostrord.ui.mentions.MentionCtx
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.navigation.threadShareLink
import org.nostr.nostrord.ui.screens.group.GroupMembership
import org.nostr.nostrord.ui.screens.group.MentionableGroup
import org.nostr.nostrord.ui.screens.group.ThreadsPlaceholder
import org.nostr.nostrord.ui.screens.group.ThreadsViewModel
import org.nostr.nostrord.ui.screens.group.canDeleteThreadMessage
import org.nostr.nostrord.ui.screens.group.deleteThreadConfirmBody
import org.nostr.nostrord.ui.screens.group.threadComposerSubmits
import org.nostr.nostrord.ui.screens.group.threadParentIdTag
import org.nostr.nostrord.ui.screens.group.threadRootIdTag
import org.nostr.nostrord.ui.screens.group.threadTitle
import org.nostr.nostrord.ui.screens.group.threadsPlaceholder
import org.nostr.nostrord.ui.screens.group.topReactionChips
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.formatDateTime
import org.nostr.nostrord.utils.getDateLabel
import org.nostr.nostrord.utils.normalizeRelayUrl
import org.nostr.nostrord.web.bridge.VirtualKeyboard
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.AvatarKind
import org.nostr.nostrord.web.components.EmojiPicker
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.MentionMatch
import org.nostr.nostrord.web.components.MentionPopup
import org.nostr.nostrord.web.components.Portal
import org.nostr.nostrord.web.components.UploadButton
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.confirmDialog
import org.nostr.nostrord.web.components.copyToClipboard
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.mentionDisplayName
import org.nostr.nostrord.web.components.mentionHighlight
import org.nostr.nostrord.web.components.mentionMatches
import org.nostr.nostrord.web.components.messageSendStatus
import org.nostr.nostrord.web.components.reactionBadges
import org.nostr.nostrord.web.components.sendStateIcon
import org.nostr.nostrord.web.components.uploadBlob
import org.nostr.nostrord.web.modals.CreateThreadModal
import org.nostr.nostrord.web.modals.JoinGroupConfirmModal
import org.nostr.nostrord.web.modals.UserProfileModal
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.textarea
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName
import web.dom.ElementId
import web.html.HTMLDivElement
import web.html.HTMLTextAreaElement

/** Lock panel for a group the relay withholds; same chrome as the chat gate. */
private fun ChildrenBuilder.threadsLockedState(title: String, body: String) {
    div {
        className = ClassName("chat-restricted")
        icon(Ic.Lock, "chat-restricted-icon")
        div {
            className = ClassName("chat-restricted-title")
            +title
        }
        div {
            className = ClassName("chat-restricted-body")
            +body
        }
    }
}

private fun threadDisplayName(pubkey: String, meta: UserMetadata?): String = mentionDisplayName(pubkey, meta)

// Deep-link flash duration; mirrors the .thread-msg.highlight animation (2.4s msg-flash).
private const val HIGHLIGHT_FLASH_MS = 2_400

/** Right-click target for the thread context menu: the message plus the click viewport coords. */
private data class ThreadCtxMenu(
    val msg: NostrGroupClient.NostrMessage,
    val x: Double,
    val y: Double,
)

private external interface ThreadComposerProps : Props {
    /** The screen's ThreadsViewModel (shared, not a second instance). */
    var vm: ThreadsViewModel

    /** Message being answered (null posts top-level), plus the banner's name and preview. */
    var replyingTo: NostrGroupClient.NostrMessage?

    /** Bumped on EVERY reply pick so re-replying to the same message still refocuses. */
    var replyNonce: Int
    var replyParentName: String?
    var replyParentPreview: String?
    var onCancelReply: () -> Unit

    /** Cleared after a successful publish so the screen can drop the reply target. */
    var onSent: () -> Unit
    var members: List<String>
    var allGroups: List<MentionableGroup>
    var userMetadata: Map<String, UserMetadata>

    /** Relay's NIP-11 pubkey, the naddr author of a `%group` mention on that relay. */
    var relayPubkeyOf: (String) -> String?
    var onUploadError: (String) -> Unit
}

/**
 * The thread reply composer: the DM pill (.dm-composer) plus the `@user` / `%group` autocomplete
 * and the colored mention mirror the chat composer has. Split out of [ThreadsScreen] so typing
 * re-renders only this subtree, not the whole thread.
 */
private val ThreadComposer =
    FC<ThreadComposerProps> { props ->
        val vm = props.vm
        val (reply, setReply) = useState { "" }
        val (sending, setSending) = useState { false }
        val (emojiOpen, setEmojiOpen) = useState { false }
        val (uploadCount, setUploadCount) = useState { 0 }

        // Active @user / %group mention being typed, and the ones already chosen: displayName ->
        // pubkey for users (resolved to nostr:npub + a p tag by the repo), name -> nostr:naddr for
        // groups (spliced into the content at send).
        val (mention, setMention) = useState<MentionCtx?> { null }
        val (mentions, setMentions) = useState<Map<String, String>> { emptyMap() }
        val (groupMentions, setGroupMentions) = useState<Map<String, String>> { emptyMap() }
        val (mentionSelected, setMentionSelected) = useState { 0 }

        val composerInputRef = useRef<HTMLTextAreaElement>(null)
        val composerHighlightRef = useRef<HTMLDivElement>(null)

        val suggestions = mentionMatches(mention, props.members, props.userMetadata, props.allGroups, props.relayPubkeyOf)

        useEffect(props.replyingTo?.id, props.replyNonce) {
            if (props.replyingTo == null) return@useEffect
            val ta = composerInputRef.current ?: return@useEffect
            // Re-focusing an already-focused field does not re-open a keyboard the user
            // dismissed, so blur first in exactly that case (mirrors ChatScreen).
            if (!VirtualKeyboard.isOpen && document.asDynamic().activeElement === ta.asDynamic()) {
                ta.blur()
            }
            ta.focus()
        }

        // Keep the mirror's box in step with the auto-growing textarea.
        useEffect(reply) {
            val el = composerInputRef.current ?: return@useEffect
            el.style.height = "auto"
            el.style.height = "${el.scrollHeight}px"
        }

        fun isMediaMime(type: String?): Boolean = type != null && (type.startsWith("image/") || type.startsWith("video/") || type.startsWith("audio/"))

        // Upload a pasted / dropped file and append its URL to the reply draft (parity with DM / group).
        fun handleMediaFile(file: dynamic) {
            setUploadCount { it + 1 }
            launchApp {
                try {
                    when (val r = uploadBlob(file)) {
                        is Result.Success -> setReply { prev -> if (prev.isBlank()) r.data.url else "$prev ${r.data.url}" }
                        is Result.Error -> props.onUploadError(r.error.message)
                    }
                } finally {
                    setUploadCount { it - 1 }
                }
            }
        }

        /** Replace the typed "@query" / "%query" with the chosen suggestion. */
        fun insertMention(mm: MentionMatch) {
            val ctx = mention ?: return
            setReply(MentionAutocomplete.insert(reply, ctx, mm.label).text)
            if (mm.group) setGroupMentions { it + (mm.label to mm.ref) } else setMentions { it + (mm.label to mm.seed) }
            setMention(null)
            setMentionSelected(0)
        }

        fun sendReply() {
            if (reply.isBlank() || sending) return
            // Resolve %group mentions to their nostr:naddr inline (native does this at send too);
            // @user mentions are resolved by the repo from the mentions map (+ p tag).
            var text = reply
            groupMentions.forEach { (name, ref) -> text = text.replace("%$name", ref) }
            setSending(true)
            vm.sendReply(
                text,
                parent = props.replyingTo,
                mentions = mentions,
                onSuccess = {
                    setReply("")
                    setMentions(emptyMap())
                    setGroupMentions(emptyMap())
                    setSending(false)
                    props.onSent()
                },
                onFailure = { setSending(false) },
            )
        }

        // execCommand keeps the cursor position so an emoji lands where the caret is, not appended.
        fun insertAtCursor(s: String) {
            val ta = composerInputRef.current
            if (ta == null) {
                setReply { it + s }
                return
            }
            ta.focus()
            document.asDynamic().execCommand("insertText", false, s)
        }

        // Same composer as DM (.dm-composer): rounded bar, Enter to send, emoji picker.
        div {
            className = ClassName("dm-composer-wrap")
            // Floats above the pill; .dm-composer-wrap is the positioned ancestor it anchors to.
            // Every child here carries a stable key: without them, mounting the popup shifts the
            // siblings after it and React remounts the textarea, which drops the caret mid-word.
            if (mention != null && suggestions.isNotEmpty()) {
                MentionPopup {
                    key = "mention-popup"
                    matches = suggestions
                    trigger = mention.trigger
                    selected = mentionSelected
                    onPick = { insertMention(it) }
                    onHover = { setMentionSelected(it) }
                }
            }
            // Reply chip above the composer while answering a specific message.
            props.replyParentName?.let { parentName ->
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
                    props.replyParentPreview?.let { preview ->
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
            // Post box, not a chat pill: it opens several lines tall and posts from a labeled
            // button, so a reply reads as something you compose (issue #267).
            div {
                key = "composer-pill"
                // Keeps .dm-composer for the shared box rules (transparent textarea over the
                // mention mirror, focus ring); .thread-composer restacks it as a post box.
                className = ClassName("dm-composer thread-composer")
                div {
                    className = ClassName("composer-input-wrap")
                    // Colored mirror painted behind the transparent textarea: same text, with the
                    // resolved @user / %group mentions tinted (native parity).
                    div {
                        className = ClassName("composer-highlight")
                        ref = composerHighlightRef
                        mentionHighlight(reply, mentions.keys.map { "@$it" } + groupMentions.keys.map { "%$it" })
                    }
                    textarea {
                        ref = composerInputRef
                        className = ClassName("composer-input thread-composer-input")
                        rows = 3
                        value = reply
                        placeholder = "Write a reply..."
                        onScroll = {
                            composerHighlightRef.current?.scrollTop = composerInputRef.current?.scrollTop ?: 0.0
                        }
                        onChange = { event ->
                            val v = (event.target as HTMLTextAreaElement).value
                            setReply(v)
                            val cursor = (event.currentTarget.asDynamic().selectionStart as? Int) ?: v.length
                            setMention(MentionAutocomplete.detect(v, cursor))
                            setMentionSelected(0)
                        }
                        onKeyDown = { e ->
                            val hasMatches = mention != null && suggestions.isNotEmpty()
                            when {
                                hasMatches && e.key == "ArrowDown" -> {
                                    e.preventDefault()
                                    setMentionSelected { (it + 1).coerceAtMost(suggestions.size - 1) }
                                }
                                hasMatches && e.key == "ArrowUp" -> {
                                    e.preventDefault()
                                    setMentionSelected { (it - 1).coerceAtLeast(0) }
                                }
                                hasMatches && (e.key == "Enter" || e.key == "Tab") -> {
                                    e.preventDefault()
                                    insertMention(suggestions[mentionSelected.coerceIn(0, suggestions.size - 1)])
                                }
                                mention != null && e.key == "Escape" -> {
                                    e.preventDefault()
                                    setMention(null)
                                }
                                // Enter writes a newline here; only Ctrl/Cmd+Enter posts.
                                threadComposerSubmits(e.key == "Enter", e.ctrlKey || e.metaKey, e.shiftKey) -> {
                                    e.preventDefault()
                                    sendReply()
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
                                if (isMediaMime(file.type.unsafeCast<String?>())) handleMediaFile(file)
                            }
                        }
                    }
                }
                div {
                    className = ClassName("thread-composer-bar")
                    UploadButton {
                        cls = "dm-composer-btn"
                        icon = Ic.AttachFile
                        busy = uploadCount > 0
                        onBusyChange = { b -> setUploadCount { if (b) it + 1 else it - 1 } }
                        onPickerClosed = { composerInputRef.current?.focus() }
                        onUploaded = { upload -> setReply { prev -> if (prev.isBlank()) upload.url else "$prev ${upload.url}" } }
                        onError = { props.onUploadError(it) }
                    }
                    button {
                        className = ClassName(if (emojiOpen) "dm-composer-btn active" else "dm-composer-btn")
                        title = "Emoji"
                        onClick = { setEmojiOpen(!emojiOpen) }
                        icon(Ic.EmojiEmotions)
                    }
                    button {
                        className = ClassName("btn-primary thread-composer-post")
                        disabled = (reply.isBlank() && uploadCount == 0) || uploadCount > 0 || sending
                        // Keyboard-neutral tap: keep textarea focus (so the keyboard stays up)
                        // only when it is already up; rules in VirtualKeyboardPolicy.
                        onMouseDown = { e ->
                            val keep = VirtualKeyboardPolicy.keepComposerFocusOnTap(
                                keyboardOpen = VirtualKeyboard.isOpen,
                                touchDevice = window.navigator.maxTouchPoints > 0,
                            )
                            if (keep) e.preventDefault()
                        }
                        onClick = { sendReply() }
                        if (sending) span { className = ClassName("btn-spinner") } else +"Post reply"
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
    }

external interface ThreadsScreenProps : Props {
    var route: GroupRoute
    var group: GroupMetadata
    var onNavigate: (GroupRoute) -> Unit

    /** Mobile-only: opens the groups-sidebar drawer (the ≡ in the page header). */
    var onOpenDrawer: () -> Unit
}

/**
 * Forum-style Threads pane: web mirror of the Compose `ThreadsScreen` and of the prototype's
 * GroupPanels.ThreadsPanel / ThreadPanel, rendered as a page (not a modal). Shows the list of
 * kind:11 roots, or a single open thread (root + kind:1111 replies) when the route carries a
 * threadRootId. Consumes the shared `ThreadsViewModel`; the group rail + sidebar stay mounted in
 * AppFrame, so only this centre pane swaps when leaving chat.
 */
val ThreadsScreen =
    FC<ThreadsScreenProps> { props ->
        val route = props.route
        val vm = useViewModel("${route.relayUrl}|${route.groupId}") {
            ThreadsViewModel(AppModule.nostrRepository, route.groupId, route.relayUrl)
        }
        // Same confirm step as the chat screen, so the public/private choice is never skipped
        // depending on which affordance the user reached the join from.
        val (showJoinConfirm, setShowJoinConfirm) = useState { false }
        val threads = useStateFlow(vm.threads)
        val isLoading = useStateFlow(vm.isLoading)
        val openThread = useStateFlow(vm.openThread)
        val userMetadata = useStateFlow(vm.userMetadata)
        val messageStatus = useStateFlow(vm.messageStatus)
        val reactions = useStateFlow(vm.reactions)
        val pendingReactions = useStateFlow(vm.pendingReactions)
        val reactionError = useStateFlow(vm.reactionError)
        val deleteError = useStateFlow(vm.deleteError)
        val isAdmin = useStateFlow(vm.isAdmin)
        val isRestricted = useStateFlow(vm.isRestricted)
        val isPendingApproval = useStateFlow(vm.isPendingApproval)
        val membership = useStateFlow(vm.membershipState)
        val groupAccess = useStateFlow(vm.groupAccess)
        val joinError = useStateFlow(vm.joinError)
        val myPubkey = vm.getPublicKey()

        // Full-picker target: the (eventId, authorPubkey) of the message being reacted to.
        val (reactingTo, setReactingTo) = useState<Pair<String, String>?> { null }

        // Avatar / author-name / mention tap target: opens the user profile modal (chat parity).
        val (profilePubkey, setProfilePubkey) = useState<String?> { null }

        // Context menu (right-click / long-press) over a thread message.
        val (ctxMenu, setCtxMenu) = useState<ThreadCtxMenu?> { null }
        val ctxMenuRef = useRef<HTMLDivElement>(null)

        // Clamp the menu into the viewport once it mounts (.ctx-menu starts visibility:hidden).
        useEffect(ctxMenu) {
            val el = ctxMenuRef.current?.asDynamic() ?: return@useEffect
            val m = ctxMenu ?: return@useEffect
            val w = el.offsetWidth as Int
            val h = el.offsetHeight as Int
            var left = m.x
            if (left + w > window.innerWidth - 8) left = (window.innerWidth - 8.0 - w).coerceAtLeast(8.0)
            var top = m.y
            if (top + h > window.innerHeight - 8) top = (m.y - h).coerceAtLeast(8.0)
            el.style.left = "${left}px"
            el.style.top = "${top}px"
            el.style.visibility = "visible"
        }

        // Message being answered by the composer (context-menu Reply); null posts top-level.
        val (replyingTo, setReplyingTo) = useState<NostrGroupClient.NostrMessage?> { null }

        // Message pending delete confirmation (the root or any reply; header or menu).
        val (deleteTarget, setDeleteTarget) = useState<NostrGroupClient.NostrMessage?> { null }

        // Deep-link target (?e=): scroll to and flash the message once the thread loads.
        val (highlightId, setHighlightId) = useState<String?> { null }
        val highlightLoaded = route.messageId != null &&
            openThread?.let { d -> (d.replies + d.root).any { it.id == route.messageId } } == true
        useEffect(route.messageId, highlightLoaded) {
            val target = route.messageId
            if (target != null && highlightLoaded) {
                setHighlightId(target)
                document.getElementById("thread-msg-$target")?.asDynamic()
                    ?.scrollIntoView(js("{ block: 'center', behavior: 'smooth' }"))
                window.setTimeout({ setHighlightId(null) }, HIGHLIGHT_FLASH_MS)
            }
        }

        /** Scroll to a message inside the open thread and flash it (quote tap, chat parity). */
        fun jumpToMessage(id: String) {
            val el = document.getElementById("thread-msg-$id") ?: return
            setHighlightId(id)
            el.asDynamic().scrollIntoView(js("{ block: 'center', behavior: 'smooth' }"))
            window.setTimeout({ setHighlightId(null) }, HIGHLIGHT_FLASH_MS)
        }

        // Keep the open thread synced with the URL (#/g/<relay>/<id>/threads/<rootId>).
        useEffect(route.threadRootId) {
            vm.openThread(route.threadRootId)
            setReplyingTo(null)
        }

        val (composing, setComposing) = useState { false }
        val (uploadError, setUploadError) = useState<String?> { null }

        // Picking Reply should leave the caret in the composer, ready to type (chat parity).
        // Bumped per pick so replying twice to the same message re-focuses.
        val (replyFocusNonce, setReplyFocusNonce) = useState { 0 }

        // Mention candidates, shared with the new-thread modal: the group's members (admins first,
        // chat parity) and the groups reachable through your follows.
        val mentionableMembers = useStateFlow(vm.mentionableMembers)
        val threadAdmins = useStateFlow(vm.groupAdmins)
        val mentionableGroups = useStateFlow(vm.mentionableGroups)
        val relayMetadata = useStateFlow(AppModule.nostrRepository.relayMetadata)
        val mentionMembers = mentionableMembers.sortedWith(
            compareByDescending<String> { it in threadAdmins }
                .thenBy { mentionDisplayName(it, userMetadata[it]).lowercase() },
        )
        val relayPubkeyOf: (String) -> String? = { r ->
            relayMetadata[r]?.groupNaddrAuthor ?: relayMetadata[r.normalizeRelayUrl()]?.groupNaddrAuthor
        }

        div {
            className = ClassName(if (route.threadRootId != null) "threads-page detail-open" else "threads-page")

            // Page header: group identity over the whole pane, mirroring the chat header
            // (drawer ≡ on mobile, avatar + name).
            div {
                className = ClassName("chat-header")
                button {
                    className = ClassName("chat-icon-btn chat-drawer-btn")
                    onClick = { props.onOpenDrawer() }
                    icon(Ic.Menu)
                }
                div {
                    className = ClassName("chat-header-title")
                    WebAvatar {
                        url = props.group.picture
                        seed = props.group.id
                        kind = AvatarKind.GROUP
                        this.name = props.group.name?.takeIf { it.isNotBlank() } ?: "#${props.group.id.take(8)}"
                        cls = "chat-header-icon"
                    }
                    div {
                        className = ClassName("chat-header-name")
                        +(props.group.name?.takeIf { it.isNotBlank() } ?: "#${props.group.id.take(8)}")
                    }
                }
            }

            div {
                className = ClassName("threads-body")

                // ---- Threads list pane: always mounted. On desktop the open thread docks beside
                // it (Discord-style split); on mobile .detail-open hides this pane instead. ----
                div {
                    className = ClassName("threads-list-pane")
                    div {
                        className = ClassName("threads-header")
                        span {
                            className = ClassName("threads-title")
                            // Same glyph the sidebar's Threads row uses, so the pane is
                            // identifiable on mobile where that row is off screen.
                            icon(Ic.Forum, "threads-title-icon")
                            span { +"Threads" }
                        }
                        when {
                            // Outsiders get the same join affordance as the chat header instead of
                            // a composer the relay would reject.
                            membership.status == GroupMembership.NONE ->
                                button {
                                    className = ClassName("chat-join-btn")
                                    onClick = { setShowJoinConfirm { true } }
                                    icon(Ic.PersonAdd)
                                    span { +(if (groupAccess.isOpen) "Join" else "Request to Join") }
                                }
                            membership.status == GroupMembership.PENDING ->
                                span {
                                    className = ClassName("chat-pending")
                                    +"Request pending"
                                }
                            // No composer while the relay withholds the group: the kind:11 would be rejected.
                            !isRestricted ->
                                button {
                                    className = ClassName("btn-primary thread-new-btn")
                                    onClick = { setComposing(true) }
                                    icon(Ic.Add)
                                    span { +"New thread" }
                                }
                        }
                    }

                    when (threadsPlaceholder(threads.isNotEmpty(), isLoading, isPendingApproval, isRestricted)) {
                        ThreadsPlaceholder.LOADING ->
                            div {
                                className = ClassName("threads-empty")
                                +"Loading threads..."
                            }
                        ThreadsPlaceholder.PENDING_APPROVAL ->
                            threadsLockedState(
                                "Awaiting admin approval",
                                "Threads will appear once an admin approves your request.",
                            )
                        ThreadsPlaceholder.PRIVATE ->
                            threadsLockedState(
                                "Private group",
                                "You need an invite code or admin approval to see threads.",
                            )
                        ThreadsPlaceholder.EMPTY ->
                            div {
                                className = ClassName("threads-empty")
                                +"No threads yet. Start the first one."
                            }
                        null ->
                            div {
                                className = ClassName("thread-list")
                                threads.forEach { t ->
                                    button {
                                        key = t.rootId
                                        className = ClassName(if (t.rootId == route.threadRootId) "thread-card active" else "thread-card")
                                        onClick = { props.onNavigate(route.copy(threadRootId = t.rootId)) }
                                        WebAvatar {
                                            url = userMetadata[t.authorPubkey]?.picture
                                            seed = t.authorPubkey
                                            this.name = threadDisplayName(t.authorPubkey, userMetadata[t.authorPubkey])
                                            kind = AvatarKind.USER
                                            cls = "thread-card-avatar"
                                        }
                                        div {
                                            className = ClassName("thread-card-main")
                                            span {
                                                className = ClassName("thread-card-title")
                                                +t.title
                                            }
                                            if (t.preview.isNotBlank()) {
                                                span {
                                                    className = ClassName("thread-card-preview")
                                                    +t.preview
                                                }
                                            }
                                            div {
                                                className = ClassName("thread-card-meta")
                                                // Top reactions on the root, then author / replies / publication date.
                                                topReactionChips(reactions[t.rootId] ?: emptyMap()).forEach { chip ->
                                                    span {
                                                        className = ClassName("thread-card-chip")
                                                        if (!chip.emojiUrl.isNullOrBlank()) {
                                                            img {
                                                                className = ClassName("reaction-emoji")
                                                                src = chip.emojiUrl
                                                                alt = chip.emoji
                                                            }
                                                        } else {
                                                            +chip.emoji
                                                        }
                                                        span {
                                                            className = ClassName("thread-card-chip-count")
                                                            +"${chip.count}"
                                                        }
                                                    }
                                                }
                                                +threadDisplayName(t.authorPubkey, userMetadata[t.authorPubkey])
                                                span {
                                                    className = ClassName("thread-card-dot")
                                                    +"·"
                                                }
                                                +(if (t.replyCount == 1) "1 reply" else "${t.replyCount} replies")
                                                span {
                                                    className = ClassName("thread-card-dot")
                                                    +"·"
                                                }
                                                +getDateLabel(t.createdAt)
                                            }
                                        }
                                    }
                                }
                            }
                    }
                }

                if (composing) {
                    Portal {
                        CreateThreadModal {
                            onClose = { setComposing(false) }
                            onCreate = { title, content, shareToChat, mentions ->
                                // Open the new thread right away (Discord parity; the optimistic
                                // root is already in the store, so the detail renders instantly).
                                vm.createThread(title, content, shareToChat, mentions) { rootId ->
                                    props.onNavigate(route.copy(threadRootId = rootId))
                                }
                            }
                            members = mentionMembers
                            allGroups = mentionableGroups
                            this.userMetadata = userMetadata
                            this.relayPubkeyOf = relayPubkeyOf
                        }
                    }
                }

                if (route.threadRootId != null) {
                    // ---- Open thread: full page on mobile, right dock on desktop ----
                    div {
                        className = ClassName("thread-detail-pane")
                        div {
                            className = ClassName("threads-header")
                            button {
                                // Mobile only; on desktop the split stays and the X on the right closes.
                                className = ClassName("icon-btn thread-back")
                                title = "Back to threads"
                                onClick = { props.onNavigate(route.copy(threadRootId = null)) }
                                icon(Ic.ArrowBack)
                            }
                            span {
                                className = ClassName("threads-title")
                                +"Thread"
                            }
                            val root = openThread?.root
                            if (root != null && canDeleteThreadMessage(root.pubkey, myPubkey, isAdmin)) {
                                button {
                                    className = ClassName("icon-btn")
                                    title = "Delete thread"
                                    onClick = { setDeleteTarget(root) }
                                    icon(Ic.Delete)
                                }
                            }
                            button {
                                // Desktop only (CSS): closes the docked thread, Discord-style.
                                className = ClassName("icon-btn thread-close")
                                title = "Close thread"
                                onClick = { props.onNavigate(route.copy(threadRootId = null)) }
                                icon(Ic.Close)
                            }
                        }
                        val detail = openThread
                        if (detail == null) {
                            div {
                                className = ClassName("threads-empty")
                                +"Loading thread..."
                            }
                        } else {
                            // Pending sends for one message: "eventId|emoji" keys -> that message's emojis.
                            fun pendingFor(id: String) = pendingReactions.filter { it.startsWith("$id|") }.map { it.substringAfter('|') }

                            // Nested replies resolve their lowercase-e parent from the loaded thread.
                            val messagesById = (detail.replies + detail.root).associateBy { it.id }

                            fun ChildrenBuilder.renderThreadMessage(msg: NostrGroupClient.NostrMessage, isRoot: Boolean) = threadMessage(
                                msg,
                                userMetadata,
                                isRoot = isRoot,
                                myPubkey,
                                messageStatus[msg.id],
                                reactions[msg.id] ?: emptyMap(),
                                pendingFor(msg.id),
                                parentMsg = msg.threadParentIdTag()?.let { messagesById[it] },
                                highlighted = msg.id == highlightId,
                                menuOpen = ctxMenu?.msg?.id == msg.id,
                                onReact = { emoji -> vm.sendReaction(msg.id, msg.pubkey, emoji) },
                                onReply = {
                                    setReplyingTo(msg)
                                    setReplyFocusNonce { it + 1 }
                                },
                                onAddReaction = { setReactingTo(msg.id to msg.pubkey) },
                                onOpenMenu = { x, y -> setCtxMenu(ThreadCtxMenu(msg, x, y)) },
                                onCloseMenu = { setCtxMenu(null) },
                                onUser = { setProfilePubkey(it) },
                                onJumpToParent = msg.threadParentIdTag()
                                    ?.takeIf { messagesById.containsKey(it) }
                                    ?.let { parentId -> { jumpToMessage(parentId) } },
                                // A group ref in the body opens that group's chat page.
                                onGroupRef = { gid, relay -> props.onNavigate(GroupRoute(relay ?: route.relayUrl, gid)) },
                                hostRelayUrl = route.relayUrl,
                                onRetry = { vm.retrySend(msg.id) },
                                onDismiss = { vm.dismissFailed(msg.id) },
                            )

                            div {
                                className = ClassName("thread-detail-body")
                                renderThreadMessage(detail.root, isRoot = true)
                                div {
                                    className = ClassName("thread-replies-divider")
                                    +(if (detail.replies.size == 1) "1 reply" else "${detail.replies.size} replies")
                                }
                                detail.replies.forEach { r -> renderThreadMessage(r, isRoot = false) }
                            }
                            ThreadComposer {
                                this.vm = vm
                                this.replyingTo = replyingTo
                                this.replyNonce = replyFocusNonce
                                this.replyParentName = replyingTo?.let { threadDisplayName(it.pubkey, userMetadata[it.pubkey]) }
                                this.replyParentPreview = replyingTo?.content
                                    ?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
                                this.onCancelReply = { setReplyingTo(null) }
                                this.onSent = { setReplyingTo(null) }
                                this.members = mentionMembers
                                this.allGroups = mentionableGroups
                                this.userMetadata = userMetadata
                                this.relayPubkeyOf = relayPubkeyOf
                                this.onUploadError = { setUploadError(it) }
                            }
                        }
                    }
                }
            }

            // Thread message context menu: quick reactions, copy text, delete (own message).
            ctxMenu?.let { m ->
                div {
                    className = ClassName("ctx-overlay")
                    onClick = { setCtxMenu(null) }
                    // No preventDefault: closing is enough, and swallowing it would deny the
                    // browser menu a right-click outside the row is entitled to (chat parity).
                    onContextMenu = { setCtxMenu(null) }
                }
                div {
                    ref = ctxMenuRef
                    className = ClassName("ctx-menu")
                    div {
                        className = ClassName("ctx-reactions")
                        for (emoji in QuickReactions) {
                            button {
                                className = ClassName("ctx-reaction")
                                onClick = {
                                    vm.sendReaction(m.msg.id, m.msg.pubkey, emoji)
                                    setCtxMenu(null)
                                }
                                +emoji
                            }
                        }
                        button {
                            className = ClassName("ctx-reaction ctx-reaction-more")
                            title = "Add reaction"
                            onClick = {
                                setCtxMenu(null)
                                setReactingTo(m.msg.id to m.msg.pubkey)
                            }
                            icon(Ic.EmojiEmotions)
                        }
                    }
                    div { className = ClassName("ctx-divider") }
                    ctxItem(Ic.Reply, "Reply") {
                        setReplyingTo(m.msg)
                        setCtxMenu(null)
                        // Focus rides the effect above, not a call here: this runs before React
                        // re-renders, so the composer it would focus is the pre-reply one.
                        setReplyFocusNonce { it + 1 }
                    }
                    // Announce the thread in the group chat (kind:9 with the root's nevent).
                    if (m.msg.kind == 11) {
                        ctxItem(Ic.Forum, "Share to chat") {
                            vm.shareThreadToChat(m.msg)
                            setCtxMenu(null)
                        }
                    }
                    div { className = ClassName("ctx-divider") }
                    ctxItem(Ic.ContentCopy, "Copy text") {
                        copyToClipboard(m.msg.content)
                        setCtxMenu(null)
                    }
                    ctxItem(Ic.Link, "Copy event link") {
                        // A reply links to its thread page (root id from the E tag), targeting
                        // this message via ?e= so opening it scrolls to and flashes it.
                        copyToClipboard(threadShareLink(route.relayUrl, route.groupId, m.msg.threadRootIdTag() ?: m.msg.id, messageId = m.msg.id))
                        setCtxMenu(null)
                    }
                    ctxItem(Ic.Code, "Copy nevent") {
                        copyToClipboard(Nip19.encodeNevent(m.msg.id, relays = listOf(route.relayUrl), authorHex = m.msg.pubkey, kind = m.msg.kind))
                        setCtxMenu(null)
                    }
                    ctxItem(Ic.Code, "Copy event JSON") {
                        copyToClipboard(m.msg.toEventJson())
                        setCtxMenu(null)
                    }
                    if (canDeleteThreadMessage(m.msg.pubkey, myPubkey, isAdmin)) {
                        div { className = ClassName("ctx-divider") }
                        ctxItem(Ic.Delete, "Delete message", danger = true) {
                            setCtxMenu(null)
                            setDeleteTarget(m.msg)
                        }
                    }
                }
            }

            // Full emoji picker for a reaction (opened by the add-reaction button).
            reactingTo?.let { (targetEventId, targetPubkey) ->
                Portal {
                    div {
                        className = ClassName("emoji-overlay")
                        onClick = { setReactingTo(null) }
                        EmojiPicker {
                            onPick = { emoji ->
                                vm.sendReaction(targetEventId, targetPubkey, emoji)
                                setReactingTo(null)
                            }
                        }
                    }
                }
            }
            // User profile modal (avatar / author-name / mention tap), same modal as chat.
            profilePubkey?.let { pk ->
                UserProfileModal {
                    pubkey = pk
                    groupId = route.groupId
                    onClose = { setProfilePubkey(null) }
                }
            }

            // Delete confirm modal (chat parity: destructive confirm, no window.confirm).
            deleteTarget?.let { target ->
                val isRoot = target.id == route.threadRootId
                val moderatedAuthor = if (target.pubkey == myPubkey) null else threadDisplayName(target.pubkey, userMetadata[target.pubkey])
                confirmDialog(
                    title = if (isRoot) "Delete Thread" else "Delete Message",
                    body = deleteThreadConfirmBody(isRoot, moderatedAuthor),
                    confirmLabel = "Delete",
                    danger = true,
                    onCancel = { setDeleteTarget(null) },
                    onConfirm = {
                        setDeleteTarget(null)
                        // A relay rejection surfaces via vm.deleteError below.
                        vm.deleteThread(target.id)
                        if (isRoot) props.onNavigate(route.copy(threadRootId = null))
                    },
                )
            }
            // Relay refused the kind:9021 (or the invite code): same acknowledge-only shape as
            // the delete failure, since there is nothing to retry from here.
            joinError?.let { err ->
                errorDialog("Could Not Join", err) { vm.clearJoinError() }
            }
            // Relay rejected the kind:5/9005 - show the reason (chat parity).
            deleteError?.let { err ->
                deleteMessageErrorDialog(err) { vm.clearDeleteError() }
            }
            reactionError?.let { err ->
                reactionErrorDialog(
                    err,
                    onDismiss = { vm.clearReactionError() },
                    onJoin = {
                        vm.clearReactionError()
                        setShowJoinConfirm { true }
                    },
                )
            }
            if (showJoinConfirm) {
                JoinGroupConfirmModal {
                    this.groupName = AppModule.nostrRepository.groups.value.firstOrNull { it.id == props.route.groupId }?.name
                    this.isGroupClosed = !groupAccess.isOpen
                    onConfirm = { listPrivately ->
                        setShowJoinConfirm { false }
                        vm.joinGroup(listPrivately)
                    }
                    onClose = { setShowJoinConfirm { false } }
                }
            }
            uploadError?.let { uploadErrorDialog(it) { setUploadError(null) } }
        }
    }

/** Minimal "upload failed" dialog, parity with the DM composer's. */
private fun ChildrenBuilder.uploadErrorDialog(message: String, onDismiss: () -> Unit) {
    div {
        className = ClassName("modal-overlay")
        onClick = { onDismiss() }
        div {
            className = ClassName("modal-card")
            onClick = { it.stopPropagation() }
            div {
                className = ClassName("modal-header")
                div {
                    className = ClassName("modal-title")
                    +"Upload failed"
                }
            }
            div {
                className = ClassName("modal-subtitle")
                +message
            }
            div {
                className = ClassName("modal-actions")
                button {
                    className = ClassName("btn-primary")
                    onClick = { onDismiss() }
                    +"OK"
                }
            }
        }
    }
}

/** One message row in the thread detail (the root with its title, or a reply). */
private fun ChildrenBuilder.threadMessage(
    msg: NostrGroupClient.NostrMessage,
    userMetadata: Map<String, UserMetadata>,
    isRoot: Boolean,
    myPubkey: String?,
    status: GroupManager.MessageStatus?,
    reactions: Map<String, GroupManager.ReactionInfo>,
    pendingEmojis: List<String>,
    parentMsg: NostrGroupClient.NostrMessage?,
    highlighted: Boolean,
    // Keeps this row tinted while its context menu is open, so the target of the actions is
    // unambiguous (chat parity: .msg.menu-open).
    menuOpen: Boolean,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onAddReaction: () -> Unit,
    onOpenMenu: (Double, Double) -> Unit,
    onCloseMenu: () -> Unit,
    onUser: (String) -> Unit,
    // Tapping the quoted parent scrolls to it; null when the parent is not in this thread.
    onJumpToParent: (() -> Unit)?,
    onGroupRef: (String, String?) -> Unit,
    /** Relay this thread's group is open on, for resolving quoted references against it. */
    hostRelayUrl: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    div {
        id = ElementId("thread-msg-${msg.id}")
        className = ClassName(
            (if (isRoot) "thread-msg thread-msg-root" else "thread-msg") +
                (if (highlighted) " highlight" else "") +
                (if (menuOpen) " menu-open" else ""),
        )
        // Right-click (desktop) / long-press (mobile browsers) opens the thread context menu.
        // Right-click directly on a hyperlink keeps the browser's native menu (chat parity),
        // so "Copy link address" copies the actual URL.
        onContextMenu = { e ->
            when {
                // On a hyperlink the browser menu wins, so "Copy link address" copies the URL.
                e.target.asDynamic().closest("a") != null -> onCloseMenu()
                // First right-click: ours at the cursor, native suppressed.
                !menuOpen -> {
                    e.preventDefault()
                    onOpenMenu(e.clientX, e.clientY)
                }
                // Second right-click: the row sits above the overlay, so the event lands here.
                // Close ours and let the native menu through (no preventDefault).
                else -> onCloseMenu()
            }
        }
        // Avatar and author name open the user profile modal (chat parity).
        div {
            className = ClassName("thread-msg-avatar-btn")
            onClick = { onUser(msg.pubkey) }
            WebAvatar {
                url = userMetadata[msg.pubkey]?.picture
                seed = msg.pubkey
                this.name = threadDisplayName(msg.pubkey, userMetadata[msg.pubkey])
                kind = AvatarKind.USER
                cls = "thread-msg-avatar"
            }
        }
        div {
            className = ClassName("thread-msg-main")
            div {
                className = ClassName("thread-msg-head")
                span {
                    className = ClassName("thread-msg-author")
                    onClick = { onUser(msg.pubkey) }
                    +threadDisplayName(msg.pubkey, userMetadata[msg.pubkey])
                }
                // Absolute date, not "2h": a post is a dated record.
                span {
                    className = ClassName("thread-msg-time")
                    +formatDateTime(msg.createdAt)
                }
            }
            if (isRoot) {
                h2 {
                    className = ClassName("thread-msg-title")
                    +msg.threadTitle()
                }
            }
            // Nested reply: quote the answered message above the body (placeholder when the
            // parent has not loaded), like chat's reply preview.
            if (!isRoot && (parentMsg != null || msg.threadParentIdTag() != null)) {
                div {
                    // Tappable only when the parent is in this thread; otherwise it is decoration
                    // and must not swallow the long-press that opens the context menu.
                    className = ClassName(if (onJumpToParent != null) "msg-reply tappable" else "msg-reply")
                    if (onJumpToParent != null) onClick = { onJumpToParent() }
                    div { className = ClassName("msg-reply-bar") }
                    div {
                        className = ClassName("msg-reply-content")
                        div {
                            className = ClassName("msg-reply-author")
                            +(parentMsg?.let { threadDisplayName(it.pubkey, userMetadata[it.pubkey]) } ?: "Replying to a message...")
                        }
                        parentMsg?.let { p ->
                            div {
                                className = ClassName("msg-reply-text")
                                +p.content.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
                            }
                        }
                    }
                }
            }
            div {
                className = ClassName("thread-msg-content")
                // Same rich renderer as chat: media embeds, links, mentions, custom emoji, markdown.
                renderMessageContent(
                    msg.content,
                    msg.tags,
                    userMetadata,
                    emptyMap(),
                    onUser = onUser,
                    onEventRef = {},
                    onGroupRef = onGroupRef,
                    // The event's own NIP-29 `h` tag scopes a quoted reference's by-id REQ.
                    groupId = msg.tags.firstOrNull { it.size >= 2 && it[0] == "h" }?.get(1),
                    hostRelay = hostRelayUrl,
                )
                // Inline send-state icon (clock/check) so no extra line shifts the list.
                if (myPubkey != null && myPubkey == msg.pubkey) {
                    sendStateIcon(status)
                }
            }
            if (myPubkey != null && myPubkey == msg.pubkey) {
                messageSendStatus(status, onRetry, onDismiss)
            }
            reactionBadges(reactions, pendingEmojis, myPubkey, userMetadata, onReact)
            // Answering a forum post is the primary action, so it is a visible footer button and
            // not only a context-menu item the way a chat reply is.
            div {
                className = ClassName("thread-post-actions")
                button {
                    className = ClassName("thread-post-action")
                    onClick = { onReply() }
                    icon(Ic.Reply)
                    span { +"Reply" }
                }
                button {
                    className = ClassName("thread-post-action")
                    onClick = { onAddReaction() }
                    icon(Ic.EmojiEmotions)
                    span { +"React" }
                }
            }
        }
    }
}
