package org.nostr.nostrord.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import org.nostr.nostrord.auth.Account
import org.nostr.nostrord.auth.AccountStore
import org.nostr.nostrord.auth.ActiveAccountManager
import org.nostr.nostrord.auth.AuthMethod
import org.nostr.nostrord.auth.NostrSigner
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.nostr.Nip07
import org.nostr.nostrord.nostr.Nip46Client
import org.nostr.nostrord.nostr.Nip55
import org.nostr.nostrord.platformDisplayName
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.clearAllCredentialsForAccount
import org.nostr.nostrord.storage.clearEncryptedPrivateKeyFor
import org.nostr.nostrord.storage.clearPrivateKeyFor
import org.nostr.nostrord.storage.getBunkerClientPrivateKeyFor
import org.nostr.nostrord.storage.getBunkerUrlFor
import org.nostr.nostrord.storage.getEncryptedPrivateKeyFor
import org.nostr.nostrord.storage.getNip55SignerPackageFor
import org.nostr.nostrord.storage.getPrivateKeyFor
import org.nostr.nostrord.storage.saveBunkerClientPrivateKeyFor
import org.nostr.nostrord.storage.saveBunkerUrlFor
import org.nostr.nostrord.storage.saveEncryptedPrivateKeyFor
import org.nostr.nostrord.storage.saveNip55SignerPackageFor
import org.nostr.nostrord.storage.savePrivateKeyFor
import org.nostr.nostrord.utils.epochMillis

// Interactive reconnect deadlines. Kept short so the banner's "Reconnect" button
// fails fast with a precise reason instead of freezing on the old 120s RPC wait.
// connectRelaysOnly's internal waitForConnection caps at 7s; backgroundConnect at
// 10s — these are the safety nets just above those.
private const val RELAY_CONNECT_TIMEOUT_MS = 8_000L
private const val SIGNER_CONNECT_TIMEOUT_MS = 12_000L

// Background auto-reconnect: capped exponential backoff, bounded attempts.
private const val AUTO_RECONNECT_BASE_MS = 3_000L
private const val AUTO_RECONNECT_MAX_MS = 60_000L
private const val AUTO_RECONNECT_MAX_ATTEMPTS = 5

// Consecutive bunker sign failures (timeouts/transport, not explicit permission
// rejections) before background signs raise the Unreachable banner. The count alone
// is not enough: N relays re-challenging AUTH can burn through it in one burst
// during a single hiccup, so the streak must also SPAN the window below.
private const val BUNKER_SIGN_FAILURE_BANNER_THRESHOLD = 5
private const val BUNKER_SIGN_FAILURE_BANNER_WINDOW_MS = 90_000L

// A failure this long after the previous one starts a NEW streak: stale counts from
// an old burst must not let a single later failure trip the threshold.
private const val BUNKER_SIGN_FAILURE_STREAK_RESET_MS = 5 * 60_000L

/**
 * Manages authentication state and signing operations.
 *
 * One instance is alive at a time, holding state for the currently active
 * account. Switching accounts re-loads credentials in place rather than
 * creating new instances, so dependents (SessionManager, UI) can keep their
 * StateFlow references stable across switches.
 */
class AuthManager(
    private val accountStore: AccountStore,
) {
    private val authScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var keyPair: KeyPair? = null
    private var nip46Client: Nip46Client? = null
    private var isBunkerLogin = false
    private var bunkerUserPubkey: String? = null
    private var isNip07Login = false
    private var nip07UserPubkey: String? = null
    private var isAmberLogin = false
    private var amberUserPubkey: String? = null

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    /**
     * Set when session restore (or an account switch) hits a NIP-49
     * password-protected account: only the ncryptsec is on disk, so the app
     * cannot build a signer until the user enters the password. The UIs gate
     * an unlock dialog on this; cleared on a successful login/unlock or when
     * the user dismisses it.
     */
    private val _pendingUnlock = MutableStateFlow<Account?>(null)
    val pendingUnlock: StateFlow<Account?> = _pendingUnlock.asStateFlow()

    fun clearPendingUnlock() {
        _pendingUnlock.value = null
    }

    /**
     * Invoked when the active session is invalidated involuntarily (bunker
     * permission revoked, etc). Receives the pubkey that just lost its
     * session so the host can try to switch to another signed-in account
     * before falling back to a logged-out state. If unset, [handlePermissionDenied]
     * sets [_isLoggedIn] to false directly.
     */
    internal var onSessionInvalidated: (suspend (invalidatedPubkey: String?) -> Unit)? = null

    // Single source of truth for the bunker connection lifecycle. The legacy
    // isBunkerConnected boolean is derived from it so existing consumers keep
    // working unchanged. isBunkerVerifying stays an independent flag because the
    // App reuses it as a generic "show loading overlay" signal during cold-boot
    // restore and logout — folding it in here would change those screens.
    private val _bunkerState = MutableStateFlow<BunkerState>(BunkerState.Inactive)
    val bunkerState: StateFlow<BunkerState> = _bunkerState.asStateFlow()

    val isBunkerConnected: StateFlow<Boolean> =
        _bunkerState
            .map { it is BunkerState.Connected }
            .stateIn(authScope, SharingStarted.Eagerly, false)

    private val _isBunkerVerifying = MutableStateFlow(false)
    val isBunkerVerifying: StateFlow<Boolean> = _isBunkerVerifying.asStateFlow()

    // Capped-backoff background reconnect, started on entering Unreachable and
    // cancelled once reconnected or the session ends. Serialized via the mutex
    // so the banner button and the auto loop never reconnect concurrently.
    private val reconnectMutex = Mutex()
    private var autoReconnectJob: Job? = null

    private val _authUrl = MutableStateFlow<String?>(null)
    val authUrl: StateFlow<String?> = _authUrl.asStateFlow()

    // Default relays for nostrconnect:// QR code flow. damus.io / nos.lol are
    // general-purpose relays that reliably carry NIP-46 traffic. relay.nsec.app
    // is intentionally excluded: it was unreachable for some users and made the
    // whole QR connect fail with "Failed to connect to any relay".
    // Users can override these per-session in the QR login screen.
    val defaultNostrConnectRelays =
        listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
        )

    fun clearAuthUrl() {
        _authUrl.value = null
    }

    /**
     * Get the current user's public key (hex).
     *
     * Prefers [ActiveAccountManager]'s session so the value is always in sync
     * with the active session after a switch. Falls back to the inline fields
     * for compatibility during the login flow before the session is activated.
     */
    fun getPublicKey(): String? = ActiveAccountManager.currentPubkey ?: when {
        isBunkerLogin -> bunkerUserPubkey
        isNip07Login -> nip07UserPubkey
        isAmberLogin -> amberUserPubkey
        keyPair != null -> keyPair?.publicKeyHex
        else -> null
    }

    /**
     * Get the current user's private key (hex) - only for local login
     */
    fun getPrivateKey(): String? = if (isBunkerLogin || isNip07Login || isAmberLogin) null else keyPair?.privateKeyHex

    fun isUsingBunker(): Boolean = isBunkerLogin

    fun isBunkerReady(): Boolean = isBunkerLogin && nip46Client != null

    /**
     * Live local key pair for [AccountSessionFactory]. Null for non-LOCAL
     * accounts. Shared instance: disposing the session's signer zeroes this
     * manager's bytes too (desired on switch).
     */
    internal fun activeKeyPair(): org.nostr.nostrord.nostr.KeyPair? = keyPair

    /**
     * Live NIP-46 client for [AccountSessionFactory]. Null for non-BUNKER
     * accounts. Shared instance: disposing the session's signer disconnects
     * this manager's client too (desired on switch).
     */
    internal fun activeNip46Client(): Nip46Client? = nip46Client

    private fun zeroAndClearKeyPair() {
        keyPair?.privateKey?.fill(0)
        keyPair = null
    }

    /**
     * The client identity to connect [signerPubkey] with: the key an account already
     * registered with that same signer, so it stays the client the signer approved
     * (Amber's "always allow"), or null for a fresh one.
     *
     * Replaying the retired global key instead is what wedged a re-login: the pomegranate
     * handler had already dropped that client with the previous session and never answered
     * its `connect`, leaving the UI on "Connecting..." until the request timed out.
     */
    private fun clientKeyForBunker(signerPubkey: String): String? = accountStore.accounts.value
        .firstOrNull { account ->
            SecureStorage
                .getBunkerUrlFor(account.pubkey)
                ?.let { runCatching { parseBunkerUrl(it).pubkey }.getOrNull() } == signerPubkey
        }?.let { SecureStorage.getBunkerClientPrivateKeyFor(it.pubkey) }

    /**
     * Login with NIP-46 bunker URL
     * Returns the user's public key on success
     */
    suspend fun loginWithBunker(bunkerUrl: String): String {
        val bunkerInfo = parseBunkerUrl(bunkerUrl)

        val newNip46Client = Nip46Client(clientKeyForBunker(bunkerInfo.pubkey))

        // Set up auth URL callback
        newNip46Client.onAuthUrl = { url ->
            _authUrl.value = url
        }

        try {
            newNip46Client.connect(
                remoteSignerPubkey = bunkerInfo.pubkey,
                relays = bunkerInfo.relays,
                secret = bunkerInfo.secret,
            )
        } catch (e: Exception) {
            // "already connected" means the signer remembers us - success!
            if (e.message?.contains("already connected", ignoreCase = true) == true) {
            } else {
                throw e
            }
        }

        val userPubkey = newNip46Client.getPublicKey()
        rejectIfBunkerSwap(userPubkey, bunkerUrl)

        nip46Client = newNip46Client
        bunkerUserPubkey = userPubkey
        isBunkerLogin = true
        zeroAndClearKeyPair()

        // Save bunker credentials for session persistence
        SecureStorage.saveBunkerUrl(bunkerUrl)
        SecureStorage.saveBunkerUserPubkey(userPubkey)
        SecureStorage.saveBunkerClientPrivateKey(newNip46Client.clientPrivateKey)
        SecureStorage.saveBunkerUrlFor(userPubkey, bunkerUrl)
        SecureStorage.saveBunkerClientPrivateKeyFor(userPubkey, newNip46Client.clientPrivateKey)
        SecureStorage.clearPrivateKey()
        registerAccountAfterLogin(userPubkey, AuthMethod.BUNKER)

        _bunkerState.value = BunkerState.Connected
        stopAutoReconnect()
        _authUrl.value = null

        return userPubkey
    }

    /**
     * Defense-in-depth against a signer claiming a pubkey we already track
     * under a different bunker URL. The pubkey-keyed credential slots
     * (SecureStorage.*For(pubkey)) trust this returned pubkey, so a swap here
     * would silently re-point an existing account at a foreign signer.
     */
    private fun rejectIfBunkerSwap(
        returnedPubkey: String,
        bunkerUrl: String,
    ) {
        val existing = accountStore.get(returnedPubkey) ?: return
        if (existing.authMethod != AuthMethod.BUNKER) {
            throw IllegalStateException(
                "Account $returnedPubkey is already registered with a different sign-in method.",
            )
        }
        val savedUrl = SecureStorage.getBunkerUrlFor(returnedPubkey)
        if (!savedUrl.isNullOrBlank() && savedUrl != bunkerUrl) {
            throw IllegalStateException(
                "Account $returnedPubkey already exists with a different bunker. " +
                    "Remove the existing account before reconnecting.",
            )
        }
    }

    /**
     * Login via nostrconnect:// QR code flow.
     * Connects to relays and starts listening BEFORE returning the URI,
     * so no events are missed when the signer scans the QR code.
     */
    suspend fun createNostrConnectSession(relays: List<String> = defaultNostrConnectRelays): Pair<String, Nip46Client> {
        val newNip46Client = Nip46Client(null)
        newNip46Client.onAuthUrl = { url -> _authUrl.value = url }
        // Connect to relays and subscribe FIRST
        newNip46Client.startListeningForConnection(relays, null)
        // Only then generate the URI for QR display
        val uri = newNip46Client.generateNostrConnectUri(relays, name = platformDisplayName)
        return uri to newNip46Client
    }

    /**
     * Complete the nostrconnect:// flow by waiting for the signer and fetching the public key.
     * The client must already be listening (via createNostrConnectSession).
     */
    suspend fun completeNostrConnectLogin(
        client: Nip46Client,
        relays: List<String> = defaultNostrConnectRelays,
    ): String {
        val signerPubkey = client.awaitIncomingConnection()

        val userPubkey = client.getPublicKey()

        // Build a bunker:// URL for session persistence (also used by the
        // swap-guard below to compare against any saved URL for this pubkey).
        val relayParams = relays.joinToString("&") { "relay=$it" }
        val bunkerUrl = "bunker://$signerPubkey?$relayParams"
        rejectIfBunkerSwap(userPubkey, bunkerUrl)

        nip46Client = client
        bunkerUserPubkey = userPubkey
        isBunkerLogin = true
        zeroAndClearKeyPair()

        SecureStorage.saveBunkerUrl(bunkerUrl)
        SecureStorage.saveBunkerUserPubkey(userPubkey)
        SecureStorage.saveBunkerClientPrivateKey(client.clientPrivateKey)
        SecureStorage.saveBunkerUrlFor(userPubkey, bunkerUrl)
        SecureStorage.saveBunkerClientPrivateKeyFor(userPubkey, client.clientPrivateKey)
        SecureStorage.clearPrivateKey()
        registerAccountAfterLogin(userPubkey, AuthMethod.BUNKER)

        _bunkerState.value = BunkerState.Connected
        stopAutoReconnect()
        _authUrl.value = null

        return userPubkey
    }

    /**
     * Login via NIP-07 browser extension.
     * The public key has already been obtained from window.nostr.getPublicKey().
     */
    fun loginWithNip07(pubkey: String) {
        nip07UserPubkey = pubkey
        isNip07Login = true
        isBunkerLogin = false
        isAmberLogin = false
        amberUserPubkey = null
        zeroAndClearKeyPair()
        nip46Client = null

        SecureStorage.saveNip07UserPubkey(pubkey)
        SecureStorage.clearPrivateKey()
        SecureStorage.clearBunkerUrl()
        SecureStorage.clearBunkerUserPubkey()
        SecureStorage.clearBunkerClientPrivateKey()
        registerAccountAfterLogin(pubkey, AuthMethod.NIP07)
    }

    /**
     * Login via a NIP-55 Android signer app (Amber). The public key and signer
     * package have already been obtained through the signer's get_public_key UI.
     */
    fun loginWithAmber(
        pubkey: String,
        signerPackage: String?,
    ) {
        amberUserPubkey = pubkey
        isAmberLogin = true
        isNip07Login = false
        nip07UserPubkey = null
        isBunkerLogin = false
        zeroAndClearKeyPair()
        nip46Client = null

        if (signerPackage != null) SecureStorage.saveNip55SignerPackageFor(pubkey, signerPackage)
        SecureStorage.clearPrivateKey()
        SecureStorage.clearBunkerUrl()
        SecureStorage.clearBunkerUserPubkey()
        SecureStorage.clearBunkerClientPrivateKey()
        SecureStorage.clearNip07UserPubkey()
        registerAccountAfterLogin(pubkey, AuthMethod.AMBER)
    }

    /**
     * Login with local private key. When [ncryptsec] is given the account is
     * password-protected: only the encrypted key is persisted (the raw key
     * lives in the in-memory signer) and the next session restore asks for the
     * password via [pendingUnlock].
     */
    fun loginWithPrivateKey(
        privateKeyHex: String,
        publicKeyHex: String,
        ncryptsec: String? = null,
    ) {
        zeroAndClearKeyPair()
        keyPair = KeyPair.fromPrivateKeyHex(privateKeyHex)
        isBunkerLogin = false
        bunkerUserPubkey = null
        isNip07Login = false
        nip07UserPubkey = null
        isAmberLogin = false
        amberUserPubkey = null
        nip46Client = null

        if (ncryptsec == null) {
            SecureStorage.savePrivateKey(privateKeyHex)
            SecureStorage.savePrivateKeyFor(publicKeyHex, privateKeyHex)
            SecureStorage.clearEncryptedPrivateKeyFor(publicKeyHex)
        } else {
            SecureStorage.clearPrivateKey()
            SecureStorage.clearPrivateKeyFor(publicKeyHex)
            SecureStorage.saveEncryptedPrivateKeyFor(publicKeyHex, ncryptsec)
        }
        SecureStorage.clearBunkerUrl()
        SecureStorage.clearBunkerUserPubkey()
        SecureStorage.clearBunkerClientPrivateKey()
        SecureStorage.clearNip07UserPubkey()
        _pendingUnlock.value = null
        registerAccountAfterLogin(publicKeyHex, AuthMethod.LOCAL)
    }

    /**
     * Insert or update the Account record for [pubkey] and set it as active.
     * Called by every login method after credentials are persisted.
     */
    private fun registerAccountAfterLogin(
        pubkey: String,
        method: AuthMethod,
    ) {
        if (pubkey.isBlank()) return
        val existing = accountStore.get(pubkey)
        val account =
            existing?.copy(authMethod = method) ?: Account(
                pubkey = pubkey,
                label = "Account ${accountStore.accounts.value.size + 1}",
                authMethod = method,
                addedAt = epochMillis(),
            )
        accountStore.upsert(account)
        accountStore.setActive(pubkey)
    }

    /**
     * Restore session from saved credentials.
     *
     * Prefers the AccountStore: if there's an active account, load credentials
     * from its pubkey-scoped slot. Falls back to legacy single-slot lookup so
     * an in-flight upgrade (Account record present but credentials still only
     * in legacy slot) doesn't lock the user out.
     */
    suspend fun restoreSession(): Boolean {
        val active = accountStore.active
        if (active != null && useAccount(active)) return true

        // Legacy fallback path. Order matches the original precedence
        // (NIP-07 > bunker > local key).
        val savedNip07Pubkey = SecureStorage.getNip07UserPubkey()
        if (savedNip07Pubkey != null && Nip07.isAvailable()) {
            isNip07Login = true
            nip07UserPubkey = savedNip07Pubkey
            _isLoggedIn.value = true
            return true
        }

        val savedBunkerUrl = SecureStorage.getBunkerUrl()
        val savedUserPubkey = SecureStorage.getBunkerUserPubkey()
        if (savedBunkerUrl != null && savedUserPubkey != null) {
            return restoreBunkerSession(savedBunkerUrl, savedUserPubkey)
        }

        val savedPrivateKey = SecureStorage.getPrivateKey()
        if (savedPrivateKey != null) {
            return restorePrivateKeySession(savedPrivateKey)
        }

        return false
    }

    /**
     * Load credentials for [account] into this AuthManager instance and emit
     * the resulting auth-state flows. Used by both restoreSession() and
     * AccountManager.switchAccount.
     *
     * Validates the new credentials BEFORE tearing down the current session,
     * so a missing/invalid slot does not leave the app in a half-logged-in
     * state. On success, [accountStore] is set to active.
     */
    suspend fun useAccount(account: Account): Boolean {
        // Build new credentials before touching the current session — a
        // BUNKER connect failure here leaves the existing session intact.
        val prepared: PreparedAccount =
            when (account.authMethod) {
                AuthMethod.LOCAL -> {
                    // The legacy global slot holds the LAST plain-key login, which in a
                    // multi-account setup can belong to a different account. Only accept
                    // it when it actually derives this account's pubkey — otherwise a
                    // password-protected account (empty per-account slot) would silently
                    // come up signing with another account's key.
                    val kp =
                        SecureStorage.getPrivateKeyFor(account.pubkey)?.let(::keyPairOrNull)
                            ?: SecureStorage.getPrivateKey()?.let(::keyPairOrNull)
                                ?.takeIf { it.publicKeyHex == account.pubkey }
                    if (kp == null) {
                        // Password-protected key: only the ncryptsec is stored. Signal
                        // the unlock gate instead of silently failing the restore.
                        if (SecureStorage.getEncryptedPrivateKeyFor(account.pubkey) != null) {
                            _pendingUnlock.value = account
                        }
                        return false
                    }
                    PreparedAccount.Local(kp)
                }
                AuthMethod.BUNKER -> {
                    val bunkerUrl =
                        SecureStorage.getBunkerUrlFor(account.pubkey)
                            ?: SecureStorage.getBunkerUrl()
                            ?: return false
                    val client =
                        buildBunkerClient(bunkerUrl, account.pubkey)
                            ?: return false // connect failed — keep current session
                    PreparedAccount.Bunker(bunkerUrl, client)
                }
                AuthMethod.NIP07 -> {
                    if (!Nip07.isAvailable()) return false
                    PreparedAccount.Nip07
                }
                AuthMethod.AMBER -> {
                    if (!Nip55.isAvailable()) return false
                    PreparedAccount.Amber
                }
            }

        nip46Client?.disconnect()
        nip46Client = null
        isBunkerLogin = false
        bunkerUserPubkey = null
        isNip07Login = false
        nip07UserPubkey = null
        isAmberLogin = false
        amberUserPubkey = null
        zeroAndClearKeyPair()
        stopAutoReconnect()
        _bunkerState.value = BunkerState.Inactive

        when (prepared) {
            is PreparedAccount.Local -> {
                keyPair = prepared.keyPair
                _isLoggedIn.value = true
            }
            is PreparedAccount.Bunker -> {
                installBunkerClient(prepared.client, prepared.bunkerUrl, account.pubkey)
            }
            PreparedAccount.Nip07 -> {
                isNip07Login = true
                nip07UserPubkey = account.pubkey
                _isLoggedIn.value = true
            }
            PreparedAccount.Amber -> {
                isAmberLogin = true
                amberUserPubkey = account.pubkey
                _isLoggedIn.value = true
            }
        }

        _pendingUnlock.value = null
        accountStore.setActive(account.pubkey)
        return true
    }

    /**
     * Build a [Nip46Client] object for [bunkerUrl]. Only validates that the
     * URL parses; no network I/O happens here.
     *
     * Returns null only when the bunker URL is malformed — i.e. a true
     * credential problem. Transient network failures during relay connect are
     * handled later in [installBunkerClient] (async), so a flaky relay does
     * not block the account switch.
     */
    private fun buildBunkerClient(
        bunkerUrl: String,
        userPubkey: String,
    ): Nip46Client? = try {
        parseBunkerUrl(bunkerUrl) // validate URL
        val savedClientPrivateKey =
            SecureStorage.getBunkerClientPrivateKeyFor(userPubkey)
                ?: SecureStorage.getBunkerClientPrivateKey()
        val client =
            if (savedClientPrivateKey != null) {
                Nip46Client(savedClientPrivateKey)
            } else {
                Nip46Client()
            }
        client.onAuthUrl = { url -> _authUrl.value = url }
        client
    } catch (_: Exception) {
        null
    }

    /**
     * Install a [client] that was already prepared by [buildBunkerClient],
     * and start the asynchronous relay connect + NIP-46 connect RPC in
     * [authScope].
     *
     * Failures during the async connect mark the bunker as disconnected
     * but do NOT log the user out — they remain on this account and the
     * UI can offer a retry. Explicit signer rejection still routes through
     * the [onRevoked] callback (which calls [handlePermissionDenied]).
     *
     * Identity-guarded callbacks: every async callback checks
     * `nip46Client === client` so stale events from a swapped-out client
     * cannot affect the current account.
     */
    private fun installBunkerClient(
        client: Nip46Client,
        bunkerUrl: String,
        userPubkey: String,
    ) {
        nip46Client = client
        bunkerUserPubkey = userPubkey
        isBunkerLogin = true
        _isLoggedIn.value = true
        // Optimistic: assume reachable. The async block below flips this to
        // false if the WebSocket open fails so the UI can show "Disconnected".
        _bunkerState.value = BunkerState.Connected
        _isBunkerVerifying.value = true

        val savedClientPrivateKey =
            SecureStorage.getBunkerClientPrivateKeyFor(userPubkey)
                ?: SecureStorage.getBunkerClientPrivateKey()

        authScope.launch {
            val info =
                try {
                    parseBunkerUrl(bunkerUrl)
                } catch (_: Exception) {
                    if (nip46Client === client) {
                        _isBunkerVerifying.value = false
                        _bunkerState.value =
                            BunkerState.Unreachable(BunkerUnreachableReason.Unknown)
                    }
                    return@launch
                }
            try {
                client.connectRelaysOnly(info.pubkey, info.relays)
            } catch (_: Exception) {
                // Relay unreachable — keep the user on this account, just
                // surface the disconnected state. They can retry later;
                // backgroundConnect below would just time out without sockets.
                if (nip46Client === client) {
                    _isBunkerVerifying.value = false
                    enterUnreachable(BunkerUnreachableReason.RelaysUnreachable)
                }
                return@launch
            }
            client.backgroundConnect(
                secret = info.secret,
                onSuccess = {
                    if (nip46Client !== client) return@backgroundConnect
                    // Confirm the signer actually controls the pubkey this account
                    // claims. Without this, a malicious bunker (or a slot whose
                    // contents leaked across accounts) could sign under a key the
                    // user does not own. See SecureStorage credential slot fix.
                    authScope.launch {
                        val signerPubkey =
                            try {
                                client.getPublicKey()
                            } catch (_: Exception) {
                                if (nip46Client === client) _isBunkerVerifying.value = false
                                return@launch
                            }
                        if (nip46Client !== client) return@launch
                        if (!signerPubkey.equals(userPubkey, ignoreCase = true)) {
                            // Signer identity does not match — refuse the session.
                            handlePermissionDenied()
                            delay(1500)
                            _isBunkerVerifying.value = false
                            return@launch
                        }
                        _isBunkerVerifying.value = false
                    }
                },
                onRevoked = {
                    if (nip46Client !== client) return@backgroundConnect
                    // Couldn't reach or verify the signer — keep the user signed
                    // in and let the banner offer a retry instead of logging out.
                    markSignerUnreachable()
                },
            )
        }

        if (savedClientPrivateKey == null) {
            SecureStorage.saveBunkerClientPrivateKey(client.clientPrivateKey)
            SecureStorage.saveBunkerClientPrivateKeyFor(userPubkey, client.clientPrivateKey)
        }
    }

    private sealed class PreparedAccount {
        data class Local(
            val keyPair: KeyPair,
        ) : PreparedAccount()

        data class Bunker(
            val bunkerUrl: String,
            val client: Nip46Client,
        ) : PreparedAccount()

        object Nip07 : PreparedAccount()

        object Amber : PreparedAccount()
    }

    private suspend fun restoreBunkerSession(
        bunkerUrl: String,
        savedUserPubkey: String,
    ): Boolean {
        val bunkerInfo =
            try {
                parseBunkerUrl(bunkerUrl)
            } catch (_: Exception) {
                // A corrupt/unparseable stored URL is the only restore failure
                // that genuinely logs out — there is nothing to reconnect to.
                _isLoggedIn.value = false
                _isBunkerVerifying.value = false
                clearBunkerCredentials()
                return false
            }

        val savedClientPrivateKey =
            SecureStorage.getBunkerClientPrivateKeyFor(savedUserPubkey)
                ?: SecureStorage.getBunkerClientPrivateKey()

        val newNip46Client =
            if (savedClientPrivateKey != null) {
                Nip46Client(savedClientPrivateKey)
            } else {
                Nip46Client()
            }

        bunkerUserPubkey = savedUserPubkey
        isBunkerLogin = true
        newNip46Client.onAuthUrl = { url ->
            _authUrl.value = url
        }
        nip46Client = newNip46Client

        // Stay logged in immediately; the relay connection below is best-effort.
        // The UI shows "Reconnecting to signer..." while verifying. If the bunker
        // relay is offline we keep the user on this account and only mark the
        // signer disconnected — dropping the session here would force the user to
        // re-paste the bunker URI (issue #85).
        _isLoggedIn.value = true
        _isBunkerVerifying.value = true
        _bunkerState.value = BunkerState.Connected

        if (savedClientPrivateKey == null) {
            SecureStorage.saveBunkerClientPrivateKey(newNip46Client.clientPrivateKey)
            SecureStorage.saveBunkerClientPrivateKeyFor(
                savedUserPubkey,
                newNip46Client.clientPrivateKey,
            )
        }

        // The slow relay connect + identity verification run async so a hung or
        // offline bunker never blocks startup or tears down the session. Mirrors
        // installBunkerClient (the multi-account path).
        authScope.launch {
            try {
                newNip46Client.connectRelaysOnly(bunkerInfo.pubkey, bunkerInfo.relays)
            } catch (_: Exception) {
                // Relay unreachable — keep the user signed in and surface the
                // disconnected state so the UI can offer a one-tap reconnect.
                if (nip46Client === newNip46Client) {
                    _isBunkerVerifying.value = false
                    enterUnreachable(BunkerUnreachableReason.RelaysUnreachable)
                }
                return@launch
            }
            newNip46Client.backgroundConnect(
                secret = bunkerInfo.secret,
                onSuccess = {
                    if (nip46Client === newNip46Client) _isBunkerVerifying.value = false
                },
                onRevoked = {
                    // Couldn't reach or verify the signer on restore — stay
                    // logged in and let the banner offer a retry. We can't tell
                    // an offline signer from a deleted connection apart, so we
                    // never drop the session here (issue #85).
                    if (nip46Client !== newNip46Client) return@backgroundConnect
                    markSignerUnreachable()
                },
            )
        }

        return true
    }

    private fun keyPairOrNull(privateKeyHex: String): KeyPair? = try {
        KeyPair.fromPrivateKeyHex(privateKeyHex)
    } catch (_: Exception) {
        null
    }

    private fun restorePrivateKeySession(privateKeyHex: String): Boolean = try {
        keyPair = KeyPair.fromPrivateKeyHex(privateKeyHex)
        isBunkerLogin = false
        _isLoggedIn.value = true
        true
    } catch (e: Exception) {
        SecureStorage.clearPrivateKey()
        false
    }

    /**
     * Bring the bunker back online on demand (banner "Reconnect" button, or any
     * caller that needs a live signer). Runs a single bounded attempt; if it
     * fails, leaves background auto-reconnect running so recovery continues
     * without further user action.
     */
    suspend fun ensureBunkerConnected(): Boolean {
        if (!isBunkerLogin) return true
        if (nip46Client != null && _bunkerState.value is BunkerState.Connected) return true
        val ok = attemptReconnect()
        if (!ok) startAutoReconnect()
        return ok
    }

    /**
     * One mutex-guarded reconnect attempt. Holds [BunkerState.Reconnecting] for
     * the whole attempt so the banner spinner can never outlive it, then settles
     * on [BunkerState.Connected] or [BunkerState.Unreachable] with a precise
     * reason. Concurrent callers (button + auto loop) serialize here.
     */
    private suspend fun attemptReconnect(): Boolean = reconnectMutex.withLock {
        if (!isBunkerLogin) return@withLock false
        if (nip46Client != null && _bunkerState.value is BunkerState.Connected) {
            return@withLock true
        }

        _bunkerState.value = BunkerState.Reconnecting
        val reason = doReconnect()
        if (reason == null) {
            stopAutoReconnect()
            _bunkerState.value = BunkerState.Connected
            true
        } else {
            // Settle directly (not via enterUnreachable) so an attempt made
            // from inside the auto loop doesn't restart the loop; the caller
            // decides whether to keep retrying.
            _bunkerState.value = BunkerState.Unreachable(reason)
            false
        }
    }

    /**
     * Build a fresh client from the saved bunker URL and connect it in two
     * bounded phases so a hang in either is short and distinguishable:
     *  1. relays — [Nip46Client.connectRelaysOnly], capped at
     *     [RELAY_CONNECT_TIMEOUT_MS]; failure ⇒ [BunkerUnreachableReason.RelaysUnreachable].
     *  2. signer — [Nip46Client.backgroundConnect], capped at
     *     [SIGNER_CONNECT_TIMEOUT_MS]; no ack ⇒ [BunkerUnreachableReason.SignerNotResponding].
     * Returns null on success, otherwise the failure reason.
     */
    private suspend fun doReconnect(): BunkerUnreachableReason? {
        val activePubkey = bunkerUserPubkey
        val savedBunkerUrl =
            activePubkey?.let { SecureStorage.getBunkerUrlFor(it) }
                ?: SecureStorage.getBunkerUrl()
                ?: return BunkerUnreachableReason.Unknown
        val savedClientPrivateKey =
            activePubkey?.let { SecureStorage.getBunkerClientPrivateKeyFor(it) }
                ?: SecureStorage.getBunkerClientPrivateKey()
        val info =
            try {
                parseBunkerUrl(savedBunkerUrl)
            } catch (_: Exception) {
                return BunkerUnreachableReason.Unknown
            }

        val client =
            if (savedClientPrivateKey != null) {
                Nip46Client(savedClientPrivateKey)
            } else {
                Nip46Client()
            }
        client.onAuthUrl = { url -> _authUrl.value = url }

        val relaysOk =
            withTimeoutOrNull(RELAY_CONNECT_TIMEOUT_MS) {
                try {
                    client.connectRelaysOnly(info.pubkey, info.relays)
                    true
                } catch (_: Exception) {
                    false
                }
            } ?: false
        if (!relaysOk) {
            client.disconnect()
            return BunkerUnreachableReason.RelaysUnreachable
        }

        val authorized = awaitSignerAuthorize(client, info.secret)
        if (!authorized) {
            client.disconnect()
            return BunkerUnreachableReason.SignerNotResponding
        }

        nip46Client = client
        if (savedClientPrivateKey == null) {
            SecureStorage.saveBunkerClientPrivateKey(client.clientPrivateKey)
            activePubkey?.let {
                SecureStorage.saveBunkerClientPrivateKeyFor(it, client.clientPrivateKey)
            }
        }
        return null
    }

    /**
     * Fire the NIP-46 "connect" RPC and wait (bounded) for the signer to
     * re-authorize. [Nip46Client.backgroundConnect] already self-times-out and
     * reports back via callbacks; the outer deadline is a safety net.
     */
    private suspend fun awaitSignerAuthorize(
        client: Nip46Client,
        secret: String?,
    ): Boolean {
        val authorized = CompletableDeferred<Boolean>()
        client.backgroundConnect(
            secret = secret,
            onSuccess = { authorized.complete(true) },
            onRevoked = { authorized.complete(false) },
        )
        return withTimeoutOrNull(SIGNER_CONNECT_TIMEOUT_MS) { authorized.await() } ?: false
    }

    /** Start capped-backoff background reconnect (idempotent while already running). */
    private fun startAutoReconnect() {
        if (autoReconnectJob?.isActive == true) return
        autoReconnectJob =
            authScope.launch {
                var delayMs = AUTO_RECONNECT_BASE_MS
                repeat(AUTO_RECONNECT_MAX_ATTEMPTS) {
                    delay(delayMs)
                    if (!isBunkerLogin || _bunkerState.value is BunkerState.Connected) {
                        return@launch
                    }
                    if (attemptReconnect()) return@launch
                    delayMs = (delayMs * 2).coerceAtMost(AUTO_RECONNECT_MAX_MS)
                }
            }
    }

    private fun stopAutoReconnect() {
        autoReconnectJob?.cancel()
        autoReconnectJob = null
    }

    /**
     * Sign an event using the active account's isolated [NostrSigner].
     *
     * Routes through [ActiveAccountManager] first so the correct signer is
     * always used regardless of which account is active. Falls back to the
     * inline auth state for the initial login flow, before the session is
     * activated.
     */
    suspend fun signEvent(
        event: Event,
        interactive: Boolean = true,
    ): Event {
        val sessionSigner = ActiveAccountManager.session.value?.signer
        if (sessionSigner != null) {
            return try {
                sessionSigner.signEvent(event).also {
                    if (sessionSigner is NostrSigner.Bunker) noteBunkerSignSuccess()
                }
            } catch (e: NostrSigner.SigningException) {
                if (isPermissionError(e)) {
                    if (sessionSigner is NostrSigner.Bunker) {
                        // An explicit permission rejection is deterministic evidence the
                        // signer revoked this connection — not the transient relay hiccup
                        // the interactive gate guards against — so raise the banner even
                        // for background signs (NIP-42 AUTH). A reading-only session has
                        // no interactive signs: without this, AUTH quietly fails and the
                        // feed goes deaf with no cue. Never log out: the banner offers
                        // Reconnect/Log out, and its copy points at the signer app.
                        markSignerUnreachable(BunkerUnreachableReason.PermissionDenied)
                        throw Exception("Your signer refused to sign. Check its permissions.")
                    }
                    handlePermissionDenied()
                    throw Exception("Signing permission denied. Please login again.")
                }
                if (sessionSigner is NostrSigner.Bunker) noteBunkerSignFailure(interactive && !isRateLimitThrottle(e))
                throw e
            }
        }
        // Fallback: session not yet activated (e.g. initial login in progress).
        return when {
            isNip07Login -> signWithNip07(event)
            isAmberLogin -> signWithAmber(event)
            isBunkerLogin -> signWithBunker(event, interactive)
            else -> signWithKeyPair(event)
        }
    }

    private suspend fun signWithNip07(event: Event): Event {
        val eventJson = event.toJsonString()
        val signedJson = Nip07.signEvent(eventJson)
        return parseSignedEvent(signedJson)
    }

    private suspend fun signWithAmber(event: Event): Event {
        val pubkey = amberUserPubkey ?: throw Exception("Amber signer not connected")
        val signedJson = Nip55.signEvent(event.toJsonString(), pubkey, SecureStorage.getNip55SignerPackageFor(pubkey))
        return parseSignedEvent(signedJson)
    }

    private suspend fun signWithBunker(
        event: Event,
        interactive: Boolean = true,
    ): Event {
        // Fast-fail when the bunker is known disconnected. Otherwise every
        // NIP-42 AUTH challenge from a NIP-29 relay piles up a 120s sign
        // attempt: N relays = N concurrent hung coroutines doing crypto on
        // the JS main thread, which freezes the browser tab.
        if (_bunkerState.value !is BunkerState.Connected) {
            throw Exception("Bunker not connected")
        }
        if (nip46Client == null) {
            val reconnected = attemptReconnect()
            if (!reconnected) {
                throw Exception("Bunker not connected and reconnection failed")
            }
        }

        val bunker = nip46Client ?: throw Exception("Bunker not connected")

        try {
            val eventJson = event.toJsonString()
            val signedEventJson = bunker.signEvent(eventJson)
            noteBunkerSignSuccess()
            return parseSignedEvent(signedEventJson)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (isPermissionError(e)) {
                // Deterministic revocation — banner even for background signs (see
                // signEvent), with copy that points at the signer app's permissions.
                markSignerUnreachable(BunkerUnreachableReason.PermissionDenied)
                throw Exception("Your signer refused to sign. Check its permissions.")
            }
            noteBunkerSignFailure(interactive && !isRateLimitThrottle(e))
            throw e
        }
    }

    // A rate-limited bunker publish is the relay asking us to slow down, not evidence the
    // signer is gone. An interactive teardown here starts a reconnect loop whose fresh
    // client re-bursts and gets rate-limited again (devices stuck cycling "can't reach
    // signer" while the first one works), so a throttle only counts toward the
    // background failure streak.
    private fun isRateLimitThrottle(e: Exception): Boolean = org.nostr.nostrord.nostr.Nip46PublishPacer.isRateLimitReason(e.message ?: "")

    private fun signWithKeyPair(event: Event): Event {
        val kp = keyPair ?: throw Exception("Not logged in")
        return event.sign(kp)
    }

    private fun isPermissionError(e: Exception): Boolean {
        val msg = e.message?.lowercase() ?: return false
        return msg.contains("no permission") ||
            msg.contains("not authorized") ||
            msg.contains("permission denied")
    }

    /**
     * Bunker sign failures that are NOT explicit permission rejections (timeouts,
     * transport errors). One background failure is no evidence — NIP-42 AUTH
     * re-challenges hit transient hiccups all the time (the banner-flicker guard) —
     * but a signer that keeps not answering while nothing succeeds is revoked or
     * offline, and a reading-only session never signs interactively to find out:
     * AUTH quietly fails and auth-gated relays withhold events with no cue. The
     * banner is raised only when [BUNKER_SIGN_FAILURE_BANNER_THRESHOLD] consecutive
     * failures ALSO span [BUNKER_SIGN_FAILURE_BANNER_WINDOW_MS] — a multi-relay AUTH
     * burst reaches the count in seconds during one hiccup and must not trip it. An
     * interactive failure raises it immediately: the user just watched the action
     * fail, and the banner carries the recovery path (Reconnect).
     */
    private var consecutiveBunkerSignFailures = 0
    private var bunkerSignFailStreakStartMs = 0L
    private var bunkerSignLastFailureAtMs = 0L

    private fun noteBunkerSignSuccess() {
        consecutiveBunkerSignFailures = 0
        bunkerSignFailStreakStartMs = 0L
    }

    private fun noteBunkerSignFailure(interactive: Boolean) {
        // A signer that answered with an auth_url is alive and waiting on the USER
        // (approval screen); failures racked up during that wait prove nothing.
        if (_authUrl.value != null) return
        val now = epochMillis()
        if (consecutiveBunkerSignFailures == 0 || now - bunkerSignLastFailureAtMs > BUNKER_SIGN_FAILURE_STREAK_RESET_MS) {
            consecutiveBunkerSignFailures = 0
            bunkerSignFailStreakStartMs = now
        }
        bunkerSignLastFailureAtMs = now
        consecutiveBunkerSignFailures++
        val streakSpansWindow = now - bunkerSignFailStreakStartMs >= BUNKER_SIGN_FAILURE_BANNER_WINDOW_MS
        if (interactive || (consecutiveBunkerSignFailures >= BUNKER_SIGN_FAILURE_BANNER_THRESHOLD && streakSpansWindow)) {
            markSignerUnreachable()
        }
    }

    /**
     * The bunker signer can't be reached or refused a request. The app cannot
     * tell "signer offline" from "connection deleted on the signer" apart — both
     * surface as a timeout or an error — so this NEVER logs the user out or wipes
     * credentials. It only marks the signer disconnected; [BunkerStatusBanner]
     * then explains the situation and lets the user choose to reconnect or log
     * out. (issue #85)
     */
    private fun markSignerUnreachable(
        reason: BunkerUnreachableReason = BunkerUnreachableReason.SignerNotResponding,
    ) {
        // Fresh window after a reconnect: a still-broken signer re-trips the
        // threshold instead of instantly re-raising the banner on its first miss.
        consecutiveBunkerSignFailures = 0
        nip46Client?.disconnect()
        nip46Client = null
        _isBunkerVerifying.value = false
        enterUnreachable(reason)
        // isBunkerLogin, bunkerUserPubkey, the stored bunker URL and isLoggedIn
        // are left intact so the reconnect path can re-read the saved URL on retry.
    }

    /** Enter the Unreachable state and start capped-backoff background reconnect. */
    private fun enterUnreachable(reason: BunkerUnreachableReason) {
        _bunkerState.value = BunkerState.Unreachable(reason)
        startAutoReconnect()
    }

    private fun handlePermissionDenied() {
        // Capture the invalidated account's pubkey BEFORE clearing in-memory
        // state so the host's fallback can act on it.
        val invalidatedPubkey = bunkerUserPubkey ?: getPublicKey()

        nip46Client?.disconnect()
        nip46Client = null
        stopAutoReconnect()
        _bunkerState.value = BunkerState.Inactive
        clearBunkerCredentials()
        isBunkerLogin = false
        bunkerUserPubkey = null
        // Flip immediately so the resolve() in App.kt does not briefly land in
        // Authenticated (gray-screen flicker) while the async callback below is
        // still running. If the callback successfully activates a fallback
        // account, installBunkerClient / useAccount will set this back to true.
        _isLoggedIn.value = false

        val callback = onSessionInvalidated
        if (callback != null) {
            authScope.launch {
                try {
                    callback(invalidatedPubkey)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun parseSignedEvent(jsonString: String): Event {
        val json = Json { ignoreUnknownKeys = true }
        val obj = json.parseToJsonElement(jsonString).jsonObject

        return Event(
            id = obj["id"]?.jsonPrimitive?.content,
            pubkey = obj["pubkey"]?.jsonPrimitive?.content ?: "",
            createdAt = obj["created_at"]?.jsonPrimitive?.long ?: 0L,
            kind = obj["kind"]?.jsonPrimitive?.int ?: 0,
            tags =
            obj["tags"]?.jsonArray?.map { tag ->
                tag.jsonArray.map { it.jsonPrimitive.content }
            } ?: emptyList(),
            content = obj["content"]?.jsonPrimitive?.content ?: "",
            sig = obj["sig"]?.jsonPrimitive?.content,
        )
    }

    /**
     * Logout - clear all auth state and forget the active account.
     *
     * Wipes credentials for the active account (both legacy slots and the
     * pubkey-scoped slot) and removes the Account record so re-login starts
     * fresh. Other accounts in the store, if any, are left intact.
     */
    fun logout() {
        val activePubkey = accountStore.active?.pubkey ?: getPublicKey()

        nip46Client?.disconnect()
        nip46Client = null
        isBunkerLogin = false
        bunkerUserPubkey = null
        isNip07Login = false
        nip07UserPubkey = null
        isAmberLogin = false
        amberUserPubkey = null
        zeroAndClearKeyPair()

        _isLoggedIn.value = false
        stopAutoReconnect()
        _bunkerState.value = BunkerState.Inactive
        // Reset the verifying flag so a hung backgroundConnect does not keep
        // the UI stuck on "Reconnecting to signer..." after logout completes.
        _isBunkerVerifying.value = false

        SecureStorage.clearPrivateKey()
        SecureStorage.clearBunkerUrl()
        SecureStorage.clearBunkerUserPubkey()
        SecureStorage.clearNip07UserPubkey()
        // Keep legacy client key for re-login.

        if (!activePubkey.isNullOrBlank()) {
            SecureStorage.clearAllCredentialsForAccount(activePubkey)
            accountStore.remove(activePubkey)
        }
    }

    /**
     * Completely forget bunker connection
     */
    fun forgetBunkerConnection() {
        nip46Client?.disconnect()
        nip46Client = null
        isBunkerLogin = false
        bunkerUserPubkey = null
        zeroAndClearKeyPair()
        stopAutoReconnect()
        _bunkerState.value = BunkerState.Inactive
        clearBunkerCredentials()
        SecureStorage.clearBunkerClientPrivateKey()
    }

    private fun clearBunkerCredentials() {
        SecureStorage.clearBunkerUrl()
        SecureStorage.clearBunkerUserPubkey()
    }

    /**
     * Set logged in state (called by repository after connections established)
     */
    fun setLoggedIn(value: Boolean) {
        _isLoggedIn.value = value
    }
}
