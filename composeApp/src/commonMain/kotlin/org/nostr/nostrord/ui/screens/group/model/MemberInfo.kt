package org.nostr.nostrord.ui.screens.group.model

/**
 * Represents a group member for mention suggestions
 */
data class MemberInfo(
    val pubkey: String,
    val displayName: String,
    val picture: String?
)
