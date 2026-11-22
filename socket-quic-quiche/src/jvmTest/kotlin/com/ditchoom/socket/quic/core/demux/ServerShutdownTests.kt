package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicCloseReason
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.demux.BoundedIo.Companion.CLOSED
import com.ditchoom.socket.quic.core.demux.BoundedIo.Companion.SENT
import com.ditchoom.socket.quic.core.demux.ServerRig.Companion.initial
import com.ditchoom.socket.quic.core.demux.ServerRig.Companion.peer
import com.ditchoom.socket.quic.core.edge.closeReasonOf
import com.ditchoom.socket.quic.core.edge.endedWith
import com.ditchoom.socket.quic.core.life.CloseChoice
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.sched.SleepPlan
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A server's shutdown while a caller's thread is inside one of its connections: the server stops, and its socket
 * closes, only after that connection's CONNECTION_CLOSE went out. The test thread is the loop; virtual time.
 */
class ServerShutdownTests {
    @Test
    fun theSocketClosesOnlyAfterTheCloseOfAConnectionACallerHeldWentOut() {
        val (rig, conn) = serverWithOnePeer()
        val holder = HoldWork()
        val caller = holdAsCaller(conn, holder)

        rig.demux.beginShutdown()
        rig.pass()
        rig.pass()
        assertNotEquals(ServerPhase.Stopped, rig.demux.phase(), "a caller is inside: its connection's close has not gone out")
        assertEquals(emptyList(), rig.io.events, "nothing was sent and the socket is open")

        holder.release.countDown()
        caller.join(5_000)
        rig.pass()
        assertEquals(ServerPhase.Stopped, rig.demux.phase())
        assertEquals(listOf(SENT, CLOSED), rig.io.events.toList(), "the CONNECTION_CLOSE went out, then the socket closed")
        assertEquals<List<ConnEnd>>(listOf(ConnEnd.ServerClosed), rig.sinks.single().ended)
    }

    @Test
    fun whileACallerHoldsAClosingConnectionTheLoopSleepsUntilTheBoundAndTheCallerLeavingWakesIt() {
        val (rig, conn) = serverWithOnePeer()
        val holder = HoldWork()
        val caller = holdAsCaller(conn, holder)

        rig.demux.beginShutdown()
        val next = rig.pass()
        assertEquals(DRAIN_BOUND, next.fold({ -1L }, { it }), "the loop's next wake is the drain bound")
        assertEquals(SleepPlan.Await, rig.loop.port.beginSleep(next), "nothing is owed: the loop sleeps, it does not spin")
        val wakesBefore = rig.poller.wakes

        holder.release.countDown()
        caller.join(5_000)
        assertEquals(wakesBefore + 1, rig.poller.wakes, "the caller that sent the close woke the sleeping loop")
        rig.loop.port.endSleep()
        rig.pass()
        assertEquals(ServerPhase.Stopped, rig.demux.phase())
        assertEquals(listOf(SENT, CLOSED), rig.io.events.toList())
    }

    @Test
    fun atTheDrainBoundTheServerStopsNamingTheConnectionWhoseCloseWasNotOutAndKeepsTheSocketForItsCaller() {
        val (rig, conn) = serverWithOnePeer()
        val holder = HoldWork()
        val caller = holdAsCaller(conn, holder)

        rig.demux.beginShutdown()
        rig.pass(0)
        rig.pass(DRAIN_BOUND - 1)
        assertEquals(ServerPhase.Draining, rig.demux.phase(), "the bound has not passed")
        rig.pass(DRAIN_BOUND)
        assertEquals(ServerPhase.Stopped, rig.demux.phase(), "the server stops at the bound")
        assertEquals(1L, rig.counts.closesUnsettled)
        assertEquals(emptyList(), rig.io.events, "the caller still holds a lane: the socket stays open")
        assertEquals(1, rig.socket.lanesOut())

        holder.release.countDown()
        caller.join(5_000)
        assertEquals<List<ConnEnd>>(listOf(ConnEnd.ServerClosedUnsettled), rig.sinks.single().ended)
        rig.pass()
        assertEquals(listOf(SENT, CLOSED), rig.io.events.toList(), "the caller sent the close in place before the socket closed")
    }

    @Test
    fun aConnectionEndedAtTheDrainBoundTellsItsUserThatItsCloseWasNeverSent() {
        val (rig, conn) = serverWithOnePeer()
        val holder = HoldWork()
        val caller = holdAsCaller(conn, holder)

        rig.demux.beginShutdown()
        rig.pass(0)
        rig.pass(DRAIN_BOUND)
        holder.release.countDown()
        caller.join(5_000)

        val end =
            rig.sinks
                .single()
                .ended
                .single()
        val reason = closeReasonOf(end)
        assertNotEquals<QuicCloseReason>(
            QuicCloseReason.ByLocal(QuicError.NoError),
            reason,
            "a close the peer never got is not a clean one",
        )
        assertEquals<QuicCloseReason>(QuicCloseReason.ByLocal(QuicError.ServerStoppedBeforeCloseSent), reason)
        assertEquals(reason, (endedWith(end, QuicCloseCircumstance.Ended) as QuicCloseException).closeReason)
    }

    @Test
    fun aCallerStalledPastTheLaneBoundIsCutTheSocketClosesAndItsLateSendNeverReachesTheSocket() {
        val (rig, conn) = serverWithOnePeer()
        val holder = HoldWork()
        val caller = holdAsCaller(conn, holder)

        rig.demux.beginShutdown()
        rig.pass(0)
        val next = rig.pass(DRAIN_BOUND)
        assertEquals(ServerPhase.Stopped, rig.demux.phase())
        assertEquals(1, rig.socket.lanesOut(), "the stalled caller still holds its lane")
        assertEquals(DRAIN_BOUND + LANE_BOUND, next.fold({ -1L }, { it }), "the loop's next wake is the lane bound")

        rig.pass(DRAIN_BOUND + LANE_BOUND - 1)
        assertEquals(emptyList(), rig.io.events, "the lane bound has not passed: the socket stays open")
        assertEquals(0L, rig.loop.counts.lanesCut)

        rig.pass(DRAIN_BOUND + LANE_BOUND)
        assertEquals(listOf(CLOSED), rig.io.events.toList(), "the lane was cut and the socket closed with it still out")
        assertEquals(1L, rig.loop.counts.lanesCut)
        assertEquals(1, rig.socket.lanesOut())

        holder.release.countDown()
        caller.join(5_000)
        assertEquals(listOf(CLOSED), rig.io.events.toList(), "the late send never reached the socket")
        assertTrue(rig.loop.callerCounts.sendsCut >= 1, "the late send was refused as cut")
        assertEquals(0L, rig.loop.callerCounts.sendsInPlace)
        assertEquals(0, rig.socket.lanesOut(), "the lane came back")
    }

    @Test
    fun withNobodyInsideAConnectionWhoseDrainNeverEndsIsToldAtTheCloseLimitBeforeTheDrainBound() {
        val api =
            StubQuicheApi().apply {
                emitOneDatagramOnClose = true
                streamDataUnacknowledged = true
            }
        val rig = ServerRig(api, lanes = 2)
        rig.io.arrive(initial(Random(12).nextBytes(8)), peer(1))
        rig.pass(0)
        rig.demux.beginShutdown()
        rig.pass(0)
        rig.pass(CloseChoice.DEFAULT_LIMIT_NANOS - 1)
        assertEquals(emptyList(), rig.io.events, "the peer has not acknowledged: the graceful close still waits")
        // Each pass is the loop's own: no caller is inside, and the loop serves the limit when it falls due.
        rig.pass(CloseChoice.DEFAULT_LIMIT_NANOS)
        assertEquals(listOf(SENT, CLOSED), rig.io.events.toList(), "the CONNECTION_CLOSE went at the close limit, then the socket closed")
        assertEquals(ServerPhase.Stopped, rig.demux.phase(), "the server stopped a second before its drain bound")
        assertEquals(0L, rig.counts.closesUnsettled, "no close was left unsent")
        assertEquals<List<ConnEnd>>(listOf(ConnEnd.ServerClosed), rig.sinks.single().ended)
    }

    @Test
    fun aServerWithNoConnectionsStopsInThePassItWasClosedIn() {
        val rig = ServerRig(lanes = 2)
        rig.demux.beginShutdown()
        rig.pass()
        assertEquals(ServerPhase.Stopped, rig.demux.phase())
        assertEquals(listOf(CLOSED), rig.io.events.toList())
    }

    /** A server with one accepted connection, whose quiche writes one datagram once it is closed, and a caller lane. */
    private fun serverWithOnePeer(): Pair<ServerRig, Conn> {
        val api = StubQuicheApi().apply { emitOneDatagramOnClose = true }
        val rig = ServerRig(api, lanes = 2)
        rig.io.arrive(initial(Random(11).nextBytes(8)), peer(1))
        rig.pass()
        assertEquals(1L, rig.counts.accepted)
        return rig to rig.conns.single()
    }

    /** Makes a thread enter [conn] as a caller and block in [holder] until released. */
    private fun holdAsCaller(
        conn: Conn,
        holder: HoldWork,
    ): Thread {
        val thread = Thread { conn.submit(holder, CallerPlace.MayEnter) }
        thread.name = "test-caller"
        thread.start()
        assertTrue(holder.entered.await(5, TimeUnit.SECONDS), "the caller entered the connection")
        return thread
    }

    /** Work that blocks the thread inside the connection until released. */
    private class HoldWork : ConnWork() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun run(inside: Inside) {
            entered.countDown()
            release.await()
        }

        override fun gone(end: ConnEnd) = Unit
    }

    private companion object {
        /** The drain bound: a graceful close's default limit, and a second for a stalled thread inside. */
        const val DRAIN_BOUND = CloseChoice.DEFAULT_LIMIT_NANOS + 1_000_000_000L
        const val LANE_BOUND = 1_000_000_000L
    }
}
