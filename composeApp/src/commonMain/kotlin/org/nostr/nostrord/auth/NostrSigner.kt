package org.nostr.nostrord.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.nostr.Nip07
import org.nostr.nostrord.nostr.Nip44
import org.nostr.nostrord.nostr.Nip46Client
import org.nostr.nostrord.nostr.Nip55
import kotlin.concurrent.Volatile

/**
 * Isolated signing context for a single Nostr account.
 *
 * One instance lives per [AccountSession]. When the session is cancelled,
 * [dispose] is called immediately to zero key material from memory. After
 * disposal, any [signEvent] call throws [SigningException].
 *
 * Production implementations: [Local], [Bunker], [Nip07Extension].
 * Tests may provide their own implementations.
 */
interface NostrSigner {
    /** Hex-encoded public key this signer represents. */
    val pubkey: String

    /**
     * True when signing is a remote round-trip (bunker / NIP-07) rather than a local,
     * effectively-instant secp256k1 sign. Callers use it to budget NIP-42 AUTH waits: a
     * remote signer needs a much longer window for the AUTH event to be signed, otherwise
     * the first private-group REQ races the sign and comes back CLOSED "auth-required".
     */
    val isRemote: Boolean get() = false

    /**
     * Sign [event] with this account's key material.
     *
     * Throws [SigningException] on permission denial, signer disconnection,
     * or if the signer was already disposed.
     */
    suspend fun signEvent(event: Event): Event

    /**
     * NIP-44 encrypt [plaintext] for [peerPubkeyHex] using this account's identity key — used to
     * seal NIP-17 direct messages. [Local] encrypts locally; [Bunker] and [Nip07Extension] delegate
     * to the remote signer. The interface default throws [SigningException] (unsupported), as it
     * does after [dispose]. The private key never leaves the signer.
     */
    suspend fun nip44Encrypt(peerPubkeyHex: String, plaintext: String): String = throw SigningException("This signer does not support NIP-44 encryption")

    /** NIP-44 decrypt [ciphertext] from [peerPubkeyHex] with this account's identity key. */
    suspend fun nip44Decrypt(peerPubkeyHex: String, ciphertext: String): String = throw SigningException("This signer does not support NIP-44 decryption")

    /**
     * Release all key material from memory. Called when the owning
     * [AccountSession] is cancelled. Must be idempotent.
     */
    fun dispose()

    class SigningException(
        message: String,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    /**
     * Signs synchronously using a locally held secp256k1 key pair.
     * Private key bytes are zeroed on [dispose].
     */
    class Local(
        keyPair: KeyPair,
    ) : NostrSigner {
        override val pubkey: String = keyPair.publicKeyHex

        @Volatile private var _keyPair: KeyPair? = keyPair

        override suspend fun signEvent(event: Event): Event {
            val kp =
                _keyPair
                    ?: throw SigningException("Local signer has been disposed")
            return event.sign(kp)
        }

        override suspend fun nip44Encrypt(peerPubkeyHex: String, plaintext: String): String {
            val kp = _keyPair ?: throw SigningException("Local signer has been disposed")
            return Nip44.encrypt(plaintext, kp.privateKeyHex, peerPubkeyHex)
        }

        override suspend fun nip44Decrypt(peerPubkeyHex: String, ciphertext: String): String {
            val kp = _keyPair ?: throw SigningException("Local signer has been disposed")
            return Nip44.decrypt(ciphertext, kp.privateKeyHex, peerPubkeyHex)
        }

        override fun dispose() {
            _keyPair?.privateKey?.fill(0)
            _keyPair = null
        }
    }

    /**
     * Signs via a NIP-46 remote bunker. The [nip46Client] is disconnected on
     * [dispose] so the previous account's relay subscription is torn down
     * immediately when the session switches.
     */
    class Bunker(
        val nip46Client: Nip46Client,
        override val pubkey: String,
    ) : NostrSigner {
        override val isRemote: Boolean = true

        @Volatile private var disposed = false

        override suspend fun signEvent(event: Event): Event {
            if (disposed) throw SigningException("Bunker signer has been disposed")
            return try {
                val signedJson = nip46Client.signEvent(event.toJsonString())
                parseSignedEventJson(signedJson)
            } catch (e: Exception) {
                // A cancelled sign (scope teardown, account switch) must propagate as
                // cancellation, not read as a signer failure to callers that count them.
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is SigningException) throw e
                throw SigningException("Bunker signing failed: ${e.message}", e)
            }
        }

        override suspend fun nip44Encrypt(peerPubkeyHex: String, plaintext: String): String {
            if (disposed) throw SigningException("Bunker signer has been disposed")
            return try {
                nip46Client.nip44Encrypt(peerPubkeyHex, plaintext)
            } catch (e: Exception) {
                throw SigningException("Bunker NIP-44 encryption failed: ${e.message}", e)
            }
        }

        /** How this signer session answers the custom `nip44_decrypt_batch` method. */
        enum class BatchDecryptMode { Untried, Supported, Unsupported }

        @Volatile
        var batchDecryptMode: BatchDecryptMode = BatchDecryptMode.Untried
            private set

        private val decryptBatcher = org.nostr.nostrord.nostr.Nip46DecryptBatcher(
            send = { items -> nip46Client.nip44DecryptBatch(items) },
        )

        override suspend fun nip44Decrypt(peerPubkeyHex: String, ciphertext: String): String {
            if (disposed) throw SigningException("Bunker signer has been disposed")
            // Batch lane first: concurrent decrypts (the DM backlog) coalesce into one
            // request/response event pair, the only real cure for the bunker relay's
            // rate limit. Falls back to per-item for signers without the method.
            if (batchDecryptMode != BatchDecryptMode.Unsupported) {
                try {
                    val plaintext = decryptBatcher.decrypt(peerPubkeyHex, ciphertext)
                    batchDecryptMode = BatchDecryptMode.Supported
                    return plaintext
                        ?: throw SigningException("Bunker NIP-44 decryption failed: signer could not decrypt")
                } catch (e: org.nostr.nostrord.nostr.Nip46DecryptBatcher.BatchUnsupported) {
                    batchDecryptMode = BatchDecryptMode.Unsupported
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (e is SigningException) throw e
                    throw SigningException("Bunker NIP-44 decryption failed: ${e.message}", e)
                }
            }
            return try {
                nip46Client.nip44Decrypt(peerPubkeyHex, ciphertext)
            } catch (e: Exception) {
                throw SigningException("Bunker NIP-44 decryption failed: ${e.message}", e)
            }
        }

        override fun dispose() {
            if (disposed) return
            disposed = true
            try {
                nip46Client.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Signs via the NIP-07 browser extension (window.nostr).
     * Valid on JS/WasmJS targets only. On other targets [Nip07.isAvailable]
     * returns false and [AccountSessionFactory] will not create this signer.
     */
    class Nip07Extension(
        override val pubkey: String,
    ) : NostrSigner {
        override val isRemote: Boolean = true

        @Volatile private var disposed = false

        override suspend fun signEvent(event: Event): Event {
            if (disposed) throw SigningException("NIP-07 signer has been disposed")
            val signedJson = Nip07.signEvent(event.toJsonString())
            return parseSignedEventJson(signedJson)
        }

        override suspend fun nip44Encrypt(peerPubkeyHex: String, plaintext: String): String {
            if (disposed) throw SigningException("NIP-07 signer has been disposed")
            return try {
                Nip07.nip44Encrypt(peerPubkeyHex, plaintext)
            } catch (e: Exception) {
                throw SigningException("NIP-07 NIP-44 encryption failed: ${e.message}", e)
            }
        }

        override suspend fun nip44Decrypt(peerPubkeyHex: String, ciphertext: String): String {
            if (disposed) throw SigningException("NIP-07 signer has been disposed")
            return try {
                Nip07.nip44Decrypt(peerPubkeyHex, ciphertext)
            } catch (e: Exception) {
                throw SigningException("NIP-07 NIP-44 decryption failed: ${e.message}", e)
            }
        }

        override fun dispose() {
            disposed = true
        }
    }

    /**
     * Signs via a NIP-55 external Android signer app (Amber). The key never enters this
     * process; pre-authorized requests go through the signer's ContentResolver silently,
     * others open its approval UI. [signerPackage] addresses the signer chosen at login.
     */
    class Amber(
        override val pubkey: String,
        private val signerPackage: String?,
    ) : NostrSigner {
        override val isRemote: Boolean = true

        @Volatile private var disposed = false

        override suspend fun signEvent(event: Event): Event {
            if (disposed) throw SigningException("Amber signer has been disposed")
            return try {
                parseSignedEventJson(Nip55.signEvent(event.toJsonString(), pubkey, signerPackage))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is SigningException) throw e
                throw SigningException("Amber signing failed: ${e.message}", e)
            }
        }

        override suspend fun nip44Encrypt(peerPubkeyHex: String, plaintext: String): String {
            if (disposed) throw SigningException("Amber signer has been disposed")
            return try {
                Nip55.nip44Encrypt(peerPubkeyHex, plaintext, pubkey, signerPackage)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                throw SigningException("Amber NIP-44 encryption failed: ${e.message}", e)
            }
        }

        override suspend fun nip44Decrypt(peerPubkeyHex: String, ciphertext: String): String {
            if (disposed) throw SigningException("Amber signer has been disposed")
            return try {
                Nip55.nip44Decrypt(peerPubkeyHex, ciphertext, pubkey, signerPackage)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                throw SigningException("Amber NIP-44 decryption failed: ${e.message}", e)
            }
        }

        override fun dispose() {
            disposed = true
        }
    }
}

private val signedEventJson = Json { ignoreUnknownKeys = true }

internal fun parseSignedEventJson(jsonString: String): Event {
    val obj = signedEventJson.parseToJsonElement(jsonString).jsonObject
    val id =
        obj["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: throw NostrSigner.SigningException("Bunker returned event with missing id")
    val sig =
        obj["sig"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: throw NostrSigner.SigningException("Bunker returned unsigned event (missing sig)")
    return Event(
        id = id,
        pubkey = obj["pubkey"]?.jsonPrimitive?.content ?: "",
        createdAt = obj["created_at"]?.jsonPrimitive?.long ?: 0L,
        kind = obj["kind"]?.jsonPrimitive?.int ?: 0,
        tags =
        obj["tags"]?.jsonArray?.map { tag ->
            tag.jsonArray.map { it.jsonPrimitive.content }
        } ?: emptyList(),
        content = obj["content"]?.jsonPrimitive?.content ?: "",
        sig = sig,
    )
}
