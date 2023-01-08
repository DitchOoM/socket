package com.ditchoom.socket.nio2

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.ServerSocket
import com.ditchoom.socket.SocketOptions
import com.ditchoom.socket.nio.util.aClose
import com.ditchoom.socket.nio.util.asyncSetOptions
import com.ditchoom.socket.nio2.util.aAccept
import com.ditchoom.socket.nio2.util.aBind
import com.ditchoom.socket.nio2.util.openAsyncServerSocketChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.AsynchronousServerSocketChannel

class AsyncServerSocket(private val bufferFactory: () -> PlatformBuffer) : ServerSocket {

    private var server: AsynchronousServerSocketChannel? = null
    private lateinit var coroutineScope: CoroutineScope

    override fun setScope(scope: CoroutineScope) {
        this.coroutineScope = scope
    }

    override fun port() = (server?.localAddress as? InetSocketAddress)?.port ?: -1

    override fun isOpen() = try {
        server?.isOpen ?: false
    } catch (e: Throwable) {
        false
    }

    override suspend fun start(
        port: Int,
        host: String?,
        socketOptions: SocketOptions?,
        backlog: Int,
        acceptedClient: suspend (ClientSocket) -> Unit
    ): SocketOptions {
        val socketAddress = if (port > 0) {
            InetSocketAddress(host ?: "localhost", port)
        } else {
            null
        }

        val serverLocal = openAsyncServerSocketChannel()
        val options = serverLocal.asyncSetOptions(socketOptions)
        val server = serverLocal.aBind(socketAddress, backlog)
        this.server = server

        coroutineScope.launch {
            while (isActive && isOpen()) {
                val client = try {
                    server.aAccept()
                } catch (e: AsynchronousCloseException) {
                    break
                }
                val serverToClient = AsyncServerToClientSocket(bufferFactory, client)
                launch { acceptedClient(serverToClient) }
            }
        }
        return options
    }

    override suspend fun close() {
        server?.aClose()
    }
}
