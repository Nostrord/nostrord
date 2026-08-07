package org.nostr.nostrord.auth.pomegranate

/** The web already runs the sign-in in a popup on the central's own origin. */
internal actual object PomegranateNativeGoogle {
    actual val isAvailable: Boolean = false

    actual suspend fun requestIdToken(serverClientId: String): String? = null
}
