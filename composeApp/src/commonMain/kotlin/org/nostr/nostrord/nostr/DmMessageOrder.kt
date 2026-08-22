package org.nostr.nostrord.nostr

import org.nostr.nostrord.utils.epochMillis

/**
 * Sub-second ordering for private rumors. `created_at` has one-second resolution, so an image
 * and the text sent right after it tie and sort by accident; the millisecond rides in an `ms`
 * tag inside the sealed rumor (a convention other NIP-17 clients share) and sends from one
 * session are stamped strictly increasing without pushing any rumor a whole second ahead.
 */
object DmMessageOrder {
    const val TAG = "ms"

    private var lastMs = 0L

    /** created_at plus the millisecond to put in the [TAG] tag. Monotonic within the session. */
    fun next(nowMs: Long = epochMillis()): Pair<Long, Int> {
        lastMs = maxOf(nowMs, lastMs + 1)
        return (lastMs / 1000) to (lastMs % 1000).toInt()
    }

    /** [tags] with a fresh [TAG], replacing any stale copy. */
    fun withOrderTag(tags: List<List<String>>, millisecond: Int): List<List<String>> = tags.filter { it.firstOrNull() != TAG } + listOf(listOf(TAG, millisecond.toString()))

    /** Millisecond sort key: created_at * 1000 + ms. A missing or invalid tag counts as 0. */
    fun orderKey(createdAt: Long, tags: List<List<String>>): Long = createdAt * 1000 + (tags.firstOrNull { it.firstOrNull() == TAG }?.getOrNull(1)?.let(::validMs) ?: 0)

    fun orderKey(rumor: Event): Long = orderKey(rumor.createdAt, rumor.tags)

    /** Same key off the cached tags json, without parsing the whole array. */
    fun orderKey(createdAt: Long, tagsJson: String): Long = createdAt * 1000 + (CACHED_TAG.find(tagsJson)?.groupValues?.get(1)?.let(::validMs) ?: 0)

    private fun validMs(raw: String): Int? = raw.toIntOrNull()?.takeIf { it in 0..999 }

    private val CACHED_TAG = Regex("""\["ms","(\d{1,3})"\]""")
}
