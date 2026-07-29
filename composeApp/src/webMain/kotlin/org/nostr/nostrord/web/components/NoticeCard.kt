package org.nostr.nostrord.web.components

import react.ChildrenBuilder
import react.dom.html.ReactHTML.div
import web.cssom.ClassName

/**
 * Tinted callout with an icon, title and body: brand by default, warning when [alert].
 * The web counterpart of the Compose `InfoCard`.
 */
fun ChildrenBuilder.noticeCard(
    title: String,
    body: String,
    alert: Boolean = false,
    ic: Ic = if (alert) Ic.Warning else Ic.Info,
) {
    div {
        className = ClassName(if (alert) "protect-info alert" else "protect-info")
        icon(ic)
        div {
            div {
                className = ClassName("protect-info-title")
                +title
            }
            div {
                className = ClassName("protect-info-desc")
                +body
            }
        }
    }
}
