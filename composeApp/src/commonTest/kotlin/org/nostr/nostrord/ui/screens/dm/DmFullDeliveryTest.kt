package org.nostr.nostrord.ui.screens.dm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.nostr.nostrord.network.FakeNostrRepository
import org.nostr.nostrord.network.managers.DmMessage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PEER = "00000000000000000000000000000000000000000000000000000000000000aa"

/**
 * The DM second tick: it appears only when the wrap reached every inbox relay the peer publishes,
 * so a partial fan-out stays visually distinct from a complete one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DmFullDeliveryTest {
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

    private fun mine(id: String, relays: List<String>) = DmMessage(
        id = id,
        peerPubkey = PEER,
        senderPubkey = "me",
        content = "hi",
        createdAt = 1,
        mine = true,
        relays = relays,
    )

    /** The flow is WhileSubscribed, so it only computes while something collects it. */
    private suspend fun kotlinx.coroutines.test.TestScope.fullyDelivered(repo: FakeNostrRepository): Set<String> {
        val vm = DmViewModel(repo)
        val seen = mutableSetOf<String>()
        backgroundScope.launch {
            vm.fullyDelivered.collect {
                seen.clear()
                seen.addAll(it)
            }
        }
        advanceUntilIdle()
        return seen
    }

    @Test
    fun `only a message on every inbox relay earns the second tick`() = runTest {
        val repo = FakeNostrRepository()
        repo.dmRelaysByPubkeyFlow.value = mapOf(PEER to listOf("wss://a.example", "wss://b.example"))
        repo.dmMessagesByPeerFlow.value = mapOf(
            PEER to listOf(
                mine("all", listOf("wss://a.example", "wss://b.example")),
                mine("partial", listOf("wss://a.example")),
            ),
        )

        assertEquals(setOf("all"), fullyDelivered(repo))
    }

    @Test
    fun `a peer with no published list never reaches the second tick`() = runTest {
        val repo = FakeNostrRepository()
        // No kind:10050 for this peer: there is no known set of inboxes to have covered.
        repo.dmMessagesByPeerFlow.value = mapOf(PEER to listOf(mine("m1", listOf("wss://a.example"))))

        assertTrue(fullyDelivered(repo).isEmpty(), "without the peer's inbox list the tick must stay single")
    }

    @Test
    fun `relay urls that differ only in case or trailing slash still match`() = runTest {
        val repo = FakeNostrRepository()
        repo.dmRelaysByPubkeyFlow.value = mapOf(PEER to listOf("wss://A.example/", "wss://b.example"))
        repo.dmMessagesByPeerFlow.value = mapOf(
            PEER to listOf(mine("m1", listOf("wss://a.example", "wss://b.example/"))),
        )

        assertEquals(setOf("m1"), fullyDelivered(repo))
    }
}
