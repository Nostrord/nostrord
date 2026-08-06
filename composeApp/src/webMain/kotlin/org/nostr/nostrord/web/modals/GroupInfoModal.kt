package org.nostr.nostrord.web.modals

import js.objects.unsafeJso
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.settings.NotificationLevel
import org.nostr.nostrord.ui.navigation.RelayRoute
import org.nostr.nostrord.ui.screens.group.GroupViewModel
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.AvatarKind
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.aboutMentionPubkeys
import org.nostr.nostrord.web.components.bannerGradientCss
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.renderAboutText
import org.nostr.nostrord.web.components.useEscClose
import org.nostr.nostrord.web.navigation.pushRoute
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.b
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useState
import web.cssom.ClassName

external interface GroupInfoModalProps : Props {
    var group: GroupMetadata

    /** Relay hosting the group; scopes the member count and the RELAY link. */
    var relayUrl: String?
    var onLeave: () -> Unit
    var onClose: () -> Unit
}

/**
 * Group info modal — prototype GroupInfoModal: title bar, gradient cover with the
 * centered group avatar, name, status badges, ABOUT, per-group NOTIFICATIONS level,
 * the RELAY link and, for members, Leave group with an inline confirm. (The group
 * address moved to ShareGroupModal.)
 */
val GroupInfoModal =
    FC<GroupInfoModalProps> { props ->
        val group = props.group
        val groupName = group.name?.takeIf { it.isNotBlank() } ?: "Group"
        val notificationSettings = AppModule.notificationSettings
        val groupLevels = useStateFlow(notificationSettings.groupLevels)
        val defaultLevel = useStateFlow(notificationSettings.defaultLevel)
        val level = groupLevels[group.id] ?: defaultLevel
        val userMetadata = useStateFlow(AppModule.nostrRepository.userMetadata)
        // Through the relay-scoped VM: the flat map's count can belong to a same-id group
        // on another relay.
        val vm = useViewModel("${props.relayUrl}|${group.id}") { GroupViewModel(AppModule.nostrRepository, group.id, props.relayUrl) }
        val memberCount = useStateFlow(vm.groupMembers)[group.id]?.size ?: 0
        // Leaving is about the user's own kind:10009 list, so it is offered whenever the group is
        // in that list for THIS relay, even if the relay is dead and membership/posting can't be
        // confirmed. Gating on "can post" would hide the only exit from a broken relay; matching
        // the id across all relays offered Leave for a same-id group joined somewhere else.
        val isJoined = useStateFlow(vm.isJoinedHere)
        val isListedPrivately = useStateFlow(vm.isListedPrivately)
        val privateSectionOpaque = useStateFlow(vm.privateListSectionOpaque)
        val focusedRelay = useStateFlow(AppModule.nostrRepository.currentRelayUrl)
        val relayUrl = props.relayUrl ?: focusedRelay
        val relayMetadata = useStateFlow(AppModule.nostrRepository.relayMetadata)
        // Mention click in the description opens that user's profile on top.
        val (profilePubkey, setProfilePubkey) = useState<String?> { null }
        val (confirmLeave, setConfirmLeave) = useState { false }
        // Resolve @names for any npub/nprofile in the description (native RichAboutText
        // does the same fetch pass) so mentions show display names, not raw npubs.
        useEffect(group.about) {
            val pks = aboutMentionPubkeys(group.about ?: "")
            if (pks.isNotEmpty()) launchApp { AppModule.nostrRepository.requestUserMetadata(pks) }
        }
        useEscClose { props.onClose() }

        div {
            className = ClassName("modal-overlay")
            onClick = { props.onClose() }
            div {
                className = ClassName("modal-card info-card")
                onClick = { it.stopPropagation() }

                // Prototype Modal header: title + X over a bottom border, above the cover.
                // Standard .modal-header object; .info-card scopes the padded/bordered variant.
                div {
                    className = ClassName("modal-header")
                    div {
                        className = ClassName("modal-title")
                        +"Group Info"
                    }
                    button {
                        className = ClassName("modal-close")
                        onClick = { props.onClose() }
                        icon(Ic.Close)
                    }
                }

                // Full-bleed gradient band with the centered group avatar (prototype cover).
                div {
                    className = ClassName("info-cover")
                    style = unsafeJso { background = bannerGradientCss(group.id).unsafeCast<web.cssom.Background>() }
                    WebAvatar {
                        url = group.picture
                        seed = group.id
                        kind = AvatarKind.GROUP
                        name = groupName
                        cls = "info-cover-avatar"
                    }
                }

                div {
                    className = ClassName("info-content")
                    div {
                        className = ClassName("info-name")
                        +groupName
                    }
                    div {
                        className = ClassName("info-badges")
                        span {
                            className = ClassName(if (group.isPublic) "info-badge success" else "info-badge warning")
                            +(if (group.isPublic) "Public" else "Private")
                        }
                        span {
                            className = ClassName(if (group.isOpen) "info-badge primary" else "info-badge orange")
                            +(if (group.isOpen) "Open" else "Closed")
                        }
                        if (group.isRestricted) {
                            span {
                                className = ClassName("info-badge danger")
                                +"Restricted"
                            }
                        }
                        if (group.isHidden) {
                            span {
                                className = ClassName("info-badge info")
                                +"Hidden"
                            }
                        }
                        if (memberCount > 0) {
                            span {
                                className = ClassName("info-badge")
                                +"$memberCount members"
                            }
                        }
                    }

                    if (!group.about.isNullOrBlank()) {
                        div {
                            className = ClassName("settings-section-head")
                            +"ABOUT"
                        }
                        div {
                            className = ClassName("info-about")
                            renderAboutText(group.about, userMetadata) { setProfilePubkey(it) }
                        }
                    }

                    div {
                        className = ClassName("settings-section-head")
                        +"NOTIFICATIONS"
                    }
                    div {
                        className = ClassName("info-radio-list")
                        infoRadio("All messages", "Notify for every message in this group.", level == NotificationLevel.ALL) {
                            notificationSettings.setGroupLevel(group.id, NotificationLevel.ALL)
                        }
                        infoRadio(
                            "Mentions & replies",
                            "Only replies, @mentions, and reactions to your messages.",
                            level == NotificationLevel.MENTIONS_REPLIES,
                        ) {
                            notificationSettings.setGroupLevel(group.id, NotificationLevel.MENTIONS_REPLIES)
                        }
                        infoRadio("Muted", "Silence everything, including replies and mentions.", level == NotificationLevel.MUTED) {
                            notificationSettings.setGroupLevel(group.id, NotificationLevel.MUTED)
                        }
                    }

                    // Relay this group lives on: tappable to open the relay page (parity with
                    // the native GroupInfoModal and the group sidebar banner's relay link).
                    val relayHost = relayUrl.removePrefix("wss://").removePrefix("ws://").trimEnd('/')
                    if (relayHost.isNotBlank()) {
                        div {
                            className = ClassName("settings-section-head")
                            +"RELAY"
                        }
                        button {
                            className = ClassName("info-relay-row")
                            onClick = {
                                pushRoute(RelayRoute(relayUrl))
                                props.onClose()
                            }
                            WebAvatar {
                                url = relayMetadata[relayUrl]?.icon
                                seed = relayUrl
                                name = relayHost
                                kind = AvatarKind.RELAY
                                cls = "info-relay-icon"
                            }
                            span {
                                className = ClassName("info-relay-host")
                                +relayHost
                            }
                        }
                    }

                    if (isJoined) {
                        div { className = ClassName("info-divider") }
                        div {
                            className = ClassName("settings-section-head")
                            +"YOUR LIST"
                        }
                        accessToggle(
                            ic = Ic.Lock,
                            label = "Private on my list",
                            description =
                            "Encrypted, so nobody can see you are in this group. " +
                                "People who follow you stop discovering it.",
                            checked = isListedPrivately,
                            onToggle = { vm.setListedPrivately(!isListedPrivately) },
                        )
                        if (privateSectionOpaque) {
                            div {
                                className = ClassName("info-private-note")
                                +(
                                    "Part of your private list was written by another app and cannot be read here. " +
                                        "It is kept untouched."
                                    )
                            }
                        }
                        div { className = ClassName("info-divider") }
                        if (confirmLeave) {
                            div {
                                className = ClassName("info-leave-confirm")
                                div {
                                    className = ClassName("info-leave-text")
                                    +"Leave "
                                    b { +groupName }
                                    +"? "
                                    +(
                                        if (!group.isOpen) {
                                            "To come back you will need approval or an invite."
                                        } else {
                                            "You can rejoin whenever you want."
                                        }
                                        )
                                }
                                div {
                                    className = ClassName("info-leave-actions")
                                    button {
                                        className = ClassName("btn-danger")
                                        onClick = { props.onLeave() }
                                        +"Confirm leave"
                                    }
                                    button {
                                        className = ClassName("btn-ghost")
                                        onClick = { setConfirmLeave(false) }
                                        +"Cancel"
                                    }
                                }
                            }
                        } else {
                            button {
                                className = ClassName("info-leave-row")
                                onClick = { setConfirmLeave(true) }
                                icon(Ic.Logout)
                                +"Leave group"
                            }
                        }
                    }
                }
            }
        }

        // A mention tapped in the description opens that profile over this modal.
        profilePubkey?.let { pk ->
            UserProfileModal {
                pubkey = pk
                onClose = { setProfilePubkey(null) }
            }
        }
    }

private fun ChildrenBuilder.infoRadio(label: String, description: String, selected: Boolean, onSelect: () -> Unit) {
    button {
        className = ClassName(if (selected) "info-radio on" else "info-radio")
        onClick = { onSelect() }
        span {
            className = ClassName(if (selected) "info-radio-circle on" else "info-radio-circle")
            if (selected) span { className = ClassName("info-radio-dot") }
        }
        span {
            className = ClassName("info-radio-text")
            span {
                className = ClassName("info-radio-label")
                +label
            }
            span {
                className = ClassName("info-radio-desc")
                +description
            }
        }
    }
}
