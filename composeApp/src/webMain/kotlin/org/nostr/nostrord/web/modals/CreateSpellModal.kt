package org.nostr.nostrord.web.modals

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.Spell
import org.nostr.nostrord.nostr.SpellDraft
import org.nostr.nostrord.nostr.toSpell
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.useEscClose
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.useState
import web.cssom.ClassName

external interface CreateSpellModalProps : Props {
    var onDismiss: () -> Unit
    var onCreated: (Spell) -> Unit
}

/**
 * Build a spell and pin it to the rail. Mirrors the Compose `CreateSpellModal`: both are text
 * fields over [SpellDraft.toSpell], so validation lives in commonMain and cannot drift.
 */
val CreateSpellModal = FC<CreateSpellModalProps> { props ->
    var draft by useState(SpellDraft())
    var error by useState<String?>(null)
    useEscClose { props.onDismiss() }

    div {
        className = ClassName("modal-overlay")
        onClick = { props.onDismiss() }

        div {
            className = ClassName("modal-card")
            onClick = { it.stopPropagation() }

            div {
                className = ClassName("modal-header")
                div {
                    className = ClassName("modal-header-text")
                    div {
                        className = ClassName("modal-title")
                        +"New spell"
                    }
                    div {
                        className = ClassName("modal-subtitle")
                        +"A saved query, pinned to the rail. Name relays and leave authors blank for a relay feed."
                    }
                }
                button {
                    className = ClassName("modal-close")
                    onClick = { props.onDismiss() }
                    icon(Ic.Close)
                }
            }

            field("Name", draft.name, "Bitcoin from friends") { draft = draft.copy(name = it) }
            field("Kinds", draft.kinds, "1, 30023 (blank = any)") { draft = draft.copy(kinds = it) }
            field("Authors", draft.authors, "\$contacts, \$me or an npub") { draft = draft.copy(authors = it) }
            field("Hashtags", draft.hashtags, "bitcoin, nostr") { draft = draft.copy(hashtags = it) }
            field("Relays", draft.relays, "relay.damus.io (blank = your read relays)") {
                draft = draft.copy(relays = it)
            }

            error?.let { message ->
                div {
                    className = ClassName("field-hint modal-error-text")
                    +message
                }
            }

            div {
                className = ClassName("modal-footer")
                button {
                    className = ClassName("btn-text")
                    onClick = { props.onDismiss() }
                    +"Cancel"
                }
                button {
                    className = ClassName("btn-primary")
                    onClick = {
                        when (val result = draft.toSpell()) {
                            is Result.Success -> {
                                AppModule.spellLibrary.add(result.data)
                                props.onCreated(result.data)
                            }
                            is Result.Error -> error = result.error.message
                        }
                    }
                    +"Create"
                }
            }
        }
    }
}

private fun react.ChildrenBuilder.field(
    caption: String,
    current: String,
    hint: String,
    onValue: (String) -> Unit,
) {
    div {
        className = ClassName("field-label")
        +caption
    }
    input {
        className = ClassName("modal-input")
        value = current
        placeholder = hint
        onChange = { event -> onValue(event.currentTarget.value) }
    }
}
