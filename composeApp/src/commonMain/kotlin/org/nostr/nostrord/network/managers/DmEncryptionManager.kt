package org.nostr.nostrord.network.managers

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.storage.Nip4eAnnouncement
import org.nostr.nostrord.storage.Nip4eStoredKey
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.loadNip4eAnnouncedAtFor
import org.nostr.nostrord.storage.loadNip4eAnnouncedFor
import org.nostr.nostrord.storage.loadNip4eAnnouncementFor
import org.nostr.nostrord.storage.loadNip4eKeysFor
import org.nostr.nostrord.storage.saveNip4eAnnouncedAtFor
import org.nostr.nostrord.storage.saveNip4eAnnouncementFor
import org.nostr.nostrord.storage.saveNip4eKeysFor
import org.nostr.nostrord.utils.epochSeconds

/**
 * Owns this account's NIP-4e encryption keys: the keys held on this device, and the newest
 * kind:10044 known for the account.
 *
 * The point of holding a key is that inbound DMs addressed to it decrypt in-process, so a remote
 * signer leaves the read path entirely. Accounts with a local key already decrypt in-process and
 * gain nothing, hence [State.Unavailable].
 *
 * Keys are a LIST, newest first. Rotation (ours, or done by another device on this account)
 * retires a key without deleting it, because messages already addressed to it can only ever be
 * opened with it. Withdrawing the announcement never drops a key.
 *
 * State is DERIVED, never latched. It answers one question — does the key set contain the key the
 * newest announcement names — and answers it again on every change to either side. An earlier
 * version cached that answer in flags patched from six entry points, which let the account read as
 * "announced by another device" while holding the very key in question, permanently: the relay
 * echoes our own announcement back within a second, and the key was not adopted until the publish
 * returned up to eight seconds later.
 */
class DmEncryptionManager {
    sealed interface State {
        /** Signing is local, so there is nothing to offload. The UI hides the whole section. */
        data object Unavailable : State

        /** No key held and none announced. */
        data object Disabled : State

        /**
         * We hold [encPubkey] and it is the announced one: senders address it, we decrypt locally.
         * [confirmed] is false while the announcement is only known locally, so the UI can say it
         * is still being published rather than claiming senders already see it.
         */
        data class Active(val encPubkey: String, val confirmed: Boolean = true) : State

        /** Key kept from an earlier enable, currently un-advertised. History still opens. */
        data class HeldNotAnnounced(val encPubkey: String) : State

        /** The account announces [encPubkey], which is not on this device. */
        data class AnnouncedElsewhere(val encPubkey: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Unavailable)
    val state: StateFlow<State> = _state.asStateFlow()

    // Guards every field below. They are written from the relay pipeline (announcements arriving),
    // from the UI scope (enable/rotate/reset/import), and from the session collector (load/clear).
    private val lock = SynchronizedObject()

    private var accountPubkey: String = ""
    private var remoteSigner: Boolean = false
    private var keys: List<Nip4eStoredKey> = emptyList()

    /** The newest kind:10044 known for this account. Null encPubkey means the key was withdrawn. */
    private var announcement: Nip4eAnnouncement? = null
    private var announcedAt: Long = 0L

    /** Load the account's keys. [remoteSigner] gates the whole feature (see [State.Unavailable]). */
    fun loadFor(pubkey: String, remoteSigner: Boolean) {
        synchronized(lock) {
            accountPubkey = pubkey
            this.remoteSigner = remoteSigner
            keys = prune(SecureStorage.loadNip4eKeysFor(pubkey))
            announcement = SecureStorage.loadNip4eAnnouncementFor(pubkey) ?: legacyAnnouncement(pubkey)
            announcedAt = SecureStorage.loadNip4eAnnouncedAtFor(pubkey)
            recompute()
        }
    }

    /**
     * Slots written before announcements were persisted only recorded a boolean. Treat "was
     * announced, and we hold a key" as an announcement of that key at an unknown time, so any real
     * copy from a relay supersedes it.
     */
    private fun legacyAnnouncement(pubkey: String): Nip4eAnnouncement? {
        if (!SecureStorage.loadNip4eAnnouncedFor(pubkey)) return null
        val current = keys.firstOrNull()?.keyPair()?.publicKeyHex ?: return null
        return Nip4eAnnouncement(encPubkey = current, createdAt = 0L, confirmed = true)
    }

    /** All held keys, current first. Receive tries each, so a retired key still opens its history. */
    fun heldKeys(): List<KeyPair> = synchronized(lock) { keys.mapNotNull { it.keyPair() } }

    fun currentEncPubkeyOrNull(): String? = synchronized(lock) { keys.firstOrNull()?.keyPair()?.publicKeyHex }

    /**
     * Hold [kp], making it current and retiring the previous one. Returns its pubkey.
     *
     * Call this BEFORE publishing an announcement for it: the key is what cannot be recovered. An
     * unannounced key on the device is inert, while an announced key the device failed to keep
     * costs the account every message sent to it.
     */
    fun adoptKey(kp: KeyPair): String {
        synchronized(lock) {
            adopt(kp)
            recompute()
        }
        return kp.publicKeyHex
    }

    /** Generate, hold, and make current in one step. */
    fun generateKey(): String = adoptKey(KeyPair.generate())

    /**
     * Hold a key exported from another device. Rejected unless it derives to the key this account
     * announces, so a typo cannot silently leave us unable to read.
     */
    fun importKey(privateKeyHex: String): Boolean {
        val kp = runCatching { KeyPair.fromPrivateKeyHex(privateKeyHex.trim()) }.getOrNull() ?: return false
        return synchronized(lock) {
            val expected = announcement?.encPubkey
            if (expected != null && kp.publicKeyHex != expected) {
                false
            } else {
                adopt(kp)
                recompute()
                true
            }
        }
    }

    /** The current private key, for moving it to another device. */
    fun exportKey(): String? = synchronized(lock) { keys.firstOrNull()?.privateKeyHex }

    /**
     * Record the newest kind:10044 for this account: ours the moment it is signed, or a relay's
     * copy as it arrives. Only a strictly newer one counts, since relays that have not caught up
     * keep serving the previous version.
     *
     * Recording our own at signing time is what makes the echo of that same event a no-op instead
     * of a claim that someone else owns the key.
     */
    fun ingestAnnouncement(encPubkey: String?, createdAt: Long, fromRelay: Boolean) {
        synchronized(lock) {
            val known = announcement
            when {
                // Our own is authoritative the moment it is signed, including a second announcement
                // inside the same second (epochSeconds granularity), which a relay copy cannot be.
                known == null || createdAt > known.createdAt || (!fromRelay && createdAt >= known.createdAt) ->
                    announcement = Nip4eAnnouncement(encPubkey, createdAt, confirmed = fromRelay)
                // The relay serving back the announcement we just signed is the confirmation that
                // it landed; same event, so only the confirmed flag moves.
                fromRelay && !known.confirmed && createdAt == known.createdAt ->
                    announcement = known.copy(confirmed = true)
                else -> return@synchronized
            }
            SecureStorage.saveNip4eAnnouncementFor(accountPubkey, announcement)
            recompute()
        }
    }

    /** When NIP-4e addressing began for this account; 0 when it never has. */
    fun announcedAt(): Long = announcedAt

    /** Drop in-memory state on account switch. Storage is untouched. */
    fun clear() {
        synchronized(lock) {
            accountPubkey = ""
            remoteSigner = false
            keys = emptyList()
            announcement = null
            announcedAt = 0L
            _state.value = State.Unavailable
        }
    }

    /** Make [kp] the current key, stamping the previous one as retired. Caller holds [lock]. */
    private fun adopt(kp: KeyPair) {
        val now = epochSeconds()
        val retired = keys.map { if (it.retiredAt == 0L) it.copy(retiredAt = now) else it }
        keys = prune(listOf(Nip4eStoredKey(kp.privateKeyHex)) + retired.filter { it.privateKeyHex != kp.privateKeyHex })
        SecureStorage.saveNip4eKeysFor(accountPubkey, keys)
    }

    private fun Nip4eStoredKey.keyPair(): KeyPair? = runCatching { KeyPair.fromPrivateKeyHex(privateKeyHex) }.getOrNull()

    /**
     * Drop retired keys past the retention window or beyond the cap. The current key (retiredAt 0)
     * is never dropped. A pruned key's messages stop opening on this device, which is the point:
     * a rotated-away key is a decryption capability we no longer want lying around. Archive the
     * history to the new key before rotating if it needs to outlive the window.
     */
    private fun prune(all: List<Nip4eStoredKey>): List<Nip4eStoredKey> {
        val now = epochSeconds()
        val current = all.filter { it.retiredAt == 0L }
        val retired = all.filter { it.retiredAt != 0L }
            .filter { now - it.retiredAt < RETENTION_SECONDS }
            .sortedByDescending { it.retiredAt }
            .take(MAX_RETIRED_KEYS)
        return current + retired
    }

    /**
     * Derive the state from the two facts that decide it, and promote the announced key to current
     * so sends tag the key senders are actually addressing. Caller holds [lock].
     */
    private fun recompute() {
        val announcedKey = announcement?.encPubkey
        val holdsAnnounced = announcedKey != null && keys.any { it.keyPair()?.publicKeyHex == announcedKey }
        if (holdsAnnounced && keys.firstOrNull()?.keyPair()?.publicKeyHex != announcedKey) {
            keys = keys.sortedByDescending { it.keyPair()?.publicKeyHex == announcedKey }
            SecureStorage.saveNip4eKeysFor(accountPubkey, keys)
        }
        // First announcement only: it marks where NIP-4e addressing begins, which is the cutoff the
        // self-archive works back from. A later rotation must not move it forward.
        if (holdsAnnounced && announcedAt <= 0L) {
            announcedAt = epochSeconds()
            SecureStorage.saveNip4eAnnouncedAtFor(accountPubkey, announcedAt)
        }
        val current = keys.firstOrNull()?.keyPair()?.publicKeyHex
        _state.value =
            when {
                !remoteSigner -> State.Unavailable
                holdsAnnounced -> State.Active(announcedKey!!, confirmed = announcement?.confirmed == true)
                announcedKey != null -> State.AnnouncedElsewhere(announcedKey)
                current == null -> State.Disabled
                else -> State.HeldNotAnnounced(current)
            }
    }

    companion object {
        /** Matches Jumble so a key rotated on either client behaves the same. */
        const val RETENTION_SECONDS = 90L * 24 * 60 * 60

        const val MAX_RETIRED_KEYS = 10
    }
}
