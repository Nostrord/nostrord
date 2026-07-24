package org.nostr.nostrord.network.managers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.cache.CachedMsg
import org.nostr.nostrord.storage.cache.InMemoryCacheStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SB_PUBKEY = "00000000000000000000000000000000000000000000000000000000feedface"
private const val SB_GROUP = "scrollback-group"

@OptIn(ExperimentalCoroutinesApi::class)
class GroupScrollbackCacheTest {
    @AfterTest
    fun cleanup() = SecureStorage.clearAllMessagesForAccount(SB_PUBKEY)

    private val slot = "wss://cache.relay|$SB_GROUP"

    private fun cached(id: String, createdAt: Long) = CachedMsg(id, slot, "p", createdAt, kind = 9, content = "c", tagsJson = "[]")

    @Test
    fun `scroll-back serves an older page from the cache without hitting the relay`() = runTest {
        val scope = TestScope(testScheduler)
        val cache = InMemoryCacheStore()
        val manager = GroupManager(connectionManager = ConnectionManager(scope), scope = scope, cacheStore = cache)
        manager.setCurrentPubkey(SB_PUBKEY)
        manager.setGroupRelayHint(SB_GROUP, "wss://cache.relay")

        // The cache holds the full history m1..m10 under the relay-scoped slot; memory only
        // has the recent tail m8..m10 (as if older messages were evicted or never hydrated).
        cache.upsertMessages(SB_PUBKEY, slot, (1L..10L).map { cached("m$it", it) })
        SecureStorage.saveMessagesForGroup(
            SB_PUBKEY,
            SB_GROUP,
            (8..10).joinToString(prefix = "[", postfix = "]") {
                """{"id":"m$it","pubkey":"p","content":"c","createdAt":$it,"kind":9,"tags":[]}"""
            },
        )
        manager.loadMessagesFromStorage(SB_GROUP)
        assertEquals(listOf("m8", "m9", "m10"), manager.getMessagesForGroup(SB_GROUP).map { it.id })

        // No relay client exists; the older page must come from disk.
        val loaded = manager.loadMoreMessages(SB_GROUP)
        assertTrue(loaded, "disk-first scroll-back should report a loaded page")
        assertEquals(
            listOf("m1", "m2", "m3", "m4", "m5", "m6", "m7", "m8", "m9", "m10"),
            manager.getMessagesForGroup(SB_GROUP).map { it.id },
        )

        // History is exhausted (everything in memory is also on disk): the next scroll falls
        // through to the relay path, which returns false with no client.
        assertFalse(manager.loadMoreMessages(SB_GROUP), "exhausted cache must fall through to the relay")

        scope.cancel()
    }
}
