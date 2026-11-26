package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.ResolvedAddress
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.quic.QuicCloseChoice
import com.ditchoom.socket.quic.QuicConnect
import com.ditchoom.socket.quic.QuicConnection
import com.ditchoom.socket.quic.QuicEndpoint
import com.ditchoom.socket.quic.QuicIncomingStream
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicPeer
import com.ditchoom.socket.quic.QuicReceiveStream
import com.ditchoom.socket.quic.QuicServe
import com.ditchoom.socket.quic.QuicStream
import com.ditchoom.socket.quic.StreamWrite
import com.ditchoom.socket.quic.connectQuicOnCore
import com.ditchoom.socket.quic.core.demux.withCoreEchoServer
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.sched.LoopWork
import com.ditchoom.socket.quic.core.testing.platformTlsConfig
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.getOrElse
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.readEach
import com.ditchoom.socket.quic.serveQuicOnCore
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * The core's loops never run the application's code.
 *
 * A loop is one thread serving many connections; code that blocks it stalls every one of them. So a server's connection
 * handler and stream handler, a client's stream block, every continuation after a read, a write or an open, and a trace
 * sink must run off the core's loop threads. Every point where application code runs records its thread; any that is a
 * loop thread fails the test. The control proves the probe can see a loop thread at all: work handed to a loop runs on it.
 */
class CoreApplicationCodeTests {
    private class Probe {
        val onLoop = ConcurrentLinkedQueue<String>()
        val checked = AtomicInteger()
        val traced = AtomicInteger()

        fun note(where: String) {
            checked.incrementAndGet()
            val thread = Thread.currentThread().name
            if (isLoopThread(thread)) onLoop.add("$where ran on $thread")
        }
    }

    private fun tracing(probe: Probe) =
        QuicOptions(
            alpnProtocols = listOf("app-code"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
            trace =
                QuicTraceCapture(
                    TraceSink {
                        probe.traced.incrementAndGet()
                        probe.note("trace sink")
                    },
                ),
        )

    @Test
    fun handlersBlocksAndContinuationsRunOffTheLoopThreads() =
        runBlocking {
            skipOnMissingNativeLib(CoreApplicationCodeTests::class) {
                val probe = Probe()
                val options = tracing(probe)
                val served =
                    serveQuicOnCore(0, LOOPBACK, platformTlsConfig(), options) { listener ->
                        val serving =
                            launch {
                                listener.handleConnections { connection ->
                                    probe.note("server connection handler")
                                    connection.handleStreams { incoming ->
                                        probe.note("server stream handler")
                                        echo(probe, incoming)
                                        probe.note("server after the echo")
                                    }
                                }
                            }
                        val peer = QuicPeer.Candidates(listOf(QuicEndpoint(ResolvedAddress(LOOPBACK, IpFamily.V4), listener.port)), "localhost")
                        val connected = connectQuicOnCore(peer, options, TransportConfig(), WAIT)
                        probe.note("after the connect")
                        val client = assertIs<QuicConnect.Connected<QuicConnection>>(connected).value
                        val echoed = CompletableDeferred<Int>()
                        client
                            .openStream { (send, receive) ->
                                probe.note("client stream block")
                                assertIs<StreamWrite.Written>(send.writeAndFinish(bytes(ByteArray(PAYLOAD) { it.toByte() })))
                                probe.note("client after the write")
                                var got = 0
                                receive.readEach {
                                    probe.note("client read scope")
                                    got += it.remaining()
                                }
                                probe.note("client after the read")
                                echoed.complete(got)
                            }.getOrElse { fail("no stream was opened: $it") }
                        probe.note("after the open")
                        assertTrue(withTimeout(WAIT) { echoed.await() } == PAYLOAD, "the echo came back whole")
                        client.close(QuicCloseChoice.Immediate())
                        serving.cancel()
                    }
                assertIs<QuicServe.Serving<Unit>>(served)

                assertTrue(LoopSet.shared.countsOfStarted().isNotEmpty(), "no core loop was running, so nothing here was checked against one")
                assertTrue(probe.checked.get() > MIN_POINTS, "the probe recorded only ${probe.checked.get()} points")
                assertTrue(probe.traced.get() > 0, "no trace event reached a sink, so the sinks were not checked")
                if (probe.onLoop.isNotEmpty()) {
                    fail("application code ran on a core loop thread:\n" + probe.onLoop.distinct().joinToString("\n"))
                }
            }
        }

    /** The probe's sensitivity: work handed to a loop runs on a thread the probe names a loop thread. */
    @Test
    fun theControlWorkHandedToALoopRunsOnAThreadTheProbeSees() =
        runBlocking {
            skipOnMissingNativeLib(CoreApplicationCodeTests::class) {
                val probe = Probe()
                withCoreEchoServer { server ->
                    val client = connectCore(server.port)
                    val ran = CompletableDeferred<Unit>()
                    client.loop.port.submit(
                        object : LoopWork() {
                            override fun run(port: LoopPort) {
                                probe.note("work on the client's loop")
                                ran.complete(Unit)
                            }

                            override fun gone(end: ConnEnd) {
                                ran.complete(Unit)
                            }
                        },
                    )
                    withTimeout(WAIT) { ran.await() }
                    client.connection.closeNow(com.ditchoom.socket.quic.QuicError.NoError)
                }
                assertTrue(
                    probe.onLoop.isNotEmpty(),
                    "work run by a loop was never seen on a loop thread, so the probe cannot see one: the main test proves nothing",
                )
            }
        }

    private suspend fun echo(
        probe: Probe,
        incoming: QuicIncomingStream,
    ) {
        when (incoming) {
            is QuicStream ->
                incoming.receive.readEach {
                    probe.note("server read scope")
                    incoming.send.write(it)
                    probe.note("server after the write")
                }
            is QuicReceiveStream -> incoming.readEach { probe.note("server read scope") }
        }
    }

    private fun bytes(content: ByteArray): ReadBuffer =
        BufferFactory.network().allocate(content.size).apply {
            writeBytes(content)
            resetForRead()
        }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val PAYLOAD = 5_000
        const val MIN_POINTS = 8
        val WAIT = 10.seconds

        /** A core loop thread: the server's, or one of a client set; their handler threads carry the prefix too, and are not loops. */
        fun isLoopThread(name: String): Boolean =
            name == "quic-core-server" || Regex("${LoopSet.THREAD_PREFIX}-\\d+").matches(name)
    }
}
