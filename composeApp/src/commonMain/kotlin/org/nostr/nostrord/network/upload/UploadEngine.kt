package org.nostr.nostrord.network.upload

import io.ktor.client.*
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.*
import io.ktor.client.request.headers
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.ByteArrayContent

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
                    key = "fileToUpload",
                    value = bytes,
                    headers =
                    Headers.build {
                        append(
                            HttpHeaders.ContentDisposition,
                            "form-data; name=\"fileToUpload\"; filename=\"$filename\"",
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
