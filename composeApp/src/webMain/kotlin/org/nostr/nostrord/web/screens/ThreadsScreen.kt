package org.nostr.nostrord.web.screens

import kotlinx.browser.document
import kotlinx.browser.window
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.screens.group.ThreadsViewModel
import org.nostr.nostrord.ui.screens.group.threadTitle
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.shortNpub
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.AvatarKind
import org.nostr.nostrord.web.components.EmojiPicker
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.Portal
import org.nostr.nostrord.web.components.UploadButton
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.messageSendStatus
import org.nostr.nostrord.web.components.reactionBadges
import org.nostr.nostrord.web.components.sendStateIcon
import org.nostr.nostrord.web.components.uploadBlob
import org.nostr.nostrord.web.modals.CreateThreadModal
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.textarea
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName
import web.html.HTMLTextAreaElement
import kotlin.js.Date

// Mirrors ChatScreen.displayName (private there): profile name, else a short npub.
private fun threadDisplayName(pubkey: String, meta: UserMetadata?): String = meta?.displayName?.takeIf { it.isNotBlank() }
    ?: meta?.name?.takeIf { it.isNotBlank() }
    ?: shortNpub(pubkey)

private fun relativeTime(createdAtSeconds: Long): String {
    val nowSec = (Date.now() / 1000).toLong()
    val diff = (nowSec - createdAtSeconds).coerceAtLeast(0)
    return when {
        diff < 60 -> "now"
        diff < 3600 -> "${diff / 60}m"
        diff < 86_400 -> "${diff / 3600}h"
        diff < 604_800 -> "${diff / 86_400}d"
        else -> "${diff / 604_800}w"
    }
}

external interface ThreadsScreenProps : Props {
    var route: GroupRoute
    var group: GroupMetadata
    var onNavigate: (GroupRoute) -> Unit
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
        val threads = useStateFlow(vm.threads)
        val isLoading = useStateFlow(vm.isLoading)
        val openThread = useStateFlow(vm.openThread)
        val userMetadata = useStateFlow(vm.userMetadata)
        val messageStatus = useStateFlow(vm.messageStatus)
        val reactions = useStateFlow(vm.reactions)
        val pendingReactions = useStateFlow(vm.pendingReactions)
        val reactionError = useStateFlow(vm.reactionError)
        val myPubkey = vm.getPublicKey()

        // Full-picker target: the (eventId, authorPubkey) of the message being reacted to.
        val (reactingTo, setReactingTo) = useState<Pair<String, String>?> { null }

        // Keep the open thread synced with the URL (#/g/<relay>/<id>/threads/<rootId>).
        useEffect(route.threadRootId) { vm.openThread(route.threadRootId) }

        val (composing, setComposing) = useState { false }
        val (reply, setReply) = useState { "" }
        val (sending, setSending) = useState { false }
        val (emojiOpen, setEmojiOpen) = useState { false }
        val (uploadCount, setUploadCount) = useState { 0 }
        val (uploadError, setUploadError) = useState<String?> { null }
        val composerInputRef = useRef<HTMLTextAreaElement>(null)

        fun isMediaMime(type: String?): Boolean = type != null && (type.startsWith("image/") || type.startsWith("video/") || type.startsWith("audio/"))

        // Upload a pasted / dropped file and append its URL to the reply draft (parity with DM / group).
        fun handleMediaFile(file: dynamic) {
            setUploadCount { it + 1 }
            launchApp {
                try {
                    when (val r = uploadBlob(file)) {
                        is Result.Success -> setReply { prev -> if (prev.isBlank()) r.data.url else "$prev ${r.data.url}" }
                        is Result.Error -> setUploadError(r.error.message)
                    }
                } finally {
                    setUploadCount { it - 1 }
                }
            }
        }

        fun sendReply() {
            if (reply.isBlank() || sending) return
            setSending(true)
            vm.sendReply(
                reply,
                onSuccess = {
                    setReply("")
                    setSending(false)
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

        div {
            className = ClassName("threads-page")

            if (route.threadRootId != null) {
                // ---- Single thread (detail) ----
                div {
                    className = ClassName("threads-header")
                    button {
                        className = ClassName("icon-btn")
                        title = "Back to threads"
                        onClick = { props.onNavigate(route.copy(threadRootId = null)) }
                        icon(Ic.ArrowBack)
                    }
                    span {
                        className = ClassName("threads-title")
                        +"Thread"
                    }
                    val ownRoot = openThread?.root
                    if (myPubkey != null && ownRoot != null && ownRoot.pubkey == myPubkey) {
                        button {
                            className = ClassName("icon-btn")
                            title = "Delete thread"
                            onClick = {
                                if (window.confirm("Delete this thread? This cannot be undone.")) {
                                    vm.deleteThread(ownRoot.id)
                                    props.onNavigate(route.copy(threadRootId = null))
                                }
                            }
                            icon(Ic.Delete)
                        }
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

                    fun ChildrenBuilder.renderThreadMessage(msg: NostrGroupClient.NostrMessage, isRoot: Boolean) = threadMessage(
                        msg,
                        userMetadata,
                        isRoot = isRoot,
                        myPubkey,
                        messageStatus[msg.id],
                        reactions[msg.id] ?: emptyMap(),
                        pendingFor(msg.id),
                        onReact = { emoji -> vm.sendReaction(msg.id, msg.pubkey, emoji) },
                        onOpenPicker = { setReactingTo(msg.id to msg.pubkey) },
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
                    // Same composer as DM (.dm-composer): rounded bar, Enter to send, emoji picker.
                    div {
                        className = ClassName("dm-composer-wrap")
                        div {
                            className = ClassName("dm-composer")
                            UploadButton {
                                cls = "dm-composer-btn"
                                icon = Ic.AttachFile
                                busy = uploadCount > 0
                                onBusyChange = { b -> setUploadCount { if (b) it + 1 else it - 1 } }
                                onPickerClosed = { composerInputRef.current?.focus() }
                                onUploaded = { upload -> setReply { prev -> if (prev.isBlank()) upload.url else "$prev ${upload.url}" } }
                                onError = { setUploadError(it) }
                            }
                            textarea {
                                ref = composerInputRef
                                rows = 1
                                value = reply
                                placeholder = "Write a reply..."
                                onChange = { setReply((it.target as HTMLTextAreaElement).value) }
                                onKeyDown = { e ->
                                    if (e.key == "Enter" && !e.shiftKey) {
                                        e.preventDefault()
                                        sendReply()
                                    }
                                }
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
                            button {
                                className = ClassName(if (emojiOpen) "dm-composer-btn active" else "dm-composer-btn")
                                title = "Emoji"
                                onClick = { setEmojiOpen(!emojiOpen) }
                                icon(Ic.EmojiEmotions)
                            }
                            button {
                                className = ClassName("dm-composer-btn send")
                                title = "Send"
                                disabled = (reply.isBlank() && uploadCount == 0) || uploadCount > 0 || sending
                                onMouseDown = { e -> e.preventDefault() }
                                onClick = { sendReply() }
                                if (sending) span { className = ClassName("btn-spinner") } else icon(Ic.Send)
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
            } else {
                // ---- Threads list ----
                div {
                    className = ClassName("threads-header")
                    span {
                        className = ClassName("threads-title")
                        +"Threads"
                    }
                    button {
                        className = ClassName("btn-primary thread-new-btn")
                        onClick = { setComposing(true) }
                        icon(Ic.Forum)
                        span { +"New thread" }
                    }
                }

                if (composing) {
                    Portal {
                        CreateThreadModal {
                            onClose = { setComposing(false) }
                            onCreate = { title, content -> vm.createThread(title, content) }
                        }
                    }
                }

                when {
                    isLoading && threads.isEmpty() ->
                        div {
                            className = ClassName("threads-empty")
                            +"Loading threads..."
                        }
                    threads.isEmpty() ->
                        div {
                            className = ClassName("threads-empty")
                            +"No threads yet. Start the first one."
                        }
                    else ->
                        div {
                            className = ClassName("thread-list")
                            threads.forEach { t ->
                                button {
                                    key = t.rootId
                                    className = ClassName("thread-card")
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
                                            +relativeTime(t.lastActivity)
                                        }
                                    }
                                }
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
            reactionError?.let { err ->
                reactionErrorDialog(
                    err,
                    onDismiss = { vm.clearReactionError() },
                    onJoin = {
                        vm.clearReactionError()
                        vm.joinGroup()
                    },
                )
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
    onReact: (String) -> Unit,
    onOpenPicker: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    div {
        className = ClassName(if (isRoot) "thread-msg thread-msg-root" else "thread-msg")
        WebAvatar {
            url = userMetadata[msg.pubkey]?.picture
            seed = msg.pubkey
            this.name = threadDisplayName(msg.pubkey, userMetadata[msg.pubkey])
            kind = AvatarKind.USER
            cls = "thread-msg-avatar"
        }
        div {
            className = ClassName("thread-msg-main")
            div {
                className = ClassName("thread-msg-head")
                span {
                    className = ClassName("thread-msg-author")
                    +threadDisplayName(msg.pubkey, userMetadata[msg.pubkey])
                }
                span {
                    className = ClassName("thread-msg-time")
                    +relativeTime(msg.createdAt)
                }
            }
            if (isRoot) {
                h2 {
                    className = ClassName("thread-msg-title")
                    +msg.threadTitle()
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
                    onUser = {},
                    onEventRef = {},
                    onGroupRef = { _, _ -> },
                )
                // Inline send-state icon (clock/check) so no extra line shifts the list.
                if (myPubkey != null && myPubkey == msg.pubkey) {
                    sendStateIcon(status)
                }
            }
            if (myPubkey != null && myPubkey == msg.pubkey) {
                messageSendStatus(status, onRetry, onDismiss)
            }
            // Reaction badges + the always-visible add-reaction affordance (threads have no
            // hover-action row like chat, so the button is the way in to the full picker).
            div {
                className = ClassName("thread-msg-react-row")
                reactionBadges(reactions, pendingEmojis, myPubkey, userMetadata, onReact)
                button {
                    className = ClassName("thread-react-btn")
                    title = "Add reaction"
                    onClick = { onOpenPicker() }
                    icon(Ic.EmojiEmotions)
                }
            }
        }
    }
}
