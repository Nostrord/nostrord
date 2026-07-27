package org.nostr.nostrord.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.nostr.nostrord.network.FakeNostrRepository
import org.nostr.nostrord.nostr.Nip56
import org.nostr.nostrord.ui.screens.report.ReportUserViewModel
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReportUserViewModelTest {
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
    fun `send without a selected reason is a no-op`() = runTest {
        val fake = FakeNostrRepository()
        val vm = ReportUserViewModel(fake, targetPubkey = "target")

        vm.send()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ReportUserViewModel.Phase.Editing, vm.phase.value)
        assertTrue(fake.calls.none { it.startsWith("reportUser:") })
    }

    @Test
    fun `send publishes the report and mutes by default`() = runTest {
        val fake = FakeNostrRepository()
        val vm = ReportUserViewModel(fake, targetPubkey = "target", eventId = "evt1")

        vm.select(Nip56.ReportType.SPAM)
        vm.setNote("  repeated ads  ")
        vm.send()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ReportUserViewModel.Phase.Sent, vm.phase.value)
        assertTrue(vm.didMute.value)
        assertEquals("reportUser:target:spam:  repeated ads  :evt1", fake.calls.first { it.startsWith("reportUser:") })
        assertTrue(fake.calls.contains("muteUser:target"))
    }

    @Test
    fun `unchecking also-mute skips the mute`() = runTest {
        val fake = FakeNostrRepository()
        val vm = ReportUserViewModel(fake, targetPubkey = "target")

        vm.select(Nip56.ReportType.OTHER)
        vm.toggleAlsoMute()
        vm.send()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ReportUserViewModel.Phase.Sent, vm.phase.value)
        assertFalse(vm.didMute.value)
        assertTrue(fake.calls.none { it.startsWith("muteUser:") })
    }

    @Test
    fun `already-muted target is not re-muted`() = runTest {
        val fake = FakeNostrRepository()
        fake._mutedPubkeys.value = setOf("target")
        val vm = ReportUserViewModel(fake, targetPubkey = "target")

        assertTrue(vm.targetAlreadyMuted.value)
        vm.select(Nip56.ReportType.PROFANITY)
        vm.send()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ReportUserViewModel.Phase.Sent, vm.phase.value)
        assertTrue(fake.calls.none { it.startsWith("muteUser:") })
    }

    @Test
    fun `publish failure surfaces the error and returns to editing`() = runTest {
        val fake = FakeNostrRepository()
        fake.reportUserResult = Result.Error(AppError.Network.PublishRejected("rate-limited"))
        val vm = ReportUserViewModel(fake, targetPubkey = "target")

        vm.select(Nip56.ReportType.MALWARE)
        vm.send()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ReportUserViewModel.Phase.Editing, vm.phase.value)
        assertNotNull(vm.error.value)
        assertTrue(fake.calls.none { it.startsWith("muteUser:") })
    }
}
