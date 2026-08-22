package org.nostr.nostrord.network

/**
 * Display order for chat messages: created_at, then event id.
 *
 * created_at alone leaves same-second messages tied, and a stable sort keeps whatever arrival
 * order each client happened to have — the sender inserts its own message optimistically at the
 * tail while the peer receives it interleaved, so the two transcripts disagree and never
 * converge. The event id is the only field every client agrees on, so it breaks ties identically
 * everywhere.
 */
fun List<NostrGroupClient.NostrMessage>.sortedForDisplay(): List<NostrGroupClient.NostrMessage> = sortedWith(compareBy({ it.createdAt }, { it.id }))
