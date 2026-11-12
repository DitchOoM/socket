package com.ditchoom.socket.nio

import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.ServerSocket
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.nio.util.SelectorLoop
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
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.nio.channels.ClosedChannelException
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * A server socket on plain NIO, for runtimes without NIO2 (Android below API 26), where `ServerSocket.allocate`
 * returns it, and for anyone who constructs it. Binds through `socket().bind(…)` because `ServerSocketChannel.bind(…)`
 * needs API 24.
 *
 * With [nonBlocking] the listener and every accepted connection are non-blocking channels whose readiness
 * waits run on the shared [SelectorLoop]s: no thread is parked in `accept()` or in a read. Without it, accept
 * blocks a [Dispatchers.IO] thread and the accepted connections are blocking channels.
 */
class NioServerSocket(
    private val config: TransportConfig,
    private val nonBlocking: Boolean,
) : ServerSocket {
    // A separate constructor, not a default argument, so the signature released callers link against stays.
    constructor(config: TransportConfig = TransportConfig()) : this(config, nonBlocking = false)

    private sealed interface Listener {
        data object Unbound : Listener

        class Bound(
            val channel: ServerSocketChannel,
        ) : Listener
    }

    @Volatile
    private var listener: Listener = Listener.Unbound

    /** The loop the listener's OP_ACCEPT waits run on. */
    private val loop by lazy { SelectorLoopGroup.next() }

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
                    if (nonBlocking) channel.configureBlocking(false)
                    channel
                } catch (e: Throwable) {
                    runCatching { channel.close() }
                    throw wrapJvmException(e, host, port)
                }
            }
        listener = Listener.Bound(server)
        if (nonBlocking) return acceptOnLoop(server)
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

    /**
     * Accepts without parking a thread: try `accept()`, and when no connection is queued wait for OP_ACCEPT on a
     * loop. Every socket system call here (`accept`, `configureBlocking`, `close`) goes through
     * [offTheAndroidMainThread], because the collector may be the Android main thread, and a connection that
     * is accepted but never reaches the collector is closed.
     */
    private fun acceptOnLoop(server: ServerSocketChannel): Flow<ClientSocket> =
        flow {
            while (isListening()) {
                val accepted: SocketChannel? =
                    try {
                        offTheAndroidMainThread(onAbandoned = { it?.closeQuietly() }) { server.accept() }
                    } catch (e: ClosedChannelException) {
                        break
                    }
                if (accepted == null) {
                    try {
                        loop.awaitReady(server, SelectionKey.OP_ACCEPT, Duration.INFINITE)
                    } catch (e: SocketClosedException) {
                        // A close() ends the flow; a collector cancelled from outside ends it as it always has.
                        currentCoroutineContext().ensureActive()
                        break
                    } catch (e: CancellationException) {
                        // Like the blocking accept, a cancelled collector closes the listener.
                        withContext(NonCancellable) {
                            offTheAndroidMainThread { server.closeQuietly() }
                            // A registered channel's descriptor stays open until the loop drops its key.
                            loop.release(server)
                        }
                        throw e
                    }
                    continue
                }
                val peer =
                    try {
                        offTheAndroidMainThread(onAbandoned = { accepted.closeQuietly() }) {
                            accepted.configureBlocking(false)
                            NioServerToClientSocket(accepted, config, blocking = false)
                        }
                    } catch (t: Throwable) {
                        accepted.closeQuietly()
                        throw t
                    }
                try {
                    currentCoroutineContext().ensureActive()
                } catch (t: Throwable) {
                    // Cancelled between accepting and handing the connection over: nobody owns it.
                    accepted.closeQuietly()
                    throw t
                }
                emit(peer)
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

    override suspend fun close() {
        when (val l = listener) {
            Listener.Unbound -> Unit
            is Listener.Bound -> {
                l.channel.aClose()
                if (nonBlocking) loop.release(l.channel)
            }
        }
    }
}

/** The server side of a connection [NioServerSocket] accepted: blocking NIO, like [NioClientSocket]'s default. */
class NioServerToClientSocket(
    channel: SocketChannel,
    config: TransportConfig,
    blocking: Boolean,
) : BaseClientSocket(blocking, config) {
    constructor(channel: SocketChannel, config: TransportConfig = TransportConfig()) : this(channel, config, blocking = true)

    init {
        this.socket = channel
    }
}
