package org.nostr.nostrord.nostr

import fr.acinq.secp256k1.Secp256k1
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.Base64
import kotlin.random.Random

actual object Nip04 {
    
    actual fun encrypt(plaintext: String, privateKeyHex: String, pubKeyHex: String): String {
        val sharedSecret = computeSharedSecret(privateKeyHex, pubKeyHex)
        
        val iv = Random.nextBytes(16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(sharedSecret, "AES")
        val ivSpec = IvParameterSpec(iv)
        
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        
        val encryptedBase64 = Base64.getEncoder().encodeToString(encrypted)
        val ivBase64 = Base64.getEncoder().encodeToString(iv)
        
        return "$encryptedBase64?iv=$ivBase64"
    }
    
    actual fun decrypt(ciphertext: String, privateKeyHex: String, pubKeyHex: String): String {
        val parts = ciphertext.split("?iv=")
        if (parts.size != 2) {
            throw IllegalArgumentException("Invalid ciphertext format")
        }
        
        val encryptedBase64 = parts[0]
        val ivBase64 = parts[1]
        
        val encrypted = Base64.getDecoder().decode(encryptedBase64)
        val iv = Base64.getDecoder().decode(ivBase64)
        
        val sharedSecret = computeSharedSecret(privateKeyHex, pubKeyHex)
        
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(sharedSecret, "AES")
        val ivSpec = IvParameterSpec(iv)
        
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        val decrypted = cipher.doFinal(encrypted)
        
        return String(decrypted, Charsets.UTF_8)
    }
    
    private fun computeSharedSecret(privateKeyHex: String, pubKeyHex: String): ByteArray {
        val privateKeyBytes = privateKeyHex.hexToByteArray()
        
        // Convert 32-byte x-only pubkey to 33-byte compressed pubkey (add 02 prefix)
        val compressedPubKey = ("02" + pubKeyHex).hexToByteArray()
        
        // Use pubKeyTweakMul to multiply the public key point by our private key scalar
        // This gives us the shared point (same as ECDH)
        val sharedPoint = Secp256k1.pubKeyTweakMul(compressedPubKey, privateKeyBytes)
        
        // The result is a 33-byte compressed public key
        // NIP-04 uses the X coordinate (bytes 1-32, skip the prefix byte)
        return sharedPoint.copyOfRange(1, 33)
    }
    
    private fun String.hexToByteArray(): ByteArray {
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
