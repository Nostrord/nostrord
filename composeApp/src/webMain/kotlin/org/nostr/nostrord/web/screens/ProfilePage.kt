package org.nostr.nostrord.web.screens

import js.objects.unsafeJso
import org.nostr.nostrord.auth.ActiveAccountManager
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.Nip57
import org.nostr.nostrord.ui.isValidNip05
import org.nostr.nostrord.ui.navigation.DmRoute
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.navigation.RelayRoute
import org.nostr.nostrord.ui.navigation.UserRoute
import org.nostr.nostrord.ui.screens.profile.ProfilePageViewModel
import org.nostr.nostrord.ui.theme.AvatarGradients
import org.nostr.nostrord.utils.normalizeRelayUrl
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.IdentifierField
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.WebZapController
import org.nostr.nostrord.web.components.aboutMentionPubkeys
import org.nostr.nostrord.web.components.followToggleButton
import org.nostr.nostrord.web.components.groupTypeBadges
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.renderAboutText
import org.nostr.nostrord.web.navigation.pushRoute
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useEffect
import web.cssom.Background
import web.cssom.ClassName

external interface ProfilePageProps : Props {
    var pubkey: String
    var onOpenGroup: (GroupRoute) -> Unit
    var onEditProfile: () -> Unit
    var onOpenDrawer: () -> Unit
}

/**
 * New-design user profile page (prototype Profile, /u/:pubkey): header, identity
 * card (gradient banner matching the user's avatar identity, avatar, name + ADMIN
 * badge, NIP-05, about, npub with copy) and the groups this user is in. Follow /
 * DM actions arrive with the follow and DM features. Mirrors the Compose
 * ui/screens/profile/ProfilePageScreen.
 */
val ProfilePage =
    FC<ProfilePageProps> { props ->
        val vm = useViewModel("profile-${props.pubkey}") { ProfilePageViewModel(AppModule.nostrRepository, props.pubkey) }
        val metadata = useStateFlow(vm.metadata)
        val groups = useStateFlow(vm.userGroups)
        val isFollowing = useStateFlow(vm.isFollowing)
        val isMuted = useStateFlow(vm.isMuted)
        val dmEnabled = useStateFlow(AppModule.dmSettings.dmEnabled)
        val isFollowBusy = useStateFlow(vm.isFollowBusy)
        val allMeta = useStateFlow(AppModule.nostrRepository.userMetadata)
        val relayMetadata = useStateFlow(AppModule.nostrRepository.relayMetadata)
        // Collected unconditionally (hooks must not run inside the isSelf branch below, or the hook
        // count changes when navigating between your own and another user's profile).
        val activeSession = useStateFlow(ActiveAccountManager.session)
        // Resolve @names for any npub/nprofile mentioned in the bio so mentions
        // render as display names, not raw npubs.
        useEffect(metadata?.about) {
            val pks = aboutMentionPubkeys(metadata?.about ?: "")
            if (pks.isNotEmpty()) launchApp { AppModule.nostrRepository.requestUserMetadata(pks) }
        }

        val name =
            metadata?.displayName?.takeIf { it.isNotBlank() }
                ?: metadata?.name?.takeIf { it.isNotBlank() }
                ?: vm.npub.take(12) + "..."

        div {
            className = ClassName("profile-page")
            div {
                className = ClassName("page-header")
                button {
                    className = ClassName("icon-btn frame-menu-btn")
                    onClick = { props.onOpenDrawer() }
                    icon(Ic.Menu)
                }
                icon(Ic.Person)
                span {
                    className = ClassName("page-header-title")
                    +"Profile"
                }
            }

            div {
                className = ClassName("profile-page-scroll")
                div {
                    className = ClassName("profile-page-content")
                    div {
                        className = ClassName("profile-page-card")
                        div {
                            className = ClassName("profile-page-banner")
                            style = unsafeJso { background = profileBannerCss(props.pubkey).unsafeCast<Background>() }
                            // Real banner over the seeded gradient: covers it once loaded, falls
                            // back to the gradient if missing or it fails.
                            metadata?.banner?.takeIf { it.isNotBlank() }?.let { bannerUrl ->
                                img {
                                    className = ClassName("profile-page-banner-img")
                                    src = bannerUrl
                                    alt = ""
                                    onError = { e -> e.currentTarget.asDynamic().style.display = "none" }
                                }
                            }
                        }
                        div {
                            className = ClassName("profile-page-card-body")
                            div {
                                className = ClassName("profile-page-avatar-row")
                                div {
                                    className = ClassName("profile-page-avatar-ring")
                                    WebAvatar {
                                        url = metadata?.picture
                                        seed = props.pubkey
                                        this.name = name
                                        cls = "profile-page-avatar"
                                    }
                                }
                                if (vm.isSelf) {
                                    button {
                                        className = ClassName("btn-secondary")
                                        onClick = { props.onEditProfile() }
                                        +"Edit profile"
                                    }
                                } else {
                                    div {
                                        className = ClassName("profile-page-actions")
                                        if (dmEnabled) {
                                            button {
                                                className = ClassName("btn-secondary profile-btn")
                                                onClick = { pushRoute(DmRoute(props.pubkey)) }
                                                icon(Ic.Mail)
                                                +"Message"
                                            }
                                        }
                                        followToggleButton(isFollowing, isFollowBusy) { vm.toggleFollow() }
                                    }
                                }
                            }

                            div {
                                className = ClassName("profile-page-name-row")
                                h1 {
                                    className = ClassName("profile-page-name")
                                    +name
                                }
                            }
                            metadata?.nip05?.takeIf { isValidNip05(it) }?.let {
                                div {
                                    className = ClassName("profile-page-nip05")
                                    +it
                                }
                            }
                            metadata?.about?.takeIf { it.isNotBlank() }?.let { about ->
                                p {
                                    className = ClassName("profile-page-about")
                                    renderAboutText(about, allMeta) { pushRoute(UserRoute(it)) }
                                }
                            }
                            // Cycling identifier (prototype IdentifierField): npub /
                            // nprofile / link / hex / nip-05 with swap + copy.
                            IdentifierField {
                                pubkey = props.pubkey
                                nip05 = metadata?.nip05
                            }

                            if (!vm.isSelf) {
                                // Zaps require a signer + a lightning address on the profile.
                                val canSign = activeSession != null
                                val canZap = canSign && Nip57.resolvePayEndpoint(metadata?.lud16, metadata?.lud06) != null
                                div {
                                    className = ClassName("profile-page-actions secondary")
                                    button {
                                        className = ClassName("btn-secondary profile-btn sm")
                                        disabled = !canZap
                                        if (!canZap) title = "No lightning address"
                                        onClick = { WebZapController.request(props.pubkey) }
                                        icon(Ic.Bolt)
                                        +"Zap"
                                    }
                                    // NIP-56 reports aren't wired yet.
                                    button {
                                        className = ClassName("btn-ghost profile-btn sm")
                                        onClick = { vm.toggleMute() }
                                        icon(Ic.NotificationsOff)
                                        +(if (isMuted) "Unmute" else "Mute")
                                    }
                                    button {
                                        className = ClassName("btn-ghost profile-btn sm")
                                        disabled = true
                                        title = "Coming soon"
                                        icon(Ic.Shield)
                                        +"Report"
                                    }
                                }
                            }
                        }
                    }

                    div {
                        className = ClassName("profile-groups-label")
                        +((if (vm.isSelf) "Your groups" else "$name's groups") + " · ${groups.size}")
                    }
                    if (groups.isEmpty()) {
                        div {
                            className = ClassName("profile-groups-empty")
                            +"No groups to show."
                        }
                    } else {
                        groups.forEach { group ->
                            val groupName = group.meta.name ?: group.meta.id
                            button {
                                key = "${group.relayUrl}/${group.meta.id}"
                                className = ClassName("profile-group-row")
                                onClick = { props.onOpenGroup(GroupRoute(group.relayUrl, group.meta.id)) }
                                WebAvatar {
                                    url = group.meta.picture
                                    seed = group.meta.id
                                    this.name = groupName
                                    kind = org.nostr.nostrord.web.components.AvatarKind.GROUP
                                    cls = "profile-group-avatar"
                                }
                                span {
                                    className = ClassName("profile-group-meta")
                                    // Top line: name + the admin chip (the viewer's role)
                                    // hug the left as a unit; the group's access tags are
                                    // pushed to the right edge.
                                    span {
                                        className = ClassName("profile-group-head")
                                        span {
                                            className = ClassName("profile-group-name")
                                            +groupName
                                        }
                                        if (group.isAdmin) {
                                            span {
                                                className = ClassName("admin-chip")
                                                +"admin"
                                            }
                                        }
                                        span {
                                            className = ClassName("profile-group-tags")
                                            groupTypeBadges(group.meta)
                                        }
                                    }
                                    // Sub line: member count and the host relay on one muted
                                    // row, separated by a dot. Relay is tappable to its page.
                                    val relayHost = group.relayUrl.removePrefix("wss://").removePrefix("ws://").trimEnd('/')
                                    if (group.memberCount > 0 || relayHost.isNotBlank()) {
                                        span {
                                            className = ClassName("profile-group-sub")
                                            if (group.memberCount > 0) {
                                                span {
                                                    className = ClassName("profile-group-members")
                                                    +"${group.memberCount} members"
                                                }
                                            }
                                            if (group.memberCount > 0 && relayHost.isNotBlank()) {
                                                span {
                                                    className = ClassName("profile-group-dot")
                                                    +"·"
                                                }
                                            }
                                            if (relayHost.isNotBlank()) {
                                                span {
                                                    className = ClassName("profile-group-relay")
                                                    onClick = {
                                                        it.stopPropagation()
                                                        pushRoute(RelayRoute(group.relayUrl))
                                                    }
                                                    WebAvatar {
                                                        url = relayMetadata[group.relayUrl]?.icon
                                                            ?: relayMetadata[group.relayUrl.normalizeRelayUrl()]?.icon
                                                        seed = group.relayUrl
                                                        this.name = relayHost
                                                        kind = org.nostr.nostrord.web.components.AvatarKind.RELAY
                                                        cls = "profile-group-relay-icon"
                                                    }
                                                    span {
                                                        className = ClassName("profile-group-relay-host")
                                                        +relayHost
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

/** Banner from the user's avatar identity hue, darkened (prototype: u.color into dark). */
private fun profileBannerCss(seed: String): String {
    val g = AvatarGradients.user(seed)
    return "linear-gradient(120deg, hsl(${g.start.hue} 62% 40%), var(--color-background-dark))"
}
