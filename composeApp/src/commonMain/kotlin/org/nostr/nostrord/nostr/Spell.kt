package org.nostr.nostrord.nostr

import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result

/** Spell event: a portable, shareable saved query. */
const val SPELL_KIND = 777

/** Spellbook: addressable index of pinned spells, scoped per group by its `d` tag. */
const val SPELLBOOK_KIND = 30777

/** Author lists longer than this are rejected by many relays; [Spell.resolveChunked] splits them. */
const val SPELL_AUTHOR_CAP = 500

const val VAR_ME = "\$me"
const val VAR_CONTACTS = "\$contacts"
const val VAR_MEMBERS = "\$members"

enum class SpellCmd { REQ, COUNT }

/**
 * Binds a spell to a NIP-29 group, carried as `["group", <relay>, <group-id>]`.
 *
 * Keeps a published spell self-contained: `$members` resolves from the bound group rather than
 * from whatever screen happens to cast it.
 */
data class GroupBinding(
    val relayUrl: String,
    val groupId: String,
)

/** Identity resolved at cast time. Distinct per user and per group, so it is never cached. */
data class SpellContext(
    val me: String? = null,
    val contacts: List<String> = emptyList(),
    val members: List<String> = emptyList(),
    val now: Long,
)

/**
 * A saved query in its **unresolved** form: `$me` / `$contacts` / `$members` and relative
 * timestamps such as `7d` stay literal.
 *
 * This is the invariant that keeps a spell alive rather than a frozen snapshot. Resolving at
 * creation time would pin the author list and the time window to the day it was written.
 *
 * [tagFilters] keys are bare tag letters ("t", "h", "p"); the `#` prefix is added on resolve.
 */
data class Spell(
    val id: String,
    val name: String,
    val description: String = "",
    val cmd: SpellCmd = SpellCmd.REQ,
    val kinds: List<Int> = emptyList(),
    val authors: List<String> = emptyList(),
    val ids: List<String> = emptyList(),
    val tagFilters: Map<String, List<String>> = emptyMap(),
    val relays: List<String> = emptyList(),
    val limit: Int? = null,
    val since: String? = null,
    val until: String? = null,
    val search: String? = null,
    val closeOnEose: Boolean = false,
    val topics: List<String> = emptyList(),
    val forkedFrom: String? = null,
    val group: GroupBinding? = null,
    val pubkey: String? = null,
    val createdAt: Long? = null,
) {
    /** True when the spell carries no filter tag at all, which the format forbids. */
    val hasFilter: Boolean
        get() = kinds.isNotEmpty() ||
            authors.isNotEmpty() ||
            ids.isNotEmpty() ||
            tagFilters.isNotEmpty() ||
            !search.isNullOrBlank() ||
            since != null ||
            until != null

    val usesVariables: Boolean
        get() = (authors + tagFilters.values.flatten()).any { it.isVariable() }
}

private fun String.isVariable(): Boolean = this == VAR_ME || this == VAR_CONTACTS || this == VAR_MEMBERS

// ---------------------------------------------------------------------------
// Relative timestamps
// ---------------------------------------------------------------------------

private val RELATIVE_TIME = Regex("^(\\d+)(s|mo|m|h|d|w|y)$")

private fun unitSeconds(unit: String): Long = when (unit) {
    "s" -> 1L
    "m" -> 60L
    "h" -> 3_600L
    "d" -> 86_400L
    "w" -> 604_800L
    "mo" -> 2_592_000L // approximate: 30 days
    "y" -> 31_536_000L // approximate: 365 days
    else -> 0L
}

/**
 * Resolves `now`, a bare unix timestamp, or a relative expression (`7d`, `12h`, `3mo`) against
 * [now]. Returns null when the value does not parse.
 */
fun parseRelativeTime(
    value: String,
    now: Long,
): Long? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed == "now") return now
    trimmed.toLongOrNull()?.let { return it }
    val match = RELATIVE_TIME.matchEntire(trimmed) ?: return null
    val amount = match.groupValues[1].toLongOrNull() ?: return null
    return now - amount * unitSeconds(match.groupValues[2])
}

// ---------------------------------------------------------------------------
// Parse
// ---------------------------------------------------------------------------

private fun malformed(reason: String): Result.Error<Nothing> = Result.Error(AppError.Spell.Malformed(reason))

/** Parses a `kind:777` event into a [Spell], keeping variables and relative times unresolved. */
fun parseSpell(event: Event): Result<Spell> {
    if (event.kind != SPELL_KIND) return malformed("expected kind $SPELL_KIND, got ${event.kind}")

    var cmd: SpellCmd? = null
    var name = ""
    var limit: Int? = null
    var since: String? = null
    var until: String? = null
    var search: String? = null
    var closeOnEose = false
    var forkedFrom: String? = null
    var group: GroupBinding? = null
    val kinds = mutableListOf<Int>()
    val authors = mutableListOf<String>()
    val ids = mutableListOf<String>()
    val relays = mutableListOf<String>()
    val topics = mutableListOf<String>()
    val tagFilters = mutableMapOf<String, MutableList<String>>()

    for (tag in event.tags) {
        val label = tag.firstOrNull() ?: continue
        val values = tag.drop(1)
        when (label) {
            "cmd" ->
                cmd = when (values.firstOrNull()?.uppercase()) {
                    "REQ" -> SpellCmd.REQ
                    "COUNT" -> SpellCmd.COUNT
                    else -> return malformed("unknown cmd '${values.firstOrNull()}'")
                }
            "k" -> values.firstOrNull()?.toIntOrNull()?.let { kinds += it }
            "authors" -> authors += values.filter { it.isNotBlank() }
            "ids" -> ids += values.filter { it.isNotBlank() }
            "relays" -> relays += values.filter { it.isNotBlank() }
            "limit" -> limit = values.firstOrNull()?.toIntOrNull()
            "since" -> since = values.firstOrNull()?.takeIf { it.isNotBlank() }
            "until" -> until = values.firstOrNull()?.takeIf { it.isNotBlank() }
            "search" -> search = values.firstOrNull()?.takeIf { it.isNotBlank() }
            "close-on-eose" -> closeOnEose = true
            "name" -> name = values.firstOrNull().orEmpty()
            "t" -> values.firstOrNull()?.takeIf { it.isNotBlank() }?.let { topics += it }
            "e" -> forkedFrom = values.firstOrNull()?.takeIf { it.isNotBlank() }
            "group" -> {
                val relay = values.getOrNull(0)?.takeIf { it.isNotBlank() }
                val gid = values.getOrNull(1)?.takeIf { it.isNotBlank() }
                if (relay != null && gid != null) group = GroupBinding(relay, gid)
            }
            // Filter conditions on event tags are namespaced under "tag" so a ["p", ...] filter
            // parameter is never mistaken for a social-graph reference.
            "tag" -> {
                val letter = values.firstOrNull()?.takeIf { it.length == 1 } ?: continue
                val conditions = values.drop(1).filter { it.isNotBlank() }
                if (conditions.isNotEmpty()) {
                    tagFilters.getOrPut(letter) { mutableListOf() } += conditions
                }
            }
        }
    }

    val resolvedCmd = cmd ?: return malformed("missing cmd tag")

    val spell =
        Spell(
            id = event.id.orEmpty(),
            name = name,
            description = event.content,
            cmd = resolvedCmd,
            kinds = kinds.distinct(),
            authors = authors.distinct(),
            ids = ids.distinct(),
            tagFilters = tagFilters.mapValues { (_, v) -> v.distinct() },
            relays = relays.distinct(),
            limit = limit,
            since = since,
            until = until,
            search = search,
            closeOnEose = closeOnEose,
            topics = topics.distinct(),
            forkedFrom = forkedFrom,
            group = group,
            pubkey = event.pubkey,
            createdAt = event.createdAt,
        )

    if (!spell.hasFilter) return malformed("spell carries no filter tag")
    return Result.Success(spell)
}

// ---------------------------------------------------------------------------
// Encode
// ---------------------------------------------------------------------------

/** Builds the unsigned `kind:777` event. Tag order matches the draft's worked examples. */
fun Spell.toUnsignedEvent(
    pubkey: String,
    now: Long,
): Event {
    val tags = mutableListOf<List<String>>()
    tags += listOf("cmd", cmd.name)
    if (name.isNotBlank()) tags += listOf("name", name)
    tags += listOf("alt", "Spell: ${name.ifBlank { "saved query" }}")
    group?.let { tags += listOf("group", it.relayUrl, it.groupId) }
    kinds.forEach { tags += listOf("k", it.toString()) }
    if (authors.isNotEmpty()) tags += listOf("authors") + authors
    if (ids.isNotEmpty()) tags += listOf("ids") + ids
    tagFilters.entries.sortedBy { it.key }.forEach { (letter, values) ->
        if (values.isNotEmpty()) tags += listOf("tag", letter) + values
    }
    if (relays.isNotEmpty()) tags += listOf("relays") + relays
    limit?.let { tags += listOf("limit", it.toString()) }
    since?.let { tags += listOf("since", it) }
    until?.let { tags += listOf("until", it) }
    search?.takeIf { it.isNotBlank() }?.let { tags += listOf("search", it) }
    if (closeOnEose) tags += listOf("close-on-eose")
    topics.forEach { tags += listOf("t", it) }
    forkedFrom?.let { tags += listOf("e", it) }

    return Event(
        pubkey = pubkey,
        createdAt = now,
        kind = SPELL_KIND,
        tags = tags,
        content = description,
    )
}

// ---------------------------------------------------------------------------
// Resolve
// ---------------------------------------------------------------------------

private fun expand(
    values: List<String>,
    ctx: SpellContext,
): Result<List<String>> {
    val out = mutableListOf<String>()
    for (value in values) {
        when (value) {
            VAR_ME -> {
                val me = ctx.me
                    ?: return Result.Error(AppError.Spell.UnresolvedVariable(VAR_ME, "no signed-in account"))
                out += me
            }
            VAR_CONTACTS -> {
                if (ctx.contacts.isEmpty()) {
                    return Result.Error(AppError.Spell.UnresolvedVariable(VAR_CONTACTS, "contact list is empty"))
                }
                out += ctx.contacts
            }
            VAR_MEMBERS -> {
                if (ctx.members.isEmpty()) {
                    return Result.Error(AppError.Spell.UnresolvedVariable(VAR_MEMBERS, "member list not loaded"))
                }
                out += ctx.members
            }
            else -> out += value
        }
    }
    return Result.Success(out.distinct())
}

/**
 * Expands variables and relative timestamps into a sendable filter.
 *
 * Fails rather than degrading: an unresolvable variable must never reach a relay as a literal
 * `$members`, which would silently match nothing.
 */
fun Spell.resolve(ctx: SpellContext): Result<NostrFilter> {
    val resolvedAuthors =
        when (val r = expand(authors, ctx)) {
            is Result.Success -> r.data
            is Result.Error -> return Result.Error(r.error)
        }

    val resolvedTags = mutableMapOf<String, List<String>>()
    for ((letter, values) in tagFilters) {
        when (val r = expand(values, ctx)) {
            is Result.Success -> resolvedTags["#$letter"] = r.data
            is Result.Error -> return Result.Error(r.error)
        }
    }

    val resolvedSince = since?.let {
        parseRelativeTime(it, ctx.now)
            ?: return malformed("bad since '$it'")
    }
    val resolvedUntil = until?.let {
        parseRelativeTime(it, ctx.now)
            ?: return malformed("bad until '$it'")
    }

    return Result.Success(
        NostrFilter(
            ids = ids.takeIf { it.isNotEmpty() },
            authors = resolvedAuthors.takeIf { it.isNotEmpty() },
            kinds = kinds.takeIf { it.isNotEmpty() },
            tags = resolvedTags,
            since = resolvedSince,
            until = resolvedUntil,
            limit = limit,
            search = search,
        ),
    )
}

/**
 * Resolves into one filter per author chunk.
 *
 * `$members` on a large group produces an author list many relays reject outright, so the caller
 * sends the chunks as separate REQs rather than one oversized filter.
 */
fun Spell.resolveChunked(
    ctx: SpellContext,
    cap: Int = SPELL_AUTHOR_CAP,
): Result<List<NostrFilter>> {
    val filter =
        when (val r = resolve(ctx)) {
            is Result.Success -> r.data
            is Result.Error -> return Result.Error(r.error)
        }
    val allAuthors = filter.authors
    if (allAuthors == null || allAuthors.size <= cap) return Result.Success(listOf(filter))
    return Result.Success(allAuthors.chunked(cap).map { filter.copy(authors = it) })
}

/**
 * Relays to send this spell to.
 *
 * The bound group's relay is dropped from the NIP-65 fallback: a NIP-29 relay serves group
 * events only, so a kind:1 query routed there returns empty and reads as a broken feed. Presets
 * that genuinely target the group relay carry it in [Spell.relays] explicitly.
 */
fun Spell.targetRelays(nip65ReadRelays: List<String>): List<String> {
    if (relays.isNotEmpty()) return relays.distinct()
    val groupRelay = group?.relayUrl
    return nip65ReadRelays.distinct().filterNot { it.equals(groupRelay, ignoreCase = true) }
}
