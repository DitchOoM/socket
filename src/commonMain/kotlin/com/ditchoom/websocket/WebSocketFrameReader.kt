@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.websocket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.SuspendingSocketInputStream
import kotlin.experimental.and
import kotlin.time.ExperimentalTime

@ExperimentalTime
class WebSocketFrameReader(val inputStream: SuspendingSocketInputStream) {

    suspend fun read(): ReadBuffer? {
        val metadata = readFrameMetadata() ?: return null
        return inputStream.sizedReadBuffer(metadata.payloadLength)
    }

    private suspend fun readFrameMetadata(): FrameMetadata? {
        val firstByte = inputStream.readByte()
//        val fin = firstByte and 0x80.toByte() != 0.toByte()
        val opcode = firstByte and 0x0F.toByte()
        check(opcode == 2.toByte() || opcode == 8.toByte()) { "Unsupported websocket opcode for mqtt, opcode: $opcode" }
        if (opcode == 8.toByte()) { // Connection closed
            return null
        }
        var maskedByteLength = inputStream.readByte()
        val masked = maskedByteLength and 0x80.toByte() != 0.toByte()
        var websocketPayloadLength: Int = (0x7F.toByte() and maskedByteLength).toInt()
        var byteCount = when (websocketPayloadLength) {
            0x7F -> 8
            0x7E -> 2
            else -> 0
        }
        if (byteCount > 0) websocketPayloadLength = 0
        while (--byteCount >= 0) {
            maskedByteLength = inputStream.readByte()
            websocketPayloadLength =
                websocketPayloadLength or ((maskedByteLength and 0xFF.toByte()).toInt() shl 8 * byteCount)
        }
        return FrameMetadata(masked, websocketPayloadLength)
    }
}