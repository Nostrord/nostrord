package org.nostr.nostrord.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.nostr.nostrord.network.FakeNostrRepository
import org.nostr.nostrord.network.managers.DmConversation
import org.nostr.nostrord.ui.screens.dm.DmViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DmViewModelTest {
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

    private fun convo(peer: String, unread: Int = 0) = DmConversation(peerPubkey = peer, lastMessage = "hi", lastAt = 1, unread = unread)

    private fun FakeNostrRepository.setConversations(vararg c: DmConversation) {
        @Suppress("UNCHECKED_CAST")
        (dmConversations as MutableStateFlow<List<DmConversation>>).value = c.toList()
    }

    @Test
    fun `splits conversations into follows and others by the contact list`() = runTest {
        val fake = FakeNostrRepository()
        fake.setConversations(convo("alice"), convo("bob"))
        fake._following.value = setOf("alice")
        val vm = DmViewModel(fake)

        val jobs = listOf(launch { vm.followsConversations.collect {} }, launch { vm.othersConversations.collect {} })
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("alice"), vm.followsConversations.value.map { it.peerPubkey })
        assertEquals(listOf("bob"), vm.othersConversations.value.map { it.peerPubkey })
        jobs.forEach { it.cancel() }
    }

    @Test
    fun `a peer moves to follows when the contact list updates`() = runTest {
        val fake = FakeNostrRepository()
        fake.setConversations(convo("bob"))
        val vm = DmViewModel(fake)

        val jobs = listOf(launch { vm.followsConversations.collect {} }, launch { vm.othersConversations.collect {} })
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(emptyList(), vm.followsConversations.value.map { it.peerPubkey })
        assertEquals(listOf("bob"), vm.othersConversations.value.map { it.peerPubkey })

        fake._following.value = setOf("bob")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("bob"), vm.followsConversations.value.map { it.peerPubkey })
        assertEquals(emptyList(), vm.othersConversations.value.map { it.peerPubkey })
        jobs.forEach { it.cancel() }
    }

    @Test
    fun `othersUnread sums only the others inbox`() = runTest {
        val fake = FakeNostrRepository()
        fake.setConversations(convo("alice", unread = 5), convo("bob", unread = 3), convo("carol", unread = 2))
        fake._following.value = setOf("alice")
        val vm = DmViewModel(fake)

        val job = launch { vm.othersUnread.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(5, vm.othersUnread.value) // bob (3) + carol (2), not alice
        job.cancel()
    }

    @Test
    fun `opening a conversation records it as the peer the DM nav returns to`() = runTest {
        val fake = FakeNostrRepository()
        val vm = DmViewModel(fake)
        assertEquals(null, vm.lastPeer.value)

        vm.openConversation("alice")
        assertEquals("alice", vm.lastPeer.value)
    }

    @Test
    fun `an unfollowed peer belongs to the requests inbox`() = runTest {
        val fake = FakeNostrRepository()
        fake._following.value = setOf("alice")
        val vm = DmViewModel(fake)

        assertEquals(false, vm.isRequestPeer("alice", vm.following.value))
        assertEquals(true, vm.isRequestPeer("bob", vm.following.value))
        assertEquals(false, vm.isRequestPeer(null, vm.following.value))
    }
}
