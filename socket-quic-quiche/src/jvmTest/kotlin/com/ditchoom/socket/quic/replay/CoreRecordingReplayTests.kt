package com.ditchoom.socket.quic.replay

import com.ditchoom.buffer.BaseJvmBuffer
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.unwrapFully
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.core.conn.FlightSize
import com.ditchoom.socket.quic.core.demux.ServerConfig
import com.ditchoom.socket.quic.core.edge.coreConnect
import com.ditchoom.socket.quic.core.edge.coreServe
import com.ditchoom.socket.quic.core.edge.coreTestOptions
import com.ditchoom.socket.quic.core.edge.coreTlsConfig
import com.ditchoom.socket.quic.core.edge.echoOver
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.NioOneByOne
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.jvmMigrationSimEnv
import com.ditchoom.socket.quic.loadQuicheApi
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.Buffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A core client and a core server, each recording through its trace capture, echo over a link that loses some of the
 * client's datagrams. The two recordings become one network schedule ([RecordingToSchedule]), and a fresh quiche pair
 * put through that schedule twice behaves the same both times: the schedule, not the run, decides what happens.
 */
class CoreRecordingReplayTests {
    /** Loses the client's datagrams whose ordinals are in [lost]; sends the rest itself, one by one. */
    private class LossyLink(
        private val real: DatagramBatchIo,
        private val lost: Set<Int>,
    ) : DatagramBatchIo by real {
        private var ordinal = 0
        val dropped = CopyOnWriteArrayList<Int>()

        override fun send(batch: SendBatch): SendBatchResult {
            val channel = (real as NioOneByOne).channel
            val view = (batch.storage.owner.unwrapFully() as BaseJvmBuffer).byteBuffer.duplicate()
            for (n in 0 until batch.pending) {
                val this0 = ordinal++
                if (this0 in lost) {
                    dropped += this0
                    continue
                }
                (view as Buffer).limit(batch.offsetOf(n) + batch.lengthOf(n)).position(batch.offsetOf(n))
                channel.write(view)
            }
            return SendBatchResult.AllTaken
        }
    }

    @Test
    fun aLossyCoreToCoreRunReplaysTheSameWayTwiceThroughAFreshQuichePair() =
        runBlocking {
            skipOnMissingNativeLib(CoreRecordingReplayTests::class) {
                val clientRecording = CopyOnWriteArrayList<TraceEvent>()
                val serverRecording = CopyOnWriteArrayList<TraceEvent>()
                val server =
                    coreServe(
                        bind = InetSocketAddress("127.0.0.1", 0),
                        tls = coreTlsConfig(),
                        options = coreTestOptions.copy(trace = QuicTraceCapture(TraceSink { serverRecording += it })),
                        factory = BufferFactory.Default,
                        api = loadQuicheApi(),
                        readPolicy = ReadPolicy.Bounded(10.seconds),
                        writePolicy = WritePolicy.Bounded(10.seconds),
                        config = ServerConfig(flight = BIG),
                    )
                val echo =
                    launch(Dispatchers.IO) {
                        val connection = server.accept()
                        val stream = connection.acceptStream()
                        // The client closing the connection ends the echo; it is not a failure.
                        try {
                            while (stream.read(10.seconds) { stream.writeFully(it, 10.seconds) } is ScopedRead.Data<*>) Unit
                        } catch (closed: com.ditchoom.socket.quic.QuicCloseException) {
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
                            api = loadQuicheApi(),
                            timeout = 10.seconds,
                            readPolicy = ReadPolicy.Bounded(10.seconds),
                            writePolicy = WritePolicy.Bounded(10.seconds),
                            wrapIo = { real -> LossyLink(real, LOST).also { link[0] = it } },
                            flight = BIG,
                        )
                    val payload = Random(3).nextBytes(PAYLOAD)
                    kotlin.test.assertContentEquals(payload, echoOver(client.connection.openStream(), payload))
                    // Recordings reach their sinks off the loop: give them a moment to arrive.
                    delay(500.milliseconds)
                    client.connection.close(QuicError.NoError)
                } finally {
                    echo.cancel()
                    server.close()
                }
                assertTrue(link[0]!!.dropped.isNotEmpty(), "the link lost some of the client's datagrams")

                val converted =
                    RecordingToSchedule.convert(
                        name = "core client and core server over a lossy link",
                        ours = QuicRole.Client,
                        alpn = coreTestOptions.alpnProtocols.single(),
                        ourRecording = clientRecording.toList(),
                        peerRecording = serverRecording.toList(),
                        ourTransport = TransportSetup(idleTimeout = coreTestOptions.idleTimeout),
                        peerTransport = TransportSetup(idleTimeout = coreTestOptions.idleTimeout),
                    )
                val schedule = assertIs<RecordingToSchedule.Converted.Schedule>(converted).schedule
                val ours = schedule.steps.filterIsInstance<Step.OursSent>()
                assertEquals(
                    link[0]!!.dropped.size,
                    ours.count { it.fate == Fate.Lost },
                    "every datagram the link lost, and no other, is lost in the schedule",
                )
                assertTrue(schedule.steps.any { it is Step.PeerSent && it.fate is Fate.Delivered }, "the server's datagrams arrived")

                val first = replayCapture(jvmMigrationSimEnv(), schedule)
                val second = replayCapture(jvmMigrationSimEnv(), schedule)
                println(first.describe())
                assertEquals(first.describe(), second.describe(), "the same schedule replays the same way")
            }
        }

    private companion object {
        /** The client's second and fourth datagrams are lost: one in the handshake, one after it. */
        val LOST = setOf(1, 3)
        const val PAYLOAD = 3_000
        val BIG = FlightSize(records = 4096, payloadBytes = 1 shl 20)
    }
}
