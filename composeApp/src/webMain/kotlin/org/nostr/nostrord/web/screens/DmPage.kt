package org.nostr.nostrord.web.screens

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.awaitCancellation
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.managers.DmMessage
import org.nostr.nostrord.network.managers.previewText
import org.nostr.nostrord.ui.components.emoji.QuickReactions
import org.nostr.nostrord.ui.extractDmGroupInvite
import org.nostr.nostrord.ui.navigation.DmRoute
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.navigation.UserRoute
import org.nostr.nostrord.ui.screens.dm.DmChatItem
import org.nostr.nostrord.ui.screens.dm.DmViewModel
import org.nostr.nostrord.ui.screens.dm.buildDmChatItems
import org.nostr.nostrord.ui.screens.dm.eventJson
import org.nostr.nostrord.ui.screens.dm.prettyEventJson
import org.nostr.nostrord.ui.screens.profile.ProfilePageViewModel
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.formatDateTime
import org.nostr.nostrord.utils.formatTime
import org.nostr.nostrord.web.DmConversationList
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.DmAttachment
import org.nostr.nostrord.web.components.EmojiPicker
import org.nostr.nostrord.web.components.GroupInviteCard
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.PickedFile
import org.nostr.nostrord.web.components.UploadButton
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.copyToClipboard
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.reactionBadges
import org.nostr.nostrord.web.components.readPickedFile
import org.nostr.nostrord.web.components.sendStateIcon
import org.nostr.nostrord.web.components.useEscClose
import org.nostr.nostrord.web.modals.DmEventSourceModal
import org.nostr.nostrord.web.modals.DmRelaysModal
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.textarea
import react.useEffect
import react.useLayoutEffect
import react.useRef
import react.useState
import web.cssom.ClassName
import web.html.HTMLDivElement
import web.html.HTMLTextAreaElement
import kotlin.math.abs

external interface DmPageProps : Props {
    /** Peer of the open conversation; null shows the section's empty hero. */
    var pubkey: String?
    var onOpenProfile: (UserRoute) -> Unit
    var onOpenConversation: (DmRoute) -> Unit
    var onOpenGroup: (GroupRoute) -> Unit
    var onOpenDrawer: () -> Unit
}

/**
 * Direct-message conversation page (prototype DirectMessage, NIP-17 style). The
 * message backend does not exist yet: the conversation intro and the composer are
 * in place, with sending disabled until NIP-17 lands. Mirrors the Compose
 * ui/screens/dm/DmPageScreen.
 */
val DmPage =
    FC<DmPageProps> { props ->
        val pubkey = props.pubkey
        if (pubkey == null) {
            div {
                className = ClassName("dm-page")
                div {
                    className = ClassName("page-header")
                    button {
                        className = ClassName("icon-btn frame-menu-btn")
                        onClick = { props.onOpenDrawer() }
                        icon(Ic.Menu)
                    }
                    icon(Ic.Mail)
                    span {
                        className = ClassName("page-header-title")
                        +"Direct messages"
                    }
                }
                // Desktop: the conversation list lives in the sidebar, so the main area is an
                // empty hero. Mobile has no visible sidebar, so it shows the list here instead
                // (the two are toggled by CSS).
                div {
                    className = ClassName("dm-hero")
                    div {
                        className = ClassName("dm-hero-tile")
                        +"✉️"
                    }
                    h2 { +"Your direct messages" }
                    p { +"Pick a conversation on the side or start a new one with someone you follow." }
                }
                div {
                    className = ClassName("dm-page-convos")
                    DmConversationList {
                        activePubkey = null
                        onOpenConversation = { props.onOpenConversation(it) }
                        // The drawer hosts the DM sidebar's search, where a new conversation starts.
                        onStartConversation = { props.onOpenDrawer() }
                    }
                }
            }
            return@FC
        }

        val vm = useViewModel("dm-$pubkey") { ProfilePageViewModel(AppModule.nostrRepository, pubkey) }
        val metadata = useStateFlow(vm.metadata)
        val dmVm = useViewModel { DmViewModel(AppModule.nostrRepository) }
        val messages = useStateFlow(dmVm.messagesByPeer)[pubkey].orEmpty()
        // Metadata map for resolving @-mention names inside rich message bodies.
        val userMetadata = useStateFlow(dmVm.userMetadata)
        val dmStatus = useStateFlow(dmVm.messageStatus)
        val dmFiles = useStateFlow(dmVm.fileStates)
        val dmReactions = useStateFlow(dmVm.reactions)
        val syncing = useStateFlow(dmVm.syncing)
        // Message the full emoji picker was opened for; null while it is closed.
        val (reactingTo, setReactingTo) = useState<String?> { null }
        val myPubkey = dmVm.getPublicKey()
        // Message being replied to; the composer keeps its chip until the reply is sent.
        val (replyingTo, setReplyingTo) = useState<String?> { null }
        // Resolve where this peer reads before the first message is written, not after their reply.
        useEffect(pubkey) { dmVm.openConversation(pubkey) }
        // Mark the conversation read while it is open (and as new messages stream in).
        useEffect(pubkey, messages.size) {
            if (messages.isNotEmpty()) dmVm.markRead(pubkey)
        }
        // Open a conversation pinned to the latest message (scroll to the bottom), like a chat.
        val messagesRef = useRef<HTMLDivElement>(null)
        // True while the user is at (or near) the bottom; drives whether async media growth keeps
        // the view pinned. Updated on scroll; seeded true so a fresh conversation stays pinned.
        val pinnedToBottom = useRef(true)
        // Same fact as the ref, in state: the jump pill has to re-render when it changes.
        val (atBottom, setAtBottom) = useState { true }
        // Messages FROM THE PEER that landed while the reader was up in the history. Reported on
        // the pill so going back down is their decision with the count in hand. Own messages are
        // never counted: the reader wrote them, and writing already returns the view to the bottom.
        val (newWhileAway, setNewWhileAway) = useState { 0 }
        val seenPeerCount = useRef(0)
        val peerCount = messages.count { !it.mine }
        // Opening a conversation lands on the newest message; after that the position is the
        // reader's, not the stream's.
        useLayoutEffect(pubkey) {
            messagesRef.current?.let { el ->
                el.asDynamic().style.overflowAnchor = "none"
                el.scrollTop = el.scrollHeight.toDouble()
            }
            pinnedToBottom.current = true
        }
        // Following the feed: pinned to the bottom, anchoring off. Reading further up: anchoring
        // on and scrollTop untouched, so the browser holds the reading position when the backlog
        // inserts an older message above (same trick the group list uses). Dragging the reader to
        // the bottom for a message they did not send is what makes a backfill feel like a bug.
        useLayoutEffect(messages.size) {
            val el = messagesRef.current ?: return@useLayoutEffect
            // Reading further up: nothing to do. The anchor is already "auto" (set the moment the
            // reader left the bottom), so the browser has held their position through the insert.
            if (pinnedToBottom.current == true) el.scrollTop = el.scrollHeight.toDouble()
        }
        useEffect(peerCount, atBottom) {
            if (atBottom) {
                seenPeerCount.current = peerCount
                setNewWhileAway(0)
            } else {
                setNewWhileAway((peerCount - (seenPeerCount.current ?: 0)).coerceAtLeast(0))
            }
        }
        // Our own send always returns to the bottom: the reader caused this one.
        val newestOwnId = messages.lastOrNull()?.takeIf { it.mine }?.id
        useLayoutEffect(newestOwnId) {
            if (newestOwnId == null) return@useLayoutEffect
            val el = messagesRef.current ?: return@useLayoutEffect
            pinnedToBottom.current = true
            seenPeerCount.current = peerCount
            setAtBottom(true)
            el.asDynamic().style.overflowAnchor = "none"
            el.scrollTop = el.scrollHeight.toDouble()
        }
        // Inline media (images/video/audio) loads after render and grows the list; if the user was
        // at the bottom, follow the growth so the newest message stays in view. A capturing listener
        // catches child load/loadedmetadata (they don't bubble). Setting scrollTop is jank-free.
        useEffect(pubkey) {
            val el = messagesRef.current ?: return@useEffect
            val onMediaLoad: (dynamic) -> Unit = {
                if (pinnedToBottom.current == true) el.scrollTop = el.scrollHeight.toDouble()
            }
            el.asDynamic().addEventListener("load", onMediaLoad, true)
            el.asDynamic().addEventListener("loadedmetadata", onMediaLoad, true)
            try {
                awaitCancellation()
            } finally {
                el.asDynamic().removeEventListener("load", onMediaLoad, true)
                el.asDynamic().removeEventListener("loadedmetadata", onMediaLoad, true)
            }
        }
        val (text, setText) = useState { "" }
        val (sending, setSending) = useState { false }
        val send = {
            if (text.isNotBlank() && !sending) {
                setSending(true)
                dmVm.send(
                    pubkey,
                    text,
                    replyToId = replyingTo,
                    onSuccess = {
                        setText("")
                        setReplyingTo(null)
                        setSending(false)
                    },
                    onFailure = { setSending(false) },
                )
            }
        }
        val name =
            metadata?.displayName?.takeIf { it.isNotBlank() }
                ?: metadata?.name?.takeIf { it.isNotBlank() }
                ?: vm.npub.take(12) + "..."

        // Composer media + emoji, mirroring the group ChatComposer (no mentions / formatting here).
        val (emojiOpen, setEmojiOpen) = useState { false }
        val (uploadCount, setUploadCount) = useState { 0 }
        val (uploadError, setUploadError) = useState<String?> { null }
        val composerInputRef = useRef<HTMLTextAreaElement>(null)
        // Auto-grow the composer as newlines are added (Shift+Enter), matching the group chat
        // composer; reset to "auto" first so it also shrinks when text is deleted or sent.
        useEffect(text) {
            val el = composerInputRef.current ?: return@useEffect
            el.style.height = "auto"
            el.style.height = "${el.scrollHeight}px"
        }

        // Header kebab menu + its DM-relays modal.
        val isFollowing = useStateFlow(vm.isFollowing)
        val isMutedPeer = useStateFlow(vm.isMuted)
        val peerRelays = useStateFlow(dmVm.dmRelaysByPubkey)[pubkey].orEmpty()
        val (headerMenuOpen, setHeaderMenuOpen) = useState { false }
        val (relaysOpen, setRelaysOpen) = useState { false }

        // Context menu (right-click / long-press on a bubble), mirroring ChatScreen's
        // two-stage pattern trimmed to the DM action set.
        val (menuFor, setMenuFor) = useState<String?> { null }
        // Message whose source (rumor JSON + relays) is shown in the modal.
        val (sourceFor, setSourceFor) = useState<String?> { null }
        val (menuAt, setMenuAt) = useState { 0 to 0 }
        val menuRef = useRef<HTMLDivElement>(null)
        val longPressTimer = useRef(0)
        val longPressReady = useRef(false)
        val touchStartX = useRef(0.0)
        val touchStartY = useRef(0.0)
        // Timestamp (ms) of a touch-opened menu, to swallow the trailing ghost click.
        val menuOpenedAt = useRef(0.0)

        // Place the fixed menu at its anchor, flipping up/left when it would overflow.
        useEffect(menuFor) {
            if (menuFor == null) return@useEffect
            val el = menuRef.current?.asDynamic() ?: return@useEffect
            val w = el.offsetWidth as Int
            val h = el.offsetHeight as Int
            var left = menuAt.first
            if (left + w > window.innerWidth - 8) left = (window.innerWidth - 8 - w).coerceAtLeast(8)
            var top = menuAt.second
            if (top + h > window.innerHeight - 8) top = (menuAt.second - h).coerceAtLeast(8)
            el.style.left = "${left}px"
            el.style.top = "${top}px"
            el.style.visibility = "visible"
        }
        useEscClose { if (emojiOpen) setEmojiOpen(false) }

        fun isMediaMime(type: String?): Boolean = type != null && (type.startsWith("image/") || type.startsWith("video/") || type.startsWith("audio/"))

        // Send a picked / pasted / dropped file as an encrypted kind:15 message. It is not appended
        // to the draft as a url the way the group composer does: a DM attachment is encrypted
        // before upload, so the server holds bytes nobody else can read.
        fun sendMediaFile(picked: PickedFile) {
            setUploadCount { it + 1 }
            launchApp {
                try {
                    dmVm.sendFile(
                        recipientPubkey = pubkey,
                        bytes = picked.bytes,
                        filename = picked.name,
                        mimeType = picked.mimeType,
                        onFailure = { setUploadError(it) },
                    )
                } finally {
                    setUploadCount { it - 1 }
                }
            }
        }

        fun handleMediaFile(file: dynamic) {
            setUploadCount { it + 1 }
            launchApp {
                try {
                    when (val r = readPickedFile(file)) {
                        is Result.Success -> sendMediaFile(r.data)
                        is Result.Error -> setUploadError(r.error.message)
                    }
                } finally {
                    setUploadCount { it - 1 }
                }
            }
        }

        fun insertAtCursor(s: String) {
            val ta = composerInputRef.current
            if (ta == null) {
                setText { it + s }
                return
            }
            ta.focus()
            document.asDynamic().execCommand("insertText", false, s)
        }

        div {
            className = ClassName("dm-page")
            div {
                className = ClassName("page-header")
                button {
                    className = ClassName("icon-btn frame-menu-btn")
                    onClick = { props.onOpenDrawer() }
                    icon(Ic.Menu)
                }
                button {
                    className = ClassName("dm-peer")
                    onClick = { props.onOpenProfile(UserRoute(pubkey)) }
                    WebAvatar {
                        url = metadata?.picture
                        seed = pubkey
                        this.name = name
                        cls = "dm-peer-avatar"
                    }
                    span {
                        className = ClassName("page-header-title")
                        +name
                    }
                }
                div {
                    className = ClassName("dm-header-menu-wrap")
                    button {
                        className = ClassName("icon-btn")
                        onClick = { setHeaderMenuOpen(true) }
                        icon(Ic.MoreVert)
                    }
                    if (headerMenuOpen) {
                        div {
                            className = ClassName("dm-header-menu-backdrop")
                            onClick = { setHeaderMenuOpen(false) }
                        }
                        div {
                            className = ClassName("dm-header-menu")
                            ctxItem(Ic.Person, "View profile") {
                                setHeaderMenuOpen(false)
                                props.onOpenProfile(UserRoute(pubkey))
                            }
                            ctxItem(if (isFollowing) Ic.PersonRemove else Ic.PersonAdd, if (isFollowing) "Unfollow" else "Follow") {
                                setHeaderMenuOpen(false)
                                vm.toggleFollow()
                            }
                            ctxItem(Ic.NotificationsOff, if (isMutedPeer) "Unmute user" else "Mute user") {
                                setHeaderMenuOpen(false)
                                vm.toggleMute()
                            }
                            ctxItem(Ic.ContentCopy, "Copy npub") {
                                setHeaderMenuOpen(false)
                                copyToClipboard(vm.npub)
                            }
                            ctxItem(Ic.Public, "View DM relays") {
                                setHeaderMenuOpen(false)
                                dmVm.loadPeerDmRelays(pubkey)
                                setRelaysOpen(true)
                            }
                        }
                    }
                }
            }

            div {
                className = ClassName("dm-messages-wrap")
                div {
                    className = ClassName("dm-messages")
                    ref = messagesRef
                    onScroll = {
                        val el = messagesRef.current
                        if (el != null) {
                            val nowAtBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 80.0
                            if (pinnedToBottom.current != nowAtBottom) {
                                pinnedToBottom.current = nowAtBottom
                                if (nowAtBottom) seenPeerCount.current = peerCount
                                setAtBottom(nowAtBottom)
                            }
                            // Set here, not when the list next changes: the browser applies its scroll
                            // anchor while laying out the insertion itself, so switching it afterwards
                            // (from an effect) arrives one mutation too late and the message that
                            // arrived still moves the page.
                            el.asDynamic().style.overflowAnchor = if (nowAtBottom) "none" else "auto"
                        }
                    }
                    // Sitting above the thread, so older messages landing in are expected rather than
                    // startling. Sending stays available: no client can promise it holds every message.
                    if (syncing) {
                        div {
                            className = ClassName("dm-syncing")
                            span { className = ClassName("upload-spinner") }
                            +"Catching up on older messages"
                        }
                    }
                    div {
                        className = ClassName("dm-intro")
                        WebAvatar {
                            url = metadata?.picture
                            seed = pubkey
                            this.name = name
                            cls = "dm-intro-avatar link"
                            onClick = { props.onOpenProfile(UserRoute(pubkey)) }
                        }
                        div {
                            className = ClassName("dm-intro-name link")
                            onClick = { props.onOpenProfile(UserRoute(pubkey)) }
                            +name
                        }
                        div {
                            className = ClassName("dm-intro-text")
                            +"Beginning of your direct conversation with $name. Direct messages are encrypted (NIP-17)."
                        }
                    }
                    buildDmChatItems(messages).forEach { item ->
                        when (item) {
                            is DmChatItem.DateSeparator ->
                                div {
                                    key = "sep-${item.label}"
                                    className = ClassName("date-sep")
                                    span {
                                        className = ClassName("date-sep-label")
                                        +item.label
                                    }
                                }
                            is DmChatItem.Message -> {
                                val m = item.message
                                div {
                                    key = m.id
                                    className =
                                        ClassName(
                                            buildString {
                                                append("dm-msg")
                                                if (m.mine) append(" mine")
                                                if (!item.firstInGroup) append(" grouped")
                                            },
                                        )
                                    // First right-click opens our menu at the cursor; with it open the
                                    // second lands on the overlay, which closes ours without
                                    // preventDefault so the native menu shows (Telegram-style).
                                    // Right-click directly on a hyperlink always keeps the native menu.
                                    onContextMenu = { event ->
                                        if (event.target.asDynamic().closest("a") != null) {
                                            setMenuFor(null)
                                        } else if (menuFor == null) {
                                            event.preventDefault()
                                            menuOpenedAt.current = 0.0
                                            setMenuAt(event.clientX.toInt() to event.clientY.toInt())
                                            setMenuFor(m.id)
                                        } else {
                                            setMenuFor(null)
                                        }
                                    }
                                    // Stationary 380ms hold arms the long-press; the menu opens on
                                    // touchend so the page can't jump while the finger is down.
                                    onTouchStart = { event ->
                                        val t = event.asDynamic().touches[0]
                                        touchStartX.current = t.clientX as Double
                                        touchStartY.current = t.clientY as Double
                                        longPressReady.current = false
                                        window.clearTimeout(longPressTimer.current ?: 0)
                                        longPressTimer.current = window.setTimeout({
                                            longPressReady.current = true
                                            val nav = window.navigator.asDynamic()
                                            if (nav.vibrate != null) nav.vibrate(15)
                                        }, 380)
                                    }
                                    onTouchMove = { event ->
                                        val t = event.asDynamic().touches[0]
                                        val dx = (t.clientX as Double) - (touchStartX.current ?: 0.0)
                                        val dy = (t.clientY as Double) - (touchStartY.current ?: 0.0)
                                        if (abs(dx) > 10.0 || abs(dy) > 10.0) {
                                            window.clearTimeout(longPressTimer.current ?: 0)
                                            longPressReady.current = false
                                        }
                                    }
                                    onTouchEnd = { event ->
                                        window.clearTimeout(longPressTimer.current ?: 0)
                                        if (longPressReady.current == true && menuFor == null) {
                                            // Suppress the synthesized click so it can't hit the
                                            // overlay and instantly close the menu we're opening.
                                            event.preventDefault()
                                            menuOpenedAt.current = kotlin.js.Date.now()
                                            setMenuAt(
                                                (touchStartX.current ?: 0.0).toInt() to (touchStartY.current ?: 0.0).toInt(),
                                            )
                                            setMenuFor(m.id)
                                        }
                                    }
                                    // Clock on its own line below the message, right-aligned (matches
                                    // native); hover shows the full date.
                                    div {
                                        className = ClassName("dm-bubble")
                                        title = formatDateTime(m.createdAt)
                                        // A group naddr on its own line renders as the prototype
                                        // invite card (text above, card + View group button below).
                                        // Quote of the message this one answers, above its body.
                                        m.replyToId?.let { parentId ->
                                            val parent = messages.firstOrNull { it.id == parentId }
                                            div {
                                                className = ClassName("msg-reply")
                                                div { className = ClassName("msg-reply-bar") }
                                                div {
                                                    className = ClassName("msg-reply-content")
                                                    div {
                                                        className = ClassName("msg-reply-author")
                                                        +dmReplyAuthorName(parent, userMetadata, name, myPubkey)
                                                    }
                                                    parent?.let { p ->
                                                        div {
                                                            className = ClassName("msg-reply-text")
                                                            +p.previewText()
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        val attachment = m.file
                                        val invite = extractDmGroupInvite(m.content)
                                        val body = if (attachment != null) "" else invite?.remainingText ?: m.content
                                        if (attachment != null) {
                                            DmAttachment {
                                                file = attachment
                                                state = dmFiles[m.id]
                                                onLoad = { dmVm.loadFile(m) }
                                                onRetry = { dmVm.retryFile(m) }
                                            }
                                        }
                                        if (body.isNotBlank()) {
                                            // Rich body: inline images/video/audio/links/mentions/markdown,
                                            // reusing the group chat renderer (same package).
                                            renderMessageContent(
                                                body,
                                                // The rumor's own tags: custom emoji and the imeta
                                                // hints that pre-size an inline image, same as chat.
                                                m.tags,
                                                userMetadata,
                                                emptyMap(),
                                                { props.onOpenProfile(UserRoute(it)) },
                                                {},
                                                { gid, relay -> relay?.let { props.onOpenGroup(GroupRoute(it, gid)) } },
                                                // A DM is not in a group: the by-id REQ stays unscoped
                                                // and there is no host relay to compare a quote against.
                                                null,
                                                "",
                                            )
                                        }
                                        if (invite != null) {
                                            GroupInviteCard {
                                                groupId = invite.groupId
                                                relayUrl = invite.relayUrl
                                                onOpen = { props.onOpenGroup(GroupRoute(invite.relayUrl, invite.groupId)) }
                                            }
                                        }
                                        span {
                                            className = ClassName("dm-bubble-time")
                                            +formatTime(m.createdAt)
                                            // Send state on own messages: clock while Sending, check
                                            // once a relay OKs the wrap (reuses the group chat icon).
                                            if (m.mine) sendStateIcon(dmStatus[m.id])
                                        }
                                        // Reactions hang inside the bubble so they follow its edge,
                                        // the way the group chat renders them under a message.
                                        dmReactions[m.id]?.let { byEmoji ->
                                            reactionBadges(byEmoji, emptyList(), myPubkey, userMetadata) { emoji ->
                                                dmVm.react(pubkey, m.id, emoji)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                val menuMsg = messages.firstOrNull { it.id == menuFor }
                if (menuMsg != null) {
                    div {
                        className = ClassName("ctx-overlay")
                        onTouchStart = { it.stopPropagation() }
                        onTouchMove = { it.stopPropagation() }
                        onTouchEnd = { it.stopPropagation() }
                        onClick = {
                            // Ignore the synthesized click that trails a touch-open.
                            if (kotlin.js.Date.now() - (menuOpenedAt.current ?: 0.0) > 400.0) setMenuFor(null)
                        }
                        // Close without preventDefault so the browser shows its native menu.
                        onContextMenu = { setMenuFor(null) }
                    }
                    div {
                        ref = menuRef
                        className = ClassName("ctx-menu")
                        onTouchStart = { it.stopPropagation() }
                        onTouchMove = { it.stopPropagation() }
                        onTouchEnd = { it.stopPropagation() }
                        // Quick-reactions row (one tap to react) + the full picker, mirroring the
                        // group chat menu and the native DM one.
                        div {
                            className = ClassName("ctx-reactions")
                            for (emoji in QuickReactions) {
                                button {
                                    className = ClassName("ctx-reaction")
                                    onClick = {
                                        dmVm.react(pubkey, menuMsg.id, emoji)
                                        setMenuFor(null)
                                    }
                                    +emoji
                                }
                            }
                            button {
                                className = ClassName("ctx-reaction ctx-reaction-more")
                                title = "Add reaction"
                                onClick = {
                                    setMenuFor(null)
                                    setReactingTo(menuMsg.id)
                                }
                                icon(Ic.EmojiEmotions)
                            }
                        }
                        ctxItem(Ic.Reply, "Reply") {
                            setReplyingTo(menuMsg.id)
                            setMenuFor(null)
                        }
                        ctxItem(Ic.Visibility, "View source") {
                            setSourceFor(menuMsg.id)
                            setMenuFor(null)
                        }
                        ctxItem(Ic.ContentCopy, "Copy text") {
                            copyToClipboard(menuMsg.content)
                            setMenuFor(null)
                        }
                    }
                }

                reactingTo?.let { targetId ->
                    div {
                        className = ClassName("emoji-overlay")
                        onClick = { setReactingTo(null) }
                        EmojiPicker {
                            onPick = { emoji ->
                                dmVm.react(pubkey, targetId, emoji)
                                setReactingTo(null)
                            }
                        }
                    }
                }

                val sourceMsg = messages.firstOrNull { it.id == sourceFor }
                if (sourceMsg != null) {
                    DmEventSourceModal {
                        json = sourceMsg.prettyEventJson()
                        relays = sourceMsg.relays.toTypedArray()
                        onCopy = { copyToClipboard(sourceMsg.eventJson()) }
                        onClose = { setSourceFor(null) }
                    }
                }
                // Rendered after the message list so toggling it never shifts the list's
                // sibling position (which would remount it and reset the scroll to the top).
                if (relaysOpen) {
                    DmRelaysModal {
                        relays = peerRelays.toTypedArray()
                        onClose = { setRelaysOpen(false) }
                    }
                }

                // Returning to the newest message is a tap, never something the feed does on its
                // own. The count is what arrived while the reader stayed up in the history.
                if (!atBottom) {
                    button {
                        className = ClassName("chat-jump-bottom")
                        title = "Jump to latest message"
                        onClick = {
                            val el = messagesRef.current
                            if (el != null) {
                                pinnedToBottom.current = true
                                seenPeerCount.current = peerCount
                                setAtBottom(true)
                                setNewWhileAway(0)
                                el.asDynamic().style.overflowAnchor = "none"
                                el.scrollTop = el.scrollHeight.toDouble()
                            }
                        }
                        if (newWhileAway > 0) {
                            span {
                                className = ClassName("dm-jump-count")
                                +(if (newWhileAway > 99) "99+ new" else "$newWhileAway new")
                            }
                        }
                        icon(Ic.ExpandMore)
                    }
                }
            }

            div {
                className = ClassName("dm-composer-wrap")
                // Reply chip above the input, same markup as the group composer's.
                replyingTo?.let { targetId ->
                    val parent = messages.firstOrNull { it.id == targetId }
                    div {
                        className = ClassName("composer-reply")
                        icon(Ic.Reply)
                        span {
                            className = ClassName("composer-reply-label")
                            +"Replying to"
                        }
                        span {
                            className = ClassName("composer-reply-name")
                            +dmReplyAuthorName(parent, userMetadata, name, myPubkey)
                        }
                        parent?.previewText()?.takeIf { it.isNotBlank() }?.let { preview ->
                            span {
                                className = ClassName("composer-reply-text")
                                +preview
                            }
                        }
                        button {
                            className = ClassName("composer-reply-close")
                            onClick = { setReplyingTo(null) }
                            icon(Ic.Close)
                        }
                    }
                }
                div {
                    className = ClassName("dm-composer")
                    UploadButton {
                        cls = "dm-composer-btn"
                        icon = Ic.AttachFile
                        busy = uploadCount > 0
                        onBusyChange = { b -> setUploadCount { if (b) it + 1 else it - 1 } }
                        onPickerClosed = { composerInputRef.current?.focus() }
                        onUploaded = {}
                        onPicked = { picked -> sendMediaFile(picked) }
                        onError = { setUploadError(it) }
                    }
                    textarea {
                        ref = composerInputRef
                        rows = 1
                        value = text
                        placeholder = "Message $name"
                        onChange = { setText((it.target as HTMLTextAreaElement).value) }
                        onKeyDown = { e ->
                            if (e.key == "Enter" && !e.shiftKey) {
                                e.preventDefault()
                                send()
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
                        disabled = (text.isBlank() && uploadCount == 0) || uploadCount > 0 || sending
                        onMouseDown = { e -> e.preventDefault() }
                        onClick = { send() }
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
            uploadError?.let { uploadErrorDialog(it) { setUploadError(null) } }
        }
    }

/** Minimal "upload failed" dialog, parity with the group composer's. */
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

/**
 * Name to show on a reply quote. The peer's own name is already in the header, so an unknown
 * parent falls back to it rather than to a raw npub.
 */
private fun dmReplyAuthorName(
    parent: DmMessage?,
    metadata: Map<String, UserMetadata>,
    peerName: String,
    myPubkey: String?,
): String = when {
    parent == null -> peerName
    parent.senderPubkey == myPubkey -> "You"
    else ->
        metadata[parent.senderPubkey]?.displayName
            ?: metadata[parent.senderPubkey]?.name
            ?: peerName
}
