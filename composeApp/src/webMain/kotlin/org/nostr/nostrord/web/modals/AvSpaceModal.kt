package org.nostr.nostrord.web.modals

import kotlinx.coroutines.awaitCancellation
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.livekit.AvConnectionState
import org.nostr.nostrord.network.livekit.MediaEngine
import org.nostr.nostrord.ui.screens.avspace.AvSpaceParticipant
import org.nostr.nostrord.ui.screens.avspace.AvSpaceViewModel
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.displayNameOf
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.useEscClose
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.video
import react.useEffect
import react.useMemo
import react.useRef
import web.cssom.ClassName
import web.html.HTMLVideoElement

external interface AvSpaceModalProps : Props {
    var groupId: String
    var groupName: String
    var onClose: () -> Unit
}

/**
 * The live audio/video room for a NIP-29 group (spec: relay-hosted LiveKit, kind 39004).
 *
 * The roster always renders, whether or not this browser has joined, because kind 39004 is
 * published by the relay. Speaking rings, mute badges and video tiles only light up for
 * participants the local LiveKit connection can see.
 */
val AvSpaceModal =
    FC<AvSpaceModalProps> { props ->
        val repo = AppModule.nostrRepository
        val selfPubkey = useStateFlow(repo.activePubkey)
        // One engine per open room: recreating it on re-render would drop the connection.
        val engine = useMemo(props.groupId) { MediaEngine() }
        val vm = useViewModel(props.groupId) { AvSpaceViewModel(repo, props.groupId, selfPubkey, engine) }

        val participants = useStateFlow(vm.participants)
        val connection = useStateFlow(vm.connectionState)
        val micOn = useStateFlow(vm.micEnabled)
        val cameraOn = useStateFlow(vm.cameraEnabled)
        val error = useStateFlow(vm.error)
        val audioBlocked = useStateFlow(vm.audioPlaybackBlocked)
        val userMetadata = useStateFlow(repo.userMetadata)

        useEscClose { props.onClose() }

        val connected = connection == AvConnectionState.Connected

        /**
         * Joined as far as the user is concerned. A reconnect counts: the room handle and the
         * capture are still live there, so leaving has to stay reachable. The initial join does
         * not, since there is nothing to leave yet.
         */
        val inRoom = connected || connection == AvConnectionState.Reconnecting
        val anyVideo = useStateFlow(vm.hasVideo)
        val onStage = useStateFlow(vm.onStage)
        val listeners = useStateFlow(vm.listeners)

        div {
            className = ClassName("modal-overlay av-space-overlay")
            onClick = { props.onClose() }
            div {
                className = ClassName("modal-card av-space-card")
                onClick = { it.stopPropagation() }

                div {
                    className = ClassName("modal-header av-space-header")
                    span {
                        className = ClassName("live-badge")
                        span { className = ClassName("live-dot") }
                        +"LIVE"
                    }
                    div {
                        className = ClassName("modal-header-text")
                        div {
                            className = ClassName("modal-title")
                            +(if (anyVideo) "Video room" else "Voice room")
                        }
                        div {
                            className = ClassName("modal-subtitle")
                            +"${props.groupName} · ${participants.size} in the room"
                        }
                    }
                    button {
                        className = ClassName("modal-close")
                        onClick = { props.onClose() }
                        icon(Ic.Close)
                    }
                }

                error?.let { message ->
                    div {
                        className = ClassName("av-space-error")
                        span { +message }
                        button {
                            className = ClassName("av-space-error-close")
                            onClick = { vm.dismissError() }
                            icon(Ic.Close)
                        }
                    }
                }

                // Autoplay policy: the browser refused to start audio without a gesture, so
                // give it one. Success fires audioPlaybackChanged and the banner clears itself.
                if (audioBlocked) {
                    div {
                        className = ClassName("av-space-audio-unlock")
                        span { +"Your browser blocked the room's audio" }
                        button {
                            className = ClassName("av-space-audio-unlock-btn")
                            onClick = { vm.startAudio() }
                            +"Enable audio"
                        }
                    }
                }

                div {
                    className = ClassName("av-space-body")
                    if (participants.isEmpty()) {
                        div {
                            className = ClassName("mod-empty")
                            +"Nobody is in the room yet."
                        }
                    } else if (anyVideo) {
                        div {
                            className = ClassName("av-video-grid")
                            participants.forEach { participant ->
                                VideoTile {
                                    key = participant.pubkey
                                    this.participant = participant
                                    this.userMetadata = userMetadata
                                    this.engine = engine
                                }
                            }
                        }
                    } else {
                        // Both sections always render. Letting one vanish when it empties makes
                        // the room jump every time the last speaker mutes.
                        AudioSection {
                            label = "On stage"
                            people = onStage
                            this.userMetadata = userMetadata
                            small = false
                            emptyNote = "Nobody is speaking yet."
                        }
                        AudioSection {
                            label = "Listeners"
                            people = listeners
                            this.userMetadata = userMetadata
                            small = true
                            emptyNote = "Everyone here is on stage."
                        }
                    }
                }

                div {
                    className = ClassName("av-space-controls")
                    if (!vm.canJoin) {
                        div {
                            className = ClassName("av-space-note")
                            +"Live audio and video are only available on the web for now."
                        }
                    } else if (inRoom) {
                        // The whole bar stays up while connecting or reconnecting: hiding it
                        // there strands the user with a hot microphone and no way out, which
                        // is exactly when a flapping connection makes them want to leave.
                        ControlButton {
                            active = micOn
                            glyph = if (micOn) Ic.Mic else Ic.MicOff
                            title = if (micOn) "Mute" else "Unmute"
                            enabled = connected
                            onPress = { vm.toggleMic() }
                        }
                        ControlButton {
                            active = cameraOn
                            glyph = if (cameraOn) Ic.Videocam else Ic.VideocamOff
                            title = if (cameraOn) "Turn camera off" else "Turn camera on"
                            enabled = connected
                            onPress = { vm.toggleCamera() }
                        }
                        button {
                            className = ClassName("av-leave-btn")
                            onClick = { vm.leave() }
                            icon(Ic.CallEnd)
                            span { +"Leave" }
                        }
                    } else {
                        button {
                            className = ClassName("av-join-btn")
                            disabled = connection != AvConnectionState.Disconnected
                            onClick = { vm.join() }
                            +(
                                when (connection) {
                                    AvConnectionState.Connecting -> "Joining..."
                                    AvConnectionState.Reconnecting -> "Reconnecting..."
                                    else -> "Join room"
                                }
                                )
                        }
                    }
                }
            }
        }
    }

private external interface AudioSectionProps : Props {
    var label: String
    var people: List<AvSpaceParticipant>
    var userMetadata: Map<String, UserMetadata>

    /** Listeners render at the smaller tile size. */
    var small: Boolean

    /** Shown in place of the grid, so an empty section still holds its space. */
    var emptyNote: String
}

private val AudioSection =
    FC<AudioSectionProps> { props ->
        div {
            className = ClassName("av-section-label")
            +"${props.label} · ${props.people.size}"
        }
        if (props.people.isEmpty()) {
            div {
                className = ClassName("av-section-empty")
                +props.emptyNote
            }
            return@FC
        }
        div {
            className = ClassName(if (props.small) "av-audio-grid av-audio-grid-small" else "av-audio-grid")
            props.people.forEach { participant ->
                div {
                    key = participant.pubkey
                    className = ClassName("av-audio-tile")
                    div {
                        className = ClassName(if (participant.isSpeaking) "av-audio-ring av-speaking" else "av-audio-ring")
                        WebAvatar {
                            url = props.userMetadata[participant.pubkey]?.picture
                            seed = participant.pubkey
                            name = displayNameOf(participant.pubkey, props.userMetadata)
                            cls = if (props.small) "av-avatar-small" else "av-avatar"
                        }
                        span {
                            className = ClassName("av-audio-badge")
                            icon(if (participant.micEnabled) Ic.Mic else Ic.MicOff)
                        }
                    }
                    span {
                        className = ClassName("av-audio-name")
                        +(if (participant.isSelf) "You" else displayNameOf(participant.pubkey, props.userMetadata))
                    }
                }
            }
        }
    }

private external interface VideoTileProps : Props {
    var participant: AvSpaceParticipant
    var userMetadata: Map<String, UserMetadata>
    var engine: MediaEngine
}

private val VideoTile =
    FC<VideoTileProps> { props ->
        val participant = props.participant
        val videoRef = useRef<HTMLVideoElement>(null)

        // The engine owns the MediaStreamTrack; the tile only lends it a <video> to render
        // into, and takes it back on unmount so a removed tile stops decoding frames.
        val identity = participant.identity
        useEffect(identity, participant.cameraEnabled) {
            val element = videoRef.current
            if (identity != null && element != null && participant.cameraEnabled) {
                props.engine.attachVideo(identity, element)
            }
            try {
                awaitCancellation()
            } finally {
                val current = videoRef.current
                if (identity != null && current != null) props.engine.detachVideo(identity, current)
            }
        }

        div {
            className = ClassName(if (participant.isSpeaking) "av-video-tile av-speaking" else "av-video-tile")
            if (participant.cameraEnabled) {
                video {
                    ref = videoRef
                    className = ClassName("av-video")
                    autoPlay = true
                    playsInline = true
                    // The local preview must stay muted or it feeds back into the mic.
                    muted = participant.isSelf
                }
            } else {
                div {
                    className = ClassName("av-video-fallback")
                    WebAvatar {
                        url = props.userMetadata[participant.pubkey]?.picture
                        seed = participant.pubkey
                        name = displayNameOf(participant.pubkey, props.userMetadata)
                        cls = "av-avatar"
                    }
                }
            }
            div {
                className = ClassName("av-video-label")
                icon(if (participant.micEnabled) Ic.Mic else Ic.MicOff)
                span {
                    +(if (participant.isSelf) "You" else displayNameOf(participant.pubkey, props.userMetadata))
                }
            }
        }
    }

private external interface ControlButtonProps : Props {
    var active: Boolean
    var glyph: Ic
    var title: String
    var enabled: Boolean
    var onPress: () -> Unit
}

private val ControlButton =
    FC<ControlButtonProps> { props ->
        button {
            className = ClassName(if (props.active) "av-ctrl av-ctrl-on" else "av-ctrl")
            this.title = props.title
            disabled = !props.enabled
            onClick = { props.onPress() }
            icon(props.glyph)
        }
    }
