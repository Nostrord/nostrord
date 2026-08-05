package org.nostr.nostrord.ui.text

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownPreviewTest {
    @Test
    fun `emphasis markers are dropped and their text kept`() {
        assertEquals("bold", flattenMarkdownForPreview("*bold*"))
        assertEquals("bold", flattenMarkdownForPreview("**bold**"))
        assertEquals("italic", flattenMarkdownForPreview("_italic_"))
        assertEquals("gone", flattenMarkdownForPreview("~~gone~~"))
        assertEquals("look at this now", flattenMarkdownForPreview("look at *this* now"))
    }

    @Test
    fun `spoiler content is masked, never unwrapped`() {
        assertEquals("vejam $SPOILER_MASK serio", flattenMarkdownForPreview("vejam ||o final do filme|| serio"))
        assertEquals(SPOILER_MASK, flattenMarkdownForPreview("||the killer is the butler||"))
    }

    @Test
    fun `an outer marker does not expose a nested spoiler`() {
        assertEquals(SPOILER_MASK, flattenMarkdownForPreview("**||secret||**"))
        assertEquals("see $SPOILER_MASK", flattenMarkdownForPreview("~~see ||secret||~~"))
    }

    @Test
    fun `nested emphasis unwraps completely`() {
        assertEquals("bold with italic", flattenMarkdownForPreview("**bold with _italic_**"))
    }

    @Test
    fun `code keeps its text and loses its backticks`() {
        assertEquals("run npm test now", flattenMarkdownForPreview("run `npm test` now"))
        assertEquals("val x = 1\n", flattenMarkdownForPreview("```kotlin\nval x = 1\n```"))
    }

    @Test
    fun `text without markers is untouched`() {
        assertEquals("", flattenMarkdownForPreview(""))
        assertEquals("plain message", flattenMarkdownForPreview("plain message"))
        assertEquals("@ana hi", flattenMarkdownForPreview("@ana hi"))
    }

    @Test
    fun `literal marker characters survive, matching the chat`() {
        assertEquals("if (a || b || c)", flattenMarkdownForPreview("if (a || b || c)"))
        assertEquals("1 * 2 * 3 = 6", flattenMarkdownForPreview("1 * 2 * 3 = 6"))
        assertEquals("nip44_decrypt_batch", flattenMarkdownForPreview("nip44_decrypt_batch"))
    }

    @Test
    fun `flattening is idempotent`() {
        val once = flattenMarkdownForPreview("**bold** and ||secret|| and _it_")
        assertEquals(once, flattenMarkdownForPreview(once))
    }
}
