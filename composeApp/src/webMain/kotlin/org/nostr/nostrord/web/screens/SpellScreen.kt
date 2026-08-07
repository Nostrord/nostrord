package org.nostr.nostrord.web.screens

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.navigation.UserRoute
import org.nostr.nostrord.ui.screens.spell.SpellViewModel
import org.nostr.nostrord.utils.formatTimestamp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.AvatarKind
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.navigation.pushRoute
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

external interface SpellScreenProps : Props {
    var spellId: String
    var onOpenDrawer: (() -> Unit)?
}

/**
 * A saved query rendered as a feed. Read-only, mirroring the Compose `SpellScreen`: both consume
 * the same [SpellViewModel], so behaviour lives in commonMain and only layout differs here.
 */
val SpellScreen = FC<SpellScreenProps> { props ->
    val vm = useViewModel(props.spellId) { SpellViewModel(spellId = props.spellId) }
    val events = useStateFlow(vm.events)
    val state = useStateFlow(vm.loadingState)
    val error = useStateFlow(vm.error)
    val metadata = useStateFlow(AppModule.nostrRepository.userMetadata)

    div {
        className = ClassName("page-header")
        props.onOpenDrawer?.let { open ->
            // Hidden above 767px by .chat-drawer-btn: on desktop the rail is already on screen.
            button {
                className = ClassName("chat-icon-btn chat-drawer-btn")
                onClick = { open() }
                icon(Ic.Menu)
            }
        }
        span {
            className = ClassName("page-header-title")
            +vm.title
        }
    }

    div {
        className = ClassName("spell-feed")

        when {
            error != null && events.isEmpty() -> div {
                className = ClassName("spell-empty")
                +error
            }
            events.isEmpty() && state.isLoading -> div {
                className = ClassName("spell-empty")
                +"Loading…"
            }
            events.isEmpty() -> div {
                className = ClassName("spell-empty")
                +"Nothing here yet. ${vm.subtitle}"
            }
            else -> events.forEach { event ->
                val meta = metadata[event.pubkey]
                val displayName = meta?.displayName ?: meta?.name ?: event.pubkey.take(8)
                div {
                    key = event.id
                    className = ClassName("spell-row")
                    WebAvatar {
                        url = meta?.picture
                        seed = event.pubkey
                        name = displayName
                        kind = AvatarKind.USER
                        cls = "spell-row-avatar"
                    }
                    div {
                        className = ClassName("spell-row-body")
                        div {
                            className = ClassName("spell-row-head")
                            span {
                                className = ClassName("spell-row-name")
                                +displayName
                            }
                            span {
                                className = ClassName("spell-row-time")
                                +formatTimestamp(event.createdAt)
                            }
                        }
                        div {
                            className = ClassName("spell-row-content")
                            // Same renderer chat uses: links, media, emoji and nostr refs.
                            renderMessageContent(
                                content = event.content,
                                tags = event.tags,
                                userMetadata = metadata,
                                messagesById = emptyMap(),
                                onUser = { pushRoute(UserRoute(it)) },
                                onEventRef = {},
                                onGroupRef = { _, _ -> },
                            )
                        }
                    }
                }
            }
        }

        if (state.canLoadMore && events.isNotEmpty()) {
            button {
                className = ClassName("spell-load-more")
                onClick = { vm.loadMore() }
                +if (state.isLoading) "Loading…" else "Load more"
            }
        }
    }
}
