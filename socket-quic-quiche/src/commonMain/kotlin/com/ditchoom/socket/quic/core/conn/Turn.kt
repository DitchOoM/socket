package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.life.CloseHow
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.Ending
import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.core.life.LifeStep
import com.ditchoom.socket.quic.core.life.RetiredId
import com.ditchoom.socket.quic.core.life.SpareId
import com.ditchoom.socket.quic.core.life.Tier
import com.ditchoom.socket.quic.core.life.Tiers
import com.ditchoom.socket.quic.core.life.endOf
import com.ditchoom.socket.quic.core.life.errorCode
import com.ditchoom.socket.quic.core.life.flightCode
import com.ditchoom.socket.quic.core.observe.TraceSection
import com.ditchoom.socket.quic.core.observe.traced
import com.ditchoom.socket.quic.core.path.MoveAnswer
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.sched.Pushed
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.Thrown
import com.ditchoom.socket.quic.reportOnThisThread
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
    private val inside = Inside(c.streams, c.life, clock, port, this)
    private val runWork: (ConnWork) -> Unit = { runOne(it) }
    private val paths: Paths = c.slots.paths

    fun push(work: ConnWork): Pushed = c.inbox.push(work)

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
                is Life.Open -> if (collected == Collected.Everything) c.rearm(now, fire)
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
        if (c.life.view() is Life.Over) return c.life.linger.late()
        traced(TraceSection.ConnReceive) {
            try {
                c.feed(bytes, length, from, now)
            } catch (failure: Exception) {
                fail(now, failure)
            }
        }
    }

    /** Loop thread, with a caller inside: the datagram is copied into work for that caller. */
    fun copy(
        bytes: NativeBytes,
        length: Int,
        from: PathKey,
    ): Copy = c.ingress.copy(bytes, length, from)

    /** Inside: the connection's deadline fell due. While unconfirmed, the handshake follows quiche's PTO expiries. */
    fun expire() {
        if (c.life.view() is Life.Open) c.expired(paths)
    }

    fun network(
        now: Nanos,
        change: NetworkChange,
    ) {
        c.flight.network(now, change.ordinal)
        when (c.network.changed(change)) {
            NetworkStep.Nudge -> c.timer.nudge()
            NetworkStep.Wait -> Unit
            NetworkStep.End -> finish(now, ConnEnd.LocalAddressGone)
        }
    }

    /** Inside: a client's socket now leaves from [local]; a connection that cannot follow it ends ([follow]). */
    fun moved(
        now: Nanos,
        local: PathKey,
    ) = when (c.follow(now, local)) {
        MoveAnswer.Following -> Unit
        MoveAnswer.Refused -> finish(now, ConnEnd.LocalAddressGone)
    }

    /** Inside: the ids the loop routed to this server connection go to quiche. */
    fun issue(spare: List<SpareId>) = c.slots.paths.issue(spare)

    /** Any thread: how many new ids the loop should route to this connection, taken once. */
    fun idsWanted(): Int = c.slots.paths.idsWanted()

    /** Any thread: the ids this connection's peer retired, taken once. */
    fun idsRetired(): List<RetiredId> = c.slots.paths.idsRetired()

    fun dump(sink: TraceSink) = c.flight.dump(sink)

    /**
     * Inside: the connection ends with [end], once. The deadline is disarmed and the sink publishes the end first, so a
     * caller resumed by it can read how the connection ended; then queued work is told, and waiting operations fail.
     */
    fun finish(
        now: Nanos,
        end: ConnEnd,
    ) {
        if (c.life.end(end) == Ending.Already) return
        c.timer.disarm()
        c.flight.ended(now, end.flightCode(), end.errorCode())
        c.flight.keepEnded(c.role)
        c.sink.ended(end)
        c.inbox.shut(end) { it.gone(end) }
        c.streams.endAll(end)
        c.flight.toTrace()
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
        when (c.handshake.step(now)) {
            HandshakeStep.Handshaking -> Unit
            HandshakeStep.JustEstablished -> {
                paths.established()
                c.sink.established(c.handshake.report(now, paths.read()))
                c.wakeStreams()
            }
            HandshakeStep.Established -> c.wakeStreams()
            HandshakeStep.DeadlinePassed -> {
                val end = ConnEnd.HandshakeTimedOut(c.handshake.progress(now, paths.read()))
                c.life.close(QuicError.NoError, end, CloseHow.Now, now)
                finish(now, end)
                return Collected.Everything
            }
        }
        c.life.flushStep(now)
        when (c.life.observe()) {
            LifeStep.Open -> Unit
            LifeStep.Closed -> {
                finish(now, paths.endOf(c.network.endOf(c.life.quicheEnd())))
                return Collected.Everything
            }
        }
        c.stepPaths(now)
        val wrote =
            when (val pacing = c.slots.pacing) {
                is Pacing.Unpaced -> c.egress.write(out, paths, now, c.flight)
                is Pacing.ByQuiche -> c.egress.writePaced(out, paths, now, pacing.pacer, c.flight)
            }
        if (c.life.linger.sending() && c.keepClose(now, out) == CloseSent.Yes) {
            when (val life = c.life.view()) {
                is Life.Closing -> finish(now, life.end)
                is Life.Running, is Life.Over -> Unit
            }
        }
        return when (wrote) {
            Wrote.All -> Collected.Everything
            Wrote.BatchFull -> Collected.MoreWaiting
        }
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
    private fun fail(
        now: Nanos,
        failure: Throwable,
    ) {
        if (c.life.view() is Life.Over) return
        val end = ConnEnd.ProcessingFailed(Thrown(failure))
        try {
            c.life.close(QuicError.ProcessingFailed, end, CloseHow.Now, now)
        } catch (late: Exception) {
            failure.addSuppressed(late)
        }
        finish(now, end)
    }

    /** A caller's thread threw inside: the connection ends with it, and what it cannot contain is reported. */
    private fun abandon(
        now: Nanos,
        thrown: Throwable,
    ) {
        when (Tiers.of(thrown)) {
            Tier.Connection -> fail(now, thrown)
            Tier.Process -> {
                finish(now, ConnEnd.CallerFailed(Thrown(thrown)))
                reportOnThisThread(thrown)
            }
        }
    }
}
