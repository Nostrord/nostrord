package org.nostr.nostrord.utils

/**
 * AES in pure Kotlin, enough to read the AES-GCM blobs behind NIP-17 kind:15 file messages:
 * the media server holds ciphertext and the key/nonce ride in the rumor's tags.
 *
 * Common code rather than four platform actuals so JVM, Android, JS and iOS behave identically
 * and the NIST vectors in `AesGcmTest` cover every target at once.
 *
 * Only GCM's confidentiality layer (CTR) is implemented; see [decryptUnauthenticated].
 */
object AesGcm {
    /** Web Crypto appends the 16-byte GCM tag to the ciphertext. */
    const val TAG_SIZE = 16

    /**
     * Decrypt a GCM blob WITHOUT verifying its authentication tag: the trailing tag is stripped
     * and the body run through CTR. [key] is 16/24/32 bytes, [nonce] the 12-byte IV. Null when
     * the sizes are wrong.
     *
     * Callers MUST authenticate the result themselves. For file messages that is the rumor's `ox`
     * (SHA-256 of the plaintext), which is both cheaper here (the platform's native SHA-256 beats
     * a GHASH written in Kotlin, and these are multi-megabyte images) and a stronger binding: `ox`
     * comes from the sender's sealed, signed rumor, whereas the GCM tag only proves whoever wrote
     * the blob held the key. See `DmFileManager`.
     */
    fun decryptUnauthenticated(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray? {
        if (nonce.size != 12) return null
        if (data.size < TAG_SIZE) return null
        val schedule = expandKey(key) ?: return null
        val body = data.copyOfRange(0, data.size - TAG_SIZE)
        // For a 96-bit IV, J0 = IV || 1 and the payload counter starts at J0 + 1.
        return ctr(schedule, nonce, startCounter = 2, data = body)
    }

    /** AES-CTR over [data] in place of a fresh copy of it; counter block is [nonce] || 32-bit BE. */
    private fun ctr(schedule: KeySchedule, nonce: ByteArray, startCounter: Int, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        val counterBlock = ByteArray(16)
        nonce.copyInto(counterBlock, 0, 0, 12)
        val keystream = ByteArray(16)
        var counter = startCounter
        var offset = 0
        while (offset < data.size) {
            counterBlock[12] = (counter ushr 24).toByte()
            counterBlock[13] = (counter ushr 16).toByte()
            counterBlock[14] = (counter ushr 8).toByte()
            counterBlock[15] = counter.toByte()
            encryptBlock(schedule, counterBlock, keystream)
            val n = minOf(16, data.size - offset)
            for (i in 0 until n) {
                out[offset + i] = (data[offset + i].toInt() xor keystream[i].toInt()).toByte()
            }
            offset += n
            counter++
        }
        return out
    }

    private class KeySchedule(val roundKeys: IntArray, val rounds: Int)

    private fun expandKey(key: ByteArray): KeySchedule? {
        val nk =
            when (key.size) {
                16 -> 4
                24 -> 6
                32 -> 8
                else -> return null
            }
        val rounds = nk + 6
        val w = IntArray(4 * (rounds + 1))
        for (i in 0 until nk) {
            w[i] =
                (key[4 * i].toInt() and 0xff shl 24) or
                (key[4 * i + 1].toInt() and 0xff shl 16) or
                (key[4 * i + 2].toInt() and 0xff shl 8) or
                (key[4 * i + 3].toInt() and 0xff)
        }
        for (i in nk until w.size) {
            var t = w[i - 1]
            if (i % nk == 0) {
                t = subWord((t shl 8) or (t ushr 24)) xor (RCON[i / nk] shl 24)
            } else if (nk > 6 && i % nk == 4) {
                t = subWord(t)
            }
            w[i] = w[i - nk] xor t
        }
        return KeySchedule(w, rounds)
    }

    private fun subWord(w: Int): Int = (SBOX[(w ushr 24) and 0xff] shl 24) or
        (SBOX[(w ushr 16) and 0xff] shl 16) or
        (SBOX[(w ushr 8) and 0xff] shl 8) or
        SBOX[w and 0xff]

    private fun encryptBlock(schedule: KeySchedule, input: ByteArray, output: ByteArray) {
        val rk = schedule.roundKeys
        var s0 = beInt(input, 0) xor rk[0]
        var s1 = beInt(input, 4) xor rk[1]
        var s2 = beInt(input, 8) xor rk[2]
        var s3 = beInt(input, 12) xor rk[3]
        var k = 4
        repeat(schedule.rounds - 1) {
            val t0 = TE0[(s0 ushr 24) and 0xff] xor TE1[(s1 ushr 16) and 0xff] xor TE2[(s2 ushr 8) and 0xff] xor TE3[s3 and 0xff] xor rk[k]
            val t1 = TE0[(s1 ushr 24) and 0xff] xor TE1[(s2 ushr 16) and 0xff] xor TE2[(s3 ushr 8) and 0xff] xor TE3[s0 and 0xff] xor rk[k + 1]
            val t2 = TE0[(s2 ushr 24) and 0xff] xor TE1[(s3 ushr 16) and 0xff] xor TE2[(s0 ushr 8) and 0xff] xor TE3[s1 and 0xff] xor rk[k + 2]
            val t3 = TE0[(s3 ushr 24) and 0xff] xor TE1[(s0 ushr 16) and 0xff] xor TE2[(s1 ushr 8) and 0xff] xor TE3[s2 and 0xff] xor rk[k + 3]
            s0 = t0
            s1 = t1
            s2 = t2
            s3 = t3
            k += 4
        }
        // Final round: SubBytes + ShiftRows + AddRoundKey, no MixColumns.
        putInt(output, 0, finalRound(s0, s1, s2, s3) xor rk[k])
        putInt(output, 4, finalRound(s1, s2, s3, s0) xor rk[k + 1])
        putInt(output, 8, finalRound(s2, s3, s0, s1) xor rk[k + 2])
        putInt(output, 12, finalRound(s3, s0, s1, s2) xor rk[k + 3])
    }

    private fun finalRound(a: Int, b: Int, c: Int, d: Int): Int = (SBOX[(a ushr 24) and 0xff] shl 24) or
        (SBOX[(b ushr 16) and 0xff] shl 16) or
        (SBOX[(c ushr 8) and 0xff] shl 8) or
        SBOX[d and 0xff]

    private fun beInt(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xff shl 24) or
        (b[i + 1].toInt() and 0xff shl 16) or
        (b[i + 2].toInt() and 0xff shl 8) or
        (b[i + 3].toInt() and 0xff)

    private fun putInt(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 24).toByte()
        b[i + 1] = (v ushr 16).toByte()
        b[i + 2] = (v ushr 8).toByte()
        b[i + 3] = v.toByte()
    }

    private val RCON = intArrayOf(0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36, 0x6c, 0xd8, 0xab, 0x4d)

    private val SBOX =
        intArrayOf(
            0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
            0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
            0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
            0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
            0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
            0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
            0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
            0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
            0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
            0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
            0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
            0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
            0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
            0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
            0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
            0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16,
        )

    // Round tables: TE0[x] = [2s, s, s, 3s] for s = SBOX[x]; TE1..TE3 are byte rotations of it.
    private val TE0 =
        IntArray(256) { x ->
            val s = SBOX[x]
            val s2 = xtime(s)
            val s3 = s2 xor s
            (s2 shl 24) or (s shl 16) or (s shl 8) or s3
        }
    private val TE1 = IntArray(256) { rotr8(TE0[it]) }
    private val TE2 = IntArray(256) { rotr8(TE1[it]) }
    private val TE3 = IntArray(256) { rotr8(TE2[it]) }

    private fun rotr8(v: Int): Int = (v ushr 8) or (v shl 24)

    /** Multiply by x in GF(2^8) with the AES reduction polynomial. */
    private fun xtime(b: Int): Int {
        val shifted = b shl 1
        return if (b and 0x80 != 0) (shifted xor 0x1b) and 0xff else shifted and 0xff
    }
}
