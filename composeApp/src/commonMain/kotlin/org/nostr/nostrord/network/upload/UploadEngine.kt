package org.nostr.nostrord.network.upload

import io.ktor.client.*
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.*
import io.ktor.client.request.head
import io.ktor.client.request.headers
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.ByteArrayContent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal expect suspend fun executeUpload(
    url: String,
    bytes: ByteArray,
    filename: String,
    mimeType: String,
    authHeader: String,
): Pair<Int, String>

/** Blossom `PUT /upload`: the raw blob is the body, no multipart wrapper. */
internal expect suspend fun executePutUpload(
    url: String,
    bytes: ByteArray,
    mimeType: String,
    authHeader: String,
    sha256Hex: String,
): Pair<Int, String>

internal suspend fun ktorExecuteUpload(
    client: HttpClient,
    url: String,
    bytes: ByteArray,
    filename: String,
    mimeType: String,
    authHeader: String,
): Pair<Int, String> {
    val response =
        client.submitFormWithBinaryData(
            url = url,
            formData =
            formData {
                append(
                    // NIP-96 names the part "file"; nostr.build's own v2 endpoint accepts it too.
                    key = "file",
                    value = bytes,
                    headers =
                    Headers.build {
                        append(
                            HttpHeaders.ContentDisposition,
                            "form-data; name=\"file\"; filename=\"$filename\"",
                        )
                        append(HttpHeaders.ContentType, mimeType)
                    },
                )
            },
        ) {
            headers.append(HttpHeaders.Authorization, authHeader)
            timeout {
                requestTimeoutMillis = 120_000
                socketTimeoutMillis = 60_000
            }
        }
    return Pair(response.status.value, response.bodyAsText())
}

internal suspend fun ktorExecutePutUpload(
    client: HttpClient,
    url: String,
    bytes: ByteArray,
    mimeType: String,
    authHeader: String,
    sha256Hex: String,
): Pair<Int, String> {
    val contentType = runCatching { ContentType.parse(mimeType) }.getOrDefault(ContentType.Application.OctetStream)
    val response =
        client.put(url) {
            headers {
                append(HttpHeaders.Authorization, authHeader)
                // BUD-02: lets the server reject a corrupted body with 409 instead of storing it.
                append("X-SHA-256", sha256Hex)
            }
            // ByteArrayContent carries Content-Length, which Blossom servers require.
            setBody(ByteArrayContent(bytes, contentType))
            timeout {
                requestTimeoutMillis = 120_000
                socketTimeoutMillis = 60_000
            }
        }
    val body = response.bodyAsText()
    // Blossom errors carry the human-readable cause in X-Reason, often with an empty body.
    val reason = response.headers["X-Reason"]
    return Pair(response.status.value, if (body.isBlank() && !reason.isNullOrBlank()) reason else body)
}

/**
 * BUD-06 `HEAD /upload`: asks whether the server would take this blob before the body is
 * sent, so a 413 costs one round-trip instead of 20 MB. Returns the status, or null when
 * the request itself fails (treated as "unknown", never as a rejection).
 */
internal suspend fun ktorPreflightUpload(
    url: String,
    sizeBytes: Long,
    mimeType: String,
    authHeader: String,
    sha256Hex: String,
): Int? = runCatching {
    UploadClient.client.head(url) {
        headers {
            append(HttpHeaders.Authorization, authHeader)
            append("X-SHA-256", sha256Hex)
            append("X-Content-Length", sizeBytes.toString())
            if (mimeType.isNotBlank()) append("X-Content-Type", mimeType)
        }
    }.status.value
}.getOrNull()

/**
 * BUD-04 `PUT /mirror`: asks a server to fetch a blob it doesn't have from a URL that does,
 * so a copy lands there without re-uploading the bytes. Returns true when it took the blob.
 */
internal suspend fun ktorMirrorBlob(
    serverUrl: String,
    blobUrl: String,
    authHeader: String,
): Boolean = runCatching {
    val response =
        UploadClient.client.put(serverUrl.trimEnd('/') + "/mirror") {
            headers { append(HttpHeaders.Authorization, authHeader) }
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("url", blobUrl) }.toString())
            timeout {
                requestTimeoutMillis = 60_000
                socketTimeoutMillis = 30_000
            }
        }
    response.status.value in 200..299
}.getOrDefault(false)
