package org.nostr.nostrord.ui.keyboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VirtualKeyboardPolicyTest {
    // Portrait phone: 800px closed, 420px with the keyboard up, 744px with the URL bar shown.

    @Test
    fun keyboardOpenDetectedAfterFocusShrink() {
        val baseline = VirtualKeyboardPolicy.rebaseline(800.0, 420.0, editableFocused = true)
        assertEquals(800.0, baseline)
        assertTrue(VirtualKeyboardPolicy.isOpen(baseline, 420.0, editableFocused = true))
    }

    @Test
    fun backButtonCloseKeepsFocusButReadsClosed() {
        // Android back dismisses the keyboard, composer stays focused, viewport returns.
        val baseline = VirtualKeyboardPolicy.rebaseline(800.0, 800.0, editableFocused = true)
        assertEquals(800.0, baseline)
        assertFalse(VirtualKeyboardPolicy.isOpen(baseline, 800.0, editableFocused = true))
    }

    @Test
    fun urlBarShiftNeverReadsAsKeyboard() {
        assertFalse(VirtualKeyboardPolicy.isOpen(800.0, 744.0, editableFocused = true))
    }

    @Test
    fun noEditableFocusIsNeverOpen() {
        assertFalse(VirtualKeyboardPolicy.isOpen(800.0, 420.0, editableFocused = false))
    }

    @Test
    fun blurResizeRebaselines() {
        // Keyboard closes on blur; the unfocused resize re-seeds the baseline.
        assertEquals(800.0, VirtualKeyboardPolicy.rebaseline(420.0, 800.0, editableFocused = false))
    }

    @Test
    fun viewportGrowthRebaselinesEvenWhileFocused() {
        // Landscape (400 closed) to portrait while the composer stays focused.
        assertEquals(800.0, VirtualKeyboardPolicy.rebaseline(400.0, 800.0, editableFocused = true))
    }

    @Test
    fun rotationWhileTypingStillReadsOpen() {
        // Portrait baseline 800, rotate with the keyboard up: landscape open height 200.
        val baseline = VirtualKeyboardPolicy.rebaseline(800.0, 200.0, editableFocused = true)
        assertEquals(800.0, baseline)
        assertTrue(VirtualKeyboardPolicy.isOpen(baseline, 200.0, editableFocused = true))
    }

    @Test
    fun tapKeepsFocusOnlyWhenKeyboardUpOrNoTouch() {
        // Keyboard up on touch: keep focus so it stays up (#199).
        assertTrue(VirtualKeyboardPolicy.keepComposerFocusOnTap(keyboardOpen = true, touchDevice = true))
        // Keyboard closed on touch: let the blur run or Chrome re-summons it.
        assertFalse(VirtualKeyboardPolicy.keepComposerFocusOnTap(keyboardOpen = false, touchDevice = true))
        // Desktop: no on-screen keyboard, keeping focus is always safe.
        assertTrue(VirtualKeyboardPolicy.keepComposerFocusOnTap(keyboardOpen = false, touchDevice = false))
        assertTrue(VirtualKeyboardPolicy.keepComposerFocusOnTap(keyboardOpen = true, touchDevice = false))
    }
}
