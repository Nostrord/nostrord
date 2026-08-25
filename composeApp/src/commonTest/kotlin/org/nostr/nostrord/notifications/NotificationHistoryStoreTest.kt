package org.nostr.nostrord.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationHistoryStoreTest {
    // initialize(null) keeps the store off SecureStorage: persist() is a no-op without an account,
    // so these exercise the in-memory dedup without touching a platform backend.
    private fun store() = NotificationHistoryStore().apply { initialize(null) }

    private fun entry(id: String, rootId: String? = "root1") = NotificationEntry(
        id = id,
        type = NotificationType.REPLY,
        groupId = "g1",
        relayUrl = "wss://relay.example",
        actorPubkey = "pk_other",
        createdAt = 1_700_000_000,
        preview = "hey",
        threadRootId = rootId,
    )

    @Test
    fun `the same event announces once`() {
        val store = store()
        store.add(entry("e1"))
        store.add(entry("e1"))
        assertEquals(1, store.entries.value.size)
    }

    @Test
    fun `an event re-served after falling off the feed does not come back`() {
        val store = store()
        store.add(entry("old"))
        // Push it past the feed cap: the relay re-serving it must still be recognised as a repeat.
        repeat(60) { store.add(entry("e$it")) }
        assertTrue(store.entries.value.none { it.id == "old" }, "setup: the entry should have aged out")

        store.add(entry("old"))
        assertTrue(store.entries.value.none { it.id == "old" })
    }

    @Test
    fun `a read entry re-served stays gone instead of returning unread`() {
        val store = store()
        store.add(entry("e1"))
        store.markReadForThread("root1")
        assertTrue(store.entries.value.single().read)

        store.add(entry("e1"))
        assertTrue(store.entries.value.single().read)
        assertEquals(1, store.entries.value.size)
    }

    @Test
    fun `deleting a thread root drops the notifications for its replies`() {
        val store = store()
        store.add(entry("reply1"))
        store.add(entry("reply2"))
        store.add(entry("other", rootId = "root2"))

        store.removeForEvents(setOf("root1"))

        assertEquals(listOf("other"), store.entries.value.map { it.id })
    }

    @Test
    fun `deleting an event drops its own notification`() {
        val store = store()
        store.add(entry("e1"))
        store.removeForEvents(setOf("e1"))
        assertTrue(store.entries.value.isEmpty())
    }

    @Test
    fun `a removed event re-served does not come back`() {
        val store = store()
        store.add(entry("e1"))
        store.removeForEvents(setOf("e1"))
        store.add(entry("e1"))
        assertTrue(store.entries.value.isEmpty())
    }

    @Test
    fun `clearing the feed does not re-open the gate`() {
        val store = store()
        store.add(entry("e1"))
        store.clearHistory()
        store.add(entry("e1"))
        assertTrue(store.entries.value.isEmpty())
    }

    @Test
    fun `the announced set is bounded`() {
        val store = store()
        repeat(NotificationHistoryStore.MAX_ANNOUNCED + 10) { store.add(entry("e$it")) }
        // The oldest ids are forgotten, so that event may announce again; the newest are still held.
        store.add(entry("e0"))
        assertEquals("e0", store.entries.value.first().id)

        val newest = "e${NotificationHistoryStore.MAX_ANNOUNCED + 9}"
        store.add(entry(newest))
        assertEquals(1, store.entries.value.count { it.id == newest })
    }
}
