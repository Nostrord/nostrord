package org.nostr.nostrord.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Nothing to suppress while playback itself is a no-op.
actual val notificationSoundSuppression: StateFlow<SoundSuppression?> = MutableStateFlow(null)

actual fun playNotificationSound() { /* no-op */ }
