package com.ditchoom.socket.nio2

import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.ServerSocket
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.nio.util.aClose
import com.ditchoom.socket.nio2.util.aAccept
import com.ditchoom.socket.nio2.util.aBind
import com.ditchoom.socket.nio2.util.openAsyncServerSocketChannel
import com.ditchoom.socket.offTheAndroidMainThread
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.ClosedChannelException
import java.util.concurrent.atomic.AtomicInteger

class AsyncServerSocket(
    private val config: TransportConfig = TransportConfig(),
) : ServerSocket {
    private var server: AsynchronousServerSocketChannel? = null
    private val acceptsStarted = AtomicInteger()
    private val acceptsCompleted = AtomicInteger()

    override fun toString() =
        "AsyncServerSocket(listening=${isListening()}, port=${port()}, acceptsStarted=$acceptsStarted, acceptsCompleted=$acceptsCompleted)"

    override fun port() = (server?.localAddress as? InetSocketAddress)?.port ?: -1

    override fun isListening() =
        try {
            server?.isOpen ?: false
        } catch (e: Throwable) {
            false
        }

    override suspend fun bind(
        port: Int,
        host: String?,
        backlog: Int,
    ): Flow<ClientSocket> {
        val socketAddress =
            if (port > 0) {
                InetSocketAddress(host ?: "localhost", port)
            } else {
                null
            }

        // Off the Android main thread, like every socket call here: see offTheAndroidMainThread.
        val server = offTheAndroidMainThread(onAbandoned = { it.close() }) { openAsyncServerSocketChannel().aBind(socketAddress, backlog) }
        this@AsyncServerSocket.server = server
        return flow {
            while (isListening()) {
                acceptsStarted.incrementAndGet()
                val client =
                    try {
                        offTheAndroidMainThread(
                            onAbandoned = { it.close() },
                        ) { server.aAccept() }.also { acceptsCompleted.incrementAndGet() }
                    } catch (e: ClosedChannelException) {
                        // Supertype on purpose, covering both shutdown orderings: a close that
                        // lands *during* a pending accept surfaces as AsynchronousCloseException
                        // (a ClosedChannelException subclass), while a close that lands *between*
                        // accepts surfaces as a plain ClosedChannelException. Catching only the
                        // former made a normal shutdown throw on the second ordering.
                        //
                        // This also covers aAccept() throwing synchronously rather than routing
                        // through AcceptCompletionHandler.failed, so the loop terminates cleanly
                        // no matter which path the JDK takes.
                        break
                    }
                val serverToClient = AsyncServerToClientSocket(client, config)
                emit(serverToClient)
            }
        }
    }

    override suspend fun close() {
        server?.aClose()
    }
}
