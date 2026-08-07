package org.nostr.nostrord.web.components

import org.nostr.nostrord.web.screens.ctxItem
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.useEffect
import react.useRef
import web.cssom.ClassName
import web.html.HTMLDivElement
import web.window.window

external interface GroupContextMenuProps : Props {
    /** Viewport coordinates of the click that opened the menu. */
    var x: Double
    var y: Double
    var muted: Boolean
    var onClose: () -> Unit
    var onToggleMute: () -> Unit
}

/**
 * Right-click / long-press menu for a group row (rail chip, channel row). Shares the
 * message menu's `.ctx-menu` chrome so both open the same card at the cursor.
 *
 * Native parity: ui/components/layout/GroupContextMenu.kt.
 */
val GroupContextMenu =
    FC<GroupContextMenuProps> { props ->
        val menuRef = useRef<HTMLDivElement>(null)

        // Clamp into the viewport once mounted; .ctx-menu starts visibility:hidden so the
        // pre-clamp position never flashes.
        useEffect(props.x, props.y) {
            val el = menuRef.current?.asDynamic() ?: return@useEffect
            val w = el.offsetWidth as Int
            val h = el.offsetHeight as Int
            var left = props.x
            if (left + w > window.innerWidth - 8) left = (window.innerWidth - 8.0 - w).coerceAtLeast(8.0)
            var top = props.y
            if (top + h > window.innerHeight - 8) top = (props.y - h).coerceAtLeast(8.0)
            el.style.left = "${left}px"
            el.style.top = "${top}px"
            el.style.visibility = "visible"
        }

        useEscClose { props.onClose() }

        div {
            className = ClassName("ctx-overlay")
            onClick = { props.onClose() }
            // No preventDefault: closing is enough, and swallowing it would deny the browser
            // menu a right-click outside the row is entitled to (chat parity).
            onContextMenu = { props.onClose() }
        }
        div {
            ref = menuRef
            className = ClassName("ctx-menu")
            ctxItem(
                if (props.muted) Ic.Notifications else Ic.NotificationsOff,
                if (props.muted) "Unmute group" else "Mute group",
            ) {
                props.onToggleMute()
                props.onClose()
            }
        }
    }

/** Menu anchor: where the row was right-clicked, plus which group it belongs to. */
data class GroupMenuAnchor(val groupId: String, val x: Double, val y: Double)
