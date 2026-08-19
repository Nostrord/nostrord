package org.nostr.nostrord.network

import kotlin.test.Test
import kotlin.test.assertEquals

class RelayRejectionTest {
    @Test
    fun `duplicate means the relay already has it`() {
        assertEquals(RelayRejection.AlreadyStored, classifyRejection("duplicate: already have this event"))
        assertEquals(RelayRejection.AlreadyStored, classifyRejection("Duplicate:"))
    }

    @Test
    fun `refusals that clear on their own are transient`() {
        assertEquals(RelayRejection.Transient, classifyRejection("auth-required: we can't serve DMs to unauthed users"))
        assertEquals(RelayRejection.Transient, classifyRejection("rate-limited: you are noting too much"))
        assertEquals(RelayRejection.Transient, classifyRejection("error: could not connect to the database"))
    }

    @Test
    fun `everything else is the user's problem`() {
        assertEquals(RelayRejection.Permanent, classifyRejection("blocked: pubkey not allowed"))
        assertEquals(RelayRejection.Permanent, classifyRejection("invalid: event id does not match"))
        assertEquals(RelayRejection.Permanent, classifyRejection("pow: difficulty 25 is less than 30"))
        assertEquals(RelayRejection.Permanent, classifyRejection("group doesn't exist"))
        // An unprefixed refusal says nothing hopeful, so it must not be mistaken for transient.
        assertEquals(RelayRejection.Permanent, classifyRejection(""))
    }
}
