package org.nostr.nostrord.network.managers

import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.nostr.Spell
import org.nostr.nostrord.nostr.SpellContext
import org.nostr.nostrord.nostr.SpellPresets
import org.nostr.nostrord.nostr.toSpell
import org.nostr.nostrord.utils.AppError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val GROUP_RELAY = "wss://groups.example"
private const val GROUP_ID = "g1"
private const val NOW = 1_740_000_000L
private val MEMBER = "aa".repeat(32)

private fun msg(id: String, createdAt: Long) = NostrGroupClient.NostrMessage(id = id, pubkey = MEMBER, content = "", createdAt = createdAt, kind = 1)

class SpellKeyTest {
    @Test
    fun scopesTheSameSpellPerGroup() {
        val images = SpellPresets.IMAGES
        val a = images.toSpell(GROUP_RELAY, "g1").key
        val b = images.toSpell(GROUP_RELAY, "g2").key
        val c = images.toSpell("wss://other.example", "g1").key

        // The same group id on two relays is two independent groups, so three distinct feeds.
        assertNotEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun fallsBackToTheSpellIdWhenUnbound() {
        assertEquals("solo", Spell(id = "solo", name = "n", kinds = listOf(1)).key)
    }
}

class SpellRoutingPrefixTest {
    @Test
    fun ownsOnlyItsOwnSubscriptionIds() = runTest {
        val manager = SpellManager(backgroundScope, connectRelay = { null })
        assertTrue(manager.owns("spell_abc_123"))
        // Group chat routing keys on msg_; a collision would feed group events into a spell.
        assertFalse(manager.owns("msg_abc_123"))
        assertFalse(manager.owns("mux_chat_1"))
        assertFalse(manager.owns("spell"))
    }

    @Test
    fun theRegistryNamespacesGeneratedIds() = runTest {
        val registry = GroupLoadingRegistry(backgroundScope, subIdPrefix = SPELL_SUB_PREFIX)
        val subId = registry.getController("$GROUP_RELAY|$GROUP_ID|preset:images").startInitialLoad()
        assertTrue(subId!!.startsWith("${SPELL_SUB_PREFIX}_"), subId)
    }

    @Test
    fun groupLoadingKeepsItsDefaultPrefix() = runTest {
        val registry = GroupLoadingRegistry(backgroundScope)
        assertTrue(registry.getController(GROUP_ID).startInitialLoad()!!.startsWith("msg_"))
    }
}

class SpellManagerOpenTest {
    private val ctx = SpellContext(me = MEMBER, members = listOf(MEMBER), now = NOW)

    @Test
    fun failsWhenAVariableCannotBeResolved() = runTest {
        val manager = SpellManager(backgroundScope, connectRelay = { error("must not connect") })
        val spell = SpellPresets.MEMBER_NOTES.toSpell(GROUP_RELAY, GROUP_ID)

        val result = manager.open(spell, ctx.copy(members = emptyList()), listOf("wss://nos.lol"))

        // Resolution fails before any socket work, so an unloaded member list costs nothing.
        assertTrue(result.errorOrNull() is AppError.Spell.UnresolvedVariable)
        assertTrue(manager.states.value.isEmpty())
    }

    @Test
    fun failsWhenNoRelayIsLeftToQuery() = runTest {
        val manager = SpellManager(backgroundScope, connectRelay = { error("must not connect") })
        val spell = SpellPresets.MEMBER_NOTES.toSpell(GROUP_RELAY, GROUP_ID)

        // The only NIP-65 relay is the group's own, which a member-graph spell must not use.
        val result = manager.open(spell, ctx, listOf(GROUP_RELAY))

        assertTrue(result.errorOrNull() is AppError.Spell.Malformed)
    }

    @Test
    fun failsWhenEveryRelayIsUnreachable() = runTest {
        val manager = SpellManager(backgroundScope, connectRelay = { null })
        val spell = SpellPresets.IMAGES.toSpell(GROUP_RELAY, GROUP_ID)

        val result = manager.open(spell, ctx, emptyList())

        assertTrue(result.errorOrNull() is AppError.Network.Disconnected)
    }

    @Test
    fun loadMoreIsANoOpForAClosedSpell() = runTest {
        val manager = SpellManager(backgroundScope, connectRelay = { null })
        assertFalse(manager.loadMore("$GROUP_RELAY|$GROUP_ID|preset:images"))
    }

    @Test
    fun closingAnUnopenedSpellDoesNothing() = runTest {
        val manager = SpellManager(backgroundScope, connectRelay = { error("must not connect") })
        manager.close("nope")
        assertTrue(manager.events.value.isEmpty())
    }
}

class SpellEventMergeTest {
    @Test
    fun ordersNewestFirst() {
        val merged = mergeSpellEvents(emptyList(), listOf(msg("a", 100), msg("b", 300), msg("c", 200)), 10)
        assertEquals(listOf("b", "c", "a"), merged.map { it.id })
    }

    @Test
    fun deduplicatesAcrossRelays() {
        val existing = listOf(msg("a", 300), msg("b", 200))
        val merged = mergeSpellEvents(existing, listOf(msg("b", 200), msg("c", 100)), 10)
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
    }

    @Test
    fun returnsTheSameListWhenNothingIsNew() {
        val existing = listOf(msg("a", 300), msg("b", 200))
        // Identity, not just equality: a new list would churn the StateFlow on every redelivery.
        assertTrue(mergeSpellEvents(existing, listOf(msg("a", 300)), 10) === existing)
    }

    @Test
    fun capsTheFeedKeepingTheNewest() {
        val incoming = (1..10).map { msg("e$it", it.toLong()) }
        val merged = mergeSpellEvents(emptyList(), incoming, 3)
        assertEquals(listOf("e10", "e9", "e8"), merged.map { it.id })
    }
}
