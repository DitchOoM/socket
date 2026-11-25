package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.life.CloseChoice
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.Ending
import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.core.life.LifeStep
import com.ditchoom.socket.quic.core.life.RetiredId
import com.ditchoom.socket.quic.core.life.SpareId
import com.ditchoom.socket.quic.core.life.endOf
import com.ditchoom.socket.quic.core.observe.TraceSection
import com.ditchoom.socket.quic.core.observe.traced
import com.ditchoom.socket.quic.core.path.MoveAnswer
import com.ditchoom.socket.quic.core.path.PathStep
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.sched.Pushed
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.Thrown
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.Runnable

/** What a serve left: everything collected, a batch that filled with more waiting, or a caller that had to leave. */
internal enum class Collected { Everything, MoreWaiting, Abandoned }

/**
 * The order of one serve, and the connection-tier containment site: an exception thrown by work or by a step ends
 * this connection and no other; an `Error` is not contained. Holds no state: it asks each component and passes the
 * answers on.
 */
internal class Turn(
    private val c: ConnParts,
    clock: LoopClock,
    port: LoopPort,
) {
    private val inside = Inside(c, clock, port, this)
    private val runWork: (ConnWork) -> Unit = { runOne(it) }
    private val paths: Paths = c.slots.paths

    fun push(work: ConnWork): Pushed = c.inbox.push(work)

    /** Makes the connection's deadline with [fire], before it is ever armed. */
    fun makeTimer(fire: Runnable) = c.timer.make(fire)

    fun requestRelease(then: Runnable) = c.release.request(then)

    /** Any thread: the connection's life as published. */
    fun life(): Life = c.life.view()

    fun releaseAsked(): ReleaseAsk = c.release.asked()

    /** Inside: runs queued work, serves the connection and writes its output into [out]. An `Error` propagates. */
    fun serve(
        now: Nanos,
        out: SendBatch,
        fire: Runnable,
    ): Collected = traced(TraceSection.ConnWork) { serveInside(now, out, fire) }

    private fun serveInside(
        now: Nanos,
        out: SendBatch,
        fire: Runnable,
    ): Collected {
        try {
            c.inbox.drain(runWork)
            when (c.life.view()) {
                is Life.Open -> Unit
                is Life.Over -> return over(now, out, fire)
            }
            val collected = step(now, out)
            when (c.life.view()) {
                is Life.Open -> c.served(now, paths, collected, fire)
                is Life.Over -> return over(now, out, fire)
            }
            c.flight.toTrace()
            return collected
        } catch (failure: Exception) {
            fail(now, failure)
            return over(now, out, fire)
        }
    }

    /** [serve], for a caller's thread: a thrown object it cannot contain ends the connection and is reported here. */
    fun serveAsCaller(
        now: Nanos,
        out: SendBatch,
        fire: Runnable,
    ): Collected =
        try {
            serve(now, out, fire)
        } catch (thrown: Throwable) {
            abandon(now, thrown)
            Collected.Abandoned
        }

    /** Inside, as the loop, with nothing to send: runs the queued work only. */
    fun drain() = c.inbox.drain(runWork)

    /** Inside, as a caller with no lane: runs the queued work only; the loop writes what it leaves. */
    fun drainAsCaller(now: Nanos): Collected =
        try {
            c.inbox.drain(runWork)
            Collected.Everything
        } catch (thrown: Throwable) {
            abandon(now, thrown)
            Collected.Abandoned
        }

    /** Inside: quiche reads a datagram at the start of [bytes] that came [from]; one after the end is only counted. */
    fun receive(
        bytes: NativeBytes,
        length: Int,
        from: PathKey,
        now: Nanos,
    ) {
        if (c.life.view() is Life.Over) {
            c.flight.datagramIn(now, bytes, 0, length)
            return c.life.linger.late(length)
        }
        traced(TraceSection.ConnReceive) {
            try {
                c.feed(bytes, length, from)
                c.slots.keepAlive.touched(now)
                c.slots.datagrams.received(now)
            } catch (failure: Exception) {
                fail(now, failure)
            }
        }
    }

    /** The loop's way in for datagrams: whether one goes to quiche now or is copied into work, and the copy. */
    fun ingress(): Ingress = c.ingress

    /**
     * Inside, at [now]: the connection's deadline fell due. While unconfirmed, the handshake counts quiche's PTO expiries.
     * The recording takes [now]: a replay runs quiche's timeout when this host did, however late it woke.
     */
    fun expire(now: Nanos) {
        if (c.life.view() !is Life.Open) return
        c.expired(now, paths)
    }

    fun network(
        now: Nanos,
        change: NetworkChange,
    ) {
        c.flight.network(now, change.ordinal)
        when (c.network.changed(change)) {
            NetworkStep.Nudge -> c.networkUsable(now)
            NetworkStep.Wait -> Unit
            NetworkStep.End -> finish(now, ConnEnd.LocalAddressGone)
        }
    }

    /** Inside: a client's socket now leaves from [to]; a connection that cannot follow it ends ([follow]). */
    fun moved(
        now: Nanos,
        to: MoveTo,
    ) = when (c.follow(now, to)) {
        MoveAnswer.Following -> Unit
        MoveAnswer.Refused -> finish(now, ConnEnd.LocalAddressGone)
        MoveAnswer.NoUsablePath -> finish(now, ConnEnd.NoUsablePath)
    }

    /** Inside: a client's first flight, held for 0-RTT, may leave. */
    fun releaseFirstFlight() = c.releaseFirstFlight()

    /** Inside: the ids the loop routed to this server connection go to quiche. */
    fun issue(spare: List<SpareId>) = c.slots.paths.issue(spare)

    /** Any thread: how many new ids the loop should route to this connection, taken once. */
    fun idsWanted(): Int = c.slots.paths.idsWanted()

    /** Any thread: the ids this connection's peer retired, taken once. */
    fun idsRetired(): List<RetiredId> = c.slots.paths.idsRetired()

    /** Any thread: the connection's datagrams, for the queue a caller offers sends to. */
    fun datagrams(): Datagrams = c.slots.datagrams

    /** Once the loop is quiet: where the connection's close stands at [now]. */
    fun lingerView(now: Nanos) = LingerView(now, c.life.view(), c.life.linger.wire(), c.timer.armed(now))

    fun dump(sink: TraceSink) = c.flight.dump(sink)

    /** Inside: the connection ends with [end], once ([ending], then [ended]); what quiche held for reads goes with it. */
    fun finish(
        now: Nanos,
        end: ConnEnd,
    ) {
        val first =
            when (val ending = c.ending(now, end)) {
                is Ending.First -> ending
                is Ending.Already -> return
            }
        val held =
            try {
                c.heldAtEnd(HoldAtEnd.ReadOut)
            } catch (again: Exception) {
                c.heldAtEnd(HoldAtEnd.LiveOnly)
            }
        c.ended(end, held, first.outcome)
    }

    /** Inside for good, nobody else can enter: a linger still running is cut, and quiche's memory is freed. */
    fun free(now: Nanos) {
        finish(now, ConnEnd.Released)
        c.life.settle()
        c.release.free(paths)
    }

    /** Serves an open connection: the handshake, quiche's own close, a flush that is over, paths, and output. */
    private fun step(
        now: Nanos,
        out: SendBatch,
    ): Collected {
        when (val served = c.handshakeStep(now, paths)) {
            is HandshakeServe.Open -> Unit
            is HandshakeServe.Over -> {
                val end = c.handshakeEnd(served, now, paths)
                c.closeHandshake(end, now)
                finish(now, end)
                return Collected.Everything
            }
        }
        when (val quiche = c.life.observe()) {
            LifeStep.Open -> Unit
            LifeStep.Draining, LifeStep.Closed -> {
                finish(now, c.quicheEnded(now, quiche, paths))
                return Collected.Everything
            }
        }
        if (c.stepPaths(now) == PathStep.NoUsablePath) {
            finish(now, ConnEnd.NoUsablePath)
            return Collected.Everything
        }
        if (c.firstFlight() == FlightGate.Held) return Collected.Everything
        c.ackStep(now).let { if (it != AckStep.Write) return it.collected() }
        val wrote = c.writeOut(out, paths)
        c.wrote(now)
        if (c.flushStep(now, out, paths) == LifeStep.Closed) {
            finish(now, c.quicheEnded(now, LifeStep.Closed, paths))
            return Collected.Everything
        }
        if (c.life.linger.sending() && c.keepClose(now, out) == CloseSent.Yes) {
            when (val life = c.life.view()) {
                is Life.Closing -> finish(now, endOf(life))
                is Life.Running, is Life.Over -> Unit
            }
        }
        // A clock mismatch or a refused datagram is our own failure: the peer is told with INTERNAL_ERROR, as for any processing failure.
        wrote.ends(c.egress, ours = { fail(now, it) }, refused = { finish(now, c.closeRefused(now)) })
        return wrote.collected()
    }

    /** [over], where quiche may fail again while the connection is already ending: then nothing more goes out. */
    private fun over(
        now: Nanos,
        out: SendBatch,
        fire: Runnable,
    ): Collected =
        try {
            c.over(now, out, paths, fire)
        } catch (late: Exception) {
            c.life.settle()
            c.timer.disarm()
            Collected.Everything
        }

    private fun runOne(work: ConnWork) {
        when (val life = c.life.view()) {
            is Life.Over -> work.gone(endOf(life))
            is Life.Open ->
                try {
                    work.run(inside)
                } catch (failure: Exception) {
                    fail(inside.now(), failure)
                }
        }
    }

    /** Ends the connection because processing it threw [failure]; closes it on the wire where quiche still lets us. */
    internal fun fail(
        now: Nanos,
        failure: Throwable,
    ) {
        if (c.life.view() is Life.Over) return
        val end = ConnEnd.ProcessingFailed(Thrown(failure))
        try {
            c.close(CloseChoice.Immediate(QuicError.ProcessingFailed), end, now)
        } catch (late: Exception) {
            failure.addSuppressed(late)
        }
        finish(now, end)
    }
}
