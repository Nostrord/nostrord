package org.nostr.nostrord.web.modals

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.outbox.RelayListManager
import org.nostr.nostrord.ui.screens.group.GroupAccessCopy
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.isValidRelayUrl
import org.nostr.nostrord.utils.toRelayUrl
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.GroupAvatarUploadRow
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.useEscClose
import react.FC
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.option
import react.dom.html.ReactHTML.select
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.textarea
import react.useState
import web.cssom.ClassName

/** Sentinel select value that reveals the custom-relay text input. */
private const val CUSTOM_RELAY = "__custom__"

external interface CreateGroupModalProps : Props {
    var onClose: () -> Unit

    /** Navigate to the freshly created group (relay, groupId). Called before [onClose]. */
    var onCreated: ((relayUrl: String, groupId: String) -> Unit)?

    /** When true, render as "Create Channel" (a child of the current group). */
    var subgroup: Boolean?

    /** Parent group id when creating a subgroup. */
    var parentGroupId: String?

    /** Relay to create on; defaults to the active relay. */
    var relayUrl: String?
}

/**
 * Create-group modal — layout-first React port of the Compose [CreateGroupModal]: name,
 * optional group ID, relay selector, description, image URL, and the Private / Closed
 * access toggles. Submit is stubbed (closes); wiring to the repository comes after the
 * layout is validated.
 */
val CreateGroupModal =
    FC<CreateGroupModalProps> { props ->
        val isSubgroup = props.subgroup == true
        val repo = AppModule.nostrRepository
        val kind10009 = useStateFlow(repo.kind10009Relays)
        val groupTagRelays = useStateFlow(repo.groupTagRelays)
        val currentRelayUrl = useStateFlow(repo.currentRelayUrl)
        val relayList =
            (kind10009.toList() + groupTagRelays.toList() + currentRelayUrl)
                .filter { it.isNotBlank() }
                .distinct()
        // With no relays yet, offer a known NIP-29 group relay so the user can create without
        // typing one; "Custom relay…" stays available.
        val relayOptions = relayList.ifEmpty { RelayListManager.SUGGESTED_GROUP_RELAYS }

        val (name, setName) = useState { "" }
        val (groupId, setGroupId) = useState { "" }
        // The Group ID is an advanced option (the relay assigns a random one); reveal its field
        // only when the user opts in, so the common case stays uncluttered.
        val (showCustomId, setShowCustomId) = useState { false }
        val (selectedRelay, setSelectedRelay) =
            useState { props.relayUrl ?: currentRelayUrl.ifBlank { relayOptions.first() } }
        // Custom relay: picking "Custom relay…" in the select reveals a text input so a group can be
        // created on any relay, not just the listed ones.
        val (customRelay, setCustomRelay) = useState { "" }
        val usingCustom = selectedRelay == CUSTOM_RELAY
        // A subgroup must live on its parent's relay: the parent tag carries a relay-scoped
        // group id and the relay validates the link against its own state, so cross-relay
        // channels are not expressible. The relay is therefore not selectable in subgroup mode.
        val effectiveRelay = when {
            isSubgroup -> props.relayUrl ?: currentRelayUrl
            usingCustom -> customRelay.trim().toRelayUrl()
            else -> selectedRelay
        }
        val (about, setAbout) = useState { "" }
        val (picture, setPicture) = useState { "" }
        val (isPrivate, setIsPrivate) = useState { false }
        val (isClosed, setIsClosed) = useState { false }
        val (isRestricted, setIsRestricted) = useState { false }
        val (isHidden, setIsHidden) = useState { false }
        val (listPrivately, setListPrivately) = useState { false }
        val (busy, setBusy) = useState { false }
        val (error, setError) = useState<String?> { null }

        val relayWebUrl = effectiveRelay.replace("wss://", "https://").replace("ws://", "http://")
        // Offer the relay-website link only when the failure is about web-only creation or auth
        // (mirrors native): "website" matches the friendlyError text for the blocked case.
        val showRelayLink =
            relayWebUrl.isNotBlank() &&
                listOf("website", "authorization", "auth-required", "not allowed", "restricted", "blocked")
                    .any { error?.contains(it, ignoreCase = true) == true }

        fun submit() {
            if (name.isBlank()) {
                setError("Group name is required.")
                return
            }
            // Validate the (normalized) relay before publishing: an unchecked custom value like
            // "asdasd" would be saved and then fail to connect (same gate as Add Relay).
            if (!isValidRelayUrl(effectiveRelay)) {
                setError(if (usingCustom) "Enter a valid relay URL (e.g. relay.example.com)." else "Pick a relay.")
                return
            }
            setError(null)
            setBusy(true)
            launchApp {
                val result =
                    if (isSubgroup && props.parentGroupId != null) {
                        repo.createSubgroup(
                            parentGroupId = props.parentGroupId!!,
                            name = name.trim(),
                            about = about.trim().ifBlank { null },
                            relayUrl = effectiveRelay,
                            isPrivate = isPrivate,
                            isClosed = isClosed,
                            isRestricted = isRestricted,
                            isHidden = isHidden,
                            picture = picture.trim().ifBlank { null },
                            customGroupId = groupId.trim().ifBlank { null },
                            listPrivately = listPrivately,
                        )
                    } else {
                        repo.createGroup(
                            name = name.trim(),
                            about = about.trim().ifBlank { null },
                            relayUrl = effectiveRelay,
                            isPrivate = isPrivate,
                            isClosed = isClosed,
                            isRestricted = isRestricted,
                            isHidden = isHidden,
                            picture = picture.trim().ifBlank { null },
                            customGroupId = groupId.trim().ifBlank { null },
                            listPrivately = listPrivately,
                        )
                    }
                setBusy(false)
                when (result) {
                    is Result.Success -> {
                        // Open the new group's page, then dismiss the modal.
                        props.onCreated?.invoke(effectiveRelay, result.data)
                        props.onClose()
                    }
                    is Result.Error -> setError(friendlyError(result.error.cause?.message ?: result.error.message))
                }
            }
        }

        useEscClose { props.onClose() }

        div {
            className = ClassName("modal-overlay")
            onClick = { props.onClose() }
            div {
                className = ClassName("modal-card")
                onClick = { it.stopPropagation() }

                // Header
                div {
                    className = ClassName("modal-header")
                    div {
                        className = ClassName("modal-header-text")
                        div {
                            className = ClassName("modal-title")
                            +(if (isSubgroup) "Create Channel" else "Create a Group")
                        }
                        div {
                            className = ClassName("modal-subtitle")
                            +"Give your new group a name and description. You can always change these later."
                        }
                    }
                    button {
                        className = ClassName("modal-close")
                        onClick = { props.onClose() }
                        icon(Ic.Close)
                    }
                }

                // Group photo
                GroupAvatarUploadRow {
                    pictureUrl = picture
                    seed = groupId.ifBlank { name }
                    this.name = name
                    onPictureChange = { setPicture(it) }
                    onError = { setError(it) }
                }

                // Group Name
                div {
                    className = ClassName("field-label")
                    +"Group Name"
                }
                input {
                    className = ClassName("modal-input")
                    placeholder = "#example"
                    value = name
                    onChange = { event ->
                        setName(event.currentTarget.value)
                        setError(null)
                    }
                }

                // Group ID (optional): hidden behind a disclosure link so the common case
                // (random relay-assigned ID) stays uncluttered.
                if (showCustomId) {
                    div {
                        className = ClassName("field-label")
                        +"Group ID (optional)"
                    }
                    input {
                        className = ClassName("modal-input")
                        placeholder = "my-group"
                        value = groupId
                        onChange = { event ->
                            val cleaned =
                                event.currentTarget.value.lowercase().filter { c ->
                                    c.isLetterOrDigit() || c == '-' || c == '_'
                                }
                            setGroupId(cleaned)
                        }
                    }
                    div {
                        className = ClassName("field-hint")
                        +"Leave empty for a random ID. The relay may override your choice."
                    }
                } else {
                    button {
                        className = ClassName("field-link")
                        onClick = { setShowCustomId(true) }
                        +"Set a custom ID"
                    }
                }

                // Relay: fixed to the parent's relay for a subgroup, selectable otherwise.
                div {
                    className = ClassName("field-label")
                    +"Relay"
                }
                if (isSubgroup) {
                    div {
                        className = ClassName("modal-subtitle")
                        +"Created on ${effectiveRelay.removePrefix("wss://")} (same relay as the parent group)."
                    }
                } else {
                    select {
                        className = ClassName("modal-select")
                        value = if (usingCustom) CUSTOM_RELAY else selectedRelay
                        onChange = { event -> setSelectedRelay(event.currentTarget.value) }
                        relayOptions.forEach { relay ->
                            option {
                                value = relay
                                +relay.removePrefix("wss://")
                            }
                        }
                        option {
                            value = CUSTOM_RELAY
                            +"Custom relay…"
                        }
                    }
                    if (usingCustom) {
                        input {
                            className = ClassName("modal-input")
                            placeholder = "relay.example.com"
                            value = customRelay
                            onChange = { event ->
                                setCustomRelay(event.currentTarget.value)
                                setError(null)
                            }
                        }
                    }
                }

                // Description
                div {
                    className = ClassName("field-label")
                    +"Description"
                }
                textarea {
                    className = ClassName("modal-textarea")
                    placeholder = "What is this group about?"
                    rows = 3
                    value = about
                    onChange = { event -> setAbout(event.currentTarget.value) }
                }

                // Access settings
                div {
                    className = ClassName("access-section-title")
                    +"ACCESS SETTINGS"
                }
                accessToggle(
                    ic = Ic.Lock,
                    label = GroupAccessCopy.PRIVATE_LABEL,
                    description = GroupAccessCopy.PRIVATE_DESC,
                    checked = isPrivate,
                    onToggle = { setIsPrivate(!isPrivate) },
                )
                accessToggle(
                    ic = Ic.Block,
                    label = GroupAccessCopy.CLOSED_LABEL,
                    description = GroupAccessCopy.CLOSED_DESC,
                    checked = isClosed,
                    onToggle = { setIsClosed(!isClosed) },
                )
                accessToggle(
                    ic = Ic.Send,
                    label = GroupAccessCopy.RESTRICTED_LABEL,
                    description = GroupAccessCopy.RESTRICTED_DESC,
                    checked = isRestricted,
                    onToggle = { setIsRestricted(!isRestricted) },
                )
                accessToggle(
                    ic = Ic.VisibilityOff,
                    label = GroupAccessCopy.HIDDEN_LABEL,
                    description = GroupAccessCopy.HIDDEN_DESC,
                    checked = isHidden,
                    onToggle = { setIsHidden(!isHidden) },
                )
                // Local-list setting, not a relay flag: encrypted NIP-51 entry from the start.
                div {
                    className = ClassName("access-section-title")
                    +"YOUR LIST"
                }
                accessToggle(
                    ic = Ic.Shield,
                    label = GroupAccessCopy.LIST_PRIVATELY_LABEL,
                    description = GroupAccessCopy.LIST_PRIVATELY_DESC,
                    checked = listPrivately,
                    onToggle = { setListPrivately(!listPrivately) },
                )

                if (error != null) {
                    div {
                        className = ClassName("modal-error")
                        span { +error }
                        if (showRelayLink) {
                            a {
                                className = ClassName("inline-link")
                                href = relayWebUrl
                                asDynamic().target = "_blank"
                                rel = "noopener noreferrer"
                                +" Open relay website →"
                            }
                        }
                    }
                }

                // Footer
                div {
                    className = ClassName("modal-footer")
                    button {
                        className = ClassName("btn-text")
                        onClick = { props.onClose() }
                        +"Cancel"
                    }
                    button {
                        className = ClassName("btn-primary")
                        disabled = name.isBlank() || effectiveRelay.isBlank() || busy
                        onClick = { submit() }
                        +(
                            if (busy) {
                                "Creating…"
                            } else if (isSubgroup) {
                                "Create Channel"
                            } else {
                                "Create Group"
                            }
                            )
                    }
                }
            }
        }
    }

// Map a raw relay error to a short, friendly message (mirrors the native CreateGroupModal). The
// "must use the website" wording is what drives the "Open relay website" link in the error.
private fun friendlyError(raw: String?): String = when {
    raw == null -> "Something went wrong. Try again."
    // "blocked: to create groups open https://... in your web browser"
    raw.contains("blocked:", ignoreCase = true) ->
        "Group creation on this relay must be done via the relay's website."
    raw.contains("auth-required", ignoreCase = true) ||
        raw.contains("not allowed", ignoreCase = true) ||
        raw.contains("restricted", ignoreCase = true) ->
        "This relay requires authorization to create groups."
    raw.contains("did not respond", ignoreCase = true) ||
        raw.contains("timeout", ignoreCase = true) ->
        "Relay did not respond. Try again."
    raw.contains("Not connected", ignoreCase = true) ->
        "Not connected to relay. Try again."
    else -> raw.ifBlank { "Failed to create group." }
}
