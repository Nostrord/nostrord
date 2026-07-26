package org.nostr.nostrord.web.modals

import org.nostr.nostrord.auth.ActiveAccountManager
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.nostr.Nip57
import org.nostr.nostrord.ui.isValidNip05
import org.nostr.nostrord.ui.navigation.DmRoute
import org.nostr.nostrord.ui.navigation.UserRoute
import org.nostr.nostrord.ui.screens.group.GroupViewModel
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.IdentifierField
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.WebZapController
import org.nostr.nostrord.web.components.followToggleButton
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.useEscClose
import org.nostr.nostrord.web.navigation.pushRoute
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.b
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useState
import web.cssom.ClassName

external interface UserProfileModalProps : Props {
    var pubkey: String
    var onClose: () -> Unit

    /** Group-scoped extras: set when opened from a chat so the admin section can render. */
    var groupId: String?
    var iAmAdmin: Boolean
    var targetIsAdmin: Boolean

    /** Inserts an @mention of this user into the chat composer (enabled in-chat only). */
    var onMention: ((String) -> Unit)?
}

/**
 * Quick user profile modal (prototype UserProfileCard): clickable header row to the
 * full profile page, ABOUT, the cycling IdentifierField, Follow + Message buttons,
 * View profile, and the action list (zap / mention / mute / report, plus the admin
 * rows when opened from a group by an admin). Actions whose backends don't exist
 * yet render disabled with a "Coming soon" tooltip, like the chat context menu.
 */
val UserProfileModal =
    FC<UserProfileModalProps> { props ->
        // Track the viewed pubkey internally so a mention tapped in the bio can
        // swap this modal to that profile in place (reset when the prop changes).
        val (pubkey, setPubkey) = useState { props.pubkey }
        useEffect(props.pubkey) { setPubkey(props.pubkey) }
        val allMeta = useStateFlow(AppModule.nostrRepository.userMetadata)
        val meta = allMeta[pubkey]
        val npub = Nip19.encodeNpub(pubkey)
        val name =
            meta?.displayName?.takeIf { it.isNotBlank() }
                ?: meta?.name?.takeIf { it.isNotBlank() }
                ?: (npub.take(12) + "…")
        val isSelf = AppModule.nostrRepository.getPublicKey() == pubkey
        // Zaps require signing a kind:9734 request, so only offer them when an account
        // with a usable signer is active AND the profile has a lightning address.
        val canSign = useStateFlow(ActiveAccountManager.session) != null
        val canZap = canSign && Nip57.resolvePayEndpoint(meta?.lud16, meta?.lud06) != null
        val following = useStateFlow(AppModule.nostrRepository.following)
        val isFollowing = pubkey in following
        val mutedPubkeys = useStateFlow(AppModule.nostrRepository.mutedPubkeys)
        val isMuted = pubkey in mutedPubkeys
        val dmEnabled = useStateFlow(AppModule.dmSettings.dmEnabled)
        val (followBusy, setFollowBusy) = useState { false }
        val (showReport, setShowReport) = useState { false }

        useEffect(pubkey) {
            launchApp { AppModule.nostrRepository.requestUserMetadata(setOf(pubkey)) }
            launchApp { AppModule.nostrRepository.requestContactList() }
        }
        useEscClose { props.onClose() }

        val openFull = {
            props.onClose()
            pushRoute(UserRoute(pubkey))
        }

        div {
            className = ClassName("modal-overlay")
            onClick = { props.onClose() }
            div {
                className = ClassName("modal-card profile-card")
                onClick = { it.stopPropagation() }

                div {
                    className = ClassName("modal-header")
                    div {
                        className = ClassName("modal-title")
                        +"Profile"
                    }
                    button {
                        className = ClassName("modal-close")
                        onClick = { props.onClose() }
                        icon(Ic.Close)
                    }
                }

                div {
                    className = ClassName("profile-content")

                    // Header row: avatar + name (+ ADMIN badge) + nip-05, opens the full page.
                    button {
                        className = ClassName("profile-head")
                        onClick = { openFull() }
                        WebAvatar {
                            url = meta?.picture
                            seed = pubkey
                            this.name = name
                            cls = "profile-head-avatar"
                        }
                        div {
                            className = ClassName("profile-head-meta")
                            div {
                                className = ClassName("profile-head-name-row")
                                span {
                                    className = ClassName("profile-head-name")
                                    +name
                                }
                                if (props.targetIsAdmin) {
                                    span {
                                        className = ClassName("profile-admin-badge")
                                        +"ADMIN"
                                    }
                                }
                            }
                            val nip05 = meta?.nip05
                            if (nip05 != null && isValidNip05(nip05)) {
                                div {
                                    className = ClassName("profile-nip05")
                                    +nip05
                                }
                            }
                        }
                        span {
                            className = ClassName("profile-head-chevron")
                            icon(Ic.ChevronRight)
                        }
                    }

                    // The bio lives on the full profile page (#/u/), not in the quick card.

                    // Cycling identifier (prototype IdentifierField): npub / nprofile /
                    // link / hex / nip-05 with swap + copy.
                    div {
                        className = ClassName("profile-identifier")
                        IdentifierField {
                            this.pubkey = pubkey
                            nip05 = meta?.nip05
                        }
                    }

                    if (!isSelf) {
                        div {
                            className = ClassName("profile-btn-row")
                            followToggleButton(isFollowing, followBusy) {
                                setFollowBusy(true)
                                launchApp {
                                    if (isFollowing) {
                                        AppModule.nostrRepository.unfollowUser(pubkey)
                                    } else {
                                        AppModule.nostrRepository.followUser(pubkey)
                                    }
                                    setFollowBusy(false)
                                }
                            }
                            if (dmEnabled) {
                                button {
                                    className = ClassName("btn-secondary profile-btn")
                                    onClick = {
                                        props.onClose()
                                        pushRoute(DmRoute(pubkey))
                                    }
                                    icon(Ic.Mail)
                                    +"Message"
                                }
                            }
                        }
                    }

                    // Prototype UserProfileCard: jump to the full profile page (#/u/npub).
                    button {
                        className = ClassName("btn-secondary profile-view-btn")
                        onClick = { openFull() }
                        +"View profile"
                    }

                    if (!isSelf) {
                        div {
                            className = ClassName("profile-actions")
                            actionRow(Ic.Bolt, "Send zap", disabled = !canZap, disabledTitle = "No lightning address") {
                                WebZapController.request(pubkey)
                                props.onClose()
                            }
                            // Mention only exists inside a group chat (where a composer can
                            // receive it); elsewhere the row is absent rather than shown
                            // disabled. Report joins when its backend lands (NIP-56 reports).
                            props.onMention?.let { onMention ->
                                actionRow(Ic.Reply, "Mention") {
                                    onMention(pubkey)
                                }
                            }
                            actionRow(null, if (isMuted) "Unmute user" else "Mute user", emoji = "🔕") {
                                launchApp {
                                    if (isMuted) {
                                        AppModule.nostrRepository.unmuteUser(pubkey)
                                    } else {
                                        AppModule.nostrRepository.muteUser(pubkey)
                                    }
                                }
                            }
                            actionRow(Ic.Shield, "Report user") {
                                setShowReport(true)
                            }

                            val groupId = props.groupId
                            if (props.iAmAdmin && groupId != null) {
                                GroupModerationSection {
                                    this.groupId = groupId
                                    this.pubkey = pubkey
                                    this.displayName = name
                                    this.targetIsAdmin = props.targetIsAdmin
                                    this.onClose = props.onClose
                                }
                            }
                        }
                    }

                    if (isSelf) {
                        p {
                            className = ClassName("profile-self-note")
                            +"This is you. Edit your profile in Settings."
                        }
                    }
                }
            }
        }

        if (showReport) {
            ReportUserModal {
                this.pubkey = pubkey
                eventId = null
                onClose = { setShowReport(false) }
            }
        }
    }

private external interface GroupModerationSectionProps : Props {
    var groupId: String
    var pubkey: String
    var displayName: String
    var targetIsAdmin: Boolean
    var onClose: () -> Unit
}

/**
 * Admin-only moderation rows (role change + removal), routed through the shared
 * [GroupViewModel] like the manage modal: relay rejections surface readable (the
 * "blocked:" prefix stripped), a stale timeout error is invalidated by the
 * kind:9000/9001 echo, and success posts the confirmation snackbar (#174).
 */
private val GroupModerationSection =
    FC<GroupModerationSectionProps> { props ->
        val vm = useViewModel(props.groupId) { GroupViewModel(AppModule.nostrRepository, props.groupId) }
        val moderationError = useStateFlow(vm.moderationError)
        val busy = useStateFlow(vm.moderationBusy)
        val (confirmRemove, setConfirmRemove) = useState { false }
        val (confirmRole, setConfirmRole) = useState { false }

        // The modal can swap to another profile in place (mention tap); drop any half-open
        // confirmation aimed at the previous user.
        useEffect(props.pubkey) {
            setConfirmRemove(false)
            setConfirmRole(false)
            vm.clearModerationError()
        }

        div { className = ClassName("info-divider profile-actions-divider") }
        if (confirmRole) {
            div {
                className = ClassName("info-leave-confirm")
                div {
                    className = ClassName("info-leave-text")
                    b { +props.displayName }
                    +(
                        if (props.targetIsAdmin) {
                            " will lose admin privileges."
                        } else {
                            " will be able to manage members and group settings."
                        }
                        )
                }
                moderationError?.let { err ->
                    div {
                        className = ClassName("modal-error")
                        +err
                    }
                }
                div {
                    className = ClassName("info-leave-actions")
                    button {
                        className = ClassName("btn-primary")
                        disabled = busy
                        onClick = {
                            if (props.targetIsAdmin) {
                                vm.demoteFromAdmin(props.pubkey) { props.onClose() }
                            } else {
                                vm.promoteToAdmin(props.pubkey) { props.onClose() }
                            }
                        }
                        +(
                            when {
                                busy && props.targetIsAdmin -> "Demoting…"
                                busy -> "Promoting…"
                                props.targetIsAdmin -> "Demote"
                                else -> "Promote"
                            }
                            )
                    }
                    button {
                        className = ClassName("btn-ghost")
                        onClick = { setConfirmRole(false) }
                        +"Cancel"
                    }
                }
            }
        } else {
            actionRow(
                Ic.Shield,
                if (props.targetIsAdmin) "Demote from admin" else "Promote to admin",
            ) {
                vm.clearModerationError()
                setConfirmRemove(false)
                setConfirmRole(true)
            }
        }
        if (confirmRemove) {
            div {
                className = ClassName("info-leave-confirm")
                div {
                    className = ClassName("info-leave-text")
                    +"Remove "
                    b { +props.displayName }
                    +" from the group? They lose access; you can invite them again later."
                }
                moderationError?.let { err ->
                    div {
                        className = ClassName("modal-error")
                        +err
                    }
                }
                div {
                    className = ClassName("info-leave-actions")
                    button {
                        className = ClassName("btn-danger")
                        disabled = busy
                        onClick = { vm.removeUser(props.pubkey) { props.onClose() } }
                        +(if (busy) "Removing…" else "Remove")
                    }
                    button {
                        className = ClassName("btn-ghost")
                        onClick = { setConfirmRemove(false) }
                        +"Cancel"
                    }
                }
            }
        } else {
            actionRow(Ic.Delete, "Remove from group", danger = true) {
                vm.clearModerationError()
                setConfirmRole(false)
                setConfirmRemove(true)
            }
        }
    }

private fun ChildrenBuilder.actionRow(
    ic: Ic?,
    label: String,
    danger: Boolean = false,
    disabled: Boolean = false,
    disabledTitle: String = "Coming soon",
    emoji: String? = null,
    onSelect: () -> Unit,
) {
    button {
        className =
            ClassName(
                when {
                    disabled -> "profile-action-row disabled"
                    danger -> "profile-action-row danger"
                    else -> "profile-action-row"
                },
            )
        if (disabled) title = disabledTitle
        onClick = { if (!disabled) onSelect() }
        when {
            ic != null -> icon(ic)
            emoji != null ->
                span {
                    className = ClassName("profile-action-emoji")
                    +emoji
                }
        }
        +label
    }
}
