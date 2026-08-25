package org.nostr.nostrord.network.managers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.storage.cache.CachedMsg
import org.nostr.nostrord.storage.cache.InMemoryCacheStore
import kotlin.test.Test
import kotlin.test.assertEquals

private const val HYDRATE_PUBKEY = "00000000000000000000000000000000000000000000000000000000deadbeef"
private const val HYDRATE_GROUP = "cache-group"

@OptIn(ExperimentalCoroutinesApi::class)
class GroupCacheHydrationTest {
    @Test
    fun `opening a group renders its messages from the persistent cache`() = runTest {
        val scope = TestScope(testScheduler)
        val cache = InMemoryCacheStore()
        // A previously-seen group's history already sits in the cache (e.g. a prior
        // session), under its relay-scoped slot.
        val slot = "wss://cache.relay|$HYDRATE_GROUP"
        cache.upsertMessages(
            HYDRATE_PUBKEY,
            slot,
            listOf(
                CachedMsg("m1", slot, "p", createdAt = 100, kind = 9, content = "hi", tagsJson = "[]"),
                CachedMsg("m2", slot, "p", createdAt = 200, kind = 9, content = "there", tagsJson = "[]"),
            ),
        )
        val manager = GroupManager(connectionManager = ConnectionManager(scope), scope = scope, cacheStore = cache)
        manager.setCurrentPubkey(HYDRATE_PUBKEY)
        // The slot is picked by the group's resolved relay.
        manager.setGroupRelayHint(HYDRATE_GROUP, "wss://cache.relay")

        // Opening the group hydrates from cache with no relay round-trip.
        manager.setActiveGroupId(HYDRATE_GROUP)
        advanceUntilIdle()

        assertEquals(listOf("m1", "m2"), manager.getMessagesForGroup(HYDRATE_GROUP).map { it.id })

        scope.cancel()
    }

    @Test
    fun `opening a group renders its cached chat reactions with the messages`() = runTest {
        val scope = TestScope(testScheduler)
        val cache = InMemoryCacheStore()
        val slot = "wss://cache.relay|$HYDRATE_GROUP"
        cache.upsertMessages(
            HYDRATE_PUBKEY,
            slot,
            listOf(CachedMsg("m1", slot, "p", createdAt = 100, kind = 9, content = "hi", tagsJson = "[]")),
        )
        // Reactions live in their own slot so they never eat into the message page.
        val reactionSlot = "wss://cache.relay|$HYDRATE_GROUP|reactions"
        cache.upsertMessages(
            HYDRATE_PUBKEY,
            reactionSlot,
            listOf(
                CachedMsg(
                    "r1",
                    reactionSlot,
                    "reactor",
                    createdAt = 150,
                    kind = 7,
                    content = "\uD83D\uDD25",
                    tagsJson = """[["e","m1"],["p","p"],["h","$HYDRATE_GROUP"]]""",
                ),
            ),
        )
        val manager = GroupManager(connectionManager = ConnectionManager(scope), scope = scope, cacheStore = cache)
        manager.setCurrentPubkey(HYDRATE_PUBKEY)
        manager.setGroupRelayHint(HYDRATE_GROUP, "wss://cache.relay")

        manager.setActiveGroupId(HYDRATE_GROUP)
        advanceUntilIdle()

        // The chip is there on the same paint as the message, so no row grows after the fact.
        assertEquals(listOf("reactor"), manager.reactions.value["m1"]?.get("\uD83D\uDD25")?.reactors)

        scope.cancel()
    }
}
