package com.ditchoom.socket.nio2

import com.ditchoom.socket.ClientToServerSocket
import com.ditchoom.socket.SocketOptions
import com.ditchoom.socket.nio.util.asInetAddress
import com.ditchoom.socket.nio.util.asyncSetOptions
import com.ditchoom.socket.nio2.util.aConnect
import com.ditchoom.socket.nio2.util.asyncSocket
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration

class AsyncClientSocket : AsyncBaseClientSocket(), ClientToServerSocket {

    override suspend fun open(
        port: UShort,
        timeout: Duration,
        hostname: String?,
        socketOptions: SocketOptions?
    ): SocketOptions = withTimeout(timeout) {
        val socketAddress = if (hostname != null) {
            InetSocketAddress(hostname.asInetAddress(), port.toInt())
        } else {
            suspendCoroutine {
                try {
                    it.resume(InetSocketAddress(InetAddress.getLocalHost(), port.toInt()))
                } catch (e: Exception) {
                    it.resumeWithException(e)
                }
            }
        }
        val asyncSocket = asyncSocket()

        this@AsyncClientSocket.socket = asyncSocket
        val options = asyncSocket.asyncSetOptions(socketOptions)
        asyncSocket.aConnect(socketAddress, timeout)
        options
    }

}


