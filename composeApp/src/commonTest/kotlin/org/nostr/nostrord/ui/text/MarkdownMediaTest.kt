package org.nostr.nostrord.ui.text

import org.nostr.nostrord.ui.components.chat.MessageContentParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MarkdownMediaTest {
    @Test
    fun `unwraps image embed to bare url`() {
        assertEquals(
            "Testando imagem\nhttps://24242.io/abc.jpg",
            MarkdownMedia.unwrapImages("Testando imagem\n![image](https://24242.io/abc.jpg)"),
        )
    }

    @Test
    fun `unwraps every embed including the title form`() {
        assertEquals(
            "https://a.com/1.png and https://b.com/2.gif",
            MarkdownMedia.unwrapImages("""![](https://a.com/1.png) and ![alt text](https://b.com/2.gif "a title")"""),
        )
    }

    @Test
    fun `unwraps inline data image embed`() {
        assertEquals(
            "data:image/png;base64,AAAA",
            MarkdownMedia.unwrapImages("![img](data:image/png;base64,AAAA)"),
        )
    }

    @Test
    fun `leaves non-embed text alone`() {
        val untouched =
            listOf(
                "look at this ![ thing",
                "![alt](/relative/path.jpg)",
                "[link](https://example.com/page)",
            )
        untouched.forEach { assertEquals(it, MarkdownMedia.unwrapImages(it)) }
    }

    @Test
    fun `parser renders an image embed as an image part`() {
        val parts = MessageContentParser.parse("Testando imagem ![image](https://24242.io/abc.jpg)")

        assertEquals(2, parts.size)
        assertIs<MessageContentParser.ParsedPart.Text>(parts[0])
        assertEquals("Testando imagem ", (parts[0] as MessageContentParser.ParsedPart.Text).content)
        assertIs<MessageContentParser.ParsedPart.Image>(parts[1])
        assertEquals("https://24242.io/abc.jpg", (parts[1] as MessageContentParser.ParsedPart.Image).url)
    }
}
