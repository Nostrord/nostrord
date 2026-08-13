package org.nostr.nostrord.ui.screens.group

import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.utils.groupKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelTreeTest {

    private val RELAY = "wss://relay.example"
    private fun meta(
        id: String,
        name: String? = null,
        parent: String? = null,
        children: List<String> = emptyList(),
        isPublic: Boolean = true,
        isOpen: Boolean = true,
    ) = GroupMetadata(
        id = id,
        name = name,
        about = null,
        picture = null,
        isPublic = isPublic,
        isOpen = isOpen,
        parent = parent,
        children = children,
    )

    // ── rootGroupId ─────────────────────────────────────────────────────────

    @Test
    fun rootOfPlainGroupIsItself() {
        assertEquals("a", rootGroupId("a") { null })
    }

    @Test
    fun rootWalksUpTheChain() {
        val parents = mapOf("c" to "b", "b" to "a")
        assertEquals("a", rootGroupId("c") { parents[it] })
        assertEquals("a", rootGroupId("b") { parents[it] })
        assertEquals("a", rootGroupId("a") { parents[it] })
    }

    @Test
    fun rootStopsAtUnresolvableParent() {
        // b's parent "x" is unknown (not joined / no meta): b is its own root.
        val parents = mapOf("c" to "b")
        assertEquals("b", rootGroupId("c") { parents[it] })
    }

    @Test
    fun rootSurvivesParentCycle() {
        val parents = mapOf("a" to "b", "b" to "a")
        assertEquals("b", rootGroupId("a") { parents[it] })
    }

    // ── channelTree ─────────────────────────────────────────────────────────

    @Test
    fun treeStartsWithRootAtDepthZero() {
        val tree = channelTree("root", emptyMap(), emptyMap())
        assertEquals(listOf(ChannelEntry("root", 0)), tree)
    }

    @Test
    fun treeFlattensDepthFirstWithDepths() {
        val children = mapOf("root" to setOf("a"), "a" to setOf("a1"))
        val tree = channelTree("root", children, emptyMap())
        assertEquals(
            listOf(ChannelEntry("root", 0), ChannelEntry("a", 1), ChannelEntry("a1", 2)),
            tree,
        )
    }

    @Test
    fun siblingsFollowDeclaredOrderThenName() {
        val metas = mapOf(
            "root" to meta("root", children = listOf("second", "first")),
            "first" to meta("first", name = "Alpha"),
            "second" to meta("second", name = "Zeta"),
            "undeclared" to meta("undeclared", name = "Beta"),
            "unnamed" to meta("unnamed"),
        )
        val children = mapOf("root" to setOf("first", "undeclared", "second", "unnamed"))
        val tree = channelTree("root", children, metas)
        // Declared ones first in declared order; the rest alphabetically (id fallback for unnamed).
        assertEquals(
            listOf("root", "second", "first", "undeclared", "unnamed"),
            tree.map { it.id },
        )
    }

    @Test
    fun treeGuardsAgainstCycles() {
        val children = mapOf("root" to setOf("a"), "a" to setOf("root", "a"))
        val tree = channelTree("root", children, emptyMap())
        assertEquals(listOf(ChannelEntry("root", 0), ChannelEntry("a", 1)), tree)
    }

    // ── aggregateUnread ─────────────────────────────────────────────────────

    /** Counts are keyed by (relay, group), so build the keys the aggregates read. */
    private fun unreadOn(
        relayUrl: String,
        vararg counts: Pair<String, Int>,
    ): Map<String, Int> = counts.associate { (id, n) -> groupKey(relayUrl, id) to n }

    @Test
    fun unreadAggregatesRootAndDescendants() {
        val children = mapOf("root" to setOf("a", "b"), "a" to setOf("a1"))
        val unread = unreadOn(RELAY, "root" to 1, "a" to 2, "a1" to 4, "b" to 8, "other" to 100)
        assertEquals(15, aggregateUnread(RELAY, "root", children, unread))
    }

    @Test
    fun unreadOfLeafIsItsOwnCount() {
        assertEquals(3, aggregateUnread(RELAY, "g", emptyMap(), unreadOn(RELAY, "g" to 3)))
        assertEquals(0, aggregateUnread(RELAY, "g", emptyMap(), emptyMap()))
        // A count from another relay's same-id group never lands in this root's total.
        assertEquals(0, aggregateUnread(RELAY, "g", emptyMap(), unreadOn("wss://other.example", "g" to 3)))
    }

    // ── moveChannelBefore ───────────────────────────────────────────────────

    @Test
    fun moveInsertsDraggedBeforeTarget() {
        val order = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "a", "c", "d"), moveChannelBefore(order, "a", "c"))
        assertEquals(listOf("d", "a", "b", "c"), moveChannelBefore(order, "d", "a"))
    }

    @Test
    fun moveWithNullTargetDropsAtTheEnd() {
        val order = listOf("a", "b", "c")
        assertEquals(listOf("b", "c", "a"), moveChannelBefore(order, "a", null))
        assertEquals(order, moveChannelBefore(order, "c", null))
        assertEquals(order, moveChannelBefore(order, "x", null))
    }

    @Test
    fun moveIsNoOpForUnknownOrSelfTargets() {
        val order = listOf("a", "b")
        assertEquals(order, moveChannelBefore(order, "a", "a"))
        assertEquals(order, moveChannelBefore(order, "x", "b"))
        assertEquals(order, moveChannelBefore(order, "a", "x"))
    }

    // ── aggregateUnreadUnmuted ──────────────────────────────────────────────

    @Test
    fun mutedRootZeroesItsWholeTree() {
        val children = mapOf("root" to setOf("c1", "c2"))
        val unread = unreadOn(RELAY, "root" to 3, "c1" to 5, "c2" to 1)
        assertEquals(9, aggregateUnread(RELAY, "root", children, unread))
        assertEquals(0, aggregateUnreadUnmuted(RELAY, "root", children, unread) { it == "root" })
    }

    @Test
    fun mutedChannelDropsOutOfALiveRoot() {
        val children = mapOf("root" to setOf("c1", "c2"))
        val unread = unreadOn(RELAY, "root" to 3, "c1" to 5, "c2" to 1)
        assertEquals(4, aggregateUnreadUnmuted(RELAY, "root", children, unread) { it == "c1" })
    }

    @Test
    fun nothingMutedMatchesThePlainAggregate() {
        val children = mapOf("root" to setOf("c1"))
        val unread = unreadOn(RELAY, "root" to 2, "c1" to 4)
        assertEquals(
            aggregateUnread(RELAY, "root", children, unread),
            aggregateUnreadUnmuted(RELAY, "root", children, unread) { false },
        )
    }

    // ── isLockedChannel ─────────────────────────────────────────────────────

    @Test
    fun lockReflectsAccessAndMembership() {
        val open = meta("g")
        val closed = meta("g", isOpen = false)
        val private = meta("g", isPublic = false)
        assertFalse(isLockedChannel(open, isJoined = false))
        assertTrue(isLockedChannel(closed, isJoined = false))
        assertTrue(isLockedChannel(private, isJoined = false))
        assertFalse(isLockedChannel(private, isJoined = true))
        // Unknown meta can't claim a lock.
        assertFalse(isLockedChannel(null, isJoined = false))
    }
}
