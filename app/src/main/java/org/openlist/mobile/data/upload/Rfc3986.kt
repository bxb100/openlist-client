package org.openlist.mobile.data.upload

object Rfc3986 {
    private val hex = "0123456789ABCDEF".toCharArray()

    /**
     * Percent-encodes UTF-8 bytes using the RFC 3986 unreserved set.
     * Slashes are deliberately encoded because OpenList expects File-Path as one header value.
     */
    fun encode(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val result = StringBuilder(bytes.size)
        for (byte in bytes) {
            val valueByte = byte.toInt() and 0xff
            if (isUnreserved(valueByte)) {
                result.append(valueByte.toChar())
            } else {
                result.append('%')
                result.append(hex[valueByte ushr 4])
                result.append(hex[valueByte and 0x0f])
            }
        }
        return result.toString()
    }

    private fun isUnreserved(value: Int): Boolean =
        value in 'a'.code..'z'.code ||
            value in 'A'.code..'Z'.code ||
            value in '0'.code..'9'.code ||
            value == '-'.code ||
            value == '.'.code ||
            value == '_'.code ||
            value == '~'.code
}
