package org.nostr.nostrord.nostr

/**
 * Built-in spells, materialized per group.
 *
 * Presets are code, not events: every platform derives the identical spell from
 * `(relayUrl, groupId)`, so nothing about them needs to sync. Only the pin state does, keyed by
 * [SpellPreset.slug].
 */
enum class PresetFamily {
    /** Account-level feed for the rail. Resolves from the signed-in identity alone. */
    Rail,

    /** Group content on the group's own relay, scoped by `#h`. Already connected and authed. */
    GroupRelay,

    /** Member-authored content on general relays. Must not be routed to the NIP-29 relay. */
    MemberGraph,
}

data class SpellPreset(
    val slug: String,
    val displayName: String,
    val family: PresetFamily,
    val kinds: List<Int>,
    val description: String,
)

object SpellPresets {
    // Rail: account-level, zero config. Every one resolves from the signed-in identity, so a
    // fresh install has something in the rail before the user builds anything.
    val CONTACT_NOTES =
        SpellPreset("contacts-notes", "From contacts", PresetFamily.Rail, listOf(1), "Notes from people you follow")
    val MENTIONS =
        SpellPreset("mentions", "Mentions", PresetFamily.Rail, listOf(1), "Notes that mention you")
    val MY_NOTES =
        SpellPreset("my-notes", "My notes", PresetFamily.Rail, listOf(1), "Notes you published")

    // Group content by kind. Kind 11 (NIP-7D threads) is deliberately absent: Nostrord already
    // ships it as the Threads view, and a preset that duplicates a screen splits the feature.
    val IMAGES = SpellPreset("images", "Images", PresetFamily.GroupRelay, listOf(20), "Pictures posted in this group")
    val VIDEOS = SpellPreset("videos", "Videos", PresetFamily.GroupRelay, listOf(21, 22), "Videos posted in this group")
    val POLLS = SpellPreset("polls", "Polls", PresetFamily.GroupRelay, listOf(1068), "Polls opened in this group")
    val FILES = SpellPreset("files", "Files", PresetFamily.GroupRelay, listOf(1063), "Files shared in this group")
    val FIREHOSE = SpellPreset("firehose", "Raw firehose", PresetFamily.GroupRelay, emptyList(), "Every event in this group")

    val MEMBER_NOTES =
        SpellPreset("member-notes", "Notes from members", PresetFamily.MemberGraph, listOf(1), "Public notes by group members")
    val MEMBER_ARTICLES =
        SpellPreset("member-articles", "Long-form from members", PresetFamily.MemberGraph, listOf(30023), "Articles by group members")
    val MEMBER_HIGHLIGHTS =
        SpellPreset("member-highlights", "Highlights from members", PresetFamily.MemberGraph, listOf(9802), "Highlights by group members")

    /** Rail presets in display order. */
    val rail: List<SpellPreset> = listOf(CONTACT_NOTES, MENTIONS, MY_NOTES)

    /** Group presets in display order. */
    val group: List<SpellPreset> =
        listOf(IMAGES, VIDEOS, POLLS, FILES, FIREHOSE, MEMBER_NOTES, MEMBER_ARTICLES, MEMBER_HIGHLIGHTS)

    val all: List<SpellPreset> = rail + group

    val slugs: List<String> = all.map { it.slug }

    fun bySlug(slug: String): SpellPreset? = all.firstOrNull { it.slug == slug }

    /** Every rail preset, in display order. */
    fun forRail(): List<Spell> = rail.map { it.toRailSpell() }

    fun forRail(slug: String): Spell? = rail.firstOrNull { it.slug == slug }?.toRailSpell()

    /**
     * Resolve a spell id carried by [org.nostr.nostrord.ui.navigation.SpellRoute] back to its
     * rail spell. Returns null for a custom (published) id, which comes from storage instead.
     */
    fun railSpellById(spellId: String): Spell? {
        if (!spellId.startsWith(PRESET_ID_PREFIX)) return null
        return forRail(spellId.removePrefix(PRESET_ID_PREFIX))
    }

    /** Every group preset bound to the given group, in display order. */
    fun forGroup(
        relayUrl: String,
        groupId: String,
    ): List<Spell> = group.map { it.toSpell(relayUrl, groupId) }

    fun forGroup(
        slug: String,
        relayUrl: String,
        groupId: String,
    ): Spell? = group.firstOrNull { it.slug == slug }?.toSpell(relayUrl, groupId)
}

/**
 * Materializes a rail preset.
 *
 * Relays stay empty so [targetRelays] falls back to the user's NIP-65 read relays: none of these
 * are group content, and a NIP-29 relay does not serve kind:1.
 */
fun SpellPreset.toRailSpell(): Spell {
    require(family == PresetFamily.Rail) { "$slug is not a rail preset" }
    val base =
        Spell(
            id = "$PRESET_ID_PREFIX$slug",
            name = displayName,
            description = description,
            kinds = kinds,
            limit = DEFAULT_PRESET_LIMIT,
        )
    return when (slug) {
        SpellPresets.MENTIONS.slug -> base.copy(tagFilters = mapOf("p" to listOf(VAR_ME)))
        SpellPresets.MY_NOTES.slug -> base.copy(authors = listOf(VAR_ME))
        else -> base.copy(authors = listOf(VAR_CONTACTS))
    }
}

/**
 * fiatjaf's first example: a feed of one or more kinds straight off specific relays.
 *
 * Needs exactly one input (the relays), which is why it is a builder rather than a preset or a
 * trip through the full editor.
 */
fun relayFeed(
    name: String,
    relays: List<String>,
    kinds: List<Int> = listOf(1),
    limit: Int = DEFAULT_PRESET_LIMIT,
): Spell = Spell(
    id = "relay:${relays.joinToString(",")}",
    name = name,
    description = "Everything on ${relays.joinToString(", ")}",
    kinds = kinds,
    relays = relays,
    limit = limit,
)

/**
 * Binds a preset to a group.
 *
 * [PresetFamily.GroupRelay] pins the group relay explicitly and filters by `#h`;
 * [PresetFamily.MemberGraph] leaves relays empty so [targetRelays] falls back to NIP-65 with the
 * group relay excluded, and queries `$members` instead.
 */
fun SpellPreset.toSpell(
    relayUrl: String,
    groupId: String,
): Spell {
    require(family != PresetFamily.Rail) { "$slug is a rail preset; use toRailSpell()" }
    val binding = GroupBinding(relayUrl, groupId)
    return when (family) {
        PresetFamily.Rail -> error("unreachable")
        PresetFamily.GroupRelay ->
            Spell(
                id = "$PRESET_ID_PREFIX$slug",
                name = displayName,
                description = description,
                kinds = kinds,
                tagFilters = mapOf("h" to listOf(groupId)),
                relays = listOf(relayUrl),
                limit = DEFAULT_PRESET_LIMIT,
                group = binding,
            )
        PresetFamily.MemberGraph ->
            Spell(
                id = "$PRESET_ID_PREFIX$slug",
                name = displayName,
                description = description,
                kinds = kinds,
                authors = listOf(VAR_MEMBERS),
                limit = DEFAULT_PRESET_LIMIT,
                group = binding,
            )
    }
}

/** Id namespace for built-in spells, so a preset id can never collide with an event id. */
const val PRESET_ID_PREFIX = "preset:"

private const val DEFAULT_PRESET_LIMIT = 50
