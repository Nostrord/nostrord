package org.nostr.nostrord.nostr

expect object Nip04 {
    fun encrypt(plaintext: String, privateKeyHex: String, pubKeyHex: String): String
    fun decrypt(ciphertext: String, privateKeyHex: String, pubKeyHex: String): String
}
