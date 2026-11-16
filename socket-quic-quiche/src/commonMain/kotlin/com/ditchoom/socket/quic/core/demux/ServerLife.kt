package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.sched.LoopWork

/** Where a server stands: taking connections, closing the ones it has, or done. */
internal enum class ServerPhase { Running, Draining, Stopped }

/** What a server's pass leads to. */
internal enum class ServerStep {
    Serving,

    /** Every connection was told to close in an earlier pass and its output collected: end them and stop. */
    Finish,
}

private sealed interface Phase {
    data object Running : Phase

    /** Connections were told to close in the pass numbered [since]. */
    class Draining : Phase {
        var since = 0L
    }

    data object Stopped : Phase
}

/**
 * A server's shutdown order: stop taking connections and tell each to close, let one more pass send their
 * CONNECTION_CLOSE, then end what is left and stop. A sub-part of [Demux]; loop thread only.
 */
@LoopOnly
internal class ServerLife {
    private val draining = Phase.Draining()
    private var phase: Phase = Phase.Running

    fun view(): ServerPhase =
        when (phase) {
            is Phase.Running -> ServerPhase.Running
            is Phase.Draining -> ServerPhase.Draining
            is Phase.Stopped -> ServerPhase.Stopped
        }

    /** Starts closing in pass [pass]; a server already closing or stopped is left as it is. */
    fun begin(pass: Long): ServerPhase {
        if (phase !is Phase.Running) return view()
        draining.since = pass
        phase = draining
        return ServerPhase.Running
    }

    /** After pass [pass] collected every connection's output. */
    fun step(pass: Long): ServerStep =
        when (val now = phase) {
            is Phase.Draining -> if (pass > now.since) ServerStep.Finish else ServerStep.Serving
            is Phase.Running, is Phase.Stopped -> ServerStep.Serving
        }

    fun stopped() {
        phase = Phase.Stopped
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
