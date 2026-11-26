package com.ditchoom.socket.quic.core.demux

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.InternetAccess
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.DatagramSend
import com.ditchoom.socket.quic.MigrationPolicy
import com.ditchoom.socket.quic.NetworkMonitorSource
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.edge.connectCore
import com.ditchoom.socket.quic.core.edge.coreTestOptions
import com.ditchoom.socket.quic.core.edge.public
import com.ditchoom.socket.quic.core.edge.withCoreServer
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.CrossingCount
import com.ditchoom.socket.quic.core.testing.platformQuicheApi
import com.ditchoom.socket.quic.core.testing.withDescriptorsAbove127
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.transport.NetworkId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The calls into quiche while 1 KiB echoes run on one long-lived stream per connection, client and server counted
 * together: every call is a crossing into native code. Timing decides how many loop turns, datagrams and stream
 * reads an echo takes (chunks coalesce), so no count here equals a number per echo. Each call has an upper bound
 * from work that is itself counted in the same run, or a fixed most-per-echo for the stream operations an echo cannot
 * exceed. A call added per turn, per datagram or per stream operation breaks its bound on any machine, and a function
 * with no bound fails as a new kind of crossing. The calls one turn makes together (`connReadable`, `connIsClosed`,
 * `streamIterFree`) are bounded by each other, not by a number.
 */
class CoreNativeCrossingTests {
    @get:Rule
    val noLeak: org.junit.rules.TestRule = NoServerLoopLeft()

    private val loops = LoopSet(2)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun oneConnectionEchoesCrossIntoQuicheAFewTimesPerEcho() = measure(connections = 1, measured = 2_000)

    @Test
    fun sixtyFourConnectionsEchoCrossIntoQuicheAFewTimesPerEcho() = measure(connections = 64, measured = 200)

    /**
     * An idle client whose idle timeout ends it fires quiche's timer at least once, and each firing reads its active
     * path's probes once: the echoes' law for `connActivePathProbes`, met by a run that always fires the timer.
     */
    @Test
    fun aClientsTimerFiringReadsItsActivePathOnce() =
        runBlocking {
            val count = CrossingCount(platformQuicheApi())
            withCoreEchoServer(factory = BufferFactory.network()) { server ->
                val client =
                    connectCore(
                        server.port,
                        loops = loops,
                        api = count.api,
                        options =
                            coreTestOptions.copy(
                                idleTimeout = IDLE_TIMEOUT,
                                migration = MigrationPolicy.Automatic,
                                networkMonitor = NetworkMonitorSource.Supplied(ChangeableNetwork()),
                            ),
                    )
                val stream = client.connection.openStream()
                // The echo has the handshake established, so the watch's baseline read lands before counting starts.
                roundTrip(stream, payload())
                count.start()
                withTimeout(10.seconds) { client.connection.public().awaitEnd() }
                count.stop()
                val c = count.byFunction().toMap()
                val fired = c["connOnTimeout"] ?: 0L
                assertTrue(fired > 0, "the idle timeout ended the client without its timer firing:\n%s".format(count.report(1)))
                assertEquals(
                    fired,
                    c["connActivePathProbes"] ?: 0L,
                    "reads of the active path per timer firing:\n%s".format(count.report(1)),
                )
                val broken = Laws.broken(c, echoes = 0, slack = SLACK_PER_CONNECTION)
                assertTrue(broken.isEmpty(), "crossings out of line with the work done: %s\n%s".format(broken, count.report(1)))
            }
        }

    /**
     * A client sends datagrams in bursts to a server whose reader takes them a batch at a time: each datagram is read out
     * of quiche once, and a lend asks quiche's queue its length once, whether it takes datagrams or parks until a read
     * wakes it.
     */
    @Test
    fun datagramsReadInBatchesCrossIntoQuicheOncePerDatagram() =
        runBlocking {
            withDescriptorsAbove127 {
                val count = CrossingCount(platformQuicheApi())
                val options = coreTestOptions.copy(datagrams = DatagramOptions())
                val delivered = AtomicLong()
                withCoreServer(api = count.api, options = options, onConnection = { connection ->
                    connection.public().datagrams.receiveEach { delivered.incrementAndGet() }
                }) { server ->
                    val client = connectCore(server.port, loops = loops, api = count.api, options = options)
                    val channel = client.connection.coreDatagrams
                    val payload = payload()

                    suspend fun bursts(datagrams: Int) {
                        val from = delivered.get()
                        var sent = 0
                        while (sent < datagrams) {
                            when (channel.send(payload)) {
                                is DatagramSend.Sent -> sent++
                                else -> delay(1)
                            }
                        }
                        // Every datagram sent is delivered, or dropped, once the reader's count holds still.
                        withTimeout(10.seconds) {
                            var last = from - 1
                            while (delivered.get() != last) {
                                last = delivered.get()
                                delay(QUIET_MS)
                            }
                        }
                    }
                    bursts(DATAGRAM_WARM_UP)
                    val from = delivered.get()
                    count.start()
                    bursts(DATAGRAMS)
                    count.stop()
                    val read = delivered.get() - from
                    val report = count.report(read.toInt())
                    println("[core native crossings] datagrams read in batches, per datagram delivered:\n%s".format(report))
                    client.connection.close(QuicError.NoError)
                    payload.freeIfNeeded()
                    val broken =
                        Laws.broken(
                            count.byFunction().toMap(),
                            echoes = 0,
                            slack = read / SLACK_ECHOES_DIVISOR + SLACK_PER_CONNECTION * 2,
                            datagramsDelivered = read,
                        )
                    assertTrue(broken.isEmpty(), "crossings out of line with the work done: %s\n%s".format(broken, report))
                }
            }
        }

    /** A monitor whose network can change, so a client under [MigrationPolicy.Automatic] judges its path's silence. */
    private class ChangeableNetwork : NetworkMonitor {
        override val state = MutableStateFlow<NetworkState>(NetworkState.Routable(NetworkId.Unidentified, InternetAccess.Unobserved))

        override fun close() = Unit
    }

    private fun measure(
        connections: Int,
        measured: Int,
    ) = runBlocking {
        withDescriptorsAbove127 {
            val count = CrossingCount(platformQuicheApi())
            withCoreEchoServer(api = count.api, factory = BufferFactory.network()) { server ->
                val clients =
                    List(connections) { connectCore(server.port, loops = loops, api = count.api, factory = BufferFactory.network()) }
                val streams = clients.map { it.connection.openStream() }
                val payloads = List(connections) { payload() }
                echoes(streams, payloads, WARM_UP)
                count.start()
                echoes(streams, payloads, measured)
                count.stop()
                val echoes = connections * measured
                val report = count.report(echoes)
                println("[core native crossings] %d connection(s), per 1 KiB echo:\n%s".format(connections, report))
                clients.forEach { it.connection.close(QuicError.NoError) }
                val broken =
                    Laws.broken(
                        count.byFunction().toMap(),
                        echoes,
                        slack =
                            echoes / SLACK_ECHOES_DIVISOR + SLACK_PER_CONNECTION * connections,
                    )
                assertTrue(broken.isEmpty(), "crossings out of line with the work done: %s\n%s".format(broken, report))
            }
        }
    }

    private suspend fun echoes(
        streams: List<QuicByteStream>,
        payloads: List<PlatformBuffer>,
        count: Int,
    ) = coroutineScope {
        streams.indices
            .map { i -> async(Dispatchers.Default) { repeat(count) { roundTrip(streams[i], payloads[i]) } } }
            .awaitAll()
    }

    private fun payload(): PlatformBuffer =
        BufferFactory.network().allocate(PAYLOAD).apply {
            repeat(PAYLOAD) { writeByte(it.toByte()) }
            resetForRead()
        }

    private suspend fun roundTrip(
        stream: QuicByteStream,
        payload: PlatformBuffer,
    ) {
        payload.position(0)
        stream.write(payload, 10.seconds)
        var received = 0
        while (received < payload.limit()) {
            val result = stream.read(10.seconds)
            if (result !is ReadResult.Data) fail("the echo ended early: $result")
            received += result.buffer.remaining()
            result.buffer.freeIfNeeded()
        }
    }

    private companion object {
        const val PAYLOAD = 1_024
        val IDLE_TIMEOUT = 300.milliseconds
        const val WARM_UP = 300

        /** Counting starts and stops while work is in flight: a count is off by a few per connection and by 2 % of the echoes. */
        const val SLACK_ECHOES_DIVISOR = 50L
        const val SLACK_PER_CONNECTION = 8L
        const val DATAGRAM_WARM_UP = 2_000
        const val DATAGRAMS = 20_000
        const val QUIET_MS = 50L
    }

    /** What each quiche function's count must stay under, given the work counted in the same run. */
    private object Laws {
        /** The most stream reads and writes an echo makes, client and server together; coalesced chunks make fewer. */
        private const val RECV_STEPS_PER_ECHO = 4L
        private const val SEND_STEPS_PER_ECHO = 2L
        private const val CAPACITY_CALLS_PER_ECHO = 2L

        fun broken(
            c: Map<String, Long>,
            echoes: Int,
            slack: Long,
            datagramsDelivered: Long = 0L,
        ): List<String> {
            fun n(name: String) = c[name] ?: 0L
            val turns = n("connReadable")
            val datagrams = n("connRecv")
            val recvSteps = n("connStreamRecvStep")
            val sendSteps = n("connStreamSendStep")
            val laws =
                listOf(
                    Law.atMost("connStreamRecvStep", recvSteps, RECV_STEPS_PER_ECHO * echoes, slack),
                    Law.atMost("connStreamSendStep", sendSteps, SEND_STEPS_PER_ECHO * echoes, slack),
                    Law.atMost("connStreamCapacity", n("connStreamCapacity"), CAPACITY_CALLS_PER_ECHO * echoes, slack),
                    // One turn makes these together: each is bounded by the others.
                    Law.atMost("connReadable", turns, n("connIsClosed"), slack),
                    Law.atMost("connIsClosed", n("connIsClosed"), turns, slack),
                    Law.atMost("streamIterFree", n("streamIterFree"), turns, slack),
                    // One per turn, plus at most one per stream read (a readable stream is read at least once).
                    Law.atMost("streamIterNextId", n("streamIterNextId"), turns + recvSteps, slack),
                    // One per turn, plus at most one per stream write.
                    Law.atMost("connStreamWritableNext", n("connStreamWritableNext"), turns + sendSteps, slack),
                    // One per datagram received: both calls serve the same datagram.
                    Law.atMost("sendInfoAtNanos", n("sendInfoAtNanos"), datagrams, slack),
                    Law.atMost("connRecv", datagrams, n("sendInfoAtNanos"), slack),
                    // At most one per turn.
                    Law.atMost("connTimeoutNanos", n("connTimeoutNanos"), turns, slack),
                    Law.atMost("connOnTimeout", n("connOnTimeout"), turns, slack),
                    // One per quiche timer firing on a client that judges its path's silence; a server reads none.
                    Law.atMost("connActivePathProbes", n("connActivePathProbes"), n("connOnTimeout"), slack),
                    // How a connection ended: asked once, after quiche answered closed.
                    Law.atMost("connIsTimedOut", n("connIsTimedOut"), n("connIsClosed"), slack),
                    // A connection is freed once, after its end was read, with its send info and one receive info per path it made.
                    Law.atMost("connFree", n("connFree"), n("connIsTimedOut"), slack),
                    Law.atMost("sendInfoFree", n("sendInfoFree"), n("connFree"), slack),
                    Law.atMost("recvInfoFree", n("recvInfoFree"), n("connFree"), slack),
                    // Whether the peer's close arrived: asked once in a turn that handed quiche a datagram.
                    Law.atMost("connIsDraining", n("connIsDraining"), minOf(turns, datagrams), slack),
                    // At most one terminating call per turn plus one per datagram sent (about one per datagram received).
                    Law.atMost("connSend", n("connSend"), turns + datagrams, slack),
                ) + datagramLaws(::n, turns, datagrams, datagramsDelivered, slack)
            val known = laws.map { it.name }.toSet()
            return laws.mapNotNull { it.violation() } + c.keys.filter { it !in known }.map { "$it: no law, a new crossing" }
        }

        /**
         * The datagram crossings, given the [delivered] datagrams a reader was handed and the [turns] and quiche [reads] that
         * carried them. Each datagram is read out of quiche once. A lend asks quiche's queue its length once, and is the work
         * of a turn that answers its reader, or of a wake after a read found quiche holding one. Whether quiche holds one is asked once a read
         * while a batch is parked, and once a turn. A sender's feed hands quiche each datagram once and stops at the first
         * refused, once a turn; it reads the room once a turn, and quiche's send queue once a packet written.
         */
        private fun datagramLaws(
            n: (String) -> Long,
            turns: Long,
            reads: Long,
            delivered: Long,
            slack: Long,
        ): List<Law> =
            listOf(
                Law.atMost("connDgramRecvStep", n("connDgramRecvStep"), delivered, slack),
                Law.atMost("connDgramRecvQueueLen", n("connDgramRecvQueueLen"), turns + n("hasReadableDgram"), slack),
                Law.atMost("hasReadableDgram", n("hasReadableDgram"), reads + turns, slack),
                Law.atMost("connDgramSend", n("connDgramSend"), delivered + turns, slack),
                Law.atMost("connDgramMaxWritableBytes", n("connDgramMaxWritableBytes"), turns, slack),
                Law.atMost("connDgramSendQueueLen", n("connDgramSendQueueLen"), n("connSend"), slack),
            )
    }

    /** What one function's count must satisfy. */
    private class Law private constructor(
        val name: String,
        private val counted: Long,
        private val bound: Long,
    ) {
        fun violation(): String? = if (counted > bound) "$name: counted $counted, bound $bound" else null

        companion object {
            /** [counted] is at most [bound] plus [slack]: counting starts and stops while work is in flight. */
            fun atMost(
                name: String,
                counted: Long,
                bound: Long,
                slack: Long,
            ) = Law(name, counted, bound + slack)
        }
    }
}
