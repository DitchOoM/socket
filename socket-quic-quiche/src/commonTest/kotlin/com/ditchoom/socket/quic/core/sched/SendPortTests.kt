package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.core.observe.CallerCounts
import com.ditchoom.socket.quic.core.observe.LoopCounts
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.testing.FakeIo
import com.ditchoom.socket.quic.core.testing.FakePoller
import com.ditchoom.socket.quic.core.testing.Limits
import com.ditchoom.socket.quic.core.testing.RecordingWork
import com.ditchoom.socket.quic.core.testing.batchWith
import com.ditchoom.socket.quic.core.testing.nativeBytes
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.core.udp.SocketErrors
import com.ditchoom.socket.quic.core.udp.SocketFault
import com.ditchoom.socket.quic.core.udp.SocketProbe
import com.ditchoom.socket.quic.core.udp.Thrown
import com.ditchoom.socket.quic.core.udp.UdpError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/** [SendPort] classifies every refusal the way design section 8.3 says, on every path, against a scripted io. */
class SendPortTests {
    private val io = FakeIo()
    private val port = LoopPort(FakePoller())
    private val died = RecordingWork()
    private val loopCounts = LoopCounts()
    private val callerCounts = CallerCounts()
    private val lanes =
        CallerLanes.Of(
            FixedPool(2) {
                CallerSend(
                    it,
                    SendBatch(
                        nativeBytes(Limits.maxDatagrams * Limits.maxDatagramSize),
                        Limits,
                        it + 1,
                    ),
                )
            },
        )
    private val socket = SendPort(io, port, lanes, died, loopCounts, callerCounts)
    private val unnamed = UdpError.Unnamed(Thrown(IllegalStateException()))

    private fun deadFault(): SocketFault = assertIs<SocketHealth.Dead>(socket.health()).fault

    @Test
    fun everythingTakenIsSent() {
        val batch = batchWith(3)
        assertEquals(Flushed.Sent, socket.flush(batch))
        assertEquals(0, batch.pending)
        assertEquals(3L, loopCounts.datagramsOut)
        assertSame(SocketHealth.Live, socket.health())
    }

    @Test
    fun aFullSocketKeepsTheRestAndAsksForWritability() {
        val batch = batchWith(4)
        io.onSend = { SendBatchResult.Partial(1, UdpError.WouldBlock) }
        assertEquals(Flushed.Full, socket.flush(batch))
        assertEquals(3, batch.pending)
        assertEquals(1L, loopCounts.datagramsOut)
        assertSame(SocketHealth.Live, socket.health())
    }

    @Test
    fun aDatagramsOwnErrorDropsThatDatagramAndSendsTheRest() {
        val batch = batchWith(3)
        var first = true
        io.onSend = { pending ->
            if (first) {
                first = false
                SendBatchResult.Partial(1, UdpError.PortUnreachable)
            } else {
                assertEquals(1, pending)
                SendBatchResult.AllTaken
            }
        }
        assertEquals(Flushed.Sent, socket.flush(batch))
        assertEquals(0, batch.pending)
        assertSame(SocketHealth.Live, socket.health())
    }

    /** The Android case: an unnamed platform error while the channel reports closed. */
    @Test
    fun anUnnamedErrorOnAClosedSocketEndsItOnTheLoopsFlush() {
        io.probeAnswer = SocketProbe.Closed
        io.onSend = { SendBatchResult.Partial(0, unnamed) }
        val batch = batchWith(2)
        assertEquals(Flushed.Dead, socket.flush(batch))
        assertEquals(0, batch.pending)
        assertSame(unnamed.thrown, assertIs<SocketFault.Unnamed>(deadFault()).thrown)
    }

    @Test
    fun theSameFaultOnACallersInPlaceSendEndsTheSocketToo() {
        io.probeAnswer = SocketProbe.Closed
        io.onSend = { SendBatchResult.Partial(0, unnamed) }
        val lane = assertIs<CallerSend>(socket.takeLane())
        lane.batch.commit(100, com.ditchoom.socket.quic.SendTarget.ConnectedPeer)
        assertEquals(LaneSent.SocketDied, socket.sendInPlace(lane))
        assertIs<SocketHealth.Dead>(socket.health())
    }

    @Test
    fun theSameFaultOnAReceiveEndsTheSocketToo() {
        io.probeAnswer = SocketProbe.Closed
        socket.receiveFailed(unnamed)
        assertIs<SocketFault.Unnamed>(deadFault())
        assertEquals(1L, loopCounts.receiveFailures)
    }

    @Test
    fun aClosedSocketIsTheEndWhateverTheProbeSays() {
        io.onSend = { SendBatchResult.Partial(0, UdpError.SocketClosed) }
        assertEquals(Flushed.Dead, socket.flush(batchWith(1)))
        assertSame(SocketFault.Closed, deadFault())
    }

    @Test
    fun eightUnnamedRefusalsOnAnOpenSocketEndItAndSevenDoNot() {
        io.onSend = { SendBatchResult.Partial(0, unnamed) }
        repeat(SocketErrors.REPEATED_LIMIT - 1) { assertEquals(Flushed.Sent, socket.flush(batchWith(1))) }
        assertSame(SocketHealth.Live, socket.health())
        assertEquals(Flushed.Dead, socket.flush(batchWith(1)))
        assertEquals(SocketErrors.REPEATED_LIMIT, assertIs<SocketFault.Repeated>(deadFault()).count)
    }

    @Test
    fun aDatagramTakenInBetweenBreaksTheRun() {
        io.onSend = { SendBatchResult.Partial(0, unnamed) }
        repeat(SocketErrors.REPEATED_LIMIT - 1) { socket.flush(batchWith(1)) }
        io.onSend = { SendBatchResult.AllTaken }
        socket.flush(batchWith(1))
        io.onSend = { SendBatchResult.Partial(0, unnamed) }
        repeat(SocketErrors.REPEATED_LIMIT - 1) { socket.flush(batchWith(1)) }
        assertSame(SocketHealth.Live, socket.health())
    }

    @Test
    fun theLoopIsToldOnceHoweverManyPathsSeeTheSocketDie() {
        io.onSend = { SendBatchResult.Partial(0, UdpError.SocketClosed) }
        socket.flush(batchWith(1))
        socket.flush(batchWith(1))
        socket.receiveFailed(UdpError.SocketClosed)
        port.runWork()
        assertEquals(1, died.runs)
    }

    @Test
    fun aFullSocketHandsTheCallersLaneToTheLoopWhichFinishesIt() {
        val lane = assertIs<CallerSend>(socket.takeLane())
        repeat(3) { lane.batch.commit(100, com.ditchoom.socket.quic.SendTarget.ConnectedPeer) }
        io.onSend = { SendBatchResult.Partial(1, UdpError.WouldBlock) }
        assertEquals(LaneSent.Deferred, socket.sendInPlace(lane))
        assertEquals(1L, callerCounts.datagramsInPlace)
        assertEquals(1L, callerCounts.sendsDeferred)
        assertEquals(1, lanes.pool.available())

        port.runWork()
        assertEquals(true, socket.hasWaiting())
        io.onSend = { SendBatchResult.AllTaken }
        assertEquals(Flushed.Sent, socket.flushWaiting())
        assertEquals(false, socket.hasWaiting())
        assertEquals(2, lanes.pool.available())
        assertEquals(0, lane.batch.pending)
    }
}
