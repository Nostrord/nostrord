package org.nostr.nostrord.notifications

/**
 * Separates realtime events from catch-up backlog for notification purposes.
 *
 * Armed at the instant a session becomes active. An event whose `createdAt`
 * predates that instant happened while the user was away: it belongs in the
 * in-app feed but must not play a sound or fire an OS popup.
 *
 * Disarmed means "no active session" and nothing is realtime. That is the
 * initial state, so a subscription that starts streaming before the cutoff is
 * armed stays quiet instead of announcing an entire relay backlog.
 */
class RealtimeCutoff {
    @kotlin.concurrent.Volatile
    private var armedAtSeconds: Long? = null

    fun arm(nowSeconds: Long) {
        armedAtSeconds = nowSeconds
    }

    fun disarm() {
        armedAtSeconds = null
    }

    fun isRealtime(eventCreatedAt: Long): Boolean {
        val armedAt = armedAtSeconds ?: return false
        return eventCreatedAt >= armedAt
    }
}
