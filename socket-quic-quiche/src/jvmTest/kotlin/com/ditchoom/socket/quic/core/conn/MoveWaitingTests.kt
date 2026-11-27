package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.demux.BoundedIo
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.PeerAddress
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.path.MoveWanted
import com.ditchoom.socket.quic.core.path.SocketAsk
import com.ditchoom.socket.quic.core.path.Want
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.testing.stubClientPaths
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A move that waits for a spare id adds no path to quiche: the connection's one path is the validated one it had, so a
 * close and the ACK hold read it as on a settled path. An idle end while it waits is the move's end: the peer sent no
 * id the socket could move with, so no usable path was left. On a [ConnRig] over [StubQuicheApi] whose peer hands out
 * no spare id; virtual time.
 */
class MoveWaitingTests {
    /** A close while a move waits for a spare id is repeated one and two probe timeouts after it, as on a settled path. */
    @Test
    fun aCloseWhileAMoveWaitsForASpareIdIsRepeatedAsOnASettledPath() {
        val settled = Fixture()
        settled.closeAtZero()
        val waiting = Fixture()
        waiting.moveWanted()
        waiting.closeAtZero()
        for (at in listOf(PTO, PTO + 1, 2 * PTO, 2 * PTO + 1, DRAIN - 1)) {
            settled.rig.pass(at)
            waiting.rig.pass(at)
        }
        assertEquals(3, settled.closesSent(), "precondition: the close and its two repeats on a settled path")
        assertEquals(settled.closesSent(), waiting.closesSent(), "a close while a move waited for a spare id was not repeated")
    }

    /** An idle timeout while a move waits for a spare id ends with no usable path: the move it waited for never came. */
    @Test
    fun anIdleTimeoutWhileAMoveWaitsForASpareIdEndsWithNoUsablePath() {
        val f = Fixture(timeout = IDLE)
        f.moveWanted()
        f.api.timedOut = true
        f.api.closed = true
        f.rig.pass(IDLE)
        val end =
            f.rig.sink.ended
                .single()
        assertSame(ConnEnd.NoUsablePath, end, "a move that waited for a spare id ended as a plain idle timeout")
    }

    /** While a move waits for a spare id the path is calm and its address validated: the ACK may be held as on a settled path. */
    @Test
    fun aMoveWaitingForASpareIdLeavesThePathCalm() {
        val paths = stubClientPaths(QuicheConnRef(StubQuicheApi(), QuicheConn(1L)))
        val client = (paths as Paths.Client).paths
        assertEquals(Want.Waiting, client.hold.want(Nanos(0L), MoveWanted(OTHER, RttPrior.CONFIGURED)), "precondition: the move waits")
        assertEquals(PathCalm.Settled, paths.calm(), "a move waiting for a spare id turned the ACK hold off")
        assertEquals(PeerAddress.Validated, paths.address())
    }

    /**
     * A move that waited for a spare id is asked when the id comes, later than the reading that named its network: it
     * names no network, and the loop takes the one the next rehome names.
     */
    @Test
    fun aMoveReleasedAfterWaitingNamesNoNetwork() {
        val f = Fixture()
        f.rig.conn.submit(MoveWantedWork(MoveWanted(OTHER, RttPrior.CONFIGURED, NetworkId.Link(NetworkKind.Wifi, 5L))))
        f.rig.pass(0)
        assertEquals(emptyList(), f.asks, "precondition: the move waits for a spare id")
        f.api.availableDcids = 1L
        f.rig.conn.submit(
            object : ConnWork() {
                override fun run(inside: Inside) = Unit

                override fun gone(end: ConnEnd) = Unit
            },
        )
        f.rig.pass(1_000_000L)
        val asked = f.asks.filterIsInstance<SocketAsk.OpenBeside>()
        assertEquals(1, asked.size, "precondition: the spare id released the move: ${f.asks}")
        assertEquals(NetworkId.Unidentified, asked.single().wanted.network, "a released move named the network of an old reading")
    }

    /** A client whose peer hands out no spare id, closing with one datagram, and a recorder of its socket asks. */
    private class Fixture(
        timeout: Long = DRAIN,
    ) {
        val api =
            StubQuicheApi().apply {
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

        /** The monitor's route moved: with no spare id the move waits, and no socket is asked of the loop. */
        fun moveWanted() {
            rig.conn.submit(MoveWantedWork(MoveWanted(OTHER, RttPrior.CONFIGURED)))
            rig.pass(0)
            assertEquals(emptyList(), asks, "precondition: the move waits for a spare id and asks for no socket")
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

        /** Short of the three probe timeouts of silence that make a waiting move final. */
        val IDLE = 2.seconds.inWholeNanoseconds
        val OTHER = PathKey(4, 0, 0L, 0x7F000002L)
    }
}
