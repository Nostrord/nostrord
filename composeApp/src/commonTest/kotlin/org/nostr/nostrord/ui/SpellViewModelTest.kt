package org.nostr.nostrord.ui

import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.network.FakeNostrRepository
import org.nostr.nostrord.nostr.PRESET_ID_PREFIX
import org.nostr.nostrord.nostr.SpellPresets
import org.nostr.nostrord.ui.navigation.SpellRoute
import org.nostr.nostrord.ui.navigation.parseHashRoute
import org.nostr.nostrord.ui.navigation.persistedRouteHash
import org.nostr.nostrord.ui.navigation.restoredRoute
import org.nostr.nostrord.ui.navigation.routeKey
import org.nostr.nostrord.ui.navigation.toHash
import org.nostr.nostrord.ui.screens.spell.SpellViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpellRouteTest {
    @Test
    fun roundTripsThroughTheHash() {
        val route = SpellRoute("${PRESET_ID_PREFIX}mentions")
        assertEquals("#/s/preset%3Amentions", route.toHash())
        assertEquals(route, parseHashRoute(route.toHash()))
    }

    @Test
    fun everyRailPresetHasAReachableHash() {
        SpellPresets.forRail().forEach { spell ->
            val route = SpellRoute(spell.id)
            assertEquals(route, parseHashRoute(route.toHash()), spell.id)
            assertNotNull(SpellPresets.railSpellById(route.spellId), spell.id)
        }
    }

    @Test
    fun rejectsMalformedSpellHashes() {
        assertNull(parseHashRoute("#/s/"))
        // A second segment would be a different page shape, not a spell.
        assertNull(parseHashRoute("#/s/a/b"))
    }

    @Test
    fun routeKeyDoesNotCollideWithOtherPages() {
        val key = routeKey(SpellRoute("preset:mentions"))
        assertEquals("s:preset:mentions", key)
        assertTrue(key != routeKey(null))
    }

    @Test
    fun survivesAppRestart() {
        val route = SpellRoute("preset:my-notes")
        // A rail destination must reopen on launch, exactly like a group does.
        assertEquals(route, restoredRoute(persistedRouteHash(route)))
    }
}

class SpellViewModelTest {
    private fun vm(
        repo: FakeNostrRepository,
        id: String,
    ) = SpellViewModel(repo = repo, spellId = id)

    @Test
    fun exposesThePresetItNames() = runTest {
        val model = vm(FakeNostrRepository(), "${PRESET_ID_PREFIX}mentions")
        assertNotNull(model.spell)
        assertEquals("Mentions", model.title)
        assertNull(model.error.value)
    }

    @Test
    fun reportsAnUnknownSpellInsteadOfRenderingEmpty() = runTest {
        // A link to a custom spell this device has never synced must say so, not look like a
        // feed with no posts.
        val model = vm(FakeNostrRepository(), "cafe".repeat(16))
        assertNull(model.spell)
        assertNotNull(model.error.value)
        assertTrue(model.events.value.isEmpty())
    }

    @Test
    fun startsEmptyBeforeAnyEventArrives() = runTest {
        val model = vm(FakeNostrRepository(), "${PRESET_ID_PREFIX}contacts-notes")
        assertTrue(model.events.value.isEmpty())
    }
}
