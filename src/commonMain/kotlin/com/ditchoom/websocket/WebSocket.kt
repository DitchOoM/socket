package com.ditchoom.websocket

import com.ditchoom.buffer.ParcelablePlatformBuffer
import com.ditchoom.socket.SocketController
import kotlin.time.ExperimentalTime

@ExperimentalTime
@ExperimentalUnsignedTypes
interface WebSocket : SocketController {
    suspend fun read(): WebSocketDataRead
    suspend fun write(buffer: ParcelablePlatformBuffer)
    suspend fun write(string: String)
    suspend fun ping()
}