package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.PlatformBuffer

/** [buffer]'s remaining bytes as lowercase hex. */
internal fun hex(buffer: PlatformBuffer): String {
    val out = StringBuilder(buffer.remaining() * 2)
    for (i in buffer.position() until buffer.limit()) {
        val b = buffer.get(i).toInt() and 0xFF
        out.append(HEX[b ushr 4]).append(HEX[b and 0xF])
    }
    return out.toString()
}

private const val HEX = "0123456789abcdef"
