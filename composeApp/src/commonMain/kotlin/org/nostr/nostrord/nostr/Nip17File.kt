package org.nostr.nostrord.nostr

/**
 * A NIP-17 kind:15 file message: the rumor's content is the URL of an AES-GCM encrypted blob on a
 * media server, and its tags carry everything needed to read it back. Nothing about the file is
 * public - the server holds ciphertext only, and the key never leaves the sealed rumor.
 *
 * Tag names follow what NIP-17 specifies and what other clients (Jumble, 0xchat) actually publish:
 * `file-type` for the mime type, `encryption-algorithm`, `decryption-key`, `decryption-nonce`,
 * `ox` (SHA-256 of the plaintext), `x` (SHA-256 of the ciphertext), plus optional `size`, `dim`
 * and `thumbhash` hints for laying the bubble out before the bytes arrive.
 */
data class Nip17File(
    val url: String,
    val mimeType: String?,
    val algorithm: String?,
    val decryptionKeyHex: String?,
    val decryptionNonceHex: String?,
    /** SHA-256 of the decrypted file, hex. The integrity check after decryption. */
    val originalHashHex: String?,
    /** SHA-256 of the encrypted blob, hex. */
    val encryptedHashHex: String?,
    val size: Long?,
    val width: Int?,
    val height: Int?,
) {
    val isImage: Boolean get() = mimeType?.startsWith("image/") == true

    val isVideo: Boolean get() = mimeType?.startsWith("video/") == true

    val isAudio: Boolean get() = mimeType?.startsWith("audio/") == true

    /** The dim hint, when the sender published one, for reserving the bubble's slot. */
    val dimensions: Pair<Int, Int>? get() = if (width != null && height != null) width to height else null

    /** False when a tag is missing or names an algorithm we cannot read, so the UI offers the raw link instead. */
    val isDecryptable: Boolean
        get() = decryptionKeyHex != null &&
            decryptionNonceHex != null &&
            (algorithm == null || algorithm == ALGORITHM_AES_GCM)

    companion object {
        const val ALGORITHM_AES_GCM = "aes-gcm"

        /** Parse a kind:15 rumor. Null for any other kind, or when the content is not a url. */
        fun fromRumor(rumor: Event): Nip17File? {
            if (rumor.kind != Nip17.KIND_FILE) return null
            val url = rumor.content.trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) return null
            val dim = rumor.tagValue("dim")?.split("x")
            return Nip17File(
                url = url,
                // `m` is the NIP-94 spelling; some clients send that instead of `file-type`.
                mimeType = rumor.tagValue("file-type") ?: rumor.tagValue("m"),
                algorithm = rumor.tagValue("encryption-algorithm")?.lowercase(),
                decryptionKeyHex = rumor.tagValue("decryption-key"),
                decryptionNonceHex = rumor.tagValue("decryption-nonce"),
                originalHashHex = rumor.tagValue("ox"),
                encryptedHashHex = rumor.tagValue("x"),
                size = rumor.tagValue("size")?.toLongOrNull(),
                width = dim?.getOrNull(0)?.toIntOrNull(),
                height = dim?.getOrNull(1)?.toIntOrNull(),
            )
        }

        private fun Event.tagValue(name: String): String? = getTag(name)?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }
}
