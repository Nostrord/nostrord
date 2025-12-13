@file:OptIn(ExperimentalWasmJsInterop::class)

package org.nostr.nostrord.nostr

import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import kotlin.js.ExperimentalWasmJsInterop

// Shared secret computation using secp256k1
@JsFun("(privateKey, publicKey) => globalThis.nobleSecp256k1.getSharedSecret(privateKey, publicKey)")
private external fun jsGetSharedSecret(privateKey: Uint8Array, publicKey: Uint8Array): Uint8Array

// HKDF functions
@JsFun("(salt, ikm) => globalThis.hkdfExtract(salt, ikm)")
private external fun jsHkdfExtract(salt: Uint8Array, ikm: Uint8Array): Uint8Array

@JsFun("(prk, info, length) => globalThis.hkdfExpand(prk, info, length)")
private external fun jsHkdfExpand(prk: Uint8Array, info: Uint8Array, length: Int): Uint8Array

// ChaCha20
@JsFun("(key, nonce, data) => globalThis.chacha20Encrypt(key, nonce, data)")
private external fun jsChacha20(key: Uint8Array, nonce: Uint8Array, data: Uint8Array): Uint8Array

// HMAC-SHA256
@JsFun("(key, data) => globalThis.hmacSha256(key, data)")
private external fun jsHmacSha256(key: Uint8Array, data: Uint8Array): Uint8Array

// Random bytes
@JsFun("(size) => crypto.getRandomValues(new Uint8Array(size))")
private external fun jsRandomBytes(size: Int): Uint8Array

// Base64
@JsFun("(data) => globalThis.uint8ArrayToBase64(data)")
private external fun jsToBase64(data: Uint8Array): String

@JsFun("(str) => globalThis.base64ToUint8Array(str)")
private external fun jsFromBase64(str: String): Uint8Array

// String to Uint8Array - THIS WAS MISSING
@JsFun("(str) => new TextEncoder().encode(str)")
private external fun jsStringToUint8Array(str: String): Uint8Array

// Uint8Array helpers
@JsFun("(size) => new Uint8Array(size)")
private external fun jsCreateUint8Array(size: Int): Uint8Array

@JsFun("(array, index, value) => { array[index] = value; }")
private external fun jsSetUint8ArrayValue(array: Uint8Array, index: Int, value: Int)

@JsFun("(a, b) => globalThis.concatUint8Arrays(a, b)")
private external fun jsConcatArrays(a: Uint8Array, b: Uint8Array): Uint8Array

@JsFun("(arr, start, end) => globalThis.sliceUint8Array(arr, start, end)")
private external fun jsSliceArray(arr: Uint8Array, start: Int, end: Int): Uint8Array

@JsFun("(a, b) => globalThis.uint8ArraysEqual(a, b)")
private external fun jsArraysEqual(a: Uint8Array, b: Uint8Array): Boolean

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

actual object Nip44 {
    private const val VERSION: Byte = 2
    
    actual fun encrypt(plaintext: String, privateKeyHex: String, pubKeyHex: String): String {
        val conversationKey = getConversationKey(privateKeyHex, pubKeyHex)
        val nonce = jsRandomBytes(32)
        val messageKeys = getMessageKeys(conversationKey, nonce)
        
        val padded = pad(plaintext)
        val ciphertext = jsChacha20(messageKeys.chachaKey, messageKeys.chachaNonce, padded)
        
        // MAC = HMAC-SHA256(hmac_key, nonce || ciphertext)
        val nonceAndCiphertext = jsConcatArrays(nonce, ciphertext)
        val mac = jsHmacSha256(messageKeys.hmacKey, nonceAndCiphertext)
        
        // Final result: version (1) + nonce (32) + ciphertext + mac (32)
        val versionArray = jsCreateUint8Array(1)
        jsSetUint8ArrayValue(versionArray, 0, VERSION.toInt())
        
        var result = jsConcatArrays(versionArray, nonce)
        result = jsConcatArrays(result, ciphertext)
        result = jsConcatArrays(result, mac)
        
        return jsToBase64(result)
    }
    
    actual fun decrypt(ciphertext: String, privateKeyHex: String, pubKeyHex: String): String {
        val data = jsFromBase64(ciphertext)
        
        println("   NIP-44 decrypt: data size = ${data.length}")
        
        if (data.length < 99) {
            throw IllegalArgumentException("Ciphertext too short: ${data.length} bytes")
        }
        
        val version = data[0].toInt()
        if (version != 2) {
            throw IllegalArgumentException("Unsupported NIP-44 version: $version")
        }
        
        val nonce = jsSliceArray(data, 1, 33)
        val ciphertextBytes = jsSliceArray(data, 33, data.length - 32)
        val mac = jsSliceArray(data, data.length - 32, data.length)
        
        println("   Nonce: ${nonce.toByteArray().toHexString().take(16)}...")
        println("   Ciphertext size: ${ciphertextBytes.length}")
        
        val conversationKey = getConversationKey(privateKeyHex, pubKeyHex)
        println("   Conversation key: ${conversationKey.toByteArray().toHexString().take(16)}...")
        
        val messageKeys = getMessageKeys(conversationKey, nonce)
        
        // Verify MAC: HMAC-SHA256(hmac_key, nonce || ciphertext)
        val nonceAndCiphertext = jsConcatArrays(nonce, ciphertextBytes)
        val expectedMac = jsHmacSha256(messageKeys.hmacKey, nonceAndCiphertext)
        
        println("   Expected MAC: ${expectedMac.toByteArray().toHexString().take(16)}...")
        println("   Received MAC: ${mac.toByteArray().toHexString().take(16)}...")
        
        if (!jsArraysEqual(mac, expectedMac)) {
            throw IllegalArgumentException("Invalid MAC")
        }
        
        val padded = jsChacha20(messageKeys.chachaKey, messageKeys.chachaNonce, ciphertextBytes)
        return unpad(padded)
    }
    
    private fun getConversationKey(privateKeyHex: String, pubKeyHex: String): Uint8Array {
        val privateKeyBytes = privateKeyHex.hexToByteArray().toUint8Array()
        
        // Try both Y coordinate parities (02 = even, 03 = odd)
        for (prefix in listOf("02", "03")) {
            try {
                val compressedPubKey = (prefix + pubKeyHex).hexToByteArray().toUint8Array()
                
                // getSharedSecret returns 33 bytes (compressed point)
                val sharedPoint = jsGetSharedSecret(privateKeyBytes, compressedPubKey)
                
                // Extract x-coordinate (skip the prefix byte)
                val sharedX = jsSliceArray(sharedPoint, 1, 33)
                
                println("   Shared X (${prefix}): ${sharedX.toByteArray().toHexString().take(16)}...")
                
                // NIP-44: conversation_key = HKDF-Extract(salt="nip44-v2", ikm=shared_x)
                // USE jsStringToUint8Array instead of encodeToByteArray().toUint8Array()
                val salt = jsStringToUint8Array("nip44-v2")
                return jsHkdfExtract(salt, sharedX)
            } catch (e: Exception) {
                println("   getSharedSecret failed with prefix $prefix: ${e.message}")
                continue
            }
        }
        throw IllegalArgumentException("Invalid public key: $pubKeyHex")
    }
    
    private data class MessageKeys(
        val chachaKey: Uint8Array,
        val chachaNonce: Uint8Array,
        val hmacKey: Uint8Array
    )
    
    private fun getMessageKeys(conversationKey: Uint8Array, nonce: Uint8Array): MessageKeys {
        // HKDF-Expand(prk=conversation_key, info=nonce, L=76)
        val keys = jsHkdfExpand(conversationKey, nonce, 76)
        return MessageKeys(
            chachaKey = jsSliceArray(keys, 0, 32),
            chachaNonce = jsSliceArray(keys, 32, 44),
            hmacKey = jsSliceArray(keys, 44, 76)
        )
    }
    
    private fun pad(plaintext: String): Uint8Array {
        // Use JS TextEncoder for consistent encoding
        val unpadded = jsStringToUint8Array(plaintext)
        if (unpadded.length == 0 || unpadded.length > 65535) {
            throw IllegalArgumentException("Invalid plaintext length: ${unpadded.length}")
        }
        
        val paddedLength = calcPaddedLen(unpadded.length)
        val result = jsCreateUint8Array(2 + paddedLength)
        
        // Big-endian length prefix
        jsSetUint8ArrayValue(result, 0, (unpadded.length shr 8) and 0xFF)
        jsSetUint8ArrayValue(result, 1, unpadded.length and 0xFF)
        
        // Copy plaintext
        for (i in 0 until unpadded.length) {
            jsSetUint8ArrayValue(result, 2 + i, unpadded[i].toInt() and 0xFF)
        }
        
        return result
    }
    
    private fun unpad(padded: Uint8Array): String {
        if (padded.length < 2) {
            throw IllegalArgumentException("Padded data too short")
        }
        
        val length = ((padded[0].toInt() and 0xFF) shl 8) or (padded[1].toInt() and 0xFF)
        
        if (length < 1 || length > padded.length - 2) {
            throw IllegalArgumentException("Invalid padding length: $length, padded size: ${padded.length}")
        }
        
        val slice = jsSliceArray(padded, 2, 2 + length)
        return jsUint8ArrayToString(slice)
    }
    
    private fun calcPaddedLen(unpaddedLen: Int): Int {
        if (unpaddedLen <= 32) return 32
        
        val nextPower = highestOneBit(unpaddedLen - 1) shl 1
        val chunk = maxOf(32, nextPower / 8)
        return ((unpaddedLen - 1) / chunk + 1) * chunk
    }

    private fun String.hexToByteArray(): ByteArray {
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    
    private fun highestOneBit(value: Int): Int {
        var v = value
        v = v or (v shr 1)
        v = v or (v shr 2)
        v = v or (v shr 4)
        v = v or (v shr 8)
        v = v or (v shr 16)
        return v - (v ushr 1)
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { byte -> 
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0') 
        }
    }
}

// Add this missing function
@JsFun("(data) => new TextDecoder().decode(data)")
private external fun jsUint8ArrayToString(data: Uint8Array): String
