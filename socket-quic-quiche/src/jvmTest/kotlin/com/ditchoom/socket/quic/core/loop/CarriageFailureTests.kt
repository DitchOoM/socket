package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.ready.SocketRef
import com.ditchoom.socket.quic.core.ready.SocketRefs
import com.ditchoom.socket.quic.core.sched.CallerLanes
import com.ditchoom.socket.quic.core.sched.CallerSend
import com.ditchoom.socket.quic.core.sched.LaneTake
import com.ditchoom.socket.quic.core.sched.SendPort
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.testing.CoreTestRules
import com.ditchoom.socket.quic.core.testing.FakeIo
import com.ditchoom.socket.quic.core.testing.RecordingSink
import com.ditchoom.socket.quic.core.testing.RecordingWork
import com.ditchoom.socket.quic.core.testing.nativeBytes
import com.ditchoom.socket.quic.core.testing.testConn
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.Beside
import com.ditchoom.socket.quic.core.udp.Reopened
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SocketTicket
import com.ditchoom.socket.quic.core.udp.Thrown
import com.ditchoom.socket.quic.trace.TraceCapture
import kotlinx.coroutines.Runnable
import java.io.IOException
import java.nio.channels.DatagramChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What one socket's io failing reaches: a second socket's failure ends at most its move, a carrying socket's failure
 * ends at most its own connection, and a socket that cannot carry any more is still closed and its lanes shut.
 */
class CarriageFailureTests {
    @Test
    fun aSecondSocketsReadThatThrowsEndsOnlyItsMove() {
        val io = FakeIo().apply { besideAnswer = Beside.Opened(LOCAL) }
        val rig = ConnRig(io = io)
        rig.pass(0)
        rig.loop.move(rig.endpoint, ASKED, BIND, RttPrior.CONFIGURED)
        rig.pass(1)
        io.secondReceiveThrows = IOException("second socket gone")
        rig.endpoint.readable(Via.Probing)
        for (at in 2L..6L) rig.pass(at)
        assertEquals(emptyList(), rig.sink.ended, "a second socket's read that threw ended the connection")
        assertEquals(1, io.secondsClosed, "the second socket that threw was not closed")
    }

    @Test
    fun aSecondSocketBeingMadeThatThrowsEndsOnlyItsMove() {
        val io =
            FakeIo().apply {
                besideAnswer = Beside.Arriving
                settleAnswer = Beside.Arriving
            }
        val rig = ConnRig(io = io)
        rig.pass(0)
        rig.loop.move(rig.endpoint, ASKED, BIND, RttPrior.CONFIGURED)
        rig.pass(1)
        io.settleThrows = IOException("second socket could not be made")
        for (at in 2L..6L) rig.pass(at)
        assertEquals(emptyList(), rig.sink.ended, "a second socket that threw while being made ended the connection")
        assertEquals(1, io.secondsClosed, "the second socket that threw while being made was not closed")
    }

    /** The close of the second socket throws as the carrying one dies: that connection ends, and its loop serves on. */
    @Test
    fun aCloseThatThrowsAsTheCarryingSocketDiesEndsOnlyItsConnection() {
        val io = FakeIo().apply { besideAnswer = Beside.Opened(LOCAL) }
        val rig = ConnRig(io = io)
        val other = Neighbour(rig)
        rig.pass(0)
        rig.loop.move(rig.endpoint, ASKED, BIND, RttPrior.CONFIGURED)
        rig.pass(1)
        io.closeSecondThrows = IOException("second socket's close failed")
        io.receiveThrows = IOException("carrying socket gone")
        rig.endpoint.readable(Via.Carrying)
        for (at in 2L..20L) rig.pass(at)
        assertIs<ConnEnd.SocketFailed>(rig.sink.ended.single(), "the failing endpoint's connection: ${rig.sink.ended}")
        assertEquals(emptyList(), other.sink.ended, "a neighbour on the loop ended with the failing socket")
        assertTrue(other.conn.life() !is Life.Ended, "the loop stopped serving the neighbour")
    }

    @Test
    fun aRefusedCarryingReopenClosesTheSocketAndShutsItsLanes() {
        val io = FakeIo().apply { reopenAnswer = Reopened.Refused(Thrown(IOException("no network"))) }
        val rig = ConnRig(io = io, lanes = lanes())
        rig.pass(0)
        rig.loop.rehome(rig.endpoint)
        for (at in 1L..20L) rig.pass(at)
        assertIs<ConnEnd.SocketFailed>(rig.sink.ended.single())
        assertEquals(1, io.closes, "the io of a socket that could not be reopened was not closed")
        assertIs<LaneTake.None>(rig.socket.takeLane(), "the socket that could not be reopened still lends lanes")
    }

    /** A dead socket stays while a lane is out: it is read, opened beside and sent on by nobody, and then leaves clean. */
    @Test
    fun aDeadSocketHoldingALaneReadsOpensAndSendsNothingThenLeavesClean() {
        val poller = TokenCountingPoller()
        val io = FakeIo().apply { besideAnswer = Beside.Opened(LOCAL) }
        val rig = ConnRig(io = io, poller = poller, lanes = lanes())
        rig.pass(0)
        rig.loop.move(rig.endpoint, ASKED, BIND, RttPrior.CONFIGURED)
        rig.pass(1)
        val lane = assertIs<CallerSend>(rig.socket.takeLane(), "precondition: a lane is lent")
        io.receiveThrows = IOException("carrying socket gone")
        rig.endpoint.readable(Via.Carrying)
        rig.pass(2)
        assertIs<ConnEnd.SocketFailed>(rig.sink.ended.single(), "precondition: the carrying socket died")
        io.receiveThrows = null
        val reads = io.receives + io.secondReceives
        val sends = io.sendCalls
        for (at in 3L..10L) {
            rig.loop.move(rig.endpoint, ASKED.next(), BIND, RttPrior.CONFIGURED)
            rig.endpoint.readable(Via.Carrying)
            rig.endpoint.readable(Via.Probing)
            rig.pass(at * 1_000_000L)
        }
        assertEquals(reads, io.receives + io.secondReceives, "a dead socket was read")
        assertEquals(listOf(ASKED), io.openedAs, "a socket was opened beside a dead one")
        assertEquals(sends, io.sendCalls, "a dead socket was sent on")
        assertEquals(0, io.closes, "precondition: the socket waits for its lane")
        rig.socket.returnLane(lane)
        for (at in 11L..30L) rig.pass(at * 1_000_000L)
        assertEquals(1, io.closes, "the dead socket was not closed once its lane came back")
        for (token in poller.added.toSet()) {
            assertEquals(poller.added.count { it == token }, poller.removed.count { it == token }, "token $token: ${poller.added} ${poller.removed}")
        }
    }

    /** A second client connection on [rig]'s loop. */
    private class Neighbour(
        rig: ConnRig,
    ) {
        val sink = RecordingSink()
        private val io = FakeIo()
        private val socket = SendPort(io, rig.loop.port, CallerLanes.None, RecordingWork(), rig.loop.counts, rig.loop.callerCounts)
        val conn =
            testConn(StubQuicheApi(), CoreTestRules, SendTarget.ConnectedPeer, TraceCapture.Off, rig.loop, socket, rig.clock, sink, ConnCounts())

        init {
            val endpoint =
                Endpoint(
                    SocketRefs(SocketRef(DatagramChannel.open()), SocketRef(DatagramChannel.open())),
                    io,
                    socket,
                    Route.Fixed(conn, OwnBatch(SendBatch(nativeBytes(8 * 1500), BatchLimits(8, 1500)))),
                    Runnable { },
                )
            rig.loop.attach(endpoint)
            conn.start()
        }
    }

    private companion object {
        val ASKED = SocketTicket.FIRST.next()
        val BIND = PathKey(4, 0, 0L, 0L)
        val LOCAL = PathKey(4, 50_001, 0L, 0x7F000001L)

        fun lanes() = CallerLanes.Of(FixedPool(1) { CallerSend(it, SendBatch(nativeBytes(8 * 1500), BatchLimits(8, 1500), lane = 1)) })
    }
}
