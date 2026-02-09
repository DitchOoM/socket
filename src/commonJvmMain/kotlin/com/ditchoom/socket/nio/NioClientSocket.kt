package com.ditchoom.socket.nio

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.socket.ClientToServerSocket
import com.ditchoom.socket.SocketException
import com.ditchoom.socket.SocketOptions
import com.ditchoom.socket.nio.util.aConfigureBlocking
import com.ditchoom.socket.nio.util.buildInetAddress
import com.ditchoom.socket.nio.util.connect
import com.ditchoom.socket.nio.util.openSocketChannel
import java.net.InetSocketAddress
import kotlin.time.Duration

class NioClientSocket(
    allocationZone: AllocationZone,
    blocking: Boolean = true,
) : BaseClientSocket(allocationZone, blocking),
    ClientToServerSocket {
    override suspend fun open(
        port: Int,
        timeout: Duration,
        hostname: String?,
        socketOptions: SocketOptions,
    ) {
        val socketAddress = buildInetAddress(port, hostname)
        val socketChannel = openSocketChannel()
        // Assign socket immediately so close() can clean it up if subsequent operations fail
        this.socket = socketChannel
        try {
            socketChannel.aConfigureBlocking(blocking)
            if (!socketChannel.connect(socketAddress, selector, timeout)) {
                throw SocketException("Failed to connect client ${(socketAddress as? InetSocketAddress)?.port} $socketChannel")
            }
            applySocketOptions(socketOptions)
            socketOptions.tls?.let { initTls(hostname, port, it, timeout) }
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
