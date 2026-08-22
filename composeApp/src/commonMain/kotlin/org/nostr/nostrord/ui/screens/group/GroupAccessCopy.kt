package org.nostr.nostrord.ui.screens.group

/**
 * User-facing labels and descriptions for the NIP-29 group access flags, shared by the create,
 * edit and manage modals on both platforms so the wording can't drift between them.
 *
 * Descriptions follow NIP-29 (https://github.com/nostr-protocol/nips/blob/master/29.md):
 *  - private    -> only members can READ messages
 *  - closed     -> join requests are ignored (invite-only)
 *  - restricted -> only members can WRITE (post) messages
 *  - hidden     -> relays hide the group's metadata from non-members
 *
 * The modals lay these out read / join / write / visibility, matching this order.
 */
object GroupAccessCopy {
    const val PRIVATE_LABEL = "Private"
    const val PRIVATE_DESC = "Only members can read group messages"

    const val CLOSED_LABEL = "Closed"
    const val CLOSED_DESC = "Join requests are ignored (invite-only)"

    const val RESTRICTED_LABEL = "Restricted"
    const val RESTRICTED_DESC = "Only members can post messages"

    const val HIDDEN_LABEL = "Hidden"
    const val HIDDEN_DESC = "Hidden from non-members, not discoverable"

    /**
     * Not a NIP-29 flag: keeps the new group in the NIP-44-encrypted section of the user's
     * kind:10009, so the membership is never advertised in the clear (same setting as the
     * group-info toggle, offered at creation time so it never goes out public even once).
     */
    const val LIST_PRIVATELY_LABEL = "Private on my list"
    const val LIST_PRIVATELY_DESC = "Kept encrypted in your group list so others can't see you joined"

    /**
     * NIP-29 `livekit`. Not an access flag: it adds a relay-hosted audio/video room any member
     * can join. Only offered when the relay advertises support at `/.well-known/nip29/livekit`.
     */
    const val LIVEKIT_LABEL = "Live audio and video"
    const val LIVEKIT_DESC = "Members can join a voice and video room hosted by the relay"
    const val LIVEKIT_UNSUPPORTED = "This relay does not host audio or video rooms"
}
