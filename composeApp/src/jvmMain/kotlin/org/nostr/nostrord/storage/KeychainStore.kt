package org.nostr.nostrord.storage

import com.github.javakeyring.BackendNotSupportedException
import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException
import java.util.Base64

internal sealed interface MasterKeyResult {
    data class Found(val key: ByteArray) : MasterKeyResult

    data object Absent : MasterKeyResult

    // The OS keyring holds an entry but refused to hand it over (user hit Deny on the
    // macOS ACL prompt, locked Secret Service collection, ...). Minting a fresh key or
    // running with an ephemeral one here would orphan every blob encrypted with the
    // real key — callers must treat this as fatal, never as "no key yet".
    data object Denied : MasterKeyResult
}

internal object KeychainStore {
    private const val SERVICE = "org.nostr.nostrord"
    private const val ACCOUNT_MASTER_KEY = "master_encryption_key"

    // java-keyring 1.0.3 signals "entry does not exist" only via this message prefix
    // (same string in the osx and freedesktop backends); every other failure is an
    // access/backend error. Message-matching is version-fragile — revisit on upgrade.
    private const val NOT_FOUND_PREFIX = "No stored credentials match"

    private val keyring: Keyring? by lazy {
        // Unit tests must not touch the real OS keyring: java-keyring's Secret Service
        // connection (dbus-java) has a reader thread that races its own executor shutdown,
        // and the uncaught RejectedExecutionException poisons a LATER kotlinx runTest as
        // UncaughtExceptionsBeforeTest — an intermittent, cross-class suite failure. The
        // Gradle Test tasks set this property; SecureStorage falls back to its
        // no-keychain path.
        if (System.getProperty("nostrord.disableKeychain") == "true") return@lazy null
        try {
            Keyring.create()
        } catch (_: BackendNotSupportedException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    fun isAvailable(): Boolean = keyring != null

    fun readMasterKey(): MasterKeyResult {
        val ring = keyring ?: return MasterKeyResult.Absent
        return try {
            val b64 = ring.getPassword(SERVICE, ACCOUNT_MASTER_KEY)
                ?: return MasterKeyResult.Absent
            MasterKeyResult.Found(Base64.getDecoder().decode(b64))
        } catch (e: PasswordAccessException) {
            if (e.message?.startsWith(NOT_FOUND_PREFIX) == true) {
                MasterKeyResult.Absent
            } else {
                MasterKeyResult.Denied
            }
        } catch (_: Throwable) {
            MasterKeyResult.Denied
        }
    }

    fun setMasterKey(key: ByteArray): Boolean {
        val ring = keyring ?: return false
        return try {
            ring.setPassword(SERVICE, ACCOUNT_MASTER_KEY, Base64.getEncoder().encodeToString(key))
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun deleteMasterKey() {
        val ring = keyring ?: return
        try {
            ring.deletePassword(SERVICE, ACCOUNT_MASTER_KEY)
        } catch (_: Throwable) {
        }
    }
}
