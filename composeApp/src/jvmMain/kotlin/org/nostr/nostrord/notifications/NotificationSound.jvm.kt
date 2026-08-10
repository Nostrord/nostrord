package org.nostr.nostrord.notifications

import javazoom.jl.player.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedInputStream
import kotlin.concurrent.thread

// Desktop playback goes to the media mixer; no OS focus mode gates it.
actual val notificationSoundSuppression: StateFlow<SoundSuppression?> = MutableStateFlow(null)

// Anchors resource loading so we don't rely on anonymous-class `javaClass`.
private class ClassLoaderAnchor

actual fun playNotificationSound() {
    // Player.play() is blocking — run on a short-lived daemon thread so we
    // never block the caller (UnreadManager.onMessagesFlushed runs on the
    // coroutine that delivered the batch).
    thread(isDaemon = true, name = "NotificationSound") {
        try {
            val stream = ClassLoaderAnchor::class.java.getResourceAsStream("/message-incoming.mp3")
            if (stream == null) {
                System.err.println("[NotificationSound] /message-incoming.mp3 not on classpath")
                return@thread
            }
            BufferedInputStream(stream).use { input ->
                Player(input).use { it.play() }
            }
        } catch (t: Throwable) {
            System.err.println("[NotificationSound] play failed: ${t::class.simpleName}: ${t.message}")
        }
    }
}

private inline fun <T : Player, R> T.use(block: (T) -> R): R = try {
    block(this)
} finally {
    try {
        close()
    } catch (_: Throwable) {
    }
}
