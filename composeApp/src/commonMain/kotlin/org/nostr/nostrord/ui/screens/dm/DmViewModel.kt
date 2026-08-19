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

    /** True while the inbox is still catching up, so older messages may still land above. */
    val syncing = repo.dmSyncing

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

    /**
     * Rumor ids of our own messages whose wrap every one of the recipient's kind:10050 inbox
     * relays accepted: the second tick.
     *
     * A DM is a fan-out, so "Delivered" alone is weaker here than in a group, where landing on the
     * group's one relay means everyone can read it. A wrap on a subset of the peer's inboxes only
     * reaches them if they happen to open the client that reads that relay; a wrap on all of them
     * reaches them whichever they open. It says nothing about the peer having fetched it - NIP-17
     * has no delivery receipt, by design.
     *
     * A peer with no published list is absent: without their kind:10050 there is no known set of
     * inboxes to have covered, so those messages stay at one tick.
     */
    val fullyDelivered: StateFlow<Set<String>> =
        combine(repo.dmMessagesByPeer, repo.dmRelaysByPubkey) { byPeer, inboxesByPeer ->
            buildSet {
                for ((peer, messages) in byPeer) {
                    val inboxes = inboxesByPeer[peer].orEmpty().map(::normalizeRelay).toSet()
                    if (inboxes.isEmpty()) continue
                    for (message in messages) {
                        if (!message.mine) continue
                        if (message.relays.map(::normalizeRelay).toSet().containsAll(inboxes)) add(message.id)
                    }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptySet())

    // The accepted-on set is recorded from the publish target list while the inbox list comes from
    // the peer's event, so the two spellings of the same relay have to meet somewhere.
    private fun normalizeRelay(url: String): String = url.trim().lowercase().trimEnd('/')

    val followsConversations: StateFlow<List<DmConversation>> =
        partition(keepFollowed = true)

    val othersConversations: StateFlow<List<DmConversation>> =
        partition(keepFollowed = false)

    /** NIP-02 follow list, for deciding which inbox tab holds a conversation. */
    val following: StateFlow<Set<String>> = repo.following

    /** True once the kind:3 contact list resolved; before that [following] is emptily wrong. */
    val followsLoaded: StateFlow<Boolean> = repo.contactListLoaded

    /**
     * The conversation with [peerPubkey] sits in the Others inbox (message requests), so the list
     * must show that tab: an open thread hidden behind the Follows tab reads as an empty inbox.
     * The split is only decidable once the follow set is usable: the live kind:3 resolved, or the
     * persisted cache seeded a non-empty set on cold boot. Deciding on an empty unloaded set would
     * flip a followed peer's reload onto the Others tab; refusing to decide on a seeded set would
     * leave a request peer's reload stuck on Follows until a relay echoes the kind:3.
     */
    fun isRequestPeer(peerPubkey: String?, follows: Set<String>, followsLoaded: Boolean): Boolean {
        val followsKnown = followsLoaded || follows.isNotEmpty()
        return followsKnown && peerPubkey != null && peerPubkey !in follows
    }

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
    fun openConversation(peerPubkey: String) {
        repo.rememberDmPeer(peerPubkey)
        repo.requestPeerDmRelays(peerPubkey)
    }

    /**
     * Peer the DM nav entry should reopen, or null for the conversation list. Reading a thread and
     * stepping into a group keeps the thread one click away instead of list-then-peer.
     */
    val lastPeer: StateFlow<String?> = repo.lastDmPeer

    fun getPublicKey(): String? = repo.getPublicKey()

    fun send(
        recipientPubkey: String,
        content: String,
        replyToId: String? = null,
        onSuccess: () -> Unit = {},
        onFailure: () -> Unit = {},
    ) {
        val text = content.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            val result = withMinDuration { repo.sendDm(recipientPubkey, text, replyToId) }
            when (result) {
                is Result.Error -> onFailure()
                is Result.Success -> onSuccess()
            }
        }
    }

    /** Send a refused message again (the Retry action on a Not delivered bubble). */
    fun retry(rumorId: String) = repo.retryDm(rumorId)

    /** Drop a refused message from the conversation (the Dismiss action). */
    fun dismiss(rumorId: String) = repo.dismissDm(rumorId)

    /** Clear the unread badge for a conversation when it is open on screen. */
    fun markRead(peerPubkey: String) {
        viewModelScope.launch { repo.markDmRead(peerPubkey) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5000L
    }
}
