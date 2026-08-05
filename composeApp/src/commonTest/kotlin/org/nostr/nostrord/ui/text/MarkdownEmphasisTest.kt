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
}
