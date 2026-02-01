package com.ditchoom.socket.nio2

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.socket.ClientToServerSocket
import com.ditchoom.socket.TlsOptions
import com.ditchoom.socket.nio.util.buildInetAddress
import com.ditchoom.socket.nio2.util.aConnect
import com.ditchoom.socket.nio2.util.asyncSocket
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

class AsyncClientSocket(
    allocationZone: AllocationZone,
) : AsyncBaseClientSocket(allocationZone),
    ClientToServerSocket {
    override suspend fun open(
        port: Int,
        timeout: Duration,
        hostname: String?,
        tlsOptions: TlsOptions,
    ) = withTimeout(timeout) {
        val asyncSocket = asyncSocket()
        // Assign socket immediately so close() can clean it up if connect fails
        this@AsyncClientSocket.socket = asyncSocket
        try {
            asyncSocket.aConnect(buildInetAddress(port, hostname), timeout)
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
