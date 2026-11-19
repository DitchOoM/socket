package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.DeadlineNode
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.sched.Closer
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.sched.SendPort
import com.ditchoom.socket.quic.core.sched.Shutdown
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import kotlinx.coroutines.Runnable

/** Where an ended connection's socket stands with the lanes still out of it. */
private sealed interface LaneWait {
    /** The socket has not been asked to close. */
    data object NotAsked : LaneWait

    /** Lanes were out when it was asked; at [at] they are cut, and [wake] wakes the loop then. */
    class Until(
        val at: Nanos,
        val wake: DeadlineNode,
    ) : LaneWait

    /** The lanes still out were cut: none reaches the socket again. */
    data object Cut : LaneWait
}

/**
 * The close of an endpoint's socket. While the loop runs, the socket closes once nothing but the loop can send on it:
 * when every lane is back, or [LANE_BOUND] after the first ask, when the lanes still out are cut and it closes as soon
 * as no thread is inside a send. Once the loop stopped, the last thread out closes it. A sub-part of [Endpoint].
 */
@LoopOnly
internal class SocketClose(
    private val io: DatagramBatchIo,
    private val socket: SendPort,
) {
    private var lanes: LaneWait = LaneWait.NotAsked
    private val wakeAtBound = Runnable { }

    /**
     * The loop asks at [now]; on [Shutdown.Quiet] the caller lets go of the socket and then calls [close], and on
     * [Shutdown.Closed] it only lets go: whoever closed it did.
     */
    fun ask(
        now: Nanos,
        port: LoopPort,
    ): Shutdown =
        when (val wait = lanes) {
            is LaneWait.NotAsked -> {
                val first = socket.shut(Closer.Loop)
                when (first) {
                    is Shutdown.LanesOut, is Shutdown.Sending ->
                        lanes = LaneWait.Until(now + LANE_BOUND, port.arm(now + LANE_BOUND, wakeAtBound))
                    is Shutdown.Quiet, is Shutdown.Closed -> Unit
                }
                first
            }
            is LaneWait.Until ->
                if (now >= wait.at) {
                    lanes = LaneWait.Cut
                    socket.cut(Closer.Loop)
                } else {
                    socket.shut(Closer.Loop)
                }
            is LaneWait.Cut -> socket.shut(Closer.Loop)
        }

    /** After [ask] answered [Shutdown.Quiet]: nothing else can send, so the socket is closed. */
    fun close() {
        disarm()
        io.close()
    }

    /** The loop stopped: the socket closes now, or the last thread out closes it. */
    fun stopped() {
        disarm()
        when (socket.shut(Closer.LastLane)) {
            is Shutdown.Quiet -> io.close()
            is Shutdown.LanesOut, is Shutdown.Sending, is Shutdown.Closed -> Unit
        }
    }

    private fun disarm() {
        when (val wait = lanes) {
            is LaneWait.Until -> wait.wake.disarm()
            is LaneWait.NotAsked, is LaneWait.Cut -> Unit
        }
    }

    private companion object {
        /**
         * How long an ended connection's socket waits for lanes still out before cutting them. A lane out this long
         * belongs to a thread that already overran a server's drain bound, or that stalled inside the connection; a
         * second more lets a descheduled thread send what it holds, and keeps a closing socket's life within two
         * seconds of its server's shutdown, inside its peers' idle timeouts.
         */
        const val LANE_BOUND = 1_000_000_000L
    }
}
