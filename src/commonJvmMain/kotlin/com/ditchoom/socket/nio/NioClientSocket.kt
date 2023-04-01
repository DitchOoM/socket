package com.ditchoom.socket.nio

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.socket.ClientToServerSocket
import com.ditchoom.socket.SocketException
import com.ditchoom.socket.nio.util.aConfigureBlocking
import com.ditchoom.socket.nio.util.buildInetAddress
import com.ditchoom.socket.nio.util.connect
import com.ditchoom.socket.nio.util.openSocketChannel
import java.net.InetSocketAddress
import kotlin.time.Duration

class NioClientSocket(
    allocationZone: AllocationZone,
    blocking: Boolean = true,
) : BaseClientSocket(allocationZone, blocking), ClientToServerSocket {
    override suspend fun open(
        port: Int,
        timeout: Duration,
        hostname: String?
    ) {
        val socketAddress = buildInetAddress(port, hostname)
        val socketChannel = openSocketChannel()
        socketChannel.aConfigureBlocking(blocking)
        this.socket = socketChannel
        if (!socketChannel.connect(socketAddress, selector, timeout)) {
            throw SocketException("Failed to connect client ${(socketAddress as? InetSocketAddress)?.port} $socketChannel")
        }
    }
}
