package org.nostr.nostrord.network

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatMessageOrderTest {
    private fun msg(id: String, createdAt: Long) = NostrGroupClient.NostrMessage(
        id = id,
        pubkey = "pub",
        content = id,
        createdAt = createdAt,
        kind = 9,
    )

    @Test
    fun sameSecondMessagesOrderTheSameOnEveryClient() {
        val mine = msg("bbb", 1000)
        val theirs = msg("aaa", 1000)
        // Each client inserts its own send at the tail and the peer's on arrival.
        val sender = listOf(theirs, mine).sortedForDisplay()
        val peer = listOf(mine, theirs).sortedForDisplay()
        assertEquals(listOf("aaa", "bbb"), sender.map { it.id })
        assertEquals(sender.map { it.id }, peer.map { it.id })
    }

    @Test
    fun createdAtStillWins() {
        val older = msg("zzz", 999)
        val newer = msg("aaa", 1000)
        assertEquals(listOf("zzz", "aaa"), listOf(newer, older).sortedForDisplay().map { it.id })
    }
}
