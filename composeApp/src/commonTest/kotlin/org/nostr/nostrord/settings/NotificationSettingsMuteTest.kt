package org.nostr.nostrord.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Per-group mute behavior. Each test drives a throwaway pubkey so the per-account
 * SecureStorage slot it writes can't collide with a real account's overrides.
 */
class NotificationSettingsMuteTest {
    private fun settingsFor(pubkey: String) = NotificationSettings().apply { initialize(pubkey) }

    @Test
    fun toggleMuteMutesThenFallsBackToDefault() {
        val settings = settingsFor("mute-test-toggle")
        val originalDefault = settings.defaultLevel.value
        try {
            settings.setDefaultLevel(NotificationLevel.ALL)
            assertFalse(settings.isMuted("groupA"))

            settings.toggleMute("groupA")
            assertTrue(settings.isMuted("groupA"))
            assertEquals(NotificationLevel.MUTED, settings.effectiveLevelFor("groupA"))

            // Unmuting drops the override entirely, so the group tracks the default again.
            settings.toggleMute("groupA")
            assertFalse(settings.isMuted("groupA"))
            assertEquals(null, settings.groupLevels.value["groupA"])
        } finally {
            settings.setDefaultLevel(originalDefault)
        }
    }

    @Test
    fun unmutingUnderAMutedDefaultPinsTheGroupToAll() {
        val settings = settingsFor("mute-test-default-muted")
        val originalDefault = settings.defaultLevel.value
        try {
            settings.setDefaultLevel(NotificationLevel.MUTED)
            // Inherits the muted default without an override of its own.
            assertTrue(settings.isMuted("groupB"))

            // Dropping the override here would leave it muted, so unmute has to pin ALL.
            settings.toggleMute("groupB")
            assertFalse(settings.isMuted("groupB"))
            assertEquals(NotificationLevel.ALL, settings.effectiveLevelFor("groupB"))
        } finally {
            settings.setDefaultLevel(originalDefault)
        }
    }

    @Test
    fun muteStateTracksBothHalvesTogether() {
        val settings = settingsFor("mute-test-state")
        val originalDefault = settings.defaultLevel.value
        try {
            settings.setDefaultLevel(NotificationLevel.ALL)
            settings.setGroupLevel("groupC", NotificationLevel.MUTED)

            val state = settings.muteState.value
            assertEquals(NotificationLevel.ALL, state.defaultLevel)
            assertTrue(state.isMuted("groupC"))
            assertFalse(state.isMuted("groupD"))

            // A default change has to reach groups with no override of their own.
            settings.setDefaultLevel(NotificationLevel.MUTED)
            assertTrue(settings.muteState.value.isMuted("groupD"))
        } finally {
            settings.setDefaultLevel(originalDefault)
        }
    }

    @Test
    fun clearDropsOverridesOnAccountSwitch() {
        val settings = settingsFor("mute-test-clear")
        settings.setGroupLevel("groupE", NotificationLevel.MUTED)
        assertTrue(settings.muteState.value.isMuted("groupE"))

        settings.clear()
        assertEquals(emptyMap(), settings.muteState.value.overrides)
        // Writes with no active account are dropped rather than landing on the next one.
        settings.toggleMute("groupE")
        assertEquals(emptyMap(), settings.groupLevels.value)
    }

    @Test
    fun applyRemoteLevelsReplacesRatherThanMerges() {
        val settings = settingsFor("mute-test-remote")
        val originalDefault = settings.defaultLevel.value
        try {
            settings.setDefaultLevel(NotificationLevel.ALL)
            settings.setGroupLevel("stale", NotificationLevel.MUTED)

            // Another device unmuted "stale" and muted "fresh". A merge would resurrect
            // "stale"; a replaceable event means the newer map wins whole.
            settings.applyRemoteGroupLevels(mapOf("fresh" to NotificationLevel.MUTED))

            assertEquals(mapOf("fresh" to NotificationLevel.MUTED), settings.groupLevels.value)
            assertFalse(settings.isMuted("stale"))
            assertTrue(settings.isMuted("fresh"))
            // The device-global default is untouched by a per-account sync.
            assertEquals(NotificationLevel.ALL, settings.defaultLevel.value)
        } finally {
            settings.setDefaultLevel(originalDefault)
        }
    }

    @Test
    fun appliedRemoteLevelsSurfaceVerbatimInMuteState() {
        // The sync layer decides "is this a local change worth publishing?" by comparing
        // muteState.overrides against the payload it just applied. If this write ever
        // normalized or filtered the map, that comparison would always differ and every
        // device would republish what it just received, prompting its signer each round.
        val settings = settingsFor("mute-test-remote-verbatim")
        val payload = mapOf(
            "a" to NotificationLevel.MUTED,
            "b" to NotificationLevel.MENTIONS_REPLIES,
            "c" to NotificationLevel.ALL,
        )
        settings.applyRemoteGroupLevels(payload)
        assertEquals(payload, settings.muteState.value.overrides)
    }

    @Test
    fun remoteLevelsWithNoActiveAccountAreDropped() {
        // Guards the account-switch window: an in-flight decrypt that lands after clear()
        // must not write the previous account's groups into the next one.
        val settings = settingsFor("mute-test-remote-noaccount")
        settings.clear()
        settings.applyRemoteGroupLevels(mapOf("ghost" to NotificationLevel.MUTED))
        assertEquals(emptyMap(), settings.groupLevels.value)
    }

    @Test
    fun mentionsRepliesIsNotMuted() {
        val settings = settingsFor("mute-test-mentions")
        settings.setGroupLevel("groupF", NotificationLevel.MENTIONS_REPLIES)
        assertFalse(settings.isMuted("groupF"))
        assertTrue(settings.shouldNotify(NotificationLevel.MENTIONS_REPLIES, isDirect = true))
        assertFalse(settings.shouldNotify(NotificationLevel.MENTIONS_REPLIES, isDirect = false))
    }
}
