package org.nostr.nostrord.ui.screens.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.NostrRepositoryApi
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.nostr.Nip27
import org.nostr.nostrord.ui.screens.withMinDuration
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.groupKey
import org.nostr.nostrord.utils.groupKeyId
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
 * Who may delete a thread root or reply: its author (NIP-09 kind:5) and the group's admins
 * (NIP-29 kind:9005 moderation). Shared by both thread UIs so the header button and the
 * context menu offer the action under the same rule chat already uses.
 */
fun canDeleteThreadMessage(
    authorPubkey: String,
    myPubkey: String?,
    isAdmin: Boolean,
): Boolean = myPubkey != null && (authorPubkey == myPubkey || isAdmin)

/** What the threads list shows when it has no cards to render. */
enum class ThreadsPlaceholder {
    /** Still fetching: roots may yet arrive. */
    LOADING,

    /** Join request sent, no admin answer yet. */
    PENDING_APPROVAL,

    /** The relay withholds this group's events from you (NIP-29 private group). */
    PRIVATE,

    /** Readable and genuinely empty: invite the first thread. */
    EMPTY,
}

/**
 * Placeholder for an empty threads list, or null when there are threads to render.
 *
 * A private group reads as empty because the relay withholds the events, not because nobody
 * posted: prompting to "start the first one" there invites a post the relay will reject.
 * Restriction outranks loading, since the withheld read never settles on its own.
 * Mirrors the chat gate in MessagesList / the web ChatScreen.
 */
fun threadsPlaceholder(
    hasThreads: Boolean,
    isLoading: Boolean,
    isPendingApproval: Boolean,
    isRestricted: Boolean,
): ThreadsPlaceholder? = when {
    hasThreads -> null
    isPendingApproval -> ThreadsPlaceholder.PENDING_APPROVAL
    isRestricted -> ThreadsPlaceholder.PRIVATE
    isLoading -> ThreadsPlaceholder.LOADING
    else -> ThreadsPlaceholder.EMPTY
}

/**
 * Chat messages announcing [rootId] ("Started a thread: ..." carrying its nevent), matched by
 * decoding the nostr: URIs rather than by string compare - the same root encodes to different
 * nevents depending on the relay and author hints carried.
 *
 * Deleting the thread leaves these pointing at nothing, so they go with it.
 */
fun threadAnnouncementsFor(
    rootId: String,
    messages: List<NostrGroupClient.NostrMessage>,
): List<NostrGroupClient.NostrMessage> = messages.filter { msg ->
    msg.kind == 9 &&
        Nip27.findReferences(msg.content).any { ref ->
            when (val entity = ref.entity) {
                is Nip19.Entity.Nevent -> entity.eventId == rootId
                is Nip19.Entity.Note -> entity.eventId == rootId
                else -> false
            }
        }
}

/**
 * Body of the delete confirmation for a thread root or reply. Pass [authorName] only when the
 * content is someone else's: an admin sees the delete action on every message, so moderating
 * has to read as moderating (named author, group-wide effect) and not as retracting your own.
 */
fun deleteThreadConfirmBody(
    isRoot: Boolean,
    authorName: String?,
): String {
    val what = if (isRoot) "thread" else "message"
    return if (authorName == null) {
        "Are you sure you want to delete this $what? This cannot be undone."
    } else {
        "Delete this $what by $authorName? Everyone in the group stops seeing it, and this cannot be undone."
    }
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

    /**
     * `@user` candidates for the thread composers: the group's kind:39002 member list, falling back
     * to the authors seen in the group while that list is still empty (same rule as chat, where a
     * relay that withholds 39002 must not leave the autocomplete blank).
     */
    val mentionableMembers: StateFlow<List<String>> =
        combine(repo.groupMembers, repo.messages, repo.threadRoots, repo.threadReplies) { members, messages, roots, replies ->
            members[groupId].orEmpty().ifEmpty {
                (scoped(messages[groupId].orEmpty()) + scoped(roots[groupId].orEmpty()) + scoped(replies[groupId].orEmpty()))
                    .map { it.pubkey }
                    .distinct()
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Admins of this group, so the suggestion list can rank them first (chat parity). */
    val groupAdmins: StateFlow<List<String>> =
        repo.groupAdmins
            .map { it[groupId].orEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** `%group` candidates; see [mentionableGroupsFlow]. */
    val mentionableGroups: StateFlow<List<MentionableGroup>> = mentionableGroupsFlow(repo, viewModelScope)

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
            // Reaching the thread is what clears its notifications: reading the group's chat
            // never shows a kind:11/1111, so markReadForGroup deliberately skips them.
            AppModule.notificationHistoryStore.markReadForThread(rootId)
            viewModelScope.launch { repo.fetchThread(groupId, rootId) }
        }
    }

    /**
     * Create a forum thread (kind:11). No-op on blank content. [shareToChat] also announces it
     * in the group chat via [shareThreadToChat] once the root id is known; [onCreated] fires
     * with the root id so the screen can open the new thread right away.
     */
    fun createThread(
        title: String,
        content: String,
        shareToChat: Boolean = false,
        mentions: Map<String, String> = emptyMap(),
        onCreated: (rootId: String) -> Unit = {},
    ) {
        if (content.isBlank()) return
        viewModelScope.launch {
            when (val result = repo.createThread(groupId, title.trim(), content.trim(), mentions)) {
                is Result.Success -> {
                    onCreated(result.data)
                    if (shareToChat) announceThread(result.data, title.trim(), repo.getPublicKey())
                }
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
        mentions: Map<String, String> = emptyMap(),
        onSuccess: () -> Unit = {},
        onFailure: () -> Unit = {},
    ) {
        if (content.isBlank()) return
        val root = openThread.value?.root ?: return
        viewModelScope.launch {
            val result = withMinDuration {
                repo.sendThreadReply(groupId, root = root, parent = parent ?: root, content = content.trim(), mentions = mentions)
            }
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

    /**
     * Join the group. [listPrivately] is the choice made on the join confirm step, the same one
     * the chat screen offers.
     */
    fun joinGroup(listPrivately: Boolean = false) {
        viewModelScope.launch { repo.joinGroup(groupId, listPrivately = listPrivately) }
    }

    /** React (kind:7) to a thread root or reply; dedupes an in-flight send of the same emoji. */
    fun sendReaction(
        targetEventId: String,
        targetPubkey: String,
        emoji: String,
        /** The thread the target belongs to; defaults to the open one, else the target itself
         *  (list cards react to the root). Rides along so the reaction's readers can open it. */
        threadRootId: String? = _openRootId.value ?: targetEventId,
    ) {
        val key = "$targetEventId|$emoji"
        if (key in _pendingReactions.value) return
        _pendingReactions.value = _pendingReactions.value + key
        viewModelScope.launch {
            try {
                when (val result = repo.sendReaction(groupId, targetEventId, targetPubkey, emoji, threadRootId)) {
                    is Result.Error -> _reactionError.value = friendlyRelayError(result.error)
                    is Result.Success -> Unit
                }
            } finally {
                _pendingReactions.value = _pendingReactions.value - key
            }
        }
    }

    /**
     * Denied on THIS relay. Access is granted per relay, so the twin group's denial elsewhere
     * must not lock this pane. With no host relay on the route, any denial for the id counts.
     */
    private fun restrictedHere(restricted: Map<String, String>): Boolean = if (hostRelay != null) {
        groupKey(hostRelay, groupId) in restricted
    } else {
        restricted.keys.any { groupKeyId(it) == groupId }
    }

    /** The relay withholds this group's events until you are a member (NIP-29 private group). */
    val isRestricted: StateFlow<Boolean> =
        repo.restrictedGroups
            .map { restrictedHere(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), restrictedHere(repo.restrictedGroups.value))

    /** A join request is in flight and no admin has answered yet. */
    val isPendingApproval: StateFlow<Boolean> =
        repo.pendingApprovalSince
            .map { groupId in it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), groupId in repo.pendingApprovalSince.value)

    private val membershipModel = GroupMembershipModel(repo, groupId, hostRelay, viewModelScope)

    /** Admin of this group (host-relay scoped): drives the moderation delete affordances. */
    val isAdmin: StateFlow<Boolean> = membershipModel.isAdmin

    /** Standing in this group, same verdict the chat header uses for its join affordance. */
    val membershipState: StateFlow<GroupMembershipState> = membershipModel.membershipState

    /** Access shape, for the Join vs Request-to-Join label. */
    val groupAccess: StateFlow<GroupAccess> = membershipModel.access

    private val _joinError = MutableStateFlow<String?>(null)

    /** Relay refusal of a kind:9021 join (or of the invite code), user-facing. */
    val joinError: StateFlow<String?> = _joinError

    fun clearJoinError() {
        _joinError.value = null
    }

    /** Join (or request to join) without leaving the threads pane. Mirrors GroupViewModel.joinGroup. */
    fun joinGroup(inviteCode: String? = null) {
        _joinError.value = null
        viewModelScope.launch {
            val result = repo.joinGroup(groupId, inviteCode)
            if (result is Result.Error) {
                val reason = (result.error as? AppError.Group.JoinFailed)?.cause?.message
                _joinError.value =
                    if (reason.isNullOrBlank()) {
                        "Could not join the group. Please try again."
                    } else {
                        "Could not join: $reason"
                    }
            }
        }
    }

    private val _deleteError = MutableStateFlow<String?>(null)

    /** The relay's rejection of a kind:5/9005 delete ("kind 5 not allowed", ...), user-facing. */
    val deleteError: StateFlow<String?> = _deleteError

    fun clearDeleteError() {
        _deleteError.value = null
    }

    /** Delete a thread root or reply (NIP-09 kind:5 as its author, NIP-29 kind:9005 as an admin;
     *  [GroupManager.deleteMessage] picks the kind). The relay echo removes it from local state;
     *  a rejection surfaces via [deleteError]. Runs on viewModelScope, which survives
     *  list <-> detail nav. */
    fun deleteThread(rootId: String) {
        viewModelScope.launch {
            when (val result = repo.deleteMessage(groupId, rootId)) {
                is Result.Error -> {
                    _deleteError.value = friendlyRelayError(result.error)
                    return@launch
                }
                is Result.Success -> Unit
            }
            // The chat announcement outlives its thread and renders as a dead reference card, so
            // it goes too - when this account may delete it (its author, or an admin). Failures
            // stay silent: the thread itself is gone, which is what was asked, and a second error
            // dialog over a successful delete only confuses.
            val me = repo.getPublicKey()
            val admin = isAdmin.value
            threadAnnouncementsFor(rootId, scoped(repo.messages.value[groupId].orEmpty()))
                .filter { canDeleteThreadMessage(it.pubkey, me, admin) }
                .forEach { repo.deleteMessage(groupId, it.id) }
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
