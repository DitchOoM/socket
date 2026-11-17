package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.DeadlineFired
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.Rearmable
import com.ditchoom.socket.quic.core.life.CloseHow
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.ConnLife
import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.core.life.SpareId
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.stream.StreamTable
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.testkit.trace.TraceSink

/** What work running inside a connection may reach. One per connection, handed only to work that is inside. */
internal class Inside(
    val streams: StreamTable,
    private val life: ConnLife,
    private val clock: LoopClock,
    private val port: LoopPort,
    private val turn: Turn,
) {
    fun now(): Nanos = clock.now()

    fun life(): Life = life.view()

    /** Starts the connection's close with [error]; with no error it flushes first, as the connection's close rule says. */
    fun close(error: QuicError) =
        life.close(error, ConnEnd.ByLocal(error), if (error is QuicError.NoError) CloseHow.Graceful else CloseHow.Now, clock.now())

    /** Starts the close its server asks for, with no error and no flush. */
    fun closeForServer() = life.close(QuicError.NoError, ConnEnd.ServerClosed, CloseHow.Now, clock.now())

    /** A deadline on the connection's loop that runs [action] when an arming falls due; made once, re-armed in place. */
    fun rearmable(action: DeadlineFired): Rearmable = port.rearmable(action)

    /** Arms [deadline] for [due], in place of what it was armed for; allocates nothing. */
    fun arm(
        deadline: Rearmable,
        due: Nanos,
    ) = port.arm(deadline, due)

    /** Ends the connection with [end], now. */
    fun end(end: ConnEnd) = turn.finish(clock.now(), end)

    fun network(change: NetworkChange) = turn.network(clock.now(), change)

    /** The client's socket now leaves from [local]. */
    fun moved(local: PathKey) = turn.moved(clock.now(), local)

    /** The ids the loop routed to this server connection. */
    fun issue(spare: List<SpareId>) = turn.issue(spare)

    /** A datagram that was copied because it arrived, from [from], while a caller was inside. */
    fun receiveCopy(
        bytes: NativeBytes,
        length: Int,
        from: PathKey,
    ) = turn.receive(bytes, length, from, clock.now())

    /** Writes the connection's flight recording into [sink], in the trace grammar. */
    fun dump(sink: TraceSink) = turn.dump(sink)
}
