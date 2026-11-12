package com.ditchoom.socket.nio

import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.ServerSocket
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.nio.util.SelectorLoopGroup
import com.ditchoom.socket.nio.util.aClose
import com.ditchoom.socket.offTheAndroidMainThread
import com.ditchoom.socket.wrapJvmException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.nio.channels.ClosedChannelException
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * A listener on plain NIO whose accepted connections are non-blocking: accept and every read and write wait for
 * readiness on the shared selector loops, so no thread is parked in `accept()` or in a read. It is what
 * `ServerSocket.allocate` returns where NIO2 is missing (Android below API 26) unless `useNioBlocking` is set.
 * Binds through `socket().bind(…)` because `ServerSocketChannel.bind(…)` needs API 24.
 */
class SelectorServerSocket(
    private val config: TransportConfig = TransportConfig(),
) : ServerSocket {
    private sealed interface Listener {
        data object Unbound : Listener

        class Bound(
            val channel: ServerSocketChannel,
        ) : Listener
    }

    /** What one `accept()` found. */
    private sealed interface Accepted {
        data object NoneQueued : Accepted

        class Connection(
            val channel: SocketChannel,
        ) : Accepted
    }

    private val listener = AtomicReference<Listener>(Listener.Unbound)
    private val loop by lazy { SelectorLoopGroup.next() }

    override fun port() =
        when (val l = listener.get()) {
            Listener.Unbound -> -1
            is Listener.Bound ->
                l.channel
                    .socket()
                    .localPort
                    .takeIf { it > 0 } ?: -1
        }

    override fun isListening() =
        when (val l = listener.get()) {
            Listener.Unbound -> false
            is Listener.Bound -> l.channel.isOpen
        }

    override suspend fun bind(
        port: Int,
        host: String?,
        backlog: Int,
    ): Flow<ClientSocket> {
        // A named port binds host (default localhost), an unnamed one an ephemeral port on every interface.
        val socketAddress = if (port > 0) InetSocketAddress(host ?: "localhost", port) else null
        val server =
            withContext(Dispatchers.IO) {
                val channel = ServerSocketChannel.open()
                try {
                    channel.socket().bind(socketAddress, backlog)
                    channel.configureBlocking(false)
                    channel
                } catch (e: Throwable) {
                    runCatching { channel.close() }
                    throw wrapJvmException(e, host, port)
                }
            }
        listener.set(Listener.Bound(server))
        return flow {
            while (isListening()) {
                val connection =
                    try {
                        acceptOrWait(server)
                    } catch (e: ClosedChannelException) {
                        break
                    } catch (e: SocketClosedException) {
                        // A close() ends the flow; a collector cancelled from outside ends it as it always has.
                        currentCoroutineContext().ensureActive()
                        break
                    } catch (e: CancellationException) {
                        // A cancelled collector closes the listener; its key is dropped too, or the port stays open.
                        withContext(NonCancellable) {
                            offTheAndroidMainThread { server.closeQuietly() }
                            loop.release(server)
                        }
                        throw e
                    }
                when (connection) {
                    Accepted.NoneQueued -> continue
                    is Accepted.Connection -> emit(handOver(connection.channel))
                }
            }
        }
    }

    private suspend fun acceptOrWait(server: ServerSocketChannel): Accepted {
        val accepted =
            offTheAndroidMainThread(onAbandoned = { if (it is Accepted.Connection) it.channel.closeQuietly() }) {
                server.accept()?.let { Accepted.Connection(it) } ?: Accepted.NoneQueued
            }
        when (accepted) {
            Accepted.NoneQueued -> loop.awaitReady(server, SelectionKey.OP_ACCEPT, Duration.INFINITE)
            is Accepted.Connection -> Unit
        }
        return accepted
    }

    /** Makes the accepted channel non-blocking and wraps it; a connection that cannot reach the collector is closed. */
    private suspend fun handOver(channel: SocketChannel): ClientSocket =
        try {
            currentCoroutineContext().ensureActive()
            offTheAndroidMainThread(onAbandoned = { channel.closeQuietly() }) {
                channel.configureBlocking(false)
                SelectorServerToClientSocket(channel, config)
            }
        } catch (t: Throwable) {
            channel.closeQuietly()
            throw t
        }

    override suspend fun close() {
        when (val l = listener.get()) {
            Listener.Unbound -> Unit
            is Listener.Bound -> {
                l.channel.aClose()
                loop.release(l.channel)
            }
        }
    }

    private fun SocketChannel.closeQuietly() {
        try {
            close()
        } catch (_: Throwable) {
        }
    }

    private fun ServerSocketChannel.closeQuietly() {
        try {
            close()
        } catch (_: Throwable) {
        }
    }
}

/** The server side of a connection [SelectorServerSocket] accepted: a non-blocking channel on the shared loops. */
internal class SelectorServerToClientSocket(
    channel: SocketChannel,
    config: TransportConfig,
) : BaseClientSocket(blocking = false, config) {
    init {
        this.socket = channel
    }
}
