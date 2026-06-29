package com.ditchoom.socket.transport

import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.socket.TransportConfig

interface Transport {
    suspend fun connect(
        hostname: String,
        port: Int,
        config: TransportConfig,
    ): ByteStream
}
