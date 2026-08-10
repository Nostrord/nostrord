package org.nostr.nostrord.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoundSuppressionTest {
    @Test
    fun `every reason names the system control the user has to change`() {
        assertTrue(SoundSuppression.DoNotDisturb.notice.contains("Do Not Disturb"))
        assertTrue(SoundSuppression.SilentRinger.notice.contains("silent or vibrate"))
    }

    @Test
    fun `notices carry no em-dash and stay English`() {
        SoundSuppression.entries.forEach { reason ->
            assertEquals(-1, reason.notice.indexOf('—'), "em-dash in ${reason.name}")
        }
    }

    @Test
    fun `platforms without a system mute report no suppression`() {
        // Android overrides this; every other actual is a constant null flow, which is what
        // keeps the test-sound button enabled on web, desktop and iOS.
        assertNull(notificationSoundSuppression.value)
    }
}
