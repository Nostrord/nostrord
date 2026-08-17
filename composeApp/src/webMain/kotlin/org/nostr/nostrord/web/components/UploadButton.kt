package org.nostr.nostrord.web.components

import kotlinx.coroutines.awaitCancellation
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.nostr.nostrord.network.upload.MAX_UPLOAD_BYTES
import org.nostr.nostrord.network.upload.SUPPORTED_FORMATS_MESSAGE
import org.nostr.nostrord.network.upload.UploadResult
import org.nostr.nostrord.network.upload.isSupportedUploadMime
import org.nostr.nostrord.network.upload.mimeTypeForFilename
import org.nostr.nostrord.network.upload.uploadMedia
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.web.bridge.launchApp
import react.FC
import react.Props
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName
import web.html.HTMLInputElement
import web.html.InputType
import web.html.file
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

external interface UploadButtonProps : Props {
    /** Called with the upload result (URL + NIP-68 metadata) on success. */
    var onUploaded: (UploadResult) -> Unit

    /** Called with a user-facing message when validation or the upload fails. */
    var onError: (String) -> Unit

    /** Class for the clickable label (e.g. "composer-btn" / "upload-btn"). */
    var cls: String

    /** Idle icon shown when not uploading. */
    var icon: Ic

    /** Optional text rendered after the icon, turning the button into a labelled control. */
    var label: String?

    /**
     * Optional external busy flag. When the caller owns an upload that does not go
     * through this button's file input (paste / drag-and-drop), set this true so the
     * spinner shows here on the attach icon instead of elsewhere.
     */
    var busy: Boolean?

    /**
     * Optional callback fired with true when a file-pick upload starts and false when it
     * settles. Lets the caller block its send button while a picked file is uploading (the
     * picked URL hasn't landed in the draft yet) — paste/drop already do this via uploadCount.
     */
    var onBusyChange: ((Boolean) -> Unit)?

    /**
     * Fired whenever the OS file dialog closes, whether a file was chosen OR the user
     * cancelled. The native picker steals focus and collapses the mobile soft keyboard, so
     * the caller uses this to refocus its input and re-open the keyboard, so tapping attach
     * never strands the user without a keyboard.
     */
    var onPickerClosed: (() -> Unit)?

    /**
     * Restrict to still images only (avatars / banners / group pictures), matching native's
     * MediaAccept.Images. Defaults to the full image/video/audio set used by the composer.
     */
    var imagesOnly: Boolean?

    /**
     * Takes the validated bytes instead of uploading them. DMs use it: their attachments are
     * encrypted before they reach a media server, so the plain upload here would defeat the point.
     */
    var onPicked: ((PickedFile) -> Unit)?
}

/** A picked file that passed validation, with the mime the upload should carry. */
data class PickedFile(
    val bytes: ByteArray,
    val name: String,
    val mimeType: String,
)

/**
 * Upload button — a styled `<label>` wrapping a hidden file input. On pick it validates
 * and reads the file bytes, uploads to nostr.build (NIP-98 auth via the repository), then
 * hands back the full result. Shows "…" while in flight. Failures (too large, unsupported
 * format, auth/server/network) are reported via [UploadButtonProps.onError] instead of
 * being swallowed silently, matching the native picker.
 */
val UploadButton =
    FC<UploadButtonProps> { props ->
        val (picking, setPicking) = useState { false }
        // Either an in-progress file pick (owned here) or a paste/drop upload the
        // caller signals via props.busy shows the spinner on this attach icon.
        val busy = picking || (props.busy == true)

        val inputRef = useRef<HTMLInputElement>(null)
        // `change` only fires when a file is chosen; `cancel` fires when the OS dialog is
        // dismissed with no selection. Both mean the picker closed, so both refocus the
        // composer. Without the cancel path, cancelling left the keyboard shut.
        useEffect(Unit) {
            val node = inputRef.current ?: return@useEffect
            val onCancel: (dynamic) -> Unit = { props.onPickerClosed?.invoke() }
            node.asDynamic().addEventListener("cancel", onCancel)
            try {
                awaitCancellation()
            } finally {
                node.asDynamic().removeEventListener("cancel", onCancel)
            }
        }

        label {
            className = ClassName(props.cls)
            if (busy) {
                span { className = ClassName("upload-spinner") }
            } else {
                icon(props.icon)
            }
            props.label?.let { +it }
            input {
                ref = inputRef
                className = ClassName("upload-file-input")
                type = InputType.file
                accept = if (props.imagesOnly == true) "image/*" else "image/*,video/*,audio/*"
                disabled = busy
                onChange = { event ->
                    val target = event.currentTarget
                    val fileList = target.asDynamic().files
                    val f = if (fileList != null && (fileList.length as Int) > 0) fileList[0] else null
                    // Picker closed with a selection: refocus the composer (cancel is handled
                    // by the listener above) so the soft keyboard re-opens.
                    props.onPickerClosed?.invoke()
                    if (f != null) {
                        setPicking(true)
                        props.onBusyChange?.invoke(true)
                        launchApp {
                            try {
                                val handOver = props.onPicked
                                if (handOver != null) {
                                    when (val picked = readPickedFile(f, props.imagesOnly == true)) {
                                        is Result.Success -> handOver(picked.data)
                                        is Result.Error -> props.onError(picked.error.message)
                                    }
                                } else {
                                    when (val r = uploadBlob(f, props.imagesOnly == true)) {
                                        is Result.Success -> props.onUploaded(r.data)
                                        is Result.Error -> props.onError(r.error.message)
                                    }
                                }
                            } finally {
                                setPicking(false)
                                props.onBusyChange?.invoke(false)
                                target.asDynamic().value = ""
                            }
                        }
                    }
                }
            }
        }
    }

/**
 * Validate and upload a picked/pasted File (or Blob) to nostr.build (NIP-98 auth via the
 * repository). Mirrors the native picker's checks: the 20 MB cap and the supported
 * image/video/audio formats, with the same user-facing messages. Returns the full
 * [UploadResult] (URL + NIP-68 metadata) so callers can build imeta tags, or a
 * [Result.Error] to surface. Reusable by the file picker and Ctrl+V paste / drag-and-drop.
 */
suspend fun uploadBlob(file: dynamic, imagesOnly: Boolean = false): Result<UploadResult> {
    val picked = readPickedFile(file, imagesOnly)
    val data = picked.getOrNull() ?: return Result.Error(picked.errorOrNull() ?: AppError.Unknown("Upload failed"))
    return uploadMedia(data.bytes, data.name, data.mimeType)
}

/**
 * Validate a picked/pasted File (or Blob) and read its bytes: the 20 MB cap and the supported
 * image/video/audio formats, with the same user-facing messages as the native picker. The step
 * before an upload, and all a caller that encrypts the bytes itself needs.
 */
suspend fun readPickedFile(file: dynamic, imagesOnly: Boolean = false): Result<PickedFile> {
    val name = (file.name.unsafeCast<String?>())?.takeIf { it.isNotBlank() } ?: "file"
    val size = (file.size.unsafeCast<Double?>())?.toLong() ?: 0L
    if (size > MAX_UPLOAD_BYTES) {
        return Result.Error(AppError.Unknown("File is too large. The maximum upload size is 20 MB."))
    }
    // Prefer the extension-derived mime (parity with native); fall back to the
    // browser-provided type for pasted blobs that arrive without a real filename.
    val byName = mimeTypeForFilename(name)
    val mime =
        if (byName != "application/octet-stream") {
            byName
        } else {
            (file.type.unsafeCast<String?>())?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        }
    // imagesOnly callers (avatars / banners) accept still images only, matching native.
    val supported = isSupportedUploadMime(mime) && (!imagesOnly || mime.startsWith("image/"))
    if (!supported) {
        val ext = name.substringAfterLast('.', "").ifEmpty { "unknown" }
        val message =
            if (imagesOnly) {
                "\".$ext\" files are not supported.\nChoose an image: jpg, png, gif, webp, avif."
            } else {
                "\".$ext\" files are not supported.\n\n$SUPPORTED_FORMATS_MESSAGE"
            }
        return Result.Error(AppError.Unknown(message))
    }
    val bytes = readFileBytes(file)
    if (bytes.isEmpty()) return Result.Error(AppError.Unknown("Could not read the selected file."))
    return Result.Success(PickedFile(bytes, name, mime))
}

/** Read a picked file's bytes via the Blob arrayBuffer() promise. */
private suspend fun readFileBytes(file: dynamic): ByteArray = suspendCoroutine { cont ->
    val promise = file.arrayBuffer()
    promise.then(
        { buffer: dynamic ->
            val arr = Int8Array(buffer.unsafeCast<ArrayBuffer>())
            cont.resume(ByteArray(arr.length) { arr[it] })
        },
        { _: dynamic ->
            cont.resume(ByteArray(0))
        },
    )
}
