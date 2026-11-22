package com.ditchoom.socket.quic.core.life

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.testing.nativeBytes
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WakeSink
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.Room
import com.ditchoom.socket.quic.core.udp.SendBatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnLifeTests {
    private val api = StubQuicheApi()
    private val q = QuicheConnRef(api, QuicheConn(1L))
    private val counts = ConnCounts()

    private fun lifeWith(noError: CloseChoice = NOW) = ConnLife(q, Linger(BufferFactory.Default, counts), noError)

    private val life = lifeWith()

    /** The ends the close point was told, in order. */
    private val points = ArrayList<ConnEnd>()

    private fun ConnLife.close(
        choice: CloseChoice,
        end: ConnEnd,
        now: Nanos,
    ) = close(choice, end, now) { points += it }

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
        life.close(NOW, BY_LOCAL, Nanos(0L))
        // The peer's CONNECTION_CLOSE crossed ours: quiche, closing, read it and recorded both errors.
        api.localError = QuicError.NoError
        api.peerError = QuicError.NoError
        api.closed = true
        assertEquals(QuicError.NoError, assertIs<ConnEnd.ByLocal>(life.quicheEnd()).error)
    }

    @Test
    fun aCloseAskedAfterThePeerClosedEndsByPeerAndSendsNothing() {
        api.peerError = QuicError.ProtocolViolation
        life.close(NOW, BY_LOCAL, Nanos(0L))
        assertEquals(QuicError.ProtocolViolation, assertIs<ConnEnd.ByPeer>(endOf(assertIs<Life.Closing>(life.view()))).error)
        assertSame(Wire.Draining, life.linger.wire())
        assertEquals(emptyList(), api.closeErrors, "quiche is draining the peer's close: ours is never asked for")
        assertIs<CloseOutcome.EndedFirst>(assertIs<Ending.First>(life.end(ConnEnd.ByPeer(QuicError.ProtocolViolation))).outcome)
    }

    @Test
    fun aCloseQuicheDeclinedEndsAsQuicheSays() {
        api.connCloseResult = QUICHE_ERR_DONE
        life.close(NOW, BY_LOCAL, Nanos(0L))
        assertSame(Wire.Draining, life.linger.wire(), "quiche declined: nothing of ours goes out")
    }

    @Test
    fun theEndIsRecordedOnceAndPublished() {
        assertSame(CloseOutcome.NotAsked, assertIs<Ending.First>(life.end(ConnEnd.LoopStopped)).outcome, "no close was asked")
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
    fun observeReportsTheDrainOfThePeersCloseBeforeQuicheIsClosed() {
        api.peerError = QuicError.ApplicationError(3)
        assertEquals(LifeStep.Open, life.observe(), "no datagram reached quiche: it is not asked")
        life.heard()
        assertEquals(LifeStep.Draining, life.observe(), "the peer's CONNECTION_CLOSE arrived: quiche drains")
        life.drains(Nanos(LINGER))
        assertIs<Ending.First>(life.end(ConnEnd.ByPeer(QuicError.ApplicationError(3))))
        assertIs<Life.Lingering>(life.view(), "the ids stay for quiche's draining time")
        assertEquals(Holding.Ids, life.linger.holds())
        life.linger.late()
        assertEquals(
            0,
            SendBatch(nativeBytes(1500), BatchLimits(1, 1500))
                .also {
                    life.linger.answer(it)
                }.pending,
            "a draining endpoint sends nothing",
        )
        assertTrue(!life.linger.due(Nanos(LINGER - 1)))
        assertTrue(life.linger.due(Nanos(LINGER)))
    }

    @Test
    fun aGracefulCloseWaitsForAcknowledgementThenSendsAndAnErrorCloseSendsAtOnce() {
        val graceful = lifeWith(GRACEFUL)
        api.streamDataUnacknowledged = true
        graceful.close(GRACEFUL, BY_LOCAL, Nanos(0L))
        assertIs<Life.Flushing>(graceful.view(), "past the close point, flushing")
        graceful.flushStep(Nanos(LIMIT - 1))
        assertIs<Life.Flushing>(graceful.view(), "data is unacknowledged and the limit has not passed")
        api.streamDataUnacknowledged = false
        graceful.flushStep(Nanos(LIMIT - 1))
        assertSame(Wire.Sending, graceful.linger.wire(), "acknowledged: quiche took the close")
        assertSame(CloseOutcome.Drained, assertIs<Life.Handed>(graceful.view()).outcome)
        assertEquals(listOf<QuicError>(QuicError.NoError), api.closeErrors)

        val abort = lifeWith(GRACEFUL)
        api.streamDataUnacknowledged = true
        abort.close(CloseChoice.Immediate(QuicError.ApplicationError(7)), ConnEnd.ByLocal(QuicError.ApplicationError(7)), Nanos(0L))
        assertSame(Wire.Sending, abort.linger.wire(), "a close with an error does not wait for anything")
        assertSame(CloseOutcome.Immediate, assertIs<Life.Handed>(abort.view()).outcome)
    }

    @Test
    fun aFlushEndsAtItsLimitAndACloseAskedNowCutsIt() {
        val limited = lifeWith(GRACEFUL)
        api.streamDataUnacknowledged = true
        limited.close(GRACEFUL, BY_LOCAL, Nanos(0L))
        limited.flushStep(Nanos(LIMIT))
        assertSame(Wire.Sending, limited.linger.wire(), "the limit passed with data unacknowledged")
        assertSame(CloseOutcome.LimitReached, assertIs<Life.Handed>(limited.view()).outcome)

        val peerDone = CloseChoice.Graceful(LIMIT, DrainUntil.PeerDone)
        val cut = lifeWith(peerDone)
        cut.close(peerDone, BY_LOCAL, Nanos(0L))
        cut.flushStep(Nanos(1L))
        assertIs<Life.Flushing>(cut.view(), "waiting for the peer")
        cut.close(CloseChoice.Immediate(QuicError.ProcessingFailed), ConnEnd.ServerClosed, Nanos(1L))
        assertSame(Wire.Sending, cut.linger.wire())
        assertEquals(QuicError.ProcessingFailed, api.closeErrors.last(), "the close asked now sends its own error")
        val handed = assertIs<Life.Handed>(cut.view())
        assertIs<ConnEnd.ByLocal>(handed.end, "the end is the first close's")
        assertSame(CloseOutcome.Immediate, handed.outcome)
    }

    @Test
    fun aFlushWaitingForThePeerIsDrainedByThePeersClose() {
        val peerDone = CloseChoice.Graceful(LIMIT, DrainUntil.PeerDone)
        val waiting = lifeWith(peerDone)
        waiting.close(peerDone, BY_LOCAL, Nanos(0L))
        api.peerError = QuicError.NoError
        waiting.drains(Nanos(LINGER))
        assertSame(CloseOutcome.Drained, assertIs<Life.Handed>(waiting.view()).outcome)
        assertIs<Wire.Silent>(waiting.linger.wire(), "the ids stay through the drain")
    }

    @Test
    fun aFlushWaitingForAcknowledgementThatThePeersCloseEndsIsNotDrained() {
        val graceful = lifeWith(GRACEFUL)
        api.streamDataUnacknowledged = true
        graceful.close(GRACEFUL, BY_LOCAL, Nanos(0L))
        api.peerError = QuicError.ApplicationError(2)
        graceful.drains(Nanos(LINGER))
        // quiche then reports everything acknowledged: our close was never sent, so the flush did not drain.
        api.streamDataUnacknowledged = false
        graceful.flushStep(Nanos(1L))
        val outcome = assertIs<CloseOutcome.EndedFirst>(assertIs<Life.Handed>(graceful.view()).outcome)
        assertEquals(QuicError.ApplicationError(2), assertIs<ConnEnd.ByPeer>(outcome.end).error)
        assertEquals(emptyList(), api.closeErrors, "nothing of ours goes to a draining quiche")
        assertIs<Wire.Silent>(graceful.linger.wire())
        assertIs<CloseOutcome.EndedFirst>(assertIs<Ending.First>(graceful.end(BY_LOCAL)).outcome)
        assertIs<Life.Lingering>(graceful.view(), "the ids stay through the drain, not released at once")
    }

    @Test
    fun theClosePointIsToldOnceWithTheEndItsCloseAsked() {
        val graceful = lifeWith(GRACEFUL)
        graceful.close(GRACEFUL, BY_LOCAL, Nanos(0L))
        graceful.close(CloseChoice.Immediate(QuicError.ApplicationError(1)), ConnEnd.ServerClosed, Nanos(1L))
        assertEquals(listOf<ConnEnd>(BY_LOCAL), points)
    }

    @Test
    fun aFlushOffersItsLimitAsTheNextWake() {
        val graceful = lifeWith(GRACEFUL)
        api.streamDataUnacknowledged = true
        graceful.close(GRACEFUL, BY_LOCAL, Nanos(5L))
        val sink = WakeSink()
        graceful.offerWake(sink)
        assertEquals(Nanos(5L + LIMIT), sink.earliest())
    }

    @Test
    fun anEndWhileFlushingHandsQuicheTheCloseSoTheNextServeSendsIt() {
        val flushing = lifeWith(GRACEFUL)
        api.streamDataUnacknowledged = true
        flushing.close(GRACEFUL, BY_LOCAL, Nanos(0L))
        val outcome = assertIs<CloseOutcome.EndedFirst>(assertIs<Ending.First>(flushing.end(ConnEnd.LocalAddressGone)).outcome)
        assertSame(ConnEnd.LocalAddressGone, outcome.end)
        assertSame(ConnEnd.LocalAddressGone, assertIs<Life.Lingering>(flushing.view()).end)
        assertSame(Wire.Sending, flushing.linger.wire())
        assertEquals(listOf<QuicError>(QuicError.NoError), api.closeErrors)
    }

    @Test
    fun aCloseOutLingersAnsweringLateDatagramsAtEachDoublingAndFreesItsCopyAtTheEnd() {
        life.close(NOW, BY_LOCAL, Nanos(0L))
        val out = SendBatch(nativeBytes(8 * 1500), BatchLimits(8, 1500))
        out.storage.putLong(0, CLOSE_BYTES)
        out.commit(CLOSE_LENGTH, SendTarget.ConnectedPeer)
        life.linger.closeOut(out, 0, CLOSE_LENGTH, SendTarget.ConnectedPeer, Nanos(LINGER))
        assertIs<Ending.First>(life.end(ConnEnd.ByLocal(QuicError.NoError)))
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
        const val LIMIT = 1_000_000_000L
        const val LINGER = 180_000_000L
        const val CLOSE_LENGTH = 48
        const val CLOSE_BYTES = 0x0102030405060708L
        const val LATE = 10
        val NOW = CloseChoice.Immediate(QuicError.NoError)
        val GRACEFUL = CloseChoice.Graceful(LIMIT, DrainUntil.Acknowledged)
        val BY_LOCAL = ConnEnd.ByLocal(QuicError.NoError)
    }
}
