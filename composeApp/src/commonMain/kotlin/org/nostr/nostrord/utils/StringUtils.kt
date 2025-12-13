package org.nostr.nostrord.utils

fun String.urlDecode(): String {
    val result = StringBuilder()
    var i = 0
    while (i < length) {
        when {
            this[i] == '%' && i + 2 < length -> {
                try {
                    val hex = substring(i + 1, i + 3)
                    result.append(hex.toInt(16).toChar())
                    i += 3
                } catch (e: NumberFormatException) {
                    result.append(this[i])
                    i++
                }
            }
            this[i] == '+' -> {
                result.append(' ')
                i++
            }
            else -> {
                result.append(this[i])
                i++
            }
        }
    }
    return result.toString()
}
