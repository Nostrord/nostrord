package org.nostr.nostrord.web.modals

import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.ui.mentions.MentionAutocomplete
import org.nostr.nostrord.ui.mentions.MentionCtx
import org.nostr.nostrord.ui.screens.group.MentionableGroup
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.MentionMatch
import org.nostr.nostrord.web.components.MentionPopup
import org.nostr.nostrord.web.components.UploadButton
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.mentionMatches
import org.nostr.nostrord.web.components.useEscClose
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.textarea
import react.useState
import web.cssom.ClassName
import web.html.InputType
import web.html.checkbox

external interface CreateThreadModalProps : Props {
    var onClose: () -> Unit

    /**
     * Publish a new thread: (title, content, shareToChat, mentions). Title becomes the
     * subject/title tags; mentions maps each typed `@displayName` to its pubkey. `%group`
     * mentions are already resolved to their `nostr:naddr` inside content.
     */
    var onCreate: (String, String, Boolean, Map<String, String>) -> Unit

    /** `@user` candidates (the group's members) and `%group` candidates, as in the composers. */
    var members: List<String>
    var allGroups: List<MentionableGroup>
    var userMetadata: Map<String, UserMetadata>

    /** Relay's NIP-11 pubkey, the naddr author of a `%group` mention on that relay. */
    var relayPubkeyOf: (String) -> String?
}

/**
 * Compose-a-new-thread modal (kind:11 root): an optional title plus the body, over the threads
 * page. Publish enables once the body is non-blank. Mirrors the prototype's ThreadCompose, shown
 * as a modal (the page itself stays a page). Logic stays in ThreadsViewModel; this is pure UI.
 */
val CreateThreadModal =
    FC<CreateThreadModalProps> { props ->
        val (title, setTitle) = useState { "" }
        val (body, setBody) = useState { "" }
        val (uploadError, setUploadError) = useState<String?> { null }
        // Default on: announcing the new thread in chat is the common case; one click opts out.
        val (shareToChat, setShareToChat) = useState { true }

        // Same @user / %group autocomplete the chat and reply composers carry.
        val (mention, setMention) = useState<MentionCtx?> { null }
        val (mentions, setMentions) = useState<Map<String, String>> { emptyMap() }
        val (groupMentions, setGroupMentions) = useState<Map<String, String>> { emptyMap() }
        val (mentionSelected, setMentionSelected) = useState { 0 }
        val suggestions = mentionMatches(mention, props.members, props.userMetadata, props.allGroups, props.relayPubkeyOf)

        fun insertMention(mm: MentionMatch) {
            val ctx = mention ?: return
            setBody(MentionAutocomplete.insert(body, ctx, mm.label).text)
            if (mm.group) setGroupMentions { it + (mm.label to mm.ref) } else setMentions { it + (mm.label to mm.seed) }
            setMention(null)
            setMentionSelected(0)
        }

        useEscClose { if (mention != null) setMention(null) else props.onClose() }

        div {
            className = ClassName("modal-overlay")
            onClick = { props.onClose() }
            div {
                className = ClassName("modal-card")
                onClick = { it.stopPropagation() }

                div {
                    className = ClassName("modal-header")
                    div {
                        className = ClassName("modal-header-text")
                        div {
                            className = ClassName("modal-title")
                            +"New thread"
                        }
                        div {
                            className = ClassName("modal-subtitle")
                            +"Start a new discussion in this group."
                        }
                    }
                    button {
                        className = ClassName("modal-close")
                        onClick = { props.onClose() }
                        icon(Ic.Close)
                    }
                }

                div {
                    className = ClassName("field-label")
                    +"Title"
                }
                input {
                    className = ClassName("modal-input")
                    placeholder = "Thread title"
                    value = title
                    onChange = { event -> setTitle(event.currentTarget.value) }
                }
                div {
                    className = ClassName("field-label field-label-row")
                    +"Content"
                    // Attach media to the thread body (rendered inline like chat).
                    UploadButton {
                        cls = "thread-compose-attach"
                        icon = Ic.AttachFile
                        onUploaded = { up ->
                            setUploadError(null)
                            setBody { prev -> if (prev.isBlank()) up.url else "$prev ${up.url}" }
                        }
                        onError = { setUploadError(it) }
                    }
                }
                div {
                    className = ClassName("mention-anchor")
                    // Stable keys: without them, mounting the popup shifts the textarea's position
                    // among its siblings and React remounts it, dropping the caret mid-word.
                    if (mention != null && suggestions.isNotEmpty()) {
                        MentionPopup {
                            key = "mention-popup"
                            matches = suggestions
                            trigger = mention.trigger
                            selected = mentionSelected
                            onPick = { insertMention(it) }
                            onHover = { setMentionSelected(it) }
                        }
                    }
                    textarea {
                        key = "thread-body"
                        className = ClassName("modal-textarea")
                        placeholder = "Start a discussion..."
                        value = body
                        onChange = { event ->
                            val v = event.currentTarget.value
                            setBody(v)
                            val cursor = (event.currentTarget.asDynamic().selectionStart as? Int) ?: v.length
                            setMention(MentionAutocomplete.detect(v, cursor))
                            setMentionSelected(0)
                        }
                        onKeyDown = { e ->
                            val hasMatches = mention != null && suggestions.isNotEmpty()
                            when {
                                hasMatches && e.key == "ArrowDown" -> {
                                    e.preventDefault()
                                    setMentionSelected { (it + 1).coerceAtMost(suggestions.size - 1) }
                                }
                                hasMatches && e.key == "ArrowUp" -> {
                                    e.preventDefault()
                                    setMentionSelected { (it - 1).coerceAtLeast(0) }
                                }
                                hasMatches && (e.key == "Enter" || e.key == "Tab") -> {
                                    e.preventDefault()
                                    insertMention(suggestions[mentionSelected.coerceIn(0, suggestions.size - 1)])
                                }
                            }
                        }
                        onBlur = { setMention(null) }
                    }
                }
                uploadError?.let {
                    div {
                        className = ClassName("form-error")
                        +it
                    }
                }

                label {
                    className = ClassName("thread-share-check")
                    input {
                        type = InputType.checkbox
                        checked = shareToChat
                        onChange = { event -> setShareToChat(event.currentTarget.checked) }
                    }
                    +"Share to chat"
                }

                div {
                    className = ClassName("modal-footer")
                    button {
                        className = ClassName("btn-text")
                        onClick = { props.onClose() }
                        +"Cancel"
                    }
                    button {
                        className = ClassName("btn-primary")
                        disabled = title.isBlank() || body.isBlank()
                        onClick = {
                            // %group mentions resolve to their naddr here; @user mentions ride the
                            // map so the event also carries their p tags.
                            var content = body
                            groupMentions.forEach { (name, ref) -> content = content.replace("%$name", ref) }
                            props.onCreate(title, content, shareToChat, mentions)
                            props.onClose()
                        }
                        +"Publish thread"
                    }
                }
            }
        }
    }
