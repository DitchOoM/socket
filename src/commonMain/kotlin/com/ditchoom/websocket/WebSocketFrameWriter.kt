@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.websocket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocateNewBuffer
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.SocketController
import kotlin.experimental.and
import kotlin.experimental.or
import kotlin.experimental.xor
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@ExperimentalTime
class WebSocketFrameWriter(private val clientSocket: ClientSocket, private val timeout: Duration) {

    suspend fun write(buffer: PlatformBuffer) {
        val payloadSize = buffer.remaining()
        val websocketFrameOverhead = when {
            payloadSize > UShort.MAX_VALUE -> websocketBaseFrameOverhead + 8
            payloadSize >= 126u -> websocketBaseFrameOverhead + 2
            else -> websocketBaseFrameOverhead
        }
        val length = payloadSize.toLong() + websocketFrameOverhead.toLong()
        val writeBuffer = allocateNewBuffer(length.toUInt())
        appendFinAndOpCode(writeBuffer, 2, true)
        val mask = Random.Default.nextBytes(4)
        appendLengthAndMask(writeBuffer, payloadSize.toInt(), mask)
        val startPayloadPosition = writeBuffer.position()
        // write the serialized data
        writeBuffer.write(buffer)
        // reset position to original, we are going to mask these values
        for ((count, position) in (startPayloadPosition.toInt() until length).withIndex()) {
            writeBuffer.position(position.toInt())
            val payloadByte = writeBuffer.readByte()
            val maskValue = mask[count % 4]
            val maskedByte = payloadByte xor maskValue
            writeBuffer.position(position.toInt())
            writeBuffer.write(maskedByte)
        }
        clientSocket.write(writeBuffer, timeout)
    }

    private fun appendFinAndOpCode(buffer: PlatformBuffer, opcode: Byte, fin: Boolean) {
        var b: Byte = 0x00
        // Add Fin flag
        if (fin) {
            b = b or 0x80.toByte()
        }
        // RSV 1,2,3 aren't important
        // Add opcode
        b = b or (opcode and 0x0F)
        buffer.write(b)
    }

    private fun appendLengthAndMask(buffer: PlatformBuffer, length: Int, mask: ByteArray) {
        appendLength(buffer, length, true)
        buffer.write(mask)
    }

    private fun appendLength(buffer: PlatformBuffer, length: Int, masked: Boolean) {
        if (length < 0) {
            throw IllegalArgumentException("Length cannot be negative")
        }
        val b = if (masked) 0x80.toByte() else 0x00
        when {
            length > 0xFFFF -> {
                buffer.write((b or 0x7F))
                buffer.write(0x00.toByte())
                buffer.write(0x00.toByte())
                buffer.write(0x00.toByte())
                buffer.write(0x00.toByte())
                buffer.write((length shr 24 and 0xFF).toByte())
                buffer.write((length shr 16 and 0xFF).toByte())
                buffer.write((length shr 8 and 0xFF).toByte())
                buffer.write((length and 0xFF).toByte())
            }
            length >= 0x7E -> {
                buffer.write((b or 0x7E))
                buffer.write((length shr 8).toByte())
                buffer.write((length and 0xFF).toByte())
            }
            else -> {
                buffer.write((b or length.toByte()))
            }
        }
    }
    companion object {
        private const val websocketBaseFrameOverhead = 6
    }
}