package com.ditchoom.websocket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocateNewBuffer
import com.ditchoom.buffer.toBuffer
import com.ditchoom.data.DataTransformer
import kotlin.random.Random

object WebSocketClientToServerTextFrameTransformer : DataTransformer<String, PlatformBuffer> {

    override suspend fun transform(input: String): PlatformBuffer {
        val applyFin = true
        val bytes = Random.nextBytes(4)
        val frame = WebSocketFrame(applyFin, Opcode.Text, MaskingKey.FourByteMaskingKey(bytes), input.toBuffer())
        val websocketEncodedBuffer = allocateNewBuffer(frame.size().toUInt())
        frame.serialize(websocketEncodedBuffer)
        return websocketEncodedBuffer
    }
}