package org.nostr.nostrord.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.nostr.nostrord.utils.epochMillis
import kotlin.concurrent.Volatile

/**
 * Serializes the signer operations that put a dialog in front of the user.
 *
 * A NIP-07 extension or a NIP-55 signer app renders one dialog per request. Fired
 * concurrently - every reconnecting relay challenging for NIP-42 at once, or the account's
 * encrypted lists loading together - they reach the user as a wall of dialogs, each one
 * followed by a PIN prompt when the signer is PIN-locked. Prompting signers take their turn
 * one at a time so at most one dialog is ever on screen.
 *
 * Local and bunker signers show nothing and are NOT serialized: a DM backlog queued behind
 * one slow bunker round-trip would starve group AUTH.
 */
object SignerPrompts {
    private val gate = Mutex()

    suspend fun <T> queued(
        promptsUser: Boolean,
        block: suspend () -> T,
    ): T = if (promptsUser) gate.withLock { block() } else block()
}

/**
 * Remembers self-decrypts so one ciphertext is sent to the signer once per session.
 *
 * The account's encrypted lists (kind:10000 mutes, kind:10009 private groups, kind:30078
 * per-group notification levels) are replaceable: every connected relay delivers its own
 * copy of the same event, and each copy carries the identical ciphertext. Without this,
 * each copy reaches the signer and a prompting signer opens one dialog per relay.
 *
 * A refusal is remembered too, and for much longer than it takes the same list to be
 * redelivered - re-asking on every redelivery turns a single "no" into a dialog every
 * minute for the rest of the session.
 */
class SelfDecryptCache {
    private val mutex = Mutex()

    @Volatile private var plaintexts: Map<String, String> = emptyMap()

    @Volatile private var refusedAt: Map<String, Long> = emptyMap()

    /**
     * The plaintext of [ciphertext], decrypting through [decrypt] only if this exact
     * ciphertext has not already been read or refused. Null when it cannot be read; the
     * caller leaves the section opaque.
     */
    suspend fun decrypt(
        peerPubkey: String,
        ciphertext: String,
        decrypt: suspend () -> String?,
    ): String? {
        val key = "$peerPubkey|$ciphertext"
        plaintexts[key]?.let { return it }
        return mutex.withLock {
            // Re-checked under the lock: copies of the same list race here, and the first
            // arrival resolves the ciphertext the others are queued to ask about.
            plaintexts[key]?.let { return@withLock it }
            val refused = refusedAt[key]
            if (refused != null && epochMillis() - refused < REFUSAL_RETRY_MS) return@withLock null
            val plaintext =
                try {
                    decrypt()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }
            if (plaintext == null) {
                refusedAt = refusedAt + (key to epochMillis())
            } else {
                plaintexts = bounded(plaintexts + (key to plaintext))
                refusedAt = refusedAt - key
            }
            plaintext
        }
    }

    /** Session-scoped: an account switch must not carry another account's plaintext. */
    fun clear() {
        plaintexts = emptyMap()
        refusedAt = emptyMap()
    }

    private fun bounded(entries: Map<String, String>): Map<String, String> = if (entries.size <= MAX_ENTRIES) {
        entries
    } else {
        entries.entries.drop(entries.size - MAX_ENTRIES).associate { it.key to it.value }
    }

    private companion object {
        // Long enough that a rejected list is not re-asked on every redelivery, short
        // enough that a signer that was briefly unreachable heals without a relaunch.
        const val REFUSAL_RETRY_MS = 10 * 60_000L

        // One entry per list version read this session; the bound only catches a client
        // that rewrites its lists in a loop.
        const val MAX_ENTRIES = 32
    }
}
