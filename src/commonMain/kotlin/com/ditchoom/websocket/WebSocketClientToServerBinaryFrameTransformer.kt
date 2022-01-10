package com.ditchoom.websocket

import com.ditchoom.buffer.ParcelablePlatformBuffer
import com.ditchoom.buffer.allocateNewBuffer
import com.ditchoom.data.DataTransformer
import kotlin.random.Random

/**
 * Transforms a buffer into a websocket wrapped buffer which can then be sent directly on the socket
 */
@ExperimentalUnsignedTypes
object WebSocketClientToServerBinaryFrameTransformer :
    DataTransformer<ParcelablePlatformBuffer, ParcelablePlatformBuffer> {

    override suspend fun transform(input: ParcelablePlatformBuffer): ParcelablePlatformBuffer {
        val applyFin = true
        val bytes = Random.nextBytes(4)
        val frame = WebSocketFrame(applyFin, Opcode.Binary, MaskingKey.FourByteMaskingKey(bytes), input)
        val websocketEncodedBuffer = allocateNewBuffer(frame.size().toUInt())
        frame.serialize(websocketEncodedBuffer)
        return websocketEncodedBuffer
    }
}