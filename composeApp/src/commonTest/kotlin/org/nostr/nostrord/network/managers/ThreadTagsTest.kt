package org.nostr.nostrord.network.managers

import org.nostr.nostrord.network.NostrGroupClient
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadTagsTest {
    private fun msg(id: String, pubkey: String, kind: Int) = NostrGroupClient.NostrMessage(id = id, pubkey = pubkey, content = "", createdAt = 0, kind = kind)

    @Test
    fun `root carries group h tag with relay hint and the title as both title and subject`() {
        val tags = ThreadTags.root("grp", "wss://groups.0xchat.com", "My title")
        assertEquals(listOf("h", "grp", "wss://groups.0xchat.com"), tags[0])
        // NIP-7D readers (Squalk - issue #221) want `title`; NIP-14 readers want `subject`.
        assertEquals(listOf("title", "My title"), tags.first { it[0] == "title" })
        assertEquals(listOf("subject", "My title"), tags.first { it[0] == "subject" })
    }

    @Test
    fun `root omits the title tags when blank and h has no hint when relay unknown`() {
        val tags = ThreadTags.root("grp", null, "   ")
        assertEquals(listOf("h", "grp"), tags[0])
        assertEquals(0, tags.count { it[0] == "subject" || it[0] == "title" })
    }

    @Test
    fun `root adds the relay-wide marker for the underscore group`() {
        val tags = ThreadTags.root("_", "wss://relay.example", null)
        assertEquals(1, tags.count { it[0] == "-" })
    }

    @Test
    fun `top-level reply links root scope and parent to the same kind 11 root`() {
        val root = msg("rootid", "rootpk", 11)
        val tags = ThreadTags.reply("grp", "wss://r", root, root)
        assertEquals(listOf("h", "grp", "wss://r"), tags[0])
        // Root scope (uppercase).
        assertEquals(listOf("E", "rootid", "wss://r", "rootpk"), tags.first { it[0] == "E" })
        assertEquals(listOf("K", "11"), tags.first { it[0] == "K" })
        assertEquals(listOf("P", "rootpk"), tags.first { it[0] == "P" })
        // A top-level reply (parent == root) omits the lowercase parent triple - it would only
        // duplicate E/K/P and inflate the indexable-tag count that strict relays reject. So just
        // h + E/K/P = 4 tags.
        assertEquals(4, tags.size)
        assertEquals(0, tags.count { it[0] == "e" })
        assertEquals(0, tags.count { it[0] == "k" })
        assertEquals(0, tags.count { it[0] == "p" })
    }

    @Test
    fun `nested reply keeps the root scope but points the parent at the replied-to reply`() {
        val root = msg("rootid", "rootpk", 11)
        val parent = msg("replyid", "replypk", 1111)
        val tags = ThreadTags.reply("grp", "wss://r", root, parent)
        // Root scope stays the original kind:11 root.
        assertEquals(listOf("E", "rootid", "wss://r", "rootpk"), tags.first { it[0] == "E" })
        assertEquals(listOf("K", "11"), tags.first { it[0] == "K" })
        // Parent is the kind:1111 reply being answered.
        assertEquals(listOf("e", "replyid", "wss://r", "replypk"), tags.first { it[0] == "e" })
        assertEquals(listOf("k", "1111"), tags.first { it[0] == "k" })
        assertEquals(listOf("p", "replypk"), tags.first { it[0] == "p" })
    }
}
