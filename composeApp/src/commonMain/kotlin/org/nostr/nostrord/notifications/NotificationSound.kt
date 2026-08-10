package org.nostr.nostrord.notifications

import kotlinx.coroutines.flow.StateFlow

/**
 * Play the notification chime. Web uses HTML5 Audio, JVM uses JLayer, Android
 * uses MediaPlayer; iOS is a no-op for now. Browsers may swallow the first call
 * until the user has interacted with the page.
 */
expect fun playNotificationSound()

/** Platform-level reason the chime stays silent, whatever the app's own setting says. */
enum class SoundSuppression {
    DoNotDisturb,
    SilentRinger,
    ;

    /** Copy shown next to the disabled test-sound control. */
    val notice: String
        get() = when (this) {
            DoNotDisturb ->
                "Do Not Disturb is on, so the system silences the chime. " +
                    "Turn it off to hear notification sounds."
            SilentRinger ->
                "The ringer is set to silent or vibrate, so the system silences the chime. " +
                    "Switch it back to normal to hear notification sounds."
        }
}

/**
 * Current suppression reason, or null when the chime would be audible. Only Android
 * reports one: the other platforms have no equivalent system-wide mute that applies
 * to in-process playback.
 */
expect val notificationSoundSuppression: StateFlow<SoundSuppression?>
