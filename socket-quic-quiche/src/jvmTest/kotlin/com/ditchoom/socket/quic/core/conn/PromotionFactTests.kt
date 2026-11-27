package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.MigrateOutcome
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuichePathEventType
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.path.MoveWanted
import com.ditchoom.socket.quic.core.path.SocketAsk
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.testing.FakeIo
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.core.udp.SocketTicket
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
        val probesBefore = f.stub.probeNowCount
        f.work(PromotedWork(ticket))
        assertEquals(1, f.stub.requeuePathCount, "the old path's flight is resent from the new one")
        assertEquals(listOf(ORIGINAL_ID), f.stub.retiredDcids, "the old path's id, and only it, is retired")
        assertTrue(f.stub.probeNowCount > probesBefore, "what was requeued waited for quiche's next timer")
        f.work(PromotedWork(ticket))
        assertEquals(1, f.stub.requeuePathCount, "a second promotion of the same socket changed something")
    }

    /** The new socket is lost before the loop promoted it: quiche migrates back, on the old path's id, and the connection stays. */
    @Test
    fun aSocketLostWhileLandingMigratesBackToTheOldPath() {
        val f = Fixture()
        val ticket = f.migrate()
        val probesBefore = f.stub.probeNowCount
        f.work(SecondLostWork(ticket))
        assertEquals(2, f.migrations, "the connection migrated back to the old path")
        val (to, back) = f.api.migratedTo
        assertTrue(to != CONNECT_SOCKADDR, "precondition: the first migration named the new path")
        assertEquals(CONNECT_SOCKADDR, back, "the way back named the path the connection was opened on")
        assertEquals(listOf(NEW_ID), f.stub.retiredDcids, "the lost path's id is retired, the old path's kept")
        assertEquals(1, f.stub.requeuePathCount, "what the lost path had in flight is resent on the old one")
        assertTrue(f.stub.probeNowCount > probesBefore, "what was requeued waited for quiche's next timer")
        f.stub.connSendOnce = DATAGRAM
        f.work(TakeATurn())
        assertEquals(listOf(SocketTicket.FIRST), f.io.tickets, "a datagram after the way back left by another socket")
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
        val end =
            f.rig.sink.ended
                .single()
        assertSame(ConnEnd.LocalAddressGone, end, "a landing quiche could not leave ended as something else")
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
     * A want read from where the socket was before the promotion, toward the address it was promoted to, arrives after
     * the promotion: the connection carries there already, on whatever network the stale reading named, and asks nothing.
     */
    @Test
    fun aWantForTheAddressThePromotionLandedOnAsksNothing() {
        val f = Fixture()
        val ticket = f.migrate()
        f.work(PromotedWork(ticket))
        f.work(MoveWantedWork(MoveWanted(OTHER, RttPrior.CONFIGURED, NetworkId.Link(NetworkKind.Wifi, 5L))))
        assertEquals(1, f.opens(), "a want for the address the connection carries on asked a new move: ${f.asks}")
    }

    /** quiche migrates back on another id than the old path held: that id is retired too, so none is left linked to no path. */
    @Test
    fun aWayBackOnAnotherIdRetiresTheOldPathsId() {
        val f = Fixture()
        f.stub.connMigrateOutcomes.addLast(MigrateOutcome.Migrated(NEW_ID))
        f.stub.connMigrateOutcomes.addLast(MigrateOutcome.Migrated(OTHER_ID))
        val ticket = f.migrate()
        f.work(SecondLostWork(ticket))
        assertEquals(setOf(NEW_ID, ORIGINAL_ID), f.stub.retiredDcids.toSet(), "an id the way back left unused stayed linked")
    }

    /** The new socket is lost after the application closed: the close stands, and quiche is not migrated back. */
    @Test
    fun aSocketLostWhileLandingAfterACloseLeavesTheClose() {
        val f = Fixture()
        f.stub.connMigrateOutcomes.addLast(MigrateOutcome.Migrated(NEW_ID))
        f.stub.connMigrateOutcomes.addLast(MigrateOutcome.Rejected(INVALID_STATE))
        val ticket = f.migrate()
        f.close()
        f.work(SecondLostWork(ticket))
        assertEquals(1, f.api.migratedTo.size, "a closing connection was asked to migrate back")
        assertEquals(0, f.stub.requeuePathCount, "a closing connection resent the lost path's flight")
        assertEquals(emptyList(), f.stub.retiredDcids, "a closing connection retired an id")
        assertTrue(ConnEnd.LocalAddressGone !in f.rig.sink.ended, "the loss replaced the application's close: ${f.rig.sink.ended}")
    }

    /** A move wanted once the application closed asks for no socket: quiche holds the close on the one it has. */
    @Test
    fun aMoveWantedAfterACloseAsksNothing() {
        val f = Fixture()
        f.close()
        f.work(MoveWantedWork(MoveWanted(OTHER, RttPrior.CONFIGURED)))
        f.work(TakeATurn())
        assertEquals(0, f.opens(), "a closing connection asked for a socket: ${f.asks}")
    }

    /** A move held while landing asks for no socket once the application closed, when the landing then settles. */
    @Test
    fun aMoveHeldWhileLandingAsksNothingAfterAClose() {
        val f = Fixture()
        val ticket = f.migrate()
        f.work(MoveWantedWork(MoveWanted(ELSEWHERE, RttPrior.CONFIGURED)))
        f.close()
        f.work(PromotedWork(ticket))
        f.work(TakeATurn())
        assertEquals(1, f.opens(), "a closing connection asked for a socket: ${f.asks}")
    }

    /**
     * A move held while landing is not remade each serve, and wakes nothing, however long the landing stands: past
     * the time a waiting move would turn final, a wake offered for it would fall due at once, pass after pass.
     */
    @Test
    fun aMoveHeldWhileLandingWakesNothing() {
        val held = Fixture()
        val heldTicket = held.migrate()
        held.work(MoveWantedWork(MoveWanted(ELSEWHERE, RttPrior.CONFIGURED)))
        val plain = Fixture()
        plain.migrate()
        plain.work(TakeATurn())
        held.runFor(PAST_DARK)
        assertEquals(1, held.opens(), "a move held while landing asked for a socket: ${held.asks}")
        val heldBytes = held.allocatedBy(TURNS)
        val plainBytes = plain.allocatedBy(TURNS)
        assertTrue(heldBytes - plainBytes < TURNS, "a held move allocated each serve: $heldBytes bytes over $plainBytes")
        held.work(PromotedWork(heldTicket))
        assertEquals(2, held.opens(), "the held move was asked once the landing settled: ${held.asks}")
    }

    /**
     * A client that a stub peer hands spare ids, whose new path validates when told ([validates]), and a sink of its
     * socket asks that records them and answers none.
     */
    private class Fixture {
        val stub = StubQuicheApi().apply { availableDcids = SPARE_IDS }
        private val events = ArrayDeque<QuichePathEventType>()
        val api = EventsOnEveryAddress(stub, events)
        val asks = ArrayList<SocketAsk>()
        val io = TicketsSent()
        val rig = ConnRig(api, io = io, ask = { asks += it })
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

        /** The application closes the connection: quiche holds the close. */
        fun close() =
            work(
                object : ConnWork() {
                    override fun run(inside: Inside) = inside.close(QuicError.NoError)

                    override fun gone(end: ConnEnd) = Unit
                },
            )

        /**
         * Passes the rig at each time it asks to be woken, for [span]. A connection that asks to be woken again at once,
         * pass after pass, spins its loop: that fails here within [MOST_PASSES], never as a hung run.
         */
        fun runFor(span: Duration) {
            val end = now + span.inWholeNanoseconds
            var passes = 0
            while (true) {
                work(TakeATurn())
                val due = rig.pass(now).fold({ Long.MAX_VALUE }, { it })
                if (due > end) break
                if (++passes > MOST_PASSES) throw AssertionError("the connection asked for $passes wakes in $span, the last at once")
                now = maxOf(due, now + 1)
            }
            now = end
        }

        /** The bytes this thread allocated over [turns] turns of the connection, each its own pass. */
        fun allocatedBy(turns: Int): Long {
            val turn = TakeATurn()
            val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            val before = threads.currentThreadAllocatedBytes
            repeat(turns) { work(turn) }
            return threads.currentThreadAllocatedBytes - before
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

    /** A socket that records which socket the connection named for each datagram it sent. */
    private class TicketsSent : DatagramBatchIo by FakeIo() {
        val tickets = ArrayList<SocketTicket>()

        override fun send(batch: SendBatch): SendBatchResult {
            for (n in 0 until batch.pending) tickets += batch.socketOf(n)
            return SendBatchResult.AllTaken
        }
    }

    private companion object {
        const val SPARE_IDS = 4L
        const val OTHER_ID = 7L
        const val DATAGRAM = 100

        /** The sockaddr the rig's connect names for the path it opens: none, as the stub decodes none. */
        const val CONNECT_SOCKADDR = 0L
        const val MOST_PASSES = 1_000
        const val TURNS = 200
        val PAST_DARK = 10.seconds
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
