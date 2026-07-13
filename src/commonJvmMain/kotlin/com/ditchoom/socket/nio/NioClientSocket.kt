package com.ditchoom.socket.nio

import com.ditchoom.socket.ClientToServerSocket
import com.ditchoom.socket.SocketIOException
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.nio.util.aConfigureBlocking
import com.ditchoom.socket.nio.util.buildInetAddress
import com.ditchoom.socket.nio.util.connect
import com.ditchoom.socket.nio.util.openSocketChannel
import java.net.InetSocketAddress

class NioClientSocket(
    blocking: Boolean = true,
    config: TransportConfig = TransportConfig(),
) : BaseClientSocket(blocking, config),
    ClientToServerSocket {
    override suspend fun open(
        port: Int,
        hostname: String?,
    ) {
        val timeout = config.connectTimeout
        val socketAddress = buildInetAddress(port, hostname)
        val socketChannel = openSocketChannel()
        // Assign socket immediately so close() can clean it up if subsequent operations fail
        this.socket = socketChannel
        try {
            socketChannel.aConfigureBlocking(blocking)
            if (!socketChannel.connect(socketAddress, selector, timeout)) {
                throw SocketIOException("Failed to connect client ${(socketAddress as? InetSocketAddress)?.port} $socketChannel")
            }
            applySocketOptions(config.io)
            config.tls?.let { initTls(hostname, port, it, timeout) }
        } catch (e: Throwable) {
            // Ensure socket is closed on any failure during open
            try {
                socketChannel.close()
            } catch (_: Throwable) {
                // Ignore close errors
            }
            throw e
        }
    }
}
