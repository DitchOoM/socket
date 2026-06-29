package com.ditchoom.socket.nio2

import com.ditchoom.socket.ClientToServerSocket
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.nio.util.buildInetAddress
import com.ditchoom.socket.nio2.util.aConnect
import com.ditchoom.socket.nio2.util.asyncSocket
import kotlinx.coroutines.withTimeout

class AsyncClientSocket :
    AsyncBaseClientSocket(),
    ClientToServerSocket {
    override suspend fun open(
        port: Int,
        hostname: String?,
        config: TransportConfig,
    ) {
        this.config = config
        val timeout = config.connectTimeout
        withTimeout(timeout) {
            val asyncSocket = asyncSocket()
            // Assign socket immediately so close() can clean it up if connect fails
            this@AsyncClientSocket.socket = asyncSocket
            try {
                asyncSocket.aConnect(buildInetAddress(port, hostname), timeout)
                applySocketOptions(config.io)
                config.tls?.let { initTls(hostname, port, it, timeout) }
            } catch (e: Throwable) {
                // Ensure socket is closed on any failure during open
                try {
                    asyncSocket.close()
                } catch (_: Throwable) {
                    // Ignore close errors
                }
                throw e
            }
        }
    }
}
