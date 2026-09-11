package com.ditchoom.socket.nio

import com.ditchoom.socket.ClientToServerSocket
import com.ditchoom.socket.SocketIOException
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.candidatesFor
import com.ditchoom.socket.firstReachable
import com.ditchoom.socket.nio.util.aConfigureBlocking
import com.ditchoom.socket.nio.util.connect
import com.ditchoom.socket.nio.util.openSocketChannel
import java.net.InetAddress
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
        val host = hostname ?: "localhost"
        val socketChannel =
            firstReachable(config.nameResolution.candidatesFor(host)) { candidate ->
                val attempt = openSocketChannel()
                // Assigned before the connect so close() can reach it if the attempt fails.
                this@NioClientSocket.socket = attempt
                try {
                    attempt.aConfigureBlocking(blocking)
                    val address = InetSocketAddress(InetAddress.getByName(candidate.ip), port)
                    if (!attempt.connect(address, selector, timeout)) {
                        throw SocketIOException("Failed to connect client $port $attempt")
                    }
                    attempt
                } catch (e: Throwable) {
                    runCatching { attempt.close() }
                    throw e
                }
            }
        try {
            applySocketOptions(config.io)
            config.tls?.let { initTls(hostname, port, it, timeout) }
        } catch (e: Throwable) {
            runCatching { socketChannel.close() }
            throw e
        }
    }
}
