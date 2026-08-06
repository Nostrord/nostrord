package org.nostr.nostrord.nostr

import org.nostr.nostrord.utils.epochMillis
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * NIP-98 HTTP Auth: a kind 27235 event, signed by the caller, carried in the
 * `Authorization` header so an HTTP endpoint can attribute the request to a pubkey.
 *
 * https://github.com/nostr-protocol/nips/blob/master/98.md
 */
object Nip98 {
    const val KIND = 27235

    /**
     * Unsigned kind 27235 event for [method] on [url]. `u` must carry the exact URL the
     * request goes to, including query string: verifiers compare it byte for byte.
     */
    fun buildAuthEvent(pubkey: String, url: String, method: String): Event = Event(
        pubkey = pubkey,
        createdAt = epochMillis() / 1000,
        kind = KIND,
        tags = listOf(listOf("u", url), listOf("method", method.uppercase())),
        content = "",
    )

    /** Header value for an already-signed kind 27235 event. */
    @OptIn(ExperimentalEncodingApi::class)
    fun encodeAuthHeader(signed: Event): String = "Nostr " + Base64.encode(signed.toJsonObject().toString().encodeToByteArray())

    /**
     * Build and sign the header in one step. [sign] failing (bunker timeout, extension
     * denial) propagates so callers can surface the real cause.
     */
    suspend fun buildAuthHeader(
        pubkey: String,
        url: String,
        method: String,
        sign: suspend (Event) -> Event,
    ): String = encodeAuthHeader(sign(buildAuthEvent(pubkey, url, method)))
}
