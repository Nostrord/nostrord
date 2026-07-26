package org.nostr.nostrord.startup

import org.nostr.nostrord.ui.Screen

/**
 * Represents the resolved application startup state.
 *
 * This is computed ONCE during bootstrap, BEFORE any UI is rendered.
 * The UI layer consumes this state - it does not compute or modify it.
 *
 * Precedence (highest to lowest):
 * 1. Persisted group state - restored if valid
 * 2. Default home screen - fallback
 * Deep links are not part of this state: they navigate via
 * StartupResolver.runtimeLaunchEvents once the app UI mounts.
 */
sealed class AppStartState {
    /**
     * Application is still initializing.
     * UI should show a loading indicator and wait.
     */
    data object Initializing : AppStartState()

    /**
     * User is not authenticated.
     * UI should show the login screen.
     */
    data object Unauthenticated : AppStartState()

    /**
     * User is authenticated and startup state is fully resolved.
     * UI should render with the specified initial screen.
     *
     * @param initialScreen The screen to display on startup - computed once, never changes
     * @param restoredFromPersistence Whether this screen was restored from saved state
     */
    data class Authenticated(
        val initialScreen: Screen,
        val restoredFromPersistence: Boolean = false,
    ) : AppStartState()
}
