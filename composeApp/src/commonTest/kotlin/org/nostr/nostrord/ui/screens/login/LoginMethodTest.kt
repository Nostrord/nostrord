package org.nostr.nostrord.ui.screens.login

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoginMethodTest {
    @Test
    fun everyMethodHasARowLabel() {
        // The list renders title over subtitle; a blank one would ship an empty row.
        LoginMethod.entries.forEach { method ->
            assertTrue(method.title.isNotBlank(), "${method.name} has no title")
            assertTrue(method.subtitle.isNotBlank(), "${method.name} has no subtitle")
        }
    }

    @Test
    fun keyAndBunkerAreOfferedOnEveryPlatform() {
        val methods = availableLoginMethods()
        assertEquals(LoginMethod.PrivateKey, methods.first())
        assertTrue(methods.contains(LoginMethod.Bunker))
        assertEquals(methods.distinct(), methods)
    }
}
