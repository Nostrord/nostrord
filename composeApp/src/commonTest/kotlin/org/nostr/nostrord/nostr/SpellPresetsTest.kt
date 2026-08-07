package org.nostr.nostrord.nostr

import org.nostr.nostrord.utils.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val GROUP_RELAY = "wss://groups.example"
private const val GROUP_ID = "abc123"
private const val NOW = 1_740_000_000L
private val MEMBER_A = "aa".repeat(32)
private val MEMBER_B = "bb".repeat(32)

private val ctx = SpellContext(me = MEMBER_A, contacts = listOf(MEMBER_B), members = listOf(MEMBER_A, MEMBER_B), now = NOW)

private fun preset(slug: String): Spell = requireNotNull(SpellPresets.forGroup(slug, GROUP_RELAY, GROUP_ID))

private fun Spell.filter(): NostrFilter = when (val r = resolve(ctx)) {
    is Result.Success -> r.data
    is Result.Error -> error("preset $id failed to resolve: ${r.error.message}")
}

class SpellPresetsTest {
    @Test
    fun slugsAreUniqueAndStable() {
        assertEquals(SpellPresets.slugs.distinct().size, SpellPresets.slugs.size)
        // Slugs are the sync key in the kind:30777 index, so renaming one silently unpins it.
        assertEquals(listOf("contacts-notes", "mentions", "my-notes"), SpellPresets.rail.map { it.slug })
        assertEquals(
            listOf("images", "videos", "polls", "files", "firehose", "member-notes", "member-articles", "member-highlights"),
            SpellPresets.group.map { it.slug },
        )
    }

    @Test
    fun everyPresetBindsToTheGroupAndResolves() {
        val spells = SpellPresets.forGroup(GROUP_RELAY, GROUP_ID)
        assertEquals(SpellPresets.group.size, spells.size)
        spells.forEach { spell ->
            assertEquals(GroupBinding(GROUP_RELAY, GROUP_ID), spell.group)
            assertTrue(spell.hasFilter, "${spell.id} carries no filter")
            spell.filter()
        }
    }

    @Test
    fun groupContentPresetsCarryTheExpectedKinds() {
        assertEquals(listOf(20), preset("images").kinds)
        assertEquals(listOf(21, 22), preset("videos").kinds)
        assertEquals(listOf(1068), preset("polls").kinds)
        assertEquals(listOf(1063), preset("files").kinds)
    }

    @Test
    fun groupContentPresetsQueryTheGroupRelayScopedByH() {
        listOf("images", "videos", "polls", "files", "firehose").forEach { slug ->
            val spell = preset(slug)
            assertEquals(listOf(GROUP_RELAY), spell.targetRelays(listOf("wss://nos.lol")), slug)
            assertEquals(mapOf("#h" to listOf(GROUP_ID)), spell.filter().tags, slug)
        }
    }

    @Test
    fun firehoseIsKindAgnostic() {
        assertNull(preset("firehose").filter().kinds)
    }

    @Test
    fun memberPresetsQueryMembersOffTheGroupRelay() {
        listOf("member-notes", "member-articles", "member-highlights").forEach { slug ->
            val spell = preset(slug)
            assertEquals(listOf(VAR_MEMBERS), spell.authors, slug)
            // Routing these to the NIP-29 relay returns empty and reads as a broken feed.
            assertEquals(listOf("wss://nos.lol"), spell.targetRelays(listOf("wss://nos.lol", GROUP_RELAY)), slug)
            assertEquals(listOf(MEMBER_A, MEMBER_B), spell.filter().authors, slug)
        }
    }

    @Test
    fun memberPresetsCarryTheExpectedKinds() {
        assertEquals(listOf(1), preset("member-notes").kinds)
        assertEquals(listOf(30023), preset("member-articles").kinds)
        assertEquals(listOf(9802), preset("member-highlights").kinds)
    }

    @Test
    fun memberPresetsFailClosedWithoutAMemberList() {
        val result = preset("member-notes").resolve(ctx.copy(members = emptyList()))
        assertTrue(result is Result.Error)
    }

    @Test
    fun noPresetDuplicatesTheThreadsView() {
        // Kind 11 (NIP-7D) already ships as the Threads screen.
        assertTrue(SpellPresets.all.none { 11 in it.kinds })
    }

    @Test
    fun presetsSurviveAnEventRoundTrip() {
        SpellPresets.forGroup(GROUP_RELAY, GROUP_ID).forEach { spell ->
            val event = spell.toUnsignedEvent(MEMBER_A, NOW).copy(id = "e".repeat(64))
            val reparsed =
                when (val r = parseSpell(event)) {
                    is Result.Success -> r.data
                    is Result.Error -> error("${spell.id} did not round-trip: ${r.error.message}")
                }
            assertEquals(spell.kinds, reparsed.kinds, spell.id)
            assertEquals(spell.authors, reparsed.authors, spell.id)
            assertEquals(spell.tagFilters, reparsed.tagFilters, spell.id)
            assertEquals(spell.group, reparsed.group, spell.id)
        }
    }

    @Test
    fun unknownSlugsResolveToNothing() {
        assertNull(SpellPresets.bySlug("nope"))
        assertNull(SpellPresets.forGroup("nope", GROUP_RELAY, GROUP_ID))
        assertNotNull(SpellPresets.bySlug("images"))
    }

    @Test
    fun railPresetsResolveFromIdentityAlone() {
        val spells = SpellPresets.forRail()
        assertEquals(SpellPresets.rail.size, spells.size)
        spells.forEach { spell ->
            assertNull(spell.group, spell.id)
            // Empty relays means "use my NIP-65 read list"; a pinned relay would be wrong here.
            assertTrue(spell.relays.isEmpty(), spell.id)
            assertEquals(listOf(1), spell.kinds, spell.id)
            spell.filter()
        }
    }

    @Test
    fun railPresetsCarryTheExpectedFilters() {
        assertEquals(listOf(VAR_CONTACTS), requireNotNull(SpellPresets.forRail("contacts-notes")).authors)
        assertEquals(listOf(VAR_ME), requireNotNull(SpellPresets.forRail("my-notes")).authors)
        // Mentions is a #p condition, not an author filter.
        assertEquals(mapOf("p" to listOf(VAR_ME)), requireNotNull(SpellPresets.forRail("mentions")).tagFilters)
        assertEquals(emptyList(), requireNotNull(SpellPresets.forRail("mentions")).authors)
    }

    @Test
    fun railPresetsNeverTargetAGroupRelay() {
        val nip65 = listOf("wss://nos.lol", GROUP_RELAY)
        // Unbound spells have no group relay to exclude, so the caller must not pass one in.
        SpellPresets.forRail().forEach { assertEquals(nip65, it.targetRelays(nip65), it.id) }
    }

    @Test
    fun railAndGroupSlugsDoNotCollide() {
        assertEquals(SpellPresets.all.size, SpellPresets.slugs.distinct().size)
        assertNull(SpellPresets.forRail("images"))
        assertNull(SpellPresets.forGroup("mentions", GROUP_RELAY, GROUP_ID))
    }

    @Test
    fun relayFeedTargetsExactlyTheGivenRelays() {
        val feed = relayFeed("Damus firehose", listOf("wss://relay.damus.io"))
        assertEquals(listOf("wss://relay.damus.io"), feed.targetRelays(listOf("wss://nos.lol")))
        // No author filter at all: the relay is the whole selection criterion.
        assertTrue(feed.authors.isEmpty())
        val filter = feed.filter()
        assertEquals(listOf(1), filter.kinds)
        assertNull(filter.authors)
    }

    @Test
    fun displayNamesAvoidEmDash() {
        assertFalse(SpellPresets.all.any { it.displayName.contains("—") || it.description.contains("—") })
    }
}
