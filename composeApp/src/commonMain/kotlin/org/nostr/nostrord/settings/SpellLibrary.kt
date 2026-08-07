package org.nostr.nostrord.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.nostr.nostrord.auth.ActiveAccountManager
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.nostr.PRESET_ID_PREFIX
import org.nostr.nostrord.nostr.Spell
import org.nostr.nostrord.nostr.SpellPresets
import org.nostr.nostrord.nostr.parseSpell
import org.nostr.nostrord.nostr.toUnsignedEvent
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.epochSeconds

/**
 * One entry of the rail's pinned-spell list.
 *
 * A preset stores only its slug, since the spell itself is code and an app update should be able
 * to improve it. A custom spell stores the kind:777 event it will eventually be published as, so
 * the local format and the wire format cannot drift apart.
 */
@Serializable
internal data class SpellPin(
    val preset: String? = null,
    val id: String? = null,
    val event: Event? = null,
)

/**
 * The account's rail spells: which are pinned and in what order.
 *
 * Local and authoritative. Publishing (kind:777) and the shared kind:30777 index are a later
 * step; nothing here reaches a relay.
 */
class SpellLibrary(
    scope: CoroutineScope,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _pinned = MutableStateFlow(SpellPresets.forRail())
    val pinned: StateFlow<List<Spell>> = _pinned.asStateFlow()

    private var pubkey: String? = null

    init {
        scope.launch {
            ActiveAccountManager.session.collect { session -> load(session?.pubkey) }
        }
    }

    /**
     * The spell behind a route id: a pinned one first, then an unpinned built-in so a shared
     * link to a preset still opens. Null for a custom spell this device has never stored.
     */
    fun spellById(spellId: String): Spell? = _pinned.value.firstOrNull { it.id == spellId } ?: SpellPresets.railSpellById(spellId)

    /** True when [spellId] is already in the rail. */
    fun isPinned(spellId: String): Boolean = _pinned.value.any { it.id == spellId }

    fun add(spell: Spell) {
        if (isPinned(spell.id)) return
        mutate { it + spell }
    }

    fun remove(spellId: String) {
        mutate { list -> list.filterNot { it.id == spellId } }
    }

    /** Reorder to match [orderedIds]; ids not present are appended in their current order. */
    fun reorder(orderedIds: List<String>) {
        mutate { list ->
            val byId = list.associateBy { it.id }
            orderedIds.mapNotNull { byId[it] } + list.filterNot { it.id in orderedIds }
        }
    }

    /** Restore the built-in set, discarding custom spells. */
    fun resetToDefaults() {
        mutate { SpellPresets.forRail() }
    }

    private fun load(newPubkey: String?) {
        pubkey = newPubkey
        if (newPubkey == null) {
            _pinned.value = SpellPresets.forRail()
            return
        }
        val raw = SecureStorage.getStringPref(key(newPubkey), "")
        // Absent means "never customised", which is not the same as "emptied on purpose": only
        // the first seeds the defaults, so removing every spell survives a restart.
        if (raw.isBlank()) {
            _pinned.value = SpellPresets.forRail()
            return
        }
        _pinned.value = runCatching { json.decodeFromString<List<SpellPin>>(raw) }
            .getOrDefault(emptyList())
            .mapNotNull { it.toSpell() }
    }

    private fun mutate(transform: (List<Spell>) -> List<Spell>) {
        val next = transform(_pinned.value)
        _pinned.value = next
        val owner = pubkey ?: return
        val pins = next.map { it.toPin(owner) }
        SecureStorage.saveStringPref(key(owner), json.encodeToString(pins))
    }

    private companion object {
        fun key(pubkey: String) = "spell_pins_$pubkey"
    }
}

internal fun SpellPin.toSpell(): Spell? = when {
    preset != null -> SpellPresets.forRail(preset)
    event != null -> when (val parsed = parseSpell(event)) {
        // The stored event is unsigned, so it carries no id; the pin keeps the local one.
        is Result.Success -> parsed.data.copy(id = id ?: parsed.data.id)
        is Result.Error -> null
    }
    else -> null
}

internal fun Spell.toPin(ownerPubkey: String): SpellPin = if (id.startsWith(PRESET_ID_PREFIX)) {
    SpellPin(preset = id.removePrefix(PRESET_ID_PREFIX))
} else {
    SpellPin(id = id, event = toUnsignedEvent(ownerPubkey, createdAt ?: epochSeconds()))
}
