package org.nostr.nostrord.network.livekit

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.nostr.nostrord.network.createNip11HttpClient
import org.nostr.nostrord.nostr.relayUrlToHttps
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result

/** LiveKit join credentials issued by a NIP-29 relay for one group. */
data class LiveKitCredentials(
    val token: String,
    val serverUrl: String,
)

/** Base path both NIP-29 LiveKit endpoints hang off. */
private const val WELL_KNOWN = "/.well-known/nip29/livekit"

/** Strips the trailing slash so the joined path never doubles it. */
private fun httpBase(relayUrl: String): String = relayUrlToHttps(relayUrl).trimEnd('/')

/** Token endpoint for [groupId] on [relayUrl]. Also the `u` tag of the NIP-98 auth event. */
fun liveKitTokenUrl(relayUrl: String, groupId: String): String = "${httpBase(relayUrl)}$WELL_KNOWN/$groupId"

/** Support-probe endpoint for [relayUrl]. */
fun liveKitSupportUrl(relayUrl: String): String = "${httpBase(relayUrl)}$WELL_KNOWN"

/**
 * NIP-29 AV spaces: relay-hosted LiveKit rooms.
 *
 * The relay both advertises support and mints the room JWT, enforcing group access control at
 * issue time. [buildAuthHeader] supplies the NIP-98 header (kind 27235) for the token call;
 * it returns null when nobody is signed in.
 *
 * https://github.com/nostr-protocol/nips/blob/master/29.md
 */
class LiveKitTokenClient(
    private val buildAuthHeader: suspend (url: String, method: String) -> String?,
) {
    /** Probe results per relay URL. Support is a static relay property for the session. */
    private val supportCache = mutableMapOf<String, Boolean>()

    /**
     * Whether [relayUrl] hosts LiveKit rooms. The spec has the relay answer 204; a 200 with a
     * non-HTML body also counts, for deployments that answer empty-200.
     *
     * An HTML 200 is explicitly NOT support: most relays serve a catch-all landing page for
     * unknown paths, which would otherwise read as every relay having AV.
     *
     * Unauthenticated on purpose: it is a capability probe, not a room request.
     */
    suspend fun relaySupportsAv(relayUrl: String): Boolean {
        supportCache[relayUrl]?.let { return it }
        val client = createNip11HttpClient()
        val supported = try {
            val response = client.get(liveKitSupportUrl(relayUrl))
            val contentType = response.headers[HttpHeaders.ContentType] ?: ""
            response.status.isSuccess() && !contentType.contains("text/html", ignoreCase = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        } finally {
            client.close()
        }
        supportCache[relayUrl] = supported
        return supported
    }

    /** Forget the cached probe for [relayUrl] so the next call re-checks. */
    fun invalidateSupport(relayUrl: String) {
        supportCache.remove(relayUrl)
    }

    /**
     * Fetch the LiveKit JWT and server URL for [groupId] on [relayUrl].
     *
     * The response field names are not pinned by the spec and differ between relay
     * implementations, so both the LiveKit-native (`participant_token` / `server_url`) and the
     * short (`token` / `url`) spellings are accepted.
     */
    suspend fun fetchCredentials(relayUrl: String, groupId: String): Result<LiveKitCredentials> {
        val url = liveKitTokenUrl(relayUrl, groupId)
        val auth = buildAuthHeader(url, "GET")
            ?: return Result.Error(AppError.Auth.NotAuthenticated)

        val client = createNip11HttpClient()
        return try {
            val response = client.get(url) { header(HttpHeaders.Authorization, auth) }
            if (!response.status.isSuccess()) {
                // The reason lives in the body: relays distinguish "not allowed to access
                // livekit for this group" (not a member) from "livekit not enabled for this
                // group", and the status code alone cannot tell a user which one they hit.
                val reason = runCatching { response.bodyAsText().trim() }.getOrNull()
                    ?.takeIf { it.isNotBlank() && !it.startsWith("<") }
                    ?.take(200)
                return Result.Error(
                    AppError.Unknown(reason ?: "$relayUrl refused the LiveKit token (${response.status.value})"),
                )
            }
            parseCredentials(response.bodyAsText())
                ?.let { Result.Success(it) }
                ?: Result.Error(AppError.Unknown("$relayUrl returned a malformed LiveKit token response"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(AppError.Network.ConnectionFailed(relayUrl, e))
        } finally {
            client.close()
        }
    }
}

/** Reads the token/URL pair out of a relay's token response. Null when either is missing. */
internal fun parseCredentials(body: String): LiveKitCredentials? = try {
    val obj = Json.parseToJsonElement(body).jsonObject
    fun field(vararg names: String): String? = names.firstNotNullOfOrNull {
        obj[it]?.jsonPrimitive?.contentOrNull?.takeIf { value -> value.isNotBlank() }
    }
    val token = field("participant_token", "token")
    val serverUrl = field("server_url", "serverUrl", "url")
    if (token != null && serverUrl != null) LiveKitCredentials(token, serverUrl) else null
} catch (e: Exception) {
    null
}
