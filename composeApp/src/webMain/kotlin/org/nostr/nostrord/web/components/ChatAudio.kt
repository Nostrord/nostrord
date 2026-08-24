package org.nostr.nostrord.web.components

import js.objects.unsafeJso
import kotlinx.coroutines.awaitCancellation
import org.nostr.nostrord.ui.media.formatDuration
import org.nostr.nostrord.ui.media.mediaDisplayName
import react.FC
import react.Props
import react.dom.html.ReactHTML.audio
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName
import web.html.HTMLAudioElement
import web.html.InputType
import web.html.range

external interface ChatAudioProps : Props {
    var audioUrl: String
}

/**
 * A chat inline audio clip: play/pause, file name, seek bar, elapsed/total and the save button,
 * mirroring the native `AudioPlayerChrome`. The `<audio>` element is the engine only, kept out of
 * sight, because the browser's own control bar carries a download that names the file after
 * whatever object the host serves and can't be reprogrammed.
 *
 * Honors Settings > Media > Auto-load: with it off, a tap-to-load placeholder shows until the
 * reader reveals this one clip. The element is released on unmount so switching groups/relays
 * doesn't pile up open media handles.
 */
val ChatAudio =
    FC<ChatAudioProps> { props ->
        val autoLoad = useAutoLoadMedia()
        val (revealed, setRevealed) = useState { false }
        val audioRef = useRef<HTMLAudioElement>(null)
        val (isPlaying, setIsPlaying) = useState { false }
        val (positionSec, setPositionSec) = useState { 0.0 }
        val (durationSec, setDurationSec) = useState { 0.0 }

        val showPlayer = autoLoad || revealed

        useEffect(props.audioUrl) {
            try {
                awaitCancellation()
            } finally {
                val node = audioRef.current ?: return@useEffect
                runCatching {
                    node.asDynamic().pause()
                    node.removeAttribute("src")
                    node.asDynamic().load()
                }
            }
        }

        if (!showPlayer) {
            mediaGatePlaceholder("audio") { setRevealed(true) }
        } else {
            div {
                className = ClassName("msg-audio-player")

                button {
                    className = ClassName("msg-audio-toggle")
                    title = if (isPlaying) "Pause" else "Play"
                    onClick = { event ->
                        event.stopPropagation()
                        val node = audioRef.current?.asDynamic()
                        if (node != null) {
                            if (isPlaying) node.pause() else node.play()
                        }
                        Unit
                    }
                    icon(if (isPlaying) Ic.Pause else Ic.PlayArrow)
                }

                div {
                    className = ClassName("msg-audio-body")

                    div {
                        className = ClassName("msg-audio-name")
                        +mediaDisplayName(props.audioUrl)
                    }

                    input {
                        className = ClassName("msg-audio-seek")
                        type = InputType.range
                        // Duration doubles as the "seekable yet?" flag: metadata may not have
                        // resolved, and a live stream never reports one.
                        disabled = durationSec <= 0.0
                        value = positionSec.toString()
                        // Percent for the filled half of the track (see .msg-audio-seek).
                        style = unsafeJso {
                            asDynamic()["--seek"] =
                                if (durationSec > 0.0) "${(positionSec / durationSec * 100).coerceIn(0.0, 100.0)}%" else "0%"
                        }
                        asDynamic().min = "0"
                        asDynamic().max = (if (durationSec > 0.0) durationSec else 0.0).toString()
                        asDynamic().step = "any"
                        onChange = { event ->
                            val seconds = event.currentTarget.value.toDoubleOrNull() ?: 0.0
                            audioRef.current?.asDynamic()?.currentTime = seconds
                            setPositionSec(seconds)
                        }
                    }

                    div {
                        className = ClassName("msg-audio-times")
                        span { +formatDuration((positionSec * 1000).toLong()) }
                        if (durationSec > 0.0) {
                            span { +formatDuration((durationSec * 1000).toLong()) }
                        }
                    }
                }

                MediaSaveButton {
                    url = props.audioUrl
                    fallbackBase = "audio"
                    label = "Save audio"
                    className = "msg-audio-save"
                }

                audio {
                    ref = audioRef
                    className = ClassName("msg-audio-engine")
                    src = props.audioUrl
                    preload = "metadata"
                    onPlay = { setIsPlaying(true) }
                    onPause = { setIsPlaying(false) }
                    onEnded = { setIsPlaying(false) }
                    onTimeUpdate = { event -> setPositionSec(event.currentTarget.currentTime) }
                    onLoadedMetadata = { event -> setDurationSec(usableDuration(event.currentTarget.duration)) }
                    onDurationChange = { event -> setDurationSec(usableDuration(event.currentTarget.duration)) }
                }
            }
        }
    }

/** 0 for a duration the element can't state yet, or reports as bogus (streams, some CDN files). */
private fun usableDuration(duration: Double): Double = if (duration.isFinite() && duration > 0.0) duration else 0.0
