package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.conn.MoveWantedWork
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.core.path.MoveWanted
import com.ditchoom.socket.quic.core.path.SocketAsk
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.testing.FakeIo
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.udp.SocketTicket
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An io that opens no second socket refuses a move typed: the connection stays on the socket that carries it, and
 * nothing ends. Over a [ConnRig] whose asks are loop work on its endpoint, as a connect wires them.
 */
class CarriageRefusalTests {
    @Test
    fun aMoveOnASocketWithNoSecondStaysOnTheOldSocket() {
        val io = FakeIo()
        val api = StubQuicheApi().apply { availableDcids = 4L }
        val asked = ArrayList<SocketAsk>()
        lateinit var rig: ConnRig
        rig =
            ConnRig(
                api,
                io = io,
                ask = { ask ->
                    asked += ask
                    when (ask) {
                        is SocketAsk.OpenBeside ->
                            rig.loop.move(
                                rig.endpoint,
                                ask.ticket,
                                ask.wanted.bind,
                                ask.wanted.prior,
                                ask.wanted.network,
                            )
                        is SocketAsk.Promote -> rig.loop.promote(rig.endpoint, ask.ticket)
                        is SocketAsk.CloseSecond -> rig.loop.closeSecond(rig.endpoint, ask.ticket)
                    }
                },
            )
        rig.pass(0)
        rig.conn.submit(MoveWantedWork(MoveWanted(PathKey(4, 0, 0L, 0x7F000002L), RttPrior.CONFIGURED)))
        for (at in 1L..6L) rig.pass(at)

        assertEquals(listOf(SocketTicket.FIRST.next()), io.openedAs, "precondition: the loop was asked for one second socket")
        assertEquals(emptyList(), rig.sink.ended, "a move the io cannot make ended the connection")
        assertTrue(rig.conn.life() !is Life.Ended, "the connection did not stay on its socket: ${rig.conn.life()}")
        assertEquals(0, io.secondsClosed, "a refused move closed a socket")
        val recorded = ArrayList<TraceEvent>()
        rig.conn.submit(
            object : ConnWork() {
                override fun run(inside: Inside) = inside.dump(TraceSink { recorded += it })

                override fun gone(end: ConnEnd) = Unit
            },
        )
        rig.pass(7)
        val marks = recorded.filterIsInstance<TraceEvent.PathState>().map { it.phase }
        assertTrue("NoSecond" in marks, "the refusal was not marked as one of an io with no second socket: $marks")
    }
}
