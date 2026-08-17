package org.nostr.nostrord.utils

import org.nostr.nostrord.nostr.hexToByteArray
import org.nostr.nostrord.nostr.toHexString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * NIST SP 800-38D / GCM test vectors, 96-bit IVs and no additional data, which is exactly the
 * shape Web Crypto produces for the file blobs behind NIP-17 kind:15 messages.
 *
 * Each vector is fed as ciphertext || tag, the layout the browser writes.
 */
class AesGcmTest {
    @Test
    fun `decrypts the AES-128 vector`() {
        val plaintext =
            AesGcm.decryptUnauthenticated(
                key = "feffe9928665731c6d6a8f9467308308".hexToByteArray(),
                nonce = "cafebabefacedbaddecaf888".hexToByteArray(),
                data = (
                    "42831ec2217774244b7221b784d0d49ce3aa212f2c02a4e035c17e2329aca12e" +
                        "21d514b25466931c7d8f6a5aac84aa051ba30b396a0aac973d58e091473f5985" +
                        "4d5c2af327cd64a62cf35abd2ba6fab4"
                    ).hexToByteArray(),
            )
        assertEquals(
            "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b391aafd255",
            plaintext?.toHexString(),
        )
    }

    @Test
    fun `decrypts the AES-256 vector`() {
        val plaintext =
            AesGcm.decryptUnauthenticated(
                key = "feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308".hexToByteArray(),
                nonce = "cafebabefacedbaddecaf888".hexToByteArray(),
                data = (
                    "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa" +
                        "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662898015ad" +
                        "b094dac5d93471bdec1a502270e3cc6c"
                    ).hexToByteArray(),
            )
        assertEquals(
            "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b391aafd255",
            plaintext?.toHexString(),
        )
    }

    @Test
    fun `decrypts an empty payload`() {
        val plaintext =
            AesGcm.decryptUnauthenticated(
                key = "00000000000000000000000000000000".hexToByteArray(),
                nonce = "000000000000000000000000".hexToByteArray(),
                data = "58e2fccefa7e3061367f1d57a4e7455a".hexToByteArray(),
            )
        assertEquals("", plaintext?.toHexString())
    }

    @Test
    fun `rejects a nonce that is not 96 bits`() {
        assertNull(
            AesGcm.decryptUnauthenticated(
                key = ByteArray(32),
                nonce = ByteArray(16),
                data = ByteArray(32),
            ),
        )
    }

    @Test
    fun `rejects a key of the wrong size`() {
        assertNull(
            AesGcm.decryptUnauthenticated(
                key = ByteArray(20),
                nonce = ByteArray(12),
                data = ByteArray(32),
            ),
        )
    }

    @Test
    fun `rejects a payload too short to hold a tag`() {
        assertNull(
            AesGcm.decryptUnauthenticated(
                key = ByteArray(32),
                nonce = ByteArray(12),
                data = ByteArray(8),
            ),
        )
    }

    @Test
    fun `decrypts a payload spanning several counter blocks`() {
        // 70 bytes: five AES blocks with a partial last one, so the CTR counter has to advance
        // and the tail has to stop mid-block.
        val plaintext =
            AesGcm.decryptUnauthenticated(
                key = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".hexToByteArray(),
                nonce = "0102030405060708090a0b0c".hexToByteArray(),
                data = (
                    "c7bc994c11a6daaf79962ebfdfa587a4d26960c2c1d91e2b4ba05c7adec35369" +
                        "feeb6a59ecabd6fe085933ec423636258e83abf2c50415367aefbced4ce3140f" +
                        "bacb9dab3fc1bd9aef93c9a11ca4b928ee58854015b5"
                    ).hexToByteArray(),
            )
        assertEquals(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
                "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f" +
                "404142434445",
            plaintext?.toHexString(),
        )
    }
}
