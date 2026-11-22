package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.demux.BoundedIo
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.core.stream.Retry
import com.ditchoom.socket.quic.core.stream.StreamKind
import com.ditchoom.socket.quic.core.stream.StreamTable
import com.ditchoom.socket.quic.core.stream.Waiter
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.testing.nativeBytes
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.trace.QuicTraceRecorder
import com.ditchoom.socket.quic.trace.TraceCapture
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds

/**
 * A connection's close over a scripted quiche, a recording socket and virtual time: the end is published, and every
 * waiting operation told, in the pass its CONNECTION_CLOSE leaves; the connection then lingers for quiche's draining
 * time, answering late datagrams with that same datagram at each doubling of their count and handing none to quiche;
 * once the linger is over its socket closes.
 */
class ConnCloseTests {
    private val api =
        StubQuicheApi().apply {
            emitOneDatagramOnClose = true
            connTimeout = DRAIN.nanoseconds
        }
    private val io = BoundedIo(Int.MAX_VALUE)
    private val rig = ConnRig(api, io = io)

    @Test
    fun theEndIsPublishedThenQueuedWorkThenParkedOperationsAreToldInThePassTheCloseLeaves() {
        rig.pass(0)
        val order = ArrayList<String>()
        rig.conn.submit(
            object : ConnWork() {
                override fun run(inside: Inside) =
                    inside.streams.opens.lane(StreamKind.Bidirectional).wait(
                        object : Waiter {
                            override fun retry(table: StreamTable): Retry = Retry.Again

                            override fun connectionEnded(end: ConnEnd) {
                                order += "parked after ${rig.sink.ended.size} published end(s): ${end::class.simpleName}"
                            }
                        },
                    )

                override fun gone(end: ConnEnd) = Unit
            },
        )
        rig.pass(0)
        rig.conn.submit(CloseWork(QuicError.NoError))
        rig.pass(AT)
        assertEquals(1, io.sent.size, "the CONNECTION_CLOSE left in the pass the close was asked in")
        assertEquals(listOf<ConnEnd>(ConnEnd.ByLocal(QuicError.NoError)).map { it::class }, rig.sink.ended.map { it::class })
        assertEquals(listOf("parked after 1 published end(s): ByLocal"), order)
        assertIs<Life.Lingering>(rig.conn.life(), "the connection keeps its ids for the linger")

        val goneWith = ArrayList<ConnEnd>()
        rig.conn.submit(
            object : ConnWork() {
                override fun run(inside: Inside) = Unit

                override fun gone(end: ConnEnd) {
                    goneWith += end
                }
            },
        )
        assertIs<ConnEnd.ByLocal>(goneWith.single(), "work handed over after the end is told it at once")
    }

    @Test
    fun aLingeringConnectionAnswersLateDatagramsWithItsCloseAtEachDoublingAndHandsNoneToQuiche() {
        rig.pass(0)
        rig.conn.submit(CloseWork(QuicError.NoError))
        rig.pass(AT)
        val close = io.sent.single().bytes
        val arrivals = api.recvArrivals.size
        for (n in 1..LATE) {
            rig.conn.received(nativeBytes(DATAGRAM), DATAGRAM, PEER, Nanos(AT + n))
            rig.pass(AT + n)
        }
        // The 1st, 2nd, 4th and 8th of ten late datagrams are answered.
        assertEquals(1 + 4, io.sent.size)
        for (answer in io.sent.drop(1)) assertContentEquals(close, answer.bytes, "each answer is the CONNECTION_CLOSE itself")
        assertEquals(arrivals, api.recvArrivals.size, "no late datagram reached quiche")
        assertEquals(LATE.toLong(), rig.counts.lateDatagrams)
        assertEquals(4L, rig.counts.closesResent)
    }

    @Test
    fun aLingeringConnectionsRecordingHoldsEveryLateDatagramAndEveryAnswerItSent() {
        val recorded = CopyOnWriteArrayList<TraceEvent>()
        val traced =
            ConnRig(
                StubQuicheApi().apply {
                    emitOneDatagramOnClose = true
                    connTimeout = DRAIN.nanoseconds
                },
                io = BoundedIo(Int.MAX_VALUE),
                capture = TraceCapture.On(QuicTraceRecorder(TraceSink { recorded += it })),
            )
        val sent = traced.io as BoundedIo
        traced.pass(0)
        traced.conn.submit(CloseWork(QuicError.NoError))
        traced.pass(AT)
        for (n in 1..LATE) {
            traced.conn.received(nativeBytes(DATAGRAM), DATAGRAM, PEER, Nanos(AT + n))
            traced.pass(AT + n)
        }
        assertEquals(1 + 4, sent.sent.size)
        assertEquals(sent.sent.size, recorded.count { it is TraceEvent.DgramOut }, "every datagram the connection sent")
        assertEquals(LATE, recorded.count { it is TraceEvent.DgramIn }, "every datagram that arrived after the end")
    }

    @Test
    fun theLingerEndsAtQuichesDrainingTimeThroughAnIdleTimeoutAndThenTheSocketCloses() {
        rig.pass(0)
        rig.conn.submit(CloseWork(QuicError.NoError))
        val next = rig.pass(AT)
        val wake = next.fold({ Long.MAX_VALUE }, { it })
        kotlin.test.assertTrue(wake <= AT + DRAIN, "the loop wakes by the linger's end, at $wake")
        // quiche's idle timer cannot end a connection that is draining; were it to report one, the end stays the close's.
        api.timedOut = true
        api.closed = true
        // The repeats fall due one and two probe timeouts after the close: a third of quiche's draining time each.
        rig.pass(AT + DRAIN / 3)
        rig.pass(AT + 2 * DRAIN / 3)
        rig.pass(AT + DRAIN - 1)
        assertIs<Life.Lingering>(rig.conn.life())
        val lingered = io.events.toList()
        assertEquals(
            listOf(BoundedIo.SENT, BoundedIo.SENT, BoundedIo.SENT),
            lingered,
            "the socket stays open while the connection lingers, sending its close and its two repeats",
        )
        rig.pass(AT + DRAIN)
        assertIs<Life.Ended>(rig.conn.life())
        assertSame(ConnEnd.ByLocal::class, rig.sink.ended.single()::class, "the end was recorded once, as the close's")
        assertEquals(lingered + BoundedIo.CLOSED, io.events.toList(), "the socket closed once the linger was over, sending nothing more")
    }

    @Test
    fun aCloseThatQuicheEndsBeforeAnyDatagramEndsWithoutALinger() {
        api.emitOneDatagramOnClose = false
        api.closesOnLocalClose = true
        rig.pass(0)
        rig.conn.submit(CloseWork(QuicError.NoError))
        rig.pass(AT)
        assertIs<Life.Ended>(rig.conn.life(), "nothing was sent: nothing lingers")
        assertIs<ConnEnd.ByLocal>(rig.sink.ended.single())
        assertEquals(listOf(BoundedIo.CLOSED), io.events.toList())
    }

    @Test
    fun aGracefulCloseHoldsItsConnectionCloseUntilWhatWasWrittenIsAcknowledged() {
        api.streamDataUnacknowledged = true
        rig.pass(0)
        rig.conn.submit(CloseWork(QuicError.NoError))
        rig.pass(AT)
        assertEquals(0, io.sent.size, "data is unacknowledged: the close waits")
        assertIs<Life.Closing>(rig.conn.life(), "callers may still use the connection while it flushes")
        assertEquals(emptyList(), rig.sink.ended)
        api.streamDataUnacknowledged = false
        rig.conn.received(nativeBytes(DATAGRAM), DATAGRAM, PEER, Nanos(AT + 1))
        rig.pass(AT + 1)
        assertEquals(1, io.sent.size, "acknowledged: the close went out in that pass")
        assertIs<ConnEnd.ByLocal>(rig.sink.ended.single())
    }

    @Test
    fun aCloseWithAnErrorDoesNotWaitForAcknowledgement() {
        api.streamDataUnacknowledged = true
        rig.pass(0)
        rig.conn.submit(CloseWork(QuicError.ApplicationError(APP_CODE)))
        rig.pass(AT)
        assertEquals(1, io.sent.size)
        assertEquals(QuicError.ApplicationError(APP_CODE), assertIs<ConnEnd.ByLocal>(rig.sink.ended.single()).error)
    }

    /** What the edge's close hands the connection. */
    private class CloseWork(
        private val error: QuicError,
    ) : ConnWork() {
        override fun run(inside: Inside) = inside.close(error)

        override fun gone(end: ConnEnd) = Unit
    }

    private companion object {
        const val AT = 5_000_000L
        const val DRAIN = 180_000_000L
        const val DATAGRAM = 64
        const val LATE = 10
        const val APP_CODE = 7L
        val PEER = PathKey(4, 443, 0L, 0x7F000001L)
    }
}
