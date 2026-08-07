package org.nostr.nostrord.auth.pomegranate

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.nostr.nostrord.network.createHttpClient
import org.nostr.nostrord.nostr.Crypto
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.nostr.hexToByteArray
import org.nostr.nostrord.nostr.toHexString
import org.nostr.nostrord.utils.epochMillis
import org.nostr.nostrord.utils.epochSeconds
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The pomegranate registration/recovery protocol against the central server and the
 * shard operators. HTTP + event signing are shared; only the popups and the dealer are
 * platform-gated (see [PomegranatePopups] / [PomegranateDealer]). After login the
 * returned `bunker://` URL rides the app's normal NIP-46 path — nothing downstream
 * knows the signer is threshold-based.
 *
 * Popup-opening methods must be invoked from a user gesture (click) or the browser
 * blocks the window; keep any await before them short.
 */
class PomegranateService {
    private val http by lazy { createHttpClient() }
    private val json = Json { ignoreUnknownKeys = true }

    /** Gates every UI entry point: web with the feature flag on. */
    val isAvailable: Boolean get() = PomegranateConfig.ENABLED && PomegranatePopups.isAvailable

    data class StartedLogin(
        val central: String,
        val token: GoogleToken,
        val hasAccount: Boolean,
    )

    data class LoginOutcome(
        val bunkerUrl: String,
        val central: String,
    )

    /** One operator's reachability, with the transport failure when it is down. */
    data class OperatorHealth(
        val url: String,
        val reachable: Boolean,
        val detail: String?,
    )

    data class Recovery(
        val token: GoogleToken,
        val account: PomegranateAccount,
    )

    /**
     * First half of the login: Google popup + account existence check. When an account
     * exists its operators/threshold are fixed server-side; otherwise the UI runs its
     * setup step and passes the chosen config to [finishLogin].
     */
    suspend fun startLogin(
        centralUrl: String = PomegranateConfig.CENTRAL_URL,
        onStatus: (PomegranateStatus) -> Unit,
    ): StartedLogin {
        val central = normalizePomegranateOrigin(centralUrl)
        onStatus(PomegranateStatus.WaitingForGoogle)
        val token = authenticateWithGoogle(central)
        onStatus(PomegranateStatus.Checking)
        return StartedLogin(central, token, getAccount(central, token) != null)
    }

    /**
     * Second half: creates the account when needed (key sharded across [config]'s
     * operators, or the defaults when the caller has no setup step), ensures a signing
     * profile, and returns the bunker URL to log in with plus the central origin to
     * persist on the account. Opens no popup.
     */
    suspend fun finishLogin(
        started: StartedLogin,
        config: PomegranateAccountConfig? = null,
        onStatus: (PomegranateStatus) -> Unit,
    ): LoginOutcome {
        if (!started.hasAccount) {
            onStatus(PomegranateStatus.Creating)
            createAccount(started.central, started.token, config)
        }
        var profiles = listProfiles(started.central, started.token)
        if (profiles.isEmpty()) {
            profiles = listOf(createProfile(started.central, started.token, "default"))
        }
        return LoginOutcome(bunkerUrl(started.central, profiles.first()), started.central)
    }

    /**
     * Export-nsec entry: authenticates with Google and returns the account (operators +
     * threshold) after verifying it matches the locally active pubkey, so signing in
     * with the wrong Google account fails up front instead of recovering another key.
     */
    suspend fun startRecovery(
        centralUrl: String,
        expectedPubkey: String,
    ): Recovery {
        val central = normalizePomegranateOrigin(centralUrl)
        val token = authenticateWithGoogle(central)
        val account =
            getAccount(central, token)
                ?: throw Exception("No pomegranate account found for this Google login")
        if (account.pubkey != expectedPubkey) throw PomegranatePubkeyMismatchException()
        return Recovery(token, account)
    }

    /**
     * Probes each operator's registration endpoint with an empty body: a live one answers
     * (rejecting the unsigned payload), a dead host fails to connect or times out. Run
     * before creating an account so an operator that is down is named up front instead of
     * surfacing as a half-finished registration.
     */
    suspend fun checkOperators(urls: List<String>): List<OperatorHealth> = coroutineScope {
        urls
            .map { url ->
                async {
                    val origin = runCatching { normalizePomegranateOrigin(url) }.getOrNull()
                    if (origin == null) {
                        OperatorHealth(url, reachable = false, detail = "Invalid URL")
                    } else {
                        probeOperator(url, origin)
                    }
                }
            }.awaitAll()
    }

    private suspend fun probeOperator(
        url: String,
        origin: String,
    ): OperatorHealth = withTimeoutOrNull(OPERATOR_PROBE_TIMEOUT_MS) {
        try {
            http.post("$origin/po/register") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            OperatorHealth(url, reachable = true, detail = null)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            OperatorHealth(url, reachable = false, detail = t.message?.take(120) ?: "No response")
        }
    } ?: OperatorHealth(url, reachable = false, detail = "Timed out")

    /** Recovers one shard via the operator's Google recovery popup. User gesture required. */
    suspend fun recoverShard(operator: PomegranateOperator): String {
        val origin = normalizePomegranateOrigin(operator.url)
        val shard = PomegranatePopups.awaitShardFromPopup("$origin/po/recover/google", origin)
        if (!shard.startsWith(operator.pubshard)) {
            throw Exception("Recovered shard does not match the operator")
        }
        return shard
    }

    /** Aggregates threshold-many shards back into the key hex, verified against the account pubkey. */
    fun aggregateKeyHex(
        shardHexes: List<String>,
        expectedPubkey: String,
    ): String {
        val secretHex = PomegranateDealer.aggregate(shardHexes)
        val derived = Crypto.getPublicKeyXOnly(secretHex.hexToByteArray()).toHexString()
        if (derived != expectedPubkey) throw Exception("Recovered key does not match the account")
        return secretHex
    }

    /**
     * Unlinks the account from the central signer (DELETE /account). The key still
     * exists; the account stays usable via an exported nsec. Verifies the Google
     * account maps to [expectedPubkey] before deleting.
     */
    suspend fun disconnectAccount(
        centralUrl: String,
        expectedPubkey: String,
    ) {
        val central = normalizePomegranateOrigin(centralUrl)
        val token = authenticateWithGoogle(central)
        val account = getAccount(central, token)
        if (account == null || account.pubkey != expectedPubkey) {
            throw PomegranatePubkeyMismatchException()
        }
        val res = http.delete("$central/account") { header("Authorization", "Token ${token.raw}") }
        if (!res.status.isSuccess()) throw Exception("Account deletion failed")
    }

    // --- internal -------------------------------------------------------------

    private suspend fun authenticateWithGoogle(central: String): GoogleToken {
        nativeGoogleToken(central)?.let { return it }
        val raw = PomegranatePopups.awaitTokenFromPopup("$central/login/google", central)
        return decodeGoogleToken(raw)
    }

    /**
     * Browserless sign-in: the platform account picker mints a Google ID token and the central
     * exchanges it for its own token at `POST /login/google/android`, skipping the OAuth redirect
     * dance entirely. Null on any platform or configuration where that is not possible, so the
     * caller opens the browser flow instead; a user-dismissed picker propagates as a cancel.
     */
    private suspend fun nativeGoogleToken(central: String): GoogleToken? {
        if (!PomegranateNativeGoogle.isAvailable) return null
        val idToken = PomegranateNativeGoogle.requestIdToken(PomegranateConfig.GOOGLE_WEB_CLIENT_ID) ?: return null
        val body =
            try {
                val res =
                    http.post("$central/login/google/android") {
                        contentType(ContentType.Application.Json)
                        setBody(buildJsonObject { put("id_token", idToken) }.toString())
                    }
                if (!res.status.isSuccess()) return null
                res.bodyAsText()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return null
            }
        val raw = parseAndroidLoginToken(body) ?: return null
        return try {
            decodeGoogleToken(raw)
        } catch (_: Exception) {
            null
        }
    }

    /** GET /account — the account, or null when this Google login has none yet. */
    private suspend fun getAccount(
        central: String,
        token: GoogleToken,
    ): PomegranateAccount? {
        val res = http.get("$central/account") { header("Authorization", "Token ${token.raw}") }
        if (res.status == HttpStatusCode.Unauthorized) {
            throw Exception("Google session expired, please sign in again")
        }
        if (!res.status.isSuccess()) return null
        return try {
            json.decodeFromString<PomegranateAccount>(res.bodyAsText()).takeIf { it.pubkey.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Creates a new account: shards [config]'s key (a fresh one when absent) via the
     * trusted dealer and registers with the central server (kind 20445) and every
     * operator (kind 20444). The key signs only these registration events here and is
     * then dropped — afterwards it exists whole only in the backup the user saved.
     */
    @OptIn(ExperimentalUuidApi::class)
    private suspend fun createAccount(
        central: String,
        token: GoogleToken,
        config: PomegranateAccountConfig?,
    ) {
        val operators = (config?.operators ?: PomegranateConfig.OPERATOR_URLS).map { normalizePomegranateOrigin(it) }
        check(operators.size >= PomegranateConfig.MIN_OPERATORS) {
            "At least ${PomegranateConfig.MIN_OPERATORS} operators are required"
        }
        val threshold = config?.threshold ?: PomegranateConfig.defaultThreshold(operators.size)
        check(threshold in 1..operators.size) { "Invalid signing threshold" }
        val session = Uuid.random().toString()

        // Key derivation, the trusted dealer and every signature are CPU-bound and none of
        // them suspend: left on the caller's dispatcher they run on the Android main thread,
        // which froze the app long enough for a second tap to start a second registration.
        val (keyPair, shards, regEvent) =
            withContext(Dispatchers.Default) {
                val pair = config?.privateKeyHex?.let { KeyPair.fromPrivateKeyHex(it) } ?: KeyPair.generate()
                val dealt = PomegranateDealer.deal(pair.privateKeyHex, threshold, operators.size)
                val event =
                    Event(
                        pubkey = pair.publicKeyHex,
                        createdAt = epochSeconds(),
                        kind = KIND_ACCOUNT_REGISTRATION,
                        tags =
                        buildList {
                            add(listOf("threshold", threshold.toString()))
                            operators.forEachIndexed { i, op -> add(listOf("operator", op, dealt[i].pubShardHex)) }
                        },
                        content = "",
                    ).sign(pair)
                Triple(pair, dealt, event)
            }
        val regRes =
            http.post("$central/register") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Token ${token.raw}")
                header("X-Pomegranate-Session", session)
                setBody(regEvent.toJsonString())
            }
        // Carry the server's own words: "registration failed" alone left a re-register
        // after a disconnect undiagnosable.
        if (regRes.status == HttpStatusCode.Conflict) {
            throw Exception(
                "The central server is already registering an account for this Google login. " +
                    "Wait a few seconds and try again.",
            )
        }
        if (regRes.status.value != 200) {
            throw Exception("Central server registration failed (${regRes.status.value}): ${regRes.errorDetail()}")
        }

        // Operators in parallel; a few may fail — the account works as long as at least
        // `threshold` of them hold their shard. Each attempt is time-boxed: an operator
        // whose host hangs (no TCP reset, no response) would otherwise stall the whole
        // registration behind awaitAll.
        val results =
            coroutineScope {
                operators
                    .mapIndexed { i, operator ->
                        async {
                            val event =
                                withContext(Dispatchers.Default) {
                                    Event(
                                        pubkey = keyPair.publicKeyHex,
                                        createdAt = epochSeconds(),
                                        kind = KIND_OPERATOR_REGISTRATION,
                                        tags = listOf(listOf("central", central), listOf("email", token.email)),
                                        content = shards[i].shardHex,
                                    ).sign(keyPair)
                                }
                            val ok =
                                withTimeoutOrNull(OPERATOR_REGISTER_TIMEOUT_MS) {
                                    try {
                                        http
                                            .post("$operator/po/register") {
                                                contentType(ContentType.Application.Json)
                                                header("X-Pomegranate-Operator-Token", operatorToken(session, operator))
                                                setBody(event.toJsonString())
                                            }.status.isSuccess()
                                    } catch (c: CancellationException) {
                                        throw c
                                    } catch (_: Throwable) {
                                        false
                                    }
                                } ?: false
                            operator to ok
                        }
                    }.awaitAll()
            }
        val registered = results.count { it.second }
        if (registered < threshold) {
            // Roll the central registration back. Left in place it would be found by the
            // next sign-in as an existing account whose key can never reach a signing
            // quorum, so login would succeed and every signature then fail.
            runCatching { http.delete("$central/account") { header("Authorization", "Token ${token.raw}") } }
            val unreachable = results.filterNot { it.second }.joinToString { pomegranateOperatorLabel(it.first) }
            throw Exception(
                "Only $registered of $threshold operators accepted a shard, so the account was not created. " +
                    "Not accepted by: $unreachable. Remove them under Advanced options and try again.",
            )
        }
    }

    /** GET /profiles — the NIP-46 signing profiles owned by the account. */
    private suspend fun listProfiles(
        central: String,
        token: GoogleToken,
    ): List<PomegranateProfile> {
        val res = http.get("$central/profiles") { header("Authorization", "Token ${token.raw}") }
        if (!res.status.isSuccess()) throw Exception("Failed to load signing profiles")
        return try {
            json.decodeFromString<List<PomegranateProfile>>(res.bodyAsText())
        } catch (_: Exception) {
            throw Exception("Failed to load signing profiles")
        }
    }

    /** POST /profiles — creates a signing profile and returns it. */
    private suspend fun createProfile(
        central: String,
        token: GoogleToken,
        name: String,
    ): PomegranateProfile {
        val res =
            http.post("$central/profiles") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Token ${token.raw}")
                setBody("""{"name":"$name"}""")
            }
        if (!res.status.isSuccess()) {
            throw Exception("Signing profile creation failed (${res.status.value}): ${res.errorDetail()}")
        }
        val profile =
            try {
                json.decodeFromString<PomegranateProfile>(res.bodyAsText())
            } catch (_: Exception) {
                null
            }
        if (profile == null || profile.handlerPubkey.length != 64) {
            throw Exception("Signing profile creation did not complete")
        }
        return profile
    }

    private fun bunkerUrl(
        central: String,
        profile: PomegranateProfile,
    ): String {
        val relay = central.replaceFirst("http", "ws")
        return "bunker://${profile.handlerPubkey}?relay=${relay.encodeURLParameter()}"
    }

    private fun operatorToken(
        session: String,
        operatorUrl: String,
    ): String = Crypto.sha256("$session:$operatorUrl").toHexString()

    /** Pulls the central token out of the `POST /login/google/android` reply (`{"token": "..."}`). */
    internal fun parseAndroidLoginToken(body: String): String? = try {
        (json.parseToJsonElement(body).jsonObject["token"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    /** Decodes the base64 token the central popup posts; rejects expired/garbled ones. */
    @OptIn(ExperimentalEncodingApi::class)
    internal fun decodeGoogleToken(raw: String): GoogleToken {
        val parsed =
            try {
                val decoded = Base64.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(raw).decodeToString()
                json.parseToJsonElement(decoded).jsonObject
            } catch (_: Exception) {
                throw Exception("Invalid Google sign-in token")
            }
        val createdAtMillis =
            (parsed["created_at"] as? JsonPrimitive)?.longOrNull?.times(1000)
                ?: throw Exception("Invalid Google sign-in token")
        if (epochMillis() - createdAtMillis > TOKEN_MAX_AGE_MS) {
            throw Exception("Google sign-in token expired, please try again")
        }
        val email =
            (parsed["tags"] as? JsonArray)?.firstNotNullOfOrNull { tag ->
                val arr = tag as? JsonArray ?: return@firstNotNullOfOrNull null
                if (arr.size > 1 && (arr[0] as? JsonPrimitive)?.content == "email") {
                    (arr[1] as? JsonPrimitive)?.content
                } else {
                    null
                }
            } ?: ""
        return GoogleToken(raw, email, createdAtMillis)
    }

    private companion object {
        const val KIND_ACCOUNT_REGISTRATION = 20445
        const val KIND_OPERATOR_REGISTRATION = 20444
        const val TOKEN_MAX_AGE_MS = 24 * 60 * 60 * 1000L

        /** Per-operator budget for accepting a shard; a dead host must not hold up the rest. */
        const val OPERATOR_REGISTER_TIMEOUT_MS = 20_000L

        /** Health probes run while the user reads the setup step, so they give up sooner. */
        const val OPERATOR_PROBE_TIMEOUT_MS = 8_000L
    }
}

/** The server's error text, trimmed to something a UI line can carry. */
private suspend fun HttpResponse.errorDetail(): String = try {
    bodyAsText().trim().take(200).ifBlank { status.description }
} catch (_: Exception) {
    status.description
}

/** Normalizes a central/operator URL to its origin (scheme://host[:port], no path). */
internal fun normalizePomegranateOrigin(input: String): String {
    var url = input.trim().trimEnd('/')
    if (!url.startsWith("http")) {
        url = "http" + (if (url.startsWith("localhost")) "" else "s") + "://" + url
    }
    val u = Url(url)
    return buildString {
        append(u.protocol.name).append("://").append(u.host)
        if (u.specifiedPort != 0 && u.specifiedPort != u.protocol.defaultPort) {
            append(':').append(u.specifiedPort)
        }
    }
}
