package org.nostr.nostrord.nostr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.nostr.nostrord.settings.NotificationLevel

/**
 * NIP-78 application-specific data (kind:30078): an addressable event whose `d` tag
 * names the setting and whose content carries its value.
 *
 * Nostrord uses it for per-group notification levels, so muting a group follows the
 * account to its other devices. The payload is NIP-44 self-encrypted before it is
 * published: the ids inside are NIP-29 group ids, and a plaintext list of them would
 * hand any relay the account's group membership.
 */
object Nip78 {
    const val KIND_APP_DATA = 30078

    /** `d` tag of the notification-preferences event. Versioned so a future shape can coexist. */
    const val D_NOTIFICATIONS = "nostrord.notifications.v1"

    /**
     * `default` records the publishing device's global default for context. Ingest reads
     * it but callers do not apply it: that setting's storage slot is device-global rather
     * than per-account, so one account's event must not rewrite it.
     */
    @Serializable
    data class NotificationPayload(
        @SerialName("v") val version: Int = 1,
        @SerialName("default") val defaultLevel: String = NotificationLevel.ALL.name,
        @SerialName("groups") val groups: Map<String, String> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun encodeNotifications(
        defaultLevel: NotificationLevel,
        groupLevels: Map<String, NotificationLevel>,
    ): String = json.encodeToString(
        NotificationPayload(
            defaultLevel = defaultLevel.name,
            groups = groupLevels.mapValues { it.value.name },
        ),
    )

    /**
     * Parses a decrypted payload. Returns null for anything unreadable so a corrupt or
     * future-shaped event leaves the local settings alone instead of wiping them.
     * Unknown level names are dropped per-entry: a newer client's extra level must not
     * take the whole map down with it.
     */
    fun decodeNotifications(plaintext: String): DecodedNotifications? {
        val payload =
            try {
                json.decodeFromString<NotificationPayload>(plaintext)
            } catch (_: Exception) {
                return null
            }
        if (payload.version != 1) return null
        val default = levelOrNull(payload.defaultLevel) ?: NotificationLevel.ALL
        val groups = payload.groups.mapNotNull { (id, name) ->
            levelOrNull(name)?.let { id to it }
        }.toMap()
        return DecodedNotifications(default, groups)
    }

    private fun levelOrNull(name: String): NotificationLevel? = NotificationLevel.entries.firstOrNull { it.name == name }

    data class DecodedNotifications(
        val defaultLevel: NotificationLevel,
        val groupLevels: Map<String, NotificationLevel>,
    )
}
