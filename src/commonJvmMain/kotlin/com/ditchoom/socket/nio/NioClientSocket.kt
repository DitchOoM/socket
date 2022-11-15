package com.ditchoom.socket.nio

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.ClientToServerSocket
import com.ditchoom.socket.SocketException
import com.ditchoom.socket.SocketOptions
import com.ditchoom.socket.SocketUnknownHostException
import com.ditchoom.socket.nio.util.aConfigureBlocking
import com.ditchoom.socket.nio.util.asInetAddress
import com.ditchoom.socket.nio.util.asyncSetOptions
import com.ditchoom.socket.nio.util.connect
import com.ditchoom.socket.nio.util.openSocketChannel
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration

class NioClientSocket(
    bufferFactory: () -> PlatformBuffer,
    blocking: Boolean = true,
) : BaseClientSocket(bufferFactory, blocking), ClientToServerSocket {
    override suspend fun open(
        port: Int,
        timeout: Duration,
        hostname: String?,
        socketOptions: SocketOptions?
    ): SocketOptions {
        val socketAddress = if (hostname != null) {
            try {
                InetSocketAddress(hostname.asInetAddress(), port)
            } catch (e: Exception) {
                throw SocketUnknownHostException(hostname, cause = e)
            }
        } else {
            suspendCoroutine {
                try {
                    it.resume(InetSocketAddress(InetAddress.getLocalHost(), port))
                } catch (e: Exception) {
                    it.resumeWithException(e)
                }
            }
        }
        val socketChannel = openSocketChannel()
        socketChannel.aConfigureBlocking(blocking)
        this.socket = socketChannel
        if (!socketChannel.connect(socketAddress, selector, timeout)) {
            throw SocketException("Failed to connect client ${(socketAddress as? InetSocketAddress)?.port} $socketChannel")
        }
        return socketChannel.asyncSetOptions(socketOptions)
    }
}
