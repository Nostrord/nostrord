package org.nostr.nostrord.network.managers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.nostr.Nip17
import org.nostr.nostrord.utils.epochSeconds
import org.nostr.nostrord.utils.timestampToDateTime

/**
 * The DM backup file: one decrypted rumor per line (JSONL).
 *
 * Byte-compatible with Jumble's export (`jumble-dm-<date>.jsonl`), so a file written by either
 * client restores in the other. Nothing is encrypted here: the file holds the conversations in
 * the clear, which is why the UI has to say so before writing it.
 */
object DmHistoryFile {
    const val MIME_TYPE = "application/jsonl"

    /** Kinds a backup carries: chat, file message, reaction. Anything else is a skipped line. */
    private val IMPORTABLE_KINDS = setOf(Nip17.KIND_CHAT, KIND_FILE_MESSAGE, KIND_REACTION)

    private const val KIND_FILE_MESSAGE = 15
    private const val KIND_REACTION = 7

    data class Parsed(
        val rumors: List<Event>,
        /** Lines that were not a valid rumor, reported so a partial restore is never silent. */
        val skipped: Int,
    )

    /** `nostrord-dm-YYYY-MM-DD.jsonl`, mirroring Jumble's naming. */
    fun fileName(): String {
        val now = timestampToDateTime(epochSeconds())
        val month = now.month.toString().padStart(2, '0')
        val day = now.day.toString().padStart(2, '0')
        return "nostrord-dm-${now.year}-$month-$day.jsonl"
    }

    fun render(rumorJson: List<String>): String = rumorJson.joinToString("\n")

    /**
     * Read a backup. Malformed and out-of-scope lines are counted rather than aborting the run:
     * a file with one bad line still restores everything else, which is the point of a backup.
     */
    fun parse(text: String): Parsed {
        val rumors = mutableListOf<Event>()
        var skipped = 0
        text.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val rumor = parseLine(line)
            if (rumor == null) skipped++ else rumors.add(rumor)
        }
        return Parsed(rumors, skipped)
    }

    private fun parseLine(line: String): Event? {
        val obj = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
        val kind = obj["kind"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
        if (kind !in IMPORTABLE_KINDS) return null
        val id = obj["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
        val pubkey = obj["pubkey"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
        val createdAt = obj["created_at"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null
        val tags = obj["tags"] as? JsonArray ?: return null
        // Present-but-empty is valid content; absent is not.
        val content = obj["content"]?.jsonPrimitive?.content ?: return null
        val parsedTags =
            tags.mapNotNull { tag ->
                (tag as? JsonArray)?.map { it.jsonPrimitive.content }
            }
        return Event(
            id = id,
            pubkey = pubkey,
            createdAt = createdAt,
            kind = kind,
            tags = parsedTags,
            content = content,
        )
    }
}
