package org.nostr.nostrord.nostr

import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get

private fun jsGetSharedSecret(privateKey: Uint8Array, publicKey: Uint8Array): Uint8Array =
    js("globalThis.nobleSecp256k1.getSharedSecret(privateKey, publicKey)").unsafeCast<Uint8Array>()

private fun jsAesEncrypt(key: Uint8Array, iv: Uint8Array, data: Uint8Array): Uint8Array =
    js("globalThis.cryptoEncryptAesCbc(key, iv, data)").unsafeCast<Uint8Array>()

private fun jsAesDecrypt(key: Uint8Array, iv: Uint8Array, data: Uint8Array): Uint8Array =
    js("globalThis.cryptoDecryptAesCbc(key, iv, data)").unsafeCast<Uint8Array>()

private fun jsRandomBytes(size: Int): Uint8Array =
    js("crypto.getRandomValues(new Uint8Array(size))").unsafeCast<Uint8Array>()

private fun jsToBase64(data: Uint8Array): String =
    js("globalThis.uint8ArrayToBase64(data)").unsafeCast<String>()

private fun jsFromBase64(str: String): Uint8Array =
    js("globalThis.base64ToUint8Array(str)").unsafeCast<Uint8Array>()

private fun jsStringToUint8Array(str: String): Uint8Array =
    js("new TextEncoder().encode(str)").unsafeCast<Uint8Array>()

private fun jsUint8ArrayToString(data: Uint8Array): String =
    js("new TextDecoder().decode(data)").unsafeCast<String>()

private fun jsCreateUint8Array(size: Int): Uint8Array =
    js("new Uint8Array(size)").unsafeCast<Uint8Array>()

private fun jsSetUint8ArrayValue(array: Uint8Array, index: Int, value: Int) {
    js("array[index] = value")
}

private fun ByteArray.toUint8Array(): Uint8Array {
    val result = jsCreateUint8Array(this.size)
    for (i in this.indices) {
        jsSetUint8ArrayValue(result, i, this[i].toInt() and 0xFF)
    }
    return result
}

private fun Uint8Array.toByteArray(): ByteArray {
    val result = ByteArray(this.length)
    for (i in 0 until this.length) {
        result[i] = this[i].toByte()
    }
    return result
}

actual object Nip04 {
    
    actual fun encrypt(plaintext: String, privateKeyHex: String, pubKeyHex: String): String {
        val sharedSecret = computeSharedSecret(privateKeyHex, pubKeyHex)
        
        val iv = jsRandomBytes(16)
        val plaintextArray = jsStringToUint8Array(plaintext)
        val keyArray = sharedSecret.toUint8Array()
        
        val encrypted = jsAesEncrypt(keyArray, iv, plaintextArray)
        
        val encryptedBase64 = jsToBase64(encrypted)
        val ivBase64 = jsToBase64(iv)
        
        return "$encryptedBase64?iv=$ivBase64"
    }
    
    actual fun decrypt(ciphertext: String, privateKeyHex: String, pubKeyHex: String): String {
        val parts = ciphertext.split("?iv=")
        if (parts.size != 2) {
            throw IllegalArgumentException("Invalid ciphertext format")
        }
        
        val encryptedBase64 = parts[0]
        val ivBase64 = parts[1]
        
        val encrypted = jsFromBase64(encryptedBase64)
        val iv = jsFromBase64(ivBase64)
        
        val sharedSecret = computeSharedSecret(privateKeyHex, pubKeyHex)
        val keyArray = sharedSecret.toUint8Array()
        
        val decrypted = jsAesDecrypt(keyArray, iv, encrypted)
        
        return jsUint8ArrayToString(decrypted)
    }
    
    private fun computeSharedSecret(privateKeyHex: String, pubKeyHex: String): ByteArray {
        val privateKeyBytes = privateKeyHex.hexToByteArray()
        val compressedPubKey = ("02" + pubKeyHex).hexToByteArray()
        
        val privKeyArray = privateKeyBytes.toUint8Array()
        val pubKeyArray = compressedPubKey.toUint8Array()
        
        val sharedPoint = jsGetSharedSecret(privKeyArray, pubKeyArray)
        
        val result = ByteArray(32)
        for (i in 0 until 32) {
            result[i] = sharedPoint[i + 1].toByte()
        }
        return result
    }
    
    private fun String.hexToByteArray(): ByteArray {
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
