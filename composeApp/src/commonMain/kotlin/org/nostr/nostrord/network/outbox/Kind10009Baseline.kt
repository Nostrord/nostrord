package org.nostr.nostrord.network.outbox

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * What a kind:10009 publish must carry forward from the version it replaces.
 *
 * kind:10009 is a NIP-51 list: `content` may hold another client's self-encrypted private
 * entries, and other clients may add tags this one does not model. Both belong to the user,
 * not to this client, so a replaceable-event update preserves them verbatim instead of
 * rebuilding the event from scratch (that silently wipes the private list of anyone who
 * uses more than one client).
 *
 * [createdAt] is the version this snapshot came from: only a strictly newer event replaces it.
 */
@Serializable
data class Kind10009Baseline(
    val createdAt: Long = 0L,
    val content: String = "",
    val foreignTags: List<List<String>> = emptyList(),
) {
    companion object {
        val EMPTY = Kind10009Baseline()

        /** Tag names this client owns: it rebuilds them on every publish, so they are not preserved. */
        private val OWNED_TAGS = setOf("group", "r")

        /** Tags of a kind:10009 that some other client put there. Preserved verbatim on publish. */
        fun foreignTagsOf(tags: JsonArray): List<List<String>> = tags.mapNotNull { tag ->
            val values =
                try {
                    tag.jsonArray.map { it.jsonPrimitive.content }
                } catch (_: Exception) {
                    return@mapNotNull null
                }
            val name = values.firstOrNull() ?: return@mapNotNull null
            if (name in OWNED_TAGS) null else values
        }

        /** Snapshot of an own kind:10009 as received from a relay. */
        fun from(event: JsonObject): Kind10009Baseline {
            val tags = event["tags"]?.jsonArray ?: JsonArray(emptyList())
            return Kind10009Baseline(
                createdAt = event["created_at"]?.jsonPrimitive?.longOrNull ?: 0L,
                content = event["content"]?.jsonPrimitive?.content ?: "",
                foreignTags = foreignTagsOf(tags),
            )
        }
    }
}

/**
 * Tags of a kind:10009 publish: this client's own `group` / `r` tags followed by everything
 * another client left in the version being replaced. Inputs are already normalized.
 */
fun kind10009Tags(
    order: List<Pair<String, String>>,
    nip29Relays: List<String>,
    foreignTags: List<List<String>>,
): List<List<String>> {
    val tags = mutableListOf<List<String>>()
    order.forEach { (relayUrl, groupId) -> tags.add(listOf("group", groupId, relayUrl)) }
    nip29Relays.forEach { relayUrl -> tags.add(listOf("r", relayUrl)) }
    tags.addAll(foreignTags)
    return tags
}
