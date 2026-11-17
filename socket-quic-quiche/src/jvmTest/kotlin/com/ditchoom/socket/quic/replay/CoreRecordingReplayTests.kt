package com.ditchoom.socket.quic.replay

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.core.conn.FlightSize
import com.ditchoom.socket.quic.core.demux.ServerConfig
import com.ditchoom.socket.quic.core.edge.DatagramSize
import com.ditchoom.socket.quic.core.edge.TestCertificate
import com.ditchoom.socket.quic.core.edge.coreConnect
import com.ditchoom.socket.quic.core.edge.coreServe
import com.ditchoom.socket.quic.core.edge.coreTestOptions
import com.ditchoom.socket.quic.core.edge.coreTlsConfig
import com.ditchoom.socket.quic.core.edge.echoOver
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.core.udp.toPathKey
import com.ditchoom.socket.quic.jvmMigrationSimEnv
import com.ditchoom.socket.quic.loadQuicheApi
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TracePath
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A core client and a core server, each recording through its trace capture, echo over a link that loses the client's
 * first data datagram. The two recordings become one network schedule ([RecordingToSchedule]); a fresh quiche pair put
 * through it follows the recording whole, every datagram of ours in its place with its recorded fate and the
 * connection ending as recorded, and does so the same way twice: the schedule, not the run, decides what happens.
 */
class CoreRecordingReplayTests {
    /** Loses the client's first datagram after the handshake's that is at least [DATA] bytes; sends the rest itself. */
    private class LossyLink(
        private val real: DatagramBatchIo,
        private val host: ClientHost,
    ) : DatagramBatchIo by real {
        private var ordinal = 0
        val dropped = CopyOnWriteArrayList<Int>()

        /** Every datagram the client handed the link, lost or not. */
        val sent: Int get() = ordinal

        /** How many datagrams each receive call returned, in order. */
        val received = CopyOnWriteArrayList<Int>()

        override fun receive(into: ReceiveBatch): ReceiveBatchResult =
            real.receive(into).also {
                if (it is ReceiveBatchResult.Got) received += into.count
                // A slow host reaches quiche well after its socket read returned.
                if (host == ClientHost.SlowToRead && into.count > 0) Thread.sleep(SLOW.inWholeMilliseconds)
            }

        /** One single-datagram batch per lane: each datagram the link keeps goes to the real socket on its own. */
        private val single = java.util.concurrent.ConcurrentHashMap<Int, SendBatch>()

        override fun send(batch: SendBatch): SendBatchResult {
            val one =
                single.computeIfAbsent(batch.lane) { lane ->
                    val bytes = (NativeBytes.of(BufferFactory.Default.allocate(batch.limits.maxDatagramSize)) as Addressed.Native).bytes
                    SendBatch(bytes, BatchLimits(1, batch.limits.maxDatagramSize), lane)
                }
            for (n in 0 until batch.pending) {
                val this0 = ordinal++
                if (dropped.isEmpty() && this0 >= HANDSHAKE_DATAGRAMS && batch.lengthOf(n) >= DATA) {
                    dropped += this0
                    continue
                }
                one.storage.copyFrom(batch.storage, batch.offsetOf(n), 0, batch.lengthOf(n))
                one.commit(batch.lengthOf(n), batch.targetOf(n))
                real.send(one)
                one.took(one.pending)
            }
            return SendBatchResult.AllTaken
        }
    }

    private class Recorded(
        val client: List<TraceEvent>,
        val server: List<TraceEvent>,
        val lost: Int,
    )

    /** How the client's host treats it. */
    private enum class ClientHost {
        Fast,

        /** It reaches quiche [SLOW] after each socket read that returned datagrams. */
        SlowToRead,

        /** It holds its thread for [SLOW] after quiche read each datagram, before it can answer. */
        SlowToAnswer,
    }

    /** An echo of [PAYLOAD] bytes between a core client and a core server on [host], as the two of them recorded it. */
    private suspend fun recordAnEcho(host: ClientHost = ClientHost.Fast): Recorded {
        val clientRecording = CopyOnWriteArrayList<TraceEvent>()
        val serverRecording = CopyOnWriteArrayList<TraceEvent>()
        val server =
            coreServe(
                bind = InetSocketAddress("127.0.0.1", 0).toPathKey(),
                // The replay's modelled server serves the RSA certificate: the recorded flight must have its shape.
                tls = coreTlsConfig(TestCertificate.Rsa2048),
                options = coreTestOptions.copy(trace = QuicTraceCapture(TraceSink { serverRecording += it })),
                factory = BufferFactory.Default,
                api = loadQuicheApi(),
                readPolicy = ReadPolicy.Bounded(10.seconds),
                writePolicy = WritePolicy.Bounded(10.seconds),
                config = ServerConfig(flight = BIG, flightArenaBytes = 1 shl 20),
                // The replay's modelled ends do not probe: a recording made with probes leaves the capture.
                datagramSize = DatagramSize.Configured,
            )
        val echo =
            CoroutineScope(Dispatchers.IO).launch {
                val connection = server.accept()
                val stream = connection.acceptStream()
                // The client closing the connection ends the echo; it is not a failure.
                try {
                    while (stream.read(10.seconds) { stream.writeFully(it, 10.seconds) } is ScopedRead.Data<*>) Unit
                } catch (closed: QuicCloseException) {
                    Unit
                }
            }
        val link = arrayOfNulls<LossyLink>(1)
        try {
            val client =
                coreConnect(
                    host = "127.0.0.1",
                    port = server.port,
                    serverName = "localhost",
                    options = coreTestOptions.copy(trace = QuicTraceCapture(TraceSink { clientRecording += it })),
                    factory = BufferFactory.Default,
                    api =
                        when (host) {
                            ClientHost.SlowToAnswer -> SlowToAnswer(loadQuicheApi())
                            ClientHost.Fast, ClientHost.SlowToRead -> loadQuicheApi()
                        },
                    timeout = 10.seconds,
                    readPolicy = ReadPolicy.Bounded(10.seconds),
                    writePolicy = WritePolicy.Bounded(10.seconds),
                    wrapIo = { real -> LossyLink(real, host).also { link[0] = it } },
                    flight = BIG,
                    datagramSize = DatagramSize.Configured,
                )
            val payload = Random(3).nextBytes(PAYLOAD)
            assertContentEquals(payload, echoOver(client.connection.openStream(), payload))
            // Recordings reach their sinks off the loop: give them a moment to arrive.
            delay(500.milliseconds)
            client.connection.close(QuicError.NoError)
            // close() returns once the CONNECTION_CLOSE is sent; its record reaches the sink off the loop after that.
            withTimeoutOrNull(5.seconds) {
                while (clientRecording.count { it is TraceEvent.DgramOut } < link[0]!!.sent) delay(10.milliseconds)
            }
            // The server closing first would never read the client's close, and the conversion would have it lost: the
            // server closes once it has recorded it. Seen under a loaded full suite: "expected:<1> but was:<2>".
            val close = clientRecording.filterIsInstance<TraceEvent.DgramOut>().last().payloadHex
            withTimeoutOrNull(5.seconds) {
                while (serverRecording.none { it is TraceEvent.DgramIn && it.payloadHex == close }) delay(10.milliseconds)
            }
        } finally {
            echo.cancel()
            server.close()
        }
        assertEquals(1, link[0]!!.dropped.size, "the link lost the client's data datagram")
        assertEquals(
            link[0]!!.sent,
            clientRecording.count { it is TraceEvent.DgramOut },
            "the client's recording holds every datagram it sent; receive calls returned ${link[0]!!.received}",
        )
        return Recorded(clientRecording.toList(), serverRecording.toList(), link[0]!!.dropped.size)
    }

    private fun scheduleOf(
        recorded: Recorded,
        client: List<TraceEvent> = recorded.client,
        server: List<TraceEvent> = recorded.server,
    ): CaptureSchedule {
        val converted =
            RecordingToSchedule.convert(
                name = "core client and core server over a lossy link",
                ours = QuicRole.Client,
                alpn = coreTestOptions.alpnProtocols.single(),
                ourRecording = client,
                peerRecording = server,
                ourTransport = TransportSetup(idleTimeout = coreTestOptions.idleTimeout),
                peerTransport = TransportSetup(idleTimeout = coreTestOptions.idleTimeout),
                actions = listOf(writeOf(client)),
                peerApp = PeerApp.Echoes,
                ourApp = OurApp.Reads,
                idsAtEstablishment = SPARE_IDS,
            )
        val schedule = assertIs<RecordingToSchedule.Converted.Schedule>(converted).schedule
        assertEquals(
            recorded.lost,
            schedule.steps.filterIsInstance<Step.OursSent>().count { it.fate == Fate.Lost },
            "every datagram the link lost, and no other, is lost in the schedule",
        )
        assertTrue(schedule.steps.any { it is Step.PeerSent && it.fate is Fate.Delivered }, "the server's datagrams arrived")
        return schedule
    }

    /** Replays [schedule] twice and holds the replay to the recording: followed whole, ended as recorded, identical. */
    private suspend fun assertFollowedWholeTwice(schedule: CaptureSchedule) {
        val first = replayCapture(jvmMigrationSimEnv(), schedule)
        val second = replayCapture(jvmMigrationSimEnv(), schedule)
        assertEquals(
            Course.FollowedCapture,
            first.course,
            "the replay follows the recording to its end\n${first.describe()}\n${schedule.steps.joinToString("\n")}",
        )
        assertIs<ConnectionEnd.ClosedByUs>(first.end, "the connection ends as recorded, closed by us\n${first.describe()}")
        assertEquals(PAYLOAD, first.peerRead.single().bytes, "the server read the whole payload\n${first.describe()}")
        assertEquals(first.describe(), second.describe(), "the same schedule replays the same way")
    }

    @Test
    fun aLossyCoreToCoreRunIsFollowedWholeByItsReplayTwice() =
        runBlocking {
            skipOnMissingNativeLib(CoreRecordingReplayTests::class) {
                assertFollowedWholeTwice(scheduleOf(recordAnEcho()))
            }
        }

    /**
     * A slow host: the client takes [SLOW] over each datagram it receives before it can answer, as a loaded runner's
     * did with its handshake and its write (CI: 26 ms between a turn's start and quiche writing). The recording has each
     * answer leave that long after what it answers was read, and the server's two handshake datagrams read by quiche
     * [SLOW] apart; the replay's client takes both before it answers, and answers when the recording's did, so every
     * timer quiche arms from those packets falls due when the recording's did.
     */
    @Test
    fun aRunWhoseClientWasSlowToAnswerIsFollowedWholeByItsReplayTwice() =
        runBlocking {
            skipOnMissingNativeLib(CoreRecordingReplayTests::class) {
                assertFollowedWholeTwice(scheduleOf(recordAnEcho(ClientHost.SlowToAnswer)))
            }
        }

    /**
     * A slow host: the client reaches quiche [SLOW] after each socket read. quiche measures its round trips to when it
     * read the server's datagrams, so the recording has them arrive then, and the replay's client times its probes as the
     * recording's did. Recorded at the read, the replay's round trip was [SLOW] shorter and its probes fell due early.
     */
    @Test
    fun aRunWhoseClientWasSlowToReadIsFollowedWholeByItsReplayTwice() =
        runBlocking {
            skipOnMissingNativeLib(CoreRecordingReplayTests::class) {
                assertFollowedWholeTwice(scheduleOf(recordAnEcho(ClientHost.SlowToRead)))
            }
        }

    /** quiche that, after each datagram it reads, holds its thread for [SLOW]: the host's work before quiche answers. */
    private class SlowToAnswer(
        private val real: QuicheApi,
    ) : QuicheApi by real {
        override fun connRecv(
            conn: QuicheConn,
            buf: Long,
            bufLen: Int,
            recvInfo: QuicheRecvInfo,
        ): Int = real.connRecv(conn, buf, bufLen, recvInfo).also { Thread.sleep(SLOW.inWholeMilliseconds) }
    }

    /**
     * The walk failure class: the client's four-tuple changes mid-connection. Both recordings say so (the client's
     * address is another one from its first data datagram on), and the replay follows them end to end.
     */
    @Test
    fun aRunWhoseClientChangedFourTupleMidConnectionIsFollowedWholeByItsReplayTwice() =
        runBlocking {
            skipOnMissingNativeLib(CoreRecordingReplayTests::class) {
                val recorded = recordAnEcho()
                val changedAt = writeOf(recorded.client).at
                val moved = TracePath(family = 4, port = MOVED_PORT, hi = 0, lo = MOVED_ADDRESS)

                fun List<TraceEvent>.movedAfter(at: Duration): List<TraceEvent> =
                    map {
                        when {
                            it is TraceEvent.DgramOut && it.at >= at -> it.copy(path = moved)
                            it is TraceEvent.DgramIn && it.at >= at -> it.copy(path = moved)
                            else -> it
                        }
                    }
                val client = recorded.client.movedAfter(changedAt)
                // The two recordings keep clocks of their own: the server sees the move once it is established.
                val server =
                    recorded.server.movedAfter(
                        recorded.server
                            .filterIsInstance<TraceEvent.State>()
                            .first { it.name.endsWith(".Established") }
                            .at,
                    )
                assertTrue(
                    client
                        .filterIsInstance<TraceEvent.DgramOut>()
                        .map { it.path }
                        .distinct()
                        .size > 1,
                    "the client's path changed",
                )
                assertFollowedWholeTwice(scheduleOf(recorded, client, server))
            }
        }

    /**
     * A client that closed lingers (RFC 9000 §10.2.1): a datagram the server sent late reaches it, and it answers with
     * its CONNECTION_CLOSE again, byte for byte, which the server, already gone, never records. Its recording holds
     * both; the schedule is the connection's alone, the same as without them, and its replay still follows it whole.
     * CI found this: the answer's bytes matched the close's first arrival, which moved the clocks' alignment by the
     * linger's length.
     */
    @Test
    fun whatAClosedClientSentAndReceivedWhileLingeringIsNotInItsSchedule() =
        runBlocking {
            skipOnMissingNativeLib(CoreRecordingReplayTests::class) {
                val recorded = recordAnEcho()
                val closedAt =
                    recorded.client
                        .filterIsInstance<TraceEvent.State>()
                        .first { it.name.endsWith(".Closed") && it.detail?.startsWith("local") == true }
                        .at
                val close = recorded.client.filterIsInstance<TraceEvent.DgramOut>().last { it.at <= closedAt }
                val late = recorded.server.filterIsInstance<TraceEvent.DgramOut>().last()
                val serverEnd = recorded.server.maxOf { it.at }
                val lateSent = late.copy(at = serverEnd + LINGER_STEP)
                val clientEnd = recorded.client.maxOf { it.at }
                val lingered =
                    recorded.client +
                        listOf(
                            TraceEvent.DgramIn(clientEnd + LINGER_STEP, late.len, late.path, late.payloadHex),
                            close.copy(at = clientEnd + LINGER_STEP),
                        )
                val without = scheduleOf(recorded).steps
                val with = scheduleOf(recorded, lingered, recorded.server + lateSent).steps
                assertEquals(
                    without.filterIsInstance<Step.OursSent>(),
                    with.filterIsInstance<Step.OursSent>(),
                    "the linger's answer is not the connection's, and every delivery keeps its time",
                )
                // The server's late datagram reached only the linger, never quiche: to the connection it was lost.
                assertEquals(without + (with - without.toSet()), with)
                assertEquals(Fate.Lost, assertIs<Step.PeerSent>((with - without.toSet()).single()).fate)
                assertFollowedWholeTwice(scheduleOf(recorded, lingered, recorded.server + lateSent))
            }
        }

    /** The client's application wrote the payload on its first stream when its first datagram carrying data left. */
    private fun writeOf(recording: List<TraceEvent>): RecordingToSchedule.RecordedAction {
        val first =
            recording
                .filterIsInstance<TraceEvent.DgramOut>()
                .filter {
                    isShortHeader(
                        it.payloadHex,
                    ) &&
                        it.len >= DATA
                }.minBy { it.at }
        return RecordingToSchedule.RecordedAction(first.at, OurAction.Write(stream = 0, bytes = PAYLOAD, end = StreamEnd.Open))
    }

    /** Whether a datagram, in hex, starts with a short header (RFC 9000 §17.3): a packet of the connection's 1-RTT space. */
    private fun isShortHeader(hex: String): Boolean = hex.take(2).toInt(HEX) and LONG_HEADER_BIT == 0

    private companion object {
        const val HEX = 16
        const val LONG_HEADER_BIT = 0x80

        /** The handshake's datagrams of the client: its Initial, then its Initial and Handshake coalesced. */
        const val HANDSHAKE_DATAGRAMS = 2

        /** A datagram this long carries the payload; acknowledgements are far shorter, and the handshake's come first. */
        const val DATA = 500
        const val PAYLOAD = 600

        /** The spare ids a core client hands its peer as its handshake completes, with the default limit of 8. */
        const val SPARE_IDS = 7
        const val MOVED_PORT = 40_000
        const val MOVED_ADDRESS = 0x7f000002L
        val LINGER_STEP = 100.milliseconds

        /** Longer than the replay's tolerance of a send's time: a replay that answers at once leaves the recording. */
        val SLOW = 15.milliseconds
        val BIG = FlightSize(records = 4096)
    }
}
