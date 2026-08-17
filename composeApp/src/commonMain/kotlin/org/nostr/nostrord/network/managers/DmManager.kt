package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import org.nostr.nostrord.auth.NostrSigner
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.nostr.Nip17
import org.nostr.nostrord.nostr.Nip17File
import org.nostr.nostrord.nostr.Nip4e

/** One decrypted NIP-17 message, scoped to a conversation with [peerPubkey]. */
@Serializable
data class DmMessage(
    val id: String,
    val peerPubkey: String,
    val senderPubkey: String,
    val content: String,
    val createdAt: Long,
    val mine: Boolean,
    /** Full decrypted rumor as NIP-01 JSON (unsigned). Null on messages cached
     *  before this field existed; DmMessage.eventJson() reconstructs a fallback. */
    val rumorJson: String? = null,
    /** Normalized relay urls this message's gift wrap was seen on. In-memory only
     *  (session-accumulated); empty for messages hydrated from cache. */
    val relays: List<String> = emptyList(),
    /** Rumor kind: [Nip17.KIND_CHAT] text, or [Nip17.KIND_FILE] for an encrypted attachment. */
    val kind: Int = Nip17.KIND_CHAT,
) {
    /**
     * Attachment payload for a kind:15 message, null for plain chat. Lazy because the render
     * loops touch it per frame and parsing means re-reading the rumor JSON.
     */
    val file: Nip17File? by lazy {
        if (kind != Nip17.KIND_FILE) null else rumorJson?.let { Nip17.parseRumor(it) }?.let { Nip17File.fromRumor(it) }
    }

    /**
     * Id of the message this one replies to, from the rumor's `e` tag. Null for a message that
     * starts its own exchange.
     */
    val replyToId: String? by lazy {
        rumorJson
            ?.let { Nip17.parseAnyRumor(it) }
            ?.getTag("e")
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
    }
}

/**
 * The conversation-list line for a message. A file message carries the blob url as its content,
 * which is unreadable without the key and says nothing to the reader, so it names the attachment.
 */
fun DmMessage.previewText(): String = when {
    kind != Nip17.KIND_FILE -> content
    file?.isImage == true -> "Photo"
    file?.isVideo == true -> "Video"
    file?.isAudio == true -> "Audio"
    else -> "File"
}

/**
 * A signed gift wrap awaiting relay acceptance, persisted per account so a send survives an app
 * restart (delivery is retried on next launch until a relay OKs it). One send enqueues two: the
 * recipient wrap and the self-copy, each with its own target relay set.
 */
@Serializable
data class PendingDmWrap(
    val rumorId: String,
    val wrapId: String,
    val wrapJson: String,
    val relays: List<String>,
    val createdAt: Long,
)

/**
 * A peer's announced NIP-4e encryption key (kind:10044). [key] is null when their latest
 * announcement carries none, which withdraws it and puts them back on identity-addressed
 * encryption. [createdAt] keeps replaceable latest-wins across relays.
 */
@Serializable
data class DmEncKey(
    val key: String?,
    val createdAt: Long,
)

/** A conversation summary derived from its messages. */
data class DmConversation(
    val peerPubkey: String,
    val lastMessage: String,
    val lastAt: Long,
    val unread: Int = 0,
)

/**
 * NIP-17 direct-message state and decryption. Pure logic + in-memory state: relay I/O (fetching
 * DM relays, subscribing to the inbox, publishing gift wraps) is orchestrated by [NostrRepository],
 * which feeds incoming kind:1059 / kind:10050 events here and publishes the wraps this builds.
 *
 * Standard NIP-17 (identity key, single `p` tag); the self-copy is sealed to self so the sender can
 * read their own outgoing messages across devices.
 */
class DmManager(
    private val scope: CoroutineScope,
    // NIP-51 muted authors: their conversations and unread counts are hidden at the
    // source so no badge points at a conversation the lists don't show. Messages stay
    // cached, so an unmute restores the conversation instantly.
    private val mutedPubkeys: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
) {
    private val _messagesByPeer = MutableStateFlow<Map<String, List<DmMessage>>>(emptyMap())
    val messagesByPeer: StateFlow<Map<String, List<DmMessage>>> = _messagesByPeer.asStateFlow()

    // Reaction rumors (kind:7) kept per peer, apart from the messages: they are not bubbles, so
    // they must not reach the thread, the conversation preview or the unread counts. Held in the
    // same DmMessage shape because they persist through the same cache path.
    private val _reactionRumorsByPeer = MutableStateFlow<Map<String, List<DmMessage>>>(emptyMap())
    val reactionRumorsByPeer: StateFlow<Map<String, List<DmMessage>>> = _reactionRumorsByPeer.asStateFlow()

    // Aggregated on every change rather than derived through a flow operator: the UI reads this
    // in the same frame a reaction is filed, and a stateIn would only catch up a dispatch later.
    private val _reactions = MutableStateFlow<Map<String, Map<String, GroupManager.ReactionInfo>>>(emptyMap())

    /** Reactions keyed by the rumor they target, then by emoji. Same shape as group chat's. */
    val reactions: StateFlow<Map<String, Map<String, GroupManager.ReactionInfo>>> = _reactions.asStateFlow()

    // Read high-water per peer: createdAt of the newest message marked read. Persisted by the repo.
    private val _lastReadByPeer = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastReadByPeer: StateFlow<Map<String, Long>> = _lastReadByPeer.asStateFlow()

    val conversations: StateFlow<List<DmConversation>> =
        combine(_messagesByPeer, _lastReadByPeer, mutedPubkeys) { byPeer, reads, muted ->
            byPeer.entries
                .filter { (peer, _) -> peer !in muted }
                .mapNotNull { (peer, msgs) ->
                    val last = msgs.maxByOrNull { it.createdAt } ?: return@mapNotNull null
                    val lastRead = reads[peer] ?: 0L
                    val unread = msgs.count { !it.mine && it.createdAt > lastRead }
                    DmConversation(peer, last.previewText(), last.createdAt, unread)
                }
                .sortedByDescending { it.lastAt }
        }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Unread count per peer (incoming messages newer than the read high-water), zero entries dropped. */
    val unreadByPeer: StateFlow<Map<String, Int>> =
        combine(_messagesByPeer, _lastReadByPeer, mutedPubkeys) { byPeer, reads, muted ->
            byPeer
                .filterKeys { it !in muted }
                .mapValues { (peer, msgs) ->
                    val lastRead = reads[peer] ?: 0L
                    msgs.count { !it.mine && it.createdAt > lastRead }
                }
                .filterValues { it > 0 }
        }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Total unread across all conversations, for the DM nav badge. */
    val totalUnread: StateFlow<Int> =
        unreadByPeer
            .map { it.values.sum() }
            .stateIn(scope, SharingStarted.Eagerly, 0)

    // Recipient/own DM relay lists from kind:10050, keyed by pubkey.
    private val _dmRelaysByPubkey = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val dmRelaysByPubkey: StateFlow<Map<String, List<String>>> = _dmRelaysByPubkey.asStateFlow()

    // NIP-4e encryption keys announced by peers (kind:10044), keyed by identity pubkey. created_at
    // is kept so a copy of an older announcement arriving from a slower relay cannot regress a
    // newer key; a record with a null key is a withdrawal, which must also win by created_at.
    private val _encKeyByPubkey = MutableStateFlow<Map<String, DmEncKey>>(emptyMap())

    // Send status of our own optimistic messages, keyed by rumor id (in-memory, like GroupManager).
    // Sending until a relay OKs the wrap or the self-copy echoes back; then Delivered. Reuses the
    // group MessageStatus so the same send-state icon renders on DM bubbles.
    private val _messageStatus = MutableStateFlow<Map<String, GroupManager.MessageStatus>>(emptyMap())
    val messageStatus: StateFlow<Map<String, GroupManager.MessageStatus>> = _messageStatus.asStateFlow()

    fun setSending(rumorId: String) {
        _messageStatus.update { it + (rumorId to GroupManager.MessageStatus.Sending) }
    }

    /** Flip to Delivered only if the id is tracked (an own optimistic message we are awaiting). */
    fun markDelivered(rumorId: String) {
        _messageStatus.update { if (rumorId in it) it + (rumorId to GroupManager.MessageStatus.Delivered) else it }
    }

    /**
     * Report a send that no relay ever accepted, so the bubble says so instead of sitting there
     * looking ordinary. Only overrides Sending: the recipient wrap and the self-copy are published
     * separately, and the self-copy failing does not un-deliver a message the peer's relay took.
     */
    fun markFailed(rumorId: String, reason: String) {
        val peer = peerByRumor.value[rumorId] ?: ""
        _messageStatus.update { current ->
            if (current[rumorId] !is GroupManager.MessageStatus.Sending) {
                current
            } else {
                current + (rumorId to GroupManager.MessageStatus.Failed(reason, peer, eventJson = null))
            }
        }
    }

    // Dedup: a gift wrap can arrive from several relays; the same rumor can arrive via the
    // recipient wrap and the self wrap. Held as an immutable set swapped atomically: wraps
    // are ingested by several coroutines at once, and a plain HashSet corrupts under that.
    private val seenRumorIds = MutableStateFlow<Set<String>>(emptySet())

    /** Test-and-set on [seenRumorIds]. True when [rumorId] was not seen before. */
    private fun markRumorSeen(rumorId: String): Boolean {
        var isNew = false
        seenRumorIds.update { current ->
            isNew = rumorId !in current
            if (isNew) current + rumorId else current
        }
        return isNew
    }

    /**
     * Decrypt an incoming kind:1059 with [signer] (the account) and add the message to its
     * conversation. Returns true when the wrap is definitively handled (decrypted, or a wrap we
     * never want to retry), false on a TRANSIENT failure (e.g. a bunker decrypt timeout) so the
     * caller can leave it un-acked and retry it on a later backfill.
     */
    suspend fun ingestGiftWrap(giftWrap: Event, myPubkey: String, signer: NostrSigner): Boolean = ingestGiftWrap(
        giftWrap,
        myPubkey,
        listOf(Nip17.Nip44Decryptor { peer, ciphertext -> signer.nip44Decrypt(peer, ciphertext) }),
    )

    /**
     * Decrypt and file a received gift wrap. [decryptors] are tried in order on both layers, so a
     * caller holding NIP-4e keys puts them ahead of the signer and pays no round-trip.
     *
     * [verifyLegacySender] authenticates the NIP-4e legacy shape, where the seal is signed by the
     * sender's encryption key and therefore proves nothing on its own: it must confirm that seal
     * pubkey is the encryption key the rumor's author announced. Returning false leaves the wrap
     * unhandled, which our pipeline retries later, so an unreachable kind:10044 defers the message
     * rather than deciding it wrongly.
     */
    suspend fun ingestGiftWrap(
        giftWrap: Event,
        myPubkey: String,
        decryptors: List<Nip17.Nip44Decryptor>,
        verifyLegacySender: suspend (authorPubkey: String, sealPubkey: String) -> Boolean = { _, _ -> false },
    ): Boolean {
        val unwrapped = Nip17.unwrap(giftWrap, myPubkey, decryptors)
        if (unwrapped == null) {
            // Decrypt/verify failed. On a bunker this is usually a transient timeout under load;
            // report not-handled so the wrap is retried instead of being permanently skipped.
            return false
        }
        if (!unwrapped.sealSignedByIdentity && !verifyLegacySender(unwrapped.rumor.pubkey, unwrapped.sealPubkey)) {
            return false
        }
        val rumor = unwrapped.rumor
        if (rumor.kind == Nip17.KIND_REACTION) {
            fileReaction(rumor, unwrapped.senderPubkey, myPubkey)
            return true
        }
        if (!isConversationRumor(rumor)) return true
        val rumorId = rumor.id ?: return true
        val sender = unwrapped.senderPubkey
        // The conversation peer is the other party: for my own (self-copy) messages that's the
        // rumor's `p` recipient; for received messages it's the sender.
        val peer =
            if (sender == myPubkey) {
                rumor.getTag("p")?.getOrNull(1)
            } else {
                sender
            } ?: return true

        if (!markRumorSeen(rumorId)) {
            // Same rumor from another relay or the self-wrap echo of an optimistic send: nothing
            // new to show, but the wrap round-tripped a relay, so an own message is now Delivered,
            // and its relay still counts for "seen on".
            markDelivered(rumorId)
            linkWrapToRumor(giftWrap.id, rumorId, peer)
            return true
        }
        val message =
            DmMessage(
                id = rumorId,
                peerPubkey = peer,
                senderPubkey = sender,
                content = rumor.content,
                createdAt = rumor.createdAt,
                mine = sender == myPubkey,
                rumorJson = rumor.toJsonString(),
                kind = rumor.kind,
            )
        addMessage(peer, message)
        linkWrapToRumor(giftWrap.id, rumorId, peer)
        return true
    }

    /**
     * File a rumor restored from a backup file. Same shape as an unwrapped one, minus everything
     * that only a relay delivery can mean: no delivery status, no wrap link, no seen-on relay.
     * Returns false when the rumor is not a conversation message for this account, or is known.
     */
    fun importRumor(rumor: Event, myPubkey: String): Boolean {
        if (!isConversationRumor(rumor)) return false
        val rumorId = rumor.id ?: return false
        val recipient = rumor.getTag("p")?.getOrNull(1)
        // The peer is the other party, and one side of the pair must be us, or the file is
        // someone else's history and filing it would invent conversations.
        val peer =
            when (myPubkey) {
                rumor.pubkey -> recipient
                recipient -> rumor.pubkey
                else -> null
            } ?: return false
        if (!markRumorSeen(rumorId)) return false
        addMessage(
            peer,
            DmMessage(
                id = rumorId,
                peerPubkey = peer,
                senderPubkey = rumor.pubkey,
                content = rumor.content,
                createdAt = rumor.createdAt,
                mine = rumor.pubkey == myPubkey,
                rumorJson = rumor.toJsonString(),
                kind = rumor.kind,
            ),
        )
        return true
    }

    /** Optimistically add a just-sent message so the UI shows it before the self-wrap echoes back. */
    fun addOptimistic(rumor: Event, recipientPubkey: String, myPubkey: String) {
        val rumorId = rumor.id ?: return
        setSending(rumorId)
        if (!markRumorSeen(rumorId)) return
        addMessage(
            recipientPubkey,
            DmMessage(
                id = rumorId,
                peerPubkey = recipientPubkey,
                senderPubkey = myPubkey,
                content = rumor.content,
                createdAt = rumor.createdAt,
                mine = true,
                rumorJson = rumor.toJsonString(),
                kind = rumor.kind,
            ),
        )
    }

    /**
     * Kinds that belong in a conversation thread. Everything else inside a gift wrap (reactions,
     * receipts, protocol rumors) is filed elsewhere or ignored; a kind:15 file message is a
     * message like any other, so dropping it would silently lose the sender's attachment.
     */
    private fun isConversationRumor(rumor: Event): Boolean = rumor.kind == Nip17.KIND_CHAT || rumor.kind == Nip17.KIND_FILE

    /** File an incoming kind:7 rumor under its conversation. Ignores one we already hold. */
    private fun fileReaction(rumor: Event, sender: String, myPubkey: String) {
        val rumorId = rumor.id ?: return
        val peer = (if (sender == myPubkey) rumor.getTag("p")?.getOrNull(1) else sender) ?: return
        if (!markRumorSeen(rumorId)) return
        addReactionRumor(
            peer,
            DmMessage(
                id = rumorId,
                peerPubkey = peer,
                senderPubkey = sender,
                content = rumor.content,
                createdAt = rumor.createdAt,
                mine = sender == myPubkey,
                rumorJson = rumor.toJsonString(),
                kind = Nip17.KIND_REACTION,
            ),
        )
    }

    /** Show our own reaction before its wrap round-trips; the echo dedups on the rumor id. */
    fun addOptimisticReaction(rumor: Event, recipientPubkey: String, myPubkey: String) {
        val rumorId = rumor.id ?: return
        if (!markRumorSeen(rumorId)) return
        addReactionRumor(
            recipientPubkey,
            DmMessage(
                id = rumorId,
                peerPubkey = recipientPubkey,
                senderPubkey = myPubkey,
                content = rumor.content,
                createdAt = rumor.createdAt,
                mine = true,
                rumorJson = rumor.toJsonString(),
                kind = Nip17.KIND_REACTION,
            ),
        )
    }

    /** Restore reaction rumors from the cache. */
    fun hydrateReactions(rumors: List<DmMessage>) {
        if (rumors.isEmpty()) return
        seenRumorIds.update { it + rumors.map { r -> r.id } }
        _reactionRumorsByPeer.value = rumors.groupBy { it.peerPubkey }
        _reactions.value = aggregateReactions(rumors)
    }

    private fun addReactionRumor(peer: String, reaction: DmMessage) {
        _reactionRumorsByPeer.update { current ->
            current + (peer to (current[peer].orEmpty() + reaction))
        }
        _reactions.value = aggregateReactions(_reactionRumorsByPeer.value.values.flatten())
    }

    /**
     * Collapse reaction rumors into per-target emoji counts. A reactor is counted once per emoji,
     * and a custom emoji's url comes from the rumor's own NIP-30 tag.
     */
    private fun aggregateReactions(rumors: List<DmMessage>): Map<String, Map<String, GroupManager.ReactionInfo>> {
        val out = mutableMapOf<String, MutableMap<String, GroupManager.ReactionInfo>>()
        for (r in rumors) {
            val rumor = r.rumorJson?.let { Nip17.parseAnyRumor(it) } ?: continue
            val target = rumor.getTag("e")?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: continue
            val emoji = r.content.takeIf { it.isNotBlank() } ?: continue
            val byEmoji = out.getOrPut(target) { mutableMapOf() }
            val existing = byEmoji[emoji]
            if (existing != null && r.senderPubkey in existing.reactors) continue
            val emojiTag = rumor.getTag("emoji")
            byEmoji[emoji] =
                GroupManager.ReactionInfo(
                    emojiUrl = emojiTag?.getOrNull(2) ?: existing?.emojiUrl,
                    reactors = (existing?.reactors ?: emptyList()) + r.senderPubkey,
                )
        }
        return out
    }

    private fun addMessage(peer: String, message: DmMessage) {
        peerByRumor.update { it + (message.id to peer) }
        _messagesByPeer.update { current ->
            val merged = (current[peer].orEmpty() + message).sortedBy { it.createdAt }
            current + (peer to merged)
        }
    }

    // Relays each DM was seen on. Recorded on EVERY wrap arrival — including the duplicates
    // the decrypt pipeline skips (the same wrap sits on several DM relays) — so "seen on"
    // keeps growing after the first decrypt. Keyed by rumor id (stable across restarts) so
    // NostrRepository can persist it; wrapRelays buffers arrivals seen before decrypt.
    //
    // Immutable maps swapped atomically. Wrap arrivals write these from the relay coroutines
    // while the debounced persist reads them from its own; a plain LinkedHashMap throws
    // ConcurrentModificationException the moment a snapshot overlaps an arrival.
    private val wrapRelays = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    private val rumorByWrap = MutableStateFlow<Map<String, String>>(emptyMap())
    private val peerByRumor = MutableStateFlow<Map<String, String>>(emptyMap())
    private val relaysByRumor = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /** Set by NostrRepository to persist the seen-on + wrap-to-rumor maps when they grow. */
    var onSeenRelaysChanged: (() -> Unit)? = null

    /** Set by NostrRepository to persist the peer encryption-key cache when it changes. */
    var onEncKeysChanged: (() -> Unit)? = null

    /** Record that [wrapId] was delivered by [relayUrl] (normalized). Safe pre-decrypt. */
    fun recordWrapRelay(wrapId: String, relayUrl: String) {
        if (relayUrl.isBlank()) return
        wrapRelays.update { it + (wrapId to (it[wrapId].orEmpty() + relayUrl)) }
        // Known rumor (decrypted this session, or the wrap->rumor link restored from disk):
        // attach immediately. This is what lets a re-streamed but already-decrypted wrap add
        // its relay without paying the decrypt again.
        rumorByWrap.value[wrapId]?.let { mergeRelays(it, listOf(relayUrl)) }
    }

    private fun linkWrapToRumor(wrapId: String?, rumorId: String, peer: String) {
        peerByRumor.update { it + (rumorId to peer) }
        if (wrapId == null) return
        var isNew = false
        rumorByWrap.update { current ->
            isNew = current[wrapId] != rumorId
            if (isNew) current + (wrapId to rumorId) else current
        }
        // Optimistic sends have no wrap of their own; adopt the echo's buffered relays.
        wrapRelays.value[wrapId]?.let { mergeRelays(rumorId, it) }
        // Persist the new link so a later session attaches relays on the decrypt-skip path.
        if (isNew) onSeenRelaysChanged?.invoke()
    }

    /** Snapshot of the wrap-id to rumor-id links, for persistence. */
    fun wrapToRumorSnapshot(): Map<String, String> = rumorByWrap.value

    /** Merge [relays] into the rumor's seen-on set; sync the visible message + persist on change. */
    private fun mergeRelays(rumorId: String, relays: Collection<String>) {
        if (relays.isEmpty()) return
        var changed = false
        relaysByRumor.update { current ->
            val existing = current[rumorId].orEmpty()
            val merged = existing + relays
            changed = merged.size != existing.size
            if (changed) current + (rumorId to merged) else current
        }
        if (!changed) return
        syncMessageRelays(rumorId)
        onSeenRelaysChanged?.invoke()
    }

    private fun syncMessageRelays(rumorId: String) {
        val peer = peerByRumor.value[rumorId] ?: return
        val relays = relaysByRumor.value[rumorId]?.sorted() ?: return
        _messagesByPeer.update { current ->
            val msgs = current[peer] ?: return@update current
            current + (
                peer to
                    msgs.map { m -> if (m.id == rumorId && m.relays != relays) m.copy(relays = relays) else m }
                )
        }
    }

    /** Snapshot of seen-on relays keyed by rumor id, for persistence. */
    fun seenRelaysSnapshot(): Map<String, List<String>> = relaysByRumor.value.mapValues { it.value.sorted() }

    /** Mark a conversation read up to its newest message; clears its unread count. */
    fun markRead(peer: String) {
        val latest = _messagesByPeer.value[peer]?.maxOfOrNull { it.createdAt } ?: return
        _lastReadByPeer.update { it + (peer to maxOf(it[peer] ?: 0L, latest)) }
    }

    /** Restore decrypted messages + read state + seen-on relays from disk on login. */
    fun hydrate(
        messages: List<DmMessage>,
        lastRead: Map<String, Long>,
        seenRelays: Map<String, List<String>> = emptyMap(),
        wrapToRumor: Map<String, String> = emptyMap(),
        encKeys: Map<String, DmEncKey> = emptyMap(),
    ) {
        // Restored before the first send so a cold-start message to a NIP-4e peer is addressed to
        // their encryption key instead of racing the kind:10044 fetch and falling back to identity.
        if (encKeys.isNotEmpty()) _encKeyByPubkey.update { it + encKeys }
        relaysByRumor.update { it + seenRelays.mapValues { (_, relays) -> relays.toSet() } }
        // Restore the wrap->rumor links so a re-streamed, already-processed wrap can attach its
        // relay on the decrypt-skip path (recordWrapRelay) instead of being dropped.
        rumorByWrap.update { it + wrapToRumor }
        if (messages.isNotEmpty()) {
            seenRumorIds.update { it + messages.map { m -> m.id } }
            // Late wrap arrivals must find hydrated messages too for "seen on".
            peerByRumor.update { it + messages.associate { m -> m.id to m.peerPubkey } }
            _messagesByPeer.value =
                messages
                    .groupBy { it.peerPubkey }
                    .mapValues { (_, msgs) ->
                        msgs.sortedBy { it.createdAt }.map { m ->
                            relaysByRumor.value[m.id]?.sorted()?.let { m.copy(relays = it) } ?: m
                        }
                    }
        }
        if (lastRead.isNotEmpty()) _lastReadByPeer.value = lastRead
    }

    /** Flat snapshot of every decrypted message, for persistence. */
    fun allMessages(): List<DmMessage> = _messagesByPeer.value.values.flatten()

    /** Parse a kind:10050 event into the DM relay list for its author. */
    fun ingestDmRelays(event: Event) {
        val relays =
            event.tags
                .filter { it.firstOrNull() == "relay" && it.getOrNull(1)?.isNotBlank() == true }
                .map { it[1] }
        if (relays.isEmpty()) return
        _dmRelaysByPubkey.update { it + (event.pubkey to relays) }
    }

    fun dmRelaysFor(pubkey: String): List<String> = _dmRelaysByPubkey.value[pubkey].orEmpty()

    /**
     * Parse a kind:10044 into the author's NIP-4e encryption key. Replaceable latest-wins; an
     * announcement without a valid `n` tag withdraws the key, so the author goes back to
     * identity-addressed encryption.
     */
    fun ingestEncryptionKey(event: Event) {
        if (event.kind != Nip4e.KIND_ENCRYPTION_KEY) return
        val known = _encKeyByPubkey.value[event.pubkey]
        if (known != null && known.createdAt >= event.createdAt) return
        val record = DmEncKey(key = Nip4e.encryptionKeyFrom(event), createdAt = event.createdAt)
        _encKeyByPubkey.update { it + (event.pubkey to record) }
        // A re-announcement of the same key only refreshes created_at; nothing to rewrite on disk.
        if (known?.key != record.key) onEncKeysChanged?.invoke()
    }

    /** [pubkey]'s announced encryption key, or null when they have not announced one. */
    fun encryptionKeyFor(pubkey: String): String? = _encKeyByPubkey.value[pubkey]?.key

    /** Snapshot of the peer encryption-key cache, for persistence. */
    fun encKeysSnapshot(): Map<String, DmEncKey> = _encKeyByPubkey.value

    /** Drop all state on account switch. */
    fun clear() {
        seenRumorIds.value = emptySet()
        wrapRelays.value = emptyMap()
        rumorByWrap.value = emptyMap()
        peerByRumor.value = emptyMap()
        relaysByRumor.value = emptyMap()
        _messageStatus.value = emptyMap()
        _messagesByPeer.value = emptyMap()
        _reactionRumorsByPeer.value = emptyMap()
        _reactions.value = emptyMap()
        _dmRelaysByPubkey.value = emptyMap()
        _encKeyByPubkey.value = emptyMap()
        _lastReadByPeer.value = emptyMap()
    }
}
