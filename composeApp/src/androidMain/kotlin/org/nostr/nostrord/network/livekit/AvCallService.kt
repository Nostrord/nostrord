package org.nostr.nostrord.network.livekit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.nostr.nostrord.MainActivity
import org.nostr.nostrord.R

/**
 * Foreground service held while a NIP-29 AV space is capturing.
 *
 * Android stops microphone and camera capture for a backgrounded app, so a room without this
 * goes silent the moment the user leaves the screen. It runs only while a track is actually
 * on: the `microphone` / `camera` service types require the matching runtime permission, which
 * is granted exactly then, and a listener needs nothing beyond ordinary playback.
 */
class AvCallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mic = intent?.getBooleanExtra(EXTRA_MIC, false) == true
        val camera = intent?.getBooleanExtra(EXTRA_CAMERA, false) == true
        var type = 0
        if (mic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (camera) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (type == 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        // Not sticky: a restarted service would have no room behind it.
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Voice rooms", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice room")
            .setContentText("You are live in a voice room")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "av_space_call"
        private const val NOTIFICATION_ID = 4029
        private const val EXTRA_MIC = "mic"
        private const val EXTRA_CAMERA = "camera"

        /** Start, retype, or stop the service to match what is currently capturing. */
        fun update(context: Context, mic: Boolean, camera: Boolean) {
            val intent = Intent(context, AvCallService::class.java)
            if (!mic && !camera) {
                context.stopService(intent)
                return
            }
            intent.putExtra(EXTRA_MIC, mic)
            intent.putExtra(EXTRA_CAMERA, camera)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) = update(context, mic = false, camera = false)
    }
}
