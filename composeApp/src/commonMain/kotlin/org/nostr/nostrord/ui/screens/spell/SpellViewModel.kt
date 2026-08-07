package org.nostr.nostrord.ui.screens.spell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.NostrRepositoryApi
import org.nostr.nostrord.network.managers.GroupLoadingState
import org.nostr.nostrord.network.managers.key
import org.nostr.nostrord.nostr.Spell
import org.nostr.nostrord.nostr.SpellContext
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.epochSeconds

/**
 * One spell feed, shared by the Compose and web screens.
 *
 * Deliberately not a [org.nostr.nostrord.ui.screens.group.GroupViewModel] subclass: a feed has no
 * membership, no roles and no composer, and that VM is saturated with all three.
 */
class SpellViewModel(
    private val repo: NostrRepositoryApi = AppModule.nostrRepository,
    val spellId: String,
    resolveSpell: (String) -> Spell? = { AppModule.spellLibrary.spellById(it) },
) : ViewModel() {
    /** Null when the id names a spell this build does not know (stale link, unsynced custom). */
    val spell: Spell? = resolveSpell(spellId)

    private val key: String? = spell?.key

    val title: String = spell?.name ?: "Unknown spell"
    val subtitle: String = spell?.description.orEmpty()

    val events: StateFlow<List<NostrGroupClient.NostrMessage>> =
        repo.spellEvents
            .map { it[key].orEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val loadingState: StateFlow<GroupLoadingState> =
        repo.spellStates
            .map { it[key] ?: GroupLoadingState.Idle }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupLoadingState.Idle)

    /**
     * Why the feed is empty, when it is empty for a reason the user can act on: no contact list
     * yet, no read relay configured, nothing reachable. Null once a REQ is on the wire.
     */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var opened = false

    init {
        val target = spell
        if (target == null) {
            _error.value = "This spell is not available on this device."
        } else {
            viewModelScope.launch {
                // Re-attempts on every identity change: a spell over $contacts cannot resolve
                // until kind:3 lands, which is routinely after the screen is already open.
                combine(repo.activePubkey, repo.following, repo.userRelayList) { me, follows, relays ->
                    Triple(me, follows.toList(), relays.filter { it.read }.map { it.url })
                }.collect { (me, follows, readRelays) ->
                    if (opened) return@collect
                    val ctx = SpellContext(me = me, contacts = follows, now = epochSeconds())
                    when (val result = repo.openSpell(target, ctx, readRelays)) {
                        is Result.Success -> {
                            opened = true
                            _error.value = null
                        }
                        is Result.Error -> _error.value = result.error.message
                    }
                }
            }
        }
    }

    fun loadMore() {
        val k = key ?: return
        viewModelScope.launch { repo.loadMoreSpell(k) }
    }

    override fun onCleared() {
        // The screen owns the subscription: leaving CLOSEs it rather than leaving a firehose
        // streaming into a feed nobody is reading.
        key?.let { repo.closeSpell(it) }
        super.onCleared()
    }
}
