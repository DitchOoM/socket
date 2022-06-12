package com.ditchoom.websocket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.SocketController

interface WebSocket : SocketController {
    suspend fun read(): WebSocketDataRead
    suspend fun write(buffer: PlatformBuffer)
    suspend fun write(string: String)
    suspend fun ping()
}