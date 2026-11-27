package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.IncomingStreams
import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicStreamAbort
import com.ditchoom.socket.quic.QuicStreamException
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicStreamLimit
import com.ditchoom.socket.quic.QuicStreamSide
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.StreamOpenPolicy
import com.ditchoom.socket.quic.StreamSendStep
import com.ditchoom.socket.quic.core.conn.FlightTail
import com.ditchoom.socket.quic.core.conn.StallView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How a stream's life ends on a real core client and server on virtual time: an open its caller cancels as it is
 * granted gives the stream back, each kind of stream is accepted only by an acceptor of that kind, a reset or a stop on
 * a unidirectional stream reaches the peer, and the peer gets its stream credit back however its stream ended here.
 */
class UniStreamLifeVirtualTests {
    private fun options(uni: Long = 1) =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 30.seconds,
            enablePmtuDiscovery = false,
            incomingStreams = IncomingStreams.Accept(unidirectional = QuicStreamLimit(uni.toUInt())),
            streamOpenPolicy = StreamOpenPolicy.Bounded(1.hours),
        )

    @Test
    fun anOpenCancelledAsTheLoopGrantsItGivesItsStreamBack() {
        val hook = OpenHook()
        VirtualPair.with(options(), clientApi = { hook.around(it) }) { pair ->
            val server = pair.accept()
            val client = pair.client.connection
            drainEveryReceiveOnly(server)
            val before = view(pair, client).liveStreams
            val first = opened(pair) { client.halves.openSendOnly() }
            lateinit var waiting: Job
            waiting = launch { runCatching { client.halves.openSendOnly() } }
            pair.runFor(1.seconds)
            hook.onOpen = { waiting.cancel() }
            launch { sendAll(first) }
            pair.runUntil { waiting.isCompleted }
            hook.onOpen = {}
            pair.runFor(1.seconds)
            assertEquals(before, view(pair, client).liveStreams, "the granted stream nobody took was given back")
            val next = opened(pair) { client.halves.openSendOnly() }
            assertEquals(10L, next.id.id, "the peer's credit for the stream given back came back")
        }
    }

    @Test
    fun anOpenAnsweredInPlaceToACallerCancelledMeanwhileGivesItsStreamBack() {
        val hook = OpenHook()
        VirtualPair.with(options(), clientApi = { hook.around(it) }) { pair ->
            val server = pair.accept()
            val client = pair.client.connection
            drainEveryReceiveOnly(server)
            val before = view(pair, client).liveStreams
            lateinit var cancelled: Job
            hook.onOpen = { cancelled.cancel() }
            cancelled = launch { runCatching { client.halves.openSendOnly() } }
            pair.runUntil { cancelled.isCompleted }
            hook.onOpen = {}
            pair.runFor(1.seconds)
            assertEquals(before, view(pair, client).liveStreams, "the stream answered to a cancelled caller was given back")
            val next = opened(pair) { client.halves.openSendOnly() }
            assertEquals(6L, next.id.id, "the peer's credit for the stream given back came back")
        }
    }

    @Test
    fun aCancelledWaitingOpenGivesBackItsPlaceAndSpendsNoId() =
        VirtualPair.with(options()) { pair ->
            val server = pair.accept()
            val client = pair.client.connection
            drainEveryReceiveOnly(server)
            val first = opened(pair) { client.halves.openSendOnly() }
            var outcome: Result<SendHalf>? = null
            val waiting = launch { outcome = runCatching { client.halves.openSendOnly() } }
            pair.runFor(1.seconds)
            val seen = view(pair, client)
            assertEquals(1, seen.opensWaiting, "the second open waits; it came to ${outcome.describe()}, ${seen.describe()}")
            waiting.cancel()
            pair.runUntil { waiting.isCompleted }
            assertEquals(0, view(pair, client).opensWaiting, "the cancelled open left its lane")
            launch { sendAll(first) }
            val next = opened(pair) { client.halves.openSendOnly() }
            assertEquals(6L, next.id.id, "the cancelled open spent no id")
        }

    @Test
    fun aUniAcceptNeverTakesABidirectionalStreamNorTheOther() =
        VirtualPair.with(options(uni = 100)) { pair ->
            val server = pair.accept()
            val client = pair.client.connection
            val bidi = opened(pair) { client.halves.openBidirectional() }
            launch { sendAll(bidi.send) }
            pair.runFor(200.milliseconds)
            val uni = opened(pair) { client.halves.openSendOnly() }
            launch { sendAll(uni) }
            val receiveOnly = opened(pair) { server.halves.acceptReceiveOnly() }
            assertEquals(uni.id, receiveOnly.receive.id, "the uni acceptor skipped the bidirectional stream that came first")
            val bidirectional = opened(pair) { server.halves.acceptBidirectional() }
            assertEquals(bidi.id, bidirectional.stream.id)
        }

    @Test
    fun acceptTypedStreamTakesOnlyABidirectionalStream() =
        VirtualPair.with(options(uni = 100)) { pair ->
            val server = pair.accept()
            val client = pair.client.connection
            val uni = opened(pair) { client.halves.openSendOnly() }
            launch { sendAll(uni) }
            pair.runFor(200.milliseconds)
            val bidi = opened(pair) { client.halves.openBidirectional() }
            launch { sendAll(bidi.send) }
            val typed = opened(pair) { server.acceptTypedStream(NoteCodec) }
            assertEquals(bidi.id, typed.id, "the typed acceptor left the unidirectional stream alone")
            val left = opened(pair) { server.halves.acceptReceiveOnly() }
            assertEquals(uni.id, left.receive.id)
        }

    @Test
    fun aResetOfASendOnlyStreamReachesTheReaderAndAStopReachesTheWriter() =
        VirtualPair.with(options(uni = 100)) { pair ->
            val server = pair.accept()
            val client = pair.client.connection
            val reset = opened(pair) { client.halves.openSendOnly() }
            launch { reset.write(bytes(), 1.hours) }
            val resetHere = opened(pair) { server.halves.acceptReceiveOnly() }.receive
            reset.reset(CODE)
            var last: ReadResult = ReadResult.End
            launch { last = readUntilEnd(resetHere) }
            pair.runUntil { last === ReadResult.Reset }

            val stopped = opened(pair) { client.halves.openSendOnly() }
            launch { stopped.write(bytes(), 1.hours) }
            val stopHere = opened(pair) { server.halves.acceptReceiveOnly() }.receive
            assertEquals(Stopping.Now, stopHere.stop(CODE))
            var refused: Throwable? = null
            launch {
                refused =
                    runCatching {
                        while (true) stopped.write(bytes(), 1.hours)
                    }.exceptionOrNull()
            }
            pair.runUntil { refused != null }
            assertEquals(QuicStreamAbort.StopSending(CODE), assertIs<QuicStreamException>(refused).abort)
        }

    @Test
    fun thePeerGetsItsUniCreditBackWhetherItsStreamWasReadAbandonedOrStopped() =
        VirtualPair.with(options()) { pair ->
            val server = pair.accept()
            val client = pair.client.connection
            val ends =
                listOf<suspend (ReceiveHalf) -> Unit>(
                    { readUntilEnd(it) },
                    { it.close() },
                    { it.stop(CODE) },
                )
            var expected = 2L
            for (end in ends) {
                val send = opened(pair) { client.halves.openSendOnly() }
                assertEquals(expected, send.id.id, "a limit of one: each open waited for the last stream's credit")
                launch { runCatching { sendAll(send) } }
                val receive = opened(pair) { server.halves.acceptReceiveOnly() }.receive
                launch { end(receive) }
                expected += 4
            }
            val last = opened(pair) { client.halves.openSendOnly() }
            assertEquals(expected, last.id.id)
        }

    @Test
    fun aOneWayStreamRefusesTheDirectionItDoesNotHaveAsAbsentNotClosed() =
        VirtualPair.with(options(uni = 100)) { pair ->
            val server = pair.accept()
            val client = pair.client.connection
            val sendOnly = opened(pair) { client.openUniStream() }
            launch { sendOnly.write(bytes(), 1.hours) }
            val receiveOnly = opened(pair) { server.acceptStream() }
            var failures = emptyList<Throwable?>()
            launch {
                failures =
                    listOf(
                        runCatching { sendOnly.read(1.hours) }.exceptionOrNull(),
                        runCatching { receiveOnly.write(bytes(), 1.hours) }.exceptionOrNull(),
                        runCatching { receiveOnly.shutdownSend() }.exceptionOrNull(),
                    )
            }
            pair.runUntil { failures.isNotEmpty() }
            assertEquals(QuicStreamSide.Read, assertIs<QuicStreamDirectionAbsent>(failures[0]).side)
            assertEquals(QuicStreamSide.Write, assertIs<QuicStreamDirectionAbsent>(failures[1]).side)
            assertEquals(QuicStreamSide.Write, assertIs<QuicStreamDirectionAbsent>(failures[2]).side)
            assertTrue(client.end() is EdgeEnd.Open, "a refused call ends nothing")
        }

    /** Runs a hook as quiche makes each stream an open asks for: the zero-length send that creates it. */
    private class OpenHook {
        var onOpen: () -> Unit = {}

        fun around(real: QuicheApi): QuicheApi =
            object : QuicheApi by real {
                override fun connStreamSendStep(
                    conn: QuicheConn,
                    streamId: QuicStreamId,
                    buf: Long,
                    bufLen: Int,
                    fin: Boolean,
                ): StreamSendStep =
                    real.connStreamSendStep(conn, streamId, buf, bufLen, fin).also {
                        if (bufLen == 0 && !fin && it.fold(took = { true }, full = { false }, refused = { _, _ -> false })) onOpen()
                    }
            }
    }

    /** Reads every stream the client opens to send only, to its end, until the connection ends. */
    private fun CoroutineScope.drainEveryReceiveOnly(server: CoreConnection) {
        launch {
            try {
                while (true) {
                    val receive = server.halves.acceptReceiveOnly().receive
                    launch { runCatching { readUntilEnd(receive) } }
                }
            } catch (ended: QuicCloseException) {
                // The pair stopped.
            }
        }
    }

    private suspend fun CoroutineScope.view(
        pair: VirtualPair,
        connection: CoreConnection,
    ): StallView {
        var report: StallReport? = null
        launch { report = connection.stallReport(5.seconds) }
        pair.runUntil { report != null }
        return assertIs<InsideAnswer.Answered>(checkNotNull(report).inside).view
    }

    private suspend fun <S : Any> CoroutineScope.opened(
        pair: VirtualPair,
        open: suspend () -> S,
    ): S {
        var made: S? = null
        launch { made = open() }
        pair.runUntil { made != null }
        return checkNotNull(made)
    }

    private suspend fun sendAll(send: SendHalf) {
        val source = bytes()
        while (source.remaining() > 0) send.write(source, 1.hours)
        send.finish()
    }

    /** Reads to the stream's end, its FIN or the peer's reset, which it returns. */
    private suspend fun readUntilEnd(receive: ReceiveHalf): ReadResult {
        while (true) {
            when (val r = receive.read(1.hours)) {
                is ReadResult.Data -> r.buffer.freeIfNeeded()
                else -> return r
            }
        }
    }

    private fun Result<SendHalf>?.describe(): String =
        when {
            this == null -> "no answer"
            isSuccess -> "stream ${getOrThrow().id}"
            else -> "a failure: ${exceptionOrNull()}"
        }

    private fun StallView.describe(): String {
        val last =
            when (val tail = flight) {
                FlightTail.Empty -> "none"
                is FlightTail.Last -> "${tail.kind} ${tail.ago} ago"
            }
        return "live streams $liveStreams, last flight record $last"
    }

    private fun bytes() =
        BufferFactory.Default.allocate(PAYLOAD).apply {
            repeat(PAYLOAD) { writeByte(it.toByte()) }
            resetForRead()
        }

    private companion object {
        const val PAYLOAD = 2_000
        val CODE = QuicAppErrorCode(0x77L)
    }
}
