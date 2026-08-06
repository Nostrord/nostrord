package org.nostr.nostrord.network.managers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.nostr.Nip4e
import org.nostr.nostrord.storage.Nip4eStoredKey
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.loadNip4eAnnouncedAtFor
import org.nostr.nostrord.storage.loadNip4eAnnouncedFor
import org.nostr.nostrord.storage.loadNip4eKeysFor
import org.nostr.nostrord.storage.saveNip4eAnnouncedAtFor
import org.nostr.nostrord.storage.saveNip4eAnnouncedFor
import org.nostr.nostrord.storage.saveNip4eKeysFor
import org.nostr.nostrord.utils.epochSeconds

/**
 * Owns this account's NIP-4e encryption keys: the keys we hold locally, and whether the current
 * one is advertised in our kind:10044.
 *
 * The point of holding a key is that inbound DMs addressed to it decrypt in-process, so a remote
 * signer leaves the read path entirely. Accounts with a local key already decrypt in-process and
 * gain nothing, hence [State.Unavailable].
 *
 * Keys are a LIST, newest first. Rotation (ours, or done by another device on this account)
 * retires a key without deleting it, because messages already addressed to it can only ever be
 * opened with it. Disabling only stops advertising; it never drops a key.
 */
class DmEncryptionManager {
    sealed interface State {
        /** Signing is local, so there is nothing to offload. The UI hides the whole section. */
        data object Unavailable : State

        /** No key held and none announced. */
        data object Disabled : State

        /** We hold [encPubkey] and it is the announced one: senders address it, we decrypt locally. */
        data class Active(val encPubkey: String) : State

        /** Key kept from an earlier enable, currently un-advertised. History still opens. */
        data class HeldNotAnnounced(val encPubkey: String) : State

        /** Another device announced [encPubkey] for this account; we cannot read until we hold it. */
        data class AnnouncedElsewhere(val encPubkey: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Unavailable)
    val state: StateFlow<State> = _state.asStateFlow()

    private var accountPubkey: String = ""
    private var remoteSigner: Boolean = false
    private var keys: List<Nip4eStoredKey> = emptyList()
    private var announced: Boolean = false
    private var announcedAt: Long = 0L

    /** Key announced by another device that we do not hold; drives [State.AnnouncedElsewhere]. */
    private var foreignAnnouncedKey: String? = null

    /** Load the account's keys. [remoteSigner] gates the whole feature (see [State.Unavailable]). */
    fun loadFor(pubkey: String, remoteSigner: Boolean) {
        accountPubkey = pubkey
        this.remoteSigner = remoteSigner
        keys = prune(SecureStorage.loadNip4eKeysFor(pubkey))
        announced = SecureStorage.loadNip4eAnnouncedFor(pubkey)
        announcedAt = SecureStorage.loadNip4eAnnouncedAtFor(pubkey)
        foreignAnnouncedKey = null
        recompute()
    }

    /** All held keys, current first. Receive tries each, so a retired key still opens its history. */
    fun heldKeys(): List<KeyPair> = keys.mapNotNull { it.keyPair() }

    fun currentEncPubkeyOrNull(): String? = keys.firstOrNull()?.keyPair()?.publicKeyHex

    /**
     * Generate and hold a new key, making it current and retiring the previous one. Returns its
     * pubkey. Retired keys are pruned here, so rotating is also what bounds the number of live
     * decryption capabilities sitting on this device.
     */
    fun generateKey(): String {
        val fresh = KeyPair.generate()
        val now = epochSeconds()
        val retired = keys.map { if (it.retiredAt == 0L) it.copy(retiredAt = now) else it }
        keys = prune(listOf(Nip4eStoredKey(fresh.privateKeyHex)) + retired.filter { it.privateKeyHex != fresh.privateKeyHex })
        persistKeys()
        recompute()
        return fresh.publicKeyHex
    }

    /**
     * Hold the key exported from another device. Rejected unless it derives to the key this
     * account actually announced, so a typo cannot silently leave us unable to read.
     */
    fun importKey(privateKeyHex: String): Boolean {
        val kp = runCatching { KeyPair.fromPrivateKeyHex(privateKeyHex.trim()) }.getOrNull() ?: return false
        val expected = foreignAnnouncedKey
        if (expected != null && kp.publicKeyHex != expected) return false
        val now = epochSeconds()
        val retired = keys.map { if (it.retiredAt == 0L) it.copy(retiredAt = now) else it }
        keys = prune(listOf(Nip4eStoredKey(kp.privateKeyHex)) + retired.filter { it.privateKeyHex != kp.privateKeyHex })
        persistKeys()
        if (expected != null) {
            foreignAnnouncedKey = null
            announced = true
            SecureStorage.saveNip4eAnnouncedFor(accountPubkey, true)
        }
        recompute()
        return true
    }

    /** The current private key, for moving it to another device. */
    fun exportKey(): String? = keys.firstOrNull()?.privateKeyHex

    /** Record whether our current key is advertised. Never touches the key list. */
    fun setAnnounced(value: Boolean) {
        announced = value
        SecureStorage.saveNip4eAnnouncedFor(accountPubkey, value)
        // First announcement only: it marks where NIP-4e addressing begins, which is the cutoff
        // the self-archive works back from. Re-enabling later must not move it forward.
        if (value && announcedAt <= 0L) {
            announcedAt = epochSeconds()
            SecureStorage.saveNip4eAnnouncedAtFor(accountPubkey, announcedAt)
        }
        if (value) foreignAnnouncedKey = null
        recompute()
    }

    /** When NIP-4e addressing began for this account; 0 when it never has. */
    fun announcedAt(): Long = announcedAt

    /**
     * Reconcile against our own kind:10044 as relays actually have it: another device may have
     * rotated to a key we do not hold, or withdrawn ours entirely.
     */
    fun ingestOwnAnnouncement(event: Event) {
        if (event.pubkey != accountPubkey) return
        val announcedKey = Nip4e.encryptionKeyFrom(event)
        when {
            announcedKey == null -> {
                // Withdrawn (possibly by another device). Keys stay; we just stop being addressed.
                foreignAnnouncedKey = null
                if (announced) setAnnounced(false) else recompute()
            }
            keys.any { it.keyPair()?.publicKeyHex == announcedKey } -> {
                foreignAnnouncedKey = null
                // Make the announced key current so sends tag the key senders are addressing.
                keys = keys.sortedByDescending { it.keyPair()?.publicKeyHex == announcedKey }
                persistKeys()
                if (!announced) setAnnounced(true) else recompute()
            }
            else -> {
                foreignAnnouncedKey = announcedKey
                if (announced) {
                    announced = false
                    SecureStorage.saveNip4eAnnouncedFor(accountPubkey, false)
                }
                recompute()
            }
        }
    }

    /** Drop in-memory state on account switch. Storage is untouched. */
    fun clear() {
        accountPubkey = ""
        remoteSigner = false
        keys = emptyList()
        announced = false
        announcedAt = 0L
        foreignAnnouncedKey = null
        _state.value = State.Unavailable
    }

    private fun persistKeys() {
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

    private fun recompute() {
        val current = currentEncPubkeyOrNull()
        _state.value =
            when {
                !remoteSigner -> State.Unavailable
                foreignAnnouncedKey != null -> State.AnnouncedElsewhere(foreignAnnouncedKey!!)
                current == null -> State.Disabled
                announced -> State.Active(current)
                else -> State.HeldNotAnnounced(current)
            }
    }

    companion object {
        /** Matches Jumble so a key rotated on either client behaves the same. */
        const val RETENTION_SECONDS = 90L * 24 * 60 * 60

        const val MAX_RETIRED_KEYS = 10
    }
}
