package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.Ending
import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.core.life.LifeStep
import com.ditchoom.socket.quic.core.life.Tier
import com.ditchoom.socket.quic.core.life.Tiers
import com.ditchoom.socket.quic.core.life.errorCode
import com.ditchoom.socket.quic.core.life.flightCode
import com.ditchoom.socket.quic.core.path.SinglePath
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
    private val path: SinglePath =
        when (val p = c.slots.paths) {
            is Paths.Single -> p.path
        }

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
    ): Collected {
        try {
            c.inbox.drain(runWork)
            if (c.life.view() is Life.Ended) return last(now, out)
            val collected = step(now, out)
            when (collected) {
                Collected.Everything -> rearm(now, fire)
                Collected.MoreWaiting, Collected.Abandoned -> Unit
            }
            c.flight.toTrace()
            return collected
        } catch (failure: Exception) {
            fail(now, failure)
            return last(now, out)
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
    fun drain() {
        c.inbox.drain(runWork)
    }

    /** Inside, as a caller with no lane: runs the queued work only; the loop writes what it leaves. */
    fun drainAsCaller(now: Nanos): Collected =
        try {
            c.inbox.drain(runWork)
            Collected.Everything
        } catch (thrown: Throwable) {
            abandon(now, thrown)
            Collected.Abandoned
        }

    /** Inside: quiche reads a datagram at the start of [bytes]. */
    fun receive(
        bytes: NativeBytes,
        length: Int,
        now: Nanos,
    ) {
        if (c.life.view() is Life.Ended) return
        try {
            c.ingress.direct(bytes, length, path, now, c.flight)
        } catch (failure: Exception) {
            fail(now, failure)
        }
    }

    /** Loop thread, with a caller inside: the datagram is copied into work for that caller. */
    fun copy(
        bytes: NativeBytes,
        length: Int,
    ): Copy = c.ingress.copy(bytes, length)

    /** Inside: the connection's deadline fell due. */
    fun expire() {
        if (c.life.view() is Life.Open) c.timer.expire()
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

    fun dump(sink: TraceSink) = c.flight.dump(sink)

    /**
     * Inside: the connection ends with [end], once. Queued work is told the end, waiting operations fail with it, the
     * sink hears it, and the deadline is disarmed.
     */
    fun finish(
        now: Nanos,
        end: ConnEnd,
    ) {
        if (c.life.end(end) == Ending.Already) return
        c.timer.disarm()
        c.flight.ended(now, end.flightCode(), end.errorCode())
        c.inbox.shut(end) { it.gone(end) }
        c.streams.endAll(end)
        c.sink.ended(end)
        c.flight.toTrace()
    }

    /** Inside for good, nobody else can enter: frees quiche's memory. */
    fun free(now: Nanos) {
        finish(now, ConnEnd.Released)
        c.release.free(path)
    }

    private fun step(
        now: Nanos,
        out: SendBatch,
    ): Collected {
        when (c.handshake.step()) {
            HandshakeStep.Handshaking -> Unit
            HandshakeStep.JustEstablished -> {
                c.sink.established(c.handshake.report(now))
                streams()
            }
            HandshakeStep.Established -> streams()
        }
        when (c.life.observe()) {
            LifeStep.Open -> Unit
            LifeStep.Closed -> {
                finish(now, c.network.endOf(c.life.quicheEnd()))
                return last(now, out)
            }
        }
        val wrote =
            when (val pacing = c.slots.pacing) {
                is Pacing.Unpaced -> c.egress.write(out, path, now, c.flight)
                is Pacing.ByQuiche -> c.egress.writePaced(out, path, now, pacing.pacer, c.flight)
            }
        return when (wrote) {
            Wrote.All -> Collected.Everything
            Wrote.BatchFull -> Collected.MoreWaiting
        }
    }

    private fun streams() {
        c.streams.wake()
        while (c.streams.hasIncoming()) c.sink.incoming(c.streams.takeIncoming())
    }

    private fun rearm(
        now: Nanos,
        fire: Runnable,
    ) {
        when (val pacing = c.slots.pacing) {
            is Pacing.Unpaced -> Unit
            is Pacing.ByQuiche -> pacing.pacer.offerWake(c.timer.sink)
        }
        c.timer.rearm(now, fire)
    }

    /** What quiche still has to say on the wire, such as the CONNECTION_CLOSE of a failed connection. */
    private fun last(
        now: Nanos,
        out: SendBatch,
    ): Collected {
        try {
            c.egress.write(out, path, now, c.flight)
        } catch (late: Exception) {
            // quiche failed again while the connection is already ending: nothing more can go out.
        }
        c.flight.toTrace()
        return Collected.Everything
    }

    private fun runOne(work: ConnWork) {
        when (val life = c.life.view()) {
            is Life.Ended -> work.gone(life.end)
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
        if (c.life.view() is Life.Ended) return
        try {
            c.life.close(QuicError.ProcessingFailed)
        } catch (late: Exception) {
            failure.addSuppressed(late)
        }
        finish(now, ConnEnd.ProcessingFailed(Thrown(failure)))
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
