package org.nostr.nostrord.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuoteFetchSubIdTest {
    @Test
    fun `the h-scoped by-id fetch is recognised as a quote fetch`() {
        // "eh_" does not start with "e_": a prefix test that only covers "e_" drops every reply to
        // requestGroupMessageById, which is the only lookup a strict NIP-29 relay answers.
        assertTrue(isQuoteFetchSubId("eh_1787680017123"))
        assertTrue(isQuoteFetchSubId("e_1787680017123"))
        assertTrue(isQuoteFetchSubId("event_abc"))
    }

    @Test
    fun `other subscriptions are left to their own handlers`() {
        listOf("mux_chat_1", "gapfill_1", "a_1", "addr_1", "reactions_1", "threadfocus_1", "padd_one_1")
            .forEach { assertFalse(isQuoteFetchSubId(it), "$it must not be treated as a quote fetch") }
    }
}
