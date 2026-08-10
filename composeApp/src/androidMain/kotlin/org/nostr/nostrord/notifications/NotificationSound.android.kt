package org.nostr.nostrord.notifications

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.nostr.nostrord.R

private val suppression = MutableStateFlow<SoundSuppression?>(null)

actual val notificationSoundSuppression: StateFlow<SoundSuppression?> = suppression.asStateFlow()

/**
 * Must be called once from Application.onCreate() before [playNotificationSound].
 */
object AndroidNotificationSoundInit {
    @SuppressLint("StaticFieldLeak") // Application context is safe to hold statically
    internal var appContext: Context? = null

    // Both broadcasts are system-protected, so the receiver stays not-exported. It lives
    // for the process lifetime: the suppression state has to stay live while Settings is
    // open, and the notification shade doesn't pause the activity.
    private val systemStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            suppression.value = currentSuppression(context.applicationContext)
        }
    }

    fun initialize(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        suppression.value = currentSuppression(app)
        val filter = IntentFilter().apply {
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(app, systemStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}

/**
 * The app plays its own sound instead of posting a system notification, so no
 * NotificationChannel policy applies to it. Silence has to be enforced here:
 * Do Not Disturb and silent/vibrate ringer only mute the ring and notification
 * streams, and only USAGE_NOTIFICATION routes there — the MediaPlayer default
 * (USAGE_MEDIA) is audible through both.
 */
private fun currentSuppression(context: Context): SoundSuppression? {
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
    if (dnd) return SoundSuppression.DoNotDisturb
    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    if (am != null && am.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
        return SoundSuppression.SilentRinger
    }
    return null
}

actual fun playNotificationSound() {
    val context = AndroidNotificationSoundInit.appContext ?: return
    try {
        // Re-read rather than trust the broadcast state: a missed broadcast would
        // otherwise leak an audible chime under DND.
        val quiet = currentSuppression(context)
        suppression.value = quiet
        if (quiet != null) return
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
