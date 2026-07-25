package org.nostr.nostrord.web.bridge

import kotlinx.browser.document
import kotlinx.browser.window
import org.nostr.nostrord.ui.keyboard.VirtualKeyboardPolicy

/**
 * Tracks whether the mobile on-screen keyboard is up. Feeds viewport
 * measurements through [VirtualKeyboardPolicy] (commonMain, unit-tested), which
 * holds the detection model and its rationale. Desktop never records a
 * keyboard-sized drop, so isOpen stays false there.
 */
object VirtualKeyboard {
    private var baseline = 0.0
    private var current = 0.0
    private var started = false

    private fun editableFocused(): Boolean {
        val el = document.activeElement ?: return false
        val tag = el.tagName.lowercase()
        return tag == "textarea" || tag == "input" || el.asDynamic().isContentEditable == true
    }

    private fun measure(): Double {
        val vv = window.asDynamic().visualViewport
        return if (vv != null) vv.height as Double else window.innerHeight.toDouble()
    }

    /** Call once at boot, before any keyboard can be up, to seed the baseline. */
    fun start() {
        if (started) return
        started = true
        baseline = measure()
        current = baseline
        val onResize: () -> Unit = {
            current = measure()
            baseline = VirtualKeyboardPolicy.rebaseline(baseline, current, editableFocused())
        }
        window.asDynamic().visualViewport?.addEventListener("resize", { onResize() })
        window.addEventListener("resize", { onResize() })
    }

    /** True when the visual viewport sits a keyboard's height below the closed baseline. */
    val isOpen: Boolean
        get() = started && VirtualKeyboardPolicy.isOpen(baseline, current, editableFocused())
}
