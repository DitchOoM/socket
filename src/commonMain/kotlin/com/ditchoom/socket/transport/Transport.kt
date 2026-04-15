package com.ditchoom.socket.transport

import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.socket.ConnectionOptions

interface Transport {
    suspend fun connect(
        hostname: String,
        port: Int,
        options: ConnectionOptions,
    ): ByteStream
}
