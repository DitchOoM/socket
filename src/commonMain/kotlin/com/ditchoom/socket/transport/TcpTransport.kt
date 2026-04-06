package com.ditchoom.socket.transport

import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.allocate

class TcpTransport : Transport {
    override suspend fun connect(
        hostname: String,
        port: Int,
        options: ConnectionOptions,
    ): ByteStream {
        val socket = ClientSocket.allocate()
        socket.bufferFactory = options.bufferFactory
        socket.open(port, options.connectionTimeout, hostname, options.socketOptions)
        return TcpByteStream(socket)
    }
}
