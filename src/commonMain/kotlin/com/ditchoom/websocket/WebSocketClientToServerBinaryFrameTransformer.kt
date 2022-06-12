package com.ditchoom.websocket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.data.DataTransformer
import kotlin.random.Random

/**
 * Transforms a buffer into a websocket wrapped buffer which can then be sent directly on the socket
 */
object WebSocketClientToServerBinaryFrameTransformer :
    DataTransformer<PlatformBuffer, PlatformBuffer> {

    override suspend fun transform(input: PlatformBuffer): PlatformBuffer {
        val applyFin = true
        val bytes = Random.nextBytes(4)
        val frame = WebSocketFrame(applyFin, Opcode.Binary, MaskingKey.FourByteMaskingKey(bytes), input)
        val websocketEncodedBuffer = PlatformBuffer.allocate(frame.size())
        frame.serialize(websocketEncodedBuffer)
        return websocketEncodedBuffer
    }
}