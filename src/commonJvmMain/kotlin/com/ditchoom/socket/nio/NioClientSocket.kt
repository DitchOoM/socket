package com.ditchoom.socket.nio

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.socket.ClientToServerSocket
import com.ditchoom.socket.SocketOptions
import com.ditchoom.socket.nio.util.*
import java.net.InetSocketAddress
import kotlin.time.Duration

class NioClientSocket(
    override val allocationZone: AllocationZone = AllocationZone.Direct,
    blocking: Boolean = true,
) : BaseClientSocket(blocking), ClientToServerSocket {
    override suspend fun open(
        port: UShort,
        timeout: Duration,
        hostname: String?,
        socketOptions: SocketOptions?
    ): SocketOptions {
        val socketAddress = InetSocketAddress(hostname?.asInetAddress(), port.toInt())
        val socketChannel = openSocketChannel()
        socketChannel.aConfigureBlocking(blocking)
        this.socket = socketChannel
        if (!socketChannel.connect(socketAddress, selector, timeout)) {
            println("FAILED TO CONNECT CLIENT client ${(socketChannel.remoteAddress as? InetSocketAddress)?.port} $socketChannel")
        }
        return socketChannel.asyncSetOptions(socketOptions)
    }
}


