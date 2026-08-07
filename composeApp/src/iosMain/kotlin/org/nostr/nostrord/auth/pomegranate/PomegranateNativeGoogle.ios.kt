package org.nostr.nostrord.auth.pomegranate

/**
 * iOS signs in through SFSafariViewController + loopback. A browserless path would mean the
 * Google Sign-In SDK, which is not linked here.
 */
internal actual object PomegranateNativeGoogle {
    actual val isAvailable: Boolean = false

    actual suspend fun requestIdToken(serverClientId: String): String? = null
}
