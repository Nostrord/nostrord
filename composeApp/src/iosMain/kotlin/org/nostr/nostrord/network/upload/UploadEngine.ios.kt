package org.nostr.nostrord.network.upload

internal actual suspend fun executeUpload(
    url: String,
    bytes: ByteArray,
    filename: String,
    mimeType: String,
    authHeader: String,
): Pair<Int, String> = ktorExecuteUpload(NostrBuildUploader.client, url, bytes, filename, mimeType, authHeader)

internal actual suspend fun executePutUpload(
    url: String,
    bytes: ByteArray,
    mimeType: String,
    authHeader: String,
    sha256Hex: String,
): Pair<Int, String> = ktorExecutePutUpload(NostrBuildUploader.client, url, bytes, mimeType, authHeader, sha256Hex)
