package com.ditchoom.socket.quic.core.demux

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.edge.connectCore
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.CrossingCount
import com.ditchoom.socket.quic.core.testing.platformQuicheApi
import com.ditchoom.socket.quic.core.testing.withDescriptorsAbove127
import com.ditchoom.socket.quic.network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * The calls into quiche while 1 KiB echoes run on one long-lived stream per connection, client and server counted
 * together: every call is a crossing into native code. How many loop turns and datagrams an echo takes depends on timing,
 * so nothing here is a count per echo. Each call is tied to the work it serves, counted in the same run: a turn
 * (`connReadable`), a datagram received (`connRecv`), a stream read or write (the `connStreamRecvStep` and
 * `connStreamSendStep` an echo makes, which do not depend on timing). A call per turn, per datagram or per stream
 * operation breaks its law whatever the machine's speed; a function with no law fails as a new kind of crossing.
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
        const val WARM_UP = 300

        /** Counting starts and stops while work is in flight: a count is off by a few per connection and by 2 % of the echoes. */
        const val SLACK_ECHOES_DIVISOR = 50L
        const val SLACK_PER_CONNECTION = 8L
    }

    /** What each quiche function's count must equal, or stay under, given the work counted in the same run. */
    private object Laws {
        /** Stream reads and writes one echo makes, client and server together: they do not depend on timing. */
        private const val RECV_STEPS_PER_ECHO = 4L
        private const val SEND_STEPS_PER_ECHO = 2L
        private const val CAPACITY_CALLS_PER_ECHO = 2L

        fun broken(
            c: Map<String, Long>,
            echoes: Int,
            slack: Long,
        ): List<String> {
            fun n(name: String) = c[name] ?: 0L
            val turns = n("connReadable")
            val datagrams = n("connRecv")
            val recvSteps = n("connStreamRecvStep")
            val sendSteps = n("connStreamSendStep")
            val laws =
                listOf(
                    Law.Near("connStreamRecvStep", recvSteps, RECV_STEPS_PER_ECHO * echoes, slack),
                    Law.Near("connStreamSendStep", sendSteps, SEND_STEPS_PER_ECHO * echoes, slack),
                    Law.Near("connStreamCapacity", n("connStreamCapacity"), CAPACITY_CALLS_PER_ECHO * echoes, slack),
                    // One per turn.
                    Law.Near("connIsClosed", n("connIsClosed"), turns, slack),
                    Law.Near("streamIterFree", n("streamIterFree"), turns, slack),
                    // One per turn, plus one per readable stream reported (a stream read is two recv steps).
                    Law.Near("streamIterNextId", n("streamIterNextId"), turns + recvSteps / 2, slack),
                    // One per turn, plus one per stream written.
                    Law.Near("connStreamWritableNext", n("connStreamWritableNext"), turns + sendSteps, slack),
                    // One per datagram received.
                    Law.Near("sendInfoAtNanos", n("sendInfoAtNanos"), datagrams, slack),
                    // At most one per turn.
                    Law.AtMost("connTimeoutNanos", n("connTimeoutNanos"), turns),
                    Law.AtMost("connOnTimeout", n("connOnTimeout"), turns),
                    // At most one terminating call per turn plus one per datagram sent (about one per datagram received).
                    Law.AtMost("connSend", n("connSend"), turns + datagrams),
                )
            val known = laws.map { it.name }.toSet() + setOf("connReadable", "connRecv")
            return laws.mapNotNull { it.violation() } + c.keys.filter { it !in known }.map { "$it: no law, a new crossing" }
        }
    }

    /** What one function's count must satisfy. */
    private sealed interface Law {
        val name: String

        fun violation(): String?

        /** [counted] is within [slack] of [expected]. */
        class Near(
            override val name: String,
            private val counted: Long,
            private val expected: Long,
            private val slack: Long,
        ) : Law {
            override fun violation() =
                if (kotlin.math.abs(counted - expected) > slack) "$name: counted $counted, law says about $expected" else null
        }

        /** [counted] is at most [expected]. */
        class AtMost(
            override val name: String,
            private val counted: Long,
            private val expected: Long,
        ) : Law {
            override fun violation() = if (counted > expected) "$name: counted $counted, law says at most $expected" else null
        }
    }
}
