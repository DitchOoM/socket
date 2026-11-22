package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.DeadlineNode
import com.ditchoom.socket.quic.core.life.CloseChoice
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.sched.LoopWork
import com.ditchoom.socket.quic.core.time.Nanos
import kotlinx.coroutines.Runnable

/** Where a server stands: taking connections, closing the ones it has, or done. */
internal enum class ServerPhase { Running, Draining, Stopped }

/** What a server's pass leads to. */
internal enum class ServerStep {
    Serving,

    /** Every connection's CONNECTION_CLOSE is out: end them and stop. */
    Finish,

    /** The drain bound passed with closes not out: end every connection, naming the unsettled ones, and stop. */
    FinishAtBound,
}

private sealed interface Phase {
    data object Running : Phase

    /** Connections were told to close; the server stops by [until] at the latest, when [wake] wakes the loop. */
    class Draining(
        val until: Nanos,
        val wake: DeadlineNode,
    ) : Phase

    data object Stopped : Phase
}

/**
 * A server's shutdown order: stop taking connections and tell each to close as [close] says, and stop once every
 * CONNECTION_CLOSE is out of whoever was inside its connection, or at the drain bound when one is not: the close's
 * limit, and [SETTLE_MARGIN] for a thread inside that stalls. Nothing polls: a caller that sends a close in place wakes
 * the loop, and a deadline wakes it at the bound. A sub-part of [Demux]; loop thread only.
 */
@LoopOnly
internal class ServerLife(
    close: CloseChoice,
) {
    private val limit =
        when (close) {
            is CloseChoice.Graceful -> close.limitNanos
            is CloseChoice.Immediate -> 0L
        }
    private val atBound = Runnable { }
    private var phase: Phase = Phase.Running

    fun view(): ServerPhase =
        when (phase) {
            is Phase.Running -> ServerPhase.Running
            is Phase.Draining -> ServerPhase.Draining
            is Phase.Stopped -> ServerPhase.Stopped
        }

    /** Starts closing at [now] and arms the bound on [port]; a server already closing or stopped is left as it is. */
    fun begin(
        now: Nanos,
        port: LoopPort,
    ): ServerPhase {
        if (phase !is Phase.Running) return view()
        val until = now + limit + SETTLE_MARGIN
        phase = Phase.Draining(until, port.arm(until, atBound))
        return ServerPhase.Running
    }

    /** After a pass at [now] collected every connection's output, with [unsettled] closes not yet out. */
    fun step(
        now: Nanos,
        unsettled: Int,
    ): ServerStep =
        when (val at = phase) {
            is Phase.Draining ->
                when {
                    unsettled == 0 -> ServerStep.Finish
                    now >= at.until -> ServerStep.FinishAtBound
                    else -> ServerStep.Serving
                }
            is Phase.Running, is Phase.Stopped -> ServerStep.Serving
        }

    fun stopped() {
        when (val at = phase) {
            is Phase.Draining -> at.wake.disarm()
            is Phase.Running, is Phase.Stopped -> Unit
        }
        phase = Phase.Stopped
    }

    private companion object {
        /**
         * How long past its limit a server waits for a connection's close. A close goes out in the pass its limit or
         * its delivery falls in, or as soon as the caller inside leaves; the margin matters only when a thread inside
         * a connection stalls. A second covers a descheduled thread on a loaded device.
         */
        const val SETTLE_MARGIN = 1_000_000_000L
    }
}

/** Whether a pass is already owed. */
private enum class Owed { No, Yes }

/** Loop work that does nothing but make the loop pass again. One per server: a work item sits in one inbox at a time. */
@LoopOnly
internal class PassTick : LoopWork() {
    private var owed = Owed.No

    /** Loop thread: the loop passes again soon, whether or not anything else is due. */
    fun owe(port: LoopPort) {
        if (owed == Owed.Yes) return
        owed = Owed.Yes
        port.submit(this)
    }

    override fun run(port: LoopPort) {
        owed = Owed.No
    }

    override fun gone(end: ConnEnd) {
        owed = Owed.No
    }
}
