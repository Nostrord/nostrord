package org.nostr.nostrord.network.managers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.network.GroupMembers
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.addLeftGroupForRelay
import org.nostr.nostrord.storage.getPendingGroupInvitesFor
import org.nostr.nostrord.storage.getPutUserCursorForRelay
import org.nostr.nostrord.storage.removeLeftGroupForRelay
import org.nostr.nostrord.storage.savePendingGroupInvitesFor
import org.nostr.nostrord.storage.savePutUserCursorForRelay
import org.nostr.nostrord.utils.epochSeconds
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Regression guards for a membership granted by an admin's kind:9000 (put-user): the add
// becomes a PENDING invite (never a silent adoption — a third party must not be able to
// make this client publish a kind:10009 naming their group). Accepting adopts it into the
// joined set (group list, mux, Leave); declining routes through leaveGroup, whose durable
// left marker keeps the relay's recurring 39002 from resurrecting the invite.
@OptIn(ExperimentalCoroutinesApi::class)
class GroupExternalMembershipAdoptionTest {
    private val pubkey = "00000000000000000000000000000000000000000000000000000000deadbeef"
    private val relay = "wss://test.relay"
    private val group = "external-add-group"

    private fun makeManager(scope: TestScope): GroupManager = GroupManager(connectionManager = ConnectionManager(scope), scope = scope)

    private fun reset() {
        SecureStorage.saveJoinedGroupsForRelay(pubkey, relay, emptySet())
        SecureStorage.removeLeftGroupForRelay(pubkey, relay, group)
        SecureStorage.savePendingGroupInvitesFor(pubkey, emptyList())
        SecureStorage.savePutUserCursorForRelay(pubkey, relay, 0L)
    }

    @BeforeTest fun before() = reset()

    @AfterTest fun after() = reset()

    @Test
    fun `39002 listing self registers a pending invite without adopting`() = runTest {
        val scope = TestScope(testScheduler)
        val gm = makeManager(scope)
        gm.setCurrentPubkey(pubkey)
        testScheduler.advanceUntilIdle()

        gm.handleGroupMembers(GroupMembers(group, listOf(pubkey, "someone-else")), createdAt = 100, relayUrl = relay)
        testScheduler.advanceUntilIdle()

        val invite = gm.pendingGroupInvites.value[group]
        assertEquals(relay, invite?.relayUrl, "the add lands as a pending invite")
        assertEquals(null, invite?.actorPubkey, "39002-inferred invites carry no actor")
        assertFalse(group in gm.getGroupIdsForMux(relay), "not adopted into the mux until accepted")
        assertFalse(
            group in SecureStorage.getJoinedGroupsForRelay(pubkey, relay),
            "not persisted as joined until accepted",
        )
        assertTrue(
            SecureStorage.getPendingGroupInvitesFor(pubkey).any { it.groupId == group },
            "pending invite is persisted so it survives a restart",
        )

        scope.cancel()
    }

    @Test
    fun `the same group id joined on another relay does not block the invite`() = runTest {
        val scope = TestScope(testScheduler)
        val otherRelay = "wss://other.relay"
        SecureStorage.saveJoinedGroupsForRelay(pubkey, otherRelay, setOf(group))
        try {
            val gm = makeManager(scope)
            gm.setCurrentPubkey(pubkey)
            // In memory too, not only in storage: every membership guard reads the live map.
            gm.loadJoinedGroupsFromStorage(pubkey, otherRelay)
            testScheduler.advanceUntilIdle()
            assertTrue(group in gm.joinedGroupsByRelay.value[otherRelay].orEmpty())

            gm.handleGroupMembers(GroupMembers(group, listOf(pubkey)), createdAt = 100, relayUrl = relay)
            testScheduler.advanceUntilIdle()
            assertEquals(relay, gm.pendingGroupInvites.value[group]?.relayUrl, "invite survives the id clash")

            gm.acceptPendingInvite(group)
            testScheduler.advanceUntilIdle()

            assertTrue(group in gm.getGroupIdsForMux(relay), "short ids repeat across relays; each is its own group")
            assertTrue(group in SecureStorage.getJoinedGroupsForRelay(pubkey, relay))
            assertTrue(group in SecureStorage.getJoinedGroupsForRelay(pubkey, otherRelay), "the other relay is untouched")
        } finally {
            SecureStorage.saveJoinedGroupsForRelay(pubkey, otherRelay, emptySet())
            scope.cancel()
        }
    }

    @Test
    fun `a relay-side membership can be added to the list with no invite pending`() = runTest {
        val scope = TestScope(testScheduler)
        val gm = makeManager(scope)
        gm.setCurrentPubkey(pubkey)
        testScheduler.advanceUntilIdle()

        gm.addRelaySideMembershipToList(group, relay)
        testScheduler.advanceUntilIdle()

        assertTrue(group in gm.getGroupIdsForMux(relay))
        assertTrue(group in SecureStorage.getJoinedGroupsForRelay(pubkey, relay))

        scope.cancel()
    }

    @Test
    fun `the invite is spent by the adoption itself, not before it`() = runTest {
        val scope = TestScope(testScheduler)
        val gm = makeManager(scope)
        gm.setCurrentPubkey(pubkey)
        testScheduler.advanceUntilIdle()

        gm.handleGroupMembers(GroupMembers(group, listOf(pubkey)), createdAt = 100, relayUrl = relay)
        testScheduler.advanceUntilIdle()
        assertTrue(group in gm.pendingGroupInvites.value)

        // Accept, but do not let the adoption coroutine run yet: discarding here would spend
        // the user's consent on work that has not happened (and may still be blocked).
        gm.acceptPendingInvite(group)
        assertTrue(group in gm.pendingGroupInvites.value, "card survives until the membership lands")

        testScheduler.advanceUntilIdle()
        assertFalse(group in gm.pendingGroupInvites.value)
        assertTrue(group in SecureStorage.getJoinedGroupsForRelay(pubkey, relay))

        scope.cancel()
    }

    @Test
    fun `accepting a pending invite adopts the membership into the joined set and storage`() = runTest {
        val scope = TestScope(testScheduler)
        val gm = makeManager(scope)
        gm.setCurrentPubkey(pubkey)
        testScheduler.advanceUntilIdle()

        gm.handleGroupMembers(GroupMembers(group, listOf(pubkey)), createdAt = 100, relayUrl = relay)
        testScheduler.advanceUntilIdle()

        gm.acceptPendingInvite(group)
        testScheduler.advanceUntilIdle()

        assertFalse(group in gm.pendingGroupInvites.value, "accepted invite leaves the pending set")
        assertTrue(group in gm.getGroupIdsForMux(relay), "adopted group joins the mux for its relay")
        assertTrue(
            group in SecureStorage.getJoinedGroupsForRelay(pubkey, relay),
            "adopted group is persisted so it survives a restart",
        )

        scope.cancel()
    }

    @Test
    fun `durable left marker blocks the invite - a stale relay listing must not resurrect a left group`() = runTest {
        val scope = TestScope(testScheduler)
        SecureStorage.addLeftGroupForRelay(pubkey, relay, group, nowSeconds = epochSeconds())

        val gm = makeManager(scope)
        gm.setCurrentPubkey(pubkey)
        gm.loadAllJoinedGroupsFromStorage(pubkey, listOf(relay))
        testScheduler.advanceUntilIdle()
        assertTrue(group in gm.leftGroups.value[relay].orEmpty())

        gm.handleGroupMembers(GroupMembers(group, listOf(pubkey)), createdAt = 100, relayUrl = relay)
        testScheduler.advanceUntilIdle()

        assertFalse(group in gm.pendingGroupInvites.value, "left group never re-enters the pending set")
        assertFalse(group in gm.getGroupIdsForMux(relay), "left group stays out of the mux")
        assertFalse(
            group in SecureStorage.getJoinedGroupsForRelay(pubkey, relay),
            "left group is not re-persisted as joined",
        )

        scope.cancel()
    }

    @Test
    fun `live kind-9000 targeting self registers the invite with its actor and advances the watch cursor`() = runTest {
        val scope = TestScope(testScheduler)
        val gm = makeManager(scope)
        gm.setCurrentPubkey(pubkey)
        testScheduler.advanceUntilIdle()

        val createdAt = 1_000_000L
        val msg = NostrGroupClient.NostrMessage(
            id = "evt-put-user-1",
            pubkey = "admin-pubkey",
            content = "",
            createdAt = createdAt,
            kind = 9000,
            tags = listOf(listOf("p", pubkey), listOf("h", group)),
        )
        val raw = """["EVENT","mux_padd_x",{"tags":[["p","$pubkey"],["h","$group"]]}]"""
        gm.handleMessage(msg, raw, subscriptionId = "mux_padd_x", relayUrl = relay)
        testScheduler.advanceUntilIdle()

        val invite = gm.pendingGroupInvites.value[group]
        assertEquals("admin-pubkey", invite?.actorPubkey, "put-user invite records who added us")
        assertFalse(group in gm.getGroupIdsForMux(relay), "not adopted into the mux until accepted")
        assertEquals(
            createdAt,
            SecureStorage.getPutUserCursorForRelay(pubkey, relay),
            "cursor advances so the add is not replayed on the next REQ",
        )

        scope.cancel()
    }

    @Test
    fun `declining via leaveGroup drops the invite and blocks re-registration`() = runTest {
        val scope = TestScope(testScheduler)
        val gm = makeManager(scope)
        gm.setCurrentPubkey(pubkey)
        testScheduler.advanceUntilIdle()

        gm.handleGroupMembers(GroupMembers(group, listOf(pubkey)), createdAt = 100, relayUrl = relay)
        testScheduler.advanceUntilIdle()
        assertTrue(group in gm.pendingGroupInvites.value)

        gm.leaveGroup(group, pubkey, relay, signEvent = { it }, publishJoinedGroups = {})
        testScheduler.advanceUntilIdle()
        assertFalse(group in gm.pendingGroupInvites.value, "declined invite leaves the pending set")

        // The relay still lists us (its member removal races the 9022): must not re-prompt.
        gm.handleGroupMembers(GroupMembers(group, listOf(pubkey)), createdAt = 200, relayUrl = relay)
        testScheduler.advanceUntilIdle()
        assertFalse(group in gm.pendingGroupInvites.value, "left marker blocks 39002 re-registration")

        scope.cancel()
    }

    @Test
    fun `a put-user newer than the leave re-opens the invite - a stale one does not`() = runTest {
        val scope = TestScope(testScheduler)
        // Durable marker from a past-session leave (leaveGroup itself also arms the 5s
        // isRecentlyLeft churn guard, which would mask the marker logic under test).
        val leftAt = epochSeconds()
        SecureStorage.addLeftGroupForRelay(pubkey, relay, group, nowSeconds = leftAt)
        val gm = makeManager(scope)
        gm.setCurrentPubkey(pubkey)
        gm.loadAllJoinedGroupsFromStorage(pubkey, listOf(relay))
        testScheduler.advanceUntilIdle()
        assertTrue(group in gm.leftGroups.value[relay].orEmpty())

        fun putUser(createdAt: Long) {
            val msg = NostrGroupClient.NostrMessage(
                id = "evt-readd-$createdAt",
                pubkey = "admin-pubkey",
                content = "",
                createdAt = createdAt,
                kind = 9000,
                tags = listOf(listOf("p", pubkey), listOf("h", group)),
            )
            val raw = """["EVENT","mux_padd_x",{"tags":[["p","$pubkey"],["h","$group"]]}]"""
            gm.handleMessage(msg, raw, subscriptionId = "mux_padd_x", relayUrl = relay)
        }

        // Historical replay from before the leave: stays blocked.
        putUser(leftAt - 100)
        testScheduler.advanceUntilIdle()
        assertFalse(group in gm.pendingGroupInvites.value, "a put-user older than the leave stays blocked")

        // Deliberate re-add after the leave: re-opens the invite and clears the marker.
        putUser(leftAt + 100)
        testScheduler.advanceUntilIdle()
        assertTrue(group in gm.pendingGroupInvites.value, "a fresh put-user re-opens the invite")
        assertFalse(group in gm.leftGroups.value[relay].orEmpty(), "the durable left marker is cleared")

        scope.cancel()
    }

    @Test
    fun `pending invite is restored from storage on the next session`() = runTest {
        val scope = TestScope(testScheduler)
        val first = makeManager(scope)
        first.setCurrentPubkey(pubkey)
        testScheduler.advanceUntilIdle()
        first.handleGroupMembers(GroupMembers(group, listOf(pubkey)), createdAt = 100, relayUrl = relay)
        testScheduler.advanceUntilIdle()

        val second = makeManager(scope)
        second.setCurrentPubkey(pubkey)
        second.loadAllJoinedGroupsFromStorage(pubkey, listOf(relay))
        testScheduler.advanceUntilIdle()

        assertTrue(group in second.pendingGroupInvites.value, "undecided invite survives a restart")

        scope.cancel()
    }

    @Test
    fun `a put-user older than the member list still enriches a 39002-inferred invite with its actor`() = runTest {
        val scope = TestScope(testScheduler)
        val gm = makeManager(scope)
        gm.setCurrentPubkey(pubkey)
        testScheduler.advanceUntilIdle()

        // Sweep-delivered 39002 registers the silent, actorless invite (0xchat path).
        gm.handleGroupMembers(GroupMembers(group, listOf(pubkey)), createdAt = 200, relayUrl = relay)
        testScheduler.advanceUntilIdle()
        assertEquals(null, gm.pendingGroupInvites.value[group]?.actorPubkey)

        // The enrichment fetch returns the put-user that caused it — dated BEFORE the 39002
        // (0xchat pins the list's created_at; relay29 bumps it after the add). The staleness
        // guard must not eat it: the invite gains its actor and the notification can name them.
        val msg = NostrGroupClient.NostrMessage(
            id = "evt-put-user-2",
            pubkey = "admin-pubkey",
            content = "",
            createdAt = 150,
            kind = 9000,
            tags = listOf(listOf("p", pubkey), listOf("h", group)),
        )
        val raw = """["EVENT","padd_one_x",{"tags":[["p","$pubkey"],["h","$group"]]}]"""
        gm.handleMessage(msg, raw, subscriptionId = "padd_one_x", relayUrl = relay)
        testScheduler.advanceUntilIdle()

        assertEquals("admin-pubkey", gm.pendingGroupInvites.value[group]?.actorPubkey)

        scope.cancel()
    }

    @Test
    fun `pending invite groups are included in the private-group metadata heal set`() = runTest {
        val scope = TestScope(testScheduler)
        val gm = makeManager(scope)
        gm.setCurrentPubkey(pubkey)
        testScheduler.advanceUntilIdle()

        gm.handleGroupMembers(GroupMembers(group, listOf(pubkey)), createdAt = 100, relayUrl = relay)
        testScheduler.advanceUntilIdle()

        // A private group's 39000 is withheld pre-AUTH and it is absent from the public
        // listing; the post-AUTH heal must fetch it for a pending invite too, or the
        // group screen opened from the notification stays blank.
        assertTrue(group in gm.getUncachedJoinedGroups(relay))

        scope.cancel()
    }

    @Test
    fun `a newer put-user refreshes an existing invite so a re-add notifies again`() = runTest {
        val scope = TestScope(testScheduler)
        val gm = makeManager(scope)
        gm.setCurrentPubkey(pubkey)
        val emitted = mutableListOf<GroupManager.ExternalGroupAdd>()
        val collector = scope.launch { gm.externalAddPending.collect { emitted += it } }
        testScheduler.advanceUntilIdle()

        fun putUser(id: String, createdAt: Long) {
            val msg = NostrGroupClient.NostrMessage(
                id = id,
                pubkey = "admin-pubkey",
                content = "",
                createdAt = createdAt,
                kind = 9000,
                tags = listOf(listOf("p", pubkey), listOf("h", group)),
            )
            val raw = """["EVENT","mux_padd_x",{"tags":[["p","$pubkey"],["h","$group"]]}]"""
            gm.handleMessage(msg, raw, subscriptionId = "mux_padd_x", relayUrl = relay)
        }

        putUser("evt-add-1", 1_000L)
        testScheduler.advanceUntilIdle()
        assertEquals("evt-add-1", gm.pendingGroupInvites.value[group]?.eventId)

        // The same event replayed by the inclusive since-cursor: silent.
        putUser("evt-add-1", 1_000L)
        testScheduler.advanceUntilIdle()
        assertEquals(1, emitted.size)

        // A remove we never saw (the watch carries no 9001), then a re-add: the newer
        // put-user refreshes the invite and notifies again.
        putUser("evt-add-2", 2_000L)
        testScheduler.advanceUntilIdle()
        assertEquals("evt-add-2", gm.pendingGroupInvites.value[group]?.eventId)
        assertEquals(2_000L, gm.pendingGroupInvites.value[group]?.createdAtSeconds)
        assertEquals(2, emitted.size)

        collector.cancel()
        scope.cancel()
    }

    @Test
    fun `a group that becomes joined by any path dissolves its pending invite`() = runTest {
        val scope = TestScope(testScheduler)
        val gm = makeManager(scope)
        gm.setCurrentPubkey(pubkey)
        testScheduler.advanceUntilIdle()

        gm.handleGroupMembers(GroupMembers(group, listOf(pubkey)), createdAt = 100, relayUrl = relay)
        testScheduler.advanceUntilIdle()
        assertTrue(group in gm.pendingGroupInvites.value)

        // Our own kind:10009 from another device lands (we created or accepted the group
        // there): the local "invite" from the relay's put-user echo is moot.
        SecureStorage.saveJoinedGroupsForRelay(pubkey, relay, setOf(group))
        gm.loadJoinedGroupsFromStorage(pubkey, relay)
        testScheduler.advanceUntilIdle()

        assertFalse(group in gm.pendingGroupInvites.value, "a joined group never keeps a pending invite")

        scope.cancel()
    }

    @Test
    fun `a put-user we authored ourselves does not register an invite`() = runTest {
        val scope = TestScope(testScheduler)
        val gm = makeManager(scope)
        gm.setCurrentPubkey(pubkey)
        testScheduler.advanceUntilIdle()

        // Some relays echo a creator put-user; we are both actor and target there.
        val msg = NostrGroupClient.NostrMessage(
            id = "evt-self-add",
            pubkey = pubkey,
            content = "",
            createdAt = 1_000L,
            kind = 9000,
            tags = listOf(listOf("p", pubkey), listOf("h", group)),
        )
        val raw = """["EVENT","mux_padd_x",{"tags":[["p","$pubkey"],["h","$group"]]}]"""
        gm.handleMessage(msg, raw, subscriptionId = "mux_padd_x", relayUrl = relay)
        testScheduler.advanceUntilIdle()

        assertFalse(group in gm.pendingGroupInvites.value)

        scope.cancel()
    }

    @Test
    fun `39002 without self does not register an invite`() = runTest {
        val scope = TestScope(testScheduler)
        val gm = makeManager(scope)
        gm.setCurrentPubkey(pubkey)
        testScheduler.advanceUntilIdle()

        gm.handleGroupMembers(GroupMembers(group, listOf("someone-else")), createdAt = 100, relayUrl = relay)
        testScheduler.advanceUntilIdle()

        assertFalse(group in gm.pendingGroupInvites.value)
        assertFalse(group in gm.getGroupIdsForMux(relay))
        assertFalse(group in SecureStorage.getJoinedGroupsForRelay(pubkey, relay))

        scope.cancel()
    }
}
