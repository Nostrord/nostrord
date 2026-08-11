package org.nostr.nostrord.network.managers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.toCachedEvent
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.cache.InMemoryCacheStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val MIRROR_PUBKEY = "00000000000000000000000000000000000000000000000000000000cafebabe"
private const val MIRROR_GROUP = "mirror-group"

/**
 * A quoted thread ("Share to chat") resolves through the generic by-id event cache, which the
 * kind-keyed thread stores never reach. Without the mirror the card pays a network round-trip for
 * an event already in memory, and reads as removed whenever the relay withholds it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadEventCacheMirrorTest {
    @AfterTest
    fun cleanup() = SecureStorage.clearAllMessagesForAccount(MIRROR_PUBKEY)

    private fun manager(scope: TestScope, mirrored: MutableList<NostrGroupClient.NostrMessage>) = GroupManager(
        connectionManager = ConnectionManager(scope),
        scope = scope,
        cacheStore = InMemoryCacheStore(),
        onThreadEventCached = { mirrored += it },
    ).apply { setCurrentPubkey(MIRROR_PUBKEY) }

    private val sign: suspend (Event) -> Event = { it.copy(id = it.calculateId(), sig = "ff".repeat(64)) }

    @Test
    fun `a published thread root reaches the by-id event cache`() = runTest {
        val scope = TestScope(testScheduler)
        val mirrored = mutableListOf<NostrGroupClient.NostrMessage>()
        val manager = manager(scope, mirrored)

        manager.createThread(groupId = MIRROR_GROUP, title = "Release notes", content = "body", pubKey = MIRROR_PUBKEY, signEvent = sign)
        advanceUntilIdle()

        val root = mirrored.single()
        assertEquals(11, root.kind)
        // The card titles itself from the subject tag, so the mirrored copy has to carry the tags.
        val cached = root.toCachedEvent()
        assertEquals("Release notes", cached.tags.firstOrNull { it.size >= 2 && it[0] == "subject" }?.get(1))
        assertEquals(root.id, cached.id)
        assertEquals(11, cached.kind)

        scope.cancel()
    }

    @Test
    fun `a reply is mirrored too, so quoting one resolves the same way`() = runTest {
        val scope = TestScope(testScheduler)
        val mirrored = mutableListOf<NostrGroupClient.NostrMessage>()
        val manager = manager(scope, mirrored)

        manager.createThread(groupId = MIRROR_GROUP, title = "Root", content = "body", pubKey = MIRROR_PUBKEY, signEvent = sign)
        advanceUntilIdle()
        val root = mirrored.single()

        manager.sendThreadReply(groupId = MIRROR_GROUP, root = root, parent = root, content = "re", pubKey = MIRROR_PUBKEY, signEvent = sign)
        advanceUntilIdle()

        assertEquals(listOf(11, 1111), mirrored.map { it.kind })
    }
}
