package org.nostr.nostrord.web.components

import org.nostr.nostrord.utils.downloadFileName
import org.nostr.nostrord.web.bridge.launchApp
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName

external interface MediaSaveButtonProps : Props {
    /** The url the file is published at. Names the download even when [bytes] supplies the data. */
    var url: String

    /** Plaintext the app already holds (a decrypted NIP-17 attachment); skips the network. */
    var bytes: ByteArray?
    var mimeType: String?

    /** Names a url whose path ends in a slash. */
    var fallbackBase: String?
    var className: String?
    var label: String?
}

/**
 * Saves an inline media file to the browser's downloads with the name and extension it really
 * has. Wraps [downloadMedia], which is what handles a host that serves every blob under one
 * generic object name; a fetch that has to walk mirrors takes a moment, so the button spins while
 * it works. Completion is the browser's to report: `a.download` fires no event back, so a notice
 * from here could only mark the hand-off, which lands while a "save as" prompt is still open.
 */
val MediaSaveButton =
    FC<MediaSaveButtonProps> { props ->
        val (isSaving, setIsSaving) = useState { false }

        button {
            className = ClassName(props.className ?: "media-save-btn")
            title = props.label ?: "Save file"
            disabled = isSaving
            onClick = { event ->
                event.stopPropagation()
                if (!isSaving) {
                    val base = props.fallbackBase ?: "file"
                    val bytes = props.bytes
                    if (bytes != null) {
                        saveMediaBytes(bytes, downloadFileName(props.url, props.mimeType, base), props.mimeType)
                    } else {
                        setIsSaving(true)
                        launchApp {
                            try {
                                downloadMedia(props.url, base)
                            } finally {
                                setIsSaving(false)
                            }
                        }
                    }
                }
                Unit
            }
            if (isSaving) {
                span { className = ClassName("media-save-spinner") }
            } else {
                icon(Ic.Download)
            }
        }
    }
