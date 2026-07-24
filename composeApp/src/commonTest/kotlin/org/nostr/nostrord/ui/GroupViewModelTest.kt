package org.nostr.nostrord.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.nostr.nostrord.network.FakeNostrRepository
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.managers.PendingGroupInvite
import org.nostr.nostrord.ui.screens.group.GroupAccess
import org.nostr.nostrord.ui.screens.group.GroupMembership
import org.nostr.nostrord.ui.screens.group.GroupViewModel
import org.nostr.nostrord.ui.screens.group.deriveMembershipStatus
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GroupViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        // Drain Main-dispatched viewModelScope jobs before resetMain so an un-run task
        // doesn't race the dispatcher swap and surface as UncaughtExceptionsBeforeTest
        // in a later test (see AppViewModelTest).
        testDispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    private fun vm(fake: FakeNostrRepository = FakeNostrRepository()) = GroupViewModel(fake, "test-group")

    // -------------------------------------------------------------------------
    // Host-relay scoping: the same id on two relays is two independent groups
    // -------------------------------------------------------------------------

    @Test
    fun `explicit relay registers a relay hint so repo routing follows the route`() = runTest {
        val fake = FakeNostrRepository()
        GroupViewModel(fake, "dev", "wss://b")

        assertEquals("wss://b", fake.relayHints["dev"])
    }

    @Test
    fun `no relay leaves routing unhinted`() = runTest {
        val fake = FakeNostrRepository()
        GroupViewModel(fake, "dev")

        assertNull(fake.relayHints["dev"])
    }

    @Test
    fun `groupAccess reads the host relay's metadata, not a same-id group elsewhere`() = runTest {
        val fake = FakeNostrRepository()
        val closed = GroupMetadata(id = "dev", name = "Dev A", about = null, picture = null, isPublic = false, isOpen = false)
        val open = GroupMetadata(id = "dev", name = "Dev B", about = null, picture = null, isPublic = true, isOpen = true)
        fake._groups.value = listOf(open)
        fake._groupsByRelay.value = mapOf("wss://a" to listOf(closed), "wss://b" to listOf(open))

        val vmA = GroupViewModel(fake, "dev", "wss://a")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(GroupAccess(isPrivate = true, isOpen = false), vmA.groupAccess.value)
    }

    @Test
    fun `membership counts only the host relay's joined set`() = runTest {
        val fake = FakeNostrRepository()
        fake.fakePublicKey = "me"
        fake._joinedGroupsByRelay.value = mapOf("wss://a" to setOf("dev"))

        val vmA = GroupViewModel(fake, "dev", "wss://a")
        val vmB = GroupViewModel(fake, "dev", "wss://b")
        testDispatcher.scheduler.advanceUntilIdle()

        // Joined on A (member list not arrived yet -> resolving); a stranger on B.
        assertEquals(GroupMembership.RESOLVING, vmA.membershipState.value.status)
        assertEquals(GroupMembership.NONE, vmB.membershipState.value.status)
    }

    // -------------------------------------------------------------------------
    // State passthrough
    // -------------------------------------------------------------------------

    @Test
    fun `joinedGroups reflects repo state`() = runTest {
        val fake = FakeNostrRepository()
        fake._joinedGroups.value = setOf("test-group")
        val vm = vm(fake)

        assertTrue(vm.joinedGroups.value.contains("test-group"))
    }

    @Test
    fun `isLoadingMore starts false for group`() = runTest {
        val vm = vm()
        assertFalse(vm.isLoadingMore.value["test-group"] ?: false)
    }

    // -------------------------------------------------------------------------
    // Moderation actions (#174): errors must surface, never fail silently
    // -------------------------------------------------------------------------

    @Test
    fun `promoteToAdmin sends the admin role and reports success`() = runTest {
        val fake = FakeNostrRepository()
        val vm = vm(fake)

        var succeeded = false
        vm.promoteToAdmin("target") { succeeded = true }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(Triple("test-group", "target", listOf("admin")), fake.addUserCalls.single())
        assertTrue(succeeded)
        assertNull(vm.moderationError.value)
    }

    @Test
    fun `promote failure surfaces the relay reason without the blocked prefix`() = runTest {
        val fake = FakeNostrRepository()
        fake.addUserResult =
            Result.Error(AppError.Group.SendFailed("test-group", Exception("blocked: not an admin")))
        val vm = vm(fake)

        var succeeded = false
        vm.promoteToAdmin("target") { succeeded = true }
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(succeeded)
        assertEquals("Not an admin", vm.moderationError.value)
    }

    @Test
    fun `remove timeout surfaces the friendly AppError message`() = runTest {
        val fake = FakeNostrRepository()
        fake.removeUserResult = Result.Error(AppError.Group.SendTimeout("test-group"))
        val vm = vm(fake)

        vm.removeUser("target")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "Timed out waiting for relay confirmation (group: test-group)",
            vm.moderationError.value,
        )
    }

    @Test
    fun `moderation is busy while awaiting the relay and idle after`() = runTest {
        val fake = FakeNostrRepository()
        val gate = CompletableDeferred<Unit>()
        fake.addUserGate = gate
        val vm = vm(fake)

        vm.promoteToAdmin("target")
        testDispatcher.scheduler.runCurrent()
        assertTrue(vm.moderationBusy.value)

        gate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.moderationBusy.value)
    }

    @Test
    fun `a member list change clears a stale moderation error`() = runTest {
        // Offline remove: the await times out (error shown), but the queued event is
        // flushed on reconnect and the relay echo updates the member list. The stale
        // error must not outlive the visible success.
        val fake = FakeNostrRepository()
        fake.removeUserResult = Result.Error(AppError.Group.SendTimeout("test-group"))
        val vm = vm(fake)

        vm.removeUser("target")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            "Timed out waiting for relay confirmation (group: test-group)",
            vm.moderationError.value,
        )

        fake._groupMembers.value = mapOf("test-group" to listOf("someone-else"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.moderationError.value)
    }

    @Test
    fun `a successful action clears the previous moderation error`() = runTest {
        val fake = FakeNostrRepository()
        fake.removeUserResult = Result.Error(AppError.Group.SendTimeout("test-group"))
        val vm = vm(fake)

        vm.removeUser("target")
        testDispatcher.scheduler.advanceUntilIdle()
        fake.removeUserResult = Result.Success(Unit)
        vm.removeUser("target")
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.moderationError.value)
    }

    // -------------------------------------------------------------------------
    // Pending external add (admin kind:9000) accept / decline
    // -------------------------------------------------------------------------

    private fun invite(groupId: String) = PendingGroupInvite(
        groupId = groupId,
        relayUrl = "wss://relay",
        actorPubkey = "admin-pk",
        eventId = "ev1",
        createdAtSeconds = 100L,
    )

    @Test
    fun `pendingInvite surfaces only this group's invite`() = runTest {
        val fake = FakeNostrRepository()
        fake.pendingGroupInvitesFlow.value = mapOf(
            "test-group" to invite("test-group"),
            "other" to invite("other"),
        )
        val vm = vm(fake)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("test-group", vm.pendingInvite.value?.groupId)
        assertEquals("admin-pk", vm.pendingInvite.value?.actorPubkey)
    }

    @Test
    fun `acceptInvite delegates to the repo and the prompt clears`() = runTest {
        val fake = FakeNostrRepository()
        fake.pendingGroupInvitesFlow.value = mapOf("test-group" to invite("test-group"))
        val vm = vm(fake)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.acceptInvite()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("test-group"), fake.acceptedInvites)
        assertEquals(null, vm.pendingInvite.value)
    }

    @Test
    fun `declineInvite leaves the group and then reports done`() = runTest {
        val fake = FakeNostrRepository()
        val left = mutableListOf<String>()
        fake.leaveGroupAction = { groupId, _ ->
            left += groupId
            org.nostr.nostrord.utils.Result.Success(Unit)
        }
        val vm = vm(fake)
        var done = false

        vm.declineInvite { done = true }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("test-group"), left)
        assertTrue(done)
    }

    @Test
    fun `reactions from muted users are hidden and empty entries dropped`() = runTest {
        val fake = FakeNostrRepository()
        fake._reactions.value = mapOf(
            "m1" to mapOf(
                "👍" to org.nostr.nostrord.network.managers.GroupManager.ReactionInfo(null, listOf("friend", "spammer")),
                "🔥" to org.nostr.nostrord.network.managers.GroupManager.ReactionInfo(null, listOf("spammer")),
            ),
            "m2" to mapOf(
                "👍" to org.nostr.nostrord.network.managers.GroupManager.ReactionInfo(null, listOf("spammer")),
            ),
        )
        fake._mutedPubkeys.value = setOf("spammer")
        val vm = vm(fake)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            mapOf(
                "m1" to mapOf(
                    "👍" to org.nostr.nostrord.network.managers.GroupManager.ReactionInfo(null, listOf("friend")),
                ),
            ),
            vm.reactions.value,
        )
    }

    // -------------------------------------------------------------------------
    // leaveGroup — onSuccess callback
    // -------------------------------------------------------------------------

    @Test
    fun `leaveGroup calls onSuccess after repo succeeds`() = runTest {
        val vm = vm()
        var called = false

        vm.leaveGroup { called = true }
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(called)
    }

    @Test
    fun `leaveGroup passes correct groupId to repo`() = runTest {
        val fake = FakeNostrRepository()
        var calledWith: String? = null
        fake.leaveGroupAction = { id, _ ->
            calledWith = id
            org.nostr.nostrord.utils.Result
                .Success(Unit)
        }
        val vm = GroupViewModel(fake, "my-group")

        vm.leaveGroup {}
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("my-group", calledWith)
    }

    // -------------------------------------------------------------------------
    // deleteGroup — onSuccess callback
    // -------------------------------------------------------------------------

    @Test
    fun `deleteGroup calls onSuccess after repo succeeds`() = runTest {
        val vm = vm()
        var called = false

        vm.deleteGroup { called = true }
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(called)
    }

    // -------------------------------------------------------------------------
    // sendMessage — delegates with correct groupId
    // -------------------------------------------------------------------------

    @Test
    fun `sendMessage delegates with correct groupId`() = runTest {
        val fake = FakeNostrRepository()
        var sentGroupId: String? = null
        var sentContent: String? = null
        fake.sendMessageAction = { gid, content, _, _, _ ->
            sentGroupId = gid
            sentContent = content
            org.nostr.nostrord.utils.Result
                .Success(Unit)
        }
        val vm = GroupViewModel(fake, "chat-room")

        vm.sendMessage("hello", null, emptyMap(), null)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("chat-room", sentGroupId)
        assertEquals("hello", sentContent)
    }

    // -------------------------------------------------------------------------
    // Optimistic send: retry / dismiss delegate with the right ids
    // -------------------------------------------------------------------------

    @Test
    fun `retrySend delegates the event id`() = runTest {
        val fake = FakeNostrRepository()
        var retried: String? = null
        fake.retrySendAction = { retried = it }
        val vm = GroupViewModel(fake, "chat-room")

        vm.retrySend("evt-1")

        assertEquals("evt-1", retried)
    }

    @Test
    fun `dismissFailed delegates the screen groupId and event id`() = runTest {
        val fake = FakeNostrRepository()
        var dGroup: String? = null
        var dEvent: String? = null
        fake.dismissFailedAction = { gid, eid ->
            dGroup = gid
            dEvent = eid
        }
        val vm = GroupViewModel(fake, "chat-room")

        vm.dismissFailed("evt-2")

        assertEquals("chat-room", dGroup)
        assertEquals("evt-2", dEvent)
    }

    // -------------------------------------------------------------------------
    // Membership derivation (shared by Compose + web; drives join/pending/leave UX)
    // -------------------------------------------------------------------------

    private val me = "me-pubkey"

    private fun status(
        joined: Boolean = false,
        isOpen: Boolean = true,
        hasOwnJoinRequest: Boolean = false,
        members: List<String> = emptyList(),
        admins: List<String> = emptyList(),
        pubkey: String? = me,
        locallyLeft: Boolean = false,
    ) = deriveMembershipStatus(pubkey, joined, isOpen, hasOwnJoinRequest, members, admins, locallyLeft)

    @Test
    fun `locally left beats a stale relay member list`() {
        // 0xchat keeps us in 39002 after our 9022; the durable leave marker must still read NONE.
        assertEquals(GroupMembership.NONE, status(members = listOf(me), locallyLeft = true))
    }

    @Test
    fun `locally left beats a stale admin list`() {
        assertEquals(GroupMembership.NONE, status(admins = listOf(me), locallyLeft = true))
    }

    @Test
    fun `locally left with an outstanding feed request is still NONE`() {
        // Cancel (9022) leaves a stale 9021 in the feed; locallyLeft wins.
        assertEquals(
            GroupMembership.NONE,
            status(isOpen = false, hasOwnJoinRequest = true, locallyLeft = true),
        )
    }

    @Test
    fun `not left and in members is still MEMBER`() {
        // No regression: the durable override only triggers when actually left.
        assertEquals(GroupMembership.MEMBER, status(members = listOf(me), locallyLeft = false))
    }

    @Test
    fun `no pubkey is NONE`() {
        assertEquals(GroupMembership.NONE, status(joined = true, members = listOf(me), pubkey = null))
    }

    @Test
    fun `admin wins even before the member list loads`() {
        assertEquals(GroupMembership.ADMIN, status(joined = true, admins = listOf(me)))
    }

    @Test
    fun `not joined with no outstanding request is NONE`() {
        assertEquals(GroupMembership.NONE, status(joined = false, members = listOf("someone")))
    }

    @Test
    fun `in the member list is MEMBER`() {
        assertEquals(GroupMembership.MEMBER, status(joined = true, members = listOf(me, "other")))
    }

    @Test
    fun `joined but absent from a loaded member list is PENDING`() {
        assertEquals(GroupMembership.PENDING, status(joined = true, members = listOf("other")))
    }

    @Test
    fun `closed group with an outstanding request reads PENDING before the member list loads`() {
        assertEquals(GroupMembership.PENDING, status(joined = true, isOpen = false, hasOwnJoinRequest = true))
    }

    @Test
    fun `open group just joined is RESOLVING not an admin-approval flash`() {
        // Open groups auto-approve, so "pending admin approval" would be wrong copy; wait for the list.
        assertEquals(GroupMembership.RESOLVING, status(joined = true, isOpen = true, hasOwnJoinRequest = true))
    }
}
