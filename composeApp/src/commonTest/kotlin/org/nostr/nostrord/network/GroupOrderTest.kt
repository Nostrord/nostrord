package org.nostr.nostrord.network

import org.nostr.nostrord.network.managers.orderJoinedGroups
import org.nostr.nostrord.ui.screens.home.parseRailKey
import org.nostr.nostrord.ui.screens.home.railKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Rail order comes from the kind:10009 tag sequence: the publish must carry it forward
 * (a join must not reshuffle the rail) and must never turn a position into membership.
 */
class GroupOrderTest {
    @Test
    fun `publish keeps the current tag order`() {
        val joined = mapOf("wss://a" to setOf("g1", "g2"), "wss://b" to setOf("g3"))
        val order = listOf("wss://b" to "g3", "wss://a" to "g2", "wss://a" to "g1")

        assertEquals(order, orderJoinedGroups(joined, order))
    }

    @Test
    fun `a newly joined group is appended, leaving the rest in place`() {
        val joined = mapOf("wss://a" to setOf("g1", "g2", "fresh"))
        val order = listOf("wss://a" to "g2", "wss://a" to "g1")

        assertEquals(
            listOf("wss://a" to "g2", "wss://a" to "g1", "wss://a" to "fresh"),
            orderJoinedGroups(joined, order),
        )
    }

    @Test
    fun `an ordered entry that is not joined emits no tag`() {
        val joined = mapOf("wss://a" to setOf("g1"))
        val order = listOf("wss://a" to "left-on-another-device", "wss://a" to "g1")

        assertEquals(listOf("wss://a" to "g1"), orderJoinedGroups(joined, order))
    }

    @Test
    fun `the same id on two relays keeps two independent positions`() {
        val joined = mapOf("wss://a" to setOf("dup"), "wss://b" to setOf("dup"))
        val order = listOf("wss://b" to "dup", "wss://a" to "dup")

        assertEquals(order, orderJoinedGroups(joined, order))
    }

    @Test
    fun `publishing twice is order-idempotent`() {
        val joined = mapOf("wss://a" to setOf("g1", "g2"), "wss://b" to setOf("g3"))
        val first = orderJoinedGroups(joined, emptyList())

        assertEquals(first, orderJoinedGroups(joined, first))
    }

    @Test
    fun `rail keys round-trip`() {
        val key = railKey("wss://relay.example.com", "group-1")

        assertEquals("wss://relay.example.com" to "group-1", parseRailKey(key))
        assertNull(parseRailKey("no-separator"))
        assertNull(parseRailKey("wss://a|"))
    }
}
