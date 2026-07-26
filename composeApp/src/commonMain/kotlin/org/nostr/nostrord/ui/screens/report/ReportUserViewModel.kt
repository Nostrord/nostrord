package org.nostr.nostrord.ui.screens.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepositoryApi
import org.nostr.nostrord.nostr.Nip56
import org.nostr.nostrord.utils.Result

/** One selectable NIP-56 reason: wire type + the UI copy shared by both UIs. */
data class ReportReason(
    val type: Nip56.ReportType,
    val label: String,
    val hint: String,
)

/** Prototype ReportModal order; labels/hints rendered identically on web and Compose. */
val REPORT_REASONS: List<ReportReason> = listOf(
    ReportReason(Nip56.ReportType.SPAM, "Spam", "Unwanted promotional content or repetitive posting."),
    ReportReason(Nip56.ReportType.NUDITY, "Nudity or sexual content", "Explicit or sexual material."),
    ReportReason(Nip56.ReportType.PROFANITY, "Harassment or abuse", "Hate speech, threats, or abusive language."),
    ReportReason(Nip56.ReportType.ILLEGAL, "Illegal content", "Content that may violate the law."),
    ReportReason(Nip56.ReportType.IMPERSONATION, "Impersonation", "Pretending to be someone else."),
    ReportReason(Nip56.ReportType.MALWARE, "Malware or scam", "Phishing links, scams, or malicious software."),
    ReportReason(Nip56.ReportType.OTHER, "Other", "Something else that doesn't fit the categories above."),
)

/**
 * Shared logic for the report modal (NIP-56 kind:1984): reason selection, optional
 * note, the "also mute" toggle (on by default, moot when already muted), and the
 * send that publishes the report and optionally mutes in one go.
 */
class ReportUserViewModel(
    private val repo: NostrRepositoryApi,
    private val targetPubkey: String,
    private val eventId: String? = null,
) : ViewModel() {
    enum class Phase { Editing, Sending, Sent }

    private val _selected = MutableStateFlow<Nip56.ReportType?>(null)
    val selected: StateFlow<Nip56.ReportType?> = _selected

    private val _note = MutableStateFlow("")
    val note: StateFlow<String> = _note

    private val _alsoMute = MutableStateFlow(true)
    val alsoMute: StateFlow<Boolean> = _alsoMute

    private val _phase = MutableStateFlow(Phase.Editing)
    val phase: StateFlow<Phase> = _phase

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** Hides the "also mute" toggle when the target is muted before the modal opens. */
    val targetAlreadyMuted: StateFlow<Boolean> =
        repo.mutedPubkeys
            .map { targetPubkey in it }
            .stateIn(viewModelScope, SharingStarted.Eagerly, targetPubkey in repo.mutedPubkeys.value)

    fun select(type: Nip56.ReportType) {
        if (_phase.value == Phase.Editing) _selected.value = type
    }

    fun setNote(value: String) {
        _note.value = value
    }

    fun toggleAlsoMute() {
        _alsoMute.value = !_alsoMute.value
    }

    /** True when the send also muted the target (drives the success copy). */
    val didMute: StateFlow<Boolean> get() = _didMute
    private val _didMute = MutableStateFlow(false)

    /** Clears the form for the next open (the Compose VM outlives the closed modal). */
    fun reset() {
        _selected.value = null
        _note.value = ""
        _alsoMute.value = true
        _phase.value = Phase.Editing
        _error.value = null
        _didMute.value = false
    }

    fun send() {
        val type = _selected.value ?: return
        if (_phase.value != Phase.Editing) return
        _phase.value = Phase.Sending
        _error.value = null
        viewModelScope.launch {
            when (val result = repo.reportUser(targetPubkey, type, _note.value, eventId)) {
                is Result.Success -> {
                    if (_alsoMute.value && !targetAlreadyMuted.value) {
                        // Best-effort: the report already went out even if the mute fails.
                        _didMute.value = repo.muteUser(targetPubkey) is Result.Success
                    }
                    _phase.value = Phase.Sent
                }
                is Result.Error -> {
                    _error.value = result.error.message.ifBlank { "Could not send the report." }
                    _phase.value = Phase.Editing
                }
            }
        }
    }
}
