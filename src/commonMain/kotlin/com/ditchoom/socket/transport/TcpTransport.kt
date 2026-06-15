package com.ditchoom.socket.transport

import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.allocate

class TcpTransport : Transport {
    override suspend fun connect(
        hostname: String,
        port: Int,
        config: TransportConfig,
    ): ByteStream {
        val socket = ClientSocket.allocate()
        // ClientSocket IS a ByteStream — no TcpByteStream adapter. The socket adopts the
        // injected bufferFactory + read/write policy from config at open() time.
        socket.open(port, hostname, config)
        return socket
    }
}
