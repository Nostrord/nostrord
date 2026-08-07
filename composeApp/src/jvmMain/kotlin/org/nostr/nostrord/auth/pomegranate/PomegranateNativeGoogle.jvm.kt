package org.nostr.nostrord.auth.pomegranate

/** Desktop has no system account picker; sign-in goes through the loopback browser flow. */
internal actual object PomegranateNativeGoogle {
    actual val isAvailable: Boolean = false

    actual suspend fun requestIdToken(serverClientId: String): String? = null
}
