package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.demux.BoundedIo
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.PeerAddress
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.path.SocketAsk
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.testing.stubClientPaths
import com.ditchoom.socket.quic.core.time.RttPrior
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A second socket asked of the loop and not yet answered adds no path to quiche: the connection's one path is the
 * validated one it had, so a close, an idle end and the ACK hold read it as on a settled path. On a [ConnRig] over
 * [StubQuicheApi] whose loop never answers the ask, as an NWConnection that stays in the making; virtual time.
 */
class MoveAskedTests {
    /** A close while the second socket is being made is repeated one and two probe timeouts after it, as on a settled path. */
    @Test
    fun aCloseWhileTheSecondSocketIsBeingMadeIsRepeatedAsOnASettledPath() {
        val settled = Fixture()
        settled.closeAtZero()
        val asked = Fixture()
        asked.moveWanted()
        asked.closeAtZero()
        for (at in listOf(PTO, PTO + 1, 2 * PTO, 2 * PTO + 1, DRAIN - 1)) {
            settled.rig.pass(at)
            asked.rig.pass(at)
        }
        assertEquals(3, settled.closesSent(), "precondition: the close and its two repeats on a settled path")
        assertEquals(settled.closesSent(), asked.closesSent(), "a close while the second socket was being made was not repeated")
    }

    /** An idle timeout while the second socket is being made is an idle timeout: no path of a move was ever probed. */
    @Test
    fun anIdleTimeoutWhileTheSecondSocketIsBeingMadeIsAnIdleTimeout() {
        val f = Fixture(timeout = 5.seconds.inWholeNanoseconds)
        f.moveWanted()
        f.api.timedOut = true
        f.api.closed = true
        f.rig.pass(5.seconds.inWholeNanoseconds)
        assertSame(ConnEnd.IdleTimeout, f.rig.sink.ended.single(), "an ask the loop had not answered read as a move with no usable path")
    }

    /** While the second socket is being made the path is calm and its address validated: the ACK may be held as on a settled path. */
    @Test
    fun aMoveAskedLeavesThePathCalm() {
        val paths = stubClientPaths(QuicheConnRef(StubQuicheApi(), QuicheConn(1L)))
        val client = (paths as Paths.Client).paths
        client.hold.moveNow(OTHER, RttPrior.CONFIGURED)
        assertEquals(PathCalm.Settled, paths.calm(), "an ask the loop had not answered turned the ACK hold off")
        assertEquals(PeerAddress.Validated, paths.address())
    }

    /** A client whose peer hands out spare ids, closing with one datagram, and a recorder of its socket asks; none is answered. */
    private class Fixture(
        timeout: Long = DRAIN,
    ) {
        val api =
            StubQuicheApi().apply {
                availableDcids = SPARE_IDS
                emitOneDatagramOnClose = true
                connTimeout = timeout.nanoseconds
            }
        val io = BoundedIo(Int.MAX_VALUE)
        val asks = ArrayList<SocketAsk>()
        val rig = ConnRig(api, io = io, ask = { asks += it })
        private var sentBeforeClose = 0

        init {
            rig.pass(0)
        }

        /** The monitor's route moved: the connection asks its loop for a second socket, which the loop never answers. */
        fun moveWanted() {
            rig.conn.submit(MoveWantedWork(MoveWanted(OTHER, RttPrior.CONFIGURED)))
            rig.pass(0)
            assertEquals(1, asks.count { it is SocketAsk.OpenBeside }, "precondition: the want asked for a socket: $asks")
        }

        fun closeAtZero() {
            sentBeforeClose = io.sent.size
            rig.conn.submit(CloseWork())
            rig.pass(0)
        }

        /** The datagrams sent since the close was asked: the close and its repeats. */
        fun closesSent(): Int = io.sent.size - sentBeforeClose
    }

    /** The application closes the connection. */
    private class CloseWork : ConnWork() {
        override fun run(inside: Inside) = inside.close(QuicError.NoError)

        override fun gone(end: ConnEnd) = Unit
    }

    private companion object {
        const val DRAIN = 180_000_000L

        /** A third of the drain: quiche's draining time is three probe timeouts. */
        const val PTO = DRAIN / 3
        const val SPARE_IDS = 4L
        val OTHER = PathKey(4, 0, 0L, 0x7F000002L)
    }
}
