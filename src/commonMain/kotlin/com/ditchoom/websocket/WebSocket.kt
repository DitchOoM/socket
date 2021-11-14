package com.ditchoom.websocket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.SocketController
import com.ditchoom.socket.SocketDataRead
import kotlin.time.ExperimentalTime

@ExperimentalTime
@ExperimentalUnsignedTypes
interface WebSocket : SocketController {
    suspend fun read(): WebSocketDataRead
    suspend fun write(buffer: PlatformBuffer)
    suspend fun write(string: String)
    suspend fun ping()
}