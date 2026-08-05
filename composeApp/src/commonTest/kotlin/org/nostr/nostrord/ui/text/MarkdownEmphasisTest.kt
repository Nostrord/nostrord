package org.nostr.nostrord.ui.text

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownEmphasisTest {
    private fun matches(text: String) = MarkdownEmphasis.italicUnderscoreRegex.findAll(text).map { it.groupValues[1] }.toList()

    @Test
    fun `underscores inside an identifier are not emphasis`() {
        assertEquals(emptyList(), matches("what is this nip44_decrypt_batch?"))
        assertEquals(emptyList(), matches("snake_case"))
        assertEquals(emptyList(), matches("a_b_c_d"))
    }

    @Test
    fun `emoji shortcodes keep their underscores`() {
        assertEquals(emptyList(), matches(":smiling_face_with_tear:"))
    }

    @Test
    fun `word-bounded underscores are emphasis`() {
        assertEquals(listOf("italic"), matches("_italic_"))
        assertEquals(listOf("italic"), matches("an _italic_ word"))
        assertEquals(listOf("italic"), matches("ends in punctuation _italic_."))
    }

    @Test
    fun `identifiers and emphasis coexist in one message`() {
        assertEquals(listOf("really"), matches("nip44_decrypt_batch is _really_ a custom method"))
    }

    @Test
    fun `a closing marker glued to a word does not emphasize`() {
        assertEquals(emptyList(), matches("foo _bar_baz_ qux"))
    }

    @Test
    fun `emphasis does not span lines`() {
        assertEquals(emptyList(), matches("_open\nclose_"))
    }

    @Test
    fun `pattern embeds in an alternation without changing behavior`() {
        val combined = Regex("\\*[^*\\n]+?\\*|" + MarkdownEmphasis.ITALIC_UNDERSCORE_PATTERN)
        val found = combined.findAll("*bold* nip44_decrypt_batch _italic_").map { it.value }.toList()
        assertEquals(listOf("*bold*", "_italic_"), found)
    }

    private fun tokens(text: String) = MarkdownEmphasis.inlineMarkerRegex.findAll(text).map { it.value }.toList()

    @Test
    fun `spoiler markers do not swallow a logical or`() {
        assertEquals(emptyList(), tokens("if (a || b || c) return"))
        assertEquals(emptyList(), tokens("cmd || echo fail || true"))
        assertEquals(listOf("||spoiler||"), tokens("a ||spoiler|| b"))
        assertEquals(listOf("||two words||"), tokens("||two words|| after"))
    }

    @Test
    fun `asterisks around whitespace are arithmetic, not bold`() {
        assertEquals(emptyList(), tokens("1 * 2 * 3 = 6"))
        assertEquals(emptyList(), tokens("SELECT * FROM t WHERE a = *"))
    }

    @Test
    fun `globs keep their asterisks`() {
        assertEquals(emptyList(), tokens("rm *.kt and *.js"))
    }

    @Test
    fun `a stray asterisk does not pair with a later one`() {
        // The whole line is literal: every * here is unpaired shell/arithmetic syntax.
        assertEquals(emptyList(), tokens("if (a || b || c) — 1 * 2 * 3 = 6 — rm *.kt and *.js — x**2"))
    }

    @Test
    fun `bold closes against punctuation and brackets`() {
        assertEquals(listOf("*bold*"), tokens("*bold*."))
        assertEquals(listOf("*bold*"), tokens("(*bold*)"))
        assertEquals(listOf("*bold*"), tokens("*bold*, next"))
        assertEquals(listOf("*a*", "*b*"), tokens("*a* *b*"))
    }

    @Test
    fun `a lone double asterisk is not an empty bold`() {
        assertEquals(emptyList(), tokens("x**2"))
    }

    @Test
    fun `markers do not span a newline`() {
        assertEquals(emptyList(), tokens("* one\n* two"))
        assertEquals(emptyList(), tokens("~~ one\ntwo ~~"))
    }

    @Test
    fun `ordinary formatting still renders`() {
        assertEquals(listOf("*bold*"), tokens("see *bold* here"))
        assertEquals(listOf("*b c*"), tokens("a *b c* d"))
        assertEquals(listOf("**bold**"), tokens("see **bold** here"))
        assertEquals(listOf("~~gone~~"), tokens("it is ~~gone~~ now"))
        assertEquals(listOf("_italic_"), tokens("an _italic_ word"))
    }

    @Test
    fun `longest marker wins at the same position`() {
        assertEquals(listOf("**bold**", "~~gone~~", "||hidden||"), tokens("**bold** ~~gone~~ ||hidden||"))
    }

    @Test
    fun `intraword double asterisk still bolds, as in CommonMark`() {
        assertEquals(listOf("**2 and y**"), tokens("x**2 and y**2"))
    }
}
