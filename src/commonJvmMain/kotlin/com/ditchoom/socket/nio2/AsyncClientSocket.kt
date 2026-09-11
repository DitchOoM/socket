package com.ditchoom.socket.nio2

import com.ditchoom.socket.ClientToServerSocket
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.candidatesFor
import com.ditchoom.socket.firstReachable
import com.ditchoom.socket.nio2.util.aConnect
import com.ditchoom.socket.nio2.util.asyncSocket
import java.net.InetAddress
import java.net.InetSocketAddress

class AsyncClientSocket(
    config: TransportConfig = TransportConfig(),
) : AsyncBaseClientSocket(config),
    ClientToServerSocket {
    override suspend fun open(
        port: Int,
        hostname: String?,
    ) {
        val timeout = config.connectTimeout
        val host = hostname ?: "localhost"
        val asyncSocket =
            firstReachable(config.nameResolution.candidatesFor(host)) { candidate ->
                val attempt = asyncSocket()
                // Assigned before the connect so close() can reach it if the attempt fails.
                this@AsyncClientSocket.socket = attempt
                try {
                    attempt.aConnect(InetSocketAddress(InetAddress.getByName(candidate.ip), port), timeout)
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
            runCatching { asyncSocket.close() }
            throw e
        }
    }
}
