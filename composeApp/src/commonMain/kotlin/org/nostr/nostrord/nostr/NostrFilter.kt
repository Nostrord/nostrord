package org.nostr.nostrord.nostr

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A resolved NIP-01 REQ filter.
 *
 * Every field is final: variables and relative timestamps are already expanded. Build one with
 * [Spell.resolve] rather than by hand, so the group-relay routing guard is not bypassed.
 *
 * [tags] keys carry the `#` prefix ("#t", "#h", "#p") to match the wire format.
 */
data class NostrFilter(
    val ids: List<String>? = null,
    val authors: List<String>? = null,
    val kinds: List<Int>? = null,
    val tags: Map<String, List<String>> = emptyMap(),
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null,
    val search: String? = null,
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        ids?.takeIf { it.isNotEmpty() }?.let { list ->
            put("ids", buildJsonArray { list.forEach { add(it) } })
        }
        authors?.takeIf { it.isNotEmpty() }?.let { list ->
            put("authors", buildJsonArray { list.forEach { add(it) } })
        }
        kinds?.takeIf { it.isNotEmpty() }?.let { list ->
            put("kinds", buildJsonArray { list.forEach { add(it) } })
        }
        tags.entries.sortedBy { it.key }.forEach { (key, values) ->
            if (values.isNotEmpty()) {
                put(key, buildJsonArray { values.forEach { add(it) } })
            }
        }
        since?.let { put("since", it) }
        until?.let { put("until", it) }
        limit?.let { put("limit", it) }
        search?.takeIf { it.isNotBlank() }?.let { put("search", it) }
    }

    override fun toString(): String = toJsonObject().toString()
}
