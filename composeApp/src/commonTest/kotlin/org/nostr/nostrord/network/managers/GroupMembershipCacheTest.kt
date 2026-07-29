package org.nostr.nostrord.network.managers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.network.GroupAdmins
import org.nostr.nostrord.network.GroupMembers
import org.nostr.nostrord.network.GroupRoles
import org.nostr.nostrord.network.RoleDefinition
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.clearGroupMembershipFor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Persistence is per-account; this hex is test-only and never collides with real data.
private const val PUBKEY = "00000000000000000000000000000000000000000000000000000000cafebabe"
private const val GROUP = "test-group-1"

// The snapshot is relay-scoped (v2): lists persist under the relay that served them, like
// every production handler call. Relay-less events stay flat-only and are not persisted.
private const val RELAY = "wss://relay.test"

@OptIn(ExperimentalCoroutinesApi::class)
class GroupMembershipCacheTest {
    private fun makeManager(scope: TestScope): GroupManager = GroupManager(connectionManager = ConnectionManager(scope), scope = scope)

    @BeforeTest
    fun clearBefore() = SecureStorage.clearGroupMembershipFor(PUBKEY)

    @AfterTest
    fun clearAfter() = SecureStorage.clearGroupMembershipFor(PUBKEY)

    @Test
    fun `persists members admins and roles and hydrates a fresh manager`() = runTest {
        val scope = TestScope(testScheduler)
        val writer = makeManager(scope)
        writer.setCurrentPubkey(PUBKEY)

        writer.handleGroupMembers(GroupMembers(GROUP, listOf("alice", "bob")), createdAt = 100, relayUrl = RELAY)
        writer.handleGroupAdmins(GroupAdmins(GROUP, listOf("alice")), createdAt = 100, relayUrl = RELAY)
        writer.handleGroupRoles(GroupRoles(GROUP, listOf(RoleDefinition("admin", "Group admin"))), createdAt = 100, relayUrl = RELAY)

        // A brand-new manager (cold start) hydrates from disk before any socket opens.
        val reader = makeManager(scope)
        reader.restoreGroupMembershipFromStorage(PUBKEY)

        assertEquals(listOf("alice", "bob"), reader.groupMembers.value[GROUP])
        assertEquals(listOf("alice"), reader.groupAdmins.value[GROUP])
        assertEquals(listOf(RoleDefinition("admin", "Group admin")), reader.groupRoles.value[GROUP])
        // The relay-scoped mirror hydrates too, so scoped reads (and the same-id-on-another-
        // relay separation) work straight from cache instead of falling back to the flat map.
        assertEquals(listOf("alice"), reader.groupAdminsByRelay.value[RELAY]?.get(GROUP))
        assertEquals(listOf("alice", "bob"), reader.groupMembersByRelay.value[RELAY]?.get(GROUP))

        scope.cancel()
    }

    @Test
    fun `restore seeds the staleness guard so an older event is rejected`() = runTest {
        val scope = TestScope(testScheduler)
        val writer = makeManager(scope)
        writer.setCurrentPubkey(PUBKEY)
        writer.handleGroupMembers(GroupMembers(GROUP, listOf("alice", "bob")), createdAt = 200, relayUrl = RELAY)

        val reader = makeManager(scope)
        reader.restoreGroupMembershipFromStorage(PUBKEY)

        // A slower relay re-delivers an older snapshot; the seeded timestamp must reject it.
        reader.handleGroupMembers(GroupMembers(GROUP, listOf("alice")), createdAt = 100, relayUrl = RELAY)
        assertEquals(listOf("alice", "bob"), reader.groupMembers.value[GROUP])

        scope.cancel()
    }

    @Test
    fun `a newer event after restore replaces the cached list`() = runTest {
        val scope = TestScope(testScheduler)
        val writer = makeManager(scope)
        writer.setCurrentPubkey(PUBKEY)
        writer.handleGroupMembers(GroupMembers(GROUP, listOf("alice")), createdAt = 100, relayUrl = RELAY)

        val reader = makeManager(scope)
        reader.restoreGroupMembershipFromStorage(PUBKEY)

        // The background refresh returns a newer membership; SWR replaces the cached list.
        reader.handleGroupMembers(GroupMembers(GROUP, listOf("alice", "bob", "carol")), createdAt = 300, relayUrl = RELAY)
        assertEquals(listOf("alice", "bob", "carol"), reader.groupMembers.value[GROUP])

        scope.cancel()
    }
}
