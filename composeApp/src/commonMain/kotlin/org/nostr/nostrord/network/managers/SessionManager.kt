package org.nostr.nostrord.network.managers

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*
import org.nostr.nostrord.auth.Account
import org.nostr.nostrord.network.AuthManager
import org.nostr.nostrord.network.BunkerState
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.nostr.Nip46Client
import org.nostr.nostrord.utils.epochMillis

/**
 * Manages authentication lifecycle.
 * Handles login, logout, session restoration, and NIP-42 AUTH challenges.
 */
class SessionManager(
    private val authManager: AuthManager,
) {
    // Clients whose AUTH challenge is already being signed, to prevent double-signing
    // when the relay sends a second challenge before the first completed.
    //
    // Keyed on the client INSTANCE, not the relay URL: each reconnect creates a fresh
    // NostrGroupClient, and a URL-keyed guard let an in-flight sign on a now-replaced
    // socket block the NEW socket's challenge. With a slow NIP-46 bunker (signing a
    // single AUTH takes seconds) a reconnect routinely lands mid-sign, so the live
    // connection never authenticated and its private groups stayed permanently empty.
    private val authInProgress = mutableSetOf<NostrGroupClient>()

    // Newest challenge seen per socket. Deduped duplicate frames still refresh this,
    // so the in-flight retry loop always signs the relay's current challenge.
    private val latestChallenge = mutableMapOf<NostrGroupClient, String>()

    private companion object {
        // Bounds ONE sign attempt (a healthy bunker answers in 1-4s; local keys are
        // instant). Well under Nip46Client's own 120s request timeout so a stalled
        // signer transport frees this socket's attempt quickly and the retry loop —
        // not the relay's goodwill in re-challenging — owns recovery.
        const val AUTH_SIGN_TIMEOUT_MS = 20_000L
        const val AUTH_RETRY_BASE_MS = 5_000L
        const val AUTH_RETRY_MAX_MS = 30_000L
    }

    // Delegate auth state to AuthManager
    val isLoggedIn: StateFlow<Boolean> = authManager.isLoggedIn
    val isBunkerConnected: StateFlow<Boolean> = authManager.isBunkerConnected
    val isBunkerVerifying: StateFlow<Boolean> = authManager.isBunkerVerifying
    val bunkerState: StateFlow<BunkerState> = authManager.bunkerState
    val authUrl: StateFlow<String?> = authManager.authUrl
    val pendingUnlock: StateFlow<Account?> = authManager.pendingUnlock

    fun clearPendingUnlock() = authManager.clearPendingUnlock()

    /**
     * Restore session from storage
     */
    suspend fun restoreSession(): Boolean = authManager.restoreSession()

    /**
     * Login with NIP-46 bunker URL
     */
    suspend fun loginWithBunker(bunkerUrl: String): String = authManager.loginWithBunker(bunkerUrl)

    /**
     * Login with private key. [ncryptsec] marks the account password-protected
     * (only the encrypted key is persisted; unlock asks the password at startup).
     */
    suspend fun loginWithPrivateKey(privKey: String, pubKey: String, ncryptsec: String? = null) {
        authManager.loginWithPrivateKey(privKey, pubKey, ncryptsec)
    }

    /**
     * Login via NIP-07 browser extension
     */
    fun loginWithNip07(pubkey: String) {
        authManager.loginWithNip07(pubkey)
    }

    fun loginWithAmber(
        pubkey: String,
        signerPackage: String?,
    ) {
        authManager.loginWithAmber(pubkey, signerPackage)
    }

    /**
     * Set logged in state
     */
    fun setLoggedIn(value: Boolean) {
        authManager.setLoggedIn(value)
    }

    /**
     * Logout
     */
    fun logout() {
        // Clear AUTH dedup so a logout→re-login on the same process doesn't
        // skip the first AUTH for a relay whose entry was left behind by a
        // coroutine cancelled mid-flight (scope.cancelChildren in
        // NostrRepository.logout). A stale entry would make handleAuthChallenge
        // return false, suppressing the resubscribeAfterAuth that normally
        // re-requests group messages.
        authInProgress.clear()
        latestChallenge.clear()
        authManager.logout()
    }

    /**
     * Clear auth URL
     */
    fun clearAuthUrl() {
        authManager.clearAuthUrl()
    }

    /** Default relays seeding the nostrconnect:// QR login (user-overridable). */
    val defaultNostrConnectRelays: List<String> = authManager.defaultNostrConnectRelays

    /**
     * Create a nostrconnect:// session for QR code login
     */
    suspend fun createNostrConnectSession(relays: List<String> = authManager.defaultNostrConnectRelays): Pair<String, Nip46Client> = authManager.createNostrConnectSession(relays)

    /**
     * Complete the nostrconnect:// QR code login
     */
    suspend fun completeNostrConnectLogin(
        client: Nip46Client,
        relays: List<String> = authManager.defaultNostrConnectRelays,
    ): String = authManager.completeNostrConnectLogin(client, relays)

    /**
     * Forget bunker connection
     */
    fun forgetBunkerConnection() {
        authManager.forgetBunkerConnection()
    }

    /**
     * Get public key
     */
    fun getPublicKey(): String? = authManager.getPublicKey()

    /**
     * Get private key
     */
    fun getPrivateKey(): String? = authManager.getPrivateKey()

    /**
     * Check if using bunker
     */
    fun isUsingBunker(): Boolean = authManager.isUsingBunker()

    /**
     * Check if bunker is ready
     */
    fun isBunkerReady(): Boolean = authManager.isBunkerReady()

    /**
     * Ensure bunker is connected
     */
    suspend fun ensureBunkerConnected(): Boolean = authManager.ensureBunkerConnected()

    /**
     * Sign an event
     */
    suspend fun signEvent(
        event: Event,
        interactive: Boolean = true,
    ): Event = authManager.signEvent(event, interactive)

    /**
     * Handle NIP-42 AUTH challenge from relay.
     * Deduplicates: if we're already processing a challenge for this relay, the new one is ignored.
     */
    /**
     * Sign the relay's NIP-42 challenge and send the response.
     *
     * Returns true when this call actually drove the AUTH cycle (signed and
     * sent), false when it was deduped (another coroutine is already handling
     * AUTH for this relay) or the client disconnected mid-way. The caller uses
     * the return value to decide whether to fire the post-AUTH side effects
     * (notifyAuthCompleted, resubscribeAfterAuth): chatty/broken relays that
     * re-send AUTH frames in tight loops would otherwise multiply the
     * resubscribe storm across every duplicate frame.
     */
    suspend fun handleAuthChallenge(client: NostrGroupClient, challenge: String): Boolean {
        val relayUrl = client.getRelayUrl()
        if (!client.isConnected()) return false // race-condition loser: already disconnected
        // Always record the newest challenge, even when this call loses the dedup below:
        // the in-flight loop reads it fresh on every attempt, so a relay that rotates
        // challenges never gets a stale one signed.
        latestChallenge[client] = challenge
        if (!authInProgress.add(client)) return false // already signing for THIS socket

        val pubKey = getPublicKey() ?: run {
            authInProgress.remove(client)
            return false
        }

        return try {
            // Retry until the socket dies. One unbounded 120s bunker sign used to own the
            // socket's ONLY attempt while the dedup above swallowed every re-challenge the
            // relay sent (observed: 8 challenges ignored, zero AUTH frames, total deafness
            // on a private relay when the signer transport was down). Each attempt now gets
            // a bounded budget, and failures back off and retry — a deaf-but-connected
            // socket has nothing better to do than keep trying to authenticate.
            var attempt = 0
            while (client.isConnected()) {
                attempt++
                val authEvent = Event(
                    pubkey = pubKey,
                    createdAt = epochMillis() / 1000,
                    kind = 22242,
                    tags = listOf(
                        listOf("relay", relayUrl),
                        listOf("challenge", latestChallenge[client] ?: challenge),
                    ),
                    content = "",
                )

                // interactive=false: a failed background AUTH sign must not flip the
                // bunker banner to "can't reach your signer". (banner-flicker fix)
                val signedEvent = try {
                    kotlinx.coroutines.withTimeoutOrNull(AUTH_SIGN_TIMEOUT_MS) {
                        signEvent(authEvent, interactive = false)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null // fail-fast throws (signer not connected yet) retry like timeouts
                }

                if (signedEvent != null) {
                    // The bunker sign above can take seconds; if the socket was replaced by a
                    // reconnect meanwhile, sending AUTH to the dead session is wasted and would
                    // fool the caller into firing resubscribeAfterAuth on a client that can't
                    // read. Bail so the live socket's own challenge drives AUTH instead.
                    if (!client.isConnected()) return false

                    client.send(
                        message = buildJsonArray {
                            add("AUTH")
                            add(signedEvent.toJsonObject())
                        }.toString(),
                    )

                    // Give the relay 500 ms to process the AUTH before we send subscriptions.
                    // requestGroups() is handled by the caller (resubscribeAfterAuth) so it only
                    // fires when this client is the focused relay.
                    kotlinx.coroutines.delay(500)
                    return true
                }

                kotlinx.coroutines.delay(minOf(AUTH_RETRY_BASE_MS * (1L shl (attempt - 1)), AUTH_RETRY_MAX_MS))
            }
            false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        } finally {
            authInProgress.remove(client)
            latestChallenge.remove(client)
        }
    }
}
