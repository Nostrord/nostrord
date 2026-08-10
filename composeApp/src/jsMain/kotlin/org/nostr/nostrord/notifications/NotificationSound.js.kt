package org.nostr.nostrord.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Browsers expose no system-wide mute state to a page.
actual val notificationSoundSuppression: StateFlow<SoundSuppression?> = MutableStateFlow(null)

actual fun playNotificationSound() {
    try {
        val audio = js("new Audio('message-incoming.mp3')")
        audio.volume = 0.6
        // play() returns a Promise that REJECTS when autoplay is blocked (no user
        // gesture yet). A try/catch can't see that rejection, so swallow it here or
        // it surfaces as an uncaught-in-promise runtime error.
        val result = audio.play()
        if (result != null && result.then != null) {
            result.catch({ _: dynamic -> null })
        }
    } catch (_: Throwable) {
        // Older browsers where play() throws synchronously.
    }
}
