package org.nostr.nostrord.ui

import org.nostr.nostrord.utils.groupKey
import kotlin.test.Test
import kotlin.test.assertEquals

/** Drafts are per (relay, group): the same group id on two relays composes independently. */
class MessageDraftStoreTest {

    private val wisp = groupKey("wss://chat.wisp.talk", "nostrord")
    private val oxchat = groupKey("wss://groups.0xchat.com", "nostrord")

    @Test
    fun `a draft typed in one relay's group does not surface in its twin`() {
        val store = MessageDraftStore()

        store.setText(wisp, "half-typed on wisp")

        assertEquals("half-typed on wisp", store.get(wisp).text)
        assertEquals("", store.get(oxchat).text)
    }

    @Test
    fun `clearing one leaves the other standing`() {
        val store = MessageDraftStore()
        store.setText(wisp, "on wisp")
        store.setText(oxchat, "on 0xchat")

        store.clear(wisp)

        assertEquals("", store.get(wisp).text)
        assertEquals("on 0xchat", store.get(oxchat).text)
    }

    @Test
    fun `a relay url differing only in case or trailing slash is the same draft`() {
        val store = MessageDraftStore()
        store.setText(groupKey("wss://Chat.Wisp.Talk/", "nostrord"), "same slot")

        assertEquals("same slot", store.get(wisp).text)
    }
}
