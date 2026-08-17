package org.nostr.nostrord.utils

import org.nostr.nostrord.network.GroupMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GroupRefLookupTest {
    private fun group(id: String, name: String, picture: String?) = GroupMetadata(id = id, name = name, about = null, picture = picture, isPublic = true, isOpen = true)

    private val wisp = group("nostrord", "Nostrord", null)
    private val zeroX = group("nostrord", "Nostrord", "https://0xchat/pic.png")
    private val groupsByRelay =
        mapOf(
            "wss://chat.wisp.talk" to listOf(wisp),
            "wss://groups.0xchat.com" to listOf(zeroX),
        )

    @Test
    fun `hint picks that relay's copy, not a same-id group elsewhere`() {
        assertEquals(wisp, resolveGroupRef(groupsByRelay, "nostrord", "wss://chat.wisp.talk"))
        assertEquals(zeroX, resolveGroupRef(groupsByRelay, "nostrord", "wss://groups.0xchat.com"))
    }

    @Test
    fun `hint is normalized before matching`() {
        assertEquals(zeroX, resolveGroupRef(groupsByRelay, "nostrord", "wss://Groups.0xchat.com/"))
    }

    @Test
    fun `hinted relay without the group stays unresolved`() {
        assertNull(resolveGroupRef(groupsByRelay, "nostrord", "wss://relay.example"))
        assertNull(resolveGroupRef(groupsByRelay, "other", "wss://chat.wisp.talk"))
    }

    @Test
    fun `no hint falls back to the fallback list then any relay`() {
        val local = group("nostrord", "Local", null)
        assertEquals(local, resolveGroupRef(groupsByRelay, "nostrord", null, fallback = listOf(local)))
        assertEquals(wisp, resolveGroupRef(groupsByRelay, "nostrord", null))
        assertEquals(wisp, resolveGroupRef(groupsByRelay, "nostrord", "  "))
    }
}
