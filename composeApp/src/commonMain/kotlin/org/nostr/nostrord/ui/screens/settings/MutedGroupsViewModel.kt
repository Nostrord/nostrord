package org.nostr.nostrord.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.NostrRepositoryApi
import org.nostr.nostrord.settings.NotificationLevel
import org.nostr.nostrord.settings.NotificationSettings

/** A group the user has given a notification level of its own. */
data class MutedGroupRow(
    val groupId: String,
    val relayUrl: String,
    val name: String,
    val picture: String?,
    val level: NotificationLevel,
)

/**
 * Shared logic for the Settings "Muted groups" panel: every group with a per-group
 * override, so the user can find and undo one without opening the group first. Both
 * the web and Compose panels consume this VM.
 */
class MutedGroupsViewModel(
    private val repo: NostrRepositoryApi,
    private val settings: NotificationSettings,
) : ViewModel() {
    val defaultLevel: StateFlow<NotificationLevel> = settings.defaultLevel

    /**
     * Overridden groups, muted first and then by name, so the list the user came here
     * to prune sits at the top. Groups whose metadata hasn't arrived render under their
     * bare id rather than disappearing.
     */
    val rows: StateFlow<List<MutedGroupRow>> =
        combine(settings.groupLevels, repo.groupsByRelay, repo.joinedGroupsByRelay) { levels, byRelay, joined ->
            // First relay wins for a same-id group joined twice: the panel is keyed by the
            // bare id the override itself uses, so it can only point at one of them.
            val located = mutableMapOf<String, Pair<String, GroupMetadata>>()
            joined.forEach { (relayUrl, ids) ->
                byRelay[relayUrl].orEmpty().forEach { meta ->
                    if (meta.id in ids && meta.id !in located) located[meta.id] = relayUrl to meta
                }
            }
            levels.map { (groupId, level) ->
                val relayUrl = located[groupId]?.first.orEmpty()
                val meta = located[groupId]?.second
                MutedGroupRow(
                    groupId = groupId,
                    relayUrl = relayUrl,
                    name = meta?.name?.takeIf { it.isNotBlank() } ?: groupId,
                    picture = meta?.picture,
                    level = level,
                )
            }.sortedWith(compareBy({ it.level != NotificationLevel.MUTED }, { it.name.lowercase() }))
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Drops the override so the group tracks the global default again. */
    fun clearOverride(groupId: String) {
        settings.clearGroupLevel(groupId)
    }

    fun setLevel(
        groupId: String,
        level: NotificationLevel,
    ) {
        settings.setGroupLevel(groupId, level)
    }
}
