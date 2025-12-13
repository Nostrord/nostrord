package org.nostr.nostrord.nostr

import fr.acinq.secp256k1.Secp256k1
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.ChaCha20ParameterSpec
import javax.crypto.spec.SecretKeySpec

actual object Nip44 {
    private const val VERSION: Byte = 2
    
    actual fun encrypt(plaintext: String, privateKeyHex: String, pubKeyHex: String): String {
        val conversationKey = getConversationKey(privateKeyHex, pubKeyHex)
        val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val messageKeys = getMessageKeys(conversationKey, nonce)
        
        val padded = pad(plaintext)
        val ciphertext = chacha20(messageKeys.chachaKey, messageKeys.chachaNonce, padded)
        
        // MAC = HMAC-SHA256(hmac_key, nonce || ciphertext)
        // In hmac_aad: aad=nonce, message=ciphertext
        val mac = hmacAad(messageKeys.hmacKey, ciphertext, nonce)
        
        // Final result: version (1) + nonce (32) + ciphertext + mac (32)
        val result = ByteBuffer.allocate(1 + 32 + ciphertext.size + 32)
            .put(VERSION)
            .put(nonce)
            .put(ciphertext)
            .put(mac)
            .array()
        
        return Base64.getEncoder().encodeToString(result)
    }
    
    actual fun decrypt(ciphertext: String, privateKeyHex: String, pubKeyHex: String): String {
        val data = Base64.getDecoder().decode(ciphertext)
        
        println("   NIP-44 decrypt: data size = ${data.size}")
        
        if (data.size < 99) {
            throw IllegalArgumentException("Ciphertext too short: ${data.size} bytes")
        }
        
        val version = data[0]
        if (version.toInt() != 2) {
            throw IllegalArgumentException("Unsupported NIP-44 version: $version")
        }
        
        val nonce = data.copyOfRange(1, 33)
        val ciphertextBytes = data.copyOfRange(33, data.size - 32)
        val mac = data.copyOfRange(data.size - 32, data.size)
        
        println("   Nonce: ${nonce.toHexString().take(16)}...")
        println("   Ciphertext size: ${ciphertextBytes.size}")
        
        val conversationKey = getConversationKey(privateKeyHex, pubKeyHex)
        println("   Conversation key: ${conversationKey.toHexString().take(16)}...")
        
        val messageKeys = getMessageKeys(conversationKey, nonce)
        
        // Verify MAC: HMAC-SHA256(hmac_key, nonce || ciphertext)
        val expectedMac = hmacAad(messageKeys.hmacKey, ciphertextBytes, nonce)
        println("   Expected MAC: ${expectedMac.toHexString().take(16)}...")
        println("   Received MAC: ${mac.toHexString().take(16)}...")
        
        if (!mac.contentEquals(expectedMac)) {
            throw IllegalArgumentException("Invalid MAC")
        }
        
        val padded = chacha20(messageKeys.chachaKey, messageKeys.chachaNonce, ciphertextBytes)
        return unpad(padded)
    }
    
    private fun getConversationKey(privateKeyHex: String, pubKeyHex: String): ByteArray {
        val privateKeyBytes = privateKeyHex.hexToByteArray()
        
        // Try both Y coordinate parities (02 = even, 03 = odd)
        for (prefix in listOf("02", "03")) {
            try {
                val compressedPubKey = (prefix + pubKeyHex).hexToByteArray()
                
                // pubKeyTweakMul returns the shared point (33 bytes compressed)
                val sharedPoint = Secp256k1.pubKeyTweakMul(compressedPubKey, privateKeyBytes)
                
                // Extract x-coordinate (skip the 02/03 prefix byte)
                val sharedX = sharedPoint.copyOfRange(1, 33)
                
                println("   Shared X (${prefix}): ${sharedX.toHexString().take(16)}...")
                
                // NIP-44: conversation_key = HKDF-Extract(salt="nip44-v2", ikm=shared_x)
                return hkdfExtract("nip44-v2".toByteArray(), sharedX)
            } catch (e: Exception) {
                println("   pubKeyTweakMul failed with prefix $prefix: ${e.message}")
                continue
            }
        }
        throw IllegalArgumentException("Invalid public key: $pubKeyHex")
    }
    
    private data class MessageKeys(
        val chachaKey: ByteArray,
        val chachaNonce: ByteArray,
        val hmacKey: ByteArray
    )
    
    private fun getMessageKeys(conversationKey: ByteArray, nonce: ByteArray): MessageKeys {
        // HKDF-Expand(prk=conversation_key, info=nonce, L=76)
        val keys = hkdfExpand(conversationKey, nonce, 76)
        return MessageKeys(
            chachaKey = keys.copyOfRange(0, 32),
            chachaNonce = keys.copyOfRange(32, 44),
            hmacKey = keys.copyOfRange(44, 76)
        )
    }
    
    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }
    
    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val result = ByteArray(length)
        var prev = ByteArray(0)
        var offset = 0
        var counter: Byte = 1
        
        while (offset < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(prev)
            mac.update(info)
            mac.update(counter)
            prev = mac.doFinal()
            
            val toCopy = minOf(prev.size, length - offset)
            System.arraycopy(prev, 0, result, offset, toCopy)
            offset += toCopy
            counter++
        }
        
        return result
    }
    
    private fun chacha20(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("ChaCha20")
        val paramSpec = ChaCha20ParameterSpec(nonce, 0)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), paramSpec)
        return cipher.doFinal(data)
    }
    
    /**
     * HMAC with AAD: HMAC-SHA256(key, aad || message)
     */
    private fun hmacAad(key: ByteArray, message: ByteArray, aad: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(aad)       // AAD first (the nonce)
        mac.update(message)   // Then the ciphertext
        return mac.doFinal()
    }
    
    private fun pad(plaintext: String): ByteArray {
        val unpadded = plaintext.toByteArray(Charsets.UTF_8)
        if (unpadded.isEmpty() || unpadded.size > 65535) {
            throw IllegalArgumentException("Invalid plaintext length: ${unpadded.size}")
        }
        
        val paddedLength = calcPaddedLen(unpadded.size)
        val result = ByteArray(2 + paddedLength)
        
        // Big-endian length prefix
        result[0] = ((unpadded.size shr 8) and 0xFF).toByte()
        result[1] = (unpadded.size and 0xFF).toByte()
        
        System.arraycopy(unpadded, 0, result, 2, unpadded.size)
        
        return result
    }
    
    private fun unpad(padded: ByteArray): String {
        if (padded.size < 2) {
            throw IllegalArgumentException("Padded data too short")
        }
        
        val length = ((padded[0].toInt() and 0xFF) shl 8) or (padded[1].toInt() and 0xFF)
        
        if (length < 1 || length > padded.size - 2) {
            throw IllegalArgumentException("Invalid padding length: $length, padded size: ${padded.size}")
        }
        
        return String(padded, 2, length, Charsets.UTF_8)
    }
    
    private fun calcPaddedLen(unpaddedLen: Int): Int {
        if (unpaddedLen <= 32) return 32
        
        val nextPower = Integer.highestOneBit(unpaddedLen - 1) shl 1
        val chunk = maxOf(32, nextPower / 8)
        return ((unpaddedLen - 1) / chunk + 1) * chunk
    }
    
    private fun String.hexToByteArray(): ByteArray {
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    
    private fun ByteArray.toHexString(): String {
        return joinToString("") { byte -> "%02x".format(byte) }
    }
}
