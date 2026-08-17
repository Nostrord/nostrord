package org.nostr.nostrord.ui.screens.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.NostrRepositoryApi
import org.nostr.nostrord.network.RoleDefinition
import org.nostr.nostrord.network.UserGroupRef
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.network.managers.PendingGroupInvite
import org.nostr.nostrord.ui.screens.withMinDuration
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.groupKey
import org.nostr.nostrord.utils.groupKeyId
import org.nostr.nostrord.utils.isJoinedOn
import org.nostr.nostrord.utils.normalizeRelayUrl
import org.nostr.nostrord.utils.shortNpub

/** A group offered in the `%group` mention autocomplete (with its hosting relay). */
data class MentionableGroup(
    val relayUrl: String,
    val meta: GroupMetadata,
)

/**
 * Groups offered in the `%group` mention autocomplete: only the ones you're in plus the ones
 * discovered through people you follow (their kind:10009 lists, and friends present in a group's
 * member list) - not every group the relay ever served. Matches the friend-based discovery used on
 * the Home page. Shared by every screen with a composer (chat, threads).
 */
@Suppress("UNCHECKED_CAST")
fun mentionableGroupsFlow(
    repo: NostrRepositoryApi,
    scope: CoroutineScope,
): StateFlow<List<MentionableGroup>> = combine(
    listOf(
        repo.groupsByRelay,
        repo.joinedGroupsByRelay,
        repo.following,
        repo.userGroupLists,
        repo.groupMembers,
    ),
) { arr ->
    val byRelay = arr[0] as Map<String, List<GroupMetadata>>
    val joinedByRelay = arr[1] as Map<String, Set<String>>
    val following = arr[2] as Set<String>
    val lists = arr[3] as Map<String, List<UserGroupRef>>
    val members = arr[4] as Map<String, List<String>>

    val wanted = HashSet<String>()
    wanted.addAll(joinedByRelay.values.flatten())
    following.forEach { f -> lists[f].orEmpty().forEach { wanted.add(it.groupId) } }
    members.forEach { (gid, pks) -> if (pks.any { it in following }) wanted.add(gid) }

    byRelay
        .flatMap { (relay, list) -> list.filter { it.id in wanted }.map { MentionableGroup(relay, it) } }
        .distinctBy { it.meta.id }
}.stateIn(scope, SharingStarted.Eagerly, emptyList())

/** A follow offered in the add-member friend picker. */
data class FriendCandidate(
    val pubkey: String,
    val name: String?,
    val picture: String?,
)

/**
 * Follows joined with their cached profiles for the add-member picker: named entries
 * first (alphabetical), unnamed after. Shared by the Compose and web modals.
 */
fun buildFriendCandidates(
    following: Set<String>,
    metadata: Map<String, UserMetadata>,
): List<FriendCandidate> = following
    .map { pk ->
        val meta = metadata[pk]
        FriendCandidate(
            pubkey = pk,
            name = meta?.displayName?.takeIf { it.isNotBlank() }
                ?: meta?.name?.takeIf { it.isNotBlank() },
            picture = meta?.picture,
        )
    }.sortedWith(compareBy({ it.name == null }, { it.name?.lowercase() ?: it.pubkey }))

/** Value under [hostRelay]'s key, tolerating maps keyed by non-normalized URLs. */
internal fun <T> Map<String, T>.atRelay(hostRelay: String?): T? = hostRelay?.let { hr -> this[hr] ?: entries.firstOrNull { it.key.normalizeRelayUrl() == hr }?.value }

/**
 * The host relay's own mirror of a per-relay map, laid over the flat (bare-id) one.
 *
 * The flat map is last-writer-wins across relays, so for [groupId] it is trustworthy only
 * while no OTHER relay has served that id: once one has, inheriting it reports another
 * group's members and admins as ours — which handed out the admin UI, and its join-request
 * list, on a group we do not administer. In that case the entry reads absent until this
 * relay answers. Other groups' entries pass through untouched.
 */
internal fun <T> Map<String, Map<String, T>>.scopedOverFlat(
    flat: Map<String, T>,
    groupId: String,
    hostRelay: String?,
): Map<String, T> {
    val mine = atRelay(hostRelay).orEmpty()
    if (groupId in mine) return flat + mine
    val servedElsewhere = entries.any { it.key.normalizeRelayUrl() != hostRelay && groupId in it.value }
    return if (servedElsewhere) (flat - groupId) + mine else flat + mine
}

/** Relay hosting [groupId] (the relay whose group list carries it), else [fallbackRelay]. */
fun groupHostRelay(
    groupId: String,
    groupsByRelay: Map<String, List<GroupMetadata>>,
    fallbackRelay: String?,
): String? = groupsByRelay.entries.firstOrNull { (_, groups) -> groups.any { it.id == groupId } }?.key ?: fallbackRelay

/**
 * True when [target]'s public kind:10009 pins any group on [groupRelayUrl]: their client
 * connects to that relay, so the in-app add notification reaches them without the DM.
 * Unknown or unfetched lists read false — the DM stays the safe default.
 */
fun pubkeyUsesRelay(
    target: String,
    groupRelayUrl: String?,
    userGroupLists: Map<String, List<UserGroupRef>>,
): Boolean {
    val relay = groupRelayUrl?.normalizeRelayUrl() ?: return false
    return userGroupLists[target].orEmpty().any { it.relayUrl.normalizeRelayUrl() == relay }
}

/** Case-insensitive name / hex-prefix filter; a blank query returns everything. */
fun filterFriendCandidates(
    candidates: List<FriendCandidate>,
    query: String,
): List<FriendCandidate> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return candidates
    return candidates.filter {
        it.name?.lowercase()?.contains(q) == true || it.pubkey.lowercase().startsWith(q)
    }
}

/**
 * The current account's relationship to a group, derived once in commonMain so the Compose
 * and React UIs can't drift on the rules. The interesting distinction is [PENDING] (joined a
 * closed group and waiting on an admin) vs [RESOLVING] (joined but the kind:39002 member list
 * hasn't arrived yet) - rendering "pending" before the list lands flashed the banner on and off.
 */
enum class GroupMembership { NONE, RESOLVING, PENDING, MEMBER, ADMIN }

/**
 * The group's access shape for UI labels (Private/Closed badges, Join vs Request-to-Join). Separate
 * from [GroupMembershipState] so it can fall back to the relay's restricted signal when the kind:39000
 * metadata is withheld from a non-member, WITHOUT touching the membership derivation's permissive
 * `isOpen` default.
 */
data class GroupAccess(
    val isPrivate: Boolean = false,
    val isOpen: Boolean = true,
)

data class GroupMembershipState(
    val status: GroupMembership = GroupMembership.RESOLVING,
    /** Latest own kind:9021 createdAt (seconds) - drives the pending bar's "Requested ..." line. */
    val requestedAtSeconds: Long? = null,
)

/**
 * Pure membership verdict, kept testable and free of coroutine/Compose plumbing.
 *
 * Two signals decide "pending": the kind:39002 member list (authoritative once it arrives) and the
 * account's own outstanding kind:9021 join request (known the instant we ask, so a closed group
 * reads as pending immediately instead of sitting blank until the list loads). When neither is
 * conclusive we report [RESOLVING] (render neither composer nor pending bar) so an open group we
 * just joined doesn't flash the composer before approval and a closed one doesn't blink.
 */
internal fun deriveMembershipStatus(
    pubkey: String?,
    joined: Boolean,
    isOpen: Boolean,
    hasOwnJoinRequest: Boolean,
    members: List<String>,
    admins: List<String>,
    locallyLeft: Boolean = false,
): GroupMembership = when {
    pubkey == null -> GroupMembership.NONE
    // Durable leave intent beats a stale relay kind:39002: some relays keep us listed after our
    // 9022, which would otherwise resurrect us as MEMBER. Checked before admins/members so a left
    // group reads NONE ("Request to Join"). A rejoin clears the marker.
    locallyLeft -> GroupMembership.NONE
    pubkey in admins -> GroupMembership.ADMIN
    pubkey in members -> GroupMembership.MEMBER
    joined && members.isNotEmpty() -> GroupMembership.PENDING
    !isOpen && hasOwnJoinRequest -> GroupMembership.PENDING
    joined -> GroupMembership.RESOLVING
    hasOwnJoinRequest -> GroupMembership.PENDING
    else -> GroupMembership.NONE
}

class GroupViewModel(
    private val repo: NostrRepositoryApi,
    val groupId: String,
    relayUrl: String? = null,
) : ViewModel() {
    /**
     * Relay the route carried (normalized), when the caller knows it. The same id can name
     * two independent groups on two relays, so membership/metadata/orphan checks are scoped
     * to this relay and the repo's relay routing is pinned to it. Null (legacy callers,
     * modals riding an already-open screen) keeps the cross-relay fallbacks.
     */
    private val hostRelay: String? = relayUrl?.normalizeRelayUrl()

    init {
        if (hostRelay != null) repo.setGroupRelayHint(groupId, hostRelay)
    }

    /** Value under [hostRelay]'s key, tolerating maps keyed by non-normalized URLs. */
    private fun <T> Map<String, T>.atHostRelay(): T? = atRelay(hostRelay)

    private fun <T> Map<String, Map<String, T>>.scopedOverFlat(flat: Map<String, T>): Map<String, T> = scopedOverFlat(flat, groupId, hostRelay)

    /**
     * Chat messages with NIP-51 muted authors filtered out. The raw repo cache stays
     * untouched (membership derivation below reads it directly), so an unmute restores
     * the author's messages instantly without a re-fetch.
     */
    val messages: StateFlow<Map<String, List<NostrGroupClient.NostrMessage>>> =
        combine(repo.messages, repo.mutedPubkeys) { byGroup, muted ->
            // Same-id groups on other relays share the bare-id buckets; with an explicit
            // host relay keep only its events. Unstamped messages (rare pre-tag
            // constructions) stay visible so an own send never vanishes.
            val scoped =
                if (hostRelay == null) {
                    byGroup
                } else {
                    byGroup.mapValues { (_, list) ->
                        list.filter { it.relayUrl == null || it.relayUrl == hostRelay }
                    }
                }
            if (muted.isEmpty()) {
                scoped
            } else {
                scoped.mapValues { (_, list) -> list.filter { it.pubkey !in muted } }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, repo.messages.value)

    val mutedPubkeys = repo.mutedPubkeys
    val messageStatus = repo.messageStatus
    val connectionState = repo.connectionState
    val joinedGroups = repo.joinedGroups
    val joinedGroupsByRelay = repo.joinedGroupsByRelay

    /**
     * Is this group in the user's own list ON THIS RELAY. The flat id view answers "some group
     * with this id is joined somewhere", which for a repeated id ("nostrord") offered Leave for
     * a group that was never joined here and hid the way in. Routes with no relay (legacy deep
     * links) keep the flat answer, as there is no relay to scope to.
     */
    val isJoinedHere: StateFlow<Boolean> =
        repo.joinedGroupsByRelay
            .map { byRelay ->
                if (hostRelay == null) {
                    byRelay.values.any { groupId in it }
                } else {
                    byRelay.isJoinedOn(hostRelay, groupId)
                }
            }.distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val groups = repo.groups
    val groupsByRelay = repo.groupsByRelay
    val userMetadata = repo.userMetadata
    val userGroupLists = repo.userGroupLists

    /**
     * Reactions with NIP-51 muted reactors removed, same contract as [messages]: the raw
     * repo cache stays untouched, so an unmute restores their reactions instantly.
     */
    val reactions: StateFlow<Map<String, Map<String, GroupManager.ReactionInfo>>> =
        combine(repo.reactions, repo.mutedPubkeys, ::filterMutedReactions)
            .stateIn(viewModelScope, SharingStarted.Eagerly, repo.reactions.value)

    /**
     * Member/admin/role lists, host-relay scoped: entries the host relay published win
     * over the flat compat maps (where a same-id group on another relay overwrites).
     * The flat value stays the fallback while the per-relay mirror is still cold.
     */
    private val membershipModel = GroupMembershipModel(repo, groupId, hostRelay, viewModelScope)

    val groupMembers: StateFlow<Map<String, List<String>>> = membershipModel.members
    val groupAdmins: StateFlow<Map<String, List<String>>> = membershipModel.admins
    val groupRoles: StateFlow<Map<String, List<RoleDefinition>>> =
        if (hostRelay == null) {
            repo.groupRoles
        } else {
            combine(repo.groupRoles, repo.groupRolesByRelay) { flat, byRelay ->
                byRelay.scopedOverFlat(flat)
            }.stateIn(viewModelScope, SharingStarted.Eagerly, repo.groupRoles.value)
        }
    val loadingMembers = repo.loadingMembers

    /**
     * The route's relay withholds this group's events until you are a member. Scoped to
     * (relay, group): access is granted per relay, so the twin group's denial elsewhere must
     * not lock this screen. Both UIs read this instead of the raw map, which is keyed by
     * [groupKey] and would silently match the wrong group by bare id.
     */
    val isRestrictedHere: StateFlow<Boolean> =
        repo.restrictedGroups
            .map { restrictedHere(it) }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private fun restrictedHere(restricted: Map<String, String>): Boolean = if (hostRelay != null) {
        groupKey(hostRelay, groupId) in restricted
    } else {
        restricted.keys.any { groupKeyId(it) == groupId }
    }
    val isLoadingMore = repo.isLoadingMore
    val hasMoreMessages = repo.hasMoreMessages
    val currentRelayUrl = repo.currentRelayUrl
    val relayMetadata = repo.relayMetadata
    val childrenByParent = repo.childrenByParent
    val groupStates = repo.groupStates
    val groupsAwaitingAuthRead = repo.groupsAwaitingAuthRead
    val zaps = repo.zaps
    val cachedEvents = repo.cachedEvents

    /** Groups offered in the `%group` mention autocomplete; see [mentionableGroupsFlow]. */
    val mentionableGroups: StateFlow<List<MentionableGroup>> = mentionableGroupsFlow(repo, viewModelScope)

    /** The active account's standing in this group; derived once in [GroupMembershipModel]. */
    val membershipState: StateFlow<GroupMembershipState> = membershipModel.membershipState

    /** Access shape (Private/Closed) for the badges and the Join-vs-Request-to-Join label. */
    val groupAccess: StateFlow<GroupAccess> = membershipModel.access

    /**
     * True when this group is no longer available: the relay it lives on finished serving its group
     * list (EOSE) but returned no kind:39000 for it, i.e. it was deleted or never existed. Broader
     * than the kind:10009 "orphaned" notion, so it also catches a group you deleted yourself and then
     * navigate back to (no longer pinned). Restricted/private groups are excluded (their metadata is
     * withheld, not absent), and the EOSE gate prevents a still-loading group from reading as deleted.
     * Drives the "Group no longer available" panel instead of perpetual loading skeletons.
     *
     * Absence is only proof of deletion when the relay had a real chance to serve the group:
     * - the socket must be Connected — on a cold boot the cache-freshness restore marks the relay
     *   "complete" before it has even connected, and a slow relay then eats the whole grace window;
     * - NIP-42 AUTH must have settled — an auth-gating relay serves its public group list (and its
     *   EOSE) while withholding private 39000s until AUTH, and a bunker sign can take far longer
     *   than the grace.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val isOrphaned: StateFlow<Boolean> =
        combine(
            combine(repo.groups, repo.groupsByRelay) { g, br -> g to br },
            repo.completeGroupLoadRelays,
            repo.restrictedGroups,
            repo.currentRelayUrl,
            repo.connectionState,
        ) { (groups, byRelay), doneRelays, restricted, currentRelay, connState ->
            // With an explicit host relay, only ITS listing proves existence and only ITS
            // EOSE proves absence: same-id metadata from another relay must not mask a
            // deleted group, nor its deletion orphan a live one.
            val hasMetadata =
                if (hostRelay != null) {
                    byRelay.atHostRelay()?.any { it.id == groupId } == true
                } else {
                    groups.any { it.id == groupId }
                }
            val relayDone = (hostRelay ?: currentRelay.normalizeRelayUrl()) in doneRelays
            relayDone &&
                !hasMetadata &&
                !restrictedHere(restricted) &&
                connState is ConnectionManager.ConnectionState.Connected
        }.flatMapLatest { gone ->
            // The relay's group-list EOSE can land before this group's kind:39000, so hold the verdict
            // for a short grace (staying in loading); flatMapLatest cancels the delay the moment
            // metadata arrives (or the connection drops), so a real group never flashes the panel.
            if (gone) {
                flow {
                    // Fast no-op on public relays (short challenge grace); on auth-gating relays
                    // waits out the sign budget so the post-AUTH group-list replay can deliver
                    // the withheld private 39000 before the verdict.
                    repo.awaitRelayAuthSettled(hostRelay ?: repo.currentRelayUrl.value)
                    delay(4_000)
                    emit(true)
                }
            } else {
                flowOf(false)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending

    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError

    private val _deleteMessageError = MutableStateFlow<String?>(null)
    val deleteMessageError: StateFlow<String?> = _deleteMessageError

    private val _reactionError = MutableStateFlow<String?>(null)
    val reactionError: StateFlow<String?> = _reactionError

    // Relay rejected the kind:9021 join request (e.g. a closed group that only admits via an invite
    // code, or an auth failure). Surfaced so a tap on Join is never a silent no-op.
    private val _joinError = MutableStateFlow<String?>(null)
    val joinError: StateFlow<String?> = _joinError

    fun clearSendError() {
        _sendError.value = null
    }

    fun clearDeleteMessageError() {
        _deleteMessageError.value = null
    }

    fun clearReactionError() {
        _reactionError.value = null
    }

    fun clearJoinError() {
        _joinError.value = null
    }

    fun getPublicKey() = repo.getPublicKey()

    fun requestGroupMessages(channel: String?) {
        viewModelScope.launch { repo.requestGroupMessages(groupId, channel) }
    }

    fun sendMessage(
        content: String,
        channel: String?,
        mentions: Map<String, String>,
        replyToId: String?,
        extraTags: List<List<String>> = emptyList(),
        onSuccess: () -> Unit = {},
        onFailure: () -> Unit = {},
    ) {
        _isSending.value = true
        _sendError.value = null
        viewModelScope.launch {
            // Optimistic send: the message is placed on screen with a Sending status
            // and delivered in the background, so the result here only signals whether
            // the local build/sign step succeeded. Transient relay timeouts and network
            // drops no longer surface as a toast; they resolve via the on-message status
            // (clock -> delivered, or a Failed indicator with retry). Only a real
            // build/sign failure (no optimistic message exists) restores the draft.
            val result = withMinDuration { repo.sendMessage(groupId, content, channel, mentions, replyToId, extraTags) }
            when (result) {
                is Result.Error -> {
                    _sendError.value = "Could not send message. Please try again."
                    onFailure()
                }
                is Result.Success -> onSuccess()
            }
            _isSending.value = false
        }
    }

    fun retrySend(eventId: String) = repo.retrySend(eventId)

    fun dismissFailed(eventId: String) = repo.dismissFailed(groupId, eventId)

    /** [listPrivately] is the choice made on the join affordance; it defaults to a public listing. */
    fun joinGroup(
        inviteCode: String? = null,
        listPrivately: Boolean = false,
    ) {
        _joinError.value = null
        viewModelScope.launch {
            val result = repo.joinGroup(groupId, inviteCode, listPrivately, hostRelay)
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

    /**
     * Relay this group is listed under: the route's when it carried one, else wherever the joined
     * list has it. The private/public choice is per (relay, group), like every other list entry.
     */
    private val listedRelay: StateFlow<String?> =
        repo.joinedGroupsByRelay
            .map { byRelay -> hostRelay ?: byRelay.entries.firstOrNull { groupId in it.value }?.key }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, hostRelay)

    /** Is this group kept in the encrypted section of the user's kind:10009 instead of its public tags. */
    val isListedPrivately: StateFlow<Boolean> =
        combine(repo.privateGroupEntries, listedRelay) { entries, relay ->
            relay != null && (relay to groupId) in entries
        }.distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Part of the list was written by another app and cannot be read here; it is kept untouched. */
    val privateListSectionOpaque: StateFlow<Boolean> = repo.privateListSectionOpaque

    fun setListedPrivately(listedPrivately: Boolean) {
        val relay = listedRelay.value ?: return
        viewModelScope.launch { repo.setGroupListedPrivately(groupId, relay, listedPrivately) }
    }

    fun leaveGroup(onSuccess: () -> Unit) {
        viewModelScope.launch {
            repo.leaveGroup(groupId)
            onSuccess()
        }
    }

    /**
     * This group's pending external add (an admin's kind:9000 awaiting accept/decline),
     * if any. Both UIs prompt on it: [acceptInvite] adopts the group into the joined set
     * + kind:10009; declining routes through [leaveGroup] (kind:9022 + durable left marker).
     */
    /** This relay's invite only: an invite for the same id on another relay belongs to another group. */
    private fun Map<String, PendingGroupInvite>.inviteHere(): PendingGroupInvite? = this[groupId]?.takeIf { hostRelay == null || it.relayUrl.normalizeRelayUrl() == hostRelay }

    val pendingInvite: StateFlow<PendingGroupInvite?> =
        repo.pendingGroupInvites
            .map { it.inviteHere() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, repo.pendingGroupInvites.value.inviteHere())

    /**
     * Who invited us, for the pending-invite prompt: display name, short-npub fallback,
     * or null when there is no actor or the "actor" is the relay itself (relays author
     * the creator/backfill put-users; a relay key is not a person worth naming).
     */
    val pendingInviteActorLabel: StateFlow<String?> =
        combine(pendingInvite, repo.userMetadata, repo.relayMetadata) { invite, meta, relayMeta ->
            val actor = invite?.actorPubkey ?: return@combine null
            val relayKey = (relayMeta[invite.relayUrl] ?: relayMeta[invite.relayUrl.normalizeRelayUrl()])
                ?.pubkey
                ?.takeIf { it.isNotBlank() }
            if (actor == relayKey) return@combine null
            meta[actor]?.displayName?.takeIf { it.isNotBlank() }
                ?: meta[actor]?.name?.takeIf { it.isNotBlank() }
                ?: shortNpub(actor)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // Resolve the inviter's profile for the prompt (no-op when already cached).
        viewModelScope.launch {
            pendingInvite.collect { invite ->
                val actor = invite?.actorPubkey ?: return@collect
                if (repo.userMetadata.value[actor] == null) repo.requestUserMetadata(setOf(actor))
            }
        }
    }

    fun acceptInvite() {
        viewModelScope.launch { repo.acceptGroupInvite(groupId) }
    }

    /**
     * True when the relay already grants us membership but our own kind:10009 has no entry for
     * this (relay, group): readable and postable, yet absent from every list and from the rail.
     * Drives the "Add to my groups" action, the way back in when no invite card is pending.
     */
    val canAddToMyList: StateFlow<Boolean> =
        combine(membershipState, isJoinedHere, pendingInvite) { membership, joinedHere, invite ->
            invite == null &&
                !joinedHere &&
                (membership.status == GroupMembership.MEMBER || membership.status == GroupMembership.ADMIN)
        }.distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun addToMyList() {
        viewModelScope.launch { repo.addGroupToMyList(groupId, hostRelay) }
    }

    /** Fetch [pubkey]'s public kind:10009 so the add-member "on this relay" hint has data. */
    fun prefetchUserGroupList(pubkey: String) {
        viewModelScope.launch { repo.requestUserGroupList(pubkey) }
    }

    /** Decline a pending external add: leave relay-side (kind:9022), which drops the invite. */
    fun declineInvite(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repo.leaveGroup(groupId)
            onDone()
        }
    }

    fun deleteGroup(onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            onResult(repo.deleteGroup(groupId))
        }
    }

    /**
     * Drop an orphaned group from the joined list (kind:10009) without a relay event - used by the
     * "no longer available" state. The relay is taken from the orphan map, falling back to the
     * active relay.
     */
    fun forget(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val relay =
                repo.orphanedJoinedByRelay.value.entries.firstOrNull { groupId in it.value }?.key
                    ?: repo.currentRelayUrl.value
            repo.forgetGroup(groupId, relay)
            onDone()
        }
    }

    // Reactions with an in-flight send, keyed "$targetEventId|$emoji". The reaction
    // only appears optimistically after signEvent resolves, which on NIP-46 (bunker)
    // is a 1-2s round-trip; we surface a pending badge + spinner during that window
    // and drop it once the real reaction lands (mirrors the web client).
    private val _pendingReactions = MutableStateFlow<Set<String>>(emptySet())
    val pendingReactions: StateFlow<Set<String>> = _pendingReactions

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

    private val _moderationError = MutableStateFlow<String?>(null)
    val moderationError: StateFlow<String?> = _moderationError

    /** True while a kind:9000/9001 is awaiting the relay's OK; gates the moderation buttons. */
    private val _moderationBusy = MutableStateFlow(false)
    val moderationBusy: StateFlow<Boolean> = _moderationBusy

    // Counted, not boolean: approve-join and profile-modal actions can overlap, and the
    // first one finishing must not un-gate the UI while another is still in flight.
    // viewModelScope is main-confined, so plain increments are safe.
    private var moderationInFlight = 0

    private suspend fun trackModeration(block: suspend () -> Unit) {
        moderationInFlight++
        _moderationBusy.value = true
        try {
            block()
        } finally {
            moderationInFlight--
            if (moderationInFlight == 0) _moderationBusy.value = false
        }
    }

    init {
        // A moderation event queued while offline is flushed by the connection layer after
        // its sendAndAwaitOk already timed out, so the action can succeed with a stale
        // timeout error still on screen. The kind:9000/9001 echo updating the member or
        // admin list is the visible success signal; a change invalidates the error.
        viewModelScope.launch {
            combine(groupMembers, groupAdmins) { members, admins ->
                members[groupId] to admins[groupId]
            }
                .distinctUntilChanged()
                .drop(1)
                .collect { _moderationError.value = null }
        }
    }

    fun clearModerationError() {
        _moderationError.value = null
    }

    /** Relay OK reasons come prefixed ("blocked: not an admin"); surface them readable. */
    private fun surfaceModerationError(error: AppError) {
        val raw = error.cause?.message?.takeIf { it.isNotBlank() } ?: error.message
        _moderationError.value =
            raw
                .removePrefix("blocked: ")
                .removePrefix("error: ")
                .replaceFirstChar { it.uppercaseChar() }
    }

    /**
     * Follows for the add-member friend picker, profiles resolved from the metadata cache.
     * The first subscription kicks a refresh for any follows whose kind:0 isn't cached.
     */
    val friends: StateFlow<List<FriendCandidate>> =
        combine(repo.following, repo.userMetadata) { follows, meta -> buildFriendCandidates(follows, meta) }
            .onStart { repo.requestUserMetadata(repo.following.value) }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun addUser(
        targetPubkey: String,
        roles: List<String> = emptyList(),
        successMessage: String = "User added to the group",
        notifyViaDm: Boolean = false,
        onSuccess: () -> Unit = {},
    ) {
        viewModelScope.launch {
            _moderationError.value = null
            trackModeration {
                when (val result = repo.addUser(groupId, targetPubkey, roles, notifyViaDm)) {
                    is Result.Error -> surfaceModerationError(result.error)
                    // A kind:9000 (add / role change) is not reliably rendered in the
                    // timeline, so confirm the action to the admin who triggered it.
                    is Result.Success -> {
                        AppModule.postSystemMessage(successMessage)
                        onSuccess()
                    }
                }
            }
        }
    }

    fun removeUser(targetPubkey: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _moderationError.value = null
            trackModeration {
                when (val result = repo.removeUser(groupId, targetPubkey)) {
                    is Result.Error -> surfaceModerationError(result.error)
                    is Result.Success -> {
                        AppModule.postSystemMessage("User removed from the group")
                        onSuccess()
                    }
                }
            }
        }
    }

    fun promoteToAdmin(targetPubkey: String, onSuccess: () -> Unit = {}) {
        addUser(targetPubkey, listOf("admin"), successMessage = "User promoted to admin", onSuccess = onSuccess)
    }

    fun demoteFromAdmin(targetPubkey: String, onSuccess: () -> Unit = {}) {
        // Re-add user without admin role to demote
        addUser(targetPubkey, emptyList(), successMessage = "Admin role removed", onSuccess = onSuccess)
    }

    fun approveJoinRequest(targetPubkey: String) {
        addUser(targetPubkey, successMessage = "Join request approved")
    }

    fun rejectJoinRequest(joinRequestEventId: String) {
        viewModelScope.launch {
            when (val result = repo.rejectJoinRequest(groupId, joinRequestEventId)) {
                is Result.Error -> surfaceModerationError(result.error)
                is Result.Success -> Unit
            }
        }
    }

    fun createInviteCode(onSuccess: (String) -> Unit = {}) {
        viewModelScope.launch {
            when (val result = repo.createInviteCode(groupId)) {
                is Result.Error -> {
                    val raw = result.error.cause?.message ?: result.error.toString()
                    val cleaned = raw.removePrefix("blocked: ").removePrefix("error: ")
                    val friendly =
                        when {
                            cleaned.contains("kind 9009 not allowed", ignoreCase = true) ||
                                cleaned.contains("not allowed", ignoreCase = true) &&
                                cleaned.contains("9009") ->
                                "This relay does not support invite codes."
                            else -> cleaned.replaceFirstChar { it.uppercaseChar() }
                        }
                    _moderationError.value = friendly
                }
                is Result.Success -> onSuccess(result.data)
            }
        }
    }

    fun revokeInviteCode(eventId: String) {
        viewModelScope.launch {
            when (val result = repo.revokeInviteCode(groupId, eventId)) {
                is Result.Error -> {
                    val raw = result.error.cause?.message ?: result.error.toString()
                    _moderationError.value =
                        raw
                            .removePrefix("blocked: ")
                            .removePrefix("error: ")
                            .replaceFirstChar { it.uppercaseChar() }
                }
                is Result.Success -> Unit
            }
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            when (val result = repo.deleteMessage(groupId, messageId)) {
                is Result.Error -> _deleteMessageError.value = friendlyRelayError(result.error)
                is Result.Success -> Unit
            }
        }
    }

    fun refreshGroupData() {
        viewModelScope.launch {
            repo.requestGroupMembers(groupId)
            repo.requestGroupAdmins(groupId)
            repo.requestGroupMessages(groupId)
        }
    }

    fun loadMoreMessages(channel: String? = null) {
        viewModelScope.launch { repo.loadMoreMessages(groupId, channel) }
    }

    /** Explicit retry from the Stalled pagination state (the "Retry" affordance). */
    fun retryLoadMore(channel: String? = null) {
        viewModelScope.launch { repo.retryStalledLoad(groupId, channel) }
    }

    fun fetchMessageById(messageId: String) {
        viewModelScope.launch { repo.fetchGroupMessageById(groupId, messageId) }
    }

    fun switchRelay(relayUrl: String) {
        viewModelScope.launch { repo.switchRelay(relayUrl) }
    }

    /**
     * Relay whose copy of [groupId] this screen is reading. Unread state is per (relay, group),
     * so a route without a relay falls back to where the group is actually listed before the
     * connected relay - never to a bare-id lookup.
     */
    private fun readRelay(): String = hostRelay ?: listedRelay.value ?: repo.currentRelayUrl.value.normalizeRelayUrl()

    fun markAsRead() {
        repo.markGroupAsRead(readRelay(), groupId)
    }

    fun markAsReadUpTo(timestamp: Long) {
        repo.markGroupAsReadUpTo(readRelay(), groupId, timestamp)
    }

    fun getLastReadTimestamp(): Long? = repo.getLastReadTimestamp(readRelay(), groupId)

    fun resetGroupLoadingState() {
        viewModelScope.launch { repo.resetGroupLoadingState(groupId) }
    }

    fun requestPendingJoinRequests() {
        viewModelScope.launch { repo.requestPendingJoinRequests(groupId) }
    }

    fun requestUserMetadata(pubkeys: Set<String>) {
        if (pubkeys.isEmpty()) return
        viewModelScope.launch { repo.requestUserMetadata(pubkeys) }
    }

    fun requestEventById(
        eventId: String,
        relayHints: List<String> = emptyList(),
        author: String? = null,
    ) {
        viewModelScope.launch { repo.requestEventById(eventId, relayHints, author) }
    }

    /** Preview a referenced group (the [previewGroupId] may differ from this VM's group). */
    fun fetchGroupPreview(
        previewGroupId: String,
        relayUrl: String,
    ) {
        viewModelScope.launch { repo.fetchGroupPreview(previewGroupId, relayUrl) }
    }
}

/**
 * Reactions with NIP-51 muted reactors removed. The raw repo cache stays untouched, so an
 * unmute restores their reactions instantly. Shared by [GroupViewModel] and [ThreadsViewModel].
 */
internal fun filterMutedReactions(
    byMessage: Map<String, Map<String, GroupManager.ReactionInfo>>,
    muted: Set<String>,
): Map<String, Map<String, GroupManager.ReactionInfo>> {
    if (muted.isEmpty()) return byMessage
    return byMessage.mapNotNull { (messageId, byEmoji) ->
        val filtered = byEmoji.mapNotNull { (emoji, info) ->
            val reactors = info.reactors.filter { it !in muted }
            if (reactors.isEmpty()) null else emoji to info.copy(reactors = reactors)
        }.toMap()
        if (filtered.isEmpty()) null else messageId to filtered
    }.toMap()
}

/** User-facing message for a relay-rejected publish (kind 5/7/...), relay prefixes stripped. */
internal fun friendlyRelayError(error: AppError): String = (error.cause?.message ?: error.toString())
    .removePrefix("blocked: ")
    .removePrefix("error: ")
    .replaceFirstChar { it.uppercaseChar() }

/** How a failed reaction should be presented: the relay wants a join first, the signer could not sign the kind:7, or the relay rejected it. */
enum class ReactionErrorKind { JoinRequired, SignerFailure, RelayRejected }

/** Shared by the Compose and web reaction-error dialogs so both classify identically. */
fun classifyReactionError(message: String): ReactionErrorKind = when {
    message.contains("unknown member", ignoreCase = true) -> ReactionErrorKind.JoinRequired
    // "Bunker signing failed: ..." / "Your signer refused to sign ..." - the reaction never
    // reached the group relay, so a relay-rejection message would be false.
    message.contains("signing failed", ignoreCase = true) ||
        message.contains("signer", ignoreCase = true) -> ReactionErrorKind.SignerFailure
    else -> ReactionErrorKind.RelayRejected
}
