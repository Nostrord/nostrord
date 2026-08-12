package org.nostr.nostrord.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.nostr.nostrord.network.FakeNostrRepository
import org.nostr.nostrord.network.managers.DmEncryptionManager
import org.nostr.nostrord.ui.screens.settings.DmEncryptionViewModel
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DmEncryptionViewModelTest {
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

    @Test
    fun `hidden for an account that signs locally`() {
        val repo = FakeNostrRepository()
        repo.dmEncryptionStateFlow.value = DmEncryptionManager.State.Unavailable
        assertFalse(DmEncryptionViewModel(repo).visible)
    }

    @Test
    fun `shown once the account could benefit`() {
        val repo = FakeNostrRepository()
        repo.dmEncryptionStateFlow.value = DmEncryptionManager.State.Disabled
        assertTrue(DmEncryptionViewModel(repo).visible)
    }

    @Test
    fun `a failed enable surfaces the error and clears busy`() = runTest(testDispatcher) {
        val repo = FakeNostrRepository()
        repo.dmEncryptionStateFlow.value = DmEncryptionManager.State.Disabled
        repo.enableDmEncryptionResult = Result.Error(AppError.Unknown("relays refused it"))
        val vm = DmEncryptionViewModel(repo)

        vm.enable()
        advanceUntilIdle()

        assertEquals("relays refused it", vm.error.value)
        assertFalse(vm.busy.value)
    }

    @Test
    fun `a successful enable leaves no error`() = runTest(testDispatcher) {
        val repo = FakeNostrRepository()
        repo.dmEncryptionStateFlow.value = DmEncryptionManager.State.Disabled
        val vm = DmEncryptionViewModel(repo)

        vm.enable()
        advanceUntilIdle()

        assertNull(vm.error.value)
        assertFalse(vm.busy.value)
    }

    @Test
    fun `a rejected import explains why instead of silently failing`() {
        val repo = FakeNostrRepository()
        repo.dmEncryptionStateFlow.value = DmEncryptionManager.State.AnnouncedElsewhere("b".repeat(64))
        repo.importDmEncryptionKeyResult = false
        val vm = DmEncryptionViewModel(repo)

        vm.setImportInput("c".repeat(64))
        vm.importKey()

        assertNotNull(vm.error.value)
        assertEquals("c".repeat(64), vm.importInput.value, "the input is kept so it can be corrected")
    }

    @Test
    fun `an accepted import clears the field`() {
        val repo = FakeNostrRepository()
        repo.dmEncryptionStateFlow.value = DmEncryptionManager.State.AnnouncedElsewhere("b".repeat(64))
        repo.importDmEncryptionKeyResult = true
        val vm = DmEncryptionViewModel(repo)

        vm.setImportInput("b".repeat(64))
        vm.importKey()

        assertNull(vm.error.value)
        assertEquals("", vm.importInput.value)
    }

    @Test
    fun `the archive confirmation states the volume before publishing anything`() = runTest(testDispatcher) {
        val repo = FakeNostrRepository()
        repo.dmEncryptionStateFlow.value = DmEncryptionManager.State.Active("b".repeat(64))
        repo.archivableCount = 42
        val vm = DmEncryptionViewModel(repo)

        vm.askToArchive()
        advanceUntilIdle()

        assertTrue(vm.archiveConfirmOpen.value)
        assertEquals(42, vm.archivableCount.value)
        assertFalse(repo.calls.contains("archiveDmHistory"), "nothing is published before confirmation")
    }

    @Test
    fun `dismissing the confirmation publishes nothing`() {
        val repo = FakeNostrRepository()
        repo.dmEncryptionStateFlow.value = DmEncryptionManager.State.Active("b".repeat(64))
        val vm = DmEncryptionViewModel(repo)

        vm.askToArchive()
        vm.dismissArchiveConfirm()

        assertFalse(vm.archiveConfirmOpen.value)
        assertFalse(repo.calls.contains("archiveDmHistory"))
    }

    @Test
    fun `confirming runs the archive and surfaces a failure`() = runTest(testDispatcher) {
        val repo = FakeNostrRepository()
        repo.dmEncryptionStateFlow.value = DmEncryptionManager.State.Active("b".repeat(64))
        repo.archiveDmHistoryResult = Result.Error(AppError.Unknown("relays stopped accepting"))
        val vm = DmEncryptionViewModel(repo)

        vm.confirmArchive()
        advanceUntilIdle()

        assertTrue(repo.calls.contains("archiveDmHistory"))
        assertEquals("relays stopped accepting", vm.error.value)
    }

    @Test
    fun `rotating hides a revealed key so the stale one is not copied`() = runTest(testDispatcher) {
        val repo = FakeNostrRepository()
        repo.dmEncryptionStateFlow.value = DmEncryptionManager.State.Active("b".repeat(64))
        repo.exportedDmEncryptionKey = "d".repeat(64)
        val vm = DmEncryptionViewModel(repo)
        vm.revealKey()

        vm.rotate()
        advanceUntilIdle()

        assertTrue(repo.calls.contains("rotateDmEncryptionKey"))
        assertNull(vm.revealedKey.value, "the shown key is no longer the current one")
        assertFalse(vm.busy.value)
    }

    @Test
    fun `a failed rotation surfaces the error`() = runTest(testDispatcher) {
        val repo = FakeNostrRepository()
        repo.dmEncryptionStateFlow.value = DmEncryptionManager.State.Active("b".repeat(64))
        repo.rotateDmEncryptionKeyResult = Result.Error(AppError.Unknown("relays refused it"))
        val vm = DmEncryptionViewModel(repo)

        vm.rotate()
        advanceUntilIdle()

        assertEquals("relays refused it", vm.error.value)
        assertFalse(vm.busy.value)
    }

    @Test
    fun `a reset is confirmed before anything is published`() = runTest(testDispatcher) {
        val repo = FakeNostrRepository()
        repo.dmEncryptionStateFlow.value = DmEncryptionManager.State.AnnouncedElsewhere("c".repeat(64))
        val vm = DmEncryptionViewModel(repo)

        vm.askToReset()
        advanceUntilIdle()
        assertTrue(vm.resetConfirmOpen.value)
        assertFalse("resetDmEncryptionKey" in repo.calls)

        vm.dismissResetConfirm()
        advanceUntilIdle()
        assertFalse(vm.resetConfirmOpen.value)
        assertFalse("resetDmEncryptionKey" in repo.calls)

        vm.askToReset()
        vm.confirmReset()
        advanceUntilIdle()

        assertTrue("resetDmEncryptionKey" in repo.calls)
        assertFalse(vm.resetConfirmOpen.value)
        assertNull(vm.error.value)
        assertFalse(vm.busy.value)
    }

    @Test
    fun `a failed reset surfaces the error and keeps the account resettable`() = runTest(testDispatcher) {
        val repo = FakeNostrRepository()
        repo.dmEncryptionStateFlow.value = DmEncryptionManager.State.AnnouncedElsewhere("c".repeat(64))
        repo.resetDmEncryptionKeyResult = Result.Error(AppError.Unknown("relays refused it"))
        val vm = DmEncryptionViewModel(repo)

        vm.askToReset()
        vm.confirmReset()
        advanceUntilIdle()

        assertEquals("relays refused it", vm.error.value)
        assertFalse(vm.busy.value)
    }

    @Test
    fun `revealing the key exposes it only on request`() {
        val repo = FakeNostrRepository()
        repo.dmEncryptionStateFlow.value = DmEncryptionManager.State.Active("b".repeat(64))
        repo.exportedDmEncryptionKey = "d".repeat(64)
        val vm = DmEncryptionViewModel(repo)

        assertNull(vm.revealedKey.value)
        vm.revealKey()
        assertEquals("d".repeat(64), vm.revealedKey.value)
        vm.hideKey()
        assertNull(vm.revealedKey.value)
    }
}
