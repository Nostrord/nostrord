package org.nostr.nostrord.utils

import org.nostr.nostrord.network.GroupMetadata

/**
 * Metadata for a group reference (a quoted event's `h` tag, an naddr link, an invite) as the
 * group exists on the relay the reference points at.
 *
 * A group id is unique only within one relay (see [groupKey]), so a relay hint pins the lookup
 * to that relay's own kind:39000: `nostrord` on groups.0xchat.com is a different group from
 * `nostrord` on chat.wisp.talk and must not lend the reference its name or picture. A hinted
 * reference whose relay has no such group stays unresolved (the caller renders the raw id and
 * a seeded avatar) until [org.nostr.nostrord.network.NostrRepositoryApi.fetchGroupPreview]
 * brings that relay's copy in.
 *
 * Only a reference with no hint at all falls back to a cross-relay scan, [fallback] first.
 */
fun resolveGroupRef(
    groupsByRelay: Map<String, List<GroupMetadata>>,
    groupId: String,
    relayHint: String?,
    fallback: List<GroupMetadata> = emptyList(),
): GroupMetadata? {
    if (!relayHint.isNullOrBlank()) {
        val host = relayHint.normalizeRelayUrl()
        return groupsByRelay.entries
            .firstOrNull { it.key.normalizeRelayUrl() == host }
            ?.value
            ?.firstOrNull { it.id == groupId }
    }
    return fallback.firstOrNull { it.id == groupId }
        ?: groupsByRelay.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.id == groupId } }
}
