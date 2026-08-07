package org.nostr.nostrord.network.managers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.network.GroupMembers
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.addLeftGroupForRelay
import org.nostr.nostrord.storage.getLeftGroupsForRelay
import org.nostr.nostrord.storage.removeLeftGroupForRelay
import org.nostr.nostrord.utils.epochSeconds
import org.nostr.nostrord.utils.normalizeRelayUrl
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Regression guards for the durable-left-marker work (the membership-lifecycle redesign Phase 1).
@OptIn(ExperimentalCoroutinesApi::class)
class GroupLeftMarkerTest {
    private val pubkey = "00000000000000000000000000000000000000000000000000000000deadbeef"
    private val relay = "wss://test.relay"
    private val otherRelay = "wss://other.relay"
    private val groupA = "left-marker-group-a"
    private val groupB = "left-marker-group-b"

    // The bug this file guards: the same group id names independent groups on different relays.
    private val sharedId = "nostrord"

    private fun makeManager(scope: TestScope): GroupManager = GroupManager(connectionManager = ConnectionManager(scope), scope = scope)

    private fun GroupManager.leftOn(relayUrl: String): Set<String> = leftGroups.value[relayUrl.normalizeRelayUrl()].orEmpty()

    private fun reset() {
        for (url in listOf(relay, otherRelay)) {
            SecureStorage.saveJoinedGroupsForRelay(pubkey, url, emptySet())
            SecureStorage.removeLeftGroupForRelay(pubkey, url, groupA)
            SecureStorage.removeLeftGroupForRelay(pubkey, url, groupB)
            SecureStorage.removeLeftGroupForRelay(pubkey, url, sharedId)
        }
    }

    @BeforeTest fun before() = reset()

    @AfterTest fun after() = reset()

    @Test
    fun `loadAll restores left markers into the flow without filtering joined`() = runTest {
        val scope = TestScope(testScheduler)
        // Persist: group A is BOTH joined and left (a stale marker on a rejoined group); B is just left.
        SecureStorage.saveJoinedGroupsForRelay(pubkey, relay, setOf(groupA))
        SecureStorage.addLeftGroupForRelay(pubkey, relay, groupA, nowSeconds = epochSeconds())
        SecureStorage.addLeftGroupForRelay(pubkey, relay, groupB, nowSeconds = epochSeconds())

        val gm = makeManager(scope)
        gm.setCurrentPubkey(pubkey)
        gm.loadJoinedGroupsFromStorage(pubkey, relay)
        gm.loadAllJoinedGroupsFromStorage(pubkey, listOf(relay))
        testScheduler.advanceUntilIdle()

        // Both markers restored into the flow (drives the membership NONE override).
        assertTrue(groupA in gm.leftOn(relay))
        assertTrue(groupB in gm.leftOn(relay))
        // ...but the joined set is NOT filtered against them: a still-joined group must keep its
        // subscription (the regression where a rejoined group lost its live messages).
        assertTrue(groupA in gm.getGroupIdsForMux(relay), "a joined group must stay in the mux even with a stale left marker")

        scope.cancel()
    }

    @Test
    fun `handleGroupMembers self-heals a stale left marker when joined and listed as member`() = runTest {
        val scope = TestScope(testScheduler)
        SecureStorage.saveJoinedGroupsForRelay(pubkey, relay, setOf(groupA))
        SecureStorage.addLeftGroupForRelay(pubkey, relay, groupA, nowSeconds = epochSeconds())

        val gm = makeManager(scope)
        gm.setCurrentPubkey(pubkey)
        gm.loadJoinedGroupsFromStorage(pubkey, relay)
        gm.loadAllJoinedGroupsFromStorage(pubkey, listOf(relay))
        testScheduler.advanceUntilIdle()
        assertTrue(groupA in gm.leftOn(relay))

        // The relay confirms us as a member AND we are joined -> the marker is stale (we rejoined).
        gm.handleGroupMembers(GroupMembers(groupA, listOf(pubkey)), createdAt = 200)
        testScheduler.advanceUntilIdle()

        assertFalse(groupA in gm.leftOn(relay), "self-heal clears the stale marker in memory")
        assertEquals(emptyMap(), SecureStorage.getLeftGroupsForRelay(pubkey, relay, nowSeconds = epochSeconds()), "and in storage")

        scope.cancel()
    }

    @Test
    fun `a left marker on one relay does not touch the same id on another relay`() = runTest {
        val scope = TestScope(testScheduler)
        // "nostrord" exists on both relays. The user left it on `relay` and is still joined on
        // `otherRelay` — the exact shape that locked users out of the group they never left.
        SecureStorage.saveJoinedGroupsForRelay(pubkey, otherRelay, setOf(sharedId))
        SecureStorage.addLeftGroupForRelay(pubkey, relay, sharedId, nowSeconds = epochSeconds())

        val gm = makeManager(scope)
        gm.setCurrentPubkey(pubkey)
        gm.loadAllJoinedGroupsFromStorage(pubkey, listOf(relay, otherRelay))
        testScheduler.advanceUntilIdle()

        assertTrue(sharedId in gm.leftOn(relay), "the relay we actually left keeps its marker")
        assertFalse(sharedId in gm.leftOn(otherRelay), "the other relay's same-id group is untouched")
        assertTrue(
            sharedId in gm.getGroupIdsForMux(otherRelay),
            "the group we are still joined to must keep streaming",
        )

        scope.cancel()
    }

    @Test
    fun `a left marker survives a restart on its own relay only`() = runTest {
        val scope = TestScope(testScheduler)
        SecureStorage.addLeftGroupForRelay(pubkey, relay, sharedId, nowSeconds = epochSeconds())
        SecureStorage.saveJoinedGroupsForRelay(pubkey, otherRelay, setOf(sharedId))

        // Cold start #1: the relay lists us as a member of the OTHER relay's group, which self-heals
        // that relay's (absent) marker and must not clear the real one on `relay`.
        val first = makeManager(scope)
        first.setCurrentPubkey(pubkey)
        first.loadAllJoinedGroupsFromStorage(pubkey, listOf(relay, otherRelay))
        first.handleGroupMembers(GroupMembers(sharedId, listOf(pubkey)), createdAt = 200, relayUrl = otherRelay)
        testScheduler.advanceUntilIdle()

        // Cold start #2 reads storage back: the marker is still filed under `relay` alone.
        val second = makeManager(scope)
        second.setCurrentPubkey(pubkey)
        second.loadAllJoinedGroupsFromStorage(pubkey, listOf(relay, otherRelay))
        testScheduler.advanceUntilIdle()

        assertTrue(sharedId in second.leftOn(relay), "the leave is durable on the relay it happened on")
        assertFalse(sharedId in second.leftOn(otherRelay), "and never migrates to another relay")

        scope.cancel()
    }
}
