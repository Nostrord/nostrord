package org.nostr.nostrord.ui.screens.group

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.NostrRepositoryApi
import org.nostr.nostrord.utils.normalizeRelayUrl

/**
 * One group's roster and the active account's standing in it, host-relay scoped.
 *
 * Every screen that renders a join affordance, a member badge or a moderation control needs the
 * same verdict, so it is derived once here and shared: [GroupViewModel] (chat) and
 * [ThreadsViewModel] (forum) both delegate. Deriving it per screen is how the two drift, and a
 * wrong verdict hands out admin controls or hides the join button.
 *
 * [hostRelay] must already be normalized. When set, only that relay's mirror of a list decides:
 * a same-id group on another relay is a different group.
 */
class GroupMembershipModel(
    private val repo: NostrRepositoryApi,
    private val groupId: String,
    private val hostRelay: String?,
    private val scope: CoroutineScope,
) {
    /** Members, host-relay scoped: entries the host relay published win over the flat compat map. */
    val members: StateFlow<Map<String, List<String>>> =
        if (hostRelay == null) {
            repo.groupMembers
        } else {
            combine(repo.groupMembers, repo.groupMembersByRelay) { flat, byRelay ->
                byRelay.scopedOverFlat(flat, groupId, hostRelay)
            }.stateIn(scope, SharingStarted.Eagerly, repo.groupMembers.value)
        }

    /** Admins, scoped like [members]. */
    val admins: StateFlow<Map<String, List<String>>> =
        if (hostRelay == null) {
            repo.groupAdmins
        } else {
            combine(repo.groupAdmins, repo.groupAdminsByRelay) { flat, byRelay ->
                byRelay.scopedOverFlat(flat, groupId, hostRelay)
            }.stateIn(scope, SharingStarted.Eagerly, repo.groupAdmins.value)
        }

    /**
     * The active account's standing in this group (None / Resolving / Pending / Member / Admin),
     * the single source of truth both UIs read to choose between the join button, the pending bar,
     * and the composer - and to label leaving as "Cancel join request" vs "Leave group".
     * `accountStore.activeId` is folded in so switching accounts (which leaves groupId unchanged)
     * re-reads getPublicKey() and re-derives instead of going stale.
     */
    @Suppress("UNCHECKED_CAST")
    val membershipState: StateFlow<GroupMembershipState> =
        combine(
            listOf(
                repo.joinedGroupsByRelay,
                members,
                admins,
                repo.messages,
                repo.groups,
                AppModule.accountStore.activeId,
                repo.pendingApprovalSince,
                repo.leftGroups,
                repo.groupsByRelay,
            ),
        ) { arr ->
            val joinedByRelay = arr[0] as Map<String, Set<String>>
            val membersByGroup = arr[1] as Map<String, List<String>>
            val adminsByGroup = arr[2] as Map<String, List<String>>
            val messagesByGroup = arr[3] as Map<String, List<NostrGroupClient.NostrMessage>>
            val allGroups = arr[4] as List<GroupMetadata>
            val pendingByGroup = arr[6] as Map<String, Long>
            val leftSet = arr[7] as Set<String>
            val byRelay = arr[8] as Map<String, List<GroupMetadata>>

            val pubkey = repo.getPublicKey()
            val locallyLeft = groupId in leftSet
            // With an explicit host relay only ITS joined set counts: being in the same-id
            // group on another relay is membership in a different group.
            val joined =
                if (hostRelay != null) {
                    joinedByRelay.atRelay(hostRelay)?.contains(groupId) == true
                } else {
                    joinedByRelay.values.any { groupId in it }
                }
            val members = membersByGroup[groupId].orEmpty()
            val admins = adminsByGroup[groupId].orEmpty()
            // Absent metadata defaults to open (the permissive NIP-29 default), so we don't
            // wrongly hold a group as pending before its kind:39000 arrives.
            val meta = metadataFor(allGroups, byRelay)
            val isOpen = meta?.isOpen ?: true
            // Outstanding join request: prefer the LOCAL pendingApprovalSince (set on our 9021, cleared
            // the instant we leave / are approved) — it is the reliable in-session truth. The kind:9021
            // in the message feed is the fallback that survives a restart, but it is gated on `joined`:
            // a left group is removed from our joined list, so a stale 9021 still echoed in a re-fetched
            // feed no longer reads as pending. (`leaveGroup` clears the feed and a re-fetch races, which
            // is why the feed alone left a left group stuck on "Request pending" until a reload.)
            val localPendingAt = pendingByGroup[groupId]
            val ownEvents = messagesByGroup[groupId].orEmpty().asSequence()
                .filter { it.pubkey == pubkey }
                .filter { hostRelay == null || it.relayUrl == null || it.relayUrl == hostRelay }
            val lastJoinReq = ownEvents.filter { it.kind == 9021 }.maxOfOrNull { it.createdAt }
            val lastLeave = ownEvents.filter { it.kind == 9022 }.maxOfOrNull { it.createdAt }
            val feedRequestedAt =
                lastJoinReq?.takeIf { (lastLeave == null || it > lastLeave) && joined }
            val requestedAt = localPendingAt ?: feedRequestedAt

            val status =
                deriveMembershipStatus(
                    pubkey = pubkey,
                    joined = joined,
                    isOpen = isOpen,
                    hasOwnJoinRequest = requestedAt != null,
                    members = members,
                    admins = admins,
                    locallyLeft = locallyLeft,
                )
            GroupMembershipState(status, requestedAt)
        }.stateIn(scope, SharingStarted.Eagerly, GroupMembershipState())

    /**
     * Access shape (Private/Closed) for UI labels. Trusts the kind:39000 metadata when present;
     * otherwise (withheld from a non-member) infers from the relay's restricted signal so an outsider
     * sees "Private"/"Request to Join" instead of the misleading public/open default. Both UIs read
     * this for the badges and the Join-vs-Request-to-Join label.
     */
    val access: StateFlow<GroupAccess> =
        combine(repo.groups, repo.groupsByRelay, repo.restrictedGroups) { groups, byRelay, restricted ->
            val meta = metadataFor(groups, byRelay)
            if (meta != null) {
                GroupAccess(isPrivate = !meta.isPublic, isOpen = meta.isOpen)
            } else {
                val restrictedHere = groupId in restricted
                GroupAccess(isPrivate = restrictedHere, isOpen = !restrictedHere)
            }
        }.stateIn(scope, SharingStarted.Eagerly, GroupAccess())

    /** True when the active account administers this group. */
    val isAdmin: StateFlow<Boolean> =
        combine(admins, AppModule.accountStore.activeId) { byGroup, _ ->
            val me = repo.getPublicKey()
            me != null && me in byGroup[groupId].orEmpty()
        }.stateIn(scope, SharingStarted.Eagerly, false)

    private fun metadataFor(
        flat: List<GroupMetadata>,
        byRelay: Map<String, List<GroupMetadata>>,
    ): GroupMetadata? = if (hostRelay != null) {
        byRelay.atRelay(hostRelay)?.firstOrNull { it.id == groupId }
    } else {
        flat.find { it.id == groupId }
    }
}

/** Normalized host relay for a route, or null when the route carries none. */
internal fun hostRelayOf(relayUrl: String?): String? = relayUrl?.normalizeRelayUrl()
