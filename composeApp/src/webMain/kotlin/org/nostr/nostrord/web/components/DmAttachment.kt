package org.nostr.nostrord.web.components

import js.objects.unsafeJso
import kotlinx.coroutines.awaitCancellation
import org.nostr.nostrord.network.managers.DmFileManager
import org.nostr.nostrord.nostr.Nip17File
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useState
import web.blob.Blob
import web.blob.BlobPart
import web.blob.BlobPropertyBag
import web.cssom.ClassName
import web.url.URL

external interface DmAttachmentProps : Props {
    var file: Nip17File

    /** Null until the first load is started; see [DmFileManager]. */
    var state: DmFileManager.FileState?
    var onLoad: () -> Unit
    var onRetry: () -> Unit
}

/**
 * The body of a NIP-17 kind:15 message: an attachment the media server only holds encrypted.
 * The decrypted bytes are wrapped in an object url so the browser can paint them, which is
 * revoked on unmount rather than left to leak, and the blob url is what an image src gets. The
 * plaintext never becomes a link anyone else can follow.
 */
val DmAttachment =
    FC<DmAttachmentProps> { props ->
        val file = props.file
        val state = props.state
        val (objectUrl, setObjectUrl) = useState<String?> { null }
        // Settings > Media: with auto-load off nothing is fetched or decrypted until the
        // reader asks for this one attachment.
        val autoLoad = useAutoLoadMedia()
        val (revealed, setRevealed) = useState { false }
        val allowed = autoLoad || revealed

        useEffect(file.url, allowed) { if (allowed) props.onLoad() }

        // Bytes -> object url, torn down when the message scrolls out or new bytes replace it.
        useEffect(state) {
            val ready = state as? DmFileManager.FileState.Ready
            if (ready == null) {
                setObjectUrl(null)
                return@useEffect
            }
            val url = objectUrlFor(ready.bytes, ready.mimeType ?: file.mimeType)
            setObjectUrl(url)
            try {
                awaitCancellation()
            } finally {
                URL.revokeObjectURL(url)
            }
        }

        when {
            !allowed -> mediaGatePlaceholder(gateLabel(file)) { setRevealed(true) }

            state is DmFileManager.FileState.Failed ->
                div {
                    className = ClassName("dm-attachment dm-attachment-failed")
                    span { +state.reason }
                    button {
                        className = ClassName("btn btn-secondary btn-sm")
                        onClick = { props.onRetry() }
                        +"Retry"
                    }
                }

            objectUrl == null ->
                div {
                    className = ClassName("dm-attachment dm-attachment-loading")
                    style = unsafeJso { asDynamic().aspectRatio = file.aspectRatioCss() }
                }

            else ->
                div {
                    className = ClassName("dm-attachment-wrap")
                    when {
                        file.isImage ->
                            ChatImage {
                                imageUrl = objectUrl
                                dimensions = file.dimensions
                            }

                        file.isVideo ->
                            react.dom.html.ReactHTML.video {
                                className = ClassName("msg-video")
                                src = objectUrl
                                controls = true
                                asDynamic().controlsList = "nodownload"
                            }

                        file.isAudio ->
                            react.dom.html.ReactHTML.audio {
                                className = ClassName("msg-audio")
                                src = objectUrl
                                controls = true
                                asDynamic().controlsList = "nodownload"
                            }

                        else ->
                            div {
                                className = ClassName("dm-attachment")
                                +"File"
                            }
                    }
                    // The url only ever serves ciphertext, so the download takes the plaintext
                    // this component already holds and the type the sender declared.
                    MediaSaveButton {
                        url = file.url
                        bytes = (state as? DmFileManager.FileState.Ready)?.bytes
                        mimeType = (state as? DmFileManager.FileState.Ready)?.mimeType ?: file.mimeType
                        fallbackBase = "attachment"
                        label = "Save attachment"
                        className = "dm-attachment-save"
                    }
                }
        }
    }

/** Matches the wording of the inline-media gate: "Tap to load image". */
private fun gateLabel(file: Nip17File): String = when {
    file.isVideo -> "video"
    file.isAudio -> "audio"
    file.isImage -> "image"
    else -> "file"
}

/** CSS `aspect-ratio` for the reserved slot, square when the sender published no dim hint. */
private fun Nip17File.aspectRatioCss(): String = dimensions?.let { (w, h) -> "$w / $h" } ?: "1 / 1"

private fun objectUrlFor(bytes: ByteArray, mimeType: String?): String {
    val options = unsafeJso<BlobPropertyBag> { type = mimeType ?: "application/octet-stream" }
    // A Kotlin/JS ByteArray is an Int8Array, which is already a valid BlobPart.
    return URL.createObjectURL(Blob(arrayOf(bytes.unsafeCast<BlobPart>()), options))
}
