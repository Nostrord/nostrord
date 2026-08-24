package org.nostr.nostrord.ui.chat

import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.nostr.Nip19
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReplyReferenceTest {
    private val parentId = "a".repeat(63) + "b"
    private val otherId = "c".repeat(63) + "d"
    private val nevent = Nip19.encodeNevent(parentId, listOf("wss://relay.example"))
    private val note = Nip19.encodeNote(parentId)

    private fun message(content: String, tags: List<List<String>>) = NostrGroupClient.NostrMessage(
        id = "e".repeat(64),
        pubkey = "f".repeat(64),
        content = content,
        createdAt = 0L,
        kind = 9,
        tags = tags,
    )

    private fun reply(content: String) = message(content, listOf(listOf("q", parentId, "wss://relay.example", "f".repeat(64))))

    @Test
    fun eatsPointerThatOpensTheBody() {
        // The shape Flotilla writes: pointer, blank line, text.
        val m = reply("nostr:$nevent\n\nyes")
        assertEquals(parentId, getReplyParentId(m))
        assertEquals("yes", messageBody(m))
    }

    @Test
    fun eatsPointerSeparatedByASingleNewlineOrSpace() {
        assertEquals("yes", messageBody(reply("nostr:$nevent\nyes")))
        assertEquals("yes", messageBody(reply("nostr:$nevent yes")))
    }

    @Test
    fun eatsANotePointerToo() {
        assertEquals("yes", messageBody(reply("nostr:$note\n\nyes")))
    }

    @Test
    fun keepsAPointerFurtherIntoTheBody() {
        // A quote mid-sentence is deliberate: it renders as its own card, and stacking a reply
        // header on top of it would say the same thing twice.
        val m = reply("as I said in nostr:$nevent, no")
        assertNull(getReplyParentId(m))
        assertEquals("as I said in nostr:$nevent, no", messageBody(m))
    }

    @Test
    fun keepsAPointerThatIsTheWholeBody() {
        // A bare pointer is a share of the event, not a reply: eating it leaves nothing to render.
        val m = reply("nostr:$nevent")
        assertNull(getReplyParentId(m))
        assertEquals("nostr:$nevent", messageBody(m))
    }

    @Test
    fun keepsAPointerAimedAtAnotherEvent() {
        val m = reply("nostr:${Nip19.encodeNote(otherId)}\n\nyes")
        assertEquals(parentId, getReplyParentId(m))
        assertEquals("nostr:${Nip19.encodeNote(otherId)}\n\nyes", messageBody(m))
    }

    @Test
    fun eatsThePointerOfANip10ReplyMarker() {
        val m = message("nostr:$nevent\n\nyes", listOf(listOf("e", parentId, "wss://relay.example", "reply")))
        assertEquals(parentId, getReplyParentId(m))
        assertEquals("yes", messageBody(m))
    }

    @Test
    fun leavesAPlainMessageAlone() {
        val m = message("hello", emptyList())
        assertNull(getReplyParentId(m))
        assertEquals("hello", messageBody(m))
    }

    @Test
    fun naddrPointerStaysInline() {
        // A reply header cannot render an addressable event, so the coordinate keeps its card.
        val naddr = Nip19.encodeNaddr("chat", "wss://relay.example", 39000, "f".repeat(64))
        val coord = "39000:${"f".repeat(64)}:chat"
        val m = message("nostr:$naddr\n\nyes", listOf(listOf("q", coord)))
        assertNull(getReplyParentId(m))
        assertEquals("nostr:$naddr\n\nyes", messageBody(m))
    }
}
