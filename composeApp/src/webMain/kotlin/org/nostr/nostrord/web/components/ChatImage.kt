package org.nostr.nostrord.web.components

import js.objects.unsafeJso
import kotlinx.browser.document
import org.nostr.nostrord.ui.media.reservedWidthPx
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
        // The decoded bitmap's own size, read on load. It stands in for a missing imeta hint:
        // without either, the placeholder floor stays in force and a wide image is cropped to
        // it (object-fit) while the bubble still stretches to the image's intrinsic width.
        val (natural, setNatural) = useState<Pair<Int, Int>?> { null }
        val slot = props.dimensions ?: natural
        // Settings → Media: when auto-load is off we show a "Tap to load" placeholder
        // and fetch nothing until the user reveals this image. data: URIs are already
        // embedded in the event and blob: urls are bytes this tab already holds (a DM
        // attachment past its own gate), so neither costs a fetch and neither is gated.
        val autoLoad = useAutoLoadMedia()
        val (revealed, setRevealed) = useState { false }
        val isLocal = props.imageUrl.startsWith("data:") || props.imageUrl.startsWith("blob:")
        if (!autoLoad && !revealed && !isLocal) {
            mediaGatePlaceholder("image") { setRevealed(true) }
            return@FC
        }

        img {
            className = ClassName(
                "msg-image " + (if (loaded) "is-loaded" else "is-loading") + (backdrop?.let { " $it" } ?: ""),
            )
            // Known size (imeta hint, or the bitmap's own once decoded): pin the exact box so
            // the row keeps its height (no reflow under the reader) and drop the placeholder
            // floor, which would otherwise crop a wide image to its 120px minimum. The width
            // must be explicit: an <img> with alt="" has no intrinsic size until it decodes,
            // and `aspect-ratio` alone then resolves against a zero width, collapsing the slot
            // to 0x0 (nothing to reserve, nothing to shimmer). Until either is known the floor
            // stays and the list's ResizeObserver re-pins as the image grows.
            slot?.let { (w, h) ->
                val displayWidth = reservedWidthPx(w, h)
                style = unsafeJso {
                    asDynamic().aspectRatio = "$w / $h"
                    asDynamic().width = "${displayWidth}px"
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
                if (props.dimensions == null) {
                    val el = event.currentTarget.asDynamic()
                    val w = el.naturalWidth.unsafeCast<Int>()
                    val h = el.naturalHeight.unsafeCast<Int>()
                    if (w > 0 && h > 0) setNatural(w to h)
                }
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
