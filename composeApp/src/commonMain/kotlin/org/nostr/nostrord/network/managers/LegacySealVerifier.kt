package org.nostr.nostrord.network.managers

import kotlinx.coroutines.delay

/**
 * Authenticates a NIP-4e legacy seal, where the seal is signed by the sender's encryption key and
 * so proves nothing on its own: the seal's author must be the encryption key that the rumor's
 * author announced in their kind:10044.
 *
 * A cached announcement that disagrees is not a rejection: the author may have rotated since we
 * last looked, so one fetch is forced before deciding. An unresolved announcement returns false,
 * which leaves the wrap unhandled for the pipeline to retry rather than deciding on incomplete
 * information. (Jumble instead persists a monotonic verified flag and renders unverified messages;
 * deferring is stricter and needs no per-message UI.)
 */
class LegacySealVerifier(
    private val fetchWaitMs: Long,
    private val announcedKeyFor: (authorPubkey: String) -> String?,
    private val requestAnnouncement: suspend (authorPubkey: String) -> Unit,
) {
    suspend fun verify(authorPubkey: String, sealPubkey: String): Boolean {
        if (announcedKeyFor(authorPubkey) == sealPubkey) return true
        // Unknown or stale: exactly one fetch, then decide on what we have.
        requestAnnouncement(authorPubkey)
        delay(fetchWaitMs)
        return announcedKeyFor(authorPubkey) == sealPubkey
    }
}
