package org.nostr.nostrord.nostr

import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get

private fun jsRandomPrivateKey(): Uint8Array = 
    js("globalThis.nobleSecp256k1.utils.randomPrivateKey()").unsafeCast<Uint8Array>()

private fun jsGetPublicKey(privateKey: Uint8Array, compressed: Boolean): Uint8Array = 
    js("globalThis.nobleSecp256k1.getPublicKey(privateKey, compressed)").unsafeCast<Uint8Array>()

private fun jsSchnorrSign(message: Uint8Array, privateKey: Uint8Array): Uint8Array = 
    js("globalThis.nobleSecp256k1.schnorr.signSync(message, privateKey)").unsafeCast<Uint8Array>()

private fun jsSchnorrVerify(signature: Uint8Array, message: Uint8Array, publicKey: Uint8Array): Boolean = 
    js("globalThis.nobleSecp256k1.schnorr.verifySync(signature, message, publicKey)").unsafeCast<Boolean>()

private fun jsSha256(data: Uint8Array): Uint8Array = 
    js("globalThis.nobleSha256(data)").unsafeCast<Uint8Array>()

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

actual object Crypto {
    
    actual fun generatePrivateKey(): ByteArray {
        return jsRandomPrivateKey().toByteArray()
    }
    
    actual fun getPublicKey(privateKey: ByteArray): ByteArray {
        val privKeyArray = privateKey.toUint8Array()
        val pubKeyArray = jsGetPublicKey(privKeyArray, true)
        return pubKeyArray.toByteArray()
    }
    
    actual fun getPublicKeyXOnly(privateKey: ByteArray): ByteArray {
        val pubKey = getPublicKey(privateKey)
        return pubKey.copyOfRange(1, 33)
    }
    
    actual fun getPublicKeyHex(privateKey: ByteArray): String {
        val xOnly = getPublicKeyXOnly(privateKey)
        return xOnly.toHexString()
    }
    
    actual fun signMessage(privateKey: ByteArray, messageHash: ByteArray): ByteArray {
        val adjustedPrivKey = ensureEvenYSecretKey(privateKey)
        val privKeyArray = adjustedPrivKey.toUint8Array()
        val messageArray = messageHash.toUint8Array()
        val signature = jsSchnorrSign(messageArray, privKeyArray)
        return signature.toByteArray()
    }
    
    actual fun verifySignature(signature: ByteArray, messageHash: ByteArray, publicKey: ByteArray): Boolean {
        val xOnly = when (publicKey.size) {
            32 -> publicKey
            33 -> publicKey.copyOfRange(1, 33)
            else -> throw IllegalArgumentException("Public key must be 32 or 33 bytes")
        }
        
        return try {
            val sigArray = signature.toUint8Array()
            val msgArray = messageHash.toUint8Array()
            val pubKeyArray = xOnly.toUint8Array()
            jsSchnorrVerify(sigArray, msgArray, pubKeyArray)
        } catch (e: Throwable) {
            false
        }
    }
    
    actual fun sha256(data: ByteArray): ByteArray {
        val uint8Array = data.toUint8Array()
        val resultArray = jsSha256(uint8Array)
        return resultArray.toByteArray()
    }
    
    actual fun sha256(data: String): ByteArray = sha256(data.encodeToByteArray())
    
    private fun ensureEvenYSecretKey(privateKey: ByteArray): ByteArray {
        val pubKey = getPublicKey(privateKey)
        val prefix = pubKey[0].toInt() and 0xFF
        
        return if (prefix == 0x03) {
            negateMod(privateKey)
        } else {
            privateKey
        }
    }
    
    private fun negateMod(privateKey: ByteArray): ByteArray {
        val n = byteArrayOf(
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFE.toByte(),
            0xBA.toByte(), 0xAE.toByte(), 0xDC.toByte(), 0xE6.toByte(),
            0xAF.toByte(), 0x48.toByte(), 0xA0.toByte(), 0x3B.toByte(),
            0xBF.toByte(), 0xD2.toByte(), 0x5E.toByte(), 0x8C.toByte(),
            0xD0.toByte(), 0x36.toByte(), 0x41.toByte(), 0x41.toByte()
        )
        
        val result = ByteArray(32)
        var borrow = 0
        
        for (i in 31 downTo 0) {
            val nVal = n[i].toInt() and 0xFF
            val dVal = privateKey[i].toInt() and 0xFF
            val diff = nVal - dVal - borrow
            
            result[i] = diff.toByte()
            borrow = if (diff < 0) 1 else 0
        }
        
        return result
    }
    
    private fun ByteArray.toHexString(): String {
        return joinToString("") { byte -> 
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0') 
        }
    }
}
