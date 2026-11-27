package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.DeadlineFired
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicStatsRead
import com.ditchoom.socket.quic.Rearmable
import com.ditchoom.socket.quic.core.life.CloseChoice
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.ConnLife
import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.core.life.SpareId
import com.ditchoom.socket.quic.core.path.Rebind
import com.ditchoom.socket.quic.core.path.Want
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.stream.StreamTable
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.SocketTicket
import com.ditchoom.socket.testkit.trace.TraceSink

/** What work running inside a connection may reach. One per connection, handed only to work that is inside. */
internal class Inside(
    private val c: ConnParts,
    private val clock: LoopClock,
    private val port: LoopPort,
    private val turn: Turn,
) {
    val streams: StreamTable = c.streams
    private val life: ConnLife = c.life
    val datagrams: Datagrams = c.slots.datagrams

    fun now(): Nanos = clock.now()

    fun life(): Life = life.view()

    /**
     * The close point of a close with [error]: with no error it is the connection's [ConnLife.noError] choice, else
     * immediate.
     */
    fun close(error: QuicError) =
        c.close(if (error is QuicError.NoError) life.noError else CloseChoice.Immediate(error), ConnEnd.ByLocal(error), clock.now())

    /** The close point of a close that asks for [choice]. */
    fun close(choice: CloseChoice) = c.close(choice, ConnEnd.ByLocal(errorOf(choice)), clock.now())

    /** The close point of the close its server asks for, as the connection's [ConnLife.noError] choice says. */
    fun closeForServer() = c.close(life.noError, ConnEnd.ServerClosed, clock.now())

    /**
     * A deadline on the connection's loop that runs [action] when an arming falls due; made once, re-armed in place.
     */
    fun rearmable(action: DeadlineFired): Rearmable = port.rearmable(action)

    /** Arms [deadline] for [due], in place of what it was armed for; allocates nothing. */
    fun arm(
        deadline: Rearmable,
        due: Nanos,
    ) = port.arm(deadline, due)

    /** Ends the connection with [end], now. */
    fun end(end: ConnEnd) = turn.finish(clock.now(), end)

    fun network(change: NetworkChange) = turn.network(clock.now(), change)

    /** The client's socket is wanted on [wanted]'s address: it moves once the path from there can be probed. */
    fun moveWanted(wanted: MoveWanted) {
        c.renewRebinds(clock.now(), Rebind.To(wanted.bind, wanted.prior))
        when (c.want(clock.now(), wanted)) {
            Want.Moving, Want.Waiting -> Unit
            Want.NoUsablePath -> end(ConnEnd.NoUsablePath)
        }
    }

    /** The client's path was read silent: its socket is wanted on a fresh port of [wanted]'s address, if still silent. */
    fun rebind(wanted: MoveWanted) = c.rebind(clock.now(), wanted)

    /** The network refused the first of the connection's datagrams it was handed: a handshake it denies ends now. */
    fun sendsRefused() =
        when (val verdict = c.accessStep()) {
            is AccessVerdict.NotDenied -> Unit
            is AccessVerdict.Denied -> {
                val denied = ConnEnd.AccessDenied(verdict.denial)
                c.closeHandshake(denied, clock.now())
                end(denied)
            }
        }

    /** The client's loop refused the move it was asked for, as [why] says: the connection stays on its socket. */
    fun moveRefused(why: MoveRefusal) {
        c.openRefused()
        c.flight.path(
            clock.now(),
            when (why) {
                is MoveRefusal.Unopened -> PathMark.OpenRefused
                is MoveRefusal.SocketLent -> PathMark.LentRefused
            },
        )
    }

    /** The client's loop lost the second socket [ticket] names: the move it served ends, the connection stays. */
    fun secondLost(ticket: SocketTicket) = c.secondLost(clock.now(), ticket)

    /** The client's loop opened a second socket, which leaves from [to]. */
    fun moved(to: MoveTo) {
        turn.moved(clock.now(), to)
        c.openAnswered()
    }

    /** A client's first flight, held for its 0-RTT, may leave. */
    fun releaseFirstFlight() = turn.releaseFirstFlight()

    /** The ids the loop routed to this server connection. */
    fun issue(spare: List<SpareId>) = turn.issue(spare)

    /** A datagram that was copied because it arrived, from [from] on socket [on], while a caller was inside. */
    fun receiveCopy(
        bytes: NativeBytes,
        length: Int,
        from: PathKey,
        on: SocketTicket,
    ) {
        try {
            turn.receive(bytes, length, from, clock.now(), on)
        } finally {
            c.ingress.copyRead()
        }
    }

    /** Writes the connection's flight recording into [sink], in the trace grammar. */
    fun dump(sink: TraceSink) = turn.dump(sink)

    /** A client's server passed its check after the handshake: output held for it leaves. */
    fun serverChecked() = c.serverChecked()

    /** The connection's statistics, read now. */
    fun stats(): QuicStatsRead =
        c.slots.paths
            .stats()
            .also { c.slots.datagrams.counted(it) }

    /** What the connection waits on, seen now. */
    fun stallView(): StallView {
        val now = clock.now()
        return StallView(
            quiche = c.timer.quicheWait(),
            deadline = c.timer.armed(now),
            liveStreams = c.streams.liveStreams(),
            opensWaiting = c.streams.opensWaiting(),
            stats = stats(),
            flight = c.flight.tail(now),
        )
    }
}

/** The error the CONNECTION_CLOSE of [choice] carries. */
private fun errorOf(choice: CloseChoice): QuicError =
    when (choice) {
        is CloseChoice.Graceful -> QuicError.NoError
        is CloseChoice.Immediate -> choice.error
    }
