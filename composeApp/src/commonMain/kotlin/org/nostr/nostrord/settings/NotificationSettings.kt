package org.nostr.nostrord.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.loadGroupNotificationLevelsFor
import org.nostr.nostrord.storage.saveGroupNotificationLevelsFor

/**
 * How noisy a group's notifications are. Applied per-group, falling back to the
 * global default for groups the user hasn't overridden.
 *
 * - [ALL] — every message notifies (current default).
 * - [MENTIONS_REPLIES] — only direct replies, @mentions and reactions to the
 *   user's own messages notify. Ordinary chatter is silent.
 * - [MUTED] — nothing notifies, not even direct mentions/replies.
 *
 * [MENTIONS_REPLIES] still shows the unread count; only the feed/sound/popup is
 * skipped. [MUTED] additionally drops the group out of the rail and total badge
 * rollups, so a muted group reads as a dim dot rather than a number. The count is
 * still tracked internally, so the unread divider and jump-to-unread keep working
 * once the group is opened.
 */
enum class NotificationLevel { ALL, MENTIONS_REPLIES, MUTED }

/**
 * An immutable snapshot of "how loud is each group", published as one flow so a
 * consumer resolves a group's level without racing the default against the overrides.
 */
data class MuteState(
    val defaultLevel: NotificationLevel = NotificationLevel.ALL,
    val overrides: Map<String, NotificationLevel> = emptyMap(),
) {
    fun levelFor(groupId: String): NotificationLevel = overrides[groupId] ?: defaultLevel

    fun isMuted(groupId: String): Boolean = levelFor(groupId) == NotificationLevel.MUTED
}

/**
 * User-facing notification preferences — toggled from Settings → Notifications.
 *
 * Sound applies to every platform that has a NotificationSound actual (web/desktop/android).
 * System popups are gated on the per-platform [NotificationService.isSupported] check
 * inside the consumer; this flag just lets the user opt out even when supported.
 *
 * Per-group [NotificationLevel] overrides are account-scoped and reloaded on
 * account switch via [initialize] / [clear].
 */
class NotificationSettings {
    private val _soundEnabled =
        MutableStateFlow(
            SecureStorage.getBooleanPref(KEY_SOUND_ENABLED, default = true),
        )
    val soundEnabled: StateFlow<Boolean> = _soundEnabled.asStateFlow()

    private val _systemNotificationsEnabled =
        MutableStateFlow(
            SecureStorage.getBooleanPref(KEY_SYSTEM_ENABLED, default = true),
        )
    val systemNotificationsEnabled: StateFlow<Boolean> = _systemNotificationsEnabled.asStateFlow()

    // Global default applied to groups the user hasn't explicitly overridden.
    private val _defaultLevel =
        MutableStateFlow(
            runCatching {
                NotificationLevel.valueOf(
                    SecureStorage.getStringPref(KEY_DEFAULT_LEVEL, NotificationLevel.ALL.name),
                )
            }.getOrDefault(NotificationLevel.ALL),
        )
    val defaultLevel: StateFlow<NotificationLevel> = _defaultLevel.asStateFlow()

    // Per-account, per-group overrides. Reloaded on account switch.
    private val _groupLevels = MutableStateFlow<Map<String, NotificationLevel>>(emptyMap())
    val groupLevels: StateFlow<Map<String, NotificationLevel>> = _groupLevels.asStateFlow()

    // Default + overrides as one value, for consumers that resolve a group's level
    // reactively (badge rollups, muted styling) and must not see the two halves apart.
    private val _muteState = MutableStateFlow(MuteState(_defaultLevel.value))
    val muteState: StateFlow<MuteState> = _muteState.asStateFlow()

    private var currentPubkey: String? = null

    private fun syncMuteState() {
        _muteState.value = MuteState(_defaultLevel.value, _groupLevels.value)
    }

    fun setSoundEnabled(enabled: Boolean) {
        _soundEnabled.value = enabled
        SecureStorage.saveBooleanPref(KEY_SOUND_ENABLED, enabled)
    }

    fun setSystemNotificationsEnabled(enabled: Boolean) {
        _systemNotificationsEnabled.value = enabled
        SecureStorage.saveBooleanPref(KEY_SYSTEM_ENABLED, enabled)
    }

    fun setDefaultLevel(level: NotificationLevel) {
        _defaultLevel.value = level
        SecureStorage.saveStringPref(KEY_DEFAULT_LEVEL, level.name)
        syncMuteState()
    }

    /** Load the active account's per-group overrides. Call on account activation. */
    fun initialize(pubkey: String) {
        currentPubkey = pubkey
        _groupLevels.value =
            SecureStorage.loadGroupNotificationLevelsFor(pubkey)
                .mapNotNull { (id, name) ->
                    runCatching { id to NotificationLevel.valueOf(name) }.getOrNull()
                }
                .toMap()
        syncMuteState()
    }

    /** Drop the active account's overrides on logout / account switch. */
    fun clear() {
        currentPubkey = null
        _groupLevels.value = emptyMap()
        syncMuteState()
    }

    fun setGroupLevel(
        groupId: String,
        level: NotificationLevel,
    ) {
        val pubkey = currentPubkey ?: return
        _groupLevels.update { it + (groupId to level) }
        syncMuteState()
        SecureStorage.saveGroupNotificationLevelsFor(
            pubkey,
            _groupLevels.value.mapValues { it.value.name },
        )
    }

    /** Drops a group's override so it tracks [defaultLevel] again. */
    fun clearGroupLevel(groupId: String) {
        val pubkey = currentPubkey ?: return
        _groupLevels.update { it - groupId }
        syncMuteState()
        SecureStorage.saveGroupNotificationLevelsFor(
            pubkey,
            _groupLevels.value.mapValues { it.value.name },
        )
    }

    /**
     * One-click mute from a group row. Unmuting returns the group to the global
     * default rather than pinning it to [NotificationLevel.ALL], so a later change
     * of the default still reaches it.
     */
    fun toggleMute(groupId: String) {
        val pubkey = currentPubkey ?: return
        _groupLevels.update {
            if (isMuted(groupId)) {
                if (_defaultLevel.value == NotificationLevel.MUTED) {
                    it + (groupId to NotificationLevel.ALL)
                } else {
                    it - groupId
                }
            } else {
                it + (groupId to NotificationLevel.MUTED)
            }
        }
        syncMuteState()
        SecureStorage.saveGroupNotificationLevelsFor(
            pubkey,
            _groupLevels.value.mapValues { it.value.name },
        )
    }

    fun effectiveLevelFor(groupId: String): NotificationLevel = _groupLevels.value[groupId] ?: _defaultLevel.value

    fun isMuted(groupId: String): Boolean = effectiveLevelFor(groupId) == NotificationLevel.MUTED

    /**
     * Whether a notification should fire for a message of the given [level].
     * [isDirect] is true for replies, @mentions and reactions to the user's own
     * message; false for ordinary group chatter.
     */
    fun shouldNotify(
        level: NotificationLevel,
        isDirect: Boolean,
    ): Boolean = when (level) {
        NotificationLevel.ALL -> true
        NotificationLevel.MENTIONS_REPLIES -> isDirect
        NotificationLevel.MUTED -> false
    }

    private companion object {
        const val KEY_SOUND_ENABLED = "notif_sound_enabled"
        const val KEY_SYSTEM_ENABLED = "notif_system_enabled"
        const val KEY_DEFAULT_LEVEL = "notif_default_level"
    }
}
