package org.nostr.nostrord.web

import kotlinx.browser.window
import org.nostr.nostrord.AppViewModel
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.notifications.installPlatformFocusListeners
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.theme.paletteForTheme
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.AppLoading
import org.nostr.nostrord.web.components.SystemMessageHost
import org.nostr.nostrord.web.components.installGlobalModalFocusTrap
import org.nostr.nostrord.web.components.installPullToRefresh
import org.nostr.nostrord.web.modals.UnlockModal
import org.nostr.nostrord.web.navigation.WebRoute
import org.nostr.nostrord.web.navigation.applyWebRoute
import org.nostr.nostrord.web.navigation.currentHashRoute
import org.nostr.nostrord.web.screens.LoginScreen
import org.nostr.nostrord.web.screens.OnboardingFlow
import org.nostr.nostrord.web.theme.applyColorTokens
import org.nostr.nostrord.web.theme.systemPrefersDark
import react.FC
import react.Props
import react.useEffect
import react.useEffectOnce
import react.useState
import web.dom.ElementId
import web.dom.document

/**
 * Root React component. On mount it runs the real cold-start sequence — same as the Compose
 * AppViewModel: `nostrRepository.initialize()` (restores any persisted session and starts
 * relay/group discovery), with a 30s `forceInitialized()` fallback.
 *
 * The HTML `#loading-shell` (index.html) stays visible until `data-app-ready` is set on
 * `#composeApplication`. We set that only AFTER `repo.isInitialized` flips true — that way
 * the shell bridges the whole cold-start (HTML parse → bundle load → repo.initialize) and
 * hands off directly to Login / AppShell, instead of the canvas-era pattern of two spinners
 * in a row (HTML shell removed on React mount, then a separate `.app-loading` rendered while
 * the repo finished initializing).
 */
val WebApp =
    FC<Props> {
        val repo = AppModule.nostrRepository
        // Same screen logic as the Compose AppViewModel: its init{} runs initialize() with
        // the 30s forceInitialized() fallback, and it exposes the gate flows.
        val vm = useViewModel { AppViewModel(AppModule.nostrRepository) }
        val initialized = useStateFlow(vm.isInitialized)
        val loggedIn = useStateFlow(vm.isLoggedIn)
        val verifyingBunker = useStateFlow(vm.isBunkerVerifying)

        // Theme: re-inject the CSS custom properties whenever the preference or the OS
        // theme changes (the latter resolves AppTheme.SYSTEM live). Mirrors native App.kt's
        // NostrordColors.apply(...). The state change also re-renders the tree, so inline
        // WebColors usages follow.
        val appTheme = useStateFlow(AppModule.appearanceSettings.theme)
        val (systemDark, setSystemDark) = useState { systemPrefersDark() }
        useEffectOnce {
            // The root component never unmounts; the listener lives for the page.
            window.matchMedia("(prefers-color-scheme: dark)").asDynamic()
                .addEventListener("change") { setSystemDark(systemPrefersDark()) }
        }
        useEffect(appTheme, systemDark) {
            applyColorTokens(paletteForTheme(appTheme, systemDark))
        }

        useEffectOnce {
            // Track tab focus so notifications/unread are suppressed while the app is visible
            // (mirrors native App.kt; without this the focus-gated dispatch never updates).
            installPlatformFocusListeners(AppModule.focusTracker)
            // Watch the DOM for .modal-card add/remove and trap keyboard focus inside the open
            // modal so Tab / Shift+Tab cycle through its controls instead of leaking back to the
            // page behind the backdrop. One install covers every current and future modal.
            installGlobalModalFocusTrap()
            // Mobile pull-to-refresh: the document is scroll-locked so the browser's own
            // gesture never fires; install it manually (swipe down at the top to reload).
            installPullToRefresh()
            // Drive the repository lifecycle from page visibility, mirroring native App.kt's
            // ON_PAUSE → onBackground / ON_RESUME → onForeground. The Compose web got this for
            // free via the shared Lifecycle observer; the React shell must wire it explicitly.
            // onBackground() flushes LiveCursorStore to storage — without it every reload
            // resumes group subscriptions from the default window instead of the last-seen
            // cursor, re-fetching history each time (the cache regression vs native/old web).
            document.asDynamic().addEventListener("visibilitychange") {
                if (document.asDynamic().hidden as Boolean) repo.onBackground() else repo.onForeground()
            }
            // initialize() with the 30s fallback is driven by AppViewModel.init{} above.
        }

        // Hand off the HTML loading shell to the React app once initialization
        // completes. Setting this earlier (on mount) would expose the user to a
        // visible second spinner while the repo finishes booting.
        useEffect(initialized) {
            if (initialized) {
                document
                    .getElementById(ElementId("composeApplication"))
                    ?.setAttribute("data-app-ready", "true")
            }
        }

        // Transient notices (AppModule.systemMessages), over every screen including login.
        SystemMessageHost()

        // NIP-49 unlock gate: a password-protected account blocked session restore;
        // ask for the password over whatever screen is showing (mirrors native App.kt).
        val pendingUnlock = useStateFlow(AppModule.nostrRepository.pendingUnlockAccount)
        pendingUnlock?.let { account -> UnlockModal { this.account = account } }

        // New-design flow gate: an account whose kind:10009 lists no groups goes
        // through the onboarding wizard; everyone else lands on Home (a placeholder
        // while the prototype's Home page is ported).
        val needsOnboarding = useStateFlow(vm.needsOnboarding)
        val onboardingSkipped = useStateFlow(vm.onboardingSkipped)
        // [stayInOnboarding] keeps the wizard up after a group join (which flips
        // needsOnboarding) so the user can join several before leaving via Skip/Done.
        val stayInOnboarding = useStateFlow(vm.stayInOnboarding)
        // The sidebar's "Follow people" action re-opens the wizard even for an account
        // with groups or one that already skipped, so it overrides those gates.
        val onboardingRequested = useStateFlow(vm.onboardingRequested)
        // True while we don't yet know whether the active account has groups (its list hasn't
        // resolved): show the loading screen instead of guessing Home vs onboarding.
        val onboardingPending = useStateFlow(vm.onboardingDecisionPending)
        // A group deep-link (#/g/…) is an explicit intent to open that group, so it must win over
        // the AUTOMATIC onboarding wizard: an account whose kind:10009 is empty (brand new, or it
        // just left its only group) that opens a group invite link should land on the group's
        // join/locked view, not on "Welcome to Nostrord". The sidebar's explicit onboardingRequested
        // still overrides. Read at render: the needsOnboarding/onboardingPending StateFlows already
        // re-render the moment the onboarding decision resolves, when the deep-link hash is present.
        val onGroupDeepLink = currentHashRoute() is GroupRoute
        val showingOnboarding =
            loggedIn &&
                (
                    onboardingRequested ||
                        ((needsOnboarding || stayInOnboarding) && !onboardingSkipped && !onGroupDeepLink)
                    )

        // Hash-route mirror: #/login, #/onboarding, Home at the root. A page hash
        // (#/g/…, #/u/…) is AppFrame's territory: entering logged-in with one (deep
        // link / refresh) must not be normalized away to Home.
        val showingLogin = initialized && !loggedIn && !verifyingBunker
        useEffect(showingLogin, showingOnboarding, loggedIn) {
            when {
                // Preserve a deep-link page hash (#/g, #/u, #/dm) across the brief
                // cold-start window where we're initialized but the session hasn't
                // restored yet (initialized=true, loggedIn=false). Clobbering it to
                // #/login here is what made a refresh inside a group land on Home: the
                // hash was gone before AppFrame could read it. Keeping it means AppFrame
                // restores the group the instant loggedIn flips (and a genuine deep link
                // survives the login screen).
                showingLogin -> if (currentHashRoute() == null) applyWebRoute(WebRoute.Login)
                // Same guard as login: `needsOnboarding` can blip true on cold load
                // (relay discovery finishes before the kind:10009 group list arrives),
                // and clobbering #/g/... to #/onboarding here is what bounced a group
                // deep-link / refresh to Home. The onboarding screen still renders from
                // `showingOnboarding` below; we just keep the hash so AppFrame restores
                // the group once it resolves.
                loggedIn && showingOnboarding -> if (currentHashRoute() == null) applyWebRoute(WebRoute.Onboarding)
                loggedIn && currentHashRoute() == null -> applyWebRoute(WebRoute.Home)
            }
        }

        when {
            // Render nothing until initialized; the HTML shell holds the screen
            // and only fades out once data-app-ready is set above.
            !initialized -> null
            loggedIn ->
                when {
                    showingOnboarding ->
                        OnboardingFlow {
                            onSkip = { vm.skipOnboarding() }
                            onJoin = { input, onResult -> vm.joinGroupFromInput(input, onResult) }
                            // Discovered-group join: keep the wizard up so several can be joined.
                            onJoinGroup = { relayUrl, groupId ->
                                vm.keepOnboarding()
                                vm.joinGroupFromInput("$relayUrl'$groupId") {}
                            }
                        }
                    // Still resolving the new account's group list (e.g. just switched): hold the
                    // loading screen, then route to Home or onboarding once it resolves.
                    onboardingPending -> AppLoading()
                    else -> AppFrame()
                }
            // A bunker signer is still being (re)connected on cold start: hold the loading
            // shell instead of flashing LoginScreen during the async handshake. This is the
            // only restore path that completes after isInitialized (local/NIP-07 restore
            // flips isLoggedIn before initialize() returns). isBunkerVerifying flips false
            // when the signer connects (-> loggedIn -> AppShell) or fails (-> LoginScreen),
            // so unlike gating on persisted-account existence it can never strand the user
            // on a permanent spinner after logout or a failed restore.
            verifyingBunker -> null
            else -> LoginScreen()
        }
    }
