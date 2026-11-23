package com.ditchoom.socket.quic.replay

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.NO_TIMEOUT_NANOS
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConfig
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.QuicheSendInfo
import com.ditchoom.socket.quic.ScopedReadResult
import com.ditchoom.socket.quic.core.conn.FlightSize
import com.ditchoom.socket.quic.core.conn.MismatchResponse
import com.ditchoom.socket.quic.core.demux.ServerConfig
import com.ditchoom.socket.quic.core.edge.DatagramSize
import com.ditchoom.socket.quic.core.edge.TestCertificate
import com.ditchoom.socket.quic.core.edge.coreConnect
import com.ditchoom.socket.quic.core.edge.coreServe
import com.ditchoom.socket.quic.core.edge.coreTestOptions
import com.ditchoom.socket.quic.core.edge.coreTlsConfig
import com.ditchoom.socket.quic.core.edge.echoOver
import com.ditchoom.socket.quic.core.testing.flightDirectory
import com.ditchoom.socket.quic.core.testing.makeDirectories
import com.ditchoom.socket.quic.core.testing.writeTextFile
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.core.udp.SocketClosed
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
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

        /** What the link counted, in order: for a failure's report, which datagram its recording lacks and when it left. */
        val handed = CopyOnWriteArrayList<Handed>()

        /** How many datagrams each receive call returned, in order. */
        val received = CopyOnWriteArrayList<Int>()

        /** Whether the client's socket is still open: it closes once the connection's linger is over. */
        @Volatile var socket = Socket.Open
            private set

        override fun close(): SocketClosed = real.close().also { socket = Socket.Closed }

        /** Where a stalling host's two stalls stand. */
        @Volatile private var stall = Stall.Ahead

        /** How late a stalling host reads the server's datagrams after its payload is lost; zero for any other host. */
        private val readStall =
            when (host) {
                ClientHost.StalledAroundConfirmation -> PAST_THE_PROBE
                ClientHost.StalledPastItsNextProbe -> PAST_THE_NEXT_PROBE
                ClientHost.Fast, ClientHost.SlowToRead, ClientHost.SlowToAnswer, ClientHost.LateForTimers, ClientHost.ColdToSend,
                ClientHost.SlowToSetUp, ClientHost.SlowAfterItsTimer,
                -> Duration.ZERO
            }

        override fun receive(into: ReceiveBatch): ReceiveBatchResult =
            real.receive(into).also {
                if (it is ReceiveBatchResult.Got) received += into.count
                // A slow host reaches quiche well after its socket read returned.
                if (host == ClientHost.SlowToRead && into.count > 0) Thread.sleep(SLOW.inWholeMilliseconds)
                if (readStall > Duration.ZERO && stall == Stall.Ahead && dropped.isNotEmpty() && into.count > 0) {
                    stall = Stall.ReadLate
                    Thread.sleep(readStall.inWholeMilliseconds)
                }
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
                handed +=
                    Handed(
                        this0,
                        System.nanoTime(),
                        Thread.currentThread().name,
                        hexOf(batch.storage, batch.offsetOf(n), batch.lengthOf(n)),
                    )
                if (dropped.isEmpty() && this0 >= HANDSHAKE_DATAGRAMS && batch.lengthOf(n) >= DATA) {
                    dropped += this0
                    continue
                }
                one.storage.copyFrom(batch.storage, batch.offsetOf(n), 0, batch.lengthOf(n))
                one.commit(batch.lengthOf(n), batch.targetOf(n))
                real.send(one)
                one.took(one.pending)
            }
            // The host stalls again after sending its answer, before its loop's next pass.
            if (stall == Stall.ReadLate) {
                stall = Stall.Over
                Thread.sleep(LATE.inWholeMilliseconds)
            }
            return SendBatchResult.AllTaken
        }
    }

    /**
     * Where a client's recording stands on its close. The close travels in the last datagram the client sent at or before
     * it recorded closing; until it records closing, the last datagram it holds is an earlier one, and a server closed
     * on that one's arrival closes before the client's close reaches it.
     */
    internal sealed interface RecordedClose {
        /** The client has not recorded closing. */
        data object NotYet : RecordedClose

        /** The datagram, as hex, that carried the close. */
        class Sent(
            val hex: String,
        ) : RecordedClose

        companion object {
            fun of(recording: List<TraceEvent>): RecordedClose {
                val closing = recording.filterIsInstance<TraceEvent.State>().firstOrNull { it.name.endsWith(CLOSED) } ?: return NotYet
                val sent = recording.filterIsInstance<TraceEvent.DgramOut>().lastOrNull { it.at <= closing.at } ?: return NotYet
                return Sent(sent.payloadHex)
            }

            private const val CLOSED = ".Closed"
        }
    }

    /** The recordings this test last made or converted: written where CI keeps them when the test fails. */
    private var recordings = Recorded(emptyList(), emptyList(), 0)

    /**
     * Runs the replay test [test] (its method's name). When it fails, both recordings it last made or converted are
     * written under the flight directory, as `<class>/<test>.client.trace` and `.server.trace`, and the failure names them.
     */
    private fun replayTest(
        test: String,
        body: suspend () -> Unit,
    ) = runBlocking {
        skipOnMissingNativeLib(CoreRecordingReplayTests::class) {
            try {
                body()
            } catch (failed: AssertionError) {
                throw AssertionError("${failed.message}\n${writeRecordings(test)}", failed)
            }
        }
    }

    /** Writes [recordings] for [test]; says where they went, or that they could not be written. */
    private fun writeRecordings(test: String): String {
        val directory = flightDirectory() + "/" + CoreRecordingReplayTests::class.simpleName
        if (!makeDirectories(directory)) return "recordings: could not write under $directory"
        val client = writeTextFile("$directory/$test.client.trace", linesOf(recordings.client))
        val server = writeTextFile("$directory/$test.server.trace", linesOf(recordings.server))
        return if (client && server) "recordings: $directory/$test.*.trace" else "recordings: could not write under $directory"
    }

    private fun linesOf(recording: List<TraceEvent>): String = recording.joinToString(separator = "\n", postfix = "\n")

    /** A datagram the link counted: its [ordinal], the [nanos] it was counted at, the [thread] that handed it, and its payload. */
    private class Handed(
        val ordinal: Int,
        val nanos: Long,
        val thread: String,
        val hex: String,
    )

    /**
     * Which datagrams the link counted that [recording] does not hold, and when each was counted against [snapshotNanos],
     * the time the recording was read: a datagram counted before it and missing was never recorded, one counted after it
     * left once the recording was taken.
     */
    private fun unrecorded(
        link: LossyLink,
        recording: List<TraceEvent>,
        snapshotNanos: Long,
    ): String {
        val held =
            recording
                .filterIsInstance<TraceEvent.DgramOut>()
                .groupingBy { it.payloadHex }
                .eachCount()
                .toMutableMap()
        val missing =
            link.handed.filter { sent ->
                val left = held[sent.hex] ?: 0
                if (left > 0) held[sent.hex] = left - 1
                left == 0
            }
        return missing.joinToString(prefix = "link datagrams the recording lacks: [", postfix = "]") {
            "#${it.ordinal} ${it.hex.length / 2} bytes ${(it.nanos - snapshotNanos) / NANOS_PER_MILLI} ms from the snapshot on ${it.thread}"
        }
    }

    private class Recorded(
        val client: List<TraceEvent>,
        val server: List<TraceEvent>,
        val lost: Int,
    )

    private enum class Stall { Ahead, ReadLate, Over }

    private enum class Socket { Open, Closed }

    /** How the client's host treats it. */
    private enum class ClientHost {
        Fast,

        /** It reaches quiche [SLOW] after each socket read that returned datagrams. */
        SlowToRead,

        /** It holds its thread for [SLOW] after quiche read each datagram, before it can answer. */
        SlowToAnswer,

        /** It wakes [LATE] after each of the client's timers falls due. */
        LateForTimers,

        /** Its first send call reaches quiche [ENTRY] after the host read its clock for it, as a cold downcall does. */
        ColdToSend,

        /** It takes [SETUP] to make its quiche connection after the trace's recorder was made for it. */
        SlowToSetUp,

        /** It holds its thread for [LATE] after running quiche's timeout, before it writes what the timeout made. */
        SlowAfterItsTimer,

        /**
         * After its payload is lost, it reads the server's next datagrams [PAST_THE_PROBE] late, past when the probe for
         * the payload falls due, then stalls [LATE] after sending its answer, before its loop runs again.
         */
        StalledAroundConfirmation,

        /**
         * As [StalledAroundConfirmation], but it reads the server's datagrams [PAST_THE_NEXT_PROBE] late, past when its
         * next probe falls due too; reading them, it learns the probe is not needed, and never runs that timer.
         */
        StalledPastItsNextProbe,
    }

    /** An echo of [PAYLOAD] bytes between a core client and a core server on [host], as the two of them recorded it. */
    private suspend fun recordAnEcho(host: ClientHost = ClientHost.Fast): Recorded {
        val clientRecording = CopyOnWriteArrayList<TraceEvent>()
        val serverRecording = CopyOnWriteArrayList<TraceEvent>()
        val server =
            coreServe(
                mismatch = MismatchResponse.Fail,
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
                    while (stream.read(10.seconds) { stream.writeFully(it, 10.seconds) } is ScopedReadResult.Data<*>) Unit
                } catch (closed: QuicCloseException) {
                    Unit
                }
            }
        val link = arrayOfNulls<LossyLink>(1)
        try {
            val client =
                coreConnect(
                    mismatch = MismatchResponse.Fail,
                    host = "127.0.0.1",
                    port = server.port,
                    serverName = "localhost",
                    options = coreTestOptions.copy(trace = QuicTraceCapture(TraceSink { clientRecording += it })),
                    factory = BufferFactory.Default,
                    api =
                        when (host) {
                            ClientHost.SlowToAnswer -> SlowToAnswer(loadQuicheApi())
                            ClientHost.LateForTimers -> LateForTimers(loadQuicheApi())
                            ClientHost.ColdToSend -> ColdToSend(loadQuicheApi())
                            ClientHost.SlowToSetUp -> SlowToSetUp(loadQuicheApi())
                            ClientHost.SlowAfterItsTimer -> SlowAfterItsTimer(loadQuicheApi())
                            ClientHost.Fast,
                            ClientHost.SlowToRead,
                            ClientHost.StalledAroundConfirmation,
                            ClientHost.StalledPastItsNextProbe,
                            -> loadQuicheApi()
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
            // close() returns once the CONNECTION_CLOSE is sent, but the client lingers (RFC 9000 §10.2.1) and repeats
            // its close unprompted until the linger is over and its socket closes: only then has it sent its last
            // datagram. Each record reaches the sink off the loop after that.
            withTimeoutOrNull(5.seconds) {
                while (link[0]!!.socket == Socket.Open) delay(10.milliseconds)
                while (clientRecording.count { it is TraceEvent.DgramOut } < link[0]!!.sent) delay(10.milliseconds)
            }
            // The server closing first would never read the client's close, and the conversion would have it lost: the
            // server closes once it has recorded it. Seen under a loaded full suite: "expected:<1> but was:<2>".
            withTimeoutOrNull(5.seconds) {
                while (true) {
                    val close = RecordedClose.of(clientRecording)
                    if (close is RecordedClose.Sent && serverRecording.any { it is TraceEvent.DgramIn && it.payloadHex == close.hex }) break
                    delay(10.milliseconds)
                }
            }
        } finally {
            echo.cancel()
            server.close()
        }
        recordings = Recorded(clientRecording.toList(), serverRecording.toList(), link[0]!!.dropped.size)
        val snapshotNanos = System.nanoTime()
        assertEquals(1, link[0]!!.dropped.size, "the link lost the client's data datagram")
        assertEquals(Socket.Closed, link[0]!!.socket, "the client's linger ended and closed its socket")
        assertEquals(
            link[0]!!.sent,
            clientRecording.count { it is TraceEvent.DgramOut },
            "the client's recording holds every datagram it sent; receive calls returned ${link[0]!!.received}; " +
                unrecorded(link[0]!!, clientRecording.toList(), snapshotNanos),
        )
        return Recorded(clientRecording.toList(), serverRecording.toList(), link[0]!!.dropped.size)
    }

    private fun scheduleOf(
        recorded: Recorded,
        client: List<TraceEvent> = recorded.client,
        server: List<TraceEvent> = recorded.server,
    ): CaptureSchedule {
        recordings = Recorded(client, server, recorded.lost)
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

    /**
     * A client's close is sent after the last datagram the link had counted when close() returned: a recording without
     * the client's closing state has not got the close yet, whatever datagrams it holds (CI JDK 17 / JNI: the server
     * closed on the 39-byte acknowledgement before the 38-byte close arrived, and the schedule lost both).
     */
    @Test
    fun aClientRecordingHasItsCloseOnlyOnceItRecordsClosing() {
        val ack = TraceEvent.DgramOut(1.milliseconds, 39, null, "aa")
        val close = TraceEvent.DgramOut(2.milliseconds, 38, null, "bb")
        val closing = TraceEvent.State(3.milliseconds, "com.ditchoom.socket.quic.QuicConnectionState.Closed", "local: NoError (0x0)")
        assertIs<RecordedClose.NotYet>(RecordedClose.of(listOf(ack)), "only an acknowledgement is recorded")
        assertIs<RecordedClose.NotYet>(RecordedClose.of(listOf(ack, close)), "the close is recorded before the state")
        assertEquals("bb", assertIs<RecordedClose.Sent>(RecordedClose.of(listOf(ack, close, closing))).hex)
    }

    /**
     * A replay whose timer fell due after every recorded firing near it ran that timer at a later recorded firing, and its
     * report says so: the due time, the recorded firing it just missed, and where it ran instead (CI FFM: a probe due at
     * 185.05 ms, the recorded firing at 182.16 ms, the replay's probe never sent by 186.8 ms).
     */
    @Test
    fun aReportNamesTheTimerThatMissedItsRecordedFiring() {
        val schedule =
            CaptureSchedule(
                name = "timer",
                ours = QuicRole.Client,
                alpn = "h3",
                ourTransport = TransportSetup(),
                peerTransport = TransportSetup(),
                peerPackets = PeerPackets.AsQuiche,
                steps = emptyList(),
                established = Moment.Never,
                end = ConnectionEnd.Open,
                runsFor = Duration.ZERO,
                tolerance = SendTimeTolerance(),
            )
        val report =
            ReplayReport(
                schedule = schedule,
                course = Course.FollowedCapture,
                established = Moment.Never,
                end = ConnectionEnd.Open,
                sent = emptyList(),
                peerRead = emptyList(),
                standInGaps = emptyList(),
                acksNotSent = emptyList(),
                acksUncaptured = 0,
                ranFor = Duration.ZERO,
                timersThatMissed = listOf(TimerMissed(due = 185.milliseconds, recorded = 182.milliseconds, ranAt = 307.milliseconds)),
            )
        val described = report.describe()
        assertTrue("due 185ms" in described && "recorded firing 182ms" in described && "ran at 307ms" in described, described)
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
        replayTest("aLossyCoreToCoreRunIsFollowedWholeByItsReplayTwice") {
            assertFollowedWholeTwice(scheduleOf(recordAnEcho()))
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
        replayTest("aRunWhoseClientWasSlowToAnswerIsFollowedWholeByItsReplayTwice") {
            assertFollowedWholeTwice(scheduleOf(recordAnEcho(ClientHost.SlowToAnswer)))
        }

    /**
     * A slow host: the client reaches quiche [SLOW] after each socket read. quiche measures its round trips to when it
     * read the server's datagrams, so the recording has them arrive then, and the replay's client times its probes as the
     * recording's did. Recorded at the read, the replay's round trip was [SLOW] shorter and its probes fell due early.
     */
    @Test
    fun aRunWhoseClientWasSlowToReadIsFollowedWholeByItsReplayTwice() =
        replayTest("aRunWhoseClientWasSlowToReadIsFollowedWholeByItsReplayTwice") {
            assertFollowedWholeTwice(scheduleOf(recordAnEcho(ClientHost.SlowToRead)))
        }

    /**
     * A loaded host wakes late for a timer: the client's woke [LATE] after each fell due, so its probe for the lost
     * payload left [LATE] after quiche wanted it to. Its recording says when each timer ran, and the replay's client runs
     * them then. Fired when they fell due, the replay's probe left [LATE] before the recording's (CI: "our datagram 3
     * [OneRtt] is one more than the capture holds" at 36.8 ms, where the recorded probe left at 43.6 ms).
     */
    @Test
    fun aRunWhoseClientWokeLateForItsTimersIsFollowedWholeByItsReplayTwice() =
        replayTest("aRunWhoseClientWokeLateForItsTimersIsFollowedWholeByItsReplayTwice") {
            assertFollowedWholeTwice(scheduleOf(recordAnEcho(ClientHost.LateForTimers)))
        }

    /**
     * A host whose first send call reaches quiche [ENTRY] after it read its clock for the write, as a cold FFM downcall
     * does: quiche stamps the Initial then and measures its first round trip from then, so the recording has the Initial
     * leave then. Recorded when the write began, the replay's first round trip was longer and every probe after it fell
     * due later than the recording's ran (FFM, the JVM's first connection: 0.71 ms, and a probe 2.5 ms late).
     */
    @Test
    fun aRunWhoseClientWasColdToSendIsFollowedWholeByItsReplayTwice() =
        replayTest("aRunWhoseClientWasColdToSendIsFollowedWholeByItsReplayTwice") {
            assertFollowedWholeTwice(scheduleOf(recordAnEcho(ClientHost.ColdToSend)))
        }

    /**
     * A host that confirmed its handshake only after its probe fell due learns of the probe when it next re-arms, after
     * the turn that answers the server, and runs it when its loop next runs, here [LATE] later. The replay's client,
     * told by quiche the probe is overdue as it reads the server's datagram, fired it at once (CI: "our datagram 4
     * [OneRtt] was sent at 344.071 ms, the capture's at 362.326 ms" in the slow-to-answer run).
     */
    @Test
    fun aRunWhoseClientRanAnOverdueTimerOnlyAfterItsNextStallIsFollowedWholeByItsReplayTwice() =
        replayTest("aRunWhoseClientRanAnOverdueTimerOnlyAfterItsNextStallIsFollowedWholeByItsReplayTwice") {
            assertFollowedWholeTwice(scheduleOf(recordAnEcho(ClientHost.StalledAroundConfirmation)))
        }

    /**
     * A host that took [SETUP] between making the trace's recorder and making its connection, as a Windows runner took
     * 35 ms: the datagrams and the states its recording holds count from one origin, so its close is recorded as closing
     * before its linger repeats it, and the replay follows it whole (CI Windows: the Closed state, stamped 36 ms late,
     * took two of the close's repeats into the connection: "expected:<1> but was:<3>").
     */
    @Test
    fun aRunWhoseClientWasSlowToSetUpIsFollowedWholeByItsReplayTwice() =
        replayTest("aRunWhoseClientWasSlowToSetUpIsFollowedWholeByItsReplayTwice") {
            val recorded = recordAnEcho(ClientHost.SlowToSetUp)
            val finished = recorded.client.filterIsInstance<TraceEvent.DgramOut>()[HANDSHAKE_DATAGRAMS - 1].at
            val established =
                recorded.client
                    .filterIsInstance<TraceEvent.State>()
                    .first { it.name.endsWith(".Established") }
                    .at
            assertTrue(
                established >= finished && established - finished < SETUP,
                "the client recorded itself established at $established, after its handshake's last datagram at $finished",
            )
            assertFollowedWholeTwice(scheduleOf(recorded))
        }

    /**
     * A host that stalled between running quiche's timeout and writing the probe it made: its recording has the timer
     * fire, then the probe leave [LATE] later, stamped by quiche as it wrote it. The replay's client writes the probe when
     * the recording's did, not when its timer ran (CI macOS JDK 25 FFM: a timer recorded at 55.90 ms, its probe at
     * 70.71 ms, "our datagram 4 [OneRtt] was sent at 55.81 ms, the capture's at 70.62 ms").
     */
    @Test
    fun aRunWhoseClientWasSlowAfterItsTimerIsFollowedWholeByItsReplayTwice() =
        replayTest("aRunWhoseClientWasSlowAfterItsTimerIsFollowedWholeByItsReplayTwice") {
            val recorded = recordAnEcho(ClientHost.SlowAfterItsTimer)
            val fired =
                recorded.client
                    .filterIsInstance<TraceEvent.TimerFired>()
                    .first()
                    .at
            val probe =
                recorded.client
                    .filterIsInstance<TraceEvent.DgramOut>()
                    .first { it.at > fired }
                    .at
            assertTrue(probe - fired >= LATE, "the probe left $probe, at least $LATE after its timer ran at $fired")
            assertFollowedWholeTwice(scheduleOf(recorded))
        }

    /**
     * A host stalled past its next probe as well: by the time it reads the server's answer to its first probe, the next
     * has fallen due, but the answer acknowledges what it would probe, so the host never runs that timer, and its
     * recording holds no firing after the first probe's. The replay's client, with no recorded firing left, fired the
     * probe when it fell due (CI, macOS: a [PAST_THE_PROBE] stall that overran to 194 ms, "our datagram 5 [OneRtt] is
     * one more than the capture holds" at 112 ms).
     */
    @Test
    fun aRunWhoseClientStalledPastItsNextProbeIsFollowedWholeByItsReplayTwice() =
        replayTest("aRunWhoseClientStalledPastItsNextProbeIsFollowedWholeByItsReplayTwice") {
            assertFollowedWholeTwice(scheduleOf(recordAnEcho(ClientHost.StalledPastItsNextProbe)))
        }

    /**
     * A server that read the client's close late, with no timer of the client's recorded after it: nothing happens between
     * the close and the end of the capture, and the replay's connection, closed by its own action, still ends closed by us
     * (CI FFM, slow to read: the close read 259 ms after it left, "end: replay Open, capture ClosedByUs").
     */
    @Test
    fun aCloseWhoseArrivalTheCaptureEndsBeforeIsStillTheReplaysEnd() =
        replayTest("aCloseWhoseArrivalTheCaptureEndsBeforeIsStillTheReplaysEnd") {
            val recorded = scheduleOf(recordAnEcho())
            val closedAt = assertIs<ConnectionEnd.ClosedByUs>(recorded.end).at
            val close = recorded.steps.filterIsInstance<Step.OursSent>().last()
            assertTrue(close.at >= closedAt, "the client's last datagram is its close")
            val readLate = close.copy(fate = Fate.Delivered(close.at + CLOSE_READ_LATE))
            val timers =
                when (val ourTimers = recorded.ourTimers) {
                    OurTimers.WhenDue -> ourTimers
                    is OurTimers.AsRecorded -> OurTimers.AsRecorded(ourTimers.firings.filter { it < closedAt })
                }
            assertFollowedWholeTwice(
                CaptureSchedule(
                    name = "${recorded.name}, its close read late",
                    ours = recorded.ours,
                    alpn = recorded.alpn,
                    ourTransport = recorded.ourTransport,
                    peerTransport = recorded.peerTransport,
                    peerPackets = recorded.peerPackets,
                    peerApp = recorded.peerApp,
                    ourApp = recorded.ourApp,
                    steps = recorded.steps.map { if (it === close) readLate else it },
                    established = recorded.established,
                    end = recorded.end,
                    runsFor = recorded.runsFor,
                    tolerance = recorded.tolerance,
                    idsAtEstablishment = recorded.idsAtEstablishment,
                    ourTimers = timers,
                ),
            )
        }

    /**
     * A client stalled far past its handshake probe ran its timer once, at the recorded firing, and probed once: one
     * Handshake datagram, then its 1-RTT data. quiche re-arms that probe from the last packet it sent, so until the probe
     * is sent the re-armed timer is overdue too; the replay that ran the timer while it stayed due probed twice (CI macOS
     * JDK 25 FFM, the four-tuple run: "our datagram 4 is [Handshake], the capture's is [OneRtt] at 44.647834ms").
     */
    @Test
    fun aTimerOverdueByMoreThanItsBackOffFiresOncePerRecordedFiring() =
        replayTest("aTimerOverdueByMoreThanItsBackOffFiresOncePerRecordedFiring") {
            val schedule =
                CaptureSchedule(
                    name = "core client stalled past its handshake probe (CI job 136431650679)",
                    ours = QuicRole.Client,
                    alpn = coreTestOptions.alpnProtocols.single(),
                    ourTransport = TransportSetup(idleTimeout = coreTestOptions.idleTimeout),
                    peerTransport = TransportSetup(idleTimeout = coreTestOptions.idleTimeout),
                    peerPackets = PeerPackets.AsQuiche,
                    peerApp = PeerApp.Echoes,
                    ourApp = OurApp.Reads,
                    steps =
                        listOf(
                            Step.OursSent(0.nanoseconds, listOf(PacketKind.Initial), Fate.Delivered(2_078_626.nanoseconds)),
                            Step.PeerSent(
                                3_545_209.nanoseconds,
                                listOf(PacketKind.Initial, PacketKind.Handshake),
                                Fate.Delivered(4_504_834.nanoseconds),
                            ),
                            Step.PeerSent(3_572_043.nanoseconds, listOf(PacketKind.Handshake), Fate.Delivered(4_637_043.nanoseconds)),
                            Step.OursSent(
                                4_838_710.nanoseconds,
                                listOf(PacketKind.Initial, PacketKind.Handshake, PacketKind.OneRtt),
                                Fate.Delivered(44_533_001.nanoseconds),
                            ),
                            Step.OursDid(44_313_584.nanoseconds, OurAction.Write(stream = 0, bytes = PAYLOAD, end = StreamEnd.Open)),
                            Step.OursSent(44_313_584.nanoseconds, listOf(PacketKind.OneRtt), Fate.Lost),
                            Step.OursSent(44_616_876.nanoseconds, listOf(PacketKind.Handshake), Fate.Delivered(44_616_876.nanoseconds)),
                            Step.OursSent(44_647_834.nanoseconds, listOf(PacketKind.OneRtt), Fate.Delivered(45_757_043.nanoseconds)),
                            Step.PeerSent(44_654_043.nanoseconds, listOf(PacketKind.OneRtt), Fate.Delivered(47_158_668.nanoseconds)),
                            Step.PeerSent(45_668_751.nanoseconds, listOf(PacketKind.OneRtt), Fate.Delivered(47_247_001.nanoseconds)),
                            Step.PeerSent(45_825_418.nanoseconds, listOf(PacketKind.OneRtt), Fate.Delivered(47_264_001.nanoseconds)),
                            Step.PeerSent(46_262_542.nanoseconds, listOf(PacketKind.OneRtt), Fate.Delivered(47_273_376.nanoseconds)),
                            Step.OursSent(
                                47_539_627.nanoseconds,
                                listOf(PacketKind.OneRtt),
                                Fate.Delivered(47_792_501.nanoseconds),
                                Carries.AcksOnly,
                            ),
                            Step.OursDid(674_425_709.nanoseconds, OurAction.Close),
                            Step.OursSent(
                                674_425_709.nanoseconds,
                                listOf(PacketKind.OneRtt),
                                Fate.Delivered(675_483_293.nanoseconds),
                                Carries.AcksOnly,
                            ),
                        ),
                    established = Moment.At(4_838_710.nanoseconds),
                    end = ConnectionEnd.ClosedByUs(674_425_709.nanoseconds),
                    runsFor = 674_425_709.nanoseconds,
                    tolerance = SendTimeTolerance(),
                    idsAtEstablishment = SPARE_IDS,
                    ourTimers = OurTimers.AsRecorded(listOf(44_566_584.nanoseconds, 55_400_959.nanoseconds)),
                )
            recordings = Recorded(emptyList(), emptyList(), 1)
            assertFollowedWholeTwice(schedule)
        }

    /**
     * The server took 1.32 ms to answer the client's handshake datagram, and said so in its acknowledgement's delay. The
     * client read that answer 60 ms late, so its round trip was long enough for quiche to take the delay off it, and its
     * probe fell due 1.49 ms before a round trip with no delay in it would have had it. The replay's modelled peer
     * answered on arrival, reporting no delay: its client's probe fell due past the recorded firing and was never sent
     * (CI Linux JDK 25 FFM: "our datagram 4 [OneRtt], captured at 98.58 ms, was not sent" at 110.26 ms).
     */
    @Test
    fun aPeerThatAnsweredLateIsReplayedWithTheAcknowledgementDelayItReported() =
        replayTest("aPeerThatAnsweredLateIsReplayedWithTheAcknowledgementDelayItReported") {
            fun at(nanos: Long) = nanos.nanoseconds
            val oneRtt = listOf(PacketKind.OneRtt)
            val schedule =
                CaptureSchedule(
                    name = "core client stalled 60 ms after its payload was lost, its server 1.32 ms to answer",
                    ours = QuicRole.Client,
                    alpn = coreTestOptions.alpnProtocols.single(),
                    ourTransport = TransportSetup(idleTimeout = coreTestOptions.idleTimeout),
                    peerTransport = TransportSetup(idleTimeout = coreTestOptions.idleTimeout),
                    peerPackets = PeerPackets.AsQuiche,
                    peerApp = PeerApp.Echoes,
                    ourApp = OurApp.Reads,
                    steps =
                        listOf(
                            Step.OursSent(at(0), listOf(PacketKind.Initial), Fate.Delivered(at(562_347))),
                            Step.PeerSent(at(1_465_642), listOf(PacketKind.Initial, PacketKind.Handshake), Fate.Delivered(at(1_661_641))),
                            Step.PeerSent(at(1_465_642), listOf(PacketKind.Handshake), Fate.Delivered(at(1_661_641))),
                            Step.OursSent(
                                at(2_729_985),
                                listOf(PacketKind.Initial, PacketKind.Handshake, PacketKind.OneRtt),
                                Fate.Delivered(at(2_889_303)),
                            ),
                            Step.OursDid(at(3_004_991), OurAction.Write(stream = 0, bytes = PAYLOAD, end = StreamEnd.Open)),
                            Step.OursSent(at(3_004_991), oneRtt, Fate.Lost),
                            Step.PeerSent(at(4_212_632), oneRtt, Fate.Delivered(at(64_660_759))),
                            Step.PeerSent(at(4_423_921), oneRtt, Fate.Delivered(at(64_913_196))),
                            Step.PeerSent(at(33_084_924), oneRtt, Fate.Delivered(at(64_913_196))),
                            Step.OursSent(at(64_996_883), oneRtt, Fate.Delivered(at(65_164_943)), Carries.AcksOnly),
                            Step.OursSent(at(98_579_641), oneRtt, Fate.Delivered(at(98_803_767))),
                            Step.PeerSent(at(98_952_612), oneRtt, Fate.Delivered(at(99_148_822))),
                            Step.PeerSent(at(99_657_220), oneRtt, Fate.Delivered(at(99_850_332))),
                            Step.OursSent(at(99_891_134), oneRtt, Fate.Delivered(at(99_891_134)), Carries.AcksOnly),
                            Step.OursDid(at(600_207_275), OurAction.Close),
                            Step.OursSent(at(600_207_275), oneRtt, Fate.Delivered(at(600_771_323)), Carries.AcksOnly),
                        ),
                    established = Moment.At(at(2_729_985)),
                    end = ConnectionEnd.ClosedByUs(at(600_207_275)),
                    runsFor = at(600_207_275),
                    tolerance = SendTimeTolerance(),
                    idsAtEstablishment = SPARE_IDS,
                    ourTimers = OurTimers.AsRecorded(listOf(at(98_579_641))),
                )
            recordings = Recorded(emptyList(), emptyList(), 1)
            assertFollowedWholeTwice(schedule)
        }

    /**
     * Kept recordings of clients that never ran quiche's timeout: each read the server's next datagrams late, and what
     * it read made the probe it was owed unnecessary. A replay runs no timer its recording does not hold, so it sends
     * what was recorded and nothing more (CI: "our datagram 4 [OneRtt] is one more than the capture holds" at 38.61 ms,
     * and "our datagram 3 [Handshake]" at 9.43 ms).
     */
    @Test
    fun aClientThatRanNoTimerIsReplayedRunningNone() =
        replayTest("aClientThatRanNoTimerIsReplayedRunningNone") {
            for (kept in listOf("no-timer-run-one-rtt", "no-timer-run-handshake")) {
                val client = keptRecording("$kept.client.trace")
                assertTrue(client.none { it is TraceEvent.TimerFired }, "$kept: the recorded client ran no timer")
                assertFollowedWholeTwice(scheduleOf(Recorded(client, keptRecording("$kept.server.trace"), 1)))
            }
        }

    /**
     * A kept recording whose client read the server's datagram and sent its acknowledgement in one turn, both stamped
     * with that turn's one clock reading. The acknowledgement follows the datagram it acknowledges in the schedule, so
     * the replay sends it (CI: "captured acknowledgements not sent: our datagrams [3]", then "our datagram 4 [OneRtt],
     * captured at 62.54 ms, was not sent").
     */
    @Test
    fun anAnswerSentInTheTurnThatReadItsDatagramIsReplayedAfterThatDatagram() =
        replayTest("anAnswerSentInTheTurnThatReadItsDatagramIsReplayedAfterThatDatagram") {
            val client = keptRecording("same-turn-answer.client.trace")
            val read = client.filterIsInstance<TraceEvent.DgramIn>()
            assertTrue(
                client.filterIsInstance<TraceEvent.DgramOut>().any { out -> read.any { it.at == out.at } },
                "the recording holds a datagram read and one sent at the same instant",
            )
            assertFollowedWholeTwice(scheduleOf(Recorded(client, keptRecording("same-turn-answer.server.trace"), 1)))
        }

    /** A kept CI recording whose client's probe left 14.8 ms after its timer ran (macOS JDK 25 FFM). */
    @Test
    fun aProbeWrittenLateAfterItsTimerIsReplayedWhenItWasWritten() =
        replayTest("aProbeWrittenLateAfterItsTimerIsReplayedWhenItWasWritten") {
            assertFollowedWholeTwice(
                scheduleOf(Recorded(keptRecording("probe-written-late.client.trace"), keptRecording("probe-written-late.server.trace"), 1)),
            )
        }

    /**
     * A kept CI recording (macOS JDK 21 FFM) whose client's write reached quiche with its handshake probe due: one turn ran
     * the timeout and took the write, and quiche coalesced the Handshake probe with the payload's 1-RTT packet. The write is
     * replayed in that turn, so the replay's probe carries the payload too.
     */
    @Test
    fun aWriteTakenInItsProbesTurnIsCoalescedWithTheProbe() =
        replayTest("aWriteTakenInItsProbesTurnIsCoalescedWithTheProbe") {
            assertFollowedWholeTwice(
                scheduleOf(
                    Recorded(
                        keptRecording("write-with-handshake-probe.client.trace"),
                        keptRecording("write-with-handshake-probe.server.trace"),
                        1,
                    ),
                ),
            )
        }

    /**
     * A kept CI recording (macOS JDK 21 FFM) whose client read nothing for 165 ms after its stall, then read its server's
     * probes and resent its lost payload on the acknowledgement they carried, running no timer. The modelled peer stands in
     * for each probe with one it wrote after reading what the captured server had read, so its last probe acknowledges
     * the client's datagram as the server's did, and the replay's client finds the payload lost and resends it too.
     */
    @Test
    fun aLossFoundOnALateAcknowledgementIsResentInTheReplay() =
        replayTest("aLossFoundOnALateAcknowledgementIsResentInTheReplay") {
            assertFollowedWholeTwice(
                scheduleOf(
                    Recorded(
                        keptRecording("loss-found-on-a-late-ack.client.trace"),
                        keptRecording("loss-found-on-a-late-ack.server.trace"),
                        1,
                    ),
                ),
            )
        }

    private fun keptRecording(name: String): List<TraceEvent> =
        TraceEvent.parseAll(
            assertNotNull(this::class.java.classLoader.getResource("replay-recordings/$name"), "no kept recording $name").readText(),
        )

    /** Whether a call has reached quiche yet. */
    private enum class Calls { Cold, Warm }

    /** quiche that the first send call reaches [ENTRY] after it was made. */
    private class ColdToSend(
        private val real: QuicheApi,
    ) : QuicheApi by real {
        private var calls = Calls.Cold

        override fun connSend(
            conn: QuicheConn,
            buf: Long,
            bufLen: Int,
            sendInfo: QuicheSendInfo,
        ): Int {
            if (calls == Calls.Cold) Thread.sleep(ENTRY.inWholeMilliseconds)
            calls = Calls.Warm
            return real.connSend(conn, buf, bufLen, sendInfo)
        }
    }

    /**
     * quiche whose every timer the host is asked to run [LATE] after it falls due; once it has fallen due, the host
     * sees it due, and runs quiche's timeout.
     */
    private class LateForTimers(
        private val real: QuicheApi,
    ) : QuicheApi by real {
        override fun connTimeoutNanos(conn: QuicheConn): Long =
            when (val wait = real.connTimeoutNanos(conn)) {
                NO_TIMEOUT_NANOS, FALLEN_DUE -> wait
                else -> wait + LATE.inWholeNanoseconds
            }
    }

    /** quiche that holds its thread for [LATE] after each timeout it runs. */
    private class SlowAfterItsTimer(
        private val real: QuicheApi,
    ) : QuicheApi by real {
        override fun connOnTimeout(conn: QuicheConn) {
            real.connOnTimeout(conn)
            Thread.sleep(LATE.inWholeMilliseconds)
        }
    }

    /** quiche that takes [SETUP] to make a connection. */
    private class SlowToSetUp(
        private val real: QuicheApi,
    ) : QuicheApi by real {
        override fun connect(
            serverNameAddr: Long,
            serverNameLen: Int,
            scidAddr: Long,
            scidLen: Int,
            localAddr: Long,
            localAddrLen: Int,
            peerAddr: Long,
            peerAddrLen: Int,
            config: QuicheConfig,
        ): QuicheConn {
            Thread.sleep(SETUP.inWholeMilliseconds)
            return real.connect(serverNameAddr, serverNameLen, scidAddr, scidLen, localAddr, localAddrLen, peerAddr, peerAddrLen, config)
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
        replayTest("aRunWhoseClientChangedFourTupleMidConnectionIsFollowedWholeByItsReplayTwice") {
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

    /**
     * A client that closed lingers (RFC 9000 §10.2.1): a datagram the server sent late reaches it, and it answers with
     * its CONNECTION_CLOSE again, byte for byte, which the server, already gone, never records. Its recording holds
     * both; the schedule is the connection's alone, the same as without them, and its replay still follows it whole.
     * CI found this: the answer's bytes matched the close's first arrival, which moved the clocks' alignment by the
     * linger's length.
     */
    @Test
    fun whatAClosedClientSentAndReceivedWhileLingeringIsNotInItsSchedule() =
        replayTest("whatAClosedClientSentAndReceivedWhileLingeringIsNotInItsSchedule") {
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

    /**
     * The client's application wrote the payload on its first stream when its first datagram carrying it left: the first
     * after the handshake's at least [DATA] long, the one the link loses. It may lead with a Handshake probe the same turn
     * coalesced with it.
     */
    private fun writeOf(recording: List<TraceEvent>): RecordingToSchedule.RecordedAction {
        val first =
            recording
                .filterIsInstance<TraceEvent.DgramOut>()
                .drop(HANDSHAKE_DATAGRAMS)
                .first { it.len >= DATA }
        return RecordingToSchedule.RecordedAction(first.at, OurAction.Write(stream = 0, bytes = PAYLOAD, end = StreamEnd.Open))
    }

    private companion object {
        /** The handshake's datagrams of the client: its Initial, then its Initial and Handshake coalesced. */
        const val HANDSHAKE_DATAGRAMS = 2

        /** A datagram this long carries the payload; acknowledgements are far shorter, and the handshake's come first. */
        const val DATA = 500
        const val PAYLOAD = 600

        const val NANOS_PER_MILLI = 1_000_000L

        fun hexOf(
            bytes: NativeBytes,
            offset: Int,
            length: Int,
        ): String = (offset until offset + length).joinToString("") { "%02x".format(bytes.byteAt(it)) }

        /** The spare ids a core client hands its peer as its handshake completes, with the default limit of 8. */
        const val SPARE_IDS = 7
        const val MOVED_PORT = 40_000
        const val MOVED_ADDRESS = 0x7f000002L
        val LINGER_STEP = 100.milliseconds

        /** Far past the replay's tolerance of the close's time: no arrival of it ends the capture's run. */
        val CLOSE_READ_LATE = 300.milliseconds

        /** Longer than the replay's tolerance of a send's time: a replay that answers at once leaves the recording. */
        val SLOW = 15.milliseconds

        /** Longer than the replay's tolerance of a send's time: a replay that fires when due leaves the recording. */
        val LATE = 20.milliseconds

        /** Longer than the close's linger takes to repeat it twice. */
        val SETUP = 40.milliseconds

        /** Trebled from a first round trip into its probe, far past the replay's precision on a timer. */
        val ENTRY = 3.milliseconds

        /** quiche's wait for a timer that has fallen due. */
        const val FALLEN_DUE = 0L

        /** Longer than the probe for the lost payload takes to fall due on loopback (about 30 ms). */
        val PAST_THE_PROBE = 60.milliseconds

        /** Longer than the probe for the lost payload and the next one after it take to fall due on loopback (about 110 ms). */
        val PAST_THE_NEXT_PROBE = 200.milliseconds
        val BIG = FlightSize(records = 4096)
    }
}
