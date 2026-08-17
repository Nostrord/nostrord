package org.nostr.nostrord.ui.text

import kotlin.test.Test
import kotlin.test.assertEquals

class BlockEmbedTextTest {
    @Test
    fun `one newline touching the block is absorbed`() {
        assertEquals("look at this", BlockEmbedText.trimBefore("look at this\n"))
        assertEquals("and here is why", BlockEmbedText.trimAfter("\nand here is why"))
        assertEquals("look at this", BlockEmbedText.trimBefore("look at this\r\n"))
        assertEquals("and here is why", BlockEmbedText.trimAfter("\r\nand here is why"))
    }

    @Test
    fun `a deliberate blank line survives`() {
        assertEquals("look at this\n", BlockEmbedText.trimBefore("look at this\n\n"))
        assertEquals("\nand here is why", BlockEmbedText.trimAfter("\n\nand here is why"))
    }

    @Test
    fun `text not touching a newline is untouched`() {
        assertEquals("inline ", BlockEmbedText.trimBefore("inline "))
        assertEquals(" inline", BlockEmbedText.trimAfter(" inline"))
        assertEquals("", BlockEmbedText.trimBefore(""))
        assertEquals("", BlockEmbedText.trimAfter(""))
    }
}
