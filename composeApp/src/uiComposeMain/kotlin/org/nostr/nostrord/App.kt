package org.nostr.nostrord

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nostr.nostrord.auth.pomegranate.PomegranateAuthHost
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.startup.AppStartState
import org.nostr.nostrord.startup.StartupResolver
import org.nostr.nostrord.ui.components.layout.AppFrame
import org.nostr.nostrord.ui.components.navigation.MinimalTitleBar
import org.nostr.nostrord.ui.navigation.clearBrowserUrlQuery
import org.nostr.nostrord.ui.screens.login.NostrLoginScreen
import org.nostr.nostrord.ui.screens.login.components.UnlockAccountDialog
import org.nostr.nostrord.ui.screens.onboarding.OnboardingFlowScreen
import org.nostr.nostrord.ui.theme.AppFonts
import org.nostr.nostrord.ui.theme.ColorTokens
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.rememberInterFontFamily
import org.nostr.nostrord.ui.window.LocalDesktopWindowControls

/**
 * Main application entry point.
 *
 * ARCHITECTURE:
 * 1. Bootstrap Phase: Initialize repository, resolve startup state
 * 2. Render Phase: Display UI based on resolved state
 *
 * The startup state is computed ONCE before any content UI is rendered.
 * Navigation state is initialized from the resolved startup state.
 * This prevents any screen flicker or navigation corrections.
 *
 * Screen size breakpoints:
 * - < 600dp: Mobile (no server rail, bottom navigation)
 * - >= 600dp: Desktop (server rail + sidebars)
 */
@Composable
fun App() {
    val vm = viewModel { AppViewModel(AppModule.nostrRepository) }
    val isInitialized by vm.isInitialized.collectAsState()
    val isLoggedIn by vm.isLoggedIn.collectAsState()
    val isBunkerVerifying by vm.isBunkerVerifying.collectAsState()

    val activeId by AppModule.accountStore.activeId.collectAsState()
    // Latches once the app has been shown for a logged-in account, and clears on a real logout
    // (no active account). Once latched, a transient !isLoggedIn window with an account still
    // active is an account switch / signer reconnect, NOT a cold start: the app stays on screen
    // (its in-app bunker banner shows the reconnect) instead of the full-screen loading. The
    // loading screen is for app open only.
    var hasEnteredApp by remember { mutableStateOf(false) }
    LaunchedEffect(isLoggedIn) { if (isLoggedIn) hasEnteredApp = true }
    LaunchedEffect(activeId) { if (activeId == null) hasEnteredApp = false }

    // Background/foreground wiring (the web wires this via visibilitychange in
    // WebApp.kt). onForeground probes for zombie sockets and refreshes subs;
    // FocusTracker drives the "active group + unfocused → still notify" branch.
    // The first ON_RESUME of a cold start is skipped: initialize()/login own the
    // connection sequence and a concurrent reconnect would race them.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var wasBackgrounded = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    AppModule.focusTracker.setFocused(true)
                    if (wasBackgrounded) AppModule.nostrRepository.onForeground()
                }
                Lifecycle.Event.ON_STOP -> {
                    wasBackgrounded = true
                    AppModule.focusTracker.setFocused(false)
                    AppModule.nostrRepository.onBackground()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Compute startup state synchronously from current values.
    val startupState: AppStartState =
        remember(isInitialized, isLoggedIn, isBunkerVerifying, hasEnteredApp, activeId) {
            when {
                // Cold-start bunker restore (we've never entered the app): hold the loading
                // screen until the restored signer confirms, instead of flashing login.
                isBunkerVerifying && !isLoggedIn && !hasEnteredApp -> AppStartState.Initializing
                // Account switch / signer reconnect after the app has been shown: keep the app
                // on screen, never the full-screen loading or a login flash (mirrors the web).
                !isLoggedIn && hasEnteredApp && activeId != null -> StartupResolver.resolve(true, true)
                else -> StartupResolver.resolve(isInitialized, isLoggedIn)
            }
        }

    // Resolve the user's theme preference (Dark / Light / System) into the active
    // palette before anything reads NostrordColors. Snapshot-state write, so every
    // color usage below recomposes when the preference or the OS theme changes.
    val appTheme by AppModule.appearanceSettings.theme.collectAsState()
    NostrordColors.apply(appTheme, systemDark = isSystemInDarkTheme())

    // Install Inter as the app face before any content renders: NostrordTypography
    // reads AppFonts.defaultFontFamily, and the Material typography below covers
    // Text() calls that rely on LocalTextStyle instead of NostrordTypography.
    val interFamily = rememberInterFontFamily()
    remember(interFamily) { AppFonts.setDefaultFontFamily(interFamily) }
    val appTypography = remember(interFamily) { materialTypographyWith(interFamily) }

    MaterialTheme(colorScheme = nostrordColorScheme(), typography = appTypography) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val hasWindowControls = LocalDesktopWindowControls.current != null

            // Phase 3: Render based on resolved startup state
            when (startupState) {
                is AppStartState.Initializing -> {
                    // App open (cold start) only: a switch / reconnect keeps the app on screen
                    // instead (see startupState), so there is no per-switch message here.
                    if (hasWindowControls) {
                        Column(Modifier.fillMaxSize()) {
                            MinimalTitleBar()
                            LoadingScreen(Modifier.weight(1f))
                        }
                    } else {
                        // Edge-to-edge: AppFrame manages its own per-region insets, but the
                        // standalone loading / login / onboarding screens keep a safe-area inset.
                        LoadingScreen(Modifier.safeDrawingPadding())
                    }
                }

                is AppStartState.Unauthenticated -> {
                    // Drop any leftover ?relay=…&group=… query from the previous
                    // session. The deep-link handler is mounted only while logged
                    // in, so without this the login screen would still show the
                    // ex-account's deep link in the address bar. No-op on
                    // native platforms.
                    LaunchedEffect(Unit) { clearBrowserUrlQuery() }
                    // Not logged in - show login
                    if (hasWindowControls) {
                        Column(Modifier.fillMaxSize()) {
                            MinimalTitleBar()
                            NostrLoginScreen(modifier = Modifier.weight(1f)) {
                                // After login, the startupState will recompute due to isLoggedIn change
                            }
                        }
                    } else {
                        NostrLoginScreen(modifier = Modifier.safeDrawingPadding()) {
                            // After login, the startupState will recompute due to isLoggedIn change
                        }
                    }
                }

                is AppStartState.Authenticated -> {
                    // New-design flow: an account whose kind:10009 lists no groups goes
                    // through the onboarding wizard; everyone else lands on the AppFrame
                    // home.
                    val needsOnboarding by vm.needsOnboarding.collectAsState()
                    val onboardingSkipped by vm.onboardingSkipped.collectAsState()
                    // Keeps the wizard up after a group join so several can be joined.
                    val stayInOnboarding by vm.stayInOnboarding.collectAsState()
                    // The sidebar's "Follow people" action re-opens the wizard even for an
                    // account with groups or one that already skipped, so it overrides those.
                    val onboardingRequested by vm.onboardingRequested.collectAsState()
                    val showingOnboarding =
                        onboardingRequested || ((needsOnboarding || stayInOnboarding) && !onboardingSkipped)
                    // While the new account's group list is still resolving (e.g. just switched),
                    // show the loading screen rather than guessing Home vs onboarding, then route.
                    val onboardingPending by vm.onboardingDecisionPending.collectAsState()
                    val content: @Composable (Modifier) -> Unit =
                        when {
                            showingOnboarding -> { m ->
                                OnboardingFlowScreen(
                                    onSkip = vm::skipOnboarding,
                                    onJoin = vm::joinGroupFromInput,
                                    onJoinGroup = { relayUrl, groupId ->
                                        vm.keepOnboarding()
                                        vm.joinGroupFromInput("$relayUrl'$groupId") {}
                                    },
                                    // AppFrame manages its own insets; the onboarding wizard keeps a
                                    // safe-area inset so it stays clear of the system bars.
                                    modifier = m.safeDrawingPadding(),
                                )
                            }
                            onboardingPending -> { m -> LoadingScreen(m.safeDrawingPadding()) }
                            else -> { m -> Box(m) { AppFrame() } }
                        }
                    // Onboarding / loading keep the minimal drag bar; the AppFrame draws its own
                    // NavigationToolbar (back/forward + window controls) at its top, so it takes
                    // the full window with no extra title bar.
                    if (hasWindowControls && (showingOnboarding || onboardingPending)) {
                        Column(Modifier.fillMaxSize()) {
                            MinimalTitleBar()
                            content(Modifier.weight(1f))
                        }
                    } else {
                        content(Modifier.fillMaxSize())
                    }
                }
            }

            // NIP-49 unlock gate: a password-protected account blocked session
            // restore; ask for the password over whatever screen is showing.
            val pendingUnlock by AppModule.nostrRepository.pendingUnlockAccount.collectAsState()
            pendingUnlock?.let { UnlockAccountDialog(it) }

            // Google (pomegranate) sign-in WebView, shown over login or backup export when
            // the flow opens a popup.
            PomegranateAuthHost()
        }
    }
}

/** Material typography with every style on the given family (Inter app-wide). */
private fun materialTypographyWith(fontFamily: FontFamily): Typography {
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = fontFamily),
        displayMedium = base.displayMedium.copy(fontFamily = fontFamily),
        displaySmall = base.displaySmall.copy(fontFamily = fontFamily),
        headlineLarge = base.headlineLarge.copy(fontFamily = fontFamily),
        headlineMedium = base.headlineMedium.copy(fontFamily = fontFamily),
        headlineSmall = base.headlineSmall.copy(fontFamily = fontFamily),
        titleLarge = base.titleLarge.copy(fontFamily = fontFamily),
        titleMedium = base.titleMedium.copy(fontFamily = fontFamily),
        titleSmall = base.titleSmall.copy(fontFamily = fontFamily),
        bodyLarge = base.bodyLarge.copy(fontFamily = fontFamily),
        bodyMedium = base.bodyMedium.copy(fontFamily = fontFamily),
        bodySmall = base.bodySmall.copy(fontFamily = fontFamily),
        labelLarge = base.labelLarge.copy(fontFamily = fontFamily),
        labelMedium = base.labelMedium.copy(fontFamily = fontFamily),
        labelSmall = base.labelSmall.copy(fontFamily = fontFamily),
    )
}

/**
 * Material scheme over the active NostrordColors palette. A function (not a cached val)
 * so the snapshot reads happen inside composition and the scheme follows theme switches.
 */
@Composable
private fun nostrordColorScheme() = if (NostrordColors.IsDark) {
    darkColorScheme(
        primary = NostrordColors.Primary,
        onPrimary = NostrordColors.TextPrimary,
        primaryContainer = NostrordColors.PrimaryVariant,
        onPrimaryContainer = NostrordColors.TextPrimary,
        background = NostrordColors.Background,
        onBackground = NostrordColors.TextContent,
        surface = NostrordColors.Surface,
        onSurface = NostrordColors.TextContent,
        surfaceVariant = NostrordColors.SurfaceVariant,
        onSurfaceVariant = NostrordColors.TextSecondary,
        error = NostrordColors.Error,
        onError = NostrordColors.TextPrimary,
        outline = NostrordColors.Divider,
    )
} else {
    lightColorScheme(
        primary = NostrordColors.Primary,
        // White on brand violet, same as dark (the brand color does not change per theme)
        onPrimary = Color(ColorTokens.TextPrimary),
        primaryContainer = NostrordColors.PrimaryVariant,
        onPrimaryContainer = Color(ColorTokens.TextPrimary),
        background = NostrordColors.Background,
        onBackground = NostrordColors.TextContent,
        surface = NostrordColors.Surface,
        onSurface = NostrordColors.TextContent,
        surfaceVariant = NostrordColors.SurfaceVariant,
        onSurfaceVariant = NostrordColors.TextSecondary,
        error = NostrordColors.Error,
        onError = Color(ColorTokens.TextPrimary),
        outline = NostrordColors.Divider,
    )
}

/** Bootstrap loading screen: the brand spinner over a label, mirroring the web HTML loading shell. */
@Composable
private fun LoadingScreen(modifier: Modifier = Modifier) {
    Column(
        modifier =
        modifier
            .fillMaxSize()
            .background(NostrordColors.Background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = NostrordColors.Primary,
            strokeWidth = 4.dp,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Loading Nostrord…",
            color = NostrordColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
