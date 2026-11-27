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
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.udp.Beside
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.Reopened
import com.ditchoom.socket.quic.core.udp.SocketTicket
import com.ditchoom.socket.quic.core.udp.Thrown
import com.ditchoom.socket.quic.core.udp.UdpError
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every state of an endpoint's carriage crossed with every event the loop hands it, on a counting poller and a [FakeIo]:
 * - at the end, once the endpoint left, every poller add has its remove and every slot it took is free exactly once;
 * - an answer naming a ticket the carriage does not hold changes nothing;
 * - an event of the same kind a second time changes nothing more;
 * - only the carrying socket's end ends the connection.
 */
class CarriageTableTests {
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

    private enum class State { One, Opening, Two, Lent, Dead, Left }

    private enum class Event(
        val repeatable: Repeat,
    ) {
        OpenBeside(Repeat.Replaces),
        Promote(Repeat.NoMore),
        PromoteStale(Repeat.NoMore),
        CloseSecond(Repeat.NoMore),
        CloseStale(Repeat.NoMore),
        Rehome(Repeat.Replaces),
        RehomeBesideRefused(Repeat.Replaces),
        RehomeCarryingRefused(Repeat.Replaces),
        BesideLost(Repeat.NoMore),
        CarryingDies(Repeat.NoMore),
        Leave(Repeat.NoMore),
    }

    /** Whether a second event of the kind is the same event again, or a new ask that replaces the first. */
    private enum class Repeat { NoMore, Replaces }

    private class Cell(
        val state: State,
    ) {
        val poller = CountingPoller()
        val io = FakeIo()
        val rig =
            ConnRig(
                io = io,
                poller = poller,
                ownership = if (state == State.Lent) SocketOwnership.Lent else SocketOwnership.Owned,
            )
        private var at = 0L

        init {
            passes(1)
            when (state) {
                State.One, State.Lent -> Unit
                State.Opening -> {
                    io.besideAnswer = Beside.Arriving
                    io.settleAnswer = Beside.Arriving
                    rig.loop.move(rig.endpoint, ASKED, BIND, RttPrior.CONFIGURED)
                    passes(1)
                }
                State.Two, State.Dead -> {
                    io.besideAnswer = Beside.Opened(LOCAL)
                    rig.loop.move(rig.endpoint, ASKED, BIND, RttPrior.CONFIGURED)
                    passes(1)
                    // The carrying socket dies in the pass the event is handed in, so the event meets the dead state.
                    if (state == State.Dead) rig.endpoint.ioThrew(Thrown(IOException("carrying socket gone")))
                }
                State.Left -> {
                    rig.conn.endWithLoop(ConnEnd.Released)
                    passes(SETTLE)
                }
            }
        }

        fun hand(event: Event) {
            when (event) {
                Event.OpenBeside -> rig.loop.move(rig.endpoint, ASKED.next(), BIND, RttPrior.CONFIGURED)
                Event.Promote -> rig.loop.promote(rig.endpoint, ASKED)
                Event.PromoteStale -> rig.loop.promote(rig.endpoint, STALE)
                Event.CloseSecond -> rig.loop.closeSecond(rig.endpoint, ASKED)
                Event.CloseStale -> rig.loop.closeSecond(rig.endpoint, STALE)
                Event.Rehome -> rig.loop.rehome(rig.endpoint)
                Event.RehomeBesideRefused -> {
                    io.reopenSecondAnswer = Reopened.Refused(Thrown(IOException("no network for the second")))
                    rig.loop.rehome(rig.endpoint)
                }
                Event.RehomeCarryingRefused -> {
                    io.reopenAnswer = Reopened.Refused(Thrown(IOException("no network for the carrying")))
                    rig.loop.rehome(rig.endpoint)
                }
                Event.BesideLost -> {
                    io.secondReceiveAnswer = ReceiveBatchResult.Failed(UdpError.SocketClosed)
                    rig.endpoint.readable(Via.Probing)
                }
                Event.CarryingDies -> rig.endpoint.ioThrew(Thrown(IOException("carrying socket gone")))
                Event.Leave -> rig.conn.endWithLoop(ConnEnd.Released)
            }
            passes(SETTLE)
        }

        fun passes(count: Int) {
            repeat(count) { rig.pass(++at) }
        }

        /** What the carriage did to the poller, the io and the connection so far. */
        fun seen(): String =
            "added=${poller.added} removed=${poller.removed} opened=${io.openedAs} secondsClosed=${io.secondsClosed} " +
                "reopens=${io.reopens} secondReopens=${io.secondReopens} promotes=${io.promotes} ended=${rig.sink.ended.map { it::class.simpleName }}"

        /** The endpoint leaves; every add has its remove and every slot it took is free once. */
        fun assertLeftClean(label: String) {
            rig.conn.endWithLoop(ConnEnd.Released)
            passes(SETTLE)
            val tokens = poller.added.toSet()
            for (token in tokens) {
                assertEquals(
                    poller.added.count { it == token },
                    poller.removed.count { it == token },
                    "$label: token $token's adds and removes differ: ${seen()}",
                )
            }
            val seated = tokens.map { rig.loop.seat(rig.endpoint) }
            assertEquals(tokens, seated.toSet(), "$label: a slot was kept or given back twice; seated again $seated: ${seen()}")
        }
    }

    @Test
    fun everyStateLeavesCleanAfterEveryEvent() {
        for (state in State.entries) {
            for (event in Event.entries) {
                val cell = Cell(state)
                cell.hand(event)
                cell.assertLeftClean("$state then $event")
            }
            Cell(state).assertLeftClean("$state then nothing")
        }
    }

    @Test
    fun anAnswerNamingAnotherTicketChangesNothing() {
        for (state in State.entries) {
            for (event in listOf(Event.PromoteStale, Event.CloseStale)) {
                val handed = Cell(state).apply { hand(event) }
                val control = Cell(state).apply { passes(SETTLE) }
                assertEquals(control.seen(), handed.seen(), "$state then $event")
            }
        }
    }

    @Test
    fun aSecondEventOfTheSameKindChangesNothing() {
        for (state in State.entries) {
            for (event in Event.entries.filter { it.repeatable == Repeat.NoMore }) {
                val once = Cell(state).apply { hand(event) }.also { it.passes(SETTLE) }
                val twice = Cell(state).apply { hand(event) }.also { it.hand(event) }
                assertEquals(once.seen(), twice.seen(), "$state then $event twice")
            }
        }
    }

    /** Only the carrying socket's end ends the connection: no second socket's failure, refusal or answer does. */
    @Test
    fun onlyTheCarryingSocketsEndEndsTheConnection() {
        val ending = setOf(Event.CarryingDies, Event.RehomeCarryingRefused, Event.Leave)
        for (state in listOf(State.One, State.Opening, State.Two, State.Lent)) {
            for (event in Event.entries.filter { it !in ending }) {
                val cell = Cell(state).apply { hand(event) }
                assertEquals(emptyList(), cell.rig.sink.ended, "$state then $event ended the connection: ${cell.seen()}")
            }
        }
    }

    @Test
    fun aLentSocketNeverOpensASecond() {
        val cell = Cell(State.Lent)
        cell.io.besideAnswer = Beside.Opened(LOCAL)
        cell.hand(Event.OpenBeside)
        assertEquals(emptyList(), cell.io.openedAs, "a socket was opened beside a lent one")
        assertTrue(cell.poller.added.toSet() == setOf(cell.rig.endpoint.token), "a lent endpoint took a second slot: ${cell.seen()}")
    }

    private companion object {
        /** Passes after each event: enough for an ended connection's endpoint to leave its loop. */
        const val SETTLE = 20
        val ASKED = SocketTicket.FIRST.next()
        val STALE = SocketTicket(9)
        val BIND = PathKey(4, 0, 0L, 0L)
        val LOCAL = PathKey(4, 50_001, 0L, 0x7F000001L)
    }
}
