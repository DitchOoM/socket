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
 * A server socket on plain NIO (blocking accept on [Dispatchers.IO]) for runtimes without NIO2: Android
 * below API 26. Binds through `socket().bind(…)` because `ServerSocketChannel.bind(…)` needs API 24.
 */
class NioServerSocket(
    private val config: TransportConfig = TransportConfig(),
) : ServerSocket {
    private sealed interface Listener {
        data object Unbound : Listener

        class Bound(
            val channel: ServerSocketChannel,
        ) : Listener
    }

    @Volatile
    private var listener: Listener = Listener.Unbound

    override fun port() =
        when (val l = listener) {
            Listener.Unbound -> -1
            is Listener.Bound ->
                l.channel
                    .socket()
                    .localPort
                    .takeIf { it > 0 } ?: -1
        }

    override fun isListening() =
        when (val l = listener) {
            Listener.Unbound -> false
            is Listener.Bound -> l.channel.isOpen
        }

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
        listener = Listener.Bound(server)
        return flow {
            while (isListening()) {
                val client: SocketChannel =
                    try {
                        // A cancelled collector interrupts the accept, which closes the channel.
                        runInterruptible(Dispatchers.IO) { server.accept() }
                    } catch (e: ClosedChannelException) {
                        // A close() ends the flow; a cancellation still propagates.
                        currentCoroutineContext().ensureActive()
                        break
                    }
                emit(NioServerToClientSocket(client, config))
            }
        }
    }

    override suspend fun close() {
        when (val l = listener) {
            Listener.Unbound -> Unit
            is Listener.Bound -> l.channel.aClose()
        }
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
