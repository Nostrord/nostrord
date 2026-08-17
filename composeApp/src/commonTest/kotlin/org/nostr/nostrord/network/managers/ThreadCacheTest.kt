package org.nostr.nostrord.network.managers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.storage.cache.InMemoryCacheStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PUBKEY = "00000000000000000000000000000000000000000000000000000000cafed00d"
private const val GROUP = "thread-cache-group"
private const val RELAY = "wss://relay.test"

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadCacheTest {
    private fun makeManager(scope: TestScope, store: InMemoryCacheStore): GroupManager = GroupManager(connectionManager = ConnectionManager(scope), scope = scope, cacheStore = store)

    private fun threadMsg(id: String, kind: Int, createdAt: Long) = NostrGroupClient.NostrMessage(
        id = id,
        pubkey = "author",
        content = "body $id",
        createdAt = createdAt,
        kind = kind,
        tags = listOf(listOf("h", GROUP)),
    )

    // The raw relay frame handleMessage parses the group id ("h" tag) out of.
    private fun rawFrame(id: String, kind: Int) = """["EVENT","threads_$GROUP",{"id":"$id","kind":$kind,"tags":[["h","$GROUP"]]}]"""

    private fun deliver(manager: GroupManager, msg: NostrGroupClient.NostrMessage) {
        manager.handleMessage(msg, rawFrame(msg.id, msg.kind), "threads_$GROUP", RELAY)
    }

    @Test
    fun `thread roots and replies persist and hydrate a fresh manager before any relay`() = runTest {
        val scope = TestScope(testScheduler)
        val store = InMemoryCacheStore()
        val writer = makeManager(scope, store)
        writer.setCurrentPubkey(PUBKEY)
        writer.setGroupRelayHint(GROUP, RELAY)

        deliver(writer, threadMsg("root1", kind = 11, createdAt = 100))
        deliver(writer, threadMsg("reply1", kind = 1111, createdAt = 200))
        testScheduler.advanceUntilIdle() // flush the fire-and-forget cache writes

        // Cold start: a fresh manager with no connected client hydrates from the shared store.
        val reader = makeManager(scope, store)
        reader.setCurrentPubkey(PUBKEY)
        reader.setGroupRelayHint(GROUP, RELAY)
        assertEquals(false, reader.requestGroupThreads(GROUP)) // no client -> false, but hydrated
        assertEquals(listOf("root1"), reader.threadRoots.value[GROUP]?.map { it.id })
        assertEquals(listOf("reply1"), reader.threadReplies.value[GROUP]?.map { it.id })
        // Restored events carry the relay stamp so relay-scoped screens accept them.
        assertEquals(RELAY, reader.threadRoots.value[GROUP]?.single()?.relayUrl)

        scope.cancel()
    }

    @Test
    fun `the route's relay hydrates the pane before any group listing arrives`() = runTest {
        val scope = TestScope(testScheduler)
        val store = InMemoryCacheStore()
        val writer = makeManager(scope, store)
        writer.setCurrentPubkey(PUBKEY)
        writer.setGroupRelayHint(GROUP, RELAY)

        deliver(writer, threadMsg("root1", kind = 11, createdAt = 100))
        testScheduler.advanceUntilIdle()

        // Cold start proper: no relay hint, no kind:39000 listing, no kind:10009 - the state
        // getRelayForGroup reads is empty, so only the caller's relay can name the cache slot.
        val reader = makeManager(scope, store)
        reader.setCurrentPubkey(PUBKEY)
        assertEquals(null, reader.getRelayForGroup(GROUP))

        reader.requestGroupThreads(GROUP, RELAY)
        assertEquals(listOf("root1"), reader.threadRoots.value[GROUP]?.map { it.id })
        // The route's relay is authoritative, so it is recorded for every later resolution.
        assertEquals(RELAY, reader.getRelayForGroup(GROUP))

        scope.cancel()
    }

    @Test
    fun `without a relay the cold pane cannot hydrate`() = runTest {
        val scope = TestScope(testScheduler)
        val store = InMemoryCacheStore()
        val writer = makeManager(scope, store)
        writer.setCurrentPubkey(PUBKEY)
        writer.setGroupRelayHint(GROUP, RELAY)

        deliver(writer, threadMsg("root1", kind = 11, createdAt = 100))
        testScheduler.advanceUntilIdle()

        // The slot is relay-scoped by design: a caller with no relay has nothing to look up.
        // Guards the fallback path, so dropping the parameter cannot silently pass.
        val reader = makeManager(scope, store)
        reader.setCurrentPubkey(PUBKEY)
        reader.requestGroupThreads(GROUP)
        assertTrue(reader.threadRoots.value[GROUP].isNullOrEmpty())

        scope.cancel()
    }

    @Test
    fun `a deleted thread root does not rehydrate from cache`() = runTest {
        val scope = TestScope(testScheduler)
        val store = InMemoryCacheStore()
        val writer = makeManager(scope, store)
        writer.setCurrentPubkey(PUBKEY)
        writer.setGroupRelayHint(GROUP, RELAY)

        deliver(writer, threadMsg("root1", kind = 11, createdAt = 100))
        testScheduler.advanceUntilIdle()

        // The author's kind:5 delete arrives; the cache row must be evicted with it.
        val deletion = NostrGroupClient.NostrMessage(
            id = "del1",
            pubkey = "author",
            content = "",
            createdAt = 300,
            kind = 5,
            tags = listOf(listOf("h", GROUP), listOf("e", "root1")),
        )
        writer.handleMessage(
            deletion,
            """["EVENT","threads_$GROUP",{"id":"del1","kind":5,"tags":[["h","$GROUP"],["e","root1"]]}]""",
            "threads_$GROUP",
            RELAY,
        )
        testScheduler.advanceUntilIdle()

        val reader = makeManager(scope, store)
        reader.setCurrentPubkey(PUBKEY)
        reader.setGroupRelayHint(GROUP, RELAY)
        reader.requestGroupThreads(GROUP)
        assertTrue(reader.threadRoots.value[GROUP].isNullOrEmpty())

        scope.cancel()
    }
}
