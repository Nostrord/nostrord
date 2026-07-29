package org.nostr.nostrord.ui.screens.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nostr.nostrord.auth.AccountManager
import org.nostr.nostrord.auth.AccountStore
import org.nostr.nostrord.auth.AuthMethod
import org.nostr.nostrord.auth.pomegranate.PomegranateOperator
import org.nostr.nostrord.auth.pomegranate.PomegranatePopupClosedException
import org.nostr.nostrord.auth.pomegranate.PomegranateService
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.NostrRepositoryApi
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.nostr.Nip49
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.clearPomegranateCentralFor
import org.nostr.nostrord.storage.loadPomegranateCentralFor
import org.nostr.nostrord.storage.loadPomegranateDisconnectedFor
import org.nostr.nostrord.storage.markPomegranateDisconnectedFor
import org.nostr.nostrord.ui.Identifier
import org.nostr.nostrord.ui.nprofileRelayHints

/** Minimum length for the ncryptsec backup password, shared by the VM and both UIs. */
const val MIN_BACKUP_PASSWORD = 6

/** The Backup screen's security tips, shared so every platform shows the same set. */
val backupSecurityTips: List<String> =
    listOf(
        "Never share your private key (nsec) with anyone.",
        "Save it in a password manager, or write it down and store it offline.",
        "Never keep it in plain text files or screenshots.",
        "Never send it over email or messaging apps.",
        "Anyone with your nsec has full control of your account.",
    )

/**
 * Shared state + actions for the "Backup keys" screen, so the Compose and web UIs render the same
 * thing instead of drifting. Both expose the keys through the cycling identifier field:
 *  - the public key cycles npub / nprofile / hex (non-sensitive, shown immediately);
 *  - the private key (LOCAL accounts only) is reveal-gated, then cycles nsec / hex / ncryptsec.
 *
 * ncryptsec (NIP-49) is a password-encrypted export and so can't be a plain cycle value: the UI
 * collects a passphrase and calls [encrypt], which fills [ncryptsec]. Bunker (NIP-46) and NIP-07
 * accounts hold no local key, so [canExportPrivate] is false and the UI shows an explainer instead.
 */
class BackupViewModel(
    private val repo: NostrRepositoryApi = AppModule.nostrRepository,
    val authMethod: AuthMethod? = AppModule.accountStore.active?.authMethod,
    private val cryptoDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val pomegranate: PomegranateService = PomegranateService(),
    private val accountManager: AccountManager = AppModule.accountManager,
    private val accountStore: AccountStore = AppModule.accountStore,
) : ViewModel() {
    /** npub (default) / nprofile / hex. Empty when signed out. */
    val publicIds: List<Identifier> =
        repo.getPublicKey()?.let { hex ->
            val hints = nprofileRelayHints(repo.getRelayListForPubkey(hex).orEmpty())
            buildList {
                runCatching { Nip19.encodeNpub(hex) }.getOrNull()?.let { add(Identifier("npub", it)) }
                runCatching { Nip19.encodeNprofile(hex, hints) }.getOrNull()?.let { add(Identifier("nprofile", it)) }
                add(Identifier("hex", hex))
            }
        } ?: emptyList()

    /** Only LOCAL accounts hold the key; bunker / NIP-07 sign remotely and expose nothing. */
    val canExportPrivate: Boolean = repo.getPrivateKey() != null

    /** Central-server origin when this bunker account came from Login with Google (pomegranate). */
    val pomegranateCentral: String? =
        repo.getPublicKey()?.let { SecureStorage.loadPomegranateCentralFor(it) }

    /** True when this account's pomegranate signer was deliberately deleted: it can never sign again. */
    val pomegranateDisconnected: Boolean
        get() = repo.getPublicKey()?.let { SecureStorage.loadPomegranateDisconnectedFor(it) } ?: false

    private val _revealed = MutableStateFlow(false)
    val revealed: StateFlow<Boolean> = _revealed.asStateFlow()

    private val _passphrase = MutableStateFlow("")
    val passphrase: StateFlow<String> = _passphrase.asStateFlow()

    private val _ncryptsec = MutableStateFlow<String?>(null)
    val ncryptsec: StateFlow<String?> = _ncryptsec.asStateFlow()

    private val _encrypting = MutableStateFlow(false)
    val encrypting: StateFlow<Boolean> = _encrypting.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun reveal() {
        _revealed.value = true
    }

    /** Re-mask everything, dropping any generated ncryptsec and its passphrase. */
    fun hide() {
        _revealed.value = false
        _passphrase.value = ""
        _ncryptsec.value = null
        _error.value = null
    }

    fun setPassphrase(value: String) {
        _passphrase.value = value
        _error.value = null
        // A changed passphrase invalidates a previously generated ncryptsec.
        _ncryptsec.value = null
    }

    /** Direct (unencrypted) private formats: nsec then hex. Only meaningful after [reveal]. */
    fun privateDirectIds(): List<Identifier> {
        val hex = repo.getPrivateKey() ?: return emptyList()
        return buildList {
            runCatching { Nip19.encodeNsec(hex) }.getOrNull()?.let { add(Identifier("nsec", it)) }
            add(Identifier("hex", hex))
        }
    }

    /** NIP-49 encrypt the private key (local, or a finished pomegranate export) under the current [passphrase]. */
    fun encrypt() {
        if (_encrypting.value) return
        val hex = repo.getPrivateKey() ?: (_pomExport.value as? PomegranateExport.Done)?.hex ?: return
        val pw = _passphrase.value
        if (pw.length < MIN_BACKUP_PASSWORD) {
            _error.value = "Use at least $MIN_BACKUP_PASSWORD characters."
            return
        }
        _error.value = null
        _encrypting.value = true
        viewModelScope.launch {
            val result =
                withContext(cryptoDispatcher) {
                    runCatching { Nip49.encrypt(hex.hexToByteArray(), pw) }
                }
            _encrypting.value = false
            result.fold(
                onSuccess = { _ncryptsec.value = it },
                onFailure = { _error.value = "Could not encrypt the key." },
            )
        }
    }

    // --- Pomegranate (Login with Google) export / disconnect --------------------

    /** One operator's row in the export flow. */
    data class OperatorExport(
        val operator: PomegranateOperator,
        val status: ShardStatus,
    ) {
        /** Short host label for the row ("po.njump.me"). */
        val host: String get() = operator.url.substringAfter("://")
    }

    enum class ShardStatus { Pending, Recovering, Recovered, Failed }

    sealed interface PomegranateExport {
        data object Idle : PomegranateExport

        /** Google popup open; verifying the account on the central server. */
        data object Authing : PomegranateExport

        data class Recovering(
            val operators: List<OperatorExport>,
            val threshold: Int,
            val recovered: Int,
        ) : PomegranateExport

        data class Done(
            val nsec: String,
            val hex: String,
        ) : PomegranateExport
    }

    sealed interface PomegranateDisconnect {
        data object Idle : PomegranateDisconnect

        data object Working : PomegranateDisconnect

        /** [convertedToLocal] when the exported key was adopted in place and the account keeps signing. */
        data class Done(val convertedToLocal: Boolean) : PomegranateDisconnect
    }

    /** Callout shown in the disconnect confirmation; [alert] picks the warning styling. */
    data class DisconnectNotice(
        val title: String,
        val body: String,
        val alert: Boolean,
    )

    /**
     * Confirmation copy, keyed on whether the nsec was exported in this session. With the
     * key in hand the account converts to a local-key login and the user stays signed in;
     * without it the account can no longer sign, so it is signed out and only its nsec
     * (recovered elsewhere) can bring it back.
     */
    fun pomDisconnectNotice(keyExported: Boolean): DisconnectNotice = if (keyExported) {
        DisconnectNotice(
            title = "You stay signed in",
            body =
            "You exported this account's private key, so disconnecting only removes the link to the " +
                "central server: the account keeps working here and signs with that key locally. Keep " +
                "your backup of the nsec safe, it is the only way to sign in elsewhere.",
            alert = false,
        )
    } else {
        DisconnectNotice(
            title = "Keep your private key safe",
            body =
            "Disconnecting only removes the link between this account and the central server. Your " +
                "account still exists, and you can keep using it by logging in with your private key " +
                "(nsec). Before continuing, export and safely save your nsec using the \"Export private " +
                "key\" option: without it here, this account is signed out and cannot sign again.",
            alert = true,
        )
    }

    /** Copy for the finished disconnect, mirroring the two outcomes of [pomDisconnectNotice]. */
    fun pomDisconnectedNotice(convertedToLocal: Boolean): DisconnectNotice = if (convertedToLocal) {
        DisconnectNotice(
            title = "What happens next",
            body =
            "This account is no longer linked to the central server. It stays signed in here and " +
                "signs with the private key you exported.",
            alert = false,
        )
    } else {
        DisconnectNotice(
            title = "What happens next",
            body =
            "This account is no longer linked to the central server and is being signed out. To keep " +
                "using it, log in again with your private key (nsec).",
            alert = false,
        )
    }

    /** True once the export flow has reassembled the key, which decides the disconnect outcome. */
    val pomKeyExported: Boolean get() = _pomExport.value is PomegranateExport.Done

    private val _pomExport = MutableStateFlow<PomegranateExport>(PomegranateExport.Idle)
    val pomExport: StateFlow<PomegranateExport> = _pomExport.asStateFlow()

    private val _pomDisconnect = MutableStateFlow<PomegranateDisconnect>(PomegranateDisconnect.Idle)
    val pomDisconnect: StateFlow<PomegranateDisconnect> = _pomDisconnect.asStateFlow()

    private val _pomError = MutableStateFlow<String?>(null)
    val pomError: StateFlow<String?> = _pomError.asStateFlow()

    // Shard hexes recovered so far; wiped on cancel and once the nsec is built.
    private val recoveredShards = mutableListOf<String>()

    private var disconnectJob: Job? = null

    /**
     * Starts the nsec export: Google popup + account fetch, then the per-operator
     * recovery list. Must be called from a click so the popup is not blocked.
     */
    fun startPomegranateExport() {
        val central = pomegranateCentral ?: return
        val pubkey = repo.getPublicKey() ?: return
        if (_pomExport.value != PomegranateExport.Idle) return
        _pomError.value = null
        _pomExport.value = PomegranateExport.Authing
        viewModelScope.launch {
            try {
                val recovery = pomegranate.startRecovery(central, pubkey)
                recoveredShards.clear()
                _pomExport.value =
                    PomegranateExport.Recovering(
                        operators = recovery.account.operators.map { OperatorExport(it, ShardStatus.Pending) },
                        threshold = recovery.account.threshold,
                        recovered = 0,
                    )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _pomExport.value = PomegranateExport.Idle
                if (t !is PomegranatePopupClosedException) {
                    _pomError.value = t.message ?: "Could not start the key export"
                }
            }
        }
    }

    /**
     * Recovers one operator's shard via its popup. Any subset reaching the threshold
     * works, so failed operators can be retried or simply skipped. When the threshold
     * is met the shards are aggregated locally and verified against the account pubkey.
     */
    fun recoverPomegranateShard(operatorUrl: String) {
        val state = _pomExport.value as? PomegranateExport.Recovering ?: return
        if (state.operators.any { it.status == ShardStatus.Recovering }) return
        val target =
            state.operators.firstOrNull { it.operator.url == operatorUrl && it.status != ShardStatus.Recovered }
                ?: return
        _pomError.value = null
        setShardStatus(operatorUrl, ShardStatus.Recovering)
        viewModelScope.launch {
            try {
                val shard = pomegranate.recoverShard(target.operator)
                recoveredShards.add(shard)
                val cur = _pomExport.value as? PomegranateExport.Recovering ?: return@launch
                val ops = cur.operators.map { if (it.operator.url == operatorUrl) it.copy(status = ShardStatus.Recovered) else it }
                val done = ops.count { it.status == ShardStatus.Recovered }
                if (done >= cur.threshold) {
                    val pubkey = repo.getPublicKey() ?: return@launch
                    val shardsToAggregate = recoveredShards.toList()
                    val hex = withContext(cryptoDispatcher) { pomegranate.aggregateKeyHex(shardsToAggregate, pubkey) }
                    recoveredShards.clear()
                    _pomExport.value = PomegranateExport.Done(nsec = Nip19.encodeNsec(hex), hex = hex)
                } else {
                    _pomExport.value = cur.copy(operators = ops, recovered = done)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                if (t is PomegranatePopupClosedException) {
                    setShardStatus(operatorUrl, ShardStatus.Pending)
                } else {
                    setShardStatus(operatorUrl, ShardStatus.Failed)
                    _pomError.value = t.message ?: "Shard recovery failed"
                }
            }
        }
    }

    /** Direct formats of the exported pomegranate key: nsec then hex. Empty until [PomegranateExport.Done]. */
    fun pomDirectIds(): List<Identifier> {
        val done = _pomExport.value as? PomegranateExport.Done ?: return emptyList()
        return listOf(Identifier("nsec", done.nsec), Identifier("hex", done.hex))
    }

    /** Leaves the export flow and forgets recovered shards, the shown nsec and any ncryptsec. */
    fun cancelPomegranateExport() {
        recoveredShards.clear()
        _pomExport.value = PomegranateExport.Idle
        _pomError.value = null
        _passphrase.value = ""
        _ncryptsec.value = null
        _error.value = null
    }

    /**
     * Unlinks the account from the central server (Google re-auth + pubkey check,
     * then DELETE /account). Google login stops working for it everywhere. When the
     * export flow finished first ([PomegranateExport.Done] holds the key), the
     * account is converted to a LOCAL-key account in place and keeps signing with
     * the exported key; otherwise it becomes read-only and the disconnected
     * tombstone drives the "signer is gone" messaging.
     */
    fun disconnectPomegranate() {
        val central = pomegranateCentral ?: return
        val pubkey = repo.getPublicKey() ?: return
        if (_pomDisconnect.value == PomegranateDisconnect.Working) return
        _pomError.value = null
        _pomDisconnect.value = PomegranateDisconnect.Working
        disconnectJob =
            viewModelScope.launch {
                try {
                    pomegranate.disconnectAccount(central, pubkey)
                    // Past the server call the account is already unlinked, so the local
                    // swap has to finish even if the dialog closes: convertActiveToLocal
                    // ends in reloadForActiveAccount, and cancelling that leaves the
                    // joined-groups map empty, which reads as onboarding on an account
                    // that is still signed in.
                    withContext(NonCancellable) {
                        SecureStorage.clearPomegranateCentralFor(pubkey)
                        val exportedHex = (_pomExport.value as? PomegranateExport.Done)?.hex
                        val converted =
                            exportedHex != null && accountManager.convertActiveToLocal(exportedHex).isSuccess
                        if (!converted) SecureStorage.markPomegranateDisconnectedFor(pubkey)
                        _pomDisconnect.value = PomegranateDisconnect.Done(convertedToLocal = converted)
                    }
                } catch (c: CancellationException) {
                    // Leaving Working behind would wedge the dialog: the button stays on
                    // "Disconnecting..." and the guard above swallows every later click.
                    _pomDisconnect.value = PomegranateDisconnect.Idle
                    throw c
                } catch (t: Throwable) {
                    _pomDisconnect.value = PomegranateDisconnect.Idle
                    _pomError.value =
                        if (t is PomegranatePopupClosedException) {
                            "Google sign-in was closed before it finished."
                        } else {
                            t.message ?: "Could not disconnect from the central server"
                        }
                }
            }
    }

    /** Abandons a disconnect still waiting on the Google popup (the user left the dialog). */
    fun cancelPomegranateDisconnect() {
        // Nothing to abandon once the server call went through; the rest is local cleanup.
        if (_pomDisconnect.value is PomegranateDisconnect.Done) return
        disconnectJob?.cancel()
        disconnectJob = null
        if (_pomDisconnect.value == PomegranateDisconnect.Working) {
            _pomDisconnect.value = PomegranateDisconnect.Idle
        }
        _pomError.value = null
    }

    /**
     * Closes the disconnect flow. A converted account signs locally now, so it stays put;
     * one that never exported its key can no longer sign, so it is removed here (falling
     * back to another account, or to the login screen) rather than left unusable.
     */
    fun finishPomegranateDisconnect(onClosed: () -> Unit = {}) {
        val done = _pomDisconnect.value as? PomegranateDisconnect.Done
        val accountId = accountStore.activeId.value
        if (done == null || done.convertedToLocal || accountId == null) {
            onClosed()
            return
        }
        // Removal runs on the manager's scope: it outlives this screen, which the
        // account swap unmounts.
        accountManager.removeAccountAsync(accountId) { onClosed() }
    }

    private fun setShardStatus(
        operatorUrl: String,
        status: ShardStatus,
    ) {
        val cur = _pomExport.value as? PomegranateExport.Recovering ?: return
        _pomExport.value =
            cur.copy(operators = cur.operators.map { if (it.operator.url == operatorUrl) it.copy(status = status) else it })
    }
}
