package org.nostr.nostrord.network.managers

import org.nostr.nostrord.nostr.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DmHistoryFileTest {
    private val me = "a".repeat(64)
    private val peer = "b".repeat(64)

    private fun rumor(id: String, author: String = me, recipient: String = peer, content: String = "hi") = Event(
        id = id,
        pubkey = author,
        createdAt = 1_700_000_000L,
        kind = 14,
        tags = listOf(listOf("p", recipient)),
        content = content,
    )

    @Test
    fun `a rendered file round-trips through the parser`() {
        val body = DmHistoryFile.render(listOf(rumor("1".repeat(64)), rumor("2".repeat(64))).map { it.toJsonString() })

        val parsed = DmHistoryFile.parse(body)

        assertEquals(2, parsed.rumors.size)
        assertEquals(0, parsed.skipped)
        assertEquals(listOf("1".repeat(64), "2".repeat(64)), parsed.rumors.map { it.id })
        assertEquals(listOf("p", peer), parsed.rumors.first().tags.first())
    }

    @Test
    fun `a Jumble export parses`() {
        // Shape taken from Jumble's exporter: one JSON.stringify'd rumor per line, no wrapper.
        val body =
            """
            {"id":"${"c".repeat(64)}","pubkey":"$peer","created_at":1700000001,"kind":14,"tags":[["p","$me"]],"content":"hey"}
            {"id":"${"d".repeat(64)}","pubkey":"$me","created_at":1700000002,"kind":15,"tags":[["p","$peer"]],"content":"file"}
            """.trimIndent()

        val parsed = DmHistoryFile.parse(body)

        assertEquals(2, parsed.rumors.size)
        assertEquals(0, parsed.skipped)
    }

    @Test
    fun `bad lines are counted instead of aborting the restore`() {
        val good = rumor("1".repeat(64)).toJsonString()
        val body =
            listOf(
                good,
                "not json at all",
                """{"id":"x","pubkey":"$me","created_at":1,"kind":1,"tags":[],"content":"a note"}""",
                """{"pubkey":"$me","created_at":1,"kind":14,"tags":[],"content":"no id"}""",
                "",
            ).joinToString("\n")

        val parsed = DmHistoryFile.parse(body)

        // A backup with a damaged line still restores everything else; blank lines are not damage.
        assertEquals(1, parsed.rumors.size)
        assertEquals(3, parsed.skipped)
    }

    @Test
    fun `the file name carries the date and the jsonl extension`() {
        val name = DmHistoryFile.fileName()
        assertTrue(name.startsWith("nostrord-dm-"), name)
        assertTrue(name.endsWith(".jsonl"), name)
        // nostrord-dm-YYYY-MM-DD.jsonl: zero-padded, so the date sorts lexicographically.
        assertTrue(Regex("""nostrord-dm-\d{4}-\d{2}-\d{2}\.jsonl""").matches(name), name)
    }
}
