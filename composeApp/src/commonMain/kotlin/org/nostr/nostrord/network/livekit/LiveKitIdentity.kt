package org.nostr.nostrord.network.livekit

private val HEX_64 = Regex("^[0-9a-f]{64}$")

/** True for a canonical 64-char lowercase hex pubkey. */
fun isHexPubkey(value: String): Boolean = HEX_64.matches(value)

/**
 * Pubkey behind a LiveKit participant identity.
 *
 * NIP-29 has the relay set the JWT `sub` to the 64-char lowercase hex pubkey followed by a
 * random suffix, so one user can hold several tokens and be in the room more than once.
 * Match on the prefix; identity equality would fail for every real participant.
 */
fun pubkeyFromLiveKitIdentity(identity: String): String? = identity.take(64).takeIf { it.length == 64 && isHexPubkey(it) }
