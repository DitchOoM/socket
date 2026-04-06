package com.ditchoom.socket.transport

import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.ConnectionContext
import com.ditchoom.socket.allocate

class TcpTransport : Transport {
    override suspend fun connect(
        hostname: String,
        port: Int,
        context: ConnectionContext,
    ): ByteStream {
        val socket = ClientSocket.allocate()
        socket.bufferFactory = context.bufferFactory
        socket.open(port, context.options.connectionTimeout, hostname, context.options.socketOptions)
        return TcpByteStream(socket, context)
    }
}
