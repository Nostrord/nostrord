package org.nostr.nostrord.web.screens

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.screens.home.DiscoverGroup
import org.nostr.nostrord.ui.screens.home.HomePageViewModel
import org.nostr.nostrord.ui.screens.onboarding.onboardingFollowSuggestions
import org.nostr.nostrord.utils.isJoinedOn
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.FollowAllButton
import org.nostr.nostrord.web.components.FollowSuggestionCard
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.formError
import org.nostr.nostrord.web.components.formHint
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.iconInput
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useState
import web.cssom.ClassName
import web.html.InputType
import web.html.text

private val STEPS = listOf("Welcome", "Who to follow", "Groups")

external interface OnboardingFlowProps : Props {
    /** "Skip for now" / "Done" without joining: session override, lands on Home. */
    var onSkip: () -> Unit

    /** Join by invite/naddr/address; success flips the kind:10009 gate to Home upstream. */
    var onJoin: (String, (Result<Unit>) -> Unit) -> Unit

    /** Join a discovered group (relayUrl, groupId) while staying in the wizard. */
    var onJoinGroup: (String, String) -> Unit
}

/**
 * Full-page onboarding wizard (prototype Onboarding, without the follow-pack /
 * avatar collection steps): progress bars + step label, Welcome and Groups steps,
 * and the footer with Back / Skip and the primary action. Mirrors the Compose
 * OnboardingFlowScreen; gating lives in AppViewModel.needsOnboarding.
 */
val OnboardingFlow =
    FC<OnboardingFlowProps> { props ->
        // Re-opened from the sidebar's "Follow people" action: jump straight to the
        // Who-to-follow step rather than the Welcome step.
        val (step, setStep) = useState { if (AppModule.onboardingRequested.value) 1 else 0 }
        val (joinInput, setJoinInput) = useState { "" }
        val (joining, setJoining) = useState { false }
        val (error, setError) = useState<String?> { null }

        fun joinRef(ref: String) {
            if (ref.isBlank() || joining) return
            setJoining(true)
            setError(null)
            props.onJoin(ref) { result ->
                setJoining(false)
                // Success flips the onboarding gate upstream and lands on Home.
                setError(result.exceptionOrNull()?.message)
            }
        }

        fun join() = joinRef(joinInput.trim())

        div {
            className = ClassName("onb-page")
            div {
                className = ClassName("onb-steps")
                STEPS.forEachIndexed { index, _ ->
                    span {
                        key = index.toString()
                        className = ClassName(if (index <= step) "onb-step-bar active" else "onb-step-bar")
                    }
                }
            }
            div {
                className = ClassName("onb-step-label")
                +"Step ${step + 1} of ${STEPS.size} · ${STEPS[step]}"
            }

            div {
                className = ClassName("onb-body")
                when (step) {
                    0 -> welcomeStep()
                    1 -> WhoToFollowStep {}
                    else ->
                        GroupsStep {
                            this.joinInput = joinInput
                            this.joining = joining
                            this.error = error
                            onJoinInputChange = { setJoinInput(it) }
                            onErrorChange = { setError(it) }
                            join = ::join
                            onJoinGroup = props.onJoinGroup
                        }
                }
            }

            div {
                className = ClassName("onb-footer")
                div {
                    className = ClassName("onb-footer-inner")
                    if (step > 0) {
                        button {
                            className = ClassName("btn-text")
                            onClick = { setStep(step - 1) }
                            +"Back"
                        }
                    } else {
                        button {
                            className = ClassName("btn-text")
                            onClick = { props.onSkip() }
                            +"Skip for now"
                        }
                    }
                    button {
                        className = ClassName("btn-primary btn-lg")
                        onClick = { if (step < STEPS.size - 1) setStep(step + 1) else props.onSkip() }
                        +(if (step < STEPS.size - 1) "Continue" else "Done")
                    }
                }
            }
        }
    }

private fun ChildrenBuilder.welcomeStep() {
    div {
        className = ClassName("onb-hero")
        img {
            className = ClassName("onb-hero-logo")
            src = "icon-192.png"
            alt = "Nostrord"
        }
        h1 {
            className = ClassName("onb-title")
            +"Welcome to Nostrord"
        }
        p {
            className = ClassName("onb-sub")
            +"Your account is ready. We'll connect you with people, and they'll lead you to the right groups."
        }
        div {
            className = ClassName("onb-hints")
            hintRow(Ic.People, "Follow some people", "Ready-made packs to start you off with a network")
            hintRow(Ic.Forum, "See their groups", "We suggest the groups where the people you follow are")
            hintRow(Ic.Link, "Or join by invite", "Paste a group link to jump straight in")
        }
    }
}

private fun ChildrenBuilder.hintRow(
    ic: Ic,
    title: String,
    description: String,
) {
    div {
        className = ClassName("hint-row")
        icon(ic)
        div {
            div {
                className = ClassName("hint-row-title")
                +title
            }
            div {
                className = ClassName("hint-row-desc")
                +description
            }
        }
    }
}

private val WhoToFollowStep =
    FC<Props> {
        val repo = AppModule.nostrRepository
        val following = useStateFlow(repo.following)
        val metadata = useStateFlow(repo.userMetadata)

        useEffect(Unit) {
            // Pull the existing kind:3 so already-followed people show as "Following", and so
            // the first follow tap skips the contact-list publish's initial fetch wait.
            launchApp { repo.requestContactList() }
            val pubkeys = onboardingFollowSuggestions.map { it.pubkey }.filter { it.isNotBlank() }.toSet()
            if (pubkeys.isNotEmpty()) launchApp { repo.requestUserMetadata(pubkeys) }
        }

        h2 {
            className = ClassName("onb-section-title")
            +"Who to follow"
        }
        p {
            className = ClassName("onb-section-sub")
            +"Pick a few people to follow: that's what unlocks group discovery."
        }
        div {
            className = ClassName("follow-list-head")
            FollowAllButton {
                people = onboardingFollowSuggestions
                this.following = following
            }
        }
        div {
            className = ClassName("onb-pack-list")
            onboardingFollowSuggestions.forEach { person ->
                FollowSuggestionCard {
                    key = person.npub
                    this.person = person
                    pictureUrl = metadata[person.pubkey]?.picture
                    isFollowing = person.pubkey in following
                }
            }
        }
        p {
            className = ClassName("onb-skip-note")
            +"Follow at least one person to see group suggestions in the next step."
        }
    }

external interface GroupsStepProps : Props {
    var joinInput: String
    var joining: Boolean
    var error: String?
    var onJoinInputChange: (String) -> Unit
    var onErrorChange: (String?) -> Unit
    var join: () -> Unit

    /** Join a discovered group directly (relayUrl, groupId). */
    var onJoinGroup: (String, String) -> Unit
}

private val GroupsStep =
    FC<GroupsStepProps> { props ->
        // Reuse the Home discovery: groups the people you follow are in, via their
        // kind:10009 lists and member lists. The relay link is disabled here since the
        // onboarding has no relay route to open yet.
        val vm = useViewModel { HomePageViewModel(AppModule.nostrRepository) }
        val friendsGroups = useStateFlow(vm.friendsGroups)
        val friendsGroupsResolving = useStateFlow(vm.friendsGroupsResolving)
        val relayMeta = useStateFlow(vm.relayMetadata)
        val following = useStateFlow(AppModule.nostrRepository.following)
        // Per (relay, id): a same-id group joined on another relay is not this one.
        val joinedByRelay = useStateFlow(AppModule.nostrRepository.joinedGroupsByRelay)

        // Re-fire as the contact list resolves: a fresh login often reaches this step before
        // kind:3 arrives, so the first call requests nothing; keying on [following] picks up the
        // follows the moment they land (the VM dedups already-requested pubkeys).
        useEffect(following) { vm.loadFriendsGroups() }

        // Accumulate discovered public groups so a joined one stays on the list (marked
        // "Joined") instead of vanishing: joining removes it from [friendsGroups] (which
        // only lists groups you are NOT in), so we keep a sticky union here.
        val (discovered, setDiscovered) = useState<List<DiscoverGroup>> { emptyList() }
        useEffect(friendsGroups) {
            setDiscovered { prev ->
                // Key by group id (stable identity, as the Home list does): the same group
                // can re-arrive with a different relayUrl representation, which would
                // otherwise split into two cards.
                val byKey = LinkedHashMap<String, DiscoverGroup>()
                prev.forEach { byKey[it.meta.id] = it }
                friendsGroups.filter { it.meta.isPublic }.forEach { byKey[it.meta.id] = it }
                byKey.values.toList()
            }
        }

        h2 {
            className = ClassName("onb-section-title")
            +"Find your group"
        }
        p {
            className = ClassName("onb-section-sub")
            +"Have an invite? Paste it below. Or join a public group the people you follow are in."
        }

        // Join-by-link stays pinned above the discovery list so an invite (a deterministic
        // action) is always reachable without scrolling past a long list of suggestions.
        div {
            className = ClassName("join-card")
            div {
                className = ClassName("join-card-title")
                icon(Ic.Link)
                +"Join by link, naddr or group ID"
            }
            formError(props.error)
            div {
                className = ClassName("join-row")
                iconInput(
                    ic = Ic.Link,
                    type = InputType.text,
                    placeholder = "naddr1... or wss://relay'groupId",
                    value = props.joinInput,
                    onChange = {
                        props.onJoinInputChange(it)
                        props.onErrorChange(null)
                    },
                    onEnter = { props.join() },
                )
                button {
                    className = ClassName("btn-secondary btn-lg")
                    disabled = props.joinInput.isBlank() || props.joining
                    onClick = { props.join() }
                    if (props.joining) {
                        span { className = ClassName("btn-spinner") }
                    }
                    +(if (props.joining) "Joining…" else "Join")
                }
            }
            formHint("Accepts an invite link, a NIP-19 naddr address, or relay'groupId.")
        }

        when {
            discovered.isEmpty() && friendsGroupsResolving ->
                div {
                    className = ClassName("onb-pack-list onb-groups")
                    repeat(3) { groupCardSkeleton() }
                }
            discovered.isEmpty() ->
                // Discovery finished with nothing from your follows: say so, so the section
                // never silently collapses to blank after the skeleton (the reported confusion).
                p {
                    className = ClassName("onb-skip-note")
                    +"No public groups from people you follow yet. Paste an invite above, or skip."
                }
            discovered.isNotEmpty() ->
                div {
                    className = ClassName("onb-pack-list onb-groups")
                    discovered.forEach { fg ->
                        discoverGroupCard(
                            fg,
                            relayMeta[fg.relayUrl]?.icon,
                            joinedByRelay.isJoinedOn(fg.relayUrl, fg.meta.id),
                            enableRelayLink = false,
                            showJoinCta = true,
                            interactive = false,
                            showRelay = false,
                        ) {
                            props.onJoinGroup(fg.relayUrl, fg.meta.id)
                        }
                    }
                }
        }

        p {
            className = ClassName("onb-skip-note")
            +"No invite yet? Skip for now and create your own group from Home."
        }
    }
