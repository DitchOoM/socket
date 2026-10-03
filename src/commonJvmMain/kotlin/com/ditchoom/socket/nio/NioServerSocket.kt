package com.ditchoom.socket.nio

import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.ServerSocket
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.nio.util.aClose
import com.ditchoom.socket.wrapJvmException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

/**
 * A server socket on plain NIO (`ServerSocketChannel`, blocking accept on [Dispatchers.IO]), for the
 * platforms without NIO2: Android below API 26, where `AsynchronousServerSocketChannel` does not exist and
 * [com.ditchoom.socket.nio2.AsyncServerSocket] cannot bind. `ServerSocket.allocate` picks it there.
 *
 * Binds through `socket().bind(…)` rather than `ServerSocketChannel.bind(…)`, which Android added only in
 * API 24 and this library's minSdk is 23.
 */
class NioServerSocket(
    private val config: TransportConfig = TransportConfig(),
) : ServerSocket {
    @Volatile
    private var server: ServerSocketChannel? = null

    override fun port() = server?.socket()?.localPort?.takeIf { it > 0 } ?: -1

    override fun isListening() = server?.isOpen ?: false

    override suspend fun bind(
        port: Int,
        host: String?,
        backlog: Int,
    ): Flow<ClientSocket> {
        // The same address choice AsyncServerSocket makes: a named port binds host (default localhost),
        // an unnamed one an ephemeral port on every interface.
        val socketAddress = if (port > 0) InetSocketAddress(host ?: "localhost", port) else null
        val server =
            withContext(Dispatchers.IO) {
                val channel = ServerSocketChannel.open()
                try {
                    channel.socket().bind(socketAddress, backlog)
                    channel
                } catch (e: Throwable) {
                    runCatching { channel.close() }
                    throw wrapJvmException(e, host, port)
                }
            }
        this.server = server
        return flow {
            while (isListening()) {
                val client: SocketChannel =
                    try {
                        // Interruptible so a cancelled collector unblocks the accept; that interrupt
                        // closes the channel (ClosedByInterruptException), which is caught below.
                        runInterruptible(Dispatchers.IO) { server.accept() }
                    } catch (e: ClosedChannelException) {
                        // A close() during or between accepts ends the flow, as AsyncServerSocket's
                        // does; a cancellation still propagates as one.
                        currentCoroutineContext().ensureActive()
                        break
                    }
                emit(NioServerToClientSocket(client, config))
            }
        }
    }

    override suspend fun close() {
        server?.aClose()
    }
}

/** The server side of a connection [NioServerSocket] accepted: blocking NIO, like [NioClientSocket]'s default. */
class NioServerToClientSocket(
    channel: SocketChannel,
    config: TransportConfig = TransportConfig(),
) : BaseClientSocket(blocking = true, config) {
    init {
        this.socket = channel
    }
}
