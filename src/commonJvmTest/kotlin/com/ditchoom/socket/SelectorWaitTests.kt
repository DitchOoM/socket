package com.ditchoom.socket

import com.ditchoom.socket.nio.util.suspendUntilReady
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The selector wait under every non-blocking JVM read, write and connect ends typed: at its deadline
 * with [SocketTimeoutException], when its selector closes with [SocketClosedException] — never with a
 * bare `CancellationException`, and never by spinning on after the socket is gone.
 */
class SelectorWaitTests {
    /**
     * A key other than the waiter's is ready the whole time: a second channel on the same selector,
     * as a connect race's candidates or a concurrent read and write share one. The waiter's own key
     * never becomes ready, so its wait must end at its deadline as a timeout.
     */
    @Test
    fun aWaitWhileAnotherKeyIsReadyEndsAtItsDeadlineAsATimeout() =
        withConnectedPair { quiet, busy ->
            Selector.open().use { selector ->
                busy.register(selector, SelectionKey.OP_WRITE)
                val ended = runBlocking { runCatching { quiet.suspendUntilReady(selector, SelectionKey.OP_READ, WAIT) } }
                assertIs<SocketTimeoutException>(ended.exceptionOrNull(), "the wait ended as: $ended")
            }
        }

    /** A wait with no deadline ends, typed, when its selector closes — the socket closing under it. */
    @Test
    fun anUnboundedWaitEndsWhenItsSelectorCloses() =
        withConnectedPair { quiet, _ ->
            val selector = Selector.open()
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                val wait = scope.async { quiet.suspendUntilReady(selector, SelectionKey.OP_READ, Duration.INFINITE) }
                Thread.sleep(WAIT.inWholeMilliseconds)
                selector.close()
                val ended = runBlocking { withTimeoutOrNull(BOUND) { runCatching { wait.await() } } }
                assertNotNull(ended, "the wait was still running $BOUND after its selector closed")
                assertIs<SocketClosedException>(ended.exceptionOrNull(), "the wait ended as: $ended")
            } finally {
                scope.cancel()
            }
        }

    /** Two connected non-blocking loopback channels: [quiet] never receives anything, [busy] can always write. */
    private fun withConnectedPair(block: (quiet: SocketChannel, busy: SocketChannel) -> Unit) {
        ServerSocketChannel.open().use { server ->
            server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            SocketChannel.open(server.localAddress).use { quiet ->
                server.accept().use {
                    SocketChannel.open(server.localAddress).use { busy ->
                        server.accept().use {
                            quiet.configureBlocking(false)
                            busy.configureBlocking(false)
                            block(quiet, busy)
                        }
                    }
                }
            }
        }
    }

    private companion object {
        val WAIT = 300.milliseconds
        val BOUND = 5.seconds
    }
}
