package org.nostr.nostrord.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthIdentityTest {
    private fun client() = NostrGroupClient("wss://relay.example")

    @Test
    fun `a socket that never authenticated serves every account`() {
        // Public relays answer the same for everyone, so they must not be swept on a switch.
        assertFalse(client().isAuthedAsOther("alice"))
        assertFalse(client().isAuthedAsOther(null))
    }

    @Test
    fun `a socket authed as another account is stale for the new one`() {
        val c = client()
        c.notifyAuthCompleted("alice")
        assertTrue(c.isAuthedAsOther("bob"))
        // Logged out counts as "not alice" too: nothing may ride alice's socket afterwards.
        assertTrue(c.isAuthedAsOther(null))
        assertFalse(c.isAuthedAsOther("alice"))
    }
}
