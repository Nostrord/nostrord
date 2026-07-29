package org.nostr.nostrord.utils

/**
 * Membership test scoped to (relay, group id) over the `joinedGroupsByRelay` map.
 *
 * NIP-29 group ids are relay-scoped and short ones ("nostrord", "lotus") exist on several
 * relays, so a flat id match reports a group as joined because a DIFFERENT group with the
 * same id is in the list. Every "am I in this group" check the UI makes must go through here.
 */
fun Map<String, Set<String>>.isJoinedOn(
    relayUrl: String?,
    groupId: String,
): Boolean {
    if (relayUrl == null) return false
    val normalized = relayUrl.normalizeRelayUrl()
    return this[normalized]?.contains(groupId) == true ||
        entries.any { it.key.normalizeRelayUrl() == normalized && groupId in it.value }
}
