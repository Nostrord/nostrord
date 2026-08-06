package org.nostr.nostrord.auth

import kotlinx.serialization.Serializable
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.loadPomegranateCentralFor
import org.nostr.nostrord.storage.loadPomegranateDisconnectedFor

/**
 * A persisted user identity the app can sign as.
 *
 * The hex [pubkey] doubles as the account id: re-adding the same npub points
 * back at the same persisted state instead of creating a duplicate slot.
 */
@Serializable
data class Account(
    val pubkey: String,
    val label: String,
    val authMethod: AuthMethod,
    val addedAt: Long,
) {
    val id: String get() = pubkey
}

@Serializable
enum class AuthMethod {
    LOCAL,
    BUNKER,
    NIP07,

    /** NIP-55 external Android signer app (Amber). */
    AMBER,
}

/**
 * Body line for the "Log out?" confirmation dialog, tailored to the user's current
 * auth method. The generic "private key or bunker URL" text confused NIP-07 users
 * (no key to keep, only a browser extension to reconnect) and Bunker users
 * (irrelevant private-key mention). Returning per-method copy from one place keeps
 * native and web in sync.
 */
/**
 * Short signer label for the account chip, shared by native and web. Pomegranate
 * ("Login with Google") accounts are BUNKER underneath (NIP-46 remote signing) but
 * read "google", which is how the user knows them.
 */
fun signerLabel(account: Account): String = when (account.authMethod) {
    AuthMethod.LOCAL -> "key"
    AuthMethod.BUNKER -> when {
        SecureStorage.loadPomegranateCentralFor(account.pubkey) != null -> "google"
        SecureStorage.loadPomegranateDisconnectedFor(account.pubkey) -> "disconnected"
        else -> "bunker"
    }
    AuthMethod.NIP07 -> "extension"
    AuthMethod.AMBER -> "amber"
}

fun logoutConfirmBody(method: AuthMethod): String = when (method) {
    AuthMethod.LOCAL -> "You will need your private key to log back in."
    AuthMethod.BUNKER -> "You will need your bunker URL to log back in."
    AuthMethod.NIP07 -> "You will need to reconnect your browser extension to log back in."
    AuthMethod.AMBER -> "You will need to reconnect your signer app to log back in."
}

/**
 * Copy for the per-account confirmation dialog the MeMenu shows before
 * [org.nostr.nostrord.auth.AccountManager.removeAccount]. Title / body /
 * confirm-button label all branch on whether the account is the currently active
 * one, mirroring native `RemoveAccountDialog` in MeMenu.kt. Centralised here so
 * native + web share the exact same wording (and stay in sync if it changes).
 */
fun removeAccountDialogTitle(isActive: Boolean, accountLabel: String): String = if (isActive) "Sign out of \"$accountLabel\"?" else "Remove account?"

/**
 * What gets wiped, in the user's own terms:
 *  - LOCAL: the private key the app stores locally — "Credentials".
 *  - BUNKER: only the connection to the remote signer — the key stays on the bunker.
 *  - NIP-07: nothing key-shaped at all — the extension owns the key, the app just
 *    keeps a reference + caches.
 */
private fun erasedSubject(method: AuthMethod, accountLabel: String): String = when (method) {
    AuthMethod.LOCAL -> "Credentials and local data for \"$accountLabel\""
    AuthMethod.BUNKER -> "Bunker connection and local data for \"$accountLabel\""
    AuthMethod.NIP07 -> "Local data for \"$accountLabel\""
    AuthMethod.AMBER -> "Signer connection and local data for \"$accountLabel\""
}

/** Tail sentence for the active-without-fallback case — "how do I get back in". */
private fun signInAgainHint(method: AuthMethod): String = when (method) {
    AuthMethod.LOCAL -> "You'll need to sign in again to continue."
    AuthMethod.BUNKER -> "You'll need your bunker URL to sign in again."
    AuthMethod.NIP07 -> "Your browser extension keeps your key. Reconnect it to sign in again."
    AuthMethod.AMBER -> "Your signer app keeps your key. Reconnect it to sign in again."
}

fun removeAccountDialogBody(
    isActive: Boolean,
    accountLabel: String,
    fallbackLabel: String?,
    method: AuthMethod,
    holdsDmEncryptionKey: Boolean = false,
): String {
    val erased = "${erasedSubject(method, accountLabel)} will be erased on this device."
    val base =
        when {
            isActive && fallbackLabel != null -> "$erased You'll switch to \"$fallbackLabel\"."
            isActive -> "$erased ${signInAgainHint(method)}"
            else -> erased
        }
    // Unlike the identity key, this one is not recoverable from a backup phrase or a signer:
    // messages already sent to it can only ever be opened with it.
    return if (holdsDmEncryptionKey) {
        "$base This device also holds this account's DM encryption key. Export it first or messages sent to it become unreadable."
    } else {
        base
    }
}

fun removeAccountConfirmLabel(isActive: Boolean): String = if (isActive) "Sign out" else "Remove"

fun removeAccountBusyLabel(isActive: Boolean): String = if (isActive) "Signing out…" else "Removing…"
