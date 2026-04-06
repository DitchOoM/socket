package com.ditchoom.socket.transport

import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.socket.ConnectionContext

interface Transport {
    suspend fun connect(
        hostname: String,
        port: Int,
        context: ConnectionContext,
    ): ByteStream
}
