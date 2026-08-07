package org.nostr.nostrord.nostr

import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.epochMillis
import org.nostr.nostrord.utils.toRelayUrl

/** Id namespace for a spell the user built here and has not published yet. */
const val LOCAL_ID_PREFIX = "local:"

/**
 * The create-spell form as the user typed it, before validation.
 *
 * Lives in commonMain so both UIs parse identically: the Compose dialog and the web modal are
 * only text fields over [toSpell].
 */
data class SpellDraft(
    val name: String = "",
    val kinds: String = "",
    val authors: String = "",
    val relays: String = "",
    val hashtags: String = "",
    val limit: String = "",
) {
    val isRelayFeed: Boolean
        get() = relays.isNotBlank() && authors.isBlank()
}

private val SEPARATORS = Regex("[,\\s]+")

private fun split(raw: String): List<String> = raw.split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }

/**
 * Normalizes one author entry: a runtime variable, an npub, or raw hex.
 *
 * Returns null when the entry is none of those, so a typo is reported rather than silently
 * shipped to a relay as an author that matches nothing.
 */
internal fun parseAuthor(raw: String): String? {
    val value = raw.trim()
    return when {
        value == VAR_ME || value == VAR_CONTACTS || value == VAR_MEMBERS -> value
        value.startsWith("npub") -> (Nip19.decode(value) as? Nip19.Entity.Npub)?.pubkey
        value.length == 64 && value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } -> value.lowercase()
        else -> null
    }
}

/**
 * Validates the draft into a spell ready to pin.
 *
 * A spell with only relays and no other condition is valid: that is fiatjaf's relay feed, where
 * the relay itself is the whole selection criterion.
 */
fun SpellDraft.toSpell(now: Long = epochMillis()): Result<Spell> {
    val trimmedName = name.trim()
    if (trimmedName.isEmpty()) {
        return Result.Error(AppError.Spell.Malformed("give the spell a name"))
    }

    val parsedKinds = mutableListOf<Int>()
    for (entry in split(kinds)) {
        val kind = entry.toIntOrNull()
            ?: return Result.Error(AppError.Spell.Malformed("'$entry' is not a kind number"))
        parsedKinds += kind
    }

    val parsedAuthors = mutableListOf<String>()
    for (entry in split(authors)) {
        parsedAuthors += parseAuthor(entry)
            ?: return Result.Error(AppError.Spell.Malformed("'$entry' is not an npub, hex key or variable"))
    }

    val parsedRelays = split(relays).map { it.toRelayUrl() }.filter { it.isNotBlank() }
    val parsedTags = split(hashtags).map { it.removePrefix("#") }.filter { it.isNotBlank() }

    val parsedLimit = limit.trim().takeIf { it.isNotEmpty() }?.let {
        it.toIntOrNull()?.takeIf { n -> n > 0 }
            ?: return Result.Error(AppError.Spell.Malformed("'$it' is not a positive limit"))
    }

    val spell =
        Spell(
            id = "$LOCAL_ID_PREFIX$now",
            name = trimmedName,
            description = "",
            kinds = parsedKinds.distinct(),
            authors = parsedAuthors.distinct(),
            tagFilters = if (parsedTags.isEmpty()) emptyMap() else mapOf("t" to parsedTags.distinct()),
            relays = parsedRelays.distinct(),
            limit = parsedLimit ?: DEFAULT_DRAFT_LIMIT,
        )

    if (!spell.hasFilter && spell.relays.isEmpty()) {
        return Result.Error(AppError.Spell.Malformed("add at least a kind, an author, a hashtag or a relay"))
    }
    return Result.Success(spell)
}

private const val DEFAULT_DRAFT_LIMIT = 50
