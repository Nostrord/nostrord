package org.nostr.nostrord.startup

import kotlinx.coroutines.flow.MutableSharedFlow
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.ui.Screen

/**
 * Resolves the application startup state from persisted data.
 *
 * This resolver is called ONCE during bootstrap, BEFORE any UI is created.
 * It produces a deterministic, single initial state that the UI consumes.
 *
 * IMPORTANT: This must be called AFTER NostrRepository.initialize() completes,
 * but BEFORE any navigation UI is rendered.
 */
object StartupResolver {
    /**
     * External launch context that overrides persisted state.
     * Set by platform code before App() is called.
     *
     * Examples: deep links, notification taps, shortcuts
     */
    var externalLaunchContext: ExternalLaunchContext? = null
        private set

    /**
     * Set an external launch context that overrides persisted state.
     * Must be called BEFORE App() is rendered.
     */
    fun setExternalLaunchContext(context: ExternalLaunchContext) {
        externalLaunchContext = context
    }

    /**
     * Clear external launch context after it has been consumed. Also drops the
     * replayed runtime event so a later UI mount doesn't re-navigate to it.
     */
    fun clearExternalLaunchContext() {
        externalLaunchContext = null
        runtimeLaunchEvents.resetReplayCache()
    }

    /**
     * The single deep-link navigation channel: cold start (argv, intent data) and
     * running-app arrivals (second desktop instance forwarding its argv, Android
     * onNewIntent) both go through [postRuntimeLaunch]. The mounted AppFrame
     * collects [runtimeLaunchEvents] and navigates; replay = 1 keeps an event
     * posted before the collector mounts (boot, login screen, onboarding) alive
     * until it does. The stored context only feeds [deepLinkRelayUrl] so
     * initialize() connects the target relay before the UI asks for the group.
     */
    val runtimeLaunchEvents = MutableSharedFlow<ExternalLaunchContext>(replay = 1)

    fun postRuntimeLaunch(context: ExternalLaunchContext) {
        externalLaunchContext = context
        runtimeLaunchEvents.tryEmit(context)
    }

    /**
     * Relay URL from external launch context (if any).
     * Read by NostrRepository.initialize() to merge into relay list before connecting.
     */
    val deepLinkRelayUrl: String?
        get() =
            when (val ctx = externalLaunchContext) {
                is ExternalLaunchContext.OpenGroup -> ctx.relayUrl
                is ExternalLaunchContext.OpenRelay -> ctx.relayUrl
                is ExternalLaunchContext.OpenNotifications -> ctx.relayUrl
                else -> null
            }

    /**
     * Resolves the initial screen for an authenticated user from persisted state.
     * Deep links do NOT resolve here: they ride [runtimeLaunchEvents] and navigate
     * once AppFrame mounts (consuming the context here would destroy the replayed
     * event before its only collector exists).
     *
     * @param pubkey The authenticated user's public key
     */
    fun resolveInitialScreen(pubkey: String): ResolvedScreen {
        // Restore persisted group state
        try {
            val lastGroup = SecureStorage.getLastViewedGroup(pubkey)
            if (lastGroup != null) {
                val (groupId, groupName) = lastGroup
                // Validate the group ID is not empty/corrupted
                if (groupId.isNotBlank()) {
                    return ResolvedScreen(Screen.Group(groupId, groupName), restoredFromPersistence = true)
                }
            }
        } catch (e: Exception) {
            // Storage error - clear corrupted state and fall through to default
            try {
                SecureStorage.clearLastViewedGroup(pubkey)
            } catch (_: Exception) {
                // Ignore cleanup errors
            }
        }

        // Default to home screen
        return ResolvedScreen(Screen.Home)
    }

    /**
     * Computes the full AppStartState based on current initialization and auth status.
     *
     * @param isInitialized Whether NostrRepository has finished initializing
     * @param isLoggedIn Whether the user is authenticated
     * @return The resolved startup state
     */
    fun resolve(
        isInitialized: Boolean,
        isLoggedIn: Boolean,
    ): AppStartState {
        // Not yet initialized - must wait
        if (!isInitialized) {
            return AppStartState.Initializing
        }

        // Not logged in - show login
        if (!isLoggedIn) {
            return AppStartState.Unauthenticated
        }

        // Authenticated - resolve initial screen
        val pubkey = AppModule.nostrRepository.getPublicKey()
        if (pubkey == null) {
            // Edge case: logged in but no pubkey (shouldn't happen, but handle gracefully)
            return AppStartState.Authenticated(
                initialScreen = Screen.Home,
                restoredFromPersistence = false,
            )
        }

        val resolved = resolveInitialScreen(pubkey)
        return AppStartState.Authenticated(
            initialScreen = resolved.screen,
            restoredFromPersistence = resolved.restoredFromPersistence,
        )
    }
}

data class ResolvedScreen(
    val screen: Screen,
    val restoredFromPersistence: Boolean = false,
)

sealed class ExternalLaunchContext {
    data class OpenGroup(
        val groupId: String,
        val groupName: String?,
        val relayUrl: String? = null,
        val inviteCode: String? = null,
        val messageId: String? = null,
    ) : ExternalLaunchContext()

    data class OpenRelay(
        val relayUrl: String,
    ) : ExternalLaunchContext()

    data class OpenNotifications(
        val relayUrl: String? = null,
    ) : ExternalLaunchContext()

    data object OpenHome : ExternalLaunchContext()
}
