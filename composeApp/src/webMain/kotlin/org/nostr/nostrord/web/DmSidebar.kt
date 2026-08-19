package org.nostr.nostrord.web

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.navigation.DmRoute
import org.nostr.nostrord.ui.screens.dm.DmViewModel
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.searchInput
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useState
import web.cssom.ClassName

external interface DmSidebarProps : Props {
    var activePubkey: String?
    var onOpenConversation: (DmRoute) -> Unit
}

/**
 * Second column on the DM section (prototype DMSidebar, obelisk-style): header with
 * search and the conversation list. The Follows / Others tabs (follows vs message
 * requests) live in DmConversationList so they travel to the mobile page too; searching a
 * valid npub/hex opens that conversation. Mirrors the Compose ui/components/layout/DmSidebar.
 */
val DmSidebar =
    FC<DmSidebarProps> { props ->
        val (searchOpen, setSearchOpen) = useState { false }
        val (query, setQuery) = useState { "" }
        val parsed = Nip19.parsePubkeyInput(query) as? Nip19.PubkeyParse.Ok

        div {
            className = ClassName("dm-side-header")
            span {
                className = ClassName("dm-side-title")
                +"Direct messages"
            }
            button {
                className = ClassName(if (searchOpen) "icon-btn active" else "icon-btn")
                title = "Search"
                onClick = {
                    setSearchOpen(!searchOpen)
                    setQuery("")
                }
                icon(Ic.Search)
            }
        }

        div {
            className = ClassName("dm-side-body")
            if (searchOpen) {
                div {
                    className = ClassName("dm-search")
                    searchInput(
                        placeholder = "Search by name, nip-05, npub or hex",
                        value = query,
                        onChange = { setQuery(it) },
                        compact = true,
                        autoFocus = true,
                        onClose = {
                            setSearchOpen(false)
                            setQuery("")
                        },
                    )
                }
                // A pasted npub/hex starts that conversation right away.
                if (parsed != null) {
                    div {
                        className = ClassName("dm-section-label")
                        +"Start a conversation"
                    }
                    button {
                        className = ClassName("dm-convo-row")
                        onClick = { props.onOpenConversation(DmRoute(parsed.hex)) }
                        WebAvatar {
                            seed = parsed.hex
                            name = parsed.hex.take(8)
                            cls = "dm-convo-avatar"
                        }
                        span {
                            className = ClassName("dm-convo-name")
                            +runCatching { Nip19.encodeNpub(parsed.hex) }.getOrDefault(parsed.hex)
                        }
                    }
                }
            }

            DmConversationList {
                activePubkey = props.activePubkey
                onOpenConversation = props.onOpenConversation
                onStartConversation = { setSearchOpen(true) }
            }
        }
    }

external interface DmConversationListProps : Props {
    var activePubkey: String?
    var onOpenConversation: (DmRoute) -> Unit

    /** Optional empty-state CTA (the sidebar opens search); omit to hide it. */
    var onStartConversation: (() -> Unit)?
}

/**
 * The DM conversation list (`.dm-convo-list`) plus its empty state, shared by the desktop
 * [DmSidebar] and the mobile DM page (which has no visible sidebar).
 */
val DmConversationList =
    FC<DmConversationListProps> { props ->
        val dmVm = useViewModel { DmViewModel(AppModule.nostrRepository) }
        val (tab, setTab) = useState { 0 }
        val follows = useStateFlow(dmVm.followsConversations)
        val others = useStateFlow(dmVm.othersConversations)
        val othersUnread = useStateFlow(dmVm.othersUnread)
        val userMetadata = useStateFlow(dmVm.userMetadata)
        val unreadByPeer = useStateFlow(dmVm.unreadByPeer)
        val conversations = if (tab == 0) follows else others

        // Keyed on the peer and on the follow list, so it lands on Others when the open
        // conversation is a request (including when kind:3 arrives after the list renders) and
        // then leaves the choice to whoever taps the tabs.
        val activeIsRequest = dmVm.isRequestPeer(props.activePubkey, useStateFlow(dmVm.following))
        useEffect(props.activePubkey, activeIsRequest) {
            if (activeIsRequest) setTab(1)
        }

        fun nameOf(pubkey: String): String {
            val meta = userMetadata[pubkey]
            return meta?.displayName?.takeIf { it.isNotBlank() }
                ?: meta?.name?.takeIf { it.isNotBlank() }
                ?: (runCatching { Nip19.encodeNpub(pubkey) }.getOrDefault(pubkey).take(12) + "…")
        }

        // Follows = peers you follow, Others = message requests; the Others tab badges its unread.
        div {
            className = ClassName("dm-tabs")
            listOf("Follows", "Others").forEachIndexed { index, label ->
                button {
                    className = ClassName(if (tab == index) "dm-tab selected" else "dm-tab")
                    onClick = { setTab(index) }
                    +label
                    if (index == 1 && othersUnread > 0) {
                        span {
                            className = ClassName("count-badge")
                            +(if (othersUnread > 99) "99+" else othersUnread.toString())
                        }
                    }
                }
            }
        }

        if (conversations.isEmpty()) {
            div {
                className = ClassName("dm-empty")
                div {
                    className = ClassName("dm-empty-tile")
                    +(if (tab == 0) "✉️" else "📥")
                }
                div {
                    className = ClassName("dm-empty-title")
                    +(if (tab == 0) "No messages yet" else "No message requests")
                }
                div {
                    className = ClassName("dm-empty-text")
                    +(
                        if (tab == 0) {
                            "Your direct messages are end-to-end encrypted. Start one with someone you follow."
                        } else {
                            "Messages from people you don't follow show up here."
                        }
                        )
                }
                if (tab == 0) {
                    props.onStartConversation?.let { start ->
                        button {
                            className = ClassName("dm-empty-cta")
                            onClick = { start() }
                            +"Start a conversation"
                        }
                    }
                }
            }
        } else {
            div {
                className = ClassName("dm-convo-list")
                conversations.forEach { c ->
                    val peer = c.peerPubkey
                    button {
                        key = peer
                        className = ClassName(if (props.activePubkey == peer) "dm-convo-row selected" else "dm-convo-row")
                        onClick = { props.onOpenConversation(DmRoute(peer)) }
                        WebAvatar {
                            url = userMetadata[peer]?.picture
                            seed = peer
                            name = nameOf(peer)
                            cls = "dm-convo-avatar"
                        }
                        span {
                            className = ClassName("dm-convo-meta")
                            span {
                                className = ClassName("dm-convo-name")
                                +nameOf(peer)
                            }
                            span {
                                className = ClassName("dm-convo-last")
                                +c.lastMessage
                            }
                        }
                        val unread = unreadByPeer[peer] ?: 0
                        if (unread > 0) {
                            span {
                                className = ClassName("dm-convo-unread")
                                +unread.toString()
                            }
                        }
                    }
                }
            }
        }
    }
