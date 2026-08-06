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
 *
 * [privateEntries] (`[relayUrl, groupId]`) and [privateOnlyRelays] are what the last successful
 * decrypt found inside [content]. They persist so a session that cannot read the section — a
 * bunker that is offline or refuses — still knows to keep those groups out of the public tags
 * instead of publishing them in the clear.
 */
@Serializable
data class Kind10009Baseline(
    val createdAt: Long = 0L,
    val content: String = "",
    val foreignTags: List<List<String>> = emptyList(),
    val privateEntries: List<List<String>> = emptyList(),
    val privateOnlyRelays: List<String> = emptyList(),
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
 * The private section of a kind:10009: [entries] as `group` tags, plus every non-`group` tag
 * [previous] held (other clients' private relays and unmodelled entries), kept verbatim.
 */
fun rebuildPrivateGroupTags(
    previous: List<List<String>>,
    entries: List<Pair<String, String>>,
): List<List<String>> = entries.map { (relayUrl, groupId) -> listOf("group", groupId, relayUrl) } +
    previous.filterNot { it.firstOrNull() == "group" }

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

/** What a kind:10009 publish writes in the clear, and what goes into the encrypted section. */
data class Kind10009Publish(
    val tags: List<List<String>>,
    val privateOrder: List<Pair<String, String>>,
)

/**
 * Split the joined list into the public tags and the private entries of one publish.
 *
 * [joinedOrder] is every joined (relay, group) in rail order; [privateEntries] and
 * [privateOnlyRelays] are what the user keeps out of the clear. Nothing in either of those
 * reaches [Kind10009Publish.tags] — that is the invariant that keeps a private group private.
 */
fun buildKind10009Publish(
    joinedOrder: List<Pair<String, String>>,
    privateEntries: Set<Pair<String, String>>,
    nip29Relays: List<String>,
    privateOnlyRelays: Set<String>,
    foreignTags: List<List<String>>,
): Kind10009Publish = Kind10009Publish(
    tags =
    kind10009Tags(
        order = joinedOrder.filterNot { it in privateEntries },
        nip29Relays = nip29Relays.filterNot { it in privateOnlyRelays },
        foreignTags = foreignTags,
    ),
    privateOrder = joinedOrder.filter { it in privateEntries },
)
