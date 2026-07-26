package org.nostr.nostrord.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.nostr.nostrord.network.AuthManager
import org.nostr.nostrord.nostr.Nip07
import org.nostr.nostrord.nostr.Nip55
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.getNip55SignerPackageFor

/**
 * Builds [AccountSession] instances from the credentials that
 * [AuthManager.useAccount] / a successful login flow have already loaded.
 *
 * Callers MUST run AuthManager's credential-load step before invoking [build],
 * so the resulting [NostrSigner] wraps the SAME key pair / bunker client that
 * AuthManager owns. Without that ordering, two `Nip46Client` instances would
 * race for the same bunker connection and signing would be ambiguous.
 *
 * Disposing a session's signer (on account switch / logout) also disconnects
 * the bunker client AuthManager holds — by design: a switched-away account
 * must not retain a live signer-relay connection.
 *
 * @param appScope Parent for all session scopes. Each session scope is a child
 *   of [appScope] (SupervisorJob) so platform lifecycle cancellation propagates
 *   correctly while individual session cancellations remain isolated.
 */
class AccountSessionFactory(
    private val appScope: CoroutineScope,
) {
    /**
     * Wrap [account]'s currently loaded credentials (in [authManager]) into a
     * fresh [AccountSession]. Returns null if the relevant credentials are not
     * available (e.g. local key not loaded, bunker client absent, NIP-07 unsupported).
     *
     * The caller is responsible for ensuring [authManager] has already loaded
     * credentials for [account] — typically by calling [AuthManager.useAccount]
     * or a login method just before this.
     */
    fun build(
        account: Account,
        authManager: AuthManager,
    ): AccountSession? {
        val signer = buildSigner(account, authManager) ?: return null
        val sessionScope =
            CoroutineScope(
                SupervisorJob(appScope.coroutineContext[Job]) + Dispatchers.Default,
            )
        return AccountSession(
            accountId = AccountId(account.id),
            pubkey = account.pubkey,
            signer = signer,
            scope = sessionScope,
            sessionToken = ActiveAccountManager.nextToken(),
        )
    }

    private fun buildSigner(
        account: Account,
        authManager: AuthManager,
    ): NostrSigner? = when (account.authMethod) {
        AuthMethod.LOCAL -> authManager.activeKeyPair()?.let { NostrSigner.Local(it) }
        AuthMethod.BUNKER ->
            authManager.activeNip46Client()?.let {
                NostrSigner.Bunker(nip46Client = it, pubkey = account.pubkey)
            }
        AuthMethod.NIP07 -> if (Nip07.isAvailable()) NostrSigner.Nip07Extension(account.pubkey) else null
        AuthMethod.AMBER ->
            if (Nip55.isAvailable()) {
                NostrSigner.Amber(account.pubkey, SecureStorage.getNip55SignerPackageFor(account.pubkey))
            } else {
                null
            }
    }
}
