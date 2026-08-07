package org.nostr.nostrord.nostr

import org.nostr.nostrord.utils.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val HEX = "aa".repeat(32)
private const val NOW = 1_740_000_000L

private fun Result<Spell>.spell(): Spell = when (this) {
    is Result.Success -> data
    is Result.Error -> error("expected a spell, got ${error.message}")
}

private fun Result<Spell>.reason(): String = when (this) {
    is Result.Success -> error("expected a failure, got ${data.name}")
    is Result.Error -> error.message
}

class SpellDraftTest {
    @Test
    fun buildsARelayFeed() {
        // fiatjaf's first example: kind:1 straight off named relays, no author criterion.
        val draft = SpellDraft(name = "Damus", kinds = "1", relays = "relay.damus.io, wss://nos.lol")
        val spell = draft.toSpell(NOW).spell()

        assertTrue(draft.isRelayFeed)
        assertEquals(listOf(1), spell.kinds)
        assertEquals(listOf("wss://relay.damus.io", "wss://nos.lol"), spell.relays)
        assertTrue(spell.authors.isEmpty())
        assertEquals("$LOCAL_ID_PREFIX$NOW", spell.id)
    }

    @Test
    fun acceptsRelaysAloneAsTheWholeCriterion() {
        // No kind, no author: the relay is the selection. hasFilter is false here by design.
        val spell = SpellDraft(name = "Everything", relays = "wss://nos.lol").toSpell(NOW).spell()
        assertEquals(listOf("wss://nos.lol"), spell.relays)
        assertTrue(spell.kinds.isEmpty())
    }

    @Test
    fun splitsOnCommasAndWhitespaceAlike() {
        val spell = SpellDraft(name = "n", kinds = "1, 6  7,,30023").toSpell(NOW).spell()
        assertEquals(listOf(1, 6, 7, 30023), spell.kinds)
    }

    @Test
    fun keepsRuntimeVariablesUnresolved() {
        val spell = SpellDraft(name = "n", kinds = "1", authors = "\$me, \$contacts").toSpell(NOW).spell()
        // Resolving here would freeze the follow list into the saved spell.
        assertEquals(listOf(VAR_ME, VAR_CONTACTS), spell.authors)
    }

    @Test
    fun acceptsHexAuthorsAndNormalizesCase() {
        val spell = SpellDraft(name = "n", kinds = "1", authors = HEX.uppercase()).toSpell(NOW).spell()
        assertEquals(listOf(HEX), spell.authors)
    }

    @Test
    fun acceptsNpubAuthors() {
        val npub = Nip19.encodeNpub(HEX)
        val spell = SpellDraft(name = "n", kinds = "1", authors = npub).toSpell(NOW).spell()
        assertEquals(listOf(HEX), spell.authors)
    }

    @Test
    fun stripsTheHashFromHashtags() {
        val spell = SpellDraft(name = "n", kinds = "1", hashtags = "#bitcoin nostr").toSpell(NOW).spell()
        assertEquals(mapOf("t" to listOf("bitcoin", "nostr")), spell.tagFilters)
    }

    @Test
    fun deduplicatesEveryList() {
        val spell =
            SpellDraft(name = "n", kinds = "1 1", authors = "$HEX $HEX", relays = "wss://nos.lol nos.lol")
                .toSpell(NOW).spell()
        assertEquals(listOf(1), spell.kinds)
        assertEquals(listOf(HEX), spell.authors)
        assertEquals(listOf("wss://nos.lol"), spell.relays)
    }

    @Test
    fun rejectsAnEmptyName() {
        assertTrue(SpellDraft(name = "   ", kinds = "1").toSpell(NOW).reason().contains("name"))
    }

    @Test
    fun rejectsADraftWithNothingToQuery() {
        assertTrue(SpellDraft(name = "n").toSpell(NOW).reason().contains("at least"))
    }

    @Test
    fun rejectsGarbageRatherThanQueryingForNothing() {
        // A typo'd author would be a valid REQ matching nothing, which reads as an empty feed.
        assertTrue(SpellDraft(name = "n", kinds = "1", authors = "npub1nope").toSpell(NOW).reason().contains("npub1nope"))
        assertTrue(SpellDraft(name = "n", kinds = "notes").toSpell(NOW).reason().contains("notes"))
        assertTrue(SpellDraft(name = "n", kinds = "1", limit = "0").toSpell(NOW).reason().contains("limit"))
    }

    @Test
    fun theResultIsAPinnableSpell() {
        val spell = SpellDraft(name = "Bitcoin", kinds = "1", hashtags = "bitcoin").toSpell(NOW).spell()
        val event = spell.toUnsignedEvent(HEX, NOW)
        val reparsed = parseSpell(event.copy(id = "e".repeat(64)))

        assertEquals(spell.kinds, reparsed.spell().kinds)
        assertEquals(spell.tagFilters, reparsed.spell().tagFilters)
        assertNull(spell.group)
    }
}

class ParseAuthorTest {
    @Test
    fun rejectsAnythingItCannotResolve() {
        assertNull(parseAuthor(""))
        assertNull(parseAuthor("alice@example.com"))
        assertNull(parseAuthor("\$everyone"))
        assertNull(parseAuthor("ab".repeat(20)))
        assertEquals(VAR_ME, parseAuthor("\$me"))
    }
}
