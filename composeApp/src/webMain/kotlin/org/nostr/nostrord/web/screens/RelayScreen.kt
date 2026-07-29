package org.nostr.nostrord.web.screens

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.ui.screens.home.DiscoverGroup
import org.nostr.nostrord.ui.screens.home.HomePageViewModel
import org.nostr.nostrord.utils.isJoinedOn
import org.nostr.nostrord.utils.normalizeRelayUrl
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.AvatarKind
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.icon
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.span
import react.useEffect
import web.cssom.ClassName

external interface RelayScreenProps : Props {
    var relayUrl: String
    var onOpenGroup: (DiscoverGroup) -> Unit
    var onOpenDrawer: () -> Unit
}

/**
 * Relay page (prototype /r/:relayId): the relay's NIP-11 header (icon, name, status, url,
 * description, capability chips, add-to-list) and a meta grid (groups / software / NIPs /
 * operator), over the groups on it that you or people you follow are in, shown with the same
 * group-card as the Home page. Reached from the relay link in a group sidebar banner / card.
 */
val RelayScreen =
    FC<RelayScreenProps> { props ->
        val repo = AppModule.nostrRepository
        val vm = useViewModel { HomePageViewModel(repo) }
        val myGroups = useStateFlow(vm.myGroups)
        val friendsGroups = useStateFlow(vm.friendsGroups)
        val relayMeta = useStateFlow(vm.relayMetadata)
        val userMetadata = useStateFlow(repo.userMetadata)
        val connState = useStateFlow(repo.connectionState)
        val currentRelay = useStateFlow(repo.currentRelayUrl)
        val myRelays = useStateFlow(repo.kind10009Relays)
        val unreachable = useStateFlow(repo.unreachableRelays)
        // Per (relay, id): this page lists ONE relay's groups, and a same-id group joined on
        // another relay must not badge them as joined.
        val joinedByRelay = useStateFlow(repo.joinedGroupsByRelay)

        val target = props.relayUrl.normalizeRelayUrl()
        val info = relayMeta[props.relayUrl] ?: relayMeta[target]
        val url = props.relayUrl.trimEnd('/')
        val host = url.removePrefix("wss://").removePrefix("ws://")
        val title = info?.name?.takeIf { it.isNotBlank() } ?: host
        val nips = info?.supportedNips.orEmpty()
        val inList = target in myRelays
        val isCurrent = currentRelay.normalizeRelayUrl() == target

        // A relay is only "Offline" if a connection attempt actually failed (unreachableRelays).
        // Background joined/pool relays stay connected without being the primary, so gating on
        // "is the current relay" wrongly showed them offline. Connecting only applies to the
        // primary relay (the one whose live connectionState we track).
        val isUnreachable = target in unreachable || props.relayUrl in unreachable
        val (statusMod, statusLabel) =
            when {
                isUnreachable -> "off" to "Offline"
                isCurrent &&
                    (
                        connState is ConnectionManager.ConnectionState.Connecting ||
                            connState is ConnectionManager.ConnectionState.Reconnecting
                        ) -> "warn" to "Connecting…"
                else -> "ok" to "Connected"
            }

        val operatorPk = info?.pubkey
        useEffect(operatorPk) {
            if (operatorPk != null) launchApp { repo.requestUserMetadata(setOf(operatorPk)) }
        }
        val operatorName =
            operatorPk?.let { pk ->
                val m = userMetadata[pk]
                m?.displayName?.takeIf { it.isNotBlank() }
                    ?: m?.name?.takeIf { it.isNotBlank() }
                    ?: (pk.take(8) + "…")
            } ?: "Unknown"
        val software = info?.software?.let { it.substringAfterLast('/').ifBlank { it } } ?: "n/a"

        // My groups + friends' groups that live on this relay, de-duped, root-level only.
        val groups =
            (myGroups + friendsGroups)
                .filter { it.relayUrl.normalizeRelayUrl() == target && it.meta.parent == null }
                .distinctBy { it.meta.id }

        div {
            className = ClassName("relay-page")
            div {
                className = ClassName("relay-page-header")
                button {
                    className = ClassName("chat-icon-btn chat-drawer-btn")
                    onClick = { props.onOpenDrawer() }
                    icon(Ic.Menu)
                }
                icon(Ic.Public)
                span {
                    className = ClassName("relay-header-name")
                    +title
                }
            }

            div {
                className = ClassName("home-scroll")
                div {
                    className = ClassName("home-content")
                    div {
                        className = ClassName("relay-info-card")
                        WebAvatar {
                            this.url = info?.icon
                            seed = props.relayUrl
                            name = title
                            kind = AvatarKind.RELAY
                            cls = "relay-info-avatar"
                        }
                        div {
                            className = ClassName("relay-info-meta")
                            div {
                                className = ClassName("relay-info-name-row")
                                span {
                                    className = ClassName("relay-info-name")
                                    +title
                                }
                                span {
                                    className = ClassName("relay-status")
                                    span { className = ClassName("relay-status-dot $statusMod") }
                                    +statusLabel
                                }
                            }
                            div {
                                className = ClassName("relay-info-url")
                                +url
                            }
                            info?.description?.takeIf { it.isNotBlank() }?.let {
                                div {
                                    className = ClassName("relay-info-desc")
                                    +it
                                }
                            }
                            div {
                                className = ClassName("relay-info-chips")
                                if (29 in nips) {
                                    span {
                                        className = ClassName("relay-cap")
                                        +"NIP-29"
                                    }
                                }
                                if (info?.supportsSubgroups == true) {
                                    span {
                                        className = ClassName("relay-cap")
                                        +"Channels"
                                    }
                                }
                                if (42 in nips) {
                                    span {
                                        className = ClassName("relay-cap")
                                        +"NIP-42"
                                    }
                                }
                                if (inList) {
                                    span {
                                        className = ClassName("relay-list-tag")
                                        +"In your list"
                                    }
                                }
                            }
                        }
                        button {
                            className = ClassName(if (inList) "btn-secondary" else "btn-primary")
                            onClick = {
                                launchApp {
                                    if (inList) repo.removeRelay(props.relayUrl) else repo.addRelay(props.relayUrl)
                                }
                            }
                            icon(if (inList) Ic.Check else Ic.Add)
                            +(if (inList) "In your list" else "Add to list")
                        }
                    }

                    div {
                        className = ClassName("relay-meta-grid")
                        relayMetaCell("Software", software)
                        relayMetaCell("NIPs", if (nips.isEmpty()) "n/a" else nips.joinToString(", "))
                        relayMetaCell("Operator", operatorName)
                    }

                    h2 {
                        className = ClassName("relay-section-title")
                        +"Groups your network is in · ${groups.size}"
                    }
                    if (groups.isEmpty()) {
                        div {
                            className = ClassName("relay-empty")
                            +"No groups you or your friends are in on this relay."
                        }
                    } else {
                        div {
                            className = ClassName("card-grid")
                            groups.forEach { g ->
                                discoverGroupCard(g, info?.icon, joinedByRelay.isJoinedOn(g.relayUrl, g.meta.id)) { props.onOpenGroup(g) }
                            }
                        }
                    }
                }
            }
        }
    }

private fun react.ChildrenBuilder.relayMetaCell(label: String, value: String) {
    div {
        className = ClassName("relay-meta-cell")
        div {
            className = ClassName("relay-meta-label")
            +label
        }
        div {
            className = ClassName("relay-meta-value")
            +value
        }
    }
}
