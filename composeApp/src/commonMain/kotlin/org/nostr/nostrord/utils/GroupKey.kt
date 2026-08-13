package org.nostr.nostrord.utils

/**
 * Identity of a NIP-29 group: the hosting relay plus the group id.
 *
 * A group id is only unique within one relay. `nostrord` on chat.wisp.talk and
 * `nostrord` on groups.0xchat.com are two unrelated groups with separate members,
 * messages and unread state, so every per-group store keys on this pair and never
 * on the bare id.
 *
 * Relay first, separator `|`: relay URLs cannot contain `|`, group ids may, so
 * [groupKeyRelay] / [groupKeyId] round-trip. The relay is normalized so a route
 * built from a raw nevent hint (`wss://Relay.example/`) keys the same slot as one
 * built from the relay list.
 */
fun groupKey(
    relayUrl: String,
    groupId: String,
): String = "${relayUrl.normalizeRelayUrl()}|$groupId"

fun groupKeyRelay(key: String): String = key.substringBefore('|')

fun groupKeyId(key: String): String = key.substringAfter('|')

/** False for legacy bare-id keys persisted before group state was relay-scoped. */
fun isGroupKey(key: String): Boolean = key.contains('|')
