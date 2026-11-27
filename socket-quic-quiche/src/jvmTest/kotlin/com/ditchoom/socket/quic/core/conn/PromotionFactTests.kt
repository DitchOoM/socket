package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuichePathEventType
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.path.SocketAsk
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.testing.nativeBytes
import com.ditchoom.socket.quic.core.testing.testLoopMemory
import com.ditchoom.socket.quic.core.time.RttPrior
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Once quiche migrated to a validated path the connection asks the loop to promote the new socket, and believes the
 * promotion only when the loop says it was made: until then the old path keeps its destination id and what it had in
 * flight. On a [ConnRig] over [StubQuicheApi] whose loop answers no promotion; virtual time.
 */
class PromotionFactTests {
    /** The promotion the connection asked for is never answered: the old path's id is kept and its flight is not resent. */
    @Test
    fun anUnansweredPromotionKeepsTheOldPathsIdAndFlight() {
        val f = Fixture()
        f.migrate()
        assertEquals(1, f.asks.count { it is SocketAsk.Promote }, "precondition: the migration asked for its promotion: ${f.asks}")
        f.rig.pass(f.now + 2)
        assertEquals(0, f.stub.requeuePathCount, "the old path's flight was resent before the loop promoted the new socket")
        assertTrue(ORIGINAL_ID !in f.stub.retiredDcids, "the old path's id was retired before the loop promoted: ${f.stub.retiredDcids}")
    }

    /**
     * A client that a stub peer hands spare ids, whose new path validates when told ([validates]), and a sink of its
     * socket asks that records them and promotes nothing.
     */
    internal class Fixture {
        val stub = StubQuicheApi().apply { availableDcids = SPARE_IDS }
        private val events = ArrayDeque<QuichePathEventType>()
        val asks = ArrayList<SocketAsk>()
        val rig =
            ConnRig(
                EventsOnEveryAddress(stub, events),
                // Path events are read into native memory.
                memory = ConnMemory(testLoopMemory(), BufferFactory.deterministic(), nativeBytes(255)),
                ask = { asks += it },
            )
        var now = START

        init {
            rig.pass(now)
        }

        /** The move is wanted, the loop opens its socket, quiche probes the path from there and migrates once it validates. */
        fun migrate() {
            work(MoveWantedWork(MoveWanted(OTHER, RttPrior.CONFIGURED)))
            val ticket = asks.filterIsInstance<SocketAsk.OpenBeside>().single().ticket
            work(MovedWork(MoveTo(OTHER, RttPrior.CONFIGURED, ticket)))
            assertEquals(1, stub.probedSockAddrs.size, "precondition: the new path was probed")
            validates()
        }

        /** quiche reports the probed path validated; the connection reads it in its next turn. */
        fun validates() {
            events.addLast(QuichePathEventType.Validated)
            work(TakeATurn())
        }

        fun work(work: ConnWork) {
            rig.conn.submit(work)
            rig.pass(++now)
        }
    }

    /** Work that does nothing: the connection takes a turn. */
    private class TakeATurn : ConnWork() {
        override fun run(inside: Inside) = Unit

        override fun gone(end: ConnEnd) = Unit
    }

    /**
     * quiche's path events, each reported for whatever local address the path that is asked about holds: the stub
     * decodes no real sockaddr, so every endpoint of the rig reads as the same one.
     */
    private class EventsOnEveryAddress(
        private val inner: StubQuicheApi,
        private val events: ArrayDeque<QuichePathEventType>,
    ) : QuicheApi by inner {
        override fun connPathEventNext(
            conn: QuicheConn,
            localOut: Long,
            localLenOut: Long,
            peerOut: Long,
            peerLenOut: Long,
        ): QuichePathEventType? = events.removeFirstOrNull()
    }

    internal companion object {
        const val SPARE_IDS = 4L
        const val START = 1_000_000_000L

        /** The destination id the connection was opened on. */
        const val ORIGINAL_ID = 0L
        val OTHER = PathKey(4, 0, 0L, 0x7F000002L)
    }
}
