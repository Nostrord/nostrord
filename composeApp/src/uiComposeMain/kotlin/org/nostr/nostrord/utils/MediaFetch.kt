package org.nostr.nostrord.utils

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.nostr.nostrord.network.createHttpClient

/**
 * Fetches [url] for saving to disk: the bytes plus the Content-Type the server labeled them with,
 * or null when the fetch fails. The header is what tells a hashed blob apart from the generic
 * object name its host stores it under, so pass it to [downloadFileName].
 */
internal suspend fun fetchMediaForDownload(url: String): Pair<ByteArray, String?>? {
    val client = createHttpClient()
    return try {
        withContext(Dispatchers.Default) {
            val response = client.get(url)
            val bytes: ByteArray = response.body()
            bytes to response.headers[HttpHeaders.ContentType]
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    } finally {
        client.close()
    }
}
