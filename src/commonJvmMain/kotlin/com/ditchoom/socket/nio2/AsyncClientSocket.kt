package com.ditchoom.socket.nio2

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.socket.ClientToServerSocket
import com.ditchoom.socket.nio.util.buildInetAddress
import com.ditchoom.socket.nio2.util.aConnect
import com.ditchoom.socket.nio2.util.asyncSocket
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

class AsyncClientSocket(allocationZone: AllocationZone) :
    AsyncBaseClientSocket(allocationZone),
    ClientToServerSocket {

    override suspend fun open(
        port: Int,
        timeout: Duration,
        hostname: String?
    ) = withTimeout(timeout) {
        val asyncSocket = asyncSocket()
        this@AsyncClientSocket.socket = asyncSocket
        asyncSocket.aConnect(buildInetAddress(port, hostname), timeout)
    }
}
