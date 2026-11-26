package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.ResolvedAddress
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.quic.QuicCloseChoice
import com.ditchoom.socket.quic.QuicCloseOutcome
import com.ditchoom.socket.quic.QuicConnect
import com.ditchoom.socket.quic.QuicConnection
import com.ditchoom.socket.quic.QuicConnectionEnd
import com.ditchoom.socket.quic.QuicConnectionLife
import com.ditchoom.socket.quic.QuicEndpoint
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicListener
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicPeer
import com.ditchoom.socket.quic.QuicServe
import com.ditchoom.socket.quic.QuicStream
import com.ditchoom.socket.quic.StreamWrite
import com.ditchoom.socket.quic.connectQuicOnCore
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.core.testing.platformTlsConfig
import com.ditchoom.socket.quic.getOrElse
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.readEach
import com.ditchoom.socket.quic.serveQuicOnCore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What code running on a connection is told when the connection ends, through the public entry points on every
 * platform. Nothing is cancelled: a block waiting on the connection ([QuicConnection.awaitEnd], or a handler of its
 * streams) returns with the end, whether the peer closed or the connection idled out, and a block that closed its own
 * connection runs on to its own return. A reply the block never read does not hold it past the peer's close.
 */
class CoreConnectionEndContractTests {
    @Test
    fun aServerHandlerWaitingOnItsStreamsReturnsWithTheClientsClose() =
        coreTest("CoreConnectionEndContractTests.aServerHandlerWaitingOnItsStreamsReturnsWithTheClientsClose") {
            val offered = CompletableDeferred<Unit>()
            val handled = CompletableDeferred<QuicConnectionEnd>()
            serving(options) { listener ->
                val serving =
                    launch {
                        listener.handleConnections {
                            offered.complete(Unit)
                            handled.complete(it.handleStreams { })
                        }
                    }
                val client = connected(connect(listener.port, options))
                // A close that reached the server with the client's last handshake flight would end the connection in the
                // serve that completes its handshake, which is never offered: the client closes once its handler runs.
                withTimeout(CLOSE_BOUND) { offered.await() }
                client.close(QuicCloseChoice.Immediate(QuicError.ApplicationError(CLIENT_CODE)))
                assertEquals<QuicConnectionEnd>(
                    QuicConnectionEnd.ByPeer(QuicError.ApplicationError(CLIENT_CODE)),
                    withTimeout(CLOSE_BOUND) { handled.await() },
                )
                serving.cancel()
            }
        }

    @Test
    fun aClientBlockWaitingOnTheConnectionReturnsWithTheServersClose() =
        coreTest("CoreConnectionEndContractTests.aClientBlockWaitingOnTheConnectionReturnsWithTheServersClose") {
            serving(options) { listener ->
                // The handler returns at once: the server closes the connection gracefully.
                val serving = launch { listener.handleConnections { } }
                val connect = connectBlock(listener.port, options) { it.awaitEnd() }
                val end = assertIs<QuicConnect.Connected<QuicConnectionEnd>>(connect, "$connect").value
                assertEquals<QuicConnectionEnd>(QuicConnectionEnd.ByPeer(QuicError.NoError), end)
                serving.cancel()
            }
        }

    @Test
    fun aBlockThatClosesItsOwnConnectionRunsToItsOwnReturn() =
        coreTest("CoreConnectionEndContractTests.aBlockThatClosesItsOwnConnectionRunsToItsOwnReturn") {
            serving(options) { listener ->
                val serving = launch { listener.handleConnections { it.awaitEnd() } }
                val connect =
                    connectBlock(listener.port, options) { connection ->
                        val outcome = connection.close(QuicCloseChoice.Immediate(QuicError.ApplicationError(CLIENT_CODE)))
                        // Time for anything that saw the end to stop this block, were anything to.
                        delay(GRACE)
                        outcome to connection.life()
                    }
                val (outcome, life) = assertIs<QuicConnect.Connected<Pair<QuicCloseOutcome, QuicConnectionLife>>>(connect, "$connect").value
                assertEquals<QuicCloseOutcome>(QuicCloseOutcome.Immediate, outcome)
                assertEquals<QuicConnectionEnd>(
                    QuicConnectionEnd.ByLocal(QuicError.ApplicationError(CLIENT_CODE)),
                    assertIs<QuicConnectionLife.Ended>(life).end,
                )
                serving.cancel()
            }
        }

    @Test
    fun bothEndsReturnWithTheIdleTimeout() =
        coreTest("CoreConnectionEndContractTests.bothEndsReturnWithTheIdleTimeout") {
            val idle = options.copy(idleTimeout = 1.seconds)
            val handled = CompletableDeferred<QuicConnectionEnd>()
            serving(idle) { listener ->
                val serving = launch { listener.handleConnections { handled.complete(it.handleStreams { }) } }
                val connect = connectBlock(listener.port, idle) { withTimeout(IDLE_BOUND) { it.awaitEnd() } }
                assertEquals<QuicConnectionEnd>(
                    QuicConnectionEnd.IdleTimeout,
                    assertIs<QuicConnect.Connected<QuicConnectionEnd>>(connect).value,
                )
                assertEquals<QuicConnectionEnd>(QuicConnectionEnd.IdleTimeout, withTimeout(IDLE_BOUND) { handled.await() })
                serving.cancel()
            }
        }

    @Test
    fun aBlockThatNeverReadsTheReplyReturnsWithTheServersClose() =
        coreTest("CoreConnectionEndContractTests.aBlockThatNeverReadsTheReplyReturnsWithTheServersClose") {
            // The client's idle timeout is far past the bound: only the server's close can end the wait in time.
            val patient = options.copy(idleTimeout = 60.seconds)
            serving(options) { listener ->
                val serving =
                    launch {
                        listener.handleConnections { connection ->
                            val replied = CompletableDeferred<Unit>()
                            launch {
                                connection.handleStreams { incoming ->
                                    val stream = incoming as QuicStream
                                    stream.receive.readEach { }
                                    stream.send.writeAndFinish(text(REPLY))
                                    replied.complete(Unit)
                                }
                            }
                            replied.await()
                            connection.close(QuicCloseChoice.Graceful())
                        }
                    }
                val connect =
                    connectBlock(listener.port, patient) { connection ->
                        val stream = connection.openStream().getOrElse { throw AssertionError("no stream: $it") }
                        assertIs<StreamWrite.Written>(stream.send.writeAndFinish(text(REQUEST)))
                        withTimeout(CLOSE_BOUND) { connection.awaitEnd() }
                    }
                assertEquals<QuicConnectionEnd>(
                    QuicConnectionEnd.ByPeer(QuicError.NoError),
                    assertIs<QuicConnect.Connected<QuicConnectionEnd>>(connect, "$connect").value,
                )
                serving.cancel()
            }
        }

    private suspend fun serving(
        options: QuicOptions,
        block: suspend (QuicListener) -> Unit,
    ) {
        assertIs<QuicServe.Serving<Unit>>(serveQuicOnCore(0, LOOPBACK, platformTlsConfig(), options, block))
    }

    private fun peer(port: Int) = QuicPeer.Candidates(listOf(QuicEndpoint(ResolvedAddress(LOOPBACK, IpFamily.V4), port)), "localhost")

    private suspend fun connect(
        port: Int,
        options: QuicOptions,
    ): QuicConnect<QuicConnection> = connectQuicOnCore(peer(port), options, TransportConfig(), WAIT)

    private suspend fun <R> connectBlock(
        port: Int,
        options: QuicOptions,
        block: suspend (QuicConnection) -> R,
    ): QuicConnect<R> = connectQuicOnCore(peer(port), options, TransportConfig(), WAIT, block)

    private fun connected(connect: QuicConnect<QuicConnection>): QuicConnection =
        assertIs<QuicConnect.Connected<QuicConnection>>(connect, "$connect").value

    private fun text(content: String): ReadBuffer =
        BufferFactory.network().allocate(content.length).apply {
            writeString(content)
            resetForRead()
        }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val CLIENT_CODE = 0x7L
        const val REQUEST = "reply-then-close;"
        val REPLY = REQUEST.repeat(64)
        val WAIT = 10.seconds

        /** How long a peer's CONNECTION_CLOSE may take to reach code on loopback: far below any idle timeout here. */
        val CLOSE_BOUND: Duration = 5.seconds

        /** The idle timer fires at the timeout, give or take a PTO: an end still not told well past it never was. */
        val IDLE_BOUND: Duration = 10.seconds
        val GRACE = 250.milliseconds
        val options = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 10.seconds)
    }
}
