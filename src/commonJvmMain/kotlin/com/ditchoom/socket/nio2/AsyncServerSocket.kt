package com.ditchoom.socket.nio2

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.ServerSocket
import com.ditchoom.socket.nio.util.aClose
import com.ditchoom.socket.nio2.util.aAccept
import com.ditchoom.socket.nio2.util.aBind
import com.ditchoom.socket.nio2.util.openAsyncServerSocketChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.AsynchronousServerSocketChannel

class AsyncServerSocket(
    private val allocationZone: AllocationZone
) : ServerSocket {

    private var server: AsynchronousServerSocketChannel? = null

    override fun port() = (server?.localAddress as? InetSocketAddress)?.port ?: -1

    override fun isListening() = try {
        server?.isOpen ?: false
    } catch (e: Throwable) {
        false
    }

    override suspend fun bind(
        port: Int,
        host: String?,
        backlog: Int,
    ): Flow<ClientSocket> {
        val socketAddress = if (port > 0) {
            InetSocketAddress(host ?: "localhost", port)
        } else {
            null
        }

        val serverLocal = openAsyncServerSocketChannel()
        val server = serverLocal.aBind(socketAddress, backlog)
        this@AsyncServerSocket.server = server
        return flow {
            while (isListening()) {
                val client = try {
                    server.aAccept()
                } catch (e: AsynchronousCloseException) {
                    break
                }
                val serverToClient = AsyncServerToClientSocket(allocationZone, client)
                emit(serverToClient)
            }
        }
    }

    override suspend fun close() {
        server?.aClose()
    }
}
