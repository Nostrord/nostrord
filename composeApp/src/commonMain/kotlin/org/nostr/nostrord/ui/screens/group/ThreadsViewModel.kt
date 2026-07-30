package org.nostr.nostrord.ui.screens.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.NostrRepositoryApi
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.screens.withMinDuration
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.normalizeRelayUrl

/** One row in the threads list: a kind:11 root plus stats derived from its kind:1111 replies. */
data class ThreadSummary(
    val rootId: String,
    val authorPubkey: String,
    val title: String,
    val preview: String,
    val replyCount: Int,
    val lastActivity: Long,
    val createdAt: Long,
    val replierPubkeys: List<String>,
)

/** A single open thread: its kind:11 root plus the kind:1111 replies, oldest-first. */
data class ThreadDetail(
    val root: NostrGroupClient.NostrMessage,
    val replies: List<NostrGroupClient.NostrMessage>,
)

/** The `E` (root-scope) tag of a kind:1111 reply: the id of the thread it belongs to. */
internal fun NostrGroupClient.NostrMessage.threadRootIdTag(): String? = tags.firstOrNull { it.size >= 2 && it[0] == "E" }?.get(1)

/**
 * The lowercase `e` (parent) tag of a kind:1111 reply: the specific message it answers.
 * Present only on nested replies - a top-level reply's parent IS the root, so ThreadTags
 * omits the lowercase triple (see [org.nostr.nostrord.network.managers.ThreadTags.reply]).
 */
internal fun NostrGroupClient.NostrMessage.threadParentIdTag(): String? = tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)

/** Cap [this] at [max] chars, appending an ellipsis when something was cut. */
private fun String.takeWithEllipsis(max: Int): String = if (length > max) take(max) + "..." else this

/**
 * A thread's title: its NIP-14 `subject` tag (what we write), else the NIP-7D `title` tag
 * (what other kind:11 clients write), else the first non-blank line of the content.
 */
internal fun NostrGroupClient.NostrMessage.threadTitle(): String {
    for (name in listOf("subject", "title")) {
        val value = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)?.trim()
        if (!value.isNullOrEmpty()) return value
    }
    return content.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.takeWithEllipsis(80)
        ?: "Untitled thread"
}

/**
 * Pure derivation of the threads list from the raw kind:11 roots and kind:1111 replies, kept out
 * of the VM's coroutine plumbing so it is unit-testable. Replies are matched to their root by the
 * uppercase `E` tag; the list is ordered by last activity (newest first).
 */
internal fun buildThreadSummaries(
    roots: List<NostrGroupClient.NostrMessage>,
    replies: List<NostrGroupClient.NostrMessage>,
): List<ThreadSummary> {
    val repliesByRoot = replies.groupBy { it.threadRootIdTag() }
    return roots
        .distinctBy { it.id }
        .map { root ->
            val rs = repliesByRoot[root.id] ?: emptyList()
            val preview = root.content.lineSequence().map { it.trim() }
                .firstOrNull { it.isNotEmpty() }.orEmpty().takeWithEllipsis(140)
            ThreadSummary(
                rootId = root.id,
                authorPubkey = root.pubkey,
                title = root.threadTitle(),
                preview = preview,
                replyCount = rs.size,
                lastActivity = rs.maxOfOrNull { it.createdAt } ?: root.createdAt,
                createdAt = root.createdAt,
                replierPubkeys = rs.map { it.pubkey }.distinct(),
            )
        }
        .sortedByDescending { it.lastActivity }
}

/** A compact emoji+count chip shown on a thread list card (top reactions on the root). */
data class ReactionChip(
    val emoji: String,
    val emojiUrl: String?,
    val count: Int,
)

/**
 * The top [maxChips] reactions on a message by reactor count (ties broken alphabetically),
 * for the thread list cards. NIP-25 "+"/"-" display as thumbs so a chip never shows a bare sign.
 * Shared by the Compose and web cards so both rank and label identically.
 */
fun topReactionChips(
    byEmoji: Map<String, GroupManager.ReactionInfo>,
    maxChips: Int = 3,
): List<ReactionChip> = byEmoji.entries
    .sortedWith(
        compareByDescending<Map.Entry<String, GroupManager.ReactionInfo>> { it.value.reactors.size }
            .thenBy { it.key },
    )
    .take(maxChips)
    .map { (emoji, info) ->
        val display = when (emoji) {
            "+" -> "👍"
            "-" -> "👎"
            else -> emoji
        }
        ReactionChip(display, info.emojiUrl, info.reactors.size)
    }

/**
 * Shared screen logic for the forum-style Threads pane (Discord-like): the list of kind:11 roots
 * with derived reply stats, and one open thread (root + kind:1111 replies). Consumed by both the
 * Compose `ThreadsScreen` and the web `ThreadsScreen`; keyed per group so list <-> detail
 * navigation within a group reuses the same instance.
 */
class ThreadsViewModel(
    private val repo: NostrRepositoryApi,
    val groupId: String,
    relayUrl: String? = null,
) : ViewModel() {
    /** Route-carried relay; scopes the bare-id thread buckets like GroupViewModel.messages. */
    private val hostRelay: String? = relayUrl?.normalizeRelayUrl()

    private fun scoped(list: List<NostrGroupClient.NostrMessage>): List<NostrGroupClient.NostrMessage> = if (hostRelay == null) list else list.filter { it.relayUrl == null || it.relayUrl == hostRelay }

    val userMetadata = repo.userMetadata

    /** Optimistic-send status per event id (Sending / Failed) - shared with chat via the repo. */
    val messageStatus = repo.messageStatus

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    val threads: StateFlow<List<ThreadSummary>> =
        combine(repo.threadRoots, repo.threadReplies) { rootsMap, repliesMap ->
            buildThreadSummaries(scoped(rootsMap[groupId] ?: emptyList()), scoped(repliesMap[groupId] ?: emptyList()))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _openRootId = MutableStateFlow<String?>(null)
    val openThread: StateFlow<ThreadDetail?> =
        combine(_openRootId, repo.threadRoots, repo.threadReplies) { rootId, rootsMap, repliesMap ->
            rootId ?: return@combine null
            val root = scoped(rootsMap[groupId] ?: emptyList()).firstOrNull { it.id == rootId } ?: return@combine null
            val replies = scoped(repliesMap[groupId] ?: emptyList())
                .filter { it.threadRootIdTag() == rootId }
                .sortedBy { it.createdAt }
            ThreadDetail(root, replies)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Subscribe to the group's threads, retrying until the group client is ready: a cold-start
        // deep link races the relay connection, and a one-shot request would silently no-op
        // (leaving the list stuck empty / "No threads yet").
        viewModelScope.launch {
            repeat(THREAD_REQUEST_ATTEMPTS) {
                if (repo.requestGroupThreads(groupId)) return@launch
                delay(THREAD_REQUEST_RETRY_MS)
            }
        }
        // Settle the skeleton once a root arrives OR the roots sub reaches EOSE (a real empty
        // result), so "No threads yet" never flashes before slow threads land. The timeout is
        // only a fallback for a stalled relay that never EOSEs.
        viewModelScope.launch {
            withTimeoutOrNull(THREAD_LOAD_SETTLE_MS) {
                combine(repo.threadRoots, repo.threadsLoaded) { roots, loaded ->
                    roots[groupId]?.isNotEmpty() == true || loaded.contains(groupId)
                }.first { it }
            }
            _isLoading.value = false
        }
    }

    /**
     * Select the open thread by its kind:11 root id, or null to show the list. Backfills the root
     * + replies by id so a deep link to an older thread (not in the loaded roots page) resolves
     * instead of hanging on "Loading thread...".
     */
    fun openThread(rootId: String?) {
        _openRootId.value = rootId
        if (rootId != null) {
            viewModelScope.launch { repo.fetchThread(groupId, rootId) }
        }
    }

    /**
     * Create a forum thread (kind:11). No-op on blank content. [shareToChat] also announces it
     * in the group chat via [shareThreadToChat] once the root id is known.
     */
    fun createThread(title: String, content: String, shareToChat: Boolean = false) {
        if (content.isBlank()) return
        viewModelScope.launch {
            when (val result = repo.createThread(groupId, title.trim(), content.trim())) {
                is Result.Success -> if (shareToChat) announceThread(result.data, title.trim(), repo.getPublicKey())
                is Result.Error -> Unit
            }
        }
    }

    /**
     * Announce a thread in the group chat: a plain kind:9 with the title and the root's
     * nostr:nevent, so every NIP-29 client renders it (ours as a tappable quoted card).
     * Opt-in only - never fired automatically without the author asking.
     */
    fun shareThreadToChat(root: NostrGroupClient.NostrMessage) {
        viewModelScope.launch { announceThread(root.id, root.threadTitle(), root.pubkey) }
    }

    private suspend fun announceThread(rootId: String, title: String, authorPubkey: String?) {
        val nevent = try {
            Nip19.encodeNevent(rootId, relays = listOfNotNull(hostRelay), authorHex = authorPubkey, kind = 11)
        } catch (_: Exception) {
            return
        }
        val text = if (title.isBlank()) "Started a thread" else "Started a thread: $title"
        repo.sendMessage(groupId, "$text\nnostr:$nevent")
    }

    /**
     * Post a reply (kind:1111) to the open thread. [parent] targets a specific message for a
     * nested reply; null (or the root itself) posts top-level. No-op on blank content.
     * [onSuccess]/[onFailure] fire after the local build/sign step (the reply then appears with
     * a Sending status and delivers in the background), so the composer can show a send spinner.
     */
    fun sendReply(
        content: String,
        parent: NostrGroupClient.NostrMessage? = null,
        onSuccess: () -> Unit = {},
        onFailure: () -> Unit = {},
    ) {
        if (content.isBlank()) return
        val root = openThread.value?.root ?: return
        viewModelScope.launch {
            val result = withMinDuration { repo.sendThreadReply(groupId, root = root, parent = parent ?: root, content = content.trim()) }
            when (result) {
                is Result.Error -> onFailure()
                is Result.Success -> onSuccess()
            }
        }
    }

    fun getPublicKey() = repo.getPublicKey()

    /** Reactions with NIP-51 muted reactors removed, same contract as [GroupViewModel.reactions]. */
    val reactions: StateFlow<Map<String, Map<String, GroupManager.ReactionInfo>>> =
        combine(repo.reactions, repo.mutedPubkeys, ::filterMutedReactions)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), repo.reactions.value)

    // Reactions with an in-flight send, keyed "$targetEventId|$emoji" — pending badge + spinner
    // during the sign round-trip (1-2s on a NIP-46 bunker), mirroring GroupViewModel.
    private val _pendingReactions = MutableStateFlow<Set<String>>(emptySet())
    val pendingReactions: StateFlow<Set<String>> = _pendingReactions

    private val _reactionError = MutableStateFlow<String?>(null)
    val reactionError: StateFlow<String?> = _reactionError

    fun clearReactionError() {
        _reactionError.value = null
    }

    /** Join the group (offered by the reaction-error dialog when the relay wants a member). */
    fun joinGroup() {
        viewModelScope.launch { repo.joinGroup(groupId) }
    }

    /** React (kind:7) to a thread root or reply; dedupes an in-flight send of the same emoji. */
    fun sendReaction(
        targetEventId: String,
        targetPubkey: String,
        emoji: String,
    ) {
        val key = "$targetEventId|$emoji"
        if (key in _pendingReactions.value) return
        _pendingReactions.value = _pendingReactions.value + key
        viewModelScope.launch {
            try {
                when (val result = repo.sendReaction(groupId, targetEventId, targetPubkey, emoji)) {
                    is Result.Error -> _reactionError.value = friendlyRelayError(result.error)
                    is Result.Success -> Unit
                }
            } finally {
                _pendingReactions.value = _pendingReactions.value - key
            }
        }
    }

    private val _deleteError = MutableStateFlow<String?>(null)

    /** The relay's rejection of a kind:5/9005 delete ("kind 5 not allowed", ...), user-facing. */
    val deleteError: StateFlow<String?> = _deleteError

    fun clearDeleteError() {
        _deleteError.value = null
    }

    /** Delete a thread root or reply you authored (NIP-09/NIP-29 deletion). The relay echo
     *  removes it from local state; a rejection surfaces via [deleteError]. Runs on
     *  viewModelScope, which survives list <-> detail nav. */
    fun deleteThread(rootId: String) {
        viewModelScope.launch {
            when (val result = repo.deleteMessage(groupId, rootId)) {
                is Result.Error -> _deleteError.value = friendlyRelayError(result.error)
                is Result.Success -> Unit
            }
        }
    }

    fun retrySend(eventId: String) = repo.retrySend(eventId)

    fun dismissFailed(eventId: String) = repo.dismissFailed(groupId, eventId)

    override fun onCleared() {
        super.onCleared()
        repo.closeThreadSubscriptions(groupId)
    }

    companion object {
        // Fallback only: the list normally settles on the roots-sub EOSE, not this timer.
        const val THREAD_LOAD_SETTLE_MS = 12_000L
        const val THREAD_REQUEST_ATTEMPTS = 12
        const val THREAD_REQUEST_RETRY_MS = 600L
    }
}
