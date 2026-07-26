package org.nostr.nostrord.nostr

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * NIP-55 Android signer client (Amber). Two channels, tried in order:
 *
 * 1. ContentResolver `content://<signer>.<TYPE>` — silent, works only for operations the
 *    user pre-authorized in the signer; a null cursor means "not authorized, ask via UI".
 * 2. `nostrsigner:` Intent — opens the signer's approval screen and returns through
 *    [Nip55AndroidBridge]'s activity-result launcher.
 *
 * Background signing (NIP-42 AUTH, resubscribes) rides channel 1; channel 2 is the
 * fallback and the only channel for `get_public_key` (login always shows the signer UI).
 */
actual object Nip55 {
    actual fun isAvailable(): Boolean {
        val pm = Nip55AndroidBridge.appContext?.packageManager ?: return false
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:"))
        return pm.queryIntentActivities(probe, 0).isNotEmpty()
    }

    actual suspend fun getPublicKey(permissionsJson: String): Nip55Login {
        val result =
            Nip55AndroidBridge.launch(
                signerIntent(payload = "", type = "get_public_key", signerPackage = null) {
                    putExtra("permissions", permissionsJson)
                },
            )
        val raw = result.result ?: throw Nip55Exception("Signer returned no public key")
        return Nip55Login(pubkeyHex = normalizePubkey(raw), signerPackage = result.signerPackage)
    }

    actual suspend fun signEvent(
        eventJson: String,
        currentUserHex: String,
        signerPackage: String?,
    ): String {
        queryProvider(signerPackage, "SIGN_EVENT", arrayOf(eventJson, "", currentUserHex))
            ?.let { cols -> cols["event"]?.takeIf { it.isNotBlank() }?.let { return it } }
        val result =
            Nip55AndroidBridge.launch(
                signerIntent(eventJson, "sign_event", signerPackage) {
                    putExtra("current_user", currentUserHex)
                },
            )
        return result.event?.takeIf { it.isNotBlank() }
            ?: throw Nip55Exception("Signer returned no signed event")
    }

    actual suspend fun nip44Encrypt(
        peerPubkeyHex: String,
        plaintext: String,
        currentUserHex: String,
        signerPackage: String?,
    ): String = cryptoOp("NIP44_ENCRYPT", "nip44_encrypt", peerPubkeyHex, plaintext, currentUserHex, signerPackage)

    actual suspend fun nip44Decrypt(
        peerPubkeyHex: String,
        ciphertext: String,
        currentUserHex: String,
        signerPackage: String?,
    ): String = cryptoOp("NIP44_DECRYPT", "nip44_decrypt", peerPubkeyHex, ciphertext, currentUserHex, signerPackage)

    private suspend fun cryptoOp(
        providerType: String,
        intentType: String,
        peerPubkeyHex: String,
        payload: String,
        currentUserHex: String,
        signerPackage: String?,
    ): String {
        queryProvider(signerPackage, providerType, arrayOf(payload, peerPubkeyHex, currentUserHex))
            ?.let { cols -> cols["result"]?.takeIf { it.isNotBlank() }?.let { return it } }
        val result =
            Nip55AndroidBridge.launch(
                signerIntent(payload, intentType, signerPackage) {
                    putExtra("pubkey", peerPubkeyHex)
                    putExtra("current_user", currentUserHex)
                },
            )
        return result.result?.takeIf { it.isNotBlank() }
            ?: throw Nip55Exception("Signer returned no result for $intentType")
    }

    private fun signerIntent(
        payload: String,
        type: String,
        signerPackage: String?,
        extras: Intent.() -> Unit = {},
    ): Intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:$payload")).apply {
        signerPackage?.let { `package` = it }
        putExtra("type", type)
        extras()
    }

    /**
     * Silent ContentResolver channel. Returns the row's columns, or null when the signer
     * is unknown, the provider is absent, or the user hasn't pre-authorized the operation
     * (all of which mean: fall back to the Intent flow). An explicit `rejected` column is
     * a user decision and throws instead of re-asking.
     */
    private suspend fun queryProvider(
        signerPackage: String?,
        type: String,
        args: Array<String>,
    ): Map<String, String>? {
        val pkg = signerPackage ?: return null
        val resolver = Nip55AndroidBridge.appContext?.contentResolver ?: return null
        return withContext(Dispatchers.IO) {
            try {
                resolver.query(Uri.parse("content://$pkg.$type"), args, null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    if (cursor.getColumnIndex("rejected") >= 0) {
                        throw Nip55Exception("Request rejected by the signer")
                    }
                    buildMap {
                        for (i in 0 until cursor.columnCount) {
                            put(cursor.getColumnName(i), cursor.getString(i) ?: "")
                        }
                    }
                }
            } catch (e: Nip55Exception) {
                throw e
            } catch (e: Exception) {
                null // provider missing / permission error: use the Intent flow
            }
        }
    }

    /** Signers may return the pubkey as npub; requests need hex. */
    private fun normalizePubkey(raw: String): String {
        val s = raw.trim()
        if (!s.startsWith("npub1")) return s.lowercase()
        return (Nip19.decode(s) as? Nip19.Entity.Npub)?.pubkey
            ?: throw Nip55Exception("Signer returned an invalid public key")
    }
}

class Nip55Exception(
    message: String,
) : Exception(message)

/**
 * Bridges the `nostrsigner:` Intent round-trip into suspend land. [MainActivity]
 * registers its launcher in onCreate (re-registering on recreation); [launch] posts one
 * Intent at a time under a mutex — concurrent sign requests (e.g. NIP-42 AUTH on several
 * relays) queue instead of clobbering each other's pending result.
 */
object Nip55AndroidBridge {
    @Volatile var appContext: Context? = null
        private set

    private var launcher: ActivityResultLauncher<Intent>? = null

    @Volatile private var pending: CompletableDeferred<Nip55IntentResult>? = null
    private val mutex = Mutex()

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun register(activity: ComponentActivity) {
        launcher =
            activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                pending?.also { pending = null }?.complete(parse(result))
            }
    }

    suspend fun launch(intent: Intent): Nip55IntentResult = mutex.withLock {
        val active = launcher ?: throw Nip55Exception("App UI is not in the foreground")
        val deferred = CompletableDeferred<Nip55IntentResult>()
        pending = deferred
        try {
            withContext(Dispatchers.Main) { active.launch(intent) }
            val result = deferred.await()
            if (result.rejected) throw Nip55Exception("Request rejected by the signer")
            result
        } finally {
            pending = null
        }
    }

    private fun parse(result: ActivityResult): Nip55IntentResult {
        val data = result.data
        if (result.resultCode != android.app.Activity.RESULT_OK || data == null) {
            return Nip55IntentResult(rejected = true)
        }
        return Nip55IntentResult(
            result = data.getStringExtra("result"),
            event = data.getStringExtra("event"),
            signerPackage = data.getStringExtra("package"),
            rejected = data.getStringExtra("rejected") != null,
        )
    }
}

data class Nip55IntentResult(
    val result: String? = null,
    val event: String? = null,
    val signerPackage: String? = null,
    val rejected: Boolean = false,
)
