package org.nostr.nostrord.ui.screens.dm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepositoryApi
import org.nostr.nostrord.network.managers.DmConversation
import org.nostr.nostrord.network.managers.DmMessage
import org.nostr.nostrord.ui.screens.withMinDuration
import org.nostr.nostrord.utils.Result

/**
 * Shared DM screen logic (commonMain): the conversation list, per-peer message threads, and
 * sending. Both the web DM screens and the native ones consume this same VM so behavior stays in
 * one place. NIP-17 send/receive lives in the repository (DmManager); this is a thin layer over it.
 *
 * The list is split into two inboxes, mirroring how Nostr clients separate known contacts from
 * unsolicited senders: [followsConversations] are peers in the user's NIP-02 follow list, and
 * [othersConversations] is everyone else (message requests). Both update live as the contact list
 * (kind:3) loads, so a peer moves between tabs the moment a follow is added or removed.
 */
class DmViewModel(
    private val repo: NostrRepositoryApi,
) : ViewModel() {
    val conversations = repo.dmConversations
    val messagesByPeer = repo.dmMessagesByPeer
    val unreadByPeer = repo.dmUnreadByPeer
    val totalUnread = repo.totalDmUnread
    val userMetadata = repo.userMetadata

    /** Published kind:10050 DM relays by author, for the header "DM relays" view. */
    val dmRelaysByPubkey = repo.dmRelaysByPubkey

    /** Send status of our own messages (Sending → Delivered), keyed by rumor id. */
    val messageStatus = repo.dmMessageStatus

    /** Download + decryption state of kind:15 attachments, keyed by rumor id. */
    val fileStates = repo.dmFileStates

    /** Reactions keyed by the message they target, then by emoji. */
    val reactions = repo.dmReactions

    /**
     * Send [bytes] as an encrypted kind:15 attachment. Unlike pasting a url into the text, the
     * file is unreadable to anyone who has not been sent the key.
     */
    fun sendFile(
        recipientPubkey: String,
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        width: Int? = null,
        height: Int? = null,
        onFailure: (String) -> Unit = {},
    ) {
        viewModelScope.launch {
            val result = repo.sendDmFile(recipientPubkey, bytes, filename, mimeType, width, height)
            if (result is Result.Error) onFailure(result.error.message)
        }
    }

    /** React to [messageId] in the conversation with [peerPubkey]. */
    fun react(peerPubkey: String, messageId: String, emoji: String, emojiUrl: String? = null) {
        viewModelScope.launch { repo.sendDmReaction(peerPubkey, messageId, emoji, emojiUrl) }
    }

    /**
     * Start reading the attachment on [message], if it has one. Idempotent, so both UIs can call
     * it from the bubble as it renders and let the manager decide whether there is work to do.
     */
    fun loadFile(message: DmMessage) {
        val file = message.file ?: return
        repo.loadDmFile(message.id, file)
    }

    /** Re-attempt an attachment whose download or decryption failed. */
    fun retryFile(message: DmMessage) {
        val file = message.file ?: return
        repo.retryDmFile(message.id, file)
    }

    val followsConversations: StateFlow<List<DmConversation>> =
        partition(keepFollowed = true)

    val othersConversations: StateFlow<List<DmConversation>> =
        partition(keepFollowed = false)

    /** Unread total across the Others inbox, for the requests-tab badge. */
    val othersUnread: StateFlow<Int> =
        othersConversations
            .map { list -> list.sumOf { it.unread } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    private fun partition(keepFollowed: Boolean): StateFlow<List<DmConversation>> = combine(repo.dmConversations, repo.following) { convos, follows ->
        convos.filter { (it.peerPubkey in follows) == keepFollowed }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** A peer's published kind:10050 DM relays (empty until fetched, or if none published). */
    fun peerDmRelays(peerPubkey: String): StateFlow<List<String>> = repo.dmRelaysByPubkey
        .map { it[peerPubkey].orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** Fetch the peer's kind:10050 so [peerDmRelays] fills in (for the header "DM relays" view). */
    fun loadPeerDmRelays(peerPubkey: String) = repo.requestPeerDmRelays(peerPubkey)

    /**
     * Opening a conversation resolves where that peer reads, so the first message is addressed
     * before it is written rather than after the reply teaches us. The send waits on this too;
     * asking here means it is usually already answered by the time anyone types.
     */
    fun openConversation(peerPubkey: String) = repo.requestPeerDmRelays(peerPubkey)

    fun getPublicKey(): String? = repo.getPublicKey()

    fun send(
        recipientPubkey: String,
        content: String,
        onSuccess: () -> Unit = {},
        onFailure: () -> Unit = {},
    ) {
        val text = content.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            val result = withMinDuration { repo.sendDm(recipientPubkey, text) }
            when (result) {
                is Result.Error -> onFailure()
                is Result.Success -> onSuccess()
            }
        }
    }

    /** Clear the unread badge for a conversation when it is open on screen. */
    fun markRead(peerPubkey: String) {
        viewModelScope.launch { repo.markDmRead(peerPubkey) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5000L
    }
}
