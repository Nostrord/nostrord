package org.nostr.nostrord.ui.screens.avspace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.nostr.nostrord.network.FakeNostrRepository
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.livekit.AvConnectionState
import org.nostr.nostrord.network.livekit.LiveKitCredentials
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AvSpaceViewModelTest {
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

    private val groupId = "grp"
    private val me = "a".repeat(64)
    private val other = "b".repeat(64)

    private fun metadata(hasLiveKit: Boolean, supportedKinds: List<Int>?) = GroupMetadata(
        id = groupId,
        name = "Test",
        about = null,
        picture = null,
        isPublic = true,
        isOpen = true,
        hasLiveKit = hasLiveKit,
        supportedKinds = supportedKinds,
    )

    private fun viewModel(repo: FakeNostrRepository) = AvSpaceViewModel(repo, groupId, me)

    @Test
    fun `space is offered only when the group carries the livekit tag`() = runTest {
        val repo = FakeNostrRepository()
        repo._groups.value = listOf(metadata(hasLiveKit = false, supportedKinds = null))
        assertFalse(viewModel(repo).hasSpace.value)

        repo._groups.value = listOf(metadata(hasLiveKit = true, supportedKinds = null))
        assertTrue(viewModel(repo).hasSpace.value)
    }

    @Test
    fun `AV-only is empty supported_kinds, not a missing tag`() = runTest {
        val repo = FakeNostrRepository()
        repo._groups.value = listOf(metadata(hasLiveKit = true, supportedKinds = null))
        assertFalse(viewModel(repo).isAvOnly.value)

        repo._groups.value = listOf(metadata(hasLiveKit = true, supportedKinds = listOf(9)))
        assertFalse(viewModel(repo).isAvOnly.value)

        repo._groups.value = listOf(metadata(hasLiveKit = true, supportedKinds = emptyList()))
        assertTrue(viewModel(repo).isAvOnly.value)
    }

    @Test
    fun `roster comes from kind 39004 and marks the local user`() = runTest {
        val repo = FakeNostrRepository()
        repo._liveKitParticipants.value = mapOf(groupId to listOf(other, me))
        val vm = viewModel(repo)
        testDispatcher.scheduler.advanceUntilIdle()

        assertContentEquals(listOf(other, me), vm.participants.value.map { it.pubkey })
        assertTrue(vm.participants.value.single { it.pubkey == me }.isSelf)
        assertFalse(vm.participants.value.single { it.pubkey == other }.isSelf)
    }

    @Test
    fun `roster ignores other groups`() = runTest {
        val repo = FakeNostrRepository()
        repo._liveKitParticipants.value = mapOf("another" to listOf(other))
        val vm = viewModel(repo)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(emptyList(), vm.participants.value)
    }

    @Test
    fun `the roster is requested on open`() = runTest {
        val repo = FakeNostrRepository()
        viewModel(repo)
        testDispatcher.scheduler.advanceUntilIdle()

        assertContentEquals(listOf(groupId), repo.requestedLiveKitParticipants)
    }

    @Test
    fun `a token failure surfaces its cause instead of connecting`() = runTest {
        val repo = FakeNostrRepository()
        repo.liveKitCredentials = Result.Error(AppError.Unknown("relay refused the token (403)"))
        val vm = viewModel(repo)

        vm.join()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(AvConnectionState.Disconnected, vm.connectionState.value)
        assertNotNull(vm.error.value)
        vm.dismissError()
        assertEquals(null, vm.error.value)
    }

    @Test
    fun `joining without a media engine reports the platform limitation`() = runTest {
        val repo = FakeNostrRepository()
        repo.liveKitCredentials = Result.Success(LiveKitCredentials("jwt", "wss://lk.example"))
        val vm = viewModel(repo)

        // Every target compiled under jvmTest uses the stub engine.
        assertFalse(vm.canJoin)
        vm.join()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(vm.error.value)
        assertEquals(AvConnectionState.Disconnected, vm.connectionState.value)
    }
}
