package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.MoveWantedWork
import com.ditchoom.socket.quic.core.path.MoveWanted
import com.ditchoom.socket.quic.core.path.SocketAsk
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.sched.CallerLanes
import com.ditchoom.socket.quic.core.sched.CallerSend
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.testing.FakeIo
import com.ditchoom.socket.quic.core.testing.nativeBytes
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.Beside
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SocketTicket
import com.ditchoom.socket.quic.core.udp.Thrown
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A client connection names each socket it asks its loop for, and one caller at a time is inside it, so its asks reach
 * the loop in the order they were made, each under a ticket of its own: the loop never sees an earlier ask after a later
 * one. Here two callers' threads enter the connection in turn, each wanting a move, over a [ConnRig] whose asks are
 * loop work on its endpoint, as a connect wires them.
 */
class SocketAskOrderTests {
    @Test
    fun asksOfOneConnectionReachTheLoopInOrder() {
        val io = FakeIo().apply { besideAnswer = Beside.Refused(Thrown(IOException("no route"))) }
        val api = StubQuicheApi().apply { availableDcids = SPARE_IDS }
        val asked = ArrayList<SocketAsk>()
        lateinit var rig: ConnRig
        rig =
            ConnRig(
                api,
                io = io,
                lanes = CallerLanes.Of(FixedPool(1) { CallerSend(it, SendBatch(nativeBytes(LANE_BYTES), LIMITS, lane = 1)) }),
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
        for (address in listOf(ONE, TWO)) {
            val caller = Thread { rig.conn.submit(MoveWantedWork(MoveWanted(address, RttPrior.CONFIGURED)), CallerPlace.MayEnter) }
            caller.start()
            caller.join(JOIN_MILLIS)
        }
        assertEquals(2L, rig.conn.counts.enteredInPlace, "precondition: each want ran on its caller's thread")
        rig.pass(1)
        rig.pass(2)
        val named = asked.filterIsInstance<SocketAsk.OpenBeside>().map { it.ticket }
        assertEquals(listOf(SocketTicket(1), SocketTicket(2)), named, "the asks named one ticket each, in order: $asked")
        assertEquals(listOf(ONE, TWO), io.movedTo, "the loop opened the sockets out of the order they were asked in")
        assertEquals(named, io.openedAs, "the loop opened a socket under a ticket the connection did not name")
    }

    private companion object {
        const val SPARE_IDS = 4L
        const val JOIN_MILLIS = 10_000L
        val LIMITS = BatchLimits(maxDatagrams = 8, maxDatagramSize = 1500)
        const val LANE_BYTES = 8 * 1500
        val ONE = PathKey(4, 0, 0L, 0x7F000002L)
        val TWO = PathKey(4, 0, 0L, 0x7F000003L)
    }
}
