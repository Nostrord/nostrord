package org.nostr.nostrord.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import java.util.prefs.Preferences
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

sealed class UnlockState {
    object Initializing : UnlockState()

    object Unlocked : UnlockState()

    object NeedsPassphrase : UnlockState()

    object NeedsPassphraseSetup : UnlockState()

    object NeedsLegacyMigration : UnlockState()
}

internal enum class KeySource { Keychain, Passphrase, Ephemeral }

actual object SecureStorage {
    private val prefs = Preferences.userNodeForPackage(SecureStorage::class.java)

    // Headroom under Preferences.MAX_VALUE_LENGTH (8192) for the chunked-value split.
    private const val MAX_PREF_VALUE_CHARS = 8000

    private const val PRIVATE_KEY_PREF = "nostr_private_key"
    private const val JOINED_GROUPS_PREFIX = "joined_groups_"
    private const val CURRENT_RELAY_URL = "current_relay_url"
    private const val RELAY_LIST = "relay_list"
    private const val BUNKER_URL_PREF = "nostr_bunker_url"
    private const val BUNKER_USER_PUBKEY_PREF = "nostr_bunker_user_pubkey"
    private const val BUNKER_CLIENT_PRIVATE_KEY_PREF = "nostr_bunker_client_private_key"
    private const val LAST_READ_PREFIX = "last_read_"
    private const val LAST_VIEWED_GROUP_PREFIX = "last_viewed_group_"
    private const val LAST_ROUTE_PREFIX = "last_route_"
    private const val MESSAGES_PREFIX = "messages_"
    private const val PENDING_EVENTS_PREFIX = "pending_events_"
    private const val RELAY_GROUPS_PREFIX = "relay_groups_"
    private const val JOINED_GROUP_META_PREFIX = "joined_group_meta_"
    private const val LIVE_CURSORS_PREFIX = "live_cursors_"

    private const val LEGACY_ENCRYPTION_KEY_PREF = "encryption_key"
    private const val PASSPHRASE_SALT_PREF = "passphrase_salt"
    private const val PASSPHRASE_VERIFIER_PREF = "passphrase_verifier"

    private const val V2_PREFIX = "v2:"
    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_BITS = 128
    private const val KEY_LEN_BITS = 256
    private const val PBKDF2_ITERATIONS = 600_000
    private const val PASSPHRASE_VERIFIER_PLAINTEXT = "nostrord-passphrase-ok"

    private val _unlockState = MutableStateFlow<UnlockState>(UnlockState.Initializing)
    val unlockState: StateFlow<UnlockState> = _unlockState.asStateFlow()

    private var masterKey: SecretKey? = null
    private var legacyKey: SecretKey? = null
    private var keySource: KeySource = KeySource.Ephemeral

    fun usesKeychain(): Boolean = keySource == KeySource.Keychain

    fun usesPassphrase(): Boolean = keySource == KeySource.Passphrase

    init {
        initialize()
    }

    private fun initialize() {
        // Test mode (set by the Gradle Test tasks): never touch the OS keyring (its dbus-java
        // thread poisons kotlinx runTest, see KeychainStore) and never inherit this machine's
        // real prefs unlock state (a dev box with legacy app data would park the whole suite
        // on NeedsLegacyMigration, killing every encrypted slot). Ephemeral key, unlocked —
        // the state a fresh keychainless install runs in.
        if (System.getProperty("nostrord.disableKeychain") == "true") {
            masterKey = SecretKeySpec(randomKeyBytes(), "AES")
            keySource = KeySource.Ephemeral
            _unlockState.value = UnlockState.Unlocked
            return
        }
        legacyKey = loadLegacyKey()

        val existingKeychainKey = KeychainStore.getMasterKey()
        if (existingKeychainKey != null) {
            masterKey = SecretKeySpec(existingKeychainKey, "AES")
            keySource = KeySource.Keychain
            _unlockState.value = UnlockState.Unlocked
            migrateLegacyIfNeeded()
            return
        }

        // Keychain available but empty: adopt it ONLY when no passphrase protects the
        // existing data. Minting a fresh key over a passphrase verifier would orphan
        // every slot encrypted with the passphrase-derived key; prompt one last time
        // instead and let unlockWithPassphrase promote that key into the keychain.
        if (KeychainStore.isAvailable() && prefs.get(PASSPHRASE_VERIFIER_PREF, null) == null) {
            val freshKey = randomKeyBytes()
            if (KeychainStore.setMasterKey(freshKey)) {
                masterKey = SecretKeySpec(freshKey, "AES")
                keySource = KeySource.Keychain
                _unlockState.value = UnlockState.Unlocked
                migrateLegacyIfNeeded()
                return
            }
        }

        when {
            prefs.get(PASSPHRASE_VERIFIER_PREF, null) != null -> {
                _unlockState.value = UnlockState.NeedsPassphrase
            }
            legacyKey != null -> {
                // Pre-existing v1 data on a machine without keychain: force a passphrase
                // setup before any read or write. Migrating to an ephemeral key here would
                // orphan the data on next launch.
                _unlockState.value = UnlockState.NeedsLegacyMigration
            }
            else -> {
                // Fresh install without keychain: run unlocked with an in-memory key.
                // A sensitive save (nsec / bunker) will later transition to NeedsPassphraseSetup.
                masterKey = SecretKeySpec(randomKeyBytes(), "AES")
                keySource = KeySource.Ephemeral
                _unlockState.value = UnlockState.Unlocked
            }
        }
    }

    fun unlockWithPassphrase(passphrase: String): Boolean {
        if (passphrase.isEmpty()) return false
        val saltB64 = prefs.get(PASSPHRASE_SALT_PREF, null) ?: return false
        val verifier = prefs.get(PASSPHRASE_VERIFIER_PREF, null) ?: return false
        val key = deriveKeyFromPassphrase(passphrase, Base64.getDecoder().decode(saltB64))
        val plain =
            try {
                decryptV2(verifier, key)
            } catch (_: Exception) {
                return false
            }
        if (plain != PASSPHRASE_VERIFIER_PLAINTEXT) return false
        masterKey = key
        keySource = KeySource.Passphrase
        // Keychain became available on a passphrase install: promote the derived key
        // into the keychain so future boots unlock silently, same data, same key.
        // Verifier and salt are removed only after the keychain write succeeds; on
        // failure the install stays in passphrase mode untouched.
        if (KeychainStore.isAvailable() && KeychainStore.setMasterKey(key.encoded)) {
            keySource = KeySource.Keychain
            prefs.remove(PASSPHRASE_VERIFIER_PREF)
            prefs.remove(PASSPHRASE_SALT_PREF)
            prefs.flush()
        }
        _unlockState.value = UnlockState.Unlocked
        migrateLegacyIfNeeded()
        return true
    }

    fun setupPassphrase(passphrase: String): Boolean {
        if (passphrase.isEmpty()) return false
        val state = _unlockState.value
        if (state !is UnlockState.NeedsPassphraseSetup && state !is UnlockState.NeedsLegacyMigration) {
            return false
        }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val newKey = deriveKeyFromPassphrase(passphrase, salt)

        // Re-encrypt anything this session already wrote with the ephemeral key.
        masterKey?.let { reencryptAllV2Blobs(it, newKey) }

        masterKey = newKey
        keySource = KeySource.Passphrase
        // Convert any v1 blobs from a previous install to v2 with the new key.
        migrateLegacyIfNeeded()

        prefs.put(PASSPHRASE_SALT_PREF, Base64.getEncoder().encodeToString(salt))
        prefs.put(PASSPHRASE_VERIFIER_PREF, encryptV2(PASSPHRASE_VERIFIER_PLAINTEXT, newKey))
        prefs.flush()
        _unlockState.value = UnlockState.Unlocked
        return true
    }

    fun changePassphrase(
        current: String,
        new: String,
    ): Boolean {
        if (new.isEmpty()) return false
        if (keySource != KeySource.Passphrase) return false
        val saltB64 = prefs.get(PASSPHRASE_SALT_PREF, null) ?: return false
        val verifier = prefs.get(PASSPHRASE_VERIFIER_PREF, null) ?: return false
        val currentKey = deriveKeyFromPassphrase(current, Base64.getDecoder().decode(saltB64))
        val plain =
            try {
                decryptV2(verifier, currentKey)
            } catch (_: Exception) {
                return false
            }
        if (plain != PASSPHRASE_VERIFIER_PLAINTEXT) return false
        val oldKey = masterKey ?: return false
        val newSalt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val newKey = deriveKeyFromPassphrase(new, newSalt)
        reencryptAllV2Blobs(oldKey, newKey)
        prefs.put(PASSPHRASE_SALT_PREF, Base64.getEncoder().encodeToString(newSalt))
        prefs.put(PASSPHRASE_VERIFIER_PREF, encryptV2(PASSPHRASE_VERIFIER_PLAINTEXT, newKey))
        prefs.flush()
        masterKey = newKey
        return true
    }

    private fun reencryptAllV2Blobs(
        oldKey: SecretKey,
        newKey: SecretKey,
    ) {
        val protectedKeys =
            setOf(
                LEGACY_ENCRYPTION_KEY_PREF,
                PASSPHRASE_SALT_PREF,
                PASSPHRASE_VERIFIER_PREF,
            )
        val keys =
            try {
                prefs.keys().toList()
            } catch (_: Exception) {
                emptyList()
            }
        keys.forEach { k ->
            if (k in protectedKeys) return@forEach
            val v = prefs.get(k, null) ?: return@forEach
            if (!v.startsWith(V2_PREFIX)) return@forEach
            val plain =
                try {
                    decryptV2(v, oldKey)
                } catch (_: Exception) {
                    return@forEach
                }
            try {
                prefs.put(k, encryptV2(plain, newKey))
            } catch (_: Exception) {
            }
        }
        try {
            prefs.flush()
        } catch (_: Exception) {
        }
    }

    private fun maybePromptPassphraseSetup() {
        if (keySource == KeySource.Ephemeral && _unlockState.value == UnlockState.Unlocked) {
            _unlockState.value = UnlockState.NeedsPassphraseSetup
        }
    }

    private fun loadLegacyKey(): SecretKey? {
        val s = prefs.get(LEGACY_ENCRYPTION_KEY_PREF, null) ?: return null
        return try {
            SecretKeySpec(Base64.getDecoder().decode(s), "AES")
        } catch (_: Exception) {
            null
        }
    }

    private fun randomKeyBytes(): ByteArray {
        val kg = KeyGenerator.getInstance("AES")
        kg.init(KEY_LEN_BITS, SecureRandom())
        return kg.generateKey().encoded
    }

    private fun deriveKeyFromPassphrase(
        passphrase: String,
        salt: ByteArray,
    ): SecretKey {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LEN_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun encryptV2(
        plain: String,
        key: SecretKey,
    ): String {
        val iv = ByteArray(GCM_IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ct, 0, out, iv.size, ct.size)
        return V2_PREFIX + Base64.getEncoder().encodeToString(out)
    }

    private fun decryptV2(
        blob: String,
        key: SecretKey,
    ): String {
        require(blob.startsWith(V2_PREFIX)) { "not a v2 blob" }
        val raw = Base64.getDecoder().decode(blob.removePrefix(V2_PREFIX))
        val iv = raw.copyOfRange(0, GCM_IV_LEN)
        val ct = raw.copyOfRange(GCM_IV_LEN, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private fun decryptLegacy(
        blob: String,
        key: SecretKey,
    ): String {
        // "AES" alone resolves to AES/ECB/PKCS5Padding — kept only to read v1 blobs during migration.
        val cipher = Cipher.getInstance("AES")
        cipher.init(Cipher.DECRYPT_MODE, key)
        return String(cipher.doFinal(Base64.getDecoder().decode(blob)), Charsets.UTF_8)
    }

    private fun encrypt(plain: String): String {
        val k = masterKey ?: throw IllegalStateException("SecureStorage is locked")
        return encryptV2(plain, k)
    }

    private fun decrypt(blob: String): String? {
        return try {
            if (blob.startsWith(V2_PREFIX)) {
                val k = masterKey ?: return null
                decryptV2(blob, k)
            } else {
                val k = legacyKey ?: return null
                decryptLegacy(blob, k)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun migrateLegacyIfNeeded() {
        val legacy = legacyKey ?: return
        val newKey = masterKey ?: return

        val protectedKeys =
            setOf(
                LEGACY_ENCRYPTION_KEY_PREF,
                PASSPHRASE_SALT_PREF,
                PASSPHRASE_VERIFIER_PREF,
            )

        val keys =
            try {
                prefs.keys().toList()
            } catch (_: Exception) {
                emptyList()
            }
        var saveFailed = false
        keys.forEach { k ->
            if (k in protectedKeys) return@forEach
            val v = prefs.get(k, null) ?: return@forEach
            if (v.startsWith(V2_PREFIX)) return@forEach
            val plain =
                try {
                    decryptLegacy(v, legacy)
                } catch (_: Exception) {
                    return@forEach
                }
            try {
                prefs.put(k, encryptV2(plain, newKey))
            } catch (_: Exception) {
                // Keep the legacy key so the next launch can retry — losing it now would orphan the v1 blob.
                saveFailed = true
            }
        }
        try {
            prefs.flush()
        } catch (_: Exception) {
        }
        if (!saveFailed) {
            try {
                prefs.remove(LEGACY_ENCRYPTION_KEY_PREF)
                prefs.flush()
            } catch (_: Exception) {
            }
            legacyKey = null
        }
    }

    actual fun savePrivateKey(privateKeyHex: String) {
        prefs.put(PRIVATE_KEY_PREF, encrypt(privateKeyHex))
        prefs.flush()
        maybePromptPassphraseSetup()
    }

    actual fun getPrivateKey(): String? {
        val encrypted = prefs.get(PRIVATE_KEY_PREF, null) ?: return null
        return decrypt(encrypted)
    }

    actual fun hasPrivateKey(): Boolean = prefs.get(PRIVATE_KEY_PREF, null) != null

    actual fun clearPrivateKey() {
        prefs.remove(PRIVATE_KEY_PREF)
        prefs.flush()
    }

    actual fun saveCurrentRelayUrl(relayUrl: String) {
        saveString(CURRENT_RELAY_URL, relayUrl)
    }

    actual fun getCurrentRelayUrl(): String? = getString(CURRENT_RELAY_URL)

    actual fun clearCurrentRelayUrl() {
        remove(CURRENT_RELAY_URL)
    }

    actual fun saveRelayList(relays: List<String>) {
        saveString(RELAY_LIST, relays.joinToString(","))
    }

    actual fun loadRelayList(): List<String> {
        val raw = getString(RELAY_LIST) ?: return emptyList()
        return if (raw.isBlank()) emptyList() else raw.split(",").filter { it.isNotBlank() }
    }

    actual fun saveJoinedGroupsForRelay(
        pubkey: String,
        relayUrl: String,
        groupIds: Set<String>,
    ) {
        val key = JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_" + relayUrl.hashCode()
        val json = Json.encodeToString(groupIds.toList())
        saveString(key, json)
    }

    actual fun getJoinedGroupsForRelay(
        pubkey: String,
        relayUrl: String,
    ): Set<String> {
        val key = JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_" + relayUrl.hashCode()
        val json = getString(key) ?: return emptySet()
        return try {
            Json.decodeFromString<List<String>>(json).toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    actual fun clearJoinedGroupsForRelay(
        pubkey: String,
        relayUrl: String,
    ) {
        val key = JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_" + relayUrl.hashCode()
        remove(key)
    }

    actual fun clearAllJoinedGroupsForAccount(pubkey: String) {
        try {
            val accountPrefix = JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_"
            prefs.keys().forEach { key ->
                if (key.startsWith(accountPrefix)) {
                    prefs.remove(key)
                }
            }
            prefs.flush()
        } catch (e: Exception) {
        }
    }

    actual fun saveBunkerUrl(bunkerUrl: String) {
        prefs.put(BUNKER_URL_PREF, encrypt(bunkerUrl))
        prefs.flush()
        maybePromptPassphraseSetup()
    }

    actual fun getBunkerUrl(): String? {
        val encrypted = prefs.get(BUNKER_URL_PREF, null) ?: return null
        return decrypt(encrypted)
    }

    actual fun hasBunkerUrl(): Boolean = prefs.get(BUNKER_URL_PREF, null) != null

    actual fun clearBunkerUrl() {
        prefs.remove(BUNKER_URL_PREF)
        prefs.flush()
    }

    actual fun saveBunkerUserPubkey(pubkey: String) {
        prefs.put(BUNKER_USER_PUBKEY_PREF, encrypt(pubkey))
        prefs.flush()
        maybePromptPassphraseSetup()
    }

    actual fun getBunkerUserPubkey(): String? {
        val encrypted = prefs.get(BUNKER_USER_PUBKEY_PREF, null) ?: return null
        return decrypt(encrypted)
    }

    actual fun clearBunkerUserPubkey() {
        prefs.remove(BUNKER_USER_PUBKEY_PREF)
        prefs.flush()
    }

    actual fun saveBunkerClientPrivateKey(privateKey: String) {
        prefs.put(BUNKER_CLIENT_PRIVATE_KEY_PREF, encrypt(privateKey))
        prefs.flush()
        maybePromptPassphraseSetup()
    }

    actual fun getBunkerClientPrivateKey(): String? {
        val encrypted = prefs.get(BUNKER_CLIENT_PRIVATE_KEY_PREF, null) ?: return null
        return decrypt(encrypted)
    }

    actual fun clearBunkerClientPrivateKey() {
        prefs.remove(BUNKER_CLIENT_PRIVATE_KEY_PREF)
        prefs.flush()
    }

    actual fun saveNip07UserPubkey(pubkey: String) {}

    actual fun getNip07UserPubkey(): String? = null

    actual fun clearNip07UserPubkey() {}

    actual fun clearAll() {
        prefs.clear()
        prefs.flush()
        KeychainStore.deleteMasterKey()
    }

    actual fun saveLastReadTimestamp(
        pubkey: String,
        groupId: String,
        timestamp: Long,
    ) {
        val key = LAST_READ_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        prefs.putLong(key, timestamp)
        prefs.flush()
    }

    actual fun getLastReadTimestamp(
        pubkey: String,
        groupId: String,
    ): Long? {
        val key = LAST_READ_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        val value = prefs.getLong(key, -1L)
        return if (value == -1L) null else value
    }

    actual fun clearLastReadTimestamp(
        pubkey: String,
        groupId: String,
    ) {
        val key = LAST_READ_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        prefs.remove(key)
        prefs.flush()
    }

    actual fun getAllLastReadTimestamps(pubkey: String): Map<String, Long> {
        val prefix = LAST_READ_PREFIX + pubkey.hashCode() + "_"
        val result = mutableMapOf<String, Long>()
        try {
            prefs.keys().forEach { key ->
                if (key.startsWith(prefix)) {
                    val groupHash = key.removePrefix(prefix)
                    val timestamp = prefs.getLong(key, -1L)
                    if (timestamp != -1L) {
                        result[groupHash] = timestamp
                    }
                }
            }
        } catch (e: Exception) {
        }
        return result
    }

    // java.util.prefs.Preferences caps a single value at MAX_VALUE_LENGTH (8192). Encrypted blobs
    // that grow past that (notably the DM cache JSON) would make prefs.put throw and the write get
    // silently dropped, so they never persisted across restarts. Split such values across
    // "<key>#<i>" chunks with "<key>#n" holding the count; small values keep the plain single-key
    // form, so existing data still loads.
    private fun chunkCountKey(key: String) = "$key#n"

    private fun chunkKey(key: String, index: Int) = "$key#$index"

    private fun clearChunks(key: String) {
        val count = prefs.getInt(chunkCountKey(key), 0)
        if (count > 0) {
            for (i in 0 until count) prefs.remove(chunkKey(key, i))
            prefs.remove(chunkCountKey(key))
        }
    }

    private fun saveString(
        key: String,
        value: String,
    ) {
        val encrypted = encrypt(value)
        clearChunks(key)
        if (encrypted.length <= MAX_PREF_VALUE_CHARS) {
            prefs.put(key, encrypted)
        } else {
            prefs.remove(key)
            val chunks = encrypted.chunked(MAX_PREF_VALUE_CHARS)
            chunks.forEachIndexed { i, chunk -> prefs.put(chunkKey(key, i), chunk) }
            prefs.putInt(chunkCountKey(key), chunks.size)
        }
        prefs.flush()
    }

    private fun getString(key: String): String? {
        val count = prefs.getInt(chunkCountKey(key), 0)
        val encrypted =
            if (count > 0) {
                buildString { for (i in 0 until count) append(prefs.get(chunkKey(key, i), "")) }
            } else {
                prefs.get(key, null) ?: return null
            }
        return decrypt(encrypted)
    }

    private fun remove(key: String) {
        prefs.remove(key)
        clearChunks(key)
        prefs.flush()
    }

    actual fun saveLastViewedGroup(
        pubkey: String,
        groupId: String,
        groupName: String?,
    ) {
        val key = LAST_VIEWED_GROUP_PREFIX + pubkey.hashCode()
        val value = "$groupId|${groupName ?: ""}"
        saveString(key, value)
    }

    actual fun getLastViewedGroup(pubkey: String): Pair<String, String?>? {
        val key = LAST_VIEWED_GROUP_PREFIX + pubkey.hashCode()
        val value = getString(key) ?: return null
        val parts = value.split("|", limit = 2)
        if (parts.isEmpty() || parts[0].isBlank()) return null
        val groupId = parts[0]
        val groupName = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        return Pair(groupId, groupName)
    }

    actual fun clearLastViewedGroup(pubkey: String) {
        val key = LAST_VIEWED_GROUP_PREFIX + pubkey.hashCode()
        remove(key)
    }

    actual fun saveLastRoute(
        pubkey: String,
        routeHash: String,
    ) {
        val key = LAST_ROUTE_PREFIX + pubkey.hashCode()
        saveString(key, routeHash)
    }

    actual fun getLastRoute(pubkey: String): String? {
        val key = LAST_ROUTE_PREFIX + pubkey.hashCode()
        return getString(key)
    }

    actual fun clearLastRoute(pubkey: String) {
        val key = LAST_ROUTE_PREFIX + pubkey.hashCode()
        remove(key)
    }

    actual fun saveMessagesForGroup(
        pubkey: String,
        groupId: String,
        messagesJson: String,
    ) {
        val key = MESSAGES_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        saveString(key, messagesJson)
    }

    actual fun getMessagesForGroup(
        pubkey: String,
        groupId: String,
    ): String? {
        val key = MESSAGES_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        return getString(key)
    }

    actual fun clearMessagesForGroup(
        pubkey: String,
        groupId: String,
    ) {
        val key = MESSAGES_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        remove(key)
    }

    actual fun clearAllMessagesForAccount(pubkey: String) {
        try {
            val accountPrefix = MESSAGES_PREFIX + pubkey.hashCode() + "_"
            prefs.keys().forEach { key ->
                if (key.startsWith(accountPrefix)) {
                    prefs.remove(key)
                }
            }
            prefs.flush()
        } catch (e: Exception) {
        }
    }

    actual fun savePendingEvents(
        pubkey: String,
        eventsJson: String,
    ) {
        val key = PENDING_EVENTS_PREFIX + pubkey.hashCode()
        saveString(key, eventsJson)
    }

    actual fun getPendingEvents(pubkey: String): String? {
        val key = PENDING_EVENTS_PREFIX + pubkey.hashCode()
        return getString(key)
    }

    actual fun clearPendingEvents(pubkey: String) {
        val key = PENDING_EVENTS_PREFIX + pubkey.hashCode()
        remove(key)
    }

    actual fun saveGroupsForRelay(
        relayUrl: String,
        groupsJson: String,
    ) {
        val key = RELAY_GROUPS_PREFIX + relayUrl.hashCode()
        saveString(key, groupsJson)
    }

    actual fun getGroupsForRelay(relayUrl: String): String? {
        val key = RELAY_GROUPS_PREFIX + relayUrl.hashCode()
        return getString(key)
    }

    actual fun clearGroupsForRelay(relayUrl: String) {
        val key = RELAY_GROUPS_PREFIX + relayUrl.hashCode()
        remove(key)
    }

    actual fun saveJoinedGroupMetadata(
        pubkey: String,
        relayUrl: String,
        groupsJson: String,
    ) {
        val key = JOINED_GROUP_META_PREFIX + pubkey.hashCode() + "_" + relayUrl.hashCode()
        saveString(key, groupsJson)
    }

    actual fun getJoinedGroupMetadata(
        pubkey: String,
        relayUrl: String,
    ): String? {
        val key = JOINED_GROUP_META_PREFIX + pubkey.hashCode() + "_" + relayUrl.hashCode()
        return getString(key)
    }

    actual fun clearAllJoinedGroupMetadataForAccount(pubkey: String) {
        try {
            val accountPrefix = JOINED_GROUP_META_PREFIX + pubkey.hashCode() + "_"
            prefs.keys().forEach { key ->
                if (key.startsWith(accountPrefix)) prefs.remove(key)
            }
            prefs.flush()
        } catch (_: Exception) {
        }
    }

    // Relay metadata is NOT stored in Preferences because java.util.prefs.Preferences.put()
    // rejects values longer than MAX_VALUE_LENGTH (8192 chars). Encrypted+Base64 relay metadata
    // for even a handful of relays can exceed this limit, causing silent save failures and a
    // missing cache on every app restart. We use a plain JSON file instead — relay metadata
    // is non-sensitive (it's public NIP-11 info) so encryption is unnecessary.
    private val relayMetadataFile: java.io.File by lazy {
        val dir = java.io.File(System.getProperty("user.home"), ".nostrord")
        dir.mkdirs()
        java.io.File(dir, "relay_metadata.json")
    }

    actual fun saveRelayMetadata(json: String) {
        try {
            relayMetadataFile.writeText(json)
        } catch (_: Exception) {
        }
    }

    actual fun getRelayMetadata(): String? = try {
        if (relayMetadataFile.exists()) relayMetadataFile.readText() else null
    } catch (_: Exception) {
        null
    }

    // Plain JSON file (same rationale as relay metadata: public, non-sensitive, and too
    // large for the 8 KB Preferences value cap).
    private val userMetadataFile: java.io.File by lazy {
        val dir = java.io.File(System.getProperty("user.home"), ".nostrord")
        dir.mkdirs()
        java.io.File(dir, "user_metadata.json")
    }

    actual fun saveUserMetadataCache(json: String) {
        try {
            userMetadataFile.writeText(json)
        } catch (_: Exception) {
        }
    }

    actual fun getUserMetadataCache(): String? = try {
        if (userMetadataFile.exists()) userMetadataFile.readText() else null
    } catch (_: Exception) {
        null
    }

    private val userGroupListsFile: java.io.File by lazy {
        val dir = java.io.File(System.getProperty("user.home"), ".nostrord")
        dir.mkdirs()
        java.io.File(dir, "user_group_lists.json")
    }

    actual fun saveUserGroupListsCache(json: String) {
        try {
            userGroupListsFile.writeText(json)
        } catch (_: Exception) {
        }
    }

    actual fun getUserGroupListsCache(): String? = try {
        if (userGroupListsFile.exists()) userGroupListsFile.readText() else null
    } catch (_: Exception) {
        null
    }

    actual fun saveLiveCursors(
        relayUrl: String,
        json: String,
    ) {
        val key = LIVE_CURSORS_PREFIX + relayUrl.hashCode()
        saveString(key, json)
    }

    actual fun getLiveCursors(relayUrl: String): String? {
        val key = LIVE_CURSORS_PREFIX + relayUrl.hashCode()
        return getString(key)
    }

    actual fun clearLiveCursors(relayUrl: String) {
        val key = LIVE_CURSORS_PREFIX + relayUrl.hashCode()
        remove(key)
    }

    actual fun saveBooleanPref(
        key: String,
        value: Boolean,
    ) {
        prefs.putBoolean(key, value)
        prefs.flush()
    }

    actual fun getBooleanPref(
        key: String,
        default: Boolean,
    ): Boolean = prefs.getBoolean(key, default)

    actual fun saveStringPref(
        key: String,
        value: String,
    ) {
        saveString(key, value)
    }

    actual fun getStringPref(
        key: String,
        default: String,
    ): String = getString(key) ?: default

    actual fun saveSensitive(
        key: String,
        value: String,
    ) {
        prefs.put(key, encrypt(value))
        prefs.flush()
        maybePromptPassphraseSetup()
    }

    actual fun getSensitive(key: String): String? {
        val encrypted = prefs.get(key, null) ?: return null
        return decrypt(encrypted)
    }

    actual fun clearSensitive(key: String) {
        prefs.remove(key)
        prefs.flush()
    }

    actual suspend fun preloadMetadata() {}
}
