package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.QuicCloseReason
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.core.edge.CoreConnection
import com.ditchoom.socket.quic.core.edge.connectCore
import com.ditchoom.socket.quic.core.edge.echoOver
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.FlightEvidenceRule
import com.ditchoom.socket.quic.loadQuicheApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Closing a server, and what one connection's failure does to the others. */
class CoreServerCloseTests {
    @get:Rule
    val flightEvidence = FlightEvidenceRule()

    @get:org.junit.Rule
    val noLeak: org.junit.rules.TestRule = NoServerLoopLeft()

    /** Client loops of this class's own, so connections it leaves ending never share a thread with another test's. */
    private val loops = LoopSet(2)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun closeDuringAFloodReturnsPromptlyEndsItsConnectionsAndStopsTheLoopThread() =
        runBlocking {
            withCoreEchoServer { server ->
                val client = connectCore(server.port, loops = loops)
                val payload = Random(1).nextBytes(2_000)
                assertContentEquals(payload, echoOver(client.connection.openStream(), payload))

                val sent = AtomicLong()
                val flooding = Flood(server.port, sent)
                val flooder = thread(name = "core-server-flood") { flooding.run() }
                delay(500.milliseconds)
                assertTrue(sent.get() > 1_000, "the flood is running: ${sent.get()} datagrams")

                val started = TimeSource.Monotonic.markNow()
                server.close()
                val took = started.elapsedNow()
                flooding.stop()
                flooder.join(5_000)

                assertTrue(took < 2.seconds, "close returned in $took while ${sent.get()} datagrams were sent at the server")
                assertTrue(liveServerLoops() == 0, "the loop thread ended")
                val closed = withTimeout(5.seconds) { client.connection.state.first { it is QuicConnectionState.Closed } }
                assertIs<QuicConnectionState.Closed>(closed)
                assertEquals(0, server.parts.connections + liveServerLoops(), "no connection or thread is left")
            }
        }

    @Test
    fun closingAServerSendsConnectionCloseToItsClients() =
        runBlocking {
            withCoreEchoServer { server ->
                val client = connectCore(server.port, loops = loops)
                val payload = Random(2).nextBytes(100)
                assertContentEquals(payload, echoOver(client.connection.openStream(), payload))
                server.close()
                // The peer is told, so it ends at once rather than when its idle timeout runs out (10 s here).
                val closed = withTimeout(3.seconds) { client.connection.state.first { it is QuicConnectionState.Closed } }
                val reason = assertIs<QuicConnectionState.Closed>(closed).reason
                assertIs<QuicCloseReason.ByPeer>(reason)
            }
        }

    @Test
    fun closesThatRaceAllReturnOnceTheLoopHasStopped() =
        runBlocking {
            withCoreEchoServer { server ->
                val client = connectCore(server.port, loops = loops)
                val closes = (0 until 4).map { async(Dispatchers.IO) { withTimeout(5.seconds) { server.close() } } }
                closes.awaitAll()
                assertEquals(0, liveServerLoops())
                client.connection.close(QuicError.NoError)
            }
        }

    @Test
    fun aServerThatWasClosedAcceptsNoMoreConnections() =
        runBlocking {
            withCoreEchoServer { server ->
                server.close()
                val outcome = runCatching { withTimeout(2.seconds) { connectCore(server.port, loops = loops) } }
                assertTrue(outcome.isFailure, "a connect to a closed server fails")
            }
        }

    @Test
    fun oneConnectionsPlantedFailureEndsOnlyThatConnection() =
        runBlocking {
            val api = PlantingApi(loadQuicheApi())
            val accepted = ConcurrentLinkedQueue<CoreConnection>()
            withCoreEchoServer(api = api, onAccepted = accepted::add) { server ->
                val doomed = connectCore(server.port, loops = loops)
                val healthy = connectCore(server.port, loops = loops)
                val doomedStream = doomed.connection.openStream()
                val healthyStream = healthy.connection.openStream()
                val first = Random(3).nextBytes(300)
                assertContentEquals(first, echoOver(doomedStream, first))
                assertContentEquals(first, echoOver(healthyStream, first))
                assertEquals(2, accepted.size)

                api.plant(api.accepted.first())
                // The next datagram of the first connection makes quiche's receive throw inside the server.
                runCatching { echoOver(doomedStream, Random(4).nextBytes(300)) }

                val serverSide = accepted.first()
                val ended = withTimeout(5.seconds) { serverSide.state.first { it is QuicConnectionState.Closed } }
                assertEquals(QuicConnectionState.Closed(QuicCloseReason.ByLocal(QuicError.ProcessingFailed)), ended)

                val passes = server.loop.counts.passes
                val second = Random(5).nextBytes(5_000)
                assertContentEquals(second, echoOver(healthyStream, second), "the other connection still echoes")
                assertTrue(server.loop.counts.passes > passes, "the loop kept passing")
                assertEquals(QuicConnectionState.Established("test"), accepted.last().state.value)
                healthy.connection.close(QuicError.NoError)
            }
        }

    /** Sends random 1200-byte datagrams at [port] as fast as the socket takes them, until stopped. */
    private class Flood(
        private val port: Int,
        private val sent: AtomicLong,
    ) {
        @Volatile private var stopped = false

        fun stop() {
            stopped = true
        }

        fun run() {
            val random = Random(99)
            val datagram = ByteArray(1_200)
            DatagramChannel.open().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port))
                while (!stopped) {
                    random.nextBytes(datagram)
                    try {
                        socket.write(ByteBuffer.wrap(datagram))
                        sent.incrementAndGet()
                    } catch (refused: java.io.IOException) {
                        // The port closed under the flood: the server is gone, which is what is being tested.
                        return
                    }
                }
            }
        }
    }

    /** Throws from `quiche_conn_recv` for one connection once told to, so that connection's processing fails. */
    private class PlantingApi(
        private val real: QuicheApi,
    ) : QuicheApi by real {
        val accepted = ConcurrentLinkedQueue<QuicheConn>()

        @Volatile private var planted = 0L

        fun plant(conn: QuicheConn) {
            planted = conn.handle
        }

        override fun accept(
            scidAddr: Long,
            scidLen: Int,
            odcidAddr: Long,
            odcidLen: Int,
            localAddr: Long,
            localAddrLen: Int,
            peerAddr: Long,
            peerAddrLen: Int,
            config: com.ditchoom.socket.quic.QuicheConfig,
        ): QuicheConn =
            real
                .accept(scidAddr, scidLen, odcidAddr, odcidLen, localAddr, localAddrLen, peerAddr, peerAddrLen, config)
                .also(accepted::add)

        override fun connRecv(
            conn: QuicheConn,
            buf: Long,
            bufLen: Int,
            recvInfo: QuicheRecvInfo,
        ): Int {
            if (conn.handle == planted) throw PlantedFailure()
            return real.connRecv(conn, buf, bufLen, recvInfo)
        }
    }

    private class PlantedFailure : IllegalStateException()
}
