package org.nostr.nostrord.ui.keyboard

/**
 * Platform-agnostic rules for on-screen keyboard detection and keyboard-neutral
 * taps. The web tracker (webMain `web/bridge/VirtualKeyboard.kt`) feeds viewport
 * measurements through these rules; keeping them here makes them unit-testable
 * in commonTest (mobile IME behavior itself cannot run in a JVM test).
 *
 * Detection model: with `interactive-widget=resizes-content` (index.html) the
 * keyboard shrinks BOTH the layout and visual viewports, so "open" cannot be
 * read as `innerHeight - visualViewport.height`. The baseline is the viewport
 * height last seen with no editable element focused (the keyboard cannot be up
 * without one); open = a keyboard-sized drop below it.
 */
object VirtualKeyboardPolicy {
    /** Every on-screen keyboard is taller than this; URL-bar show/hide is well under it. */
    const val MIN_KEYBOARD_PX = 150.0

    /**
     * New baseline after a viewport resize. No editable focus = keyboard closed
     * by definition; a viewport that GREW proves the old baseline stale
     * (rotation, split-screen, URL bar hide).
     */
    fun rebaseline(baseline: Double, height: Double, editableFocused: Boolean): Double = if (!editableFocused || height > baseline) height else baseline

    /** True when the viewport sits a keyboard's height below the closed baseline. */
    fun isOpen(baseline: Double, height: Double, editableFocused: Boolean): Boolean = editableFocused && baseline - height > MIN_KEYBOARD_PX

    /**
     * Whether a chat control's tap (jump pill) should preventDefault its
     * mousedown to keep composer focus. Chrome re-summons the keyboard when a
     * tap ends with an editable element still focused, so focus is kept only
     * when the keyboard is already up (then it stays up, #199) or the device
     * has no on-screen keyboard at all.
     */
    fun keepComposerFocusOnTap(keyboardOpen: Boolean, touchDevice: Boolean): Boolean = keyboardOpen || !touchDevice
}
