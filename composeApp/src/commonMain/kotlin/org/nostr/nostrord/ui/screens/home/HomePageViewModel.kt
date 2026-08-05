package org.nostr.nostrord.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.NostrRepositoryApi
import org.nostr.nostrord.network.UserGroupRef
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.nostr.Nip11RelayInfo
import org.nostr.nostrord.notifications.NotificationHistoryStore
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.loadFollowingCacheFor
import org.nostr.nostrord.storage.saveFollowingCacheFor
import org.nostr.nostrord.ui.screens.group.aggregateUnread
import org.nostr.nostrord.utils.normalizeRelayUrl

/**
 * Rail identity as a single string, for drag-reorder helpers that work on `List<String>`.
 * The pair (relay, id) is the identity; the bare id is not, since the same id on two
 * relays is two groups. Relay URLs carry no "|", so the first separator splits it back.
 */
fun railKey(
    relayUrl: String,
    groupId: String,
): String = "$relayUrl|$groupId"

fun parseRailKey(key: String): Pair<String, String>? {
    val sep = key.indexOf('|')
    if (sep <= 0 || sep == key.lastIndex) return null
    return key.substring(0, sep) to key.substring(sep + 1)
}

/**
 * Curator whose public group list (kind:10009) seeds the "Recommended" tab: the
 * groups we hand-pick are simply the ones this account joins.
 */
private const val CURATOR_PUBKEY = "b2cdcb37d32533145c00c4f43d5e1e1deb7c67bceea7ef63f526ca4cab891633"

/** Avatars shown on a group card's people row. */
private const val PEOPLE_PREVIEW = 5

/**
 * How long to wait for a group's member list (kind:39002) before giving up on the
 * people-row skeleton. Without this the skeleton spins forever for groups whose
 * relay never returns a member list (common on Recommended).
 */
private const val MEMBERS_RESOLVE_TIMEOUT_MS = 8_000L

/**
 * How long a discovery tab shows group-card skeletons before falling back to its
 * empty state, so a still-loading tab doesn't flash "no groups yet". Resolves
 * early the moment the first group arrives.
 */
private const val DISCOVERY_GRACE_MS = 6_000L

/** A joined group together with the relay that hosts it (needed for navigation). */
data class JoinedGroup(
    val relayUrl: String,
    val meta: GroupMetadata,
)

/** A followed user (NIP-02 kind:3) with their resolved kind:0 metadata, if known. */
data class Friend(
    val pubkey: String,
    val metadata: UserMetadata?,
)

/**
 * A discoverable group (you are NOT already in) shown on the From friends and
 * Recommended tabs. [people] is the preview shown on the card: the friends you
 * follow who are in the group (From friends, and Recommended when any), falling
 * back to the group's members as social proof on Recommended. [memberCount] is
 * the total used for the "N people" label.
 */
data class DiscoverGroup(
    val relayUrl: String,
    val meta: GroupMetadata,
    val people: List<Friend>,
    val memberCount: Int,
    /** True while the member list is still being fetched and no people are known yet. */
    val peopleLoading: Boolean = false,
    /**
     * True when [meta] came from a real kind:39000 (not the bare-id placeholder), so
     * the card may show the group's access-tag badges when it has no people to preview.
     */
    val hasMetadata: Boolean = false,
)

/**
 * Screen logic for the new-design Home page (prototype Home), shared by the Compose
 * and React UIs: the user's joined groups across every relay (kind:10009), filtered
 * by the search query. The friends / communities / people tabs are layout-only with
 * placeholder content for now, so they carry no logic here yet.
 */
class HomePageViewModel(
    private val repo: NostrRepositoryApi,
    notificationHistoryStore: NotificationHistoryStore = NotificationHistoryStore(),
    // Loads an account's on-disk friends cache (kind:3 pubkeys -> rows, metadata seeded from
    // the global store). Injectable so a test drives the per-account cache without touching
    // SecureStorage; the friends sidebar shows these rows instantly while kind:3 re-arrives.
    private val loadFriendsCache: (String?) -> List<Friend> = { pubkey ->
        if (pubkey.isNullOrBlank()) {
            emptyList()
        } else {
            SecureStorage.loadFollowingCacheFor(pubkey).map { pk -> Friend(pk, repo.userMetadata.value[pk]) }
        }
    },
    // Off-Main dispatcher for the heavy combine transforms. Injectable so tests pass their
    // test dispatcher: with the real Default here, advanceUntilIdle can't see the transform's
    // in-flight work and the assertion races it.
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun setQuery(value: String) {
        _query.value = value
    }

    /** The "Filter groups" box matches a group by name or about (empty needle = all). */
    private fun matchesQuery(
        meta: GroupMetadata,
        needle: String,
    ): Boolean = needle.isEmpty() ||
        (meta.name ?: meta.id).lowercase().contains(needle) ||
        meta.about.orEmpty().lowercase().contains(needle)

    /** The active account, excluded from card previews so a card never previews just you. */
    private val selfPubkey: String? = repo.getPublicKey()

    /** Group ids whose member list either arrived or timed out, so the skeleton can stop. */
    private val _membersResolved = MutableStateFlow<Set<String>>(emptySet())

    /**
     * The avatars shown on a group card: people you follow first, then any other
     * members to fill up to [PEOPLE_PREVIEW], excluding the active account.
     * [priorityFirst] is surfaced ahead of [memberPks] for "From friends", where a
     * friend discovered via their kind:10009 may not be in the member list yet.
     */
    private fun previewPeople(
        memberPks: List<String>,
        following: Set<String>,
        meta: Map<String, UserMetadata>,
        priorityFirst: List<String> = emptyList(),
        includeSelf: Boolean = false,
    ): List<Friend> {
        val ordered = LinkedHashSet<String>()
        ordered.addAll(priorityFirst)
        ordered.addAll(memberPks.filter { it in following })
        ordered.addAll(memberPks)
        return ordered.asSequence()
            .filter { it.isNotBlank() && (includeSelf || it != selfPubkey) }
            .take(PEOPLE_PREVIEW)
            .map { Friend(it, meta[it]) }
            .toList()
    }

    /** Unread message counts per group id (drives the rail badges). */
    val unreadCounts: StateFlow<Map<String, Int>> = repo.unreadCounts

    /** Unread notifications (drives the bell badge on the groups rail). */
    val notificationUnread: StateFlow<Int> =
        notificationHistoryStore.entries
            .map { list -> list.count { !it.read } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Relay NIP-11 metadata (icon/name), so a card can show which relay hosts the group. */
    val relayMetadata: StateFlow<Map<String, Nip11RelayInfo>> = repo.relayMetadata

    /** Known member counts per group id (only groups whose member list was fetched). */
    val memberCounts: StateFlow<Map<String, Int>> =
        repo.groupMembers
            .map { members -> members.mapValues { it.value.size } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * Joined groups across all relays (each with its hosting relay), with metadata
     * where the relay already served the kind:39000 (a bare id placeholder
     * otherwise). Unfiltered (the rail and relay page read this); the Home page list reads
     * [filteredMyGroups]. Carries the same
     * people preview + member count as the discovery tabs so every group card shows
     * the same shape: the friends you follow who are here (or members as a fallback).
     */
    val myGroups: StateFlow<List<DiscoverGroup>> =
        combine(
            listOf(
                repo.groupsByRelay,
                repo.joinedGroupsByRelay,
                repo.groupMembers,
                repo.following,
                repo.userMetadata,
                _membersResolved,
                repo.groupMembersByRelay,
                repo.groupOrder,
            ),
        ) { arr ->
            val groupsByRelay = arr[0] as Map<String, List<GroupMetadata>>
            val joinedByRelay = arr[1] as Map<String, Set<String>>
            val members = arr[2] as Map<String, List<String>>
            val following = arr[3] as Set<String>
            val meta = arr[4] as Map<String, UserMetadata>
            val resolved = arr[5] as Set<String>
            val membersByRelay = arr[6] as Map<String, Map<String, List<String>>>

            @Suppress("UNCHECKED_CAST")
            val order = arr[7] as List<Pair<String, String>>
            val orderIndex = order.withIndex().associate { (index, entry) -> entry to index }
            joinedByRelay
                .flatMap { (relay, ids) ->
                    val metas = groupsByRelay[relay].orEmpty().associateBy { it.id }
                    // This relay's own member lists first: a same-id group elsewhere
                    // must not paint this card's people row.
                    val relayMembers = membersByRelay[relay].orEmpty()
                    ids.map { id ->
                        val memberPks = relayMembers[id] ?: members[id].orEmpty()
                        // Small joined groups (<=5 members) include your own avatar so the
                        // row isn't near-empty; larger ones still omit it (My groups only).
                        val preview = previewPeople(
                            memberPks = memberPks,
                            following = following,
                            meta = meta,
                            includeSelf = memberPks.size <= PEOPLE_PREVIEW,
                        )
                        DiscoverGroup(
                            relayUrl = relay,
                            meta =
                            metas[id] ?: GroupMetadata(
                                id = id,
                                name = null,
                                about = null,
                                picture = null,
                                isPublic = true,
                                isOpen = true,
                            ),
                            people = preview,
                            memberCount = memberPks.size,
                            peopleLoading = preview.isEmpty() && id !in resolved,
                            hasMetadata = metas[id] != null,
                        )
                    }
                }
                // Dedup by (relay, id): the same id on two relays is two independent groups.
                .distinctBy { it.relayUrl to it.meta.id }
                // The kind:10009 tag order is the user's own order and the only stable one:
                // the map above is keyed by relay, so its iteration order is whichever relay
                // loaded first (the focused relay is seeded ahead of the rest), which
                // reshuffled the rail between reloads. Stable sort, so a group with no
                // position yet (fresh join, pending invite, orphan pin) sits at the end in
                // arrival order and settles once the list is published.
                .sortedBy { orderIndex[it.relayUrl to it.meta.id] ?: Int.MAX_VALUE }
            // Off Main: the previewPeople / associateBy / distinctBy transform is O(groups x members)
            // and re-runs for each of the ~50 metadata events that arrive in waves on home open;
            // running it on viewModelScope's Main dispatcher made it compete with composition. The
            // transform is pure, so flowOn is correctness-safe; stateIn still publishes on Main.
        }.flowOn(computeDispatcher).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Rail-only projection of [myGroups]: drops the member-preview fields (people / memberCount /
     * peopleLoading) the rail never renders, so the ~50 member-avatar metadata waves on home open
     * that only change those are collapsed by distinctUntilChanged. The rail collects THIS instead
     * of [myGroups] so it recomposes only when a rail-visible field (id / name / picture) changes.
     */
    val railGroups: StateFlow<List<DiscoverGroup>> =
        myGroups
            .map { list -> list.map { it.copy(people = emptyList(), memberCount = 0, peopleLoading = false) } }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Rail entries under the Discord-style channel model: root groups only. A subgroup
     * lives in its root's channel list, not the rail; one whose parent isn't joined
     * stays (it would be unreachable otherwise). [railGroups] remains the unfiltered
     * joined list for non-rail consumers (restore validation, history labels).
     */
    /**
     * Persist a rail reorder: republish kind:10009 with the `group` tags in [orderedKeys]
     * (rail order, [railKey] encoding). Entries the rail never renders (channels) follow,
     * keeping their current relative order. Membership is untouched — the publish emits
     * only joined groups, so a reorder can neither add nor drop one.
     */
    fun reorderRail(orderedKeys: List<String>) {
        val moved = orderedKeys.mapNotNull(::parseRailKey)
        if (moved.isEmpty()) return
        viewModelScope.launch {
            val movedSet = moved.toSet()
            repo.reorderGroups(moved + repo.groupOrder.value.filterNot { it in movedSet })
        }
    }

    val railRootGroups: StateFlow<List<DiscoverGroup>> =
        railGroups
            .map { list ->
                // Parent links only count within the same relay: a same-id parent joined
                // on another relay is a different group and must not swallow this root.
                val joined = list.mapTo(HashSet()) { it.relayUrl to it.meta.id }
                list.filter { it.meta.parent == null || (it.relayUrl to it.meta.parent) !in joined }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Joined-scoped parent links (child id -> parent id, only when the parent is itself
     * joined). Feeding [rootGroupId] with this resolves any open channel to the rail
     * entry that should highlight, matching [railRootGroups]'s filter exactly.
     */
    val groupParents: StateFlow<Map<String, String>> =
        railGroups
            .map { list ->
                // Same-relay scoping as railRootGroups so highlight resolution matches its filter.
                val joined = list.mapTo(HashSet()) { it.relayUrl to it.meta.id }
                list.mapNotNull { g -> g.meta.parent?.takeIf { (g.relayUrl to it) in joined }?.let { g.meta.id to it } }.toMap()
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * Rail badge counts: each root's own unread plus all its descendant channels', so a
     * message in a subgroup still surfaces when the group is closed.
     */
    val railUnreadCounts: StateFlow<Map<String, Int>> =
        combine(railRootGroups, repo.childrenByParent, repo.unreadCounts) { roots, children, unread ->
            roots.associate { it.meta.id to aggregateUnread(it.meta.id, children, unread) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * The Home page's "My groups" list. Without a query: root groups only (same predicate
     * as [railRootGroups]) — a channel lives inside its root's sidebar, not as a home card;
     * one whose parent isn't joined stays, or it would be unreachable. With a query: search
     * over ALL joined groups (channels included) by name + description, as a jump-into-channel
     * shortcut. The groups rail / relay page read the unfiltered [myGroups] so the search box
     * never trims the sidebar.
     */
    val filteredMyGroups: StateFlow<List<DiscoverGroup>> =
        combine(myGroups, _query) { groups, q ->
            val needle = q.trim().lowercase()
            if (needle.isEmpty()) {
                val joinedIds = groups.mapTo(HashSet()) { it.meta.id }
                groups.filter { it.meta.parent == null || it.meta.parent !in joinedIds }
            } else {
                groups.filter { dg ->
                    (dg.meta.name ?: dg.meta.id).lowercase().contains(needle) ||
                        dg.meta.about.orEmpty().lowercase().contains(needle)
                }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * The active account's on-disk friends cache, TAGGED with the pubkey it belongs to. The
     * [friends] flow renders it only while its tag matches the live [NostrRepositoryApi.activePubkey],
     * so a switch can never surface the previous account's rows even in the window where the repo
     * reset and this VM's cache reload race. Reloaded for the switched-to account by the activePubkey
     * collector in [init]. Only identities are persisted (see [saveFollowingCacheFor]); each row's
     * avatar/name comes from the global user-metadata store, so metadata is not duplicated.
     */
    // Seeded empty (not by a synchronous loadFriendsCache): on Android that read is an
    // EncryptedSharedPreferences decrypt + JSON parse, and doing it in the constructor blocked the
    // first composition of the home shell. The init block hydrates it off the Main thread; until
    // then [friendsLoading] keeps the skeleton up.
    private val cachedFriends =
        MutableStateFlow(repo.activePubkey.value to emptyList<Friend>())

    /**
     * Stable order by display name (npub until kind:0 lands), then pubkey. Deliberately NOT
     * avatars-first: a metadata-keyed primary sort makes rows jump as kind:0 streams in, but we
     * want each row to keep its slot and hydrate its name/avatar in place.
     */
    private fun sortFriends(list: List<Friend>): List<Friend> = list.sortedWith(
        compareBy<Friend> { (it.metadata?.displayName ?: it.metadata?.name ?: it.pubkey).lowercase() }
            .thenBy { it.pubkey },
    )

    /**
     * Backstop "the new account's follow list has resolved" gate. Driven by [armContactsResolve]
     * (waits for contactListLoaded to cycle false->true on a switch, 15s cap) and forced false on
     * every switch by the activePubkey collector, so a stale "resolved" can't surface the previous
     * account's live `following`. [friends] trusts the live `following` only once this is true;
     * until then it shows the tagged on-disk cache (or nothing), never the racing live list.
     */
    private val _contactsResolved = MutableStateFlow(false)

    /**
     * The users the active account follows (kind:3), each enriched with kind:0 metadata (live, or
     * the disk cache until it arrives). While the account's list is still resolving it shows the
     * tagged on-disk cache so rows paint immediately (identicon + npub placeholders that hydrate in
     * place); it switches to the live `following` only once [_contactsResolved] confirms the list is
     * the new account's, so a switch never leaks the previous account's rows.
     */
    val friends: StateFlow<List<Friend>> =
        combine(
            repo.following,
            repo.userMetadata,
            repo.activePubkey,
            cachedFriends,
            _contactsResolved,
        ) { following, meta, activePk, cached, resolved ->
            // Trust the cache only under the account it was loaded for (tag == active pubkey).
            val safeCache = if (cached.first == activePk) cached.second else emptyList()
            when {
                // Not yet resolved for this account: show its tagged cache, hydrating each row's
                // metadata live. Never the racing `following` (it briefly holds the prior account's).
                !resolved -> sortFriends(safeCache.map { f -> Friend(f.pubkey, meta[f.pubkey] ?: f.metadata) })
                following.isNotEmpty() -> {
                    val cacheByPk = safeCache.associateBy { it.pubkey }
                    sortFriends(following.map { pk -> Friend(pk, meta[pk] ?: cacheByPk[pk]?.metadata) })
                }
                // Resolved and genuinely follows nobody: empty, never the now-stale cache.
                else -> emptyList()
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, sortFriends(cachedFriends.value.second))

    /**
     * Show the friends skeleton only until there are rows to paint: the moment the tagged cache
     * loads OR the live list resolves, [friends] is non-empty and the skeleton drops (rows render
     * with placeholders, names/avatars hydrate in place). It stays up on a switch to an uncached
     * account until its kind:3 resolves, and [_contactsResolved] bounds that wait, so the skeleton
     * can neither flash the previous user's rows nor hang forever.
     */
    val friendsLoading: StateFlow<Boolean> =
        combine(_contactsResolved, friends) { resolved, fr -> !resolved && fr.isEmpty() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, cachedFriends.value.second.isEmpty())

    // Frozen first-seen display order for From friends (groupId -> slot), so the grid
    // doesn't reshuffle as member counts and new groups stream in.
    private val friendsOrder = mutableMapOf<String, Int>()
    private var friendsOrderNext = 0

    /**
     * Groups people you follow are in that you are NOT already in (discovery via
     * the social graph). Two signals are merged per group: a friend's public
     * kind:10009 list, and friends who appear in a group's member list (kind:39002)
     * for groups whose metadata we already have. New groups append after those
     * already shown (stable order); within a batch, ranked by friend overlap, then
     * name. Populated on demand by [loadFriendsGroups] (the "From friends" tab).
     */
    @Suppress("UNCHECKED_CAST")
    val friendsGroups: StateFlow<List<DiscoverGroup>> =
        combine(
            listOf(
                repo.following,
                repo.userGroupLists,
                repo.joinedGroupsByRelay,
                repo.groupsByRelay,
                repo.groupMembers,
                repo.userMetadata,
                _query,
                repo.unreachableRelays,
            ),
        ) { arr ->
            val following = arr[0] as Set<String>
            val lists = arr[1] as Map<String, List<UserGroupRef>>
            val joinedByRelay = arr[2] as Map<String, Set<String>>
            val byRelay = arr[3] as Map<String, List<GroupMetadata>>
            val members = arr[4] as Map<String, List<String>>
            val meta = arr[5] as Map<String, UserMetadata>
            val needle = (arr[6] as String).trim().lowercase()
            val unreachable = arr[7] as Set<String>

            if (following.isEmpty()) return@combine emptyList()
            val myGroupIds = joinedByRelay.values.flatten().toSet()
            val friendsByGroup = LinkedHashMap<String, LinkedHashSet<String>>()
            val relayByGroup = HashMap<String, String>()

            // Signal 1: each friend's public kind:10009 group refs.
            following.forEach { friend ->
                lists[friend].orEmpty().forEach { ref ->
                    if (ref.groupId in myGroupIds) return@forEach
                    friendsByGroup.getOrPut(ref.groupId) { LinkedHashSet() }.add(friend)
                    if (ref.groupId !in relayByGroup && ref.relayUrl.isNotBlank()) {
                        relayByGroup[ref.groupId] = ref.relayUrl
                    }
                }
            }

            // Signal 2: friends present in a visible group's member list (kind:39002).
            members.forEach { (gid, memberPks) ->
                if (gid in myGroupIds) return@forEach
                val friendMembers = memberPks.filter { it in following }
                if (friendMembers.isEmpty()) return@forEach
                friendsByGroup.getOrPut(gid) { LinkedHashSet() }.addAll(friendMembers)
                if (gid !in relayByGroup) {
                    byRelay.entries.firstOrNull { e -> e.value.any { it.id == gid } }?.let { relayByGroup[gid] = it.key }
                }
            }

            friendsByGroup.entries
                .mapNotNull { (gid, friendPks) ->
                    val relay = relayByGroup[gid] ?: return@mapNotNull null
                    // Resolve the real kind:39000 metadata. A card with none is dropped by
                    // the hasMetadata filter below (almost always a private group the relay
                    // won't describe to non-members); the placeholder only carries the id so
                    // the people/member preview can still be built for groups that do resolve.
                    val m = byRelay[relay].orEmpty().find { it.id == gid }
                        ?: byRelay.values.flatten().find { it.id == gid }
                    DiscoverGroup(
                        relayUrl = relay,
                        meta = m ?: GroupMetadata(
                            id = gid,
                            name = null,
                            about = null,
                            picture = null,
                            isPublic = true,
                            isOpen = true,
                        ),
                        // Friends discovered here first, then other members fill the
                        // row up to PEOPLE_PREVIEW.
                        people = previewPeople(
                            memberPks = members[gid].orEmpty(),
                            following = following,
                            meta = meta,
                            priorityFirst = friendPks.toList(),
                        ),
                        // Prefer the relay's full member count; fall back to the
                        // known friends until the member list (kind:39002) arrives.
                        memberCount = members[gid].orEmpty().size.takeIf { it > 0 } ?: friendPks.size,
                        // A friend group always has at least one friend to show.
                        peopleLoading = false,
                        hasMetadata = m != null,
                    )
                }
                .filter { it.relayUrl.normalizeRelayUrl() !in unreachable }
                // Hidden groups are not discoverable (NIP-29), so never list them here
                // even if a friend's list or a relay returns them.
                .filter { !it.meta.isHidden }
                // Only show groups whose real kind:39000 has arrived. A friend's group
                // with no metadata is almost always private (relays don't serve a private
                // group's kind:39000 to non-members), so it would otherwise sit forever as
                // a bare-id card; public groups appear as soon as their metadata lands.
                .filter { it.hasMetadata }
                .let { all ->
                    // Freeze display order: a group keeps its slot once shown, and
                    // newcomers (ranked by friend overlap, then name, among themselves)
                    // are appended after it. Without this the whole grid reshuffles every
                    // time a member count or a new group streams in (see screencasts).
                    all.asSequence()
                        .filter { it.meta.id !in friendsOrder }
                        .sortedWith(
                            compareByDescending<DiscoverGroup> { dg -> dg.people.count { it.pubkey in following } }
                                .thenBy { (it.meta.name ?: it.meta.id).lowercase() },
                        )
                        .forEach { friendsOrder.getOrPut(it.meta.id) { friendsOrderNext++ } }
                    all.filter { matchesQuery(it.meta, needle) }
                        .sortedBy { friendsOrder[it.meta.id] ?: Int.MAX_VALUE }
                }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Curated "Recommended" groups: the [CURATOR_PUBKEY]'s public kind:10009 list,
     * minus the groups you already joined. Populated on demand by [loadRecommended].
     */
    @Suppress("UNCHECKED_CAST")
    val recommendedGroups: StateFlow<List<DiscoverGroup>> =
        combine(
            listOf(
                repo.userGroupLists,
                repo.joinedGroupsByRelay,
                repo.groupsByRelay,
                repo.following,
                repo.groupMembers,
                repo.userMetadata,
                _query,
                _membersResolved,
                repo.unreachableRelays,
            ),
        ) { arr ->
            val lists = arr[0] as Map<String, List<UserGroupRef>>
            val joinedByRelay = arr[1] as Map<String, Set<String>>
            val byRelay = arr[2] as Map<String, List<GroupMetadata>>
            val following = arr[3] as Set<String>
            val members = arr[4] as Map<String, List<String>>
            val meta = arr[5] as Map<String, UserMetadata>
            val needle = (arr[6] as String).trim().lowercase()
            val resolved = arr[7] as Set<String>
            val unreachable = arr[8] as Set<String>
            val myGroupIds = joinedByRelay.values.flatten().toSet()
            lists[CURATOR_PUBKEY].orEmpty()
                .filter { it.groupId !in myGroupIds }
                .filter { it.relayUrl.normalizeRelayUrl() !in unreachable }
                .mapNotNull { ref ->
                    val gid = ref.groupId
                    // Require real kind:39000 metadata: a group the relay never
                    // described is not shown on discovery (no bare-id placeholders).
                    val m = byRelay[ref.relayUrl].orEmpty().find { it.id == gid }
                        ?: byRelay.values.flatten().find { it.id == gid }
                        ?: return@mapNotNull null
                    val memberPks = members[gid].orEmpty()
                    // People you follow first, then other members as social proof.
                    val preview = previewPeople(memberPks, following, meta)
                    DiscoverGroup(
                        relayUrl = ref.relayUrl,
                        meta = m,
                        people = preview,
                        memberCount = memberPks.size,
                        peopleLoading = preview.isEmpty() && gid !in resolved,
                        hasMetadata = true,
                    )
                }
                // Hidden groups are not discoverable (NIP-29); never recommend them.
                .filter { !it.meta.isHidden }
                .filter { matchesQuery(it.meta, needle) }
                // (relay, id): the same id on two relays is two independent groups.
                .distinctBy { it.relayUrl to it.meta.id }
            // Off Main: the previewPeople / associateBy / distinctBy transform is O(groups x members)
            // and re-runs for each of the ~50 metadata events that arrive in waves on home open;
            // running it on viewModelScope's Main dispatcher made it compete with composition. The
            // transform is pure, so flowOn is correctness-safe; stateIn still publishes on Main.
        }.flowOn(computeDispatcher).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Each tab shows skeletons while empty AND unresolved; the empty-state message
    // appears only once resolved (first group arrived, or the grace period elapsed).
    // The stateIn seed must already be the loading value so the empty message never
    // flashes for a frame before the combine emits (the bug the tab-load videos show).
    private val _myGroupsResolved = MutableStateFlow(false)
    val myGroupsLoading: StateFlow<Boolean> =
        combine(_myGroupsResolved, myGroups) { resolved, list -> !resolved && list.isEmpty() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _friendsGroupsResolved = MutableStateFlow(false)
    val friendsGroupsLoading: StateFlow<Boolean> =
        combine(_friendsGroupsResolved, friendsGroups, friends) { resolved, list, fr ->
            // Only "loading" when you actually follow people; with nobody followed the
            // tab shows the "follow someone" empty state, not skeletons.
            !resolved && list.isEmpty() && fr.isNotEmpty()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // Onboarding step 3 has no "follow someone" empty state, so it must show the skeleton
    // while discovery is still in-flight even before the contact list resolves (otherwise
    // the step flashes blank until groups pop in). Resolves via the same grace timer, so it
    // can neither hang forever nor flash an empty section for a frame.
    val friendsGroupsResolving: StateFlow<Boolean> =
        combine(_friendsGroupsResolved, friendsGroups) { resolved, list -> !resolved && list.isEmpty() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _recommendedResolved = MutableStateFlow(false)
    val recommendedGroupsLoading: StateFlow<Boolean> =
        combine(_recommendedResolved, recommendedGroups) { resolved, list -> !resolved && list.isEmpty() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private var recommendedRequested = false
    private var friendsGraceStarted = false

    /** Fetch the curator's kind:10009 once, when the "Recommended" tab opens. */
    fun loadRecommended() {
        if (recommendedRequested) return
        recommendedRequested = true
        viewModelScope.launch { repo.requestUserGroupList(CURATOR_PUBKEY) }
        viewModelScope.launch {
            withTimeoutOrNull(DISCOVERY_GRACE_MS) { recommendedGroups.first { it.isNotEmpty() } }
            _recommendedResolved.value = true
        }
    }

    /** Friends whose kind:10009 we've already requested, so a tab re-open doesn't refire. */
    private val requestedFriendLists = mutableSetOf<String>()

    /** Group ids whose member list (kind:39002) we've already requested for a card. */
    private val requestedMembers = mutableSetOf<String>()

    /** Pubkeys whose kind:0 we've requested for a Recommended card preview. */
    private val requestedPeopleMeta = mutableSetOf<String>()

    /**
     * Fetch the public group list (kind:10009) of each followed user not yet
     * requested. Called when the "From friends" tab opens so we don't pay this on
     * cold start; picks up newly followed users on a later call.
     *
     * Batched (one REQ per relay, not one per followed user) — a friends list commonly
     * clusters on the same handful of popular outbox relays, and a per-author loop was
     * observed opening 20+ concurrent subscriptions against a single relay and tripping
     * its "too many concurrent REQs" limit.
     */
    fun loadFriendsGroups() {
        if (!friendsGraceStarted) {
            friendsGraceStarted = true
            viewModelScope.launch {
                withTimeoutOrNull(DISCOVERY_GRACE_MS) { friendsGroups.first { it.isNotEmpty() } }
                _friendsGroupsResolved.value = true
            }
        }
        viewModelScope.launch {
            val pks = repo.following.value - requestedFriendLists
            if (pks.isEmpty()) return@launch
            requestedFriendLists.addAll(pks)
            repo.fetchUserGroupLists(pks)
        }
    }

    // "My groups" shows skeletons until the first joined group resolves or the grace
    // elapses, so a switch never flashes the onboarding empty state before the new
    // account's groups load.
    private fun armMyGroupsResolve() {
        viewModelScope.launch {
            withTimeoutOrNull(DISCOVERY_GRACE_MS) { myGroups.first { it.isNotEmpty() } }
            _myGroupsResolved.value = true
        }
    }

    private var contactsResolveJob: Job? = null

    // Stop showing the friends skeleton once the account's kind:3 fetch has actually
    // resolved (repo.contactListLoaded), not on a blind wall-clock from the switch.
    // On a warm swap requestContactList() only runs after the relays reconnect, so a
    // short fixed timeout fired while kind:3 was still in flight and flashed "You don't
    // follow anyone yet." even for accounts with follows.
    //
    // The reset of contactListLoaded (resetContactListState) and this re-arm run on
    // separate coroutines on a switch, so on entry contactListLoaded may still be the
    // PREVIOUS account's lingering `true`. Latching on that would resolve instantly and
    // skip the skeleton. So wait for the reset boundary (it goes false) before accepting
    // the new account's load (it goes true). On cold start it is already false, so the
    // first wait returns immediately. The outer timeout only covers the offline case
    // where no REQ ever goes out; a stale arm from a prior switch is cancelled first.
    private fun armContactsResolve() {
        contactsResolveJob?.cancel()
        contactsResolveJob =
            viewModelScope.launch {
                withTimeoutOrNull(15_000) {
                    repo.contactListLoaded.first { !it }
                    repo.contactListLoaded.first { it }
                }
                _contactsResolved.value = true
            }
    }

    init {
        armMyGroupsResolve()
        // Hydrate the on-disk friends cache off the Main thread (see [cachedFriends]); the tag guard
        // (cached.first == active pubkey) keeps it scoped to this account if a switch races in.
        viewModelScope.launch(Dispatchers.Default) {
            val pk = repo.activePubkey.value
            val rows = loadFriendsCache(pk)
            if (rows.isNotEmpty() && repo.activePubkey.value == pk) cachedFriends.value = pk to rows
        }
        // Load the own contact list and resolve names for each followed user so the
        // Friends list renders display names rather than bare npubs.
        viewModelScope.launch { repo.requestContactList() }
        viewModelScope.launch {
            repo.following.collect { pks -> if (pks.isNotEmpty()) repo.requestUserMetadata(pks) }
        }
        armContactsResolve()
        // The VM outlives account switches, so re-arm the per-account loading state when
        // the active account changes: reload the friends cache for the new pubkey and
        // reset the resolved gates so groups/friends show skeletons until the new
        // account's data lands, instead of flashing the previous account's rows or the
        // "follows nobody" / onboarding empty states. drop(1): the current account is
        // already handled by the initial arming above.
        viewModelScope.launch {
            repo.activePubkey.drop(1).collect { pk ->
                // Reset the gates SYNCHRONOUSLY first (the friends flow reads _contactsResolved), so
                // the skeleton shows instantly and the previous account's tagged cache / live follows
                // are gated out. Forcing the resolved gate false until the new kind:3 resolves.
                _contactsResolved.value = false
                _myGroupsResolved.value = false
                _friendsGroupsResolved.value = false
                _recommendedResolved.value = false
                friendsGraceStarted = false
                recommendedRequested = false
                requestedFriendLists.clear()
                armMyGroupsResolve()
                armContactsResolve()
                // Re-request the switched-to account's kind:3 (init does this on cold
                // start). reloadForActiveAccount also requests it, but only after the relay
                // reconnect; this screen-timed retry closes the gap where that single
                // attempt races the reconnect and the new account's follows never repopulate.
                viewModelScope.launch { repo.requestContactList() }
                // Then hydrate the cache off the Main thread, TAGGED with the new pubkey (the tag !=
                // active pubkey guard blocks the previous account's rows). Re-check the active pubkey
                // after the load so a fast second switch doesn't clobber it with stale rows.
                val rows = withContext(computeDispatcher) { loadFriendsCache(pk) }
                if (repo.activePubkey.value == pk) cachedFriends.value = pk to rows
            }
        }
        // Persist the followed pubkey list so the next launch shows the sidebar rows
        // instantly (their avatars/names come from the global metadata store). Only once
        // kind:3 has loaded, so we don't overwrite the cache with the empty pre-load state.
        viewModelScope.launch {
            // Persist only once kind:3 has loaded, so the empty pre-load state doesn't wipe
            // the cache; when loaded, mirror [following] exactly, clearing it if you now
            // follow nobody (otherwise the stale cache would reappear next launch).
            combine(repo.following, repo.contactListLoaded) { following, loaded -> following to loaded }
                .collect { (following, loaded) ->
                    val pk = repo.getPublicKey() ?: return@collect
                    if (loaded) SecureStorage.saveFollowingCacheFor(pk, following.toList())
                }
        }
        // Backfill metadata for discovered friend groups. We aggregate every friend's
        // kind:10009 refs into a deduplicated relayUrl -> {groupId} map (each group once,
        // each relay once) of groups we still lack metadata for, and hand it to the repo,
        // which connects to each relay a single time and batches one kind:39000 REQ.
        // Depending on groupsByRelay re-runs the fetch as metadata partially arrives, so a
        // relay that was slow or briefly unreachable on the first attempt is retried on the
        // next state change instead of being abandoned for the session; distinctUntilChanged
        // suppresses identical re-requests, and a group drops out once its metadata lands.
        viewModelScope.launch {
            combine(
                repo.following,
                repo.userGroupLists,
                repo.joinedGroupsByRelay,
                repo.groupsByRelay,
            ) { following, lists, joinedByRelay, byRelay ->
                val myGroupIds = joinedByRelay.values.flatten().toSet()
                val haveMetadata = byRelay.values.flatten().map { it.id }.toSet()
                val relayToGroups = HashMap<String, MutableSet<String>>()
                // Curated (recommended) groups need metadata too; fold them into the
                // same deduped per-relay fetch as the friends' groups.
                (following + CURATOR_PUBKEY).forEach { friend ->
                    lists[friend].orEmpty().forEach { ref ->
                        if (ref.relayUrl.isBlank() || ref.groupId in myGroupIds || ref.groupId in haveMetadata) return@forEach
                        relayToGroups.getOrPut(ref.relayUrl) { mutableSetOf() }.add(ref.groupId)
                    }
                }
                relayToGroups as Map<String, Set<String>>
            }.distinctUntilChanged().collect { relayToGroups ->
                if (relayToGroups.isNotEmpty()) repo.fetchGroupPreviews(relayToGroups)
            }
        }
        // Member lists (kind:39002) for every group card (My groups + From friends +
        // Recommended), so each shows a real "N people" count and member avatars and the
        // people-row skeleton resolves. Each group is requested once; the discovery flows
        // only emit after their tab loads, so this stays off the cold-start path.
        // Batched per relay (one REQ per relay, not one per group) — a discovery relay's
        // general directory can list dozens of public groups, and firing an individual
        // members_ subscription for each one was observed opening 40+ REQ/CLOSE pairs
        // against a single relay for one tab load.
        viewModelScope.launch {
            combine(myGroups, friendsGroups, recommendedGroups) { a, b, c -> a + b + c }
                .collect { discovered ->
                    val toRequest = discovered.filter { requestedMembers.add(it.meta.id) }
                    if (toRequest.isEmpty()) return@collect
                    toRequest.forEach { g ->
                        // Stop the people-row skeleton after a grace period even if
                        // the relay never returns a member list, so a card never
                        // shimmers forever.
                        launch {
                            delay(MEMBERS_RESOLVE_TIMEOUT_MS)
                            _membersResolved.update { it + g.meta.id }
                        }
                    }
                    val relayToGroups = toRequest
                        .groupBy({ it.relayUrl }, { it.meta.id })
                        .mapValues { (_, ids) -> ids.toSet() }
                    launch { repo.fetchGroupsMembers(relayToGroups) }
                }
        }
        // Resolve kind:0 for the people previewed on every card (members who are not
        // already followed have no metadata yet). Friends' own metadata is already
        // fetched above from [following].
        viewModelScope.launch {
            combine(myGroups, friendsGroups, recommendedGroups) { a, b, c -> a + b + c }
                .map { groups -> groups.flatMap { it.people }.map { it.pubkey }.toSet() }
                .collect { pks ->
                    val missing = pks - requestedPeopleMeta
                    if (missing.isNotEmpty()) {
                        requestedPeopleMeta.addAll(missing)
                        repo.requestUserMetadata(missing)
                    }
                }
        }
    }
}
