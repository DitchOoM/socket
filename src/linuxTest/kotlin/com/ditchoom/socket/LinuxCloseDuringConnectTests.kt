package com.ditchoom.socket

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import platform.posix.AF_INET
import platform.posix.INADDR_LOOPBACK
import platform.posix.SOCK_STREAM
import platform.posix.bind
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.htonl
import platform.posix.htons
import platform.posix.listen
import platform.posix.memset
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A `close()` ends a connect that is still waiting for its peer, at once, as a closed socket — rather than
 * leaving the connect to run out its own timeout with nobody left to receive the result.
 */
@OptIn(ExperimentalForeignApi::class)
class LinuxCloseDuringConnectTests {
    @Test
    fun aCloseDuringAConnectThatNeverAnswersEndsItAtOnceAsClosed() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            val listener = FullBacklogListener.open()
            val client = ClientSocket.allocate(TransportConfig(connectTimeout = CONNECT_TIMEOUT))
            val connecting = async(Dispatchers.Default) { runCatching { client.open(listener.port, "127.0.0.1") } }
            try {
                delay(PREMISE_WAIT)
                assertTrue(
                    connecting.isActive,
                    "premise: a connect to a listener whose accept queue is full gets no SYN-ACK and must still be " +
                        "waiting after $PREMISE_WAIT, but it ended: ${if (connecting.isCompleted) connecting.await() else "?"}",
                )
                val closedAt = TimeSource.Monotonic.markNow()
                client.close()
                val outcome = withTimeoutOrNull(CLOSE_BOUND) { connecting.await() }
                assertNotNull(
                    outcome,
                    "close() did not end the connect within $CLOSE_BOUND: the connect is waiting out its own " +
                        "$CONNECT_TIMEOUT timeout for a socket its owner has already closed",
                )
                val took = closedAt.elapsedNow()
                assertIs<SocketClosedException>(
                    outcome.exceptionOrNull(),
                    "a connect ended by close() reports the socket closed, after $took: $outcome",
                )
            } finally {
                connecting.cancel()
                client.close()
                listener.close()
            }
        }

    /**
     * A loopback listener that answers no further SYN: `listen(fd, 0)` holds one established connection in its
     * accept queue, the filler below takes that place, and Linux drops every later SYN while the queue is full
     * (`tcp_conn_request`, `LISTENOVERFLOWS`), so a connect to it waits in SYN-SENT. Plain POSIX, so nothing
     * here submits to the poller under test.
     */
    private class FullBacklogListener(
        private val listener: Int,
        private val filler: Int,
        val port: Int,
    ) {
        fun close() {
            close(filler)
            close(listener)
        }

        companion object {
            fun open(): FullBacklogListener {
                val listener = socket(AF_INET, SOCK_STREAM, 0)
                check(listener >= 0) { "socket failed: errno $errno" }
                memScoped {
                    val addr = alloc<sockaddr_in>()
                    memset(addr.ptr, 0, sizeOf<sockaddr_in>().convert())
                    addr.sin_family = AF_INET.convert()
                    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK.convert())
                    addr.sin_port = htons(0.convert())
                    check(bind(listener, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) == 0) {
                        "bind failed: errno $errno"
                    }
                    check(listen(listener, 0) == 0) { "listen failed: errno $errno" }
                    val port = getLocalPort(listener)
                    addr.sin_port = htons(port.toUShort())
                    val filler = socket(AF_INET, SOCK_STREAM, 0)
                    check(connect(filler, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) == 0) {
                        "the filler connect failed: errno $errno"
                    }
                    return FullBacklogListener(listener, filler, port)
                }
            }
        }
    }

    private companion object {
        val CONNECT_TIMEOUT = 30.seconds
        val PREMISE_WAIT = 500.milliseconds
        val CLOSE_BOUND = 5.seconds
    }
}
