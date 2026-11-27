package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.MigrateOutcome
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuichePathEventType
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.path.SocketAsk
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.udp.SocketTicket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Once quiche migrated to a validated path the connection asks the loop to promote the new socket, and believes the
 * promotion only when the loop says it was made: until then the old path keeps its destination id and what it had in
 * flight, and a socket the loop loses first takes the connection back to the old path. On a [ConnRig] over
 * [StubQuicheApi] whose loop answers only what a test hands it; virtual time.
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

    /** The loop's promotion settles the connection on the new path: the old path's flight is resent and its id retired. */
    @Test
    fun thePromotionTheLoopMadeRetiresTheOldPath() {
        val f = Fixture()
        val ticket = f.migrate()
        f.work(PromotedWork(SocketTicket.FIRST))
        assertEquals(0, f.stub.requeuePathCount, "a promotion naming the old socket settled the landing")
        f.work(PromotedWork(ticket))
        assertEquals(1, f.stub.requeuePathCount, "the old path's flight is resent from the new one")
        assertEquals(listOf(ORIGINAL_ID), f.stub.retiredDcids, "the old path's id, and only it, is retired")
        f.work(PromotedWork(ticket))
        assertEquals(1, f.stub.requeuePathCount, "a second promotion of the same socket changed something")
    }

    /** The new socket is lost before the loop promoted it: quiche migrates back, on the old path's id, and the connection stays. */
    @Test
    fun aSocketLostWhileLandingMigratesBackToTheOldPath() {
        val f = Fixture()
        val ticket = f.migrate()
        f.work(SecondLostWork(ticket))
        assertEquals(2, f.migrations, "the connection migrated back to the old path")
        assertEquals(listOf(NEW_ID), f.stub.retiredDcids, "the lost path's id is retired, the old path's kept")
        assertEquals(1, f.stub.requeuePathCount, "what the lost path had in flight is resent on the old one")
        f.work(PromotedWork(ticket))
        assertEquals(listOf(NEW_ID), f.stub.retiredDcids, "a promotion after the loss settled the landing")
        assertEquals(emptyList(), f.rig.sink.ended, "a lost second socket ends no connection")
    }

    /** The new socket is lost before the loop promoted it, and quiche will not migrate back: no socket carries, so it ends. */
    @Test
    fun aSocketLostWhileLandingEndsTheConnectionWhenQuicheRefusesTheWayBack() {
        val f = Fixture()
        f.stub.connMigrateOutcomes.addLast(MigrateOutcome.Migrated(NEW_ID))
        f.stub.connMigrateOutcomes.addLast(MigrateOutcome.Rejected(INVALID_STATE))
        val ticket = f.migrate()
        f.work(SecondLostWork(ticket))
        assertSame(ConnEnd.LocalAddressGone, f.rig.sink.ended.single())
    }

    /** A move wanted while landing asks for its socket only once the loop promoted the landing's. */
    @Test
    fun aMoveWantedWhileLandingAsksForItsSocketAfterThePromotion() {
        val f = Fixture()
        val ticket = f.migrate()
        f.work(MoveWantedWork(MoveWanted(ELSEWHERE, RttPrior.CONFIGURED)))
        assertEquals(1, f.opens(), "a move asked for a socket before the landing's promotion: ${f.asks}")
        f.work(PromotedWork(ticket))
        assertEquals(2, f.opens(), "the move wanted while landing was asked once the landing settled: ${f.asks}")
        assertEquals(1, f.stub.requeuePathCount, "the landing settled before the next move was asked")
    }

    /**
     * A client that a stub peer hands spare ids, whose new path validates when told ([validates]), and a sink of its
     * socket asks that records them and answers none.
     */
    private class Fixture {
        val stub = StubQuicheApi().apply { availableDcids = SPARE_IDS }
        private val events = ArrayDeque<QuichePathEventType>()
        private val api = EventsOnEveryAddress(stub, events)
        val asks = ArrayList<SocketAsk>()
        val rig = ConnRig(api, ask = { asks += it })
        var now = START
        val migrations get() = api.migrations

        init {
            rig.pass(now)
        }

        /**
         * The move is wanted, the loop opens its socket, quiche probes the path from there and migrates once it
         * validates; answers the new socket's ticket.
         */
        fun migrate(): SocketTicket {
            work(MoveWantedWork(MoveWanted(OTHER, RttPrior.CONFIGURED)))
            val ticket = asks.filterIsInstance<SocketAsk.OpenBeside>().single().ticket
            work(MovedWork(MoveTo(OTHER, RttPrior.CONFIGURED, ticket)))
            assertEquals(1, stub.probedSockAddrs.size, "precondition: the new path was probed")
            events.addLast(QuichePathEventType.Validated)
            work(TakeATurn())
            assertEquals(1, migrations, "precondition: quiche migrated to the validated path")
            return ticket
        }

        fun opens(): Int = asks.count { it is SocketAsk.OpenBeside }

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

    private companion object {
        const val SPARE_IDS = 4L
        const val START = 1_000_000_000L

        /** The destination id the connection was opened on, and the one the stub's first probe links to the new path. */
        const val ORIGINAL_ID = 0L
        const val NEW_ID = 1L

        /** A quiche error a refused migration reports. */
        const val INVALID_STATE = -6
        val OTHER = PathKey(4, 0, 0L, 0x7F000002L)
        val ELSEWHERE = PathKey(4, 0, 0L, 0x7F000003L)
    }
}
