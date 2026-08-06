package org.nostr.nostrord.ui.screens.avspace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.nostr.nostrord.network.FakeNostrRepository
import org.nostr.nostrord.network.GroupMetadata
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AvSpaceHostTest {
    private val testDispatcher = StandardTestDispatcher()
    private val me = "a".repeat(64)
    private val other = "b".repeat(64)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        testDispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    @Test
    fun `reopening the same room returns the session already held`() = runTest {
        val host = AvSpaceHost(FakeNostrRepository())

        val first = host.open("grp", me)
        val second = host.open("grp", me)

        // Same instance, so closing and reopening the room UI never drops the call.
        assertSame(first, second)
        assertEquals("grp", host.session.value?.groupId)
    }

    @Test
    fun `opening another room retires the previous one`() = runTest {
        val host = AvSpaceHost(FakeNostrRepository())

        val first = host.open("grp", me)
        val second = host.open("other", me)

        assertTrue(first !== second)
        assertEquals("other", host.session.value?.groupId)
    }

    @Test
    fun `release drops the session`() = runTest {
        val host = AvSpaceHost(FakeNostrRepository())
        host.open("grp", me)

        host.release()

        assertNull(host.session.value)
    }

    @Test
    fun `the banner reads kind 39004 while this client is outside the room`() = runTest {
        val repo = FakeNostrRepository()
        repo._liveKitParticipants.value = mapOf("grp" to listOf(other, me))
        val host = AvSpaceHost(repo)
        val vm = LiveSpaceBarViewModel(repo, "grp", me, host)
        testDispatcher.scheduler.advanceUntilIdle()

        assertContentEquals(listOf(other, me), vm.participants.value.map { it.pubkey })
        assertEquals(false, vm.joined.value)
    }

    @Test
    fun `leaving falls back to the relay roster instead of an emptied engine`() = runTest {
        val repo = FakeNostrRepository()
        repo._liveKitParticipants.value = mapOf("grp" to listOf(other))
        val host = AvSpaceHost(repo)
        val vm = LiveSpaceBarViewModel(repo, "grp", me, host)
        // A session survives Leave so the room can be reopened; its engine is disconnected and
        // empty. Whoever stayed must still show, which only kind 39004 knows.
        host.open("grp", me)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, vm.joined.value)
        assertContentEquals(listOf(other), vm.participants.value.map { it.pubkey })
    }

    @Test
    fun `the banner subscribes to the roster without anyone opening the room`() = runTest {
        val repo = FakeNostrRepository()
        LiveSpaceBarViewModel(repo, "grp", me, AvSpaceHost(repo))
        // Metadata lands after the surface mounts, which is the usual order on a cold open.
        repo._groups.value = listOf(groupWithSpace("grp"))
        testDispatcher.scheduler.advanceUntilIdle()

        // Otherwise the only kind 39004 subscription belongs to the room itself, and a busy
        // room reads as empty to everyone who has not joined it yet.
        assertContentEquals(listOf("grp"), repo.requestedLiveKitParticipants)
    }

    @Test
    fun `a group with no room costs no subscription`() = runTest {
        val repo = FakeNostrRepository()
        repo._groups.value = listOf(groupWithSpace("grp").copy(hasLiveKit = false))
        LiveSpaceBarViewModel(repo, "grp", me, AvSpaceHost(repo))
        testDispatcher.scheduler.advanceUntilIdle()

        // Every sidebar and banner builds one of these, most for groups with no AV at all.
        assertContentEquals(emptyList(), repo.requestedLiveKitParticipants)
    }

    @Test
    fun `an empty room keeps the banner out of the chat pane`() = runTest {
        val repo = FakeNostrRepository()
        repo._groups.value = listOf(groupWithSpace("grp"))
        val vm = LiveSpaceBarViewModel(repo, "grp", me, AvSpaceHost(repo))
        testDispatcher.scheduler.advanceUntilIdle()

        // Nothing to join and nobody to see: the sidebar's Voice room row is the way in.
        assertEquals(false, vm.visible.value)

        repo._liveKitParticipants.value = mapOf("grp" to listOf(other))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(true, vm.visible.value)
    }

    @Test
    fun `an AV-only group keeps the banner even with nobody in the room`() = runTest {
        val repo = FakeNostrRepository()
        repo._groups.value = listOf(groupWithSpace("grp").copy(supportedKinds = emptyList()))
        val vm = LiveSpaceBarViewModel(repo, "grp", me, AvSpaceHost(repo))
        testDispatcher.scheduler.advanceUntilIdle()

        // No text chat, so hiding the banner would leave an empty pane with no way into the room.
        assertEquals(true, vm.visible.value)
    }

    private fun groupWithSpace(id: String) = GroupMetadata(
        id = id,
        name = id,
        about = null,
        picture = null,
        isPublic = true,
        isOpen = true,
        hasLiveKit = true,
    )

    @Test
    fun `the banner ignores a room open for another group`() = runTest {
        val repo = FakeNostrRepository()
        repo._liveKitParticipants.value = mapOf("grp" to listOf(me))
        val host = AvSpaceHost(repo)
        val vm = LiveSpaceBarViewModel(repo, "grp", me, host)
        host.open("other", me)
        testDispatcher.scheduler.advanceUntilIdle()

        // A call in another group must not make this banner read as joined.
        assertEquals(false, vm.joined.value)
        assertContentEquals(listOf(me), vm.participants.value.map { it.pubkey })
    }
}
