package org.nostr.nostrord.web.components

import kotlinx.browser.window
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.nostr.nostrord.web.bridge.useStateFlow
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

/**
 * App-wide fullscreen image viewer (lightbox). Mirrors the native image-fullscreen: tapping a
 * chat image opens it filling the screen; click the backdrop / close button / Escape to dismiss.
 * Place [ImageViewerHost] once at the app root and call [ImageViewer.show] from anywhere.
 */
object ImageViewer {
    /** [backdrop] is the contrast class ("on-light"/"on-dark") for transparent images, or null. */
    data class Shown(val url: String, val backdrop: String?)

    private val _shown = MutableStateFlow<Shown?>(null)
    val shown: StateFlow<Shown?> = _shown.asStateFlow()

    fun show(imageUrl: String, backdrop: String? = null) {
        _shown.value = Shown(imageUrl, backdrop)
    }

    fun dismiss() {
        _shown.value = null
    }
}

/** Place once at the app root. Renders the fullscreen overlay whenever an image is open. */
val ImageViewerHost =
    FC<Props> {
        val current = useStateFlow(ImageViewer.shown)

        useEffectOnce {
            window.asDynamic().addEventListener("keydown") { event: dynamic ->
                if (event.key == "Escape" && ImageViewer.shown.value != null) ImageViewer.dismiss()
            }
        }

        if (current != null) {
            val url = current.url
            div {
                className = ClassName("img-viewer-overlay")
                onClick = { ImageViewer.dismiss() }
                div {
                    className = ClassName("img-viewer-bar")
                    onClick = { it.stopPropagation() }
                    button {
                        className = ClassName("img-viewer-action")
                        title = "Open in new tab"
                        onClick = { window.open(url, "_blank") }
                        icon(Ic.OpenInNew)
                    }
                    MediaSaveButton {
                        this.url = current.url
                        fallbackBase = "image"
                        label = "Download"
                        className = "img-viewer-action"
                    }
                    button {
                        className = ClassName("img-viewer-action")
                        title = "Close"
                        onClick = { ImageViewer.dismiss() }
                        icon(Ic.Close)
                    }
                }
                ViewerImage {
                    // Remount per image so the CORS-fallback state resets between views.
                    key = url
                    this.url = url
                    backdrop = current.backdrop
                }
            }
        }
    }

private external interface ViewerImageProps : Props {
    var url: String
    var backdrop: String?
}

/**
 * The fullscreen <img>. Loads with crossOrigin="anonymous" to match [ChatImage] so it reuses the
 * CORS cache entry the thumbnail already populated instead of triggering a second (non-CORS)
 * download. Falls back to a plain load if the host sends no CORS headers, same as the thumbnail.
 */
private val ViewerImage =
    FC<ViewerImageProps> { props ->
        val (corsBlocked, setCorsBlocked) = useState { false }
        img {
            className = ClassName("img-viewer-img" + (props.backdrop?.let { " $it" } ?: ""))
            if (!corsBlocked) asDynamic().crossOrigin = "anonymous"
            src = props.url
            alt = ""
            onClick = { it.stopPropagation() }
            onError = {
                if (!corsBlocked) setCorsBlocked(true)
                Unit
            }
        }
    }
