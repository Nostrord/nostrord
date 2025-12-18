package org.nostr.nostrord.ui.screens.relay.model

data class RelayInfo(
    val url: String,
    var status: RelayStatus = RelayStatus.DISCONNECTED,
    var groupCount: Int? = null,
    var details: String = "No additional details available."
)

enum class RelayStatus {
    CONNECTED,
    DISCONNECTED,
    CONNECTING,
    ERROR
}
