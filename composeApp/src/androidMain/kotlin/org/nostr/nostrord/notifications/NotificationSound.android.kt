package org.nostr.nostrord.notifications

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import org.nostr.nostrord.R

/**
 * Must be called once from Application.onCreate() before [playNotificationSound].
 */
object AndroidNotificationSoundInit {
    @SuppressLint("StaticFieldLeak") // Application context is safe to hold statically
    internal var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }
}

/**
 * The app plays its own sound instead of posting a system notification, so no
 * NotificationChannel policy applies to it. Silence has to be enforced here:
 * Do Not Disturb and silent/vibrate ringer only mute the ring and notification
 * streams, and only USAGE_NOTIFICATION routes there — the MediaPlayer default
 * (USAGE_MEDIA) is audible through both.
 */
private fun shouldStayQuiet(context: Context): Boolean {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    // Match the DND filters explicitly: INTERRUPTION_FILTER_UNKNOWN also fails the
    // "== ALL" test, and treating it as DND would mute the sound for good.
    val dnd = when (nm?.currentInterruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_PRIORITY,
        NotificationManager.INTERRUPTION_FILTER_ALARMS,
        NotificationManager.INTERRUPTION_FILTER_NONE,
        -> true
        else -> false
    }
    if (dnd) return true
    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    return am != null && am.ringerMode != AudioManager.RINGER_MODE_NORMAL
}

actual fun playNotificationSound() {
    val context = AndroidNotificationSoundInit.appContext ?: return
    try {
        if (shouldStayQuiet(context)) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val sessionId = audioManager?.generateAudioSessionId() ?: AudioManager.AUDIO_SESSION_ID_GENERATE
        val player = MediaPlayer.create(context, R.raw.message_incoming, attributes, sessionId) ?: return
        player.setVolume(0.6f, 0.6f)
        player.setOnCompletionListener { it.release() }
        player.setOnErrorListener { mp, _, _ ->
            mp.release()
            true
        }
        player.start()
    } catch (_: Throwable) {
        // Audio focus conflict or codec failure — silent fallback.
    }
}
