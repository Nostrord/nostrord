package org.nostr.nostrord.settings

import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.nostr.SpellPresets
import org.nostr.nostrord.nostr.relayFeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private val OWNER = "aa".repeat(32)

class SpellPinTest {
    @Test
    fun presetsStoreOnlyTheirSlug() {
        val mentions = requireNotNull(SpellPresets.forRail("mentions"))
        val pin = mentions.toPin(OWNER)

        // Storing the built-in's filter would freeze it on the day it was pinned; the slug lets
        // an app update improve the spell under a rail the user already arranged.
        assertEquals("mentions", pin.preset)
        assertNull(pin.event)
        assertEquals(mentions, pin.toSpell())
    }

    @Test
    fun customSpellsRoundTripThroughTheirEvent() {
        val feed = relayFeed("Damus firehose", listOf("wss://relay.damus.io"))
        val restored = assertNotNull(feed.toPin(OWNER).toSpell())

        assertEquals(feed.id, restored.id)
        assertEquals(feed.name, restored.name)
        assertEquals(feed.kinds, restored.kinds)
        assertEquals(feed.relays, restored.relays)
        assertEquals(feed.limit, restored.limit)
    }

    @Test
    fun customSpellsKeepTheirLocalIdDespiteAnUnsignedEvent() {
        val feed = relayFeed("Nos", listOf("wss://nos.lol"))
        val pin = feed.toPin(OWNER)

        // The stored event is unsigned, so it has no event id to be identified by.
        assertNull(pin.event?.id)
        assertEquals(feed.id, pin.id)
        assertEquals(feed.id, pin.toSpell()?.id)
    }

    @Test
    fun unknownEntriesDropOutInsteadOfCrashing() {
        assertNull(SpellPin(preset = "was-removed-in-an-update").toSpell())
        assertNull(SpellPin().toSpell())
        // A stored event that is no longer a valid spell must not take the rail down with it.
        assertNull(
            SpellPin(
                id = "local:1",
                event = Event(pubkey = OWNER, createdAt = 1L, kind = 777, tags = emptyList(), content = ""),
            ).toSpell(),
        )
    }
}
