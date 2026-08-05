package org.nostr.nostrord.web.modals

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.Portal
import react.FC
import react.Props

/**
 * The one place the AV room renders.
 *
 * Mounted once over the frame, like [org.nostr.nostrord.web.components.ZapModalHost]. Every
 * entry point (the in-chat banner, the sidebar's voice row) calls `AvSpaceHost.show(...)`
 * instead of mounting its own copy. Two mount points meant two modals, and the sidebar's lived
 * inside the mobile drawer, whose `transform` traps a fixed overlay at the drawer's width.
 */
val AvSpaceModalHost =
    FC<Props> {
        val host = AppModule.avSpaceHost
        val visible = useStateFlow(host.roomVisible)
        val session = useStateFlow(host.session)
        val groups = useStateFlow(AppModule.nostrRepository.groups)

        val live = session?.takeIf { visible } ?: return@FC
        Portal {
            AvSpaceModal {
                groupId = live.groupId
                groupName = groups.firstOrNull { it.id == live.groupId }?.name.orEmpty()
                onClose = { host.hide() }
            }
        }
    }
