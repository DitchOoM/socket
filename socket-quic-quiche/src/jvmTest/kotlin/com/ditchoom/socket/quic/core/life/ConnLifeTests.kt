package com.ditchoom.socket.quic.core.life

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.testing.FlightEvidenceRule
import com.ditchoom.socket.quic.core.testing.nativeBytes
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.Room
import com.ditchoom.socket.quic.core.udp.SendBatch
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnLifeTests {
    @get:Rule
    val flightEvidence = FlightEvidenceRule()

    private val api = StubQuicheApi()
    private val q = QuicheConnRef(api, QuicheConn(1L))
    private val counts = ConnCounts()

    private fun lifeWith(rule: CloseRule = CloseRule.Immediate) = ConnLife(q, Linger(q, rule, BufferFactory.Default, counts))

    private val life = lifeWith()

    @Test
    fun quichesEndIsReadInOrderTimedOutPeerLocalThenNothing() {
        assertSame(ConnEnd.NoErrorRecorded, life.quicheEnd())
        api.localError = QuicError.NoError
        assertEquals(QuicError.NoError, assertIs<ConnEnd.ByLocal>(life.quicheEnd()).error)
        api.peerError = QuicError.ProtocolViolation
        assertIs<ConnEnd.ByPeer>(life.quicheEnd())
        api.timedOut = true
        assertSame(ConnEnd.IdleTimeout, life.quicheEnd())
    }

    @Test
    fun aCloseWeIssuedEndsByLocalEvenWhenThePeersCloseArrivesBeforeQuicheReportsClosed() {
        life.close(QuicError.NoError, ConnEnd.ByLocal(QuicError.NoError), CloseHow.Now, Nanos(0L))
        // The peer's CONNECTION_CLOSE crossed ours: quiche, closing, read it and recorded both errors.
        api.localError = QuicError.NoError
        api.peerError = QuicError.NoError
        api.closed = true
        assertEquals(QuicError.NoError, assertIs<ConnEnd.ByLocal>(life.quicheEnd()).error)
    }

    @Test
    fun aCloseAskedAfterThePeerClosedEndsByPeerAndSendsNothing() {
        api.peerError = QuicError.ProtocolViolation
        life.close(QuicError.NoError, ConnEnd.ByLocal(QuicError.NoError), CloseHow.Now, Nanos(0L))
        assertEquals(QuicError.ProtocolViolation, assertIs<ConnEnd.ByPeer>(assertIs<Life.Closing>(life.view()).end).error)
        assertSame(Wire.Draining, life.linger.wire())
        assertEquals(emptyList(), api.closeErrors, "quiche is draining the peer's close: ours is never asked for")
    }

    @Test
    fun aCloseQuicheDeclinedEndsAsQuicheSays() {
        api.connCloseResult = QUICHE_ERR_DONE
        life.close(QuicError.NoError, ConnEnd.ByLocal(QuicError.NoError), CloseHow.Now, Nanos(0L))
        assertSame(Wire.Draining, life.linger.wire(), "quiche declined: nothing of ours goes out")
    }

    @Test
    fun theEndIsRecordedOnceAndPublished() {
        assertEquals(Ending.First, life.end(ConnEnd.LoopStopped))
        assertEquals(Ending.Already, life.end(ConnEnd.IdleTimeout))
        assertSame(ConnEnd.LoopStopped, assertIs<Life.Ended>(life.view()).end)
    }

    @Test
    fun observeReadsQuichesClosedFlag() {
        assertEquals(LifeStep.Open, life.observe())
        api.closed = true
        assertEquals(LifeStep.Closed, life.observe())
    }

    @Test
    fun aGracefulCloseWaitsForAcknowledgementThenSendsAndAnErrorCloseSendsAtOnce() {
        val graceful = lifeWith(CloseRule.UntilAcknowledged(BOUND))
        api.streamDataUnacknowledged = true
        graceful.close(QuicError.NoError, ConnEnd.ByLocal(QuicError.NoError), CloseHow.Graceful, Nanos(0L))
        assertIs<Wire.Flushing>(graceful.linger.wire())
        graceful.flushStep(Nanos(BOUND - 1))
        assertIs<Wire.Flushing>(graceful.linger.wire(), "data is unacknowledged and the bound has not passed")
        api.streamDataUnacknowledged = false
        graceful.flushStep(Nanos(BOUND - 1))
        assertSame(Wire.Sending, graceful.linger.wire(), "acknowledged: quiche took the close")
        assertEquals(listOf<QuicError>(QuicError.NoError), api.closeErrors)

        val abort = lifeWith(CloseRule.UntilAcknowledged(BOUND))
        api.streamDataUnacknowledged = true
        abort.close(QuicError.ApplicationError(7), ConnEnd.ByLocal(QuicError.ApplicationError(7)), CloseHow.Now, Nanos(0L))
        assertSame(Wire.Sending, abort.linger.wire(), "a close with an error does not wait for anything")
    }

    @Test
    fun aFlushEndsAtItsBoundAndACloseAskedNowCutsIt() {
        val bounded = lifeWith(CloseRule.UntilAcknowledged(BOUND))
        api.streamDataUnacknowledged = true
        bounded.close(QuicError.NoError, ConnEnd.ByLocal(QuicError.NoError), CloseHow.Graceful, Nanos(0L))
        bounded.flushStep(Nanos(BOUND))
        assertSame(Wire.Sending, bounded.linger.wire(), "the bound passed with data unacknowledged")

        val cut = lifeWith(CloseRule.UntilPeerDone(BOUND))
        cut.close(QuicError.NoError, ConnEnd.ByLocal(QuicError.NoError), CloseHow.Graceful, Nanos(0L))
        cut.flushStep(Nanos(1L))
        assertIs<Wire.Flushing>(cut.linger.wire(), "waiting for the peer")
        cut.close(QuicError.ProcessingFailed, ConnEnd.ServerClosed, CloseHow.Now, Nanos(1L))
        assertSame(Wire.Sending, cut.linger.wire())
        assertEquals(QuicError.ProcessingFailed, api.closeErrors.last(), "the close asked now sends its own error")
        assertIs<ConnEnd.ByLocal>(assertIs<Life.Closing>(cut.view()).end, "the end is the first close's")
    }

    @Test
    fun anEndWhileFlushingHasNothingLeftOnTheWire() {
        val flushing = lifeWith(CloseRule.UntilAcknowledged(BOUND))
        api.streamDataUnacknowledged = true
        flushing.close(QuicError.NoError, ConnEnd.ByLocal(QuicError.NoError), CloseHow.Graceful, Nanos(0L))
        flushing.end(ConnEnd.LocalAddressGone)
        assertSame(ConnEnd.LocalAddressGone, assertIs<Life.Ended>(flushing.view()).end)
        assertSame(Wire.Done, flushing.linger.wire())
    }

    @Test
    fun aCloseOutLingersAnsweringLateDatagramsAtEachDoublingAndFreesItsCopyAtTheEnd() {
        life.close(QuicError.NoError, ConnEnd.ByLocal(QuicError.NoError), CloseHow.Now, Nanos(0L))
        val out = SendBatch(nativeBytes(8 * 1500), BatchLimits(8, 1500))
        out.storage.putLong(0, CLOSE_BYTES)
        out.commit(CLOSE_LENGTH, SendTarget.ConnectedPeer)
        life.linger.closeOut(out, 0, CLOSE_LENGTH, SendTarget.ConnectedPeer, Nanos(LINGER))
        assertEquals(Ending.First, life.end(ConnEnd.ByLocal(QuicError.NoError)))
        assertIs<Life.Lingering>(life.view(), "the close is out: the connection lingers with its ids")
        out.took(out.pending)

        repeat(LATE) { life.linger.late() }
        assertEquals(Room.Open, life.linger.answer(out))
        // Answers at the 1st, 2nd, 4th and 8th of 10 late datagrams.
        assertEquals(4, out.pending)
        for (n in 0 until out.pending) {
            assertEquals(CLOSE_LENGTH, out.lengthOf(n))
            assertEquals(CLOSE_BYTES, out.storage.longAt(out.offsetOf(n)), "each answer is the close's own datagram")
        }
        assertEquals(LATE.toLong(), counts.lateDatagrams)
        assertEquals(4L, counts.closesResent)

        assertTrue(!life.linger.due(Nanos(LINGER - 1)))
        assertTrue(life.linger.due(Nanos(LINGER)))
        life.settle()
        assertIs<Life.Ended>(life.view())
        assertSame(Wire.Done, life.linger.wire())
    }

    private companion object {
        const val QUICHE_ERR_DONE = -1
        const val BOUND = 1_000_000_000L
        const val LINGER = 180_000_000L
        const val CLOSE_LENGTH = 48
        const val CLOSE_BYTES = 0x0102030405060708L
        const val LATE = 10
    }
}
