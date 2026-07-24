package org.nostr.nostrord.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.nostr.nostrord.network.FakeNostrRepository
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.notifications.NotificationEntry
import org.nostr.nostrord.notifications.NotificationHistoryStore
import org.nostr.nostrord.notifications.NotificationType
import org.nostr.nostrord.ui.screens.home.Friend
import org.nostr.nostrord.ui.screens.home.HomePageViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class HomePageViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        testDispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    private fun meta(
        id: String,
        name: String,
        about: String? = null,
        parent: String? = null,
    ) = GroupMetadata(id = id, name = name, about = about, picture = null, isPublic = true, isOpen = true, parent = parent)

    @Test
    fun `myGroups joins kind10009 ids with metadata across relays`() = runTest {
        val fake = FakeNostrRepository()
        fake._groupsByRelay.value =
            mapOf(
                "wss://a" to listOf(meta("g1", "Alpha"), meta("g2", "Beta")),
                "wss://b" to listOf(meta("g3", "Gamma")),
            )
        fake._joinedGroupsByRelay.value =
            mapOf(
                "wss://a" to setOf("g1"),
                "wss://b" to setOf("g3", "unknown"),
            )
        val vm = HomePageViewModel(fake, computeDispatcher = testDispatcher)
        testDispatcher.scheduler.advanceUntilIdle()

        val ids = vm.myGroups.value.map { it.meta.id }
        assertEquals(listOf("g1", "g3", "unknown"), ids)
        // Each entry carries its hosting relay, for navigation.
        assertEquals(listOf("wss://a", "wss://b", "wss://b"), vm.myGroups.value.map { it.relayUrl })
        // Unknown ids fall back to a bare-id placeholder until the kind:39000 arrives.
        assertEquals(null, vm.myGroups.value.last().meta.name)
    }

    @Test
    fun `same group id on two relays stays as two groups`() = runTest {
        val fake = FakeNostrRepository()
        fake._groupsByRelay.value =
            mapOf(
                "wss://a" to listOf(meta("dev", "Dev on A")),
                "wss://b" to listOf(meta("dev", "Dev on B")),
            )
        fake._joinedGroupsByRelay.value =
            mapOf(
                "wss://a" to setOf("dev"),
                "wss://b" to setOf("dev"),
            )
        val vm = HomePageViewModel(fake, computeDispatcher = testDispatcher)
        testDispatcher.scheduler.advanceUntilIdle()

        // Two independent groups, each with its own relay's metadata; both on the rail.
        assertEquals(
            listOf("wss://a" to "Dev on A", "wss://b" to "Dev on B"),
            vm.myGroups.value.map { it.relayUrl to it.meta.name },
        )
        assertEquals(2, vm.railRootGroups.value.size)
    }

    @Test
    fun `query filters by name and description`() = runTest {
        val fake = FakeNostrRepository()
        fake._groupsByRelay.value =
            mapOf("wss://a" to listOf(meta("g1", "Alpha", "cats"), meta("g2", "Beta", "dogs")))
        fake._joinedGroupsByRelay.value = mapOf("wss://a" to setOf("g1", "g2"))
        val vm = HomePageViewModel(fake, computeDispatcher = testDispatcher)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setQuery("dogs")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("g2"), vm.filteredMyGroups.value.map { it.meta.id })
        // The search must not trim the rail's unfiltered list.
        assertEquals(listOf("g1", "g2"), vm.myGroups.value.map { it.meta.id })

        vm.setQuery("ALPHA")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("g1"), vm.filteredMyGroups.value.map { it.meta.id })
    }

    @Test
    fun `home list hides channels whose parent is joined but search still finds them`() = runTest {
        val fake = FakeNostrRepository()
        fake._groupsByRelay.value =
            mapOf(
                "wss://a" to
                    listOf(
                        meta("root", "Server"),
                        meta("chan", "General chat", parent = "root"),
                        meta("stray", "Stray channel", parent = "notjoined"),
                    ),
            )
        fake._joinedGroupsByRelay.value = mapOf("wss://a" to setOf("root", "chan", "stray"))
        val vm = HomePageViewModel(fake, computeDispatcher = testDispatcher)
        testDispatcher.scheduler.advanceUntilIdle()

        // No query: roots only. A channel whose parent isn't joined stays, or it
        // would be unreachable from the home page.
        assertEquals(listOf("root", "stray"), vm.filteredMyGroups.value.map { it.meta.id })

        // Searching reaches hidden channels as a jump-into shortcut.
        vm.setQuery("chat")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("chan"), vm.filteredMyGroups.value.map { it.meta.id })
    }

    @Test
    fun `friends lists following enriched with metadata sorted by name`() = runTest {
        val fake = FakeNostrRepository()
        // kind:3 sets following and contactListLoaded together (see NostrRepository).
        fake._following.value = setOf("pkB", "pkA")
        fake._contactListLoaded.value = true
        fake._userMetadata.value =
            mapOf(
                "pkA" to org.nostr.nostrord.network.UserMetadata(
                    pubkey = "pkA",
                    name = "zoe",
                    displayName = "Zoe",
                    picture = null,
                    about = null,
                    nip05 = null,
                ),
                "pkB" to org.nostr.nostrord.network.UserMetadata(
                    pubkey = "pkB",
                    name = "amy",
                    displayName = "Amy",
                    picture = null,
                    about = null,
                    nip05 = null,
                ),
            )
        val vm = HomePageViewModel(fake, computeDispatcher = testDispatcher)
        testDispatcher.scheduler.advanceUntilIdle()

        // Sorted by display name: Amy before Zoe, each carrying its metadata.
        assertEquals(listOf("pkB", "pkA"), vm.friends.value.map { it.pubkey })
        assertEquals(listOf("Amy", "Zoe"), vm.friends.value.map { it.metadata?.displayName })
    }

    @Test
    fun `friends is empty for a loaded account that follows nobody`() = runTest {
        // The friends sidebar must NOT keep the previous account's rows once the
        // new (loaded) account follows nobody: derive an empty list, not a stale cache.
        val fake = FakeNostrRepository()
        fake._following.value = emptySet()
        fake._contactListLoaded.value = true
        val vm = HomePageViewModel(fake, computeDispatcher = testDispatcher)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(emptyList(), vm.friends.value.map { it.pubkey })
    }

    @Test
    fun `switching account updates friends within a single VM`() = runTest {
        // The VM outlives account switches; changing the active pubkey + following
        // must surface the new account's follows (the account-switch collector
        // re-arms per-account state instead of keeping account A's rows).
        val fake = FakeNostrRepository()
        fake._activePubkey.value = "a".repeat(64)
        fake._following.value = setOf("a1")
        fake._contactListLoaded.value = true
        val vm = HomePageViewModel(fake, computeDispatcher = testDispatcher)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("a1"), vm.friends.value.map { it.pubkey })

        // Switch to account B: repo signals the new active pubkey and its follows.
        fake._activePubkey.value = "b".repeat(64)
        fake._following.value = setOf("b1", "b2")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(setOf("b1", "b2"), vm.friends.value.map { it.pubkey }.toSet())
    }

    @Test
    fun `switching to an account whose kind3 is still loading keeps the friends skeleton`() = runTest {
        // On a warm switch requestContactList only runs after the relays reconnect, so
        // kind:3 often lands well after any fixed wall-clock grace. The friends loading
        // gate must follow contactListLoaded, not a blind timer, or the sidebar flashes
        // "You don't follow anyone yet." for an account that does have follows.
        val fake = FakeNostrRepository()
        fake._activePubkey.value = "a".repeat(64)
        fake._following.value = setOf("a1")
        fake._contactListLoaded.value = true
        val vm = HomePageViewModel(fake, computeDispatcher = testDispatcher)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(false, vm.friendsLoading.value)

        // Switch to B: the active pubkey changes while account A's following AND
        // contactListLoaded still linger (the repo reset runs on a separate coroutine).
        // The skeleton must show immediately, never the previous account's rows.
        fake._activePubkey.value = "b".repeat(64)
        testDispatcher.scheduler.runCurrent()
        // Skeleton up immediately, so the lingering a1 row is never shown.
        assertEquals(true, vm.friendsLoading.value)

        // The repo then resets B's contact-list state (loaded -> false, following empty).
        fake._following.value = emptySet()
        fake._contactListLoaded.value = false
        testDispatcher.scheduler.runCurrent()

        // Well past the old fixed grace: still loading (skeleton), never the empty state.
        testDispatcher.scheduler.advanceTimeBy(5_000)
        testDispatcher.scheduler.runCurrent()
        assertEquals(true, vm.friendsLoading.value)
        assertEquals(emptyList(), vm.friends.value.map { it.pubkey })

        // B's kind:3 finally resolves with follows: rows appear, skeleton clears.
        fake._following.value = setOf("b1", "b2")
        fake._contactListLoaded.value = true
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(false, vm.friendsLoading.value)
        assertEquals(setOf("b1", "b2"), vm.friends.value.map { it.pubkey }.toSet())
    }

    @Test
    fun `switching account re-arms the my-groups loading skeleton`() = runTest {
        // Account A has a joined group, so My groups is resolved (no skeleton).
        val fake = FakeNostrRepository()
        fake._activePubkey.value = "a".repeat(64)
        fake._joinedGroupsByRelay.value = mapOf("wss://a" to setOf("g1"))
        fake._groupsByRelay.value = mapOf("wss://a" to listOf(meta("g1", "Alpha")))
        val vm = HomePageViewModel(fake, computeDispatcher = testDispatcher)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(false, vm.myGroupsLoading.value)

        // Switch to account B whose groups haven't arrived: the skeleton must come
        // back (resolved gate reset) rather than flashing the onboarding empty state.
        fake._joinedGroupsByRelay.value = emptyMap()
        fake._activePubkey.value = "b".repeat(64)
        testDispatcher.scheduler.runCurrent()
        assertEquals(true, vm.myGroupsLoading.value)
    }

    @Test
    fun `switching account re-requests the contact list`() = runTest {
        // The VM outlives switches, so init's single requestContactList only covers cold
        // start. Each switch must re-fire it for the new account, or the new account's
        // kind:3 (and thus its friends) may never repopulate when the repo's own reload
        // races the relay reconnect.
        val fake = FakeNostrRepository()
        fake._activePubkey.value = "a".repeat(64)
        HomePageViewModel(fake, computeDispatcher = testDispatcher)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, fake.requestContactListCount)

        fake._activePubkey.value = "b".repeat(64)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, fake.requestContactListCount)
    }

    @Test
    fun `switching account never leaks the previous account's cached friends`() = runTest {
        // The friends flow must never render account A's cached rows under account B. The cache is
        // tagged with its pubkey; on a switch the tag stops matching the active pubkey, so A's rows
        // vanish even before B's own cache/kind:3 loads. runCurrent (no virtual-time advance) keeps
        // the resolved backstop from firing so we observe the cache branch, where the leak lived.
        val cacheA = listOf(Friend("a1", null), Friend("a2", null))
        val fake = FakeNostrRepository()
        fake._activePubkey.value = "a".repeat(64)
        val vm = HomePageViewModel(fake, loadFriendsCache = { pk -> if (pk == "a".repeat(64)) cacheA else emptyList() }, computeDispatcher = testDispatcher)
        testDispatcher.scheduler.runCurrent()
        // Account A's cached friends paint immediately (placeholders), no skeleton.
        assertEquals(setOf("a1", "a2"), vm.friends.value.map { it.pubkey }.toSet())
        assertEquals(false, vm.friendsLoading.value)

        // Switch to B (no cache): A's rows must be gone, not leaked under B; skeleton returns.
        fake._activePubkey.value = "b".repeat(64)
        testDispatcher.scheduler.runCurrent()
        assertEquals(emptyList(), vm.friends.value.map { it.pubkey })
        assertEquals(true, vm.friendsLoading.value)
    }

    @Test
    fun `friendsGroups lists friends' groups excluding mine ranked by overlap`() = runTest {
        val fake = FakeNostrRepository()
        fake._following.value = setOf("alice", "bob")
        fake._userGroupLists.value =
            mapOf(
                "alice" to
                    listOf(
                        org.nostr.nostrord.network.UserGroupRef("wss://a", "g1"),
                        org.nostr.nostrord.network.UserGroupRef("wss://a", "g2"),
                    ),
                "bob" to listOf(org.nostr.nostrord.network.UserGroupRef("wss://a", "g1")),
            )
        // I'm already in g2, so it must be excluded from discovery.
        fake._joinedGroupsByRelay.value = mapOf("wss://a" to setOf("g2"))
        fake._groupsByRelay.value = mapOf("wss://a" to listOf(meta("g1", "Group One")))

        val vm = HomePageViewModel(fake, computeDispatcher = testDispatcher)
        testDispatcher.scheduler.advanceUntilIdle()

        val fgs = vm.friendsGroups.value
        assertEquals(listOf("g1"), fgs.map { it.meta.id })
        assertEquals(setOf("alice", "bob"), fgs.single().people.map { it.pubkey }.toSet())
        assertEquals("Group One", fgs.single().meta.name)
    }

    @Test
    fun `friends' group metadata is fetched deduped by relay and group`() = runTest {
        val fake = FakeNostrRepository()
        fake._following.value = setOf("alice", "bob")
        fake._userGroupLists.value =
            mapOf(
                // alice and bob both list g1 on the same relay -> one fetch; g2 is mine.
                "alice" to
                    listOf(
                        org.nostr.nostrord.network.UserGroupRef("wss://a", "g1"),
                        org.nostr.nostrord.network.UserGroupRef("wss://a", "g2"),
                    ),
                "bob" to
                    listOf(
                        org.nostr.nostrord.network.UserGroupRef("wss://a", "g1"),
                        org.nostr.nostrord.network.UserGroupRef("wss://b", "g3"),
                    ),
            )
        fake._joinedGroupsByRelay.value = mapOf("wss://a" to setOf("g2"))

        HomePageViewModel(fake, computeDispatcher = testDispatcher)
        testDispatcher.scheduler.advanceUntilIdle()

        val byRelay =
            fake.fetchGroupPreviewsCalls
                .flatMap { it.entries }
                .groupBy({ it.key }, { it.value })
                .mapValues { (_, sets) -> sets.flatten().toSet() }
        // g1 once on wss://a (deduped across alice+bob), g3 on wss://b, g2 excluded (mine).
        assertEquals(setOf("g1"), byRelay.getValue("wss://a"))
        assertEquals(setOf("g3"), byRelay.getValue("wss://b"))
    }

    @Test
    fun `myGroups people preview shows friends first then fills excluding self capped at five`() = runTest {
        val fake = FakeNostrRepository()
        fake.fakePublicKey = "me"
        fake._groupsByRelay.value = mapOf("wss://a" to listOf(meta("g1", "Alpha")))
        fake._joinedGroupsByRelay.value = mapOf("wss://a" to setOf("g1"))
        fake._following.value = setOf("f1", "f2")
        // self + 2 friends + 4 others, interleaved, to prove ordering and the self/cap rules.
        fake._groupMembers.value = mapOf("g1" to listOf("me", "x1", "f1", "x2", "x3", "f2", "x4"))

        val vm = HomePageViewModel(fake, computeDispatcher = testDispatcher)
        testDispatcher.scheduler.advanceUntilIdle()

        // Friends first (member order), then other members to fill, self excluded, max 5.
        assertEquals(
            listOf("f1", "f2", "x1", "x2", "x3"),
            vm.myGroups.value.single().people.map { it.pubkey },
        )
        // The "N people" count is the full membership (self included).
        assertEquals(7, vm.myGroups.value.single().memberCount)
    }

    @Test
    fun `myGroups small group includes the logged-in user while large group excludes it`() = runTest {
        val fake = FakeNostrRepository()
        fake.fakePublicKey = "me"
        fake._groupsByRelay.value =
            mapOf("wss://a" to listOf(meta("small", "Small"), meta("big", "Big")))
        fake._joinedGroupsByRelay.value = mapOf("wss://a" to setOf("small", "big"))
        fake._following.value = emptySet()
        fake._groupMembers.value =
            mapOf(
                // <=5 members: self shown.
                "small" to listOf("me", "x1", "x2"),
                // >5 members: self omitted.
                "big" to listOf("me", "x1", "x2", "x3", "x4", "x5"),
            )

        val vm = HomePageViewModel(fake, computeDispatcher = testDispatcher)
        testDispatcher.scheduler.advanceUntilIdle()

        val byId = vm.myGroups.value.associate { it.meta.id to it.people.map { p -> p.pubkey } }
        assertEquals(listOf("me", "x1", "x2"), byId["small"])
        assertEquals(listOf("x1", "x2", "x3", "x4", "x5"), byId["big"])
    }

    @Test
    fun `friendsGroups people shows friends first then fills with other members`() = runTest {
        val fake = FakeNostrRepository()
        fake.fakePublicKey = "me"
        fake._following.value = setOf("alice")
        fake._userGroupLists.value =
            mapOf("alice" to listOf(org.nostr.nostrord.network.UserGroupRef("wss://a", "g1")))
        fake._groupsByRelay.value = mapOf("wss://a" to listOf(meta("g1", "One")))
        // Member list: the friend plus three non-friends to fill the row.
        fake._groupMembers.value = mapOf("g1" to listOf("alice", "o1", "o2", "o3"))

        val vm = HomePageViewModel(fake, computeDispatcher = testDispatcher)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf("alice", "o1", "o2", "o3"),
            vm.friendsGroups.value.single().people.map { it.pubkey },
        )
    }

    @Test
    fun `recommendedGroups reads the curator's list excluding mine`() = runTest {
        val curator = "b2cdcb37d32533145c00c4f43d5e1e1deb7c67bceea7ef63f526ca4cab891633"
        val fake = FakeNostrRepository()
        fake._userGroupLists.value =
            mapOf(
                curator to
                    listOf(
                        org.nostr.nostrord.network.UserGroupRef("wss://a", "g1"),
                        org.nostr.nostrord.network.UserGroupRef("wss://a", "g2"),
                    ),
            )
        fake._joinedGroupsByRelay.value = mapOf("wss://a" to setOf("g2"))
        fake._groupsByRelay.value = mapOf("wss://a" to listOf(meta("g1", "Curated One")))

        val vm = HomePageViewModel(fake, computeDispatcher = testDispatcher)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("g1"), vm.recommendedGroups.value.map { it.meta.id })
        assertEquals("Curated One", vm.recommendedGroups.value.single().meta.name)
    }

    @Test
    fun `friendsGroups hides groups whose kind39000 has not arrived like recommended`() = runTest {
        val curator = "b2cdcb37d32533145c00c4f43d5e1e1deb7c67bceea7ef63f526ca4cab891633"
        val fake = FakeNostrRepository()
        fake._following.value = setOf("alice")
        fake._userGroupLists.value =
            mapOf(
                "alice" to
                    listOf(
                        org.nostr.nostrord.network.UserGroupRef("wss://a", "known"),
                        org.nostr.nostrord.network.UserGroupRef("wss://a", "unknown"),
                    ),
                curator to
                    listOf(
                        org.nostr.nostrord.network.UserGroupRef("wss://a", "rknown"),
                        org.nostr.nostrord.network.UserGroupRef("wss://a", "runknown"),
                    ),
            )
        // Only "known"/"rknown" got their kind:39000.
        fake._groupsByRelay.value =
            mapOf("wss://a" to listOf(meta("known", "Known"), meta("rknown", "Rec Known")))

        val vm = HomePageViewModel(fake, computeDispatcher = testDispatcher)
        testDispatcher.scheduler.advanceUntilIdle()

        // A friend's group with no metadata (almost always private: relays don't describe a
        // private group to non-members) is filtered out rather than shown as a bare-id card;
        // it appears once its kind:39000 lands.
        assertEquals(listOf("known"), vm.friendsGroups.value.map { it.meta.id })
        assertEquals(true, vm.friendsGroups.value.single().hasMetadata)
        // Recommended is a curated list, so it also requires real metadata.
        assertEquals(listOf("rknown"), vm.recommendedGroups.value.map { it.meta.id })
    }

    @Test
    fun `discovery hides groups tagged Hidden`() = runTest {
        val curator = "b2cdcb37d32533145c00c4f43d5e1e1deb7c67bceea7ef63f526ca4cab891633"
        val fake = FakeNostrRepository()
        fake._following.value = setOf("alice")
        fake._userGroupLists.value =
            mapOf(
                "alice" to
                    listOf(
                        org.nostr.nostrord.network.UserGroupRef("wss://a", "visible"),
                        org.nostr.nostrord.network.UserGroupRef("wss://a", "hidden"),
                    ),
                curator to
                    listOf(
                        org.nostr.nostrord.network.UserGroupRef("wss://a", "rvisible"),
                        org.nostr.nostrord.network.UserGroupRef("wss://a", "rhidden"),
                    ),
            )
        fun g(id: String, name: String, hidden: Boolean) = GroupMetadata(id = id, name = name, about = null, picture = null, isPublic = true, isOpen = true, isHidden = hidden)
        fake._groupsByRelay.value =
            mapOf(
                "wss://a" to
                    listOf(
                        g("visible", "Visible", false),
                        g("hidden", "Hidden one", true),
                        g("rvisible", "Rec Visible", false),
                        g("rhidden", "Rec Hidden", true),
                    ),
            )

        val vm = HomePageViewModel(fake, computeDispatcher = testDispatcher)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("visible"), vm.friendsGroups.value.map { it.meta.id })
        assertEquals(listOf("rvisible"), vm.recommendedGroups.value.map { it.meta.id })
    }

    @Test
    fun `friendsGroups and recommendedGroups hide groups on unreachable relays`() = runTest {
        val curator = "b2cdcb37d32533145c00c4f43d5e1e1deb7c67bceea7ef63f526ca4cab891633"
        val fake = FakeNostrRepository()
        fake._following.value = setOf("alice")
        fake._userGroupLists.value =
            mapOf(
                "alice" to
                    listOf(
                        org.nostr.nostrord.network.UserGroupRef("wss://up", "gf"),
                        org.nostr.nostrord.network.UserGroupRef("wss://down", "gd"),
                    ),
                curator to
                    listOf(
                        org.nostr.nostrord.network.UserGroupRef("wss://up", "gr"),
                        org.nostr.nostrord.network.UserGroupRef("wss://down", "grd"),
                    ),
            )
        fake._groupsByRelay.value =
            mapOf(
                "wss://up" to listOf(meta("gf", "Friend Up"), meta("gr", "Rec Up")),
                "wss://down" to listOf(meta("gd", "Friend Down"), meta("grd", "Rec Down")),
            )
        // wss://down failed NIP-11 / socket: its groups must not appear on discovery.
        fake._unreachableRelays.value = setOf("wss://down")

        val vm = HomePageViewModel(fake, computeDispatcher = testDispatcher)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("gf"), vm.friendsGroups.value.map { it.meta.id })
        assertEquals(listOf("gr"), vm.recommendedGroups.value.map { it.meta.id })
    }

    @Test
    fun `notificationUnread counts only unread entries`() = runTest {
        fun entry(
            id: String,
            read: Boolean,
        ) = NotificationEntry(
            id = id,
            type = NotificationType.MENTION,
            groupId = "g1",
            relayUrl = "wss://a",
            actorPubkey = "pk",
            createdAt = 1L,
            preview = "",
            read = read,
        )

        val store = NotificationHistoryStore()
        val vm = HomePageViewModel(FakeNostrRepository(), store, computeDispatcher = testDispatcher)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, vm.notificationUnread.value)

        store.add(entry("n1", read = false))
        store.add(entry("n2", read = false))
        store.add(entry("n3", read = true))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, vm.notificationUnread.value)

        store.markRead("n1")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, vm.notificationUnread.value)
    }
}
