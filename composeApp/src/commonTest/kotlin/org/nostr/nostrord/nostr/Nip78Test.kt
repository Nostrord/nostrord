package org.nostr.nostrord.nostr

import org.nostr.nostrord.settings.NotificationLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Nip78Test {
    @Test
    fun notificationPayloadRoundTrips() {
        val groups = mapOf(
            "groupA" to NotificationLevel.MUTED,
            "groupB" to NotificationLevel.MENTIONS_REPLIES,
        )
        val encoded = Nip78.encodeNotifications(NotificationLevel.ALL, groups)
        val decoded = Nip78.decodeNotifications(encoded)

        assertEquals(NotificationLevel.ALL, decoded?.defaultLevel)
        assertEquals(groups, decoded?.groupLevels)
    }

    @Test
    fun emptyOverridesRoundTrip() {
        val decoded = Nip78.decodeNotifications(Nip78.encodeNotifications(NotificationLevel.MUTED, emptyMap()))
        assertEquals(NotificationLevel.MUTED, decoded?.defaultLevel)
        assertEquals(emptyMap(), decoded?.groupLevels)
    }

    @Test
    fun unknownLevelDropsOnlyItsOwnEntry() {
        // A newer client's extra level must not take the rest of the map down with it.
        val payload = """{"v":1,"default":"ALL","groups":{"a":"MUTED","b":"SOMETHING_NEW"}}"""
        val decoded = Nip78.decodeNotifications(payload)
        assertEquals(mapOf("a" to NotificationLevel.MUTED), decoded?.groupLevels)
    }

    @Test
    fun unknownDefaultFallsBackToAll() {
        val decoded = Nip78.decodeNotifications("""{"v":1,"default":"WAT","groups":{}}""")
        assertEquals(NotificationLevel.ALL, decoded?.defaultLevel)
    }

    @Test
    fun garbageAndFutureVersionsAreRejectedRatherThanApplied() {
        // Null means "leave local settings alone"; an empty result would wipe them.
        assertNull(Nip78.decodeNotifications("not json"))
        assertNull(Nip78.decodeNotifications(""))
        assertNull(Nip78.decodeNotifications("""{"v":2,"default":"ALL","groups":{"a":"MUTED"}}"""))
    }

    @Test
    fun unknownFieldsAreTolerated() {
        val decoded = Nip78.decodeNotifications("""{"v":1,"default":"ALL","groups":{"a":"MUTED"},"future":true}""")
        assertEquals(mapOf("a" to NotificationLevel.MUTED), decoded?.groupLevels)
    }
}
