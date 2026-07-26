package org.nostr.nostrord.nostr

/**
 * NIP-56 reporting. A kind:1984 flags a pubkey (and optionally one of their events);
 * the report type rides as the third element of the `p`/`e` tag.
 */
object Nip56 {
    const val KIND_REPORT = 1984

    /** Wire values defined by NIP-56. */
    enum class ReportType(val value: String) {
        NUDITY("nudity"),
        MALWARE("malware"),
        PROFANITY("profanity"),
        ILLEGAL("illegal"),
        SPAM("spam"),
        IMPERSONATION("impersonation"),
        OTHER("other"),
    }

    /** Tag list for a report on [pubkey], optionally pinned to their event [eventId]. */
    fun reportTags(
        pubkey: String,
        eventId: String?,
        type: ReportType,
    ): List<List<String>> = buildList {
        add(listOf("p", pubkey, type.value))
        if (!eventId.isNullOrBlank()) add(listOf("e", eventId, type.value))
    }
}
