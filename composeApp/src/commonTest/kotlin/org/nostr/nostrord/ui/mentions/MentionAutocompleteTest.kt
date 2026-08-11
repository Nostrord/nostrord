package org.nostr.nostrord.ui.mentions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MentionAutocompleteTest {
    private data class Member(val name: String, val pubkey: String)

    @Test
    fun `detects a user mention at the caret`() {
        val ctx = MentionAutocomplete.detect("hey @ali", 8)
        assertEquals(MentionCtx('@', "ali", 4), ctx)
    }

    @Test
    fun `detects a group mention`() {
        val ctx = MentionAutocomplete.detect("%dev", 4)
        assertEquals(MentionCtx('%', "dev", 0), ctx)
    }

    @Test
    fun `a bare trigger offers the full list`() {
        assertEquals(MentionCtx('@', "", 0), MentionAutocomplete.detect("@", 1))
    }

    @Test
    fun `mid-word trigger is not a mention`() {
        assertNull(MentionAutocomplete.detect("mail@example", 12))
        assertNull(MentionAutocomplete.detect("100%sure", 8))
    }

    @Test
    fun `a space ends the mention`() {
        assertNull(MentionAutocomplete.detect("@ali ce", 7))
    }

    @Test
    fun `the nearest trigger wins`() {
        assertEquals(MentionCtx('%', "de", 7), MentionAutocomplete.detect("@alice %de", 10))
    }

    @Test
    fun `sustain keeps the mention alive when the IME reports a stale cursor`() {
        val previous = MentionCtx('@', "al", 4)
        assertEquals(MentionCtx('@', "ali", 4), MentionAutocomplete.sustain("hey @ali", previous))
    }

    @Test
    fun `sustain drops the mention once the token is broken`() {
        assertNull(MentionAutocomplete.sustain("hey @ali ce", MentionCtx('@', "ali", 4)))
        assertNull(MentionAutocomplete.sustain("hey there", MentionCtx('@', "ali", 4)))
    }

    @Test
    fun `filter matches display name or key and caps the list`() {
        val members = listOf(
            Member("Alice", "aa11"),
            Member("Bob", "bb22"),
            Member("Alfred", "cc33"),
        )
        val keys: (Member) -> List<String> = { listOf(it.name, it.pubkey) }

        assertEquals(listOf("Alice", "Alfred"), MentionAutocomplete.filter(members, "al", keys = keys).map { it.name })
        assertEquals(listOf("Bob"), MentionAutocomplete.filter(members, "bb2", keys = keys).map { it.name })
        assertEquals(2, MentionAutocomplete.filter(members, "", limit = 2, keys = keys).size)
    }

    @Test
    fun `filter folds decorative unicode like the member search`() {
        val members = listOf(Member("𝐀lice", "aa11"))
        assertEquals(1, MentionAutocomplete.filter(members, "ali", keys = { listOf(it.name) }).size)
    }

    @Test
    fun `insert replaces the typed token and leaves the caret after it`() {
        val ctx = MentionCtx('@', "ali", 4)
        val result = MentionAutocomplete.insert("hey @ali", ctx, "Alice")
        assertEquals("hey @Alice ", result.text)
        assertEquals(result.text.length, result.cursor)
    }

    @Test
    fun `insert mid-sentence keeps the rest without doubling the space`() {
        val ctx = MentionCtx('@', "al", 4)
        val result = MentionAutocomplete.insert("hey @al how are you", ctx, "Alice")
        assertEquals("hey @Alice how are you", result.text)
        assertEquals("hey @Alice ".length, result.cursor)
    }

    @Test
    fun `insert handles a group label with spaces`() {
        val ctx = MentionCtx('%', "no", 0)
        assertEquals("%Nostr Devs ", MentionAutocomplete.insert("%no", ctx, "Nostr Devs").text)
    }
}
