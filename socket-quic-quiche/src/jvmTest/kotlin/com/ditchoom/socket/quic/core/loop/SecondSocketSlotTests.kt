package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.NextDue
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.ready.Poller
import com.ditchoom.socket.quic.core.ready.ReadyTokens
import com.ditchoom.socket.quic.core.ready.SocketRef
import com.ditchoom.socket.quic.core.ready.WakeCause
import com.ditchoom.socket.quic.core.ready.WriteInterest
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.testing.FakeIo
import com.ditchoom.socket.quic.core.testing.TEST_NETWORK
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.udp.Beside
import com.ditchoom.socket.quic.core.udp.SocketTicket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The loop slot an endpoint's second socket takes is given back once, whatever asks for it after its endpoint left. */
class SecondSocketSlotTests {
    /** A poller that counts what was added and removed, by token. */
    private class CountingPoller : Poller {
        val added = ArrayList<Int>()
        val removed = ArrayList<Int>()

        override fun add(
            socket: SocketRef,
            token: Int,
        ) {
            added += token
        }

        override fun remove(token: Int) {
            removed += token
        }

        override fun interest(
            token: Int,
            write: WriteInterest,
        ) = Unit

        override fun await(
            until: NextDue,
            ready: ReadyTokens,
            now: Nanos,
        ): WakeCause = WakeCause.Woken

        override fun wake() = Unit

        override fun close() = Unit
    }

    @Test
    fun aCloseOfTheSecondSocketAfterItsEndpointLeftGivesNoSlotBackTwice() {
        val poller = CountingPoller()
        val io = FakeIo().apply { besideAnswer = Beside.Opened(PathKey(4, 50_001, 0L, 0x7F000001L)) }
        val rig = ConnRig(io = io, poller = poller)
        rig.pass(0)
        rig.loop.move(rig.endpoint, SocketTicket.FIRST.next(), PathKey(4, 0, 0L, 0L), RttPrior.CONFIGURED, TEST_NETWORK)
        rig.pass(1)
        val seat = poller.added.last()
        assertTrue(seat != rig.endpoint.token, "the second socket took a slot of its own")

        rig.conn.endWithLoop(ConnEnd.Released)
        for (at in 2L..20L) rig.pass(at)
        assertEquals(1, poller.removed.count { it == seat }, "the endpoint gave its second socket's slot back as it left")

        // The close the connection asked for before it ended reaches the loop after its endpoint left.
        rig.loop.closeSecond(rig.endpoint, SocketTicket.FIRST.next())
        rig.pass(21)
        assertEquals(1, poller.removed.count { it == seat }, "the slot was given back once")
    }

    /**
     * A move given up while its socket is still being made closes that socket: the close names it by its ticket, and a
     * close naming any other ticket leaves it.
     */
    @Test
    fun aCloseNamingASocketStillBeingMadeClosesIt() {
        val io =
            FakeIo().apply {
                besideAnswer = Beside.Arriving
                settleAnswer = Beside.Arriving
            }
        val rig = ConnRig(io = io)
        rig.pass(0)
        val ticket = SocketTicket.FIRST.next()
        rig.loop.move(rig.endpoint, ticket, PathKey(4, 0, 0L, 0L), RttPrior.CONFIGURED, TEST_NETWORK)
        rig.pass(1)
        rig.loop.closeSecond(rig.endpoint, ticket.next())
        rig.pass(2)
        assertEquals(0, io.secondsClosed, "a close naming another ticket closed the socket being made")
        rig.loop.closeSecond(rig.endpoint, ticket)
        rig.pass(3)
        assertEquals(1, io.secondsClosed, "the socket being made was left open after its move was given up")
        rig.loop.closeSecond(rig.endpoint, ticket)
        rig.pass(4)
        assertEquals(1, io.secondsClosed, "a second close of the same socket closed something again")
    }
}
