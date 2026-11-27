@file:Suppress("ktlint:standard:filename") // rule Q names the deciding file path/Paths*: a silent path's rebinds are decided here.

package com.ditchoom.socket.quic.core.path

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.PeerMigrationPermission
import com.ditchoom.socket.quic.connPeerMigrationPermission
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.time.Span
import com.ditchoom.socket.quic.core.time.WakeSink
import com.ditchoom.socket.quic.core.time.since

/** Whether a connection's handshake lets it move (RFC 9000 §9): only once it is confirmed. */
internal enum class MoveGate { Shut, Open }

/**
 * Whether a client has a move open: a second socket asked of the loop and not answered, being probed or stranded, or a
 * socket waiting for a spare id.
 */
internal enum class Moving { Settled, Open }

/** What a rebind came to. */
internal enum class Rebound {
    /** A second socket was asked on a fresh port. */
    Asked,

    /** The path answered again since the edge read it silent: nothing moves. */
    Stale,

    /** A move is open, from its ask on: it serves the silence, and nothing more is asked. */
    Served,

    /** The ladder's last ask was too recent: the rebind is owed when its rung falls due, if the path is still silent. */
    Held,

    /** The handshake is unconfirmed, the peer has not said whether it allows a move, or no spare id is held: later. */
    Deferred,

    /** The peer forbade migration: no rebind is asked while it holds. */
    Forbidden,

    /** No rung was due. */
    Rested,

    /** The carrying path answered while the move a rebind asked for was open: the move is given up. */
    GaveUp,
}

/** Whether the open move is one a rebind asked for, and so the rebind's to give up. */
private enum class Owner { Monitor, Rebind }

/** Whether a move was open at the last look, so its end is told to the edge once. */
private enum class Busy { No, Yes }

/**
 * The rebinds a client's silent path asks for. The edge asks once the path goes silent ([asked]), naming the address the
 * route leaves from; the path's news is read again first, so evidence the connection has since answered is not acted
 * on. While the path stays silent the ask is repeated on a ladder whose rungs wait [FIRST_WAIT] doubling to
 * [LONGEST_WAIT]; the ladder is kept when the path answers, so the next silence waits out what is left of the last
 * rung, and only network news, or a move the monitor asked for, starts it again at the first wait. Nothing more is
 * asked while a move is open ([moving]), which it is from the ask on. The carrying path answering while a
 * rebind's move is still open gives that move up: it was loss, not a dead path, and migrating would only restart the
 * path's recovery. Every ask goes through [MoveHold.moveNow], the one way a client's socket moves; a move the
 * connection cannot make leaves it on the socket it has. Each move that ends, landed or not, is told to the edge
 * ([HomeCell.settled]), which holds no guess of where the socket is. Inside the connection only.
 */
@Confined
internal class Rebinds(
    private val q: QuicheConnRef,
    private val silence: PathSilence,
    private val hold: MoveHold,
    private val moving: () -> Moving,
    private val carrying: () -> PathKey,
    private val giveUp: () -> Unit,
) {
    private var ladder: Ladder = Ladder.Fresh
    private var owner = Owner.Monitor
    private var busy = Busy.No

    /** The edge read the path silent and asks at [now] for a fresh port on [bind]'s address, assumed [prior]. */
    fun asked(
        now: Nanos,
        bind: PathKey,
        prior: RttPrior,
        gate: MoveGate,
    ): Rebound {
        if (silence.now() is PathNews.Answering) {
            ladder = rest(ladder)
            return Rebound.Stale.also { unmoved() }
        }
        val owed =
            when (val was = ladder) {
                is Ladder.Fresh -> Ladder.Owed(bind, prior, now, FIRST_WAIT)
                is Ladder.Rested -> Ladder.Owed(bind, prior, later(was.due, now), decayed(was.gap, now.since(was.due)))
                is Ladder.Owed -> Ladder.Owed(bind, prior, was.due, was.gap)
            }
        ladder = owed
        if (inFlight() == Busy.Yes) return Rebound.Served
        if (now < owed.due) return Rebound.Held.also { unmoved() }
        return fire(now, owed, gate)
    }

    /**
     * Each serve at [now]: a move that ended is told to the edge; the path answering rests the ladder, and gives up a
     * rebind's move that is still open, as the silence it answered is gone; a rung due while the path is silent and
     * no move is open is asked, and the next one waits twice as long.
     */
    fun step(
        now: Nanos,
        gate: MoveGate,
    ): Rebound {
        settle()
        if (owner == Owner.Rebind && moving() == Moving.Open && silence.now() is PathNews.Answering) {
            // The carrying path proved the rebind needless: the next one waits twice as long again.
            ladder = wasted(ladder)
            giveUp()
            settle()
            return Rebound.GaveUp
        }
        val owed =
            when (val was = ladder) {
                is Ladder.Fresh, is Ladder.Rested -> return Rebound.Rested
                is Ladder.Owed -> was
            }
        if (silence.now() is PathNews.Answering) {
            ladder = rest(owed)
            return Rebound.Rested.also { unmoved() }
        }
        if (inFlight() == Busy.Yes || now < owed.due) return Rebound.Rested
        return fire(now, owed, gate)
    }

    /** The connection asked its loop for a second socket, whoever wanted it: a move is open. */
    fun opening() {
        busy = Busy.Yes
    }

    /** The loop answered an open with its second socket, followed or declined, or refused it. */
    fun answered() = settle()

    /** The connection is closing: nothing more is owed, asked or woken for. */
    fun stop() {
        ladder = rest(ladder)
        owner = Owner.Monitor
    }

    /**
     * News at [now] that a rebind may now go elsewhere, or succeed where it failed: the ladder starts again at its first
     * wait, toward [bind]'s address when the news names one.
     */
    fun renewed(
        now: Nanos,
        bind: Rebind,
    ) {
        // A move the monitor asked for replaces the rebind's, and is not the rebind's to give up.
        if (bind is Rebind.To) owner = Owner.Monitor
        ladder =
            when (val was = ladder) {
                is Ladder.Fresh, is Ladder.Rested -> Ladder.Fresh
                is Ladder.Owed ->
                    when (bind) {
                        is Rebind.Same -> Ladder.Owed(was.bind, was.prior, now + FIRST_WAIT.nanos, Span(FIRST_WAIT.nanos * 2))
                        is Rebind.To -> Ladder.Owed(bind.bind, bind.prior, now + FIRST_WAIT.nanos, Span(FIRST_WAIT.nanos * 2))
                    }
            }
    }

    /** Offers the time the next rung falls due, only while it could be asked: the path silent, no move open. */
    fun offerWake(sink: WakeSink) {
        val owed =
            when (val was = ladder) {
                is Ladder.Fresh, is Ladder.Rested -> return
                is Ladder.Owed -> was
            }
        if (silence.now() is PathNews.Silent && inFlight() == Busy.No) sink.offer(owed.due)
    }

    private fun inFlight(): Busy = if (moving() == Moving.Open) Busy.Yes else Busy.No

    /**
     * An ask that opened nothing (the path answered first, the rung is not due, the peer forbids a move, or the
     * connection cannot make one yet): the edge is told where the socket is, so it awaits no move that will not come.
     */
    private fun unmoved() {
        if (inFlight() == Busy.No) silence.home.settled(carrying())
    }

    /** A move that was open and is now ended, landed or not: the edge is told where the socket is. */
    private fun settle() {
        val now = inFlight()
        if (busy == Busy.Yes && now == Busy.No) {
            owner = Owner.Monitor
            silence.home.settled(carrying())
        }
        busy = now
    }

    /** The rung of [owed] is asked at [now]; once asked, the next falls due its gap later and waits twice as long after it. */
    private fun fire(
        now: Nanos,
        owed: Ladder.Owed,
        gate: MoveGate,
    ): Rebound {
        when (q.api.connPeerMigrationPermission(q.handle)) {
            is PeerMigrationPermission.Permitted -> Unit
            is PeerMigrationPermission.NotYetNegotiated -> return deferred(now, owed)
            is PeerMigrationPermission.Forbidden -> {
                // Asked once per silence: the peer's answer does not change while the connection lives.
                ladder = rest(owed)
                return Rebound.Forbidden.also { unmoved() }
            }
        }
        if (gate == MoveGate.Shut || q.api.connAvailableDcids(q.handle) == 0L) return deferred(now, owed)
        ladder = Ladder.Owed(owed.bind, owed.prior, now + owed.gap.nanos, Span(minOf(owed.gap.nanos * 2, LONGEST_WAIT.nanos)))
        hold.moveNow(owed.bind, owed.prior)
        owner = Owner.Rebind
        return Rebound.Asked
    }

    /** A rung that could not be asked yet: tried again a first wait later, the ladder not climbed. */
    private fun deferred(
        now: Nanos,
        owed: Ladder.Owed,
    ): Rebound {
        ladder = Ladder.Owed(owed.bind, owed.prior, now + FIRST_WAIT.nanos, owed.gap)
        unmoved()
        return Rebound.Deferred
    }

    companion object {
        /** The ladder's first wait: 250 ms. */
        val FIRST_WAIT = Span(250_000_000L)

        /** The ladder's longest wait: 60 s. */
        val LONGEST_WAIT = Span(60_000_000_000L)
    }
}
