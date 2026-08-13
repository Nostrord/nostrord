package org.nostr.nostrord.network.managers

import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.network.GroupMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A group id is only unique within one relay, so resolving a relay from a bare id must not
 * guess when two relays serve the same id.
 */
class GroupRelayResolutionTest {

    private val wisp = "wss://chat.wisp.talk"
    private val oxchat = "wss://groups.0xchat.com"
    private val gid = "nostrord"

    private fun manager(scope: TestScope) = GroupManager(connectionManager = ConnectionManager(scope), scope = scope)

    private fun meta(id: String) = GroupMetadata(
        id = id,
        name = id,
        about = null,
        picture = null,
        isPublic = true,
        isOpen = true,
    )

    @Test
    fun `one relay serving the id resolves to it`() = runTest {
        val scope = TestScope(testScheduler)
        val manager = manager(scope)

        manager.handleGroupMetadata(meta(gid), oxchat)

        assertEquals(oxchat, manager.getRelayForGroup(gid))
        scope.cancel()
    }

    @Test
    fun `two relays serving the id resolve to nothing rather than a coin flip`() = runTest {
        val scope = TestScope(testScheduler)
        val manager = manager(scope)

        manager.handleGroupMetadata(meta(gid), wisp)
        manager.handleGroupMetadata(meta(gid), oxchat)

        assertNull(manager.getRelayForGroup(gid))
        scope.cancel()
    }

    @Test
    fun `the open-group hint breaks the tie`() = runTest {
        val scope = TestScope(testScheduler)
        val manager = manager(scope)
        manager.handleGroupMetadata(meta(gid), wisp)
        manager.handleGroupMetadata(meta(gid), oxchat)

        manager.setGroupRelayHint(gid, oxchat)

        assertEquals(oxchat, manager.getRelayForGroup(gid))
        scope.cancel()
    }
}
