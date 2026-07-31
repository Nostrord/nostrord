package org.nostr.nostrord.web.components

import js.objects.unsafeJso
import kotlinx.browser.document
import react.FC
import react.Props
import react.dom.html.ReactHTML.img
import react.useState
import web.cssom.ClassName

external interface ChatImageProps : Props {
    var imageUrl: String

    /** NIP-68 imeta (width, height) hint. When present the slot is reserved at the exact
     *  aspect ratio before the bitmap decodes, so the row never grows on load. When absent
     *  the CSS min-height floor bounds the growth and the list's re-pin absorbs the rest. */
    var dimensions: Pair<Int, Int>?
}

/**
 * A chat inline image. Opens fullscreen on click. For images that have transparency it samples
 * the pixels and adds a white or dark backdrop so a dark or light transparent logo stays visible
 * on the chat surface.
 *
 * The sampling reads the visible <img> itself (loaded with crossOrigin="anonymous") rather than
 * downloading a second cache-busted copy, so the backdrop class is ready the moment the image
 * paints instead of after a full extra round-trip. Most Nostr media hosts (nostr.build, Blossom,
 * etc.) send `Access-Control-Allow-Origin: *`, so the CORS load succeeds and the canvas is
 * readable. If a host sends no CORS headers the crossOrigin load errors; we then reload the same
 * element WITHOUT crossOrigin so it still displays (just with no backdrop, same as before).
 */
val ChatImage =
    FC<ChatImageProps> { props ->
        val (backdrop, setBackdrop) = useState<String?> { null }
        // Flips to true if the crossOrigin load fails (host without CORS headers); the retry
        // render drops crossOrigin so the image still shows. We then skip sampling (the plain
        // image would taint the canvas anyway).
        val (corsBlocked, setCorsBlocked) = useState { false }
        // Drives the shimmer skeleton: the reserved slot animates until the bitmap paints,
        // so a slow image reads as loading instead of as an empty box that pops.
        val (loaded, setLoaded) = useState { false }
        // Settings → Media: when auto-load is off we show a "Tap to load" placeholder
        // and fetch nothing until the user reveals this image. data: URIs are already
        // embedded in the event (no network), so they're never gated.
        val autoLoad = useAutoLoadMedia()
        val (revealed, setRevealed) = useState { false }
        if (!autoLoad && !revealed && !props.imageUrl.startsWith("data:")) {
            mediaGatePlaceholder("image") { setRevealed(true) }
            return@FC
        }

        img {
            className = ClassName(
                "msg-image " + (if (loaded) "is-loaded" else "is-loading") + (backdrop?.let { " $it" } ?: ""),
            )
            // With an imeta dim hint, reserve the exact box before load so the row keeps its
            // height (no reflow under the reader) and drop the placeholder floor that would
            // distort a small image. Without a hint, the CSS min-height floor stays and the
            // list's ResizeObserver re-pins as the image grows.
            props.dimensions?.let { (w, h) ->
                style = unsafeJso {
                    asDynamic().aspectRatio = "$w / $h"
                    asDynamic().minHeight = "auto"
                    asDynamic().minWidth = "auto"
                }
            }
            // Sample-readable load. data: URIs are same-origin so crossOrigin is a no-op there.
            if (!corsBlocked) asDynamic().crossOrigin = "anonymous"
            src = props.imageUrl
            alt = ""
            onClick = { ImageViewer.show(props.imageUrl, backdrop) }
            onLoad = { event ->
                setLoaded(true)
                if (!corsBlocked) setBackdrop(analyzeBackdrop(event.currentTarget))
                // Re-pinning the feed on media load is handled by the list's ResizeObserver
                // (and, for imeta media, by the reserved box), so nothing is needed here.
                Unit
            }
            onError = {
                // First failure is most likely the CORS preflight on a no-CORS host; retry
                // without crossOrigin so the image still displays. The retry keeps the
                // skeleton up; a second failure stops it so a dead URL doesn't shimmer forever.
                if (!corsBlocked) setCorsBlocked(true) else setLoaded(true)
                Unit
            }
        }
    }

/**
 * Sample [image] into a small canvas. Returns "on-light" when the image has transparent areas
 * and dark content (needs a white backdrop), "on-dark" for light content (needs a dark backdrop),
 * or null when the image is effectively opaque or the canvas can't be read (cross-origin taint).
 */
private fun analyzeBackdrop(image: dynamic): String? {
    val size = 24
    return try {
        val canvas = document.createElement("canvas").asDynamic()
        canvas.width = size
        canvas.height = size
        val ctx = canvas.getContext("2d") ?: return null
        ctx.drawImage(image, 0, 0, size, size)
        val data = ctx.getImageData(0, 0, size, size).data
        val total = size * size
        var transparent = 0
        var opaque = 0
        var lumaSum = 0.0
        for (i in 0 until total) {
            val o = i * 4
            val alpha = data[o + 3].unsafeCast<Int>()
            if (alpha < 240) transparent++
            if (alpha > 200) {
                val r = data[o].unsafeCast<Int>()
                val g = data[o + 1].unsafeCast<Int>()
                val b = data[o + 2].unsafeCast<Int>()
                lumaSum += 0.299 * r + 0.587 * g + 0.114 * b
                opaque++
            }
        }
        // Mostly opaque → leave the image as-is.
        if (transparent < total * 0.05 || opaque == 0) {
            null
        } else if (lumaSum / opaque < 128.0) {
            "on-light"
        } else {
            "on-dark"
        }
    } catch (e: Throwable) {
        null
    }
}
