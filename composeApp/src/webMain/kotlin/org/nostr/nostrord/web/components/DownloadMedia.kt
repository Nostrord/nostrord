package org.nostr.nostrord.web.components

import js.objects.unsafeJso
import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.upload.RECOMMENDED_BLOSSOM_SERVERS
import org.nostr.nostrord.nostr.Crypto
import org.nostr.nostrord.nostr.toHexString
import org.nostr.nostrord.utils.blossomHashFromUrl
import org.nostr.nostrord.utils.blossomMirrorUrls
import org.nostr.nostrord.utils.downloadFileName
import web.blob.Blob
import web.blob.BlobPart
import web.blob.BlobPropertyBag
import web.url.URL
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Saves [url] to the browser's downloads and returns the name it was saved under, or null when
 * the file could only be opened. The name comes from the original url plus the Content-Type the
 * bytes arrive with, so a host that stores every blob under one generic object name can't dictate
 * a file name no app opens. [fallbackBase] names a url whose path ends in a slash.
 *
 * The page can only name a download it was allowed to read, and reading needs CORS headers on
 * every hop of the redirect chain: primal's blossom endpoint sends them but redirects to an R2
 * bucket that doesn't. Blossom addresses a blob by its sha256, so the same file usually sits on
 * the other servers the uploader mirrored to; those are tried next and the bytes are checked
 * against the hash in the url before anything is saved. Opening the file is the last resort, and
 * then the browser names it after whatever object the host serves.
 */
suspend fun downloadMedia(
    url: String,
    fallbackBase: String = "file",
): String? {
    fetchForDownload(url)?.let { (bytes, contentType) ->
        return saveMediaBytes(bytes, downloadFileName(url, contentType, fallbackBase), contentType)
    }

    val hash = blossomHashFromUrl(url)
    if (hash != null) {
        for (mirror in blossomMirrorUrls(url, mirrorServers(), MIRROR_LIMIT)) {
            val (bytes, contentType) = fetchForDownload(mirror) ?: continue
            if (Crypto.sha256(bytes).toHexString() != hash) continue
            return saveMediaBytes(bytes, downloadFileName(url, contentType, fallbackBase), contentType)
        }
    }

    AppModule.postSystemMessage("This media host blocks saving from the app. Opening the file in a new tab.")
    window.asDynamic().open(url, "_blank")
    return null
}

/** How many mirrors one save may ask for the blob, so a miss doesn't fan the hash out everywhere. */
private const val MIRROR_LIMIT = 4

/** Bytes + Content-Type, or null when the fetch fails: offline, an error status, or a blocked hop. */
private suspend fun fetchForDownload(url: String): Pair<ByteArray, String?>? = suspendCoroutine { continuation ->
    var settled = false
    val finish = { result: Pair<ByteArray, String?>? ->
        if (!settled) {
            settled = true
            continuation.resume(result)
        }
    }
    window.asDynamic().fetch(url)
        .then { response: dynamic ->
            val contentType = response.headers.get("content-type").unsafeCast<String?>()
            if (response.ok != true) {
                finish(null)
            } else {
                response.arrayBuffer().then(
                    { buffer: dynamic ->
                        val array = Int8Array(buffer.unsafeCast<ArrayBuffer>())
                        finish(ByteArray(array.length) { array[it] } to contentType)
                    },
                    { _: dynamic -> finish(null) },
                )
            }
            Unit
        }
        .catch { _: dynamic ->
            finish(null)
            Unit
        }
}

/** Hands [bytes] to the browser as a download named [fileName], labeled with [mimeType]. */
fun saveMediaBytes(
    bytes: ByteArray,
    fileName: String,
    mimeType: String?,
): String {
    val options = unsafeJso<BlobPropertyBag> { type = mimeType ?: "application/octet-stream" }
    // A Kotlin/JS ByteArray is an Int8Array, which is already a valid BlobPart.
    val objectUrl = URL.createObjectURL(Blob(arrayOf(bytes.unsafeCast<BlobPart>()), options))
    val anchor = document.createElement("a")
    val a = anchor.asDynamic()
    a.href = objectUrl
    a.download = fileName
    document.body?.appendChild(anchor)
    a.click()
    anchor.remove()
    URL.revokeObjectURL(objectUrl)
    return fileName
}

/** The servers this account uploads to, plus the free ones, as candidates holding the same blob. */
private fun mirrorServers(): List<String> = AppModule.mediaServerSettings.blossomServers.value + RECOMMENDED_BLOSSOM_SERVERS
