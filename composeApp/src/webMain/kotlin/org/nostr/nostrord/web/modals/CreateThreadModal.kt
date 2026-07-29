package org.nostr.nostrord.web.modals

import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.UploadButton
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.useEscClose
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.textarea
import react.useState
import web.cssom.ClassName

external interface CreateThreadModalProps : Props {
    var onClose: () -> Unit

    /** Publish a new thread: (title, content). Title is optional (becomes a NIP-14 subject). */
    var onCreate: (String, String) -> Unit
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

        useEscClose { props.onClose() }

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
                textarea {
                    className = ClassName("modal-textarea")
                    placeholder = "Start a discussion..."
                    value = body
                    onChange = { event -> setBody(event.currentTarget.value) }
                }
                uploadError?.let {
                    div {
                        className = ClassName("form-error")
                        +it
                    }
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
                            props.onCreate(title, body)
                            props.onClose()
                        }
                        +"Publish thread"
                    }
                }
            }
        }
    }
