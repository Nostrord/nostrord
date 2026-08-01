package org.nostr.nostrord.network.managers

import kotlin.test.Test
import kotlin.test.assertEquals

class DeletionKindTest {
    @Test
    fun `author deleting own message uses kind 5`() {
        assertEquals(5, deletionKindFor(isOwnMessage = true, isAdmin = false))
    }

    @Test
    fun `admin deleting own message uses kind 5`() {
        // An admin is still the author of their own message, so it is a standard deletion.
        // A relay that refuses kind 5 gets the 9005 retry from deleteMessage instead.
        assertEquals(5, deletionKindFor(isOwnMessage = true, isAdmin = true))
    }

    @Test
    fun `admin deleting another member's message uses kind 9005`() {
        assertEquals(9005, deletionKindFor(isOwnMessage = false, isAdmin = true))
    }

    @Test
    fun `non-admin targeting another member's message stays on kind 5`() {
        // The relay rejects it, but the client must not forge an admin kind 9005.
        assertEquals(5, deletionKindFor(isOwnMessage = false, isAdmin = false))
    }
}
