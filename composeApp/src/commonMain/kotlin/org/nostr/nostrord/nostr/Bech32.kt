package org.nostr.nostrord.nostr

/**
 * Bech32 encoding/decoding implementation for Nostr
 * Based on BIP-173 specification
 */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val CHARSET_REV = IntArray(128) { -1 }.apply {
        CHARSET.forEachIndexed { i, c -> this[c.code] = i }
    }

    private fun polymod(values: IntArray): Int {
        val generator = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) {
            val top = chk shr 25
            chk = (chk and 0x1ffffff) shl 5 xor v
            for (i in 0..4) {
                if ((top shr i) and 1 == 1) {
                    chk = chk xor generator[i]
                }
            }
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val result = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) {
            result[i] = hrp[i].code shr 5
            result[i + hrp.length + 1] = hrp[i].code and 31
        }
        return result
    }

    private fun verifyChecksum(hrp: String, data: IntArray): Boolean {
        return polymod(hrpExpand(hrp) + data) == 1
    }

    private fun createChecksum(hrp: String, data: IntArray): IntArray {
        val values = hrpExpand(hrp) + data + intArrayOf(0, 0, 0, 0, 0, 0)
        val polymod = polymod(values) xor 1
        return IntArray(6) { i -> (polymod shr (5 * (5 - i))) and 31 }
    }

    /**
     * Encode data to bech32 string
     */
    fun encode(hrp: String, data: ByteArray): String {
        val data5bit = convertBits(data, 8, 5, true)
        val checksum = createChecksum(hrp, data5bit)
        val combined = data5bit + checksum
        return hrp + "1" + combined.map { CHARSET[it] }.joinToString("")
    }

    /**
     * Decode bech32 string to hrp and data
     */
    fun decode(bech32: String): Pair<String, ByteArray>? {
        val lower = bech32.lowercase()
        if (bech32 != lower && bech32 != bech32.uppercase()) return null

        val pos = lower.lastIndexOf('1')
        // NIP-19 entities (nevent, nprofile, naddr) can exceed BIP-173's 90 char limit
        if (pos < 1 || pos + 7 > lower.length) return null

        val hrp = lower.substring(0, pos)
        val data = IntArray(lower.length - pos - 1)
        for (i in data.indices) {
            val c = lower[pos + 1 + i]
            if (c.code >= 128 || CHARSET_REV[c.code] == -1) return null
            data[i] = CHARSET_REV[c.code]
        }

        if (!verifyChecksum(hrp, data)) return null

        val converted = convertBits(data.sliceArray(0 until data.size - 6), 5, 8, false)
        return Pair(hrp, converted.map { it.toByte() }.toByteArray())
    }

    private fun convertBits(data: IntArray, fromBits: Int, toBits: Int, pad: Boolean): IntArray {
        var acc = 0
        var bits = 0
        val result = mutableListOf<Int>()
        val maxv = (1 shl toBits) - 1

        for (value in data) {
            acc = (acc shl fromBits) or value
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                result.add((acc shr bits) and maxv)
            }
        }

        if (pad) {
            if (bits > 0) {
                result.add((acc shl (toBits - bits)) and maxv)
            }
        }

        return result.toIntArray()
    }

    private fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): IntArray {
        return convertBits(data.map { it.toInt() and 0xFF }.toIntArray(), fromBits, toBits, pad)
    }
}
