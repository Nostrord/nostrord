package org.nostr.nostrord.auth.pomegranate

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Pomegranate is the "Login with Google" flow built on promenade (fiatjaf's FROST
 * threshold signer): the Nostr key is split into shards held by independent operators,
 * signing needs a threshold of them, and after setup the client talks plain NIP-46 to a
 * central coordinator. Web-only at runtime (Google popups + a JS-only dealer lib);
 * native targets never show the entry point.
 */
object PomegranateConfig {
    const val ENABLED = true

    /** Central coordinator: verifies the Google sign-in and relays NIP-46 signing to the operators. */
    const val CENTRAL_URL = "https://auth.njump.me"

    /**
     * Recommended shard operators (the public promenade instances). Any of them can be
     * offline at a given moment, so the setup step probes them and registration tolerates
     * the ones that fail.
     */
    val OPERATOR_URLS =
        listOf(
            "https://po.jumble.social",
            "https://po.coracle.social",
            "https://po.njump.me",
            "https://po.f7z.io",
            "https://po.nostrver.se",
        )

    /** With one operator the whole key would sit on a single server, so two is the floor. */
    const val MIN_OPERATORS = 2

    /**
     * ceil(n * 7/12): a little over half, so signing tolerates a few operators being
     * offline while no small subset can sign on its own.
     */
    fun defaultThreshold(operatorCount: Int): Int = (operatorCount * 7 + 11) / 12
}

/**
 * What a new account is created with, chosen by the user in the setup step: the key they
 * just backed up, the operators that hold its shards, and how many must sign.
 */
data class PomegranateAccountConfig(
    val operators: List<String>,
    val threshold: Int,
    val privateKeyHex: String,
)

/** Host of an operator URL, for display (`https://po.f7z.io` -> `po.f7z.io`). */
fun pomegranateOperatorLabel(url: String): String = url.trim().trimEnd('/').substringAfter("://")

/** Normalized operator origin, or null when [raw] is not a usable URL. */
fun pomegranateOperatorUrlOrNull(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
    val origin =
        try {
            normalizePomegranateOrigin(trimmed)
        } catch (_: Exception) {
            return null
        }
    val host = origin.substringAfter("://").substringBefore(':')
    val validHost = host == "localhost" || (host.contains('.') && !host.startsWith('.') && !host.endsWith('.'))
    return origin.takeIf { validHost }
}

/** Google auth token minted by the central server's popup; the server honors it for 24h. */
data class GoogleToken(
    val raw: String,
    val email: String,
    val createdAtMillis: Long,
)

@Serializable
data class PomegranateOperator(
    val url: String,
    val pubshard: String,
)

@Serializable
data class PomegranateAccount(
    val pubkey: String = "",
    val email: String = "",
    val operators: List<PomegranateOperator> = emptyList(),
    val threshold: Int = 0,
)

@Serializable
internal data class PomegranateProfile(
    @SerialName("handler_pubkey") val handlerPubkey: String = "",
    val name: String = "",
    val email: String = "",
)

/** One operator's share of the key produced by the trusted dealer, hex-encoded. */
data class PomegranateShard(
    val shardHex: String,
    val pubShardHex: String,
)

/** Login progress reported to the UIs while popups/HTTP round trips run. */
enum class PomegranateStatus { WaitingForGoogle, Checking, Creating, Connecting }

/** The browser blocked `window.open` — usually a popup-blocker setting. */
class PomegranatePopupBlockedException : Exception("Popup was blocked. Allow popups for this site and try again.")

/** The user closed the popup before it posted a result back; the UIs treat it as a silent cancel. */
class PomegranatePopupClosedException : Exception("Popup was closed")

/** The signed-in Google account is linked to a different pubkey than the local account. */
class PomegranatePubkeyMismatchException : Exception("This Google account is linked to a different Nostr account")
