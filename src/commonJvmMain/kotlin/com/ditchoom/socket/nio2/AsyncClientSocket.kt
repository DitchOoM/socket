package com.ditchoom.socket.nio2

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.ClientToServerSocket
import com.ditchoom.socket.SocketUnknownHostException
import com.ditchoom.socket.nio.util.asInetAddress
import com.ditchoom.socket.nio.util.buildInetAddress
import com.ditchoom.socket.nio2.util.aConnect
import com.ditchoom.socket.nio2.util.asyncSocket
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration

class AsyncClientSocket(bufferFactory: () -> PlatformBuffer) :
    AsyncBaseClientSocket(bufferFactory),
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
