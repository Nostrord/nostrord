package org.nostr.nostrord.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepositoryApi
import org.nostr.nostrord.network.managers.DmArchiveManager
import org.nostr.nostrord.network.managers.DmEncryptionManager
import org.nostr.nostrord.network.managers.DmPairingManager
import org.nostr.nostrord.utils.Result

/**
 * Shared logic for the Settings "Fast decryption key" block (NIP-4e). Both UIs consume this VM.
 *
 * The key is announced so senders address it instead of the account's identity key, which lets
 * inbound messages decrypt on this device rather than through the remote signer. It is offered
 * only where that helps: accounts whose signing is already local gain nothing and see nothing
 * ([DmEncryptionManager.State.Unavailable] -> [visible] false).
 */
class DmEncryptionViewModel(
    private val repo: NostrRepositoryApi,
) : ViewModel() {
    val state: StateFlow<DmEncryptionManager.State> = repo.dmEncryptionState

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** The private key, once the user asked to see it in order to move it to another device. */
    private val _revealedKey = MutableStateFlow<String?>(null)
    val revealedKey: StateFlow<String?> = _revealedKey.asStateFlow()

    private val _importInput = MutableStateFlow("")
    val importInput: StateFlow<String> = _importInput.asStateFlow()

    /** False hides the whole block: nothing here would speed up a locally signing account. */
    val visible: Boolean get() = state.value !is DmEncryptionManager.State.Unavailable

    init {
        // What the relays announce is the truth about this account, and this device's stored state
        // can be a session old. Asking on open is what surfaces a key another device announced.
        viewModelScope.launch { repo.refreshDmEncryptionState() }
    }

    fun clearError() {
        _error.value = null
    }

    fun setImportInput(value: String) {
        _importInput.value = value
        _error.value = null
    }

    fun enable() {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            when (val result = repo.enableDmEncryption()) {
                is Result.Error -> _error.value = result.error.message
                else -> {}
            }
            _busy.value = false
        }
    }

    /** Stops advertising the key. The key itself stays, or its message history stops opening. */
    fun disable() {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            when (val result = repo.disableDmEncryption()) {
                is Result.Error -> _error.value = result.error.message
                else -> {}
            }
            _busy.value = false
        }
    }

    /**
     * Advertise a fresh key. Offered for the case where the current one may have leaked: the old
     * key is kept, so nothing already received stops opening.
     */
    fun rotate() {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            when (val result = repo.rotateDmEncryptionKey()) {
                is Result.Error -> _error.value = result.error.message
                else -> _revealedKey.value = null
            }
            _busy.value = false
        }
    }

    private val _resetConfirmOpen = MutableStateFlow(false)
    val resetConfirmOpen: StateFlow<Boolean> = _resetConfirmOpen.asStateFlow()

    /** Confirmed first: a reset abandons the announced key, and nothing sent to it opens again. */
    fun askToReset() {
        _resetConfirmOpen.value = true
    }

    fun dismissResetConfirm() {
        _resetConfirmOpen.value = false
    }

    /**
     * Take over the announcement when the device that made it is gone. Without this the account is
     * stuck: senders keep addressing a key nobody here holds, so inbound messages never open.
     */
    fun confirmReset() {
        _resetConfirmOpen.value = false
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            when (val result = repo.resetDmEncryptionKey()) {
                is Result.Error -> _error.value = result.error.message
                else -> {
                    // The request aimed at the lost device can no longer be answered.
                    repo.dismissDmPairing()
                    _importInput.value = ""
                }
            }
            _busy.value = false
        }
    }

    fun revealKey() {
        _revealedKey.value = repo.exportDmEncryptionKey()
        if (_revealedKey.value == null) _error.value = "There is no key on this device yet."
    }

    fun hideKey() {
        _revealedKey.value = null
    }

    val archiveProgress: StateFlow<DmArchiveManager.Progress> = repo.dmArchiveProgress

    /** How many stored messages still need an archive copy; null until counted. */
    private val _archivableCount = MutableStateFlow<Int?>(null)
    val archivableCount: StateFlow<Int?> = _archivableCount.asStateFlow()

    private val _archiveConfirmOpen = MutableStateFlow(false)
    val archiveConfirmOpen: StateFlow<Boolean> = _archiveConfirmOpen.asStateFlow()

    /** Count first: the confirmation has to state the volume being published before anything is. */
    fun askToArchive() {
        _archiveConfirmOpen.value = true
        viewModelScope.launch { _archivableCount.value = repo.countDmArchivableMessages() }
    }

    fun dismissArchiveConfirm() {
        _archiveConfirmOpen.value = false
    }

    fun confirmArchive() {
        _archiveConfirmOpen.value = false
        _error.value = null
        viewModelScope.launch {
            when (val result = repo.archiveDmHistory()) {
                is Result.Error -> _error.value = result.error.message
                else -> _archivableCount.value = repo.countDmArchivableMessages()
            }
        }
    }

    fun cancelArchive() {
        repo.cancelDmArchive()
    }

    val pairingState: StateFlow<DmPairingManager.State> = repo.dmPairingState

    /** Ask another device of this account for the key, instead of copying it by hand. */
    fun requestKeyFromOtherDevice() {
        _error.value = null
        viewModelScope.launch {
            when (val result = repo.requestDmEncryptionKey()) {
                is Result.Error -> _error.value = result.error.message
                else -> {}
            }
        }
    }

    fun approvePairing() {
        _error.value = null
        viewModelScope.launch {
            when (val result = repo.approveDmPairing()) {
                is Result.Error -> _error.value = result.error.message
                else -> {}
            }
        }
    }

    fun declinePairing() {
        repo.declineDmPairing()
    }

    fun dismissPairing() {
        repo.dismissDmPairing()
    }

    fun importKey() {
        val input = _importInput.value.trim()
        if (input.isEmpty()) return
        if (!repo.importDmEncryptionKey(input)) {
            _error.value = "That key does not match the one this account announced."
            return
        }
        _importInput.value = ""
        _error.value = null
    }
}
