package com.ditchoom.socket.nio2

import com.ditchoom.socket.ClientToServerSocket
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.candidatesFor
import com.ditchoom.socket.connectRace
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
            connectRace(
                candidates = config.nameResolution.candidatesFor(host),
                pacing = config.connectPacing,
                close = { runCatching { it.close() } },
            ) { candidate ->
                val attempt = asyncSocket()
                try {
                    attempt.aConnect(InetSocketAddress(InetAddress.getByName(candidate.ip), port), timeout)
                    attempt
                } catch (e: Throwable) {
                    runCatching { attempt.close() }
                    throw e
                }
            }
        this.socket = asyncSocket
        try {
            applySocketOptions(config.io)
            config.tls?.let { initTls(hostname, port, it, timeout) }
        } catch (e: Throwable) {
            runCatching { asyncSocket.close() }
            throw e
        }
    }
}
