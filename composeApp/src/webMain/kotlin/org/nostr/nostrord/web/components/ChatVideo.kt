package org.nostr.nostrord.web.components

import js.objects.unsafeJso
import kotlinx.browser.window
import kotlinx.coroutines.awaitCancellation
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.video
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName
import web.html.HTMLVideoElement

external interface ChatVideoProps : Props {
    var videoUrl: String

    /** Optional NIP-92 imeta thumbnail, shown as the poster so a not-yet-loaded
     *  video previews a frame instead of a black box. */
    var posterUrl: String?

    /** NIP-68 imeta (width, height) hint. When present the player box is reserved at the
     *  exact aspect ratio before metadata loads, so the row never grows on load. */
    var dimensions: Pair<Int, Int>?
}

// How long a metadata load may stall before we abort and retry. nostr.build
// routinely accepts the socket and sends nothing (readyState 0 / networkState
// LOADING, no `loadedmetadata`, no `error`); a fresh request usually succeeds, so
// keep this short. Reset by any real progress.
private const val WATCHDOG_MS = 5000

// Silent watchdog re-mounts (fresh connection) before showing the Retry panel.
private const val MAX_AUTO_RETRIES = 4

/**
 * A chat inline video.
 *
 * When Settings > Media > Auto-load is ON (default), the video loads its metadata
 * and shows a real first frame with native controls, ready to play — but only once
 * it scrolls within ~one viewport (an IntersectionObserver gates the fetch). This
 * keeps the "comes back after a while" burst in check: returning to a group no
 * longer fires a Range request for every off-screen video at once, only for the
 * handful actually near the viewport.
 *
 * When Auto-load is OFF, nothing is fetched (not even the poster); a tap-to-load
 * placeholder is shown until the user reveals this single video.
 *
 * Either way a stalled metadata load (nostr.build accepting the socket then sending
 * nothing) is covered by a watchdog: abort + silent retry on a fresh element, then
 * a Retry panel, so it never hangs forever.
 */
val ChatVideo =
    FC<ChatVideoProps> { props ->
        val (failed, setFailed) = useState { false }
        // Bumped on retry (watchdog or manual): React key on the <video>, forcing a
        // full re-mount so the browser re-fetches from a fresh element.
        val (attempt, setAttempt) = useState { 0 }
        // Flips true once metadata arrives; ends the watchdog.
        val (metaLoaded, setMetaLoaded) = useState { false }
        // Lazy-load gate: the IntersectionObserver flips this true when the element
        // scrolls within ~one viewport, so off-screen videos defer their fetch.
        val (inView, setInView) = useState { false }
        val videoRef = useRef<HTMLVideoElement>(null)
        // Settings > Media: when auto-load is off we show a "Tap to load" placeholder
        // and fetch nothing until the user reveals it.
        val autoLoad = useAutoLoadMedia()
        val (revealed, setRevealed) = useState { false }

        // Show the player when auto-load is on, or once the user taps the placeholder.
        val showPlayer = autoLoad || revealed
        // Fetch only when the player is shown AND the element is near the viewport.
        // A manual reveal (tap) implies it's already on screen, so load right away.
        val loadNow = showPlayer && (inView || revealed)

        // Release the underlying media resource on unmount so switching groups /
        // relays doesn't pile up <video> elements the browser still holds open
        // (decoder + buffer + connection); the media-element cap otherwise makes
        // fresh videos render black until the engine GCs the stale ones.
        useEffect(props.videoUrl) {
            try {
                awaitCancellation()
            } finally {
                val node = videoRef.current ?: return@useEffect
                runCatching {
                    node.asDynamic().pause()
                    node.removeAttribute("src")
                    node.asDynamic().load()
                }
            }
        }

        // IntersectionObserver — one-shot. Disconnects as soon as the element enters
        // the viewport (rootMargin pre-loads one viewport ahead so scroll feels
        // instant). Only relevant while the player is shown and not yet in view.
        useEffect(props.videoUrl, showPlayer, attempt) {
            val node = videoRef.current ?: return@useEffect
            if (!showPlayer || inView) return@useEffect
            // Referenced by name inside the js() IntersectionObserver call below.
            @Suppress("UnusedPrivateProperty")
            val callback: (dynamic, dynamic) -> Unit = { entries, obs ->
                if (entries[0].isIntersecting == true) {
                    obs.disconnect()
                    setInView(true)
                }
            }
            val observer: dynamic =
                js("new (window.IntersectionObserver)(callback, { rootMargin: '200px 0px', threshold: 0.01 })")
            observer.observe(node)
            try {
                awaitCancellation()
            } finally {
                runCatching { observer.disconnect() }
            }
        }

        // Stall watchdog: only while a metadata load is in flight (loadNow but no
        // metadata yet). Any real progress resets it; on fire, abort and recover
        // (silent retry, then the Retry panel).
        useEffect(loadNow, metaLoaded, failed, attempt) {
            val node = videoRef.current
            if (node == null || !loadNow || metaLoaded || failed) return@useEffect
            var timer = 0
            val fire: () -> Unit = {
                val willRetry = attempt < MAX_AUTO_RETRIES
                runCatching {
                    node.asDynamic().pause()
                    node.removeAttribute("src")
                    node.asDynamic().load()
                }
                if (willRetry) setAttempt { it + 1 } else setFailed(true)
            }
            val arm: () -> Unit = {
                if (timer != 0) window.clearTimeout(timer)
                timer = window.setTimeout(fire, WATCHDOG_MS)
            }
            val onProgress: (dynamic) -> Unit = { arm() }
            node.asDynamic().addEventListener("progress", onProgress)
            node.asDynamic().addEventListener("loadeddata", onProgress)
            arm()
            try {
                awaitCancellation()
            } finally {
                if (timer != 0) window.clearTimeout(timer)
                node.asDynamic().removeEventListener("progress", onProgress)
                node.asDynamic().removeEventListener("loadeddata", onProgress)
            }
        }

        if (!showPlayer) {
            mediaGatePlaceholder("video") { setRevealed(true) }
        } else if (failed) {
            div {
                className = ClassName("msg-video-error")
                div {
                    className = ClassName("msg-video-error-label")
                    +"Video failed to load."
                }
                button {
                    className = ClassName("msg-video-retry")
                    onClick = {
                        setFailed(false)
                        setMetaLoaded(false)
                        setAttempt { it + 1 }
                    }
                    +"Retry"
                }
            }
        } else {
            div {
                className = ClassName("msg-video-wrap")
                video {
                    key = "${props.videoUrl}#$attempt"
                    ref = videoRef
                    className = ClassName("msg-video")
                    // Reserve the exact box from the imeta dim hint so the row keeps its height
                    // before metadata resolves; drop the floor that would distort it.
                    props.dimensions?.let { (w, h) ->
                        style = unsafeJso {
                            asDynamic().aspectRatio = "$w / $h"
                            asDynamic().minHeight = "auto"
                            asDynamic().minWidth = "auto"
                        }
                    }
                    // Only set src once near the viewport — keeps the element mounted
                    // (so the observer has a target) but defers the metadata fetch.
                    if (loadNow) src = props.videoUrl
                    // Static thumbnail (NIP-92 imeta) shown until a frame loads — a
                    // cheap <img>-style fetch, NOT a video range request.
                    props.posterUrl?.let { poster = it }
                    // Show a preview frame + duration without downloading/playing the
                    // whole file (no autoplay). The native controls let the user play.
                    preload = "metadata"
                    controls = true
                    playsInline = true
                    // Our button saves with the real extension; the browser's own download names
                    // the file after whatever object the host serves.
                    asDynamic().controlsList = "nodownload"
                    onLoadedMetadata = {
                        setMetaLoaded(true)
                        // Re-pinning the feed once the video's height is known is handled by the
                        // list's ResizeObserver (and, for imeta video, by the reserved box).
                        Unit
                    }
                    onError = { setFailed(true) }
                }
                MediaSaveButton {
                    url = props.videoUrl
                    fallbackBase = "video"
                    label = "Save video"
                    className = "msg-video-save"
                }
            }
        }
    }
