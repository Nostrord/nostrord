package org.nostr.nostrord.web.modals

import org.nostr.nostrord.web.components.useEscClose
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName
import web.html.InputType
import web.html.checkbox

external interface JoinGroupConfirmModalProps : Props {
    var groupName: String?

    /** Closed groups send a join request an admin has to approve, so the copy differs. */
    var isGroupClosed: Boolean
    var onConfirm: (listPrivately: Boolean) -> Unit
    var onClose: () -> Unit
}

/**
 * The single confirm step every join goes through, so the public/private choice is offered the
 * same way from the composer bar, the header button and an invite code. Mirrors the native
 * `JoinGroupConfirmDialog`.
 *
 * The choice belongs here rather than after the join: a group added publicly and made private
 * afterwards has already gone out in the clear once, and relays keep that version.
 */
val JoinGroupConfirmModal =
    FC<JoinGroupConfirmModalProps> { props ->
        val (listPrivately, setListPrivately) = useState { false }
        val name = props.groupName?.takeIf { it.isNotBlank() } ?: "this group"
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
                        className = ClassName("modal-title")
                        +(if (props.isGroupClosed) "Request to join $name" else "Join $name")
                    }
                }

                div {
                    className = ClassName("modal-body")
                    div {
                        className = ClassName("modal-reason")
                        +(
                            if (props.isGroupClosed) {
                                "An admin has to approve your request before you can post."
                            } else {
                                "The group is added to your list so it follows you to your other apps."
                            }
                            )
                    }
                    label {
                        className = ClassName("join-private-row")
                        input {
                            type = InputType.checkbox
                            checked = listPrivately
                            onChange = { setListPrivately(it.target.checked) }
                        }
                        span {
                            className = ClassName("join-private-text")
                            span {
                                className = ClassName("join-private-label")
                                +"Add privately"
                            }
                            span {
                                className = ClassName("join-private-desc")
                                +(
                                    "Encrypted on your list, so nobody can see you are in this group. " +
                                        "People who follow you stop discovering it."
                                    )
                            }
                        }
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
                        onClick = { props.onConfirm(listPrivately) }
                        +(if (props.isGroupClosed) "Send request" else "Join")
                    }
                }
            }
        }
    }
