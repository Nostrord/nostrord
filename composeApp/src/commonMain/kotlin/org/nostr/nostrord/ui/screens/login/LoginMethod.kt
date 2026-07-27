package org.nostr.nostrord.ui.screens.login

import org.nostr.nostrord.auth.pomegranate.PomegranateService
import org.nostr.nostrord.nostr.Nip07
import org.nostr.nostrord.nostr.Nip55

/**
 * A credential method offered by the login screen and the add-account modal. Titles and
 * subtitles live here so the Compose list and the web list read identically; the icon is
 * mapped per platform (ImageVector vs the web `Ic` sprite).
 */
enum class LoginMethod(
    val title: String,
    val subtitle: String,
) {
    PrivateKey("Private key", "nsec, hex or ncryptsec"),
    Bunker("Bunker", "Remote signer over NIP-46"),
    Extension("Browser extension", "Sign with a NIP-07 extension"),
    Amber("Amber", "Signer app on this device"),
    Google("Google", "Managed key, nothing to back up"),
}

/**
 * Methods usable at runtime, in the order they are offered. Extension is browser-only,
 * Amber Android-only, Google gated on the pomegranate build config.
 */
fun availableLoginMethods(): List<LoginMethod> = buildList {
    add(LoginMethod.PrivateKey)
    add(LoginMethod.Bunker)
    if (Nip07.isAvailable()) add(LoginMethod.Extension)
    if (Nip55.isAvailable()) add(LoginMethod.Amber)
    if (PomegranateService().isAvailable) add(LoginMethod.Google)
}
