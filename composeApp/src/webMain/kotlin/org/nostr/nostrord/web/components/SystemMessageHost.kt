package org.nostr.nostrord.web.components

import kotlinx.coroutines.delay
import org.nostr.nostrord.di.AppModule
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.useEffect
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

/** How long a system message stays up before it fades out. */
private const val TOAST_VISIBLE_MS = 4_000L

/**
 * Renders [AppModule.systemMessages] as a transient toast at the bottom of the page.
 *
 * Mount once, at the app root: the flow carries one-shot notices the user did not
 * explicitly trigger (a revoked signer, a sync that could not be signed) as well as
 * action confirmations, and without a host every one of them is dropped.
 *
 * Native parity: ui/components/layout/SystemMessageHost.kt.
 */
val SystemMessageHost =
    FC<Props> {
        val (message, setMessage) = useState<String?> { null }

        useEffectOnce {
            AppModule.systemMessages.collect { setMessage(it) }
        }
        // Keyed on the message so a second notice restarts the timer rather than
        // inheriting the first one's remaining time.
        useEffect(message) {
            if (message != null) {
                delay(TOAST_VISIBLE_MS)
                setMessage(null)
            }
        }

        message?.let { text ->
            div {
                className = ClassName("system-toast")
                +text
            }
        }
    }
