package org.nostr.nostrord.network

import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The message re-serialized as NIP-01 event JSON (id/pubkey/created_at/kind/tags/content;
 * no sig - [NostrGroupClient.NostrMessage] does not carry one). Backs "Copy event JSON" in
 * the chat and thread context menus on both platforms.
 */
fun NostrGroupClient.NostrMessage.toEventJson(): String = buildJsonObject {
    put("id", id)
    put("pubkey", pubkey)
    put("created_at", createdAt)
    put("kind", kind)
    put("content", content)
    putJsonArray("tags") {
        tags.forEach { tag -> addJsonArray { tag.forEach { add(it) } } }
    }
}.toString()
