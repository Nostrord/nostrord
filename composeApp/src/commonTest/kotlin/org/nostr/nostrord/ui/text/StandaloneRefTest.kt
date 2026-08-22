package org.nostr.nostrord.ui.text

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StandaloneRefTest {
    private val npub = "npub1f27g79lrpey73wtqa2pprn7vv3yveyytws08lxqe7pn0yuj8ppyqyk9swu"

    @Test
    fun aloneInTheMessageIsStandalone() {
        assertTrue(StandaloneRef.isStandalone("nostr:$npub", "nostr:$npub"))
    }

    @Test
    fun aloneOnItsOwnLineIsStandalone() {
        assertTrue(StandaloneRef.isStandalone("You've been added to X\nnostr:$npub", "nostr:$npub"))
    }

    @Test
    fun mixedWithTextOnTheLineIsNot() {
        assertFalse(StandaloneRef.isStandalone("hey nostr:$npub how are you", "nostr:$npub"))
    }

    @Test
    fun bareBech32MatchesThePrefixedToken() {
        assertTrue(StandaloneRef.isStandalone(npub, "nostr:$npub"))
        assertTrue(StandaloneRef.isStandalone("nostr:$npub", npub))
    }

    @Test
    fun anotherReferenceOnItsOwnLineDoesNotCount() {
        assertFalse(StandaloneRef.isStandalone("hey nostr:$npub\nnostr:npub1other", "nostr:$npub"))
    }
}
