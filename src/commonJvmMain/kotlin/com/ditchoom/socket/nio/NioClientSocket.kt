package com.ditchoom.socket.nio

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.ClientToServerSocket
import com.ditchoom.socket.SocketException
import com.ditchoom.socket.SocketOptions
import com.ditchoom.socket.nio.util.*
import java.net.InetSocketAddress
import kotlin.time.Duration

class NioClientSocket(
    bufferFactory: () -> PlatformBuffer,
    blocking: Boolean = true,
) : BaseClientSocket(bufferFactory, blocking), ClientToServerSocket {
    override suspend fun open(
        port: Int,
        timeout: Duration,
        hostname: String?,
        socketOptions: SocketOptions?
    ): SocketOptions {
        val socketAddress = InetSocketAddress(hostname?.asInetAddress(), port)
        val socketChannel = openSocketChannel()
        socketChannel.aConfigureBlocking(blocking)
        this.socket = socketChannel
        if (!socketChannel.connect(socketAddress, selector, timeout)) {
            throw SocketException("Failed to connect client ${(socketChannel.remoteAddress as? InetSocketAddress)?.port} $socketChannel")
        }
        return socketChannel.asyncSetOptions(socketOptions)
    }
}


