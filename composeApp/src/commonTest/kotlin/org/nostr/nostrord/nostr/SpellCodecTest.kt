package org.nostr.nostrord.nostr

import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOW = 1_740_000_000L
private val ALICE = "aa".repeat(32)
private val BOB = "bb".repeat(32)
private val CAROL = "cc".repeat(32)

private fun spellEvent(
    tags: List<List<String>>,
    content: String = "",
    kind: Int = SPELL_KIND,
) = Event(id = "e".repeat(64), pubkey = ALICE, createdAt = NOW, kind = kind, tags = tags, content = content)

private fun <T> Result<T>.unwrap(): T = when (this) {
    is Result.Success -> data
    is Result.Error -> error("expected success, got ${error.message}")
}

private fun <T> Result<T>.errorOf(): AppError = when (this) {
    is Result.Success -> error("expected failure, got $data")
    is Result.Error -> error
}

class RelativeTimeTest {
    @Test
    fun resolvesEveryUnit() {
        assertEquals(NOW - 30, parseRelativeTime("30s", NOW))
        assertEquals(NOW - 5 * 60, parseRelativeTime("5m", NOW))
        assertEquals(NOW - 12 * 3_600, parseRelativeTime("12h", NOW))
        assertEquals(NOW - 7 * 86_400, parseRelativeTime("7d", NOW))
        assertEquals(NOW - 2 * 604_800, parseRelativeTime("2w", NOW))
        assertEquals(NOW - 3 * 2_592_000, parseRelativeTime("3mo", NOW))
        assertEquals(NOW - 31_536_000, parseRelativeTime("1y", NOW))
    }

    @Test
    fun monthsWinOverMinutes() {
        // "mo" must not be parsed as "m" with a trailing character.
        assertEquals(NOW - 2_592_000, parseRelativeTime("1mo", NOW))
        assertEquals(NOW - 60, parseRelativeTime("1m", NOW))
    }

    @Test
    fun resolvesNowAndAbsolute() {
        assertEquals(NOW, parseRelativeTime("now", NOW))
        assertEquals(1_704_067_200L, parseRelativeTime("1704067200", NOW))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(parseRelativeTime("", NOW))
        assertNull(parseRelativeTime("soon", NOW))
        assertNull(parseRelativeTime("7x", NOW))
        assertNull(parseRelativeTime("d7", NOW))
        assertNull(parseRelativeTime("-7d", NOW))
    }
}

class SpellParseTest {
    @Test
    fun parsesTheDraftReqExample() {
        val spell =
            parseSpell(
                spellEvent(
                    content = "Notes about Bitcoin from my contacts",
                    tags =
                    listOf(
                        listOf("cmd", "REQ"),
                        listOf("name", "Bitcoin from contacts"),
                        listOf("alt", "Spell: notes about Bitcoin from contacts"),
                        listOf("k", "1"),
                        listOf("authors", VAR_CONTACTS),
                        listOf("tag", "t", "bitcoin"),
                        listOf("since", "7d"),
                        listOf("limit", "50"),
                        listOf("t", "bitcoin"),
                        listOf("t", "social"),
                    ),
                ),
            ).unwrap()

        assertEquals(SpellCmd.REQ, spell.cmd)
        assertEquals("Bitcoin from contacts", spell.name)
        assertEquals("Notes about Bitcoin from my contacts", spell.description)
        assertEquals(listOf(1), spell.kinds)
        assertEquals(listOf(VAR_CONTACTS), spell.authors)
        assertEquals(mapOf("t" to listOf("bitcoin")), spell.tagFilters)
        assertEquals("7d", spell.since)
        assertEquals(50, spell.limit)
        // A top-level ["t", ...] categorizes the spell; ["tag","t",...] filters events. Both here.
        assertEquals(listOf("bitcoin", "social"), spell.topics)
    }

    @Test
    fun parsesTheDraftCountExample() {
        val spell =
            parseSpell(
                spellEvent(
                    tags =
                    listOf(
                        listOf("cmd", "COUNT"),
                        listOf("k", "1"),
                        listOf("k", "6"),
                        listOf("k", "7"),
                        listOf("authors", VAR_ME),
                        listOf("since", "1704067200"),
                        listOf("close-on-eose"),
                    ),
                ),
            ).unwrap()

        assertEquals(SpellCmd.COUNT, spell.cmd)
        assertEquals(listOf(1, 6, 7), spell.kinds)
        assertTrue(spell.closeOnEose)
    }

    @Test
    fun parsesTheDraftSearchExample() {
        val spell =
            parseSpell(
                spellEvent(
                    content = "Search for Nostr dev discussions",
                    tags =
                    listOf(
                        listOf("cmd", "REQ"),
                        listOf("name", "Nostr dev search"),
                        listOf("k", "1"),
                        listOf("search", "nostr development"),
                        listOf("relays", "wss://relay.damus.io", "wss://nos.lol"),
                        listOf("limit", "100"),
                    ),
                ),
            ).unwrap()

        assertEquals("nostr development", spell.search)
        assertEquals(listOf("wss://relay.damus.io", "wss://nos.lol"), spell.relays)
    }

    @Test
    fun parsesGroupBindingAndFork() {
        val spell =
            parseSpell(
                spellEvent(
                    tags =
                    listOf(
                        listOf("cmd", "REQ"),
                        listOf("k", "1"),
                        listOf("group", "wss://relay.example", "abc123"),
                        listOf("e", "f".repeat(64)),
                    ),
                ),
            ).unwrap()

        assertEquals(GroupBinding("wss://relay.example", "abc123"), spell.group)
        assertEquals("f".repeat(64), spell.forkedFrom)
    }

    @Test
    fun rejectsWrongKind() {
        val err = parseSpell(spellEvent(tags = listOf(listOf("cmd", "REQ"), listOf("k", "1")), kind = 1)).errorOf()
        assertTrue(err is AppError.Spell.Malformed)
    }

    @Test
    fun rejectsMissingCmd() {
        val err = parseSpell(spellEvent(tags = listOf(listOf("k", "1")))).errorOf()
        assertTrue(err is AppError.Spell.Malformed)
    }

    @Test
    fun rejectsUnknownCmd() {
        val err = parseSpell(spellEvent(tags = listOf(listOf("cmd", "DELETE"), listOf("k", "1")))).errorOf()
        assertTrue(err is AppError.Spell.Malformed)
    }

    @Test
    fun rejectsSpellWithNoFilter() {
        val err = parseSpell(spellEvent(tags = listOf(listOf("cmd", "REQ"), listOf("name", "empty")))).errorOf()
        assertTrue(err is AppError.Spell.Malformed)
    }

    @Test
    fun ignoresMalformedTagsWithoutFailing() {
        val spell =
            parseSpell(
                spellEvent(
                    tags =
                    listOf(
                        listOf("cmd", "REQ"),
                        listOf("k", "1"),
                        listOf("k", "not-a-number"),
                        listOf("tag"),
                        listOf("tag", "toolong", "x"),
                        listOf("tag", "t"),
                        listOf("group", "wss://relay.example"),
                        emptyList(),
                    ),
                ),
            ).unwrap()

        assertEquals(listOf(1), spell.kinds)
        assertEquals(emptyMap(), spell.tagFilters)
        assertNull(spell.group)
    }
}

class SpellEncodeTest {
    @Test
    fun roundTripsThroughAnEvent() {
        val original =
            Spell(
                id = "",
                name = "Members with photos",
                description = "what the group posts",
                cmd = SpellCmd.REQ,
                kinds = listOf(20, 21),
                authors = listOf(VAR_MEMBERS, ALICE),
                ids = listOf("d".repeat(64)),
                tagFilters = mapOf("h" to listOf("group-1"), "t" to listOf("art", "photo")),
                relays = listOf("wss://relay.example"),
                limit = 25,
                since = "3d",
                until = "now",
                search = "sunset",
                closeOnEose = true,
                topics = listOf("photography"),
                forkedFrom = "f".repeat(64),
                group = GroupBinding("wss://relay.example", "group-1"),
            )

        val event = original.toUnsignedEvent(ALICE, NOW)
        assertEquals(SPELL_KIND, event.kind)

        val reparsed = parseSpell(event.copy(id = "e".repeat(64))).unwrap()
        assertEquals(original.copy(id = reparsed.id, pubkey = ALICE, createdAt = NOW), reparsed)
    }

    @Test
    fun encodesTagFiltersUnderTheTagNamespace() {
        val event =
            Spell(id = "", name = "n", kinds = listOf(1), tagFilters = mapOf("p" to listOf(BOB)))
                .toUnsignedEvent(ALICE, NOW)

        // A bare ["p", ...] would read as a social-graph reference to the network.
        assertTrue(event.tags.none { it.firstOrNull() == "p" })
        assertTrue(event.tags.any { it == listOf("tag", "p", BOB) })
        assertTrue(event.tags.any { it == listOf("k", "1") })
    }
}

class SpellResolveTest {
    private val ctx = SpellContext(me = ALICE, contacts = listOf(BOB), members = listOf(BOB, CAROL), now = NOW)

    @Test
    fun expandsVariablesAndRelativeTimes() {
        val filter =
            Spell(
                id = "",
                name = "n",
                kinds = listOf(1),
                authors = listOf(VAR_MEMBERS, VAR_ME),
                tagFilters = mapOf("t" to listOf("bitcoin")),
                since = "7d",
                limit = 50,
            ).resolve(ctx).unwrap()

        assertEquals(listOf(BOB, CAROL, ALICE), filter.authors)
        assertEquals(listOf(1), filter.kinds)
        assertEquals(mapOf("#t" to listOf("bitcoin")), filter.tags)
        assertEquals(NOW - 7 * 86_400, filter.since)
        assertEquals(50, filter.limit)
    }

    @Test
    fun deduplicatesOverlappingAuthors() {
        val filter =
            Spell(id = "", name = "n", kinds = listOf(1), authors = listOf(VAR_MEMBERS, BOB))
                .resolve(ctx).unwrap()
        assertEquals(listOf(BOB, CAROL), filter.authors)
    }

    @Test
    fun expandsVariablesInsideTagFilters() {
        val filter =
            Spell(id = "", name = "n", kinds = listOf(1), tagFilters = mapOf("p" to listOf(VAR_ME)))
                .resolve(ctx).unwrap()
        assertEquals(mapOf("#p" to listOf(ALICE)), filter.tags)
    }

    @Test
    fun failsWhenMeHasNoAccount() {
        val err =
            Spell(id = "", name = "n", kinds = listOf(1), authors = listOf(VAR_ME))
                .resolve(ctx.copy(me = null)).errorOf()
        assertEquals(VAR_ME, (err as AppError.Spell.UnresolvedVariable).variable)
    }

    @Test
    fun failsWhenContactsAreEmpty() {
        val err =
            Spell(id = "", name = "n", kinds = listOf(1), authors = listOf(VAR_CONTACTS))
                .resolve(ctx.copy(contacts = emptyList())).errorOf()
        assertEquals(VAR_CONTACTS, (err as AppError.Spell.UnresolvedVariable).variable)
    }

    @Test
    fun failsWhenMembersAreNotLoaded() {
        // Sending the literal "${'$'}members" would match nothing and read as an empty feed.
        val err =
            Spell(id = "", name = "n", kinds = listOf(1), authors = listOf(VAR_MEMBERS))
                .resolve(ctx.copy(members = emptyList())).errorOf()
        assertEquals(VAR_MEMBERS, (err as AppError.Spell.UnresolvedVariable).variable)
    }

    @Test
    fun failsOnUnparseableSince() {
        val err = Spell(id = "", name = "n", kinds = listOf(1), since = "whenever").resolve(ctx).errorOf()
        assertTrue(err is AppError.Spell.Malformed)
    }

    @Test
    fun serializesToAWireFilter() {
        val json =
            Spell(id = "", name = "n", kinds = listOf(1), authors = listOf(VAR_ME), tagFilters = mapOf("t" to listOf("nostr")))
                .resolve(ctx).unwrap().toJsonObject().toString()

        assertEquals("""{"authors":["$ALICE"],"kinds":[1],"#t":["nostr"]}""", json)
    }
}

class SpellChunkingTest {
    private val many = (0 until 1_200).map { it.toString().padStart(64, '0') }

    @Test
    fun keepsSmallAuthorListsWhole() {
        val filters =
            Spell(id = "", name = "n", kinds = listOf(1), authors = listOf(VAR_MEMBERS))
                .resolveChunked(SpellContext(members = many.take(10), now = NOW)).unwrap()
        assertEquals(1, filters.size)
    }

    @Test
    fun splitsOversizedAuthorLists() {
        val filters =
            Spell(id = "", name = "n", kinds = listOf(1), authors = listOf(VAR_MEMBERS))
                .resolveChunked(SpellContext(members = many, now = NOW)).unwrap()

        assertEquals(3, filters.size)
        assertEquals(listOf(500, 500, 200), filters.map { it.authors!!.size })
        assertEquals(many, filters.flatMap { it.authors!! })
        assertTrue(filters.all { it.kinds == listOf(1) })
    }
}

class SpellRoutingTest {
    private val groupRelay = "wss://groups.example"
    private val nip65 = listOf("wss://relay.damus.io", groupRelay, "wss://nos.lol")

    @Test
    fun explicitRelaysWin() {
        val spell =
            Spell(id = "", name = "n", kinds = listOf(20), relays = listOf(groupRelay), group = GroupBinding(groupRelay, "g1"))
        assertEquals(listOf(groupRelay), spell.targetRelays(nip65))
    }

    @Test
    fun fallbackDropsTheGroupRelay() {
        // A NIP-29 relay serves group events only, so a kind:1 query there returns empty.
        val spell = Spell(id = "", name = "n", kinds = listOf(1), group = GroupBinding(groupRelay, "g1"))
        assertEquals(listOf("wss://relay.damus.io", "wss://nos.lol"), spell.targetRelays(nip65))
    }

    @Test
    fun fallbackIgnoresRelayUrlCasing() {
        val spell = Spell(id = "", name = "n", kinds = listOf(1), group = GroupBinding("WSS://Groups.Example", "g1"))
        assertEquals(listOf("wss://relay.damus.io", "wss://nos.lol"), spell.targetRelays(nip65))
    }
}
