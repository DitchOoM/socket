package com.ditchoom.socket.quic.core.edge

/** Whether a datagram holds a QUIC v1 0-RTT packet: the first packet of a coalesced datagram is long-header. */
internal fun carriesZeroRtt(bytes: ByteArray): Boolean {
    var at = 0
    while (at < bytes.size) {
        val first = bytes[at].toInt() and 0xff
        if (first and 0x80 == 0) return false
        val type = (first shr 4) and 0x03
        if (type == ZERO_RTT) return true
        var cursor = at + 5
        cursor += 1 + (bytes[cursor].toInt() and 0xff)
        cursor += 1 + (bytes[cursor].toInt() and 0xff)
        if (type == INITIAL) {
            val (token, width) = varInt(bytes, cursor)
            cursor += width + token.toInt()
        }
        val (length, width) = varInt(bytes, cursor)
        at = cursor + width + length.toInt()
    }
    return false
}

private fun varInt(
    bytes: ByteArray,
    at: Int,
): Pair<Long, Int> {
    val first = bytes[at].toInt() and 0xff
    val width = 1 shl (first shr 6)
    var value = (first and 0x3f).toLong()
    for (i in 1 until width) value = (value shl 8) or (bytes[at + i].toLong() and 0xff)
    return value to width
}

private const val INITIAL = 0
private const val ZERO_RTT = 1
