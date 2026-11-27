package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicPathStats
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuichePathEventType
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.loop.Via
import com.ditchoom.socket.quic.core.path.MoveWanted
import com.ditchoom.socket.quic.core.path.PathNews
import com.ditchoom.socket.quic.core.path.PathNewsCell
import com.ditchoom.socket.quic.core.path.PathSilence
import com.ditchoom.socket.quic.core.path.Silence
import com.ditchoom.socket.quic.core.path.SilenceRule
import com.ditchoom.socket.quic.core.path.SocketAsk
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.sched.LoopWork
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.testing.FakeIo
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.time.Span
import com.ditchoom.socket.quic.core.udp.ArrivedOn
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.SocketTicket
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What a client does with a rebind the edge asks for once its path went silent, on a [ConnRig] over [StubQuicheApi] with
 * quiche's PTO counters written by hand and virtual time. What the connection asks of its sockets is recorded; the
 * rig's loop refuses every open ([Opens.Refused]), or answers none ([Opens.Unanswered]), as a slow NWConnection does.
 */
class RebindLadderTests {
    /**
     * The edge's ask is acted on only if the path is still silent when it arrives, and only once no move is open: a
     * path that answered since, or a move under way, opens nothing.
     */
    @Test
    fun aRebindIsReadAgainOnArrivalAndOpensNothingForAPathThatAnsweredOrAMoveUnderWay() {
        val answered = Fixture()
        answered.goSilent()
        answered.arrive()
        assertEquals(PathNews.Answering, answered.news())
        answered.rebind()
        assertEquals(0, answered.opens(), "the path answered before the ask arrived, and a socket was opened for it")
        assertTrue("RebindStale" in answered.marks(), "the flight says why: ${answered.marks()}")

        val moving = Fixture()
        moving.moveWanted()
        moving.moved(SocketTicket(1))
        moving.goSilent()
        moving.rebind()
        assertEquals(1, moving.opens(), "a rebind asked while a move was open opened a second socket: ${moving.asks}")
        assertTrue("RebindServed" in moving.marks(), "the flight says why: ${moving.marks()}")

        val silent = Fixture()
        silent.goSilent()
        silent.rebind()
        assertEquals(1, silent.opens(), "a silent path with no move open asked no fresh port")
        assertEquals(PORT_ZERO, (silent.asks.single() as SocketAsk.OpenBeside).wanted.bind, "the fresh port is the edge's bind")
    }

    /**
     * While the path stays silent and every move fails, the ask is repeated with no further news, on a schedule that
     * doubles from 250 ms to 60 s and is never given up on: a count would leave the connection on a dead path.
     */
    @Test
    fun aSilentPathIsAskedAgainOnALadderThatDoublesTo60SecondsAndNeverStops() {
        val f = Fixture(Opens.Refused)
        f.goSilent()
        f.rebind()
        val first = f.now
        f.runFor(10.minutes)
        val at = f.openTimes()
        val gaps = at.zipWithNext { a, b -> (b - a).nanoseconds }
        println("[ladder] ${at.size} asks; gaps ${gaps.take(12)}")
        assertEquals(first, at.first())
        assertEquals(250.milliseconds, gaps.first(), "the first rung waits 250 ms")
        for ((a, b) in gaps.zipWithNext()) assertTrue(b == minOf(a * 2, 60.seconds), "the rungs $a then $b do not double to 60 s: $gaps")
        assertEquals(60.seconds, gaps.last(), "the ladder settles at its 60 s rung")
        assertTrue(
            at.last() > first + 9.minutes.inWholeNanoseconds,
            "the ladder stopped asking: last at ${(at.last() - first).nanoseconds}",
        )
    }

    /** A path that answers again ends the ladder its silence started: no ask follows, however long it waits. */
    @Test
    fun aPathThatAnswersAgainEndsTheLadderItsSilenceStarted() {
        val f = Fixture(Opens.Refused)
        f.goSilent()
        f.rebind()
        f.runFor(5.seconds)
        val whileSilent = f.opens()
        assertTrue(whileSilent > 1, "the ladder never asked again while the path was silent: this cannot show it stopping")
        f.arrive()
        f.runFor(10.minutes)
        assertEquals(whileSilent, f.opens(), "the path answered and the ladder kept asking: ${f.openTimes()}")
    }

    /** News of the network starts the ladder again at its first wait: what failed before says nothing of the new network. */
    @Test
    fun aUsableNetworkStartsTheLadderAgainAtItsFirstWait() {
        val f = Fixture(Opens.Refused)
        f.goSilent()
        f.rebind()
        f.runFor(20.seconds)
        val before = f.opens()
        f.network(NetworkChange.Usable)
        val renewedAt = f.now
        f.runFor(300.milliseconds)
        assertEquals(before + 1, f.opens(), "no rung 250 ms after the network came back: ${f.openTimes()}")
        assertEquals(250.milliseconds, (f.openTimes().last() - renewedAt).nanoseconds)
    }

    /**
     * The carrying path answering while the move a rebind asked for is still open was loss, not a dead path: the move is
     * given up and its socket closed, so the connection keeps its path and that path's recovery.
     */
    @Test
    fun theCarryingPathAnsweringGivesUpTheMoveARebindOpened() {
        val f = Fixture()
        f.goSilent()
        f.rebind()
        f.moved(SocketTicket(1))
        f.arrive()
        f.runFor(100.milliseconds)
        assertTrue(f.asks.any { it is SocketAsk.CloseSecond }, "the rebind's socket was kept after the old path answered: ${f.asks}")
        assertTrue("RebindGaveUp" in f.marks(), "the flight says why: ${f.marks()}")
        assertEquals(1, f.opens(), "the ladder asked again for a path that answers")
    }

    /**
     * The carrying path answering before the loop has answered a rebind's ask gives the move up too: the socket being
     * made is closed by its ticket, so it is not left polled beside the carrying one, and the loop's late answer for it
     * probes nothing.
     */
    @Test
    fun theCarryingPathAnsweringBeforeTheLoopAnswersClosesTheSocketBeingMade() {
        val f = Fixture()
        f.goSilent()
        f.rebind()
        val asked = f.lastAsked()
        f.arrive()
        f.runFor(100.milliseconds)
        assertTrue(
            f.asks.any { it is SocketAsk.CloseSecond && it.ticket == asked },
            "the rebind was given up and the socket being made for it was kept: ${f.asks}",
        )
        assertTrue("RebindGaveUp" in f.marks(), "the flight says why: ${f.marks()}")
        f.moved(asked)
        assertEquals(0, f.api.probedSockAddrs.size, "the loop's answer for a socket given up was followed")
        assertTrue("Moved" !in f.marks(), "the flight: ${f.marks()}")
    }

    /**
     * A rebind's move quiche migrated is landing, the loop's promotion awaited: the path the migration answers on gives
     * nothing up, as there is no unfinished move left to give up, and the flight says no rebind gave up.
     */
    @Test
    fun aRebindsMoveLandingIsNotGivenUp() {
        val f = Fixture()
        f.goSilent()
        f.rebind()
        f.moved(f.lastAsked())
        f.validates()
        f.arrive()
        f.runFor(100.milliseconds)
        assertTrue("RebindGaveUp" !in f.marks(), "a landing was counted as a rebind given up: ${f.marks()}")
        assertTrue(f.asks.none { it is SocketAsk.CloseSecond }, "the landing's socket was closed: ${f.asks}")
    }

    /**
     * An open the loop has not answered holds the ladder: a slow second socket (an NWConnection still being made, or
     * one waiting for a path) is not cancelled by the next rung, however long the path stays silent.
     */
    @Test
    fun anOpenTheLoopHasNotAnsweredHoldsTheLadder() {
        val f = Fixture(Opens.Unanswered)
        f.goSilent()
        f.rebind()
        f.runFor(2.minutes)
        assertEquals(1, f.opens(), "a rung asked again over an open the loop had not answered: ${f.openTimes()}")
        f.refuse()
        f.runFor(300.milliseconds)
        assertEquals(2, f.opens(), "the loop refused the open and the ladder never asked again")
    }

    /**
     * A want toward the address an open move goes to, on the same network, asks nothing: asking again would throw that
     * move away. On another network it is asked again, and once the move ended the same want is asked anew.
     */
    @Test
    fun aWantAnOpenMoveAlreadyServesAsksNothing() {
        val f = Fixture()
        f.moveWanted()
        f.moveWanted()
        assertEquals(1, f.opens(), "a second want toward the move under way asked another socket: ${f.asks}")
        f.work(MoveWantedWork(MoveWanted(OTHER, RttPrior.CONFIGURED, ELSEWHERE)))
        assertEquals(2, f.opens(), "a want on another network was taken as served")
        f.refuse()
        f.work(MoveWantedWork(MoveWanted(OTHER, RttPrior.CONFIGURED, ELSEWHERE)))
        assertEquals(3, f.opens(), "a want after the move was refused was taken as served")
    }

    /** A peer that forbids a move is asked once per silence: one mark, not one each minute for the life of the silence. */
    @Test
    fun aForbiddingPeerIsAskedOncePerSilence() {
        val f = Fixture(peer = Peer.Forbids)
        f.goSilent()
        f.rebind()
        f.runFor(5.minutes)
        assertEquals(1, f.marks().count { it == "RebindForbidden" }, "the flight: ${f.marks()}")
        assertEquals(0, f.opens())
    }

    /**
     * Rungs that cannot be asked yet, an unconfirmed handshake here, do not climb the ladder: once the connection can
     * move, the ask comes within a first wait, and the rung after it a first wait later.
     */
    @Test
    fun deferredRungsDoNotClimbTheLadder() {
        val f = Fixture(Opens.Refused, handshake = Confirmed.No)
        f.goSilent()
        f.rebind()
        f.runFor(30.seconds)
        assertEquals(0, f.opens(), "a socket was asked before the handshake was confirmed")
        f.api.withholdConfirmation = false
        val confirmedAt = f.now
        f.runFor(600.milliseconds)
        val at = f.openTimes()
        assertTrue(at.isNotEmpty() && at.first() - confirmedAt <= 300.milliseconds.inWholeNanoseconds, "the first ask waited: $at")
        assertTrue(at.size >= 2, "no second rung: $at")
        assertEquals(250.milliseconds, (at[1] - at[0]).nanoseconds, "the deferred rungs climbed the ladder")
    }

    /** A connection past its close point asks for no socket: neither the edge's ask nor a rung opens one. */
    @Test
    fun aClosingConnectionAsksForNoSocket() {
        val f = Fixture(Opens.Refused)
        f.goSilent()
        f.rebind()
        f.runFor(1.seconds)
        val before = f.opens()
        f.close()
        f.rebind()
        f.runFor(2.minutes)
        assertEquals(before, f.opens(), "a closing connection asked for a second socket: ${f.openTimes()}")
        val due = f.rig.pass(f.now).fold({ Long.MAX_VALUE }, { it })
        assertTrue(due > f.now, "a closing connection asks its loop to wake it again at once, for a rung it never asks")
    }

    /**
     * The ladder outlives a silence: a path that keeps answering again and then going silent waits out what is left of
     * the last rung, so a lossy but alive path spends ever fewer ids, where restarting at 250 ms each time spent one per
     * silence.
     */
    @Test
    fun aPathSilentTimeAndAgainSpendsFewerIdsThanItHasSilences() {
        val f = Fixture(Opens.Unanswered)
        var ticket = 0
        repeat(EPISODES) {
            f.goSilent()
            val before = f.opens()
            f.rebind()
            if (f.opens() > before) f.moved(SocketTicket(++ticket))
            f.arrive()
            f.runFor(100.milliseconds)
        }
        println("[ladder] $EPISODES silences, ${f.opens()} rebinds at ${f.openTimes().map { (it - START).nanoseconds }}")
        assertTrue(f.opens() <= EPISODES / 2, "${f.opens()} of $EPISODES silences spent an id: the ladder starts afresh each time")
        assertTrue(f.opens() >= 3, "the ladder asked for almost nothing: ${f.openTimes()}")
    }

    /**
     * The path answering ends only the ladder its silence started: a move the monitor asked for, probing a path that has
     * not answered, carries on, its socket kept.
     */
    @Test
    fun thePathAnsweringEndsNoMoveTheMonitorAskedFor() {
        val f = Fixture()
        f.moveWanted()
        f.moved(SocketTicket(1))
        f.goSilent()
        f.arrive()
        f.runFor(500.milliseconds)
        assertEquals(PathNews.Answering, f.news())
        assertEquals(1, f.opens(), "the path answering asked for another socket: ${f.asks}")
        assertTrue(f.asks.none { it is SocketAsk.CloseSecond }, "the path answering closed the move's socket: ${f.asks}")
    }

    /**
     * Two moves wanted before the loop answers either: the loop opens the first socket, then closes it to open the
     * second, and tells the connection of both. Only the socket it asked for last is followed, so no destination id is
     * spent on a path from a socket the loop already closed.
     */
    @Test
    fun twoWantsInOnePassProbeOnce() {
        val f = Fixture()
        f.wantTwiceInOnePass()
        val (first, last) = f.openTickets()
        f.answer(MovedWork(MoveTo(OTHER, RttPrior.CONFIGURED, first)), MovedWork(MoveTo(OTHER, RttPrior.CONFIGURED, last)))
        assertEquals(1, f.api.probedSockAddrs.size, "the connection probed a path from the socket the loop closed: ${f.asks}")
        assertEquals(1, f.marks().count { it == "Moved" }, "the flight: ${f.marks()}")
        // The move followed is the last socket's: losing that one ends it.
        f.lose(last)
        assertTrue("SecondLost" in f.marks(), "the move followed a socket other than the one asked for last: ${f.marks()}")
    }

    /**
     * A rung after the first is asked later than the reading that named its network, which may have changed since (a
     * re-association): it names no network, and the loop takes the one the next rehome names.
     */
    @Test
    fun aLaterRungNamesNoNetwork() {
        val f = Fixture()
        f.goSilent()
        f.work(RebindWork(MoveWanted(PORT_ZERO, RttPrior.CONFIGURED, ELSEWHERE)))
        assertEquals(
            ELSEWHERE,
            f.asks
                .filterIsInstance<SocketAsk.OpenBeside>()
                .single()
                .wanted.network,
            "the first rung",
        )
        f.refuse()
        f.runFor(1.seconds)
        val later = f.asks.filterIsInstance<SocketAsk.OpenBeside>()
        assertTrue(later.size >= 2, "precondition: a later rung was asked: ${f.asks}")
        assertEquals(NetworkId.Unidentified, later.last().wanted.network, "a later rung named the network of an old reading")
    }

    /**
     * The loop refusing the first of two asks says nothing of the second: that move stays open, the edge is not told it
     * ended, the ladder asks nothing over it, and the socket the loop then opens for it is followed.
     */
    @Test
    fun aRefusalOfAnEarlierAskLeavesTheLaterOneOpen() {
        val f = Fixture()
        f.goSilent()
        f.wantTwiceInOnePass()
        val (first, last) = f.openTickets()
        f.answer(MoveRefusedWork(first, MoveRefusal.Unopened))
        f.rebind()
        assertEquals(2, f.opens(), "a rebind asked for a socket over the open the loop had not answered: ${f.asks}")
        f.answer(MovedWork(MoveTo(OTHER, RttPrior.CONFIGURED, last)))
        assertEquals(1, f.api.probedSockAddrs.size, "the socket of the second ask was not followed")
        assertTrue("OpenRefused" !in f.marks(), "the flight marked a refusal of a move still open: ${f.marks()}")
    }

    /** A migration is a new path: the watch's run on the old one ends, and the edge is told the path answers, at once. */
    @Test
    fun aMigrationRebaselinesTheWatchAndAnswersAtOnce() {
        val api = StubQuicheApi().apply { pathStats = listOf(stats(probes = 0L)) }
        val cell = PathNewsCell()
        val watch = PathSilence(QuicheConnRef(api, QuicheConn(1L)), SilenceRule(Silence.On, Span(30 * SECOND), cell))
        watch.baseline()
        var at = START
        var probes = 0L
        while (cell.read() != PathNews.Silent && probes < SILENCE_TICKS) {
            at += TICK
            api.pathStats = listOf(stats(probes = ++probes))
            watch.sample(Nanos(at))
            watch.verdict(Nanos(at))
        }
        assertEquals(PathNews.Silent, cell.read(), "precondition: the old path went silent")
        api.pathStats = listOf(stats(probes = probes))
        watch.migrated(Nanos(at))
        assertEquals(PathNews.Answering, cell.read(), "the migration left the old path's silence standing")
        assertEquals(PathNews.Answering, watch.now())
    }

    /** What the rig's loop answers an open with. */
    private enum class Opens { Unanswered, Refused }

    /** Whether the rig's handshake is confirmed from the start. */
    private enum class Confirmed { Yes, No }

    /** Whether the rig's peer allows a move. */
    private enum class Peer { Permits, Forbids }

    /** A rig with a client whose silence is judged, a peer that hands out spare ids, and a recorder of its socket asks. */

    private class Fixture(
        private val opens: Opens = Opens.Unanswered,
        handshake: Confirmed = Confirmed.Yes,
        peer: Peer = Peer.Permits,
    ) {
        val api =
            StubQuicheApi().apply {
                availableDcids = SPARE_IDS
                withholdConfirmation = handshake == Confirmed.No
                peerDisablesActiveMigration = peer == Peer.Forbids
            }
        val asks = ArrayList<SocketAsk>()
        private val askedAt = ArrayList<Long>()
        val io = Arrivals()
        private val cell = PathNewsCell()
        private val paths = EventsOnEveryAddress(api)
        val rig =
            ConnRig(
                paths,
                io = io,
                silence = SilenceRule(Silence.On, Span(30 * SECOND), cell),
                ask = { asked ->
                    asks += asked
                    askedAt += now
                    if (asked is SocketAsk.OpenBeside && opens == Opens.Refused) refusing = true
                },
            )
        var now = START
        private var probes = 0L
        private var refusing = false

        init {
            api.pathStats = listOf(stats(probes = 0L))
            api.connTimeout = TICK.nanoseconds
            rig.pass(now)
        }

        fun news(): PathNews = cell.read()

        fun opens(): Int = asks.count { it is SocketAsk.OpenBeside }

        fun openTimes(): List<Long> = asks.indices.filter { asks[it] is SocketAsk.OpenBeside }.map { askedAt[it] }

        /** quiche's PTO expires every [TICK] unanswered until the watch declares the path silent; then its timer rests. */
        fun goSilent() {
            var ticks = 0
            // quiche's timer is set by hand: a turn has the connection read it, as it would after any event.
            api.connTimeout = Duration.ZERO
            work(TakeATurn())
            while (news() != PathNews.Silent && ticks++ < SILENCE_TICKS) {
                api.pathStats = listOf(stats(probes = ++probes))
                api.connTimeout = Duration.ZERO
                passAt(now + TICK)
            }
            api.connTimeout = null
            passAt(now + 1)
            assertEquals(PathNews.Silent, news(), "the rig's path never went silent")
        }

        /** A datagram from the peer arrives on the carrying socket. */
        fun arrive() {
            io.pending = 1
            rig.endpoint.readable(Via.Carrying)
            passAt(now + 1_000_000L)
        }

        /** The edge asks for a fresh port, as the network feed does once the path reads silent. */
        fun rebind() = work(RebindWork(MoveWanted(PORT_ZERO, RttPrior.CONFIGURED)))

        /** The monitor's route moved: the edge asks for the socket on another address. */
        fun moveWanted() = work(MoveWantedWork(MoveWanted(OTHER, RttPrior.CONFIGURED)))

        /** The loop opened the second socket [ticket] on [OTHER]. */
        fun moved(ticket: SocketTicket) = work(MovedWork(MoveTo(OTHER, RttPrior.CONFIGURED, ticket)))

        /** quiche reports the probed path validated; the connection migrates in its next turn. */
        fun validates() {
            paths.events.addLast(QuichePathEventType.Validated)
            work(TakeATurn())
            assertEquals(1, paths.migrations, "precondition: quiche migrated to the validated path")
        }

        fun network(change: NetworkChange) = work(NetworkWork(change))

        /** The loop lost the second socket [ticket] names. */
        fun lose(ticket: SocketTicket) = work(SecondLostWork(ticket))

        /** The loop refuses the open last asked for. */
        fun refuse() = work(MoveRefusedWork(lastAsked(), MoveRefusal.Unopened))

        /** The ticket the connection named in its last open. */
        fun lastAsked(): SocketTicket = asks.filterIsInstance<SocketAsk.OpenBeside>().last().ticket

        /** The tickets the connection named in its opens, in order. */
        fun openTickets(): List<SocketTicket> = asks.filterIsInstance<SocketAsk.OpenBeside>().map { it.ticket }

        /** The monitor's route moves twice before the loop runs: the edge asks for the socket on another address twice. */
        fun wantTwiceInOnePass() {
            rig.conn.submit(MoveWantedWork(MoveWanted(OTHER, RttPrior.CONFIGURED)))
            rig.conn.submit(MoveWantedWork(MoveWanted(OTHER, RttPrior.CONFIGURED, ELSEWHERE)))
            passAt(now + 1)
            assertEquals(2, opens(), "precondition: each want asked for a socket: $asks")
        }

        /** The loop's answers reach the connection in one pass, in order. */
        fun answer(vararg answers: ConnWork) {
            for (each in answers) rig.conn.submit(each)
            passAt(now + 1)
        }

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
                val due = rig.pass(now).fold({ Long.MAX_VALUE }, { it })
                if (due > end) break
                if (++passes > MOST_PASSES) throw AssertionError("the connection asked for $passes wakes in $span, the last at once")
                passAt(maxOf(due, now + 1))
            }
            passAt(end)
        }

        fun passAt(at: Long) {
            now = at
            rig.pass(at)
            // The loop answers an open in the pass after it, as a refusal.
            if (refusing) {
                refusing = false
                rig.conn.submit(MoveRefusedWork(lastAsked(), MoveRefusal.Unopened))
                rig.pass(at)
            }
        }

        fun marks(): List<String> {
            val recorded = ArrayList<TraceEvent>()
            rig.loop.port.submit(Dump(rig, TraceSink { recorded += it }))
            rig.pass(now)
            rig.pass(now)
            return recorded.filterIsInstance<TraceEvent.PathState>().map { it.phase }
        }

        fun work(work: ConnWork) {
            rig.conn.submit(work)
            passAt(now + 1)
        }
    }

    /** Work that does nothing: the connection takes a turn. */
    private class TakeATurn : ConnWork() {
        override fun run(inside: Inside) = Unit

        override fun gone(end: ConnEnd) = Unit
    }

    /** A socket that delivers [pending] datagrams from the rig's peer at its next read. */
    private class Arrivals : DatagramBatchIo by FakeIo() {
        var pending = 0

        override fun receive(into: ReceiveBatch): ReceiveBatchResult {
            if (pending == 0) return ReceiveBatchResult.Nothing
            while (pending > 0) {
                pending--
                into.received(into.take() as ReceiveBuffer, 100, PathKey(4, 443, 0L, 0x7F000001L), ArrivedOn.Unreported)
            }
            return ReceiveBatchResult.Got
        }
    }

    private class Dump(
        private val rig: ConnRig,
        private val sink: TraceSink,
    ) : LoopWork() {
        override fun run(port: LoopPort) =
            rig.conn.submit(
                object : ConnWork() {
                    override fun run(inside: Inside) = inside.dump(sink)

                    override fun gone(end: ConnEnd) = Unit
                },
            )

        override fun gone(end: ConnEnd) = Unit
    }

    private companion object {
        const val SECOND = 1_000_000_000L
        const val START = SECOND
        const val SPARE_IDS = 4L
        const val SILENCE_TICKS = 100
        const val EPISODES = 12

        /** Far more wakes than a ladder, quiche's timer and the silence watch ask for in any window these tests run. */
        const val MOST_PASSES = 10_000
        val TICK = 100.milliseconds.inWholeNanoseconds
        val PORT_ZERO = PathKey(4, 0, 0L, 0x7F000001L)
        val OTHER = PathKey(4, 0, 0L, 0x7F000002L)
        val ELSEWHERE = NetworkId.Link(NetworkKind.Cellular, 2L)

        /** Only the fields the watch reads are set: the rest are zero on purpose. */
        fun stats(probes: Long) =
            QuicPathStats(
                validationState = 0,
                active = true,
                recv = 0,
                sent = 0,
                lost = 0,
                retrans = 0,
                totalPtoCount = probes,
                rtt = Duration.ZERO,
                minRtt = Duration.ZERO,
                maxRtt = Duration.ZERO,
                rttvar = Duration.ZERO,
                cwnd = 0,
                sentBytes = 0,
                recvBytes = 0,
                lostBytes = 0,
                streamRetransBytes = 0,
                pmtu = 0,
                deliveryRate = 0,
            )
    }
}
